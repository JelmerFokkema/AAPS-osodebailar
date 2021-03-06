package info.nightscout.androidaps.plugins.treatments;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;

import com.google.firebase.analytics.FirebaseAnalytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import info.nightscout.androidaps.Constants;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.DetailedBolusInfo;
import info.nightscout.androidaps.data.Intervals;
import info.nightscout.androidaps.data.Iob;
import info.nightscout.androidaps.data.IobTotal;
import info.nightscout.androidaps.data.MealData;
import info.nightscout.androidaps.data.NonOverlappingIntervals;
import info.nightscout.androidaps.data.OverlappingIntervals;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.data.ProfileIntervals;
import info.nightscout.androidaps.db.ExtendedBolus;
import info.nightscout.androidaps.db.ProfileSwitch;
import info.nightscout.androidaps.db.TempTarget;
import info.nightscout.androidaps.db.TemporaryBasal;
import info.nightscout.androidaps.events.EventReloadProfileSwitchData;
import info.nightscout.androidaps.events.EventReloadTempBasalData;
import info.nightscout.androidaps.events.EventReloadTreatmentData;
import info.nightscout.androidaps.events.EventTempTargetChange;
import info.nightscout.androidaps.interfaces.InsulinInterface;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PluginDescription;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.interfaces.PumpInterface;
import info.nightscout.androidaps.interfaces.TreatmentsInterface;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.bus.RxBus;
import info.nightscout.androidaps.plugins.configBuilder.ConfigBuilderPlugin;
import info.nightscout.androidaps.plugins.configBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.general.nsclient.NSUpload;
import info.nightscout.androidaps.plugins.general.overview.dialogs.ErrorHelperActivity;
import info.nightscout.androidaps.plugins.general.overview.events.EventDismissNotification;
import info.nightscout.androidaps.plugins.general.overview.notifications.Notification;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.AutosensData;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.AutosensResult;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.IobCobCalculatorPlugin;
import info.nightscout.androidaps.plugins.pump.medtronic.data.MedtronicHistoryData;
import info.nightscout.androidaps.plugins.pump.medtronic.util.MedtronicUtil;
import info.nightscout.androidaps.plugins.sensitivity.SensitivityAAPSPlugin;
import info.nightscout.androidaps.plugins.sensitivity.SensitivityWeightedAveragePlugin;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.FabricPrivacy;
import info.nightscout.androidaps.utils.SP;
import info.nightscout.androidaps.utils.T;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;

/**
 * Created by mike on 05.08.2016.
 */
public class TreatmentsPlugin extends PluginBase implements TreatmentsInterface {
    private Logger log = LoggerFactory.getLogger(L.DATATREATMENTS);

    private CompositeDisposable disposable = new CompositeDisposable();

    private static TreatmentsPlugin treatmentsPlugin;

    public static TreatmentsPlugin getPlugin() {
        if (treatmentsPlugin == null)
            treatmentsPlugin = new TreatmentsPlugin();
        return treatmentsPlugin;
    }

    private TreatmentService service;

    private IobTotal lastTreatmentCalculation;
    private IobTotal lastTempBasalsCalculation;

    private final ArrayList<Treatment> treatments = new ArrayList<>();
    private final Intervals<TemporaryBasal> tempBasals = new NonOverlappingIntervals<>();
    private final Intervals<ExtendedBolus> extendedBoluses = new NonOverlappingIntervals<>();
    private final Intervals<TempTarget> tempTargets = new OverlappingIntervals<>();
    private final ProfileIntervals<ProfileSwitch> profiles = new ProfileIntervals<>();

    public TreatmentsPlugin() {
        super(new PluginDescription()
                .mainType(PluginType.TREATMENT)
                .fragmentClass(TreatmentsFragment.class.getName())
                .pluginName(R.string.treatments)
                .shortName(R.string.treatments_shortname)
                .alwaysEnabled(true)
                .description(R.string.description_treatments)
        );
        this.service = new TreatmentService();
    }

    @Override
    protected void onStart() {
        initializeData(range());
        super.onStart();
        disposable.add(RxBus.INSTANCE
                .toObservable(EventReloadTreatmentData.class)
                .observeOn(Schedulers.io())
                .subscribe(event -> {
                            if (L.isEnabled(L.DATATREATMENTS))
                                log.debug("EventReloadTreatmentData");
                            initializeTreatmentData(range());
                            initializeExtendedBolusData(range());
                            updateTotalIOBTreatments();
                            RxBus.INSTANCE.send(event.getNext());
                        },
                        FabricPrivacy::logException
                ));
        disposable.add(RxBus.INSTANCE
                .toObservable(EventReloadProfileSwitchData.class)
                .observeOn(Schedulers.io())
                .subscribe(event -> initializeProfileSwitchData(range()),
                        FabricPrivacy::logException
                ));
        disposable.add(RxBus.INSTANCE
                .toObservable(EventTempTargetChange.class)
                .observeOn(Schedulers.io())
                .subscribe(event -> initializeTempTargetData(range()),
                        FabricPrivacy::logException
                ));
        disposable.add(RxBus.INSTANCE
                .toObservable(EventReloadTempBasalData.class)
                .observeOn(Schedulers.io())
                .subscribe(event -> {
                            if (L.isEnabled(L.DATATREATMENTS))
                                log.debug("EventReloadTempBasalData");
                            initializeTempBasalData(range());
                            updateTotalIOBTempBasals();
                        },
                        FabricPrivacy::logException
                ));
    }

    @Override
    protected void onStop() {
        disposable.clear();
        super.onStop();
    }

    public TreatmentService getService() {
        return this.service;
    }

    private long range() {
        double dia = Constants.defaultDIA;
        if (ConfigBuilderPlugin.getPlugin() != null && ProfileFunctions.getInstance().getProfile() != null)
            dia = ProfileFunctions.getInstance().getProfile().getDia();
        return  (long) (60 * 60 * 1000L * (24 + dia));
    }

    public void initializeData(long range) {
        initializeTempBasalData(range);
        initializeTreatmentData(range);
        initializeExtendedBolusData(range);
        initializeTempTargetData(range);
        initializeProfileSwitchData(range);
    }

    private void initializeTreatmentData(long range) {
        if (L.isEnabled(L.DATATREATMENTS))
            log.debug("initializeTreatmentData");
        synchronized (treatments) {
            treatments.clear();
            treatments.addAll(getService().getTreatmentDataFromTime(DateUtil.now() - range, false));
        }
    }

    private void initializeTempBasalData(long range) {
        if (L.isEnabled(L.DATATREATMENTS))
            log.debug("initializeTempBasalData");
        synchronized (tempBasals) {
            tempBasals.reset().add(MainApp.getDbHelper().getTemporaryBasalsDataFromTime(DateUtil.now() - range, false));
        }

    }

    private void initializeExtendedBolusData(long range) {
        if (L.isEnabled(L.DATATREATMENTS))
            log.debug("initializeExtendedBolusData");
        synchronized (extendedBoluses) {
            extendedBoluses.reset().add(MainApp.getDbHelper().getExtendedBolusDataFromTime(DateUtil.now() - range, false));
        }

    }

    private void initializeTempTargetData(long range) {
        if (L.isEnabled(L.DATATREATMENTS))
            log.debug("initializeTempTargetData");
        synchronized (tempTargets) {
            tempTargets.reset().add(MainApp.getDbHelper().getTemptargetsDataFromTime(DateUtil.now() - range, false));
        }
    }

    private void initializeProfileSwitchData(long range) {
        if (L.isEnabled(L.DATATREATMENTS))
            log.debug("initializeProfileSwitchData");
        synchronized (profiles) {
            profiles.reset().add(MainApp.getDbHelper().getProfileSwitchData(DateUtil.now() - range, false));
        }
    }

    @Override
    public IobTotal getLastCalculationTreatments() {
        return lastTreatmentCalculation;
    }

    @Override
    public IobTotal getCalculationToTimeTreatments(long time) {
        IobTotal total = new IobTotal(time);

        Profile profile = ProfileFunctions.getInstance().getProfile();
        if (profile == null)
            return total;

        InsulinInterface insulinInterface = ConfigBuilderPlugin.getPlugin().getActiveInsulin();
        if (insulinInterface == null)
            return total;

        PumpInterface pumpInterface = ConfigBuilderPlugin.getPlugin().getActivePump();
        if (pumpInterface == null)
            return total;

        double dia = profile.getDia();

        synchronized (treatments) {
            for (Integer pos = 0; pos < treatments.size(); pos++) {
                Treatment t = treatments.get(pos);
                if (!t.isValid) continue;
                if (t.date > time) continue;
                Iob tIOB = t.iobCalc(time, dia);
                total.iob += tIOB.iobContrib;
                total.activity += tIOB.activityContrib;
                if (t.insulin > 0 && t.date > total.lastBolusTime)
                    total.lastBolusTime = t.date;
                if (!t.isSMB) {
                    // instead of dividing the DIA that only worked on the bilinear curves,
                    // multiply the time the treatment is seen active.
                    long timeSinceTreatment = time - t.date;
                    long snoozeTime = t.date + (long) (timeSinceTreatment * SP.getDouble(R.string.key_openapsama_bolussnooze_dia_divisor, 2.0));
                    Iob bIOB = t.iobCalc(snoozeTime, dia);
                    total.bolussnooze += bIOB.iobContrib;
                }
            }
        }

        if (!pumpInterface.isFakingTempsByExtendedBoluses())
            synchronized (extendedBoluses) {
                for (int pos = 0; pos < extendedBoluses.size(); pos++) {
                    ExtendedBolus e = extendedBoluses.get(pos);
                    if (e.date > time) continue;
                    IobTotal calc = e.iobCalc(time);
                    total.plus(calc);
                }
            }
        return total;
    }

    @Override
    public void updateTotalIOBTreatments() {
        lastTreatmentCalculation = getCalculationToTimeTreatments(System.currentTimeMillis());
    }

    @Override
    public MealData getMealData() {
        MealData result = new MealData();

        Profile profile = ProfileFunctions.getInstance().getProfile();
        if (profile == null) return result;

        long now = System.currentTimeMillis();
        long dia_ago = now - (Double.valueOf(profile.getDia() * T.hours(1).msecs())).longValue();

        double maxAbsorptionHours = Constants.DEFAULT_MAX_ABSORPTION_TIME;
        if (SensitivityAAPSPlugin.getPlugin().isEnabled(PluginType.SENSITIVITY) || SensitivityWeightedAveragePlugin.getPlugin().isEnabled(PluginType.SENSITIVITY)) {
            maxAbsorptionHours = SP.getDouble(R.string.key_absorption_maxtime, Constants.DEFAULT_MAX_ABSORPTION_TIME);
        } else {
            maxAbsorptionHours = SP.getDouble(R.string.key_absorption_cutoff, Constants.DEFAULT_MAX_ABSORPTION_TIME);
        }
        long absorptionTime_ago = now - (Double.valueOf(maxAbsorptionHours * T.hours(1).msecs())).longValue();

        synchronized (treatments) {
            for (Treatment treatment : treatments) {
                if (!treatment.isValid)
                    continue;
                long t = treatment.date;

                if (t > dia_ago && t <= now) {
                    if (treatment.insulin > 0 && treatment.mealBolus) {
                        result.boluses += treatment.insulin;
                    }
                }

                if (t > absorptionTime_ago && t <= now) {
                    if (treatment.carbs >= 1) {
                        result.carbs += treatment.carbs;
                        if (t > result.lastCarbTime)
                            result.lastCarbTime = t;
                    }
                }
            }
        }

        AutosensData autosensData = IobCobCalculatorPlugin.getPlugin().getLastAutosensDataSynchronized("getMealData()");
        if (autosensData != null) {
            result.mealCOB = autosensData.cob;
            result.slopeFromMinDeviation = autosensData.slopeFromMinDeviation;
            result.slopeFromMaxDeviation = autosensData.slopeFromMaxDeviation;
            result.usedMinCarbsImpact = autosensData.usedMinCarbsImpact;
        }
        result.lastBolusTime = getLastBolusTime();
        return result;
    }

    @Override
    public List<Treatment> getTreatmentsFromHistory() {
        synchronized (treatments) {
            return new ArrayList<>(treatments);
        }
    }


    /**
     * Returns all Treatments after specified timestamp. Also returns invalid entries (required to
     * map "Fill Canulla" entries to history (and not to add double bolus for it)
     *
     * @param fromTimestamp
     * @return
     */
    @Override
    public List<Treatment> getTreatmentsFromHistoryAfterTimestamp(long fromTimestamp) {
        List<Treatment> in5minback = new ArrayList<>();

        long time = System.currentTimeMillis();
        synchronized (treatments) {
            if (MedtronicHistoryData.doubleBolusDebug)
                log.debug("DoubleBolusDebug: AllTreatmentsInDb: {}", MedtronicUtil.getGsonInstanceCore().toJson(treatments));

            for (Treatment t : treatments) {
                if (t.date <= time && t.date >= fromTimestamp)
                    in5minback.add(t);
            }

            if (MedtronicHistoryData.doubleBolusDebug)
                log.debug("DoubleBolusDebug: FilteredTreatments: AfterTime={}, Items={}", fromTimestamp, MedtronicUtil.getGsonInstanceCore().toJson(in5minback));

            return in5minback;
        }
    }


    @Override
    public List<Treatment> getTreatments5MinBackFromHistory(long time) {
        List<Treatment> in5minback = new ArrayList<>();
        synchronized (treatments) {
            for (Integer pos = 0; pos < treatments.size(); pos++) {
                Treatment t = treatments.get(pos);
                if (!t.isValid)
                    continue;
                if (t.date <= time && t.date > time - 5 * 60 * 1000 && t.carbs > 0)
                    in5minback.add(t);
            }
            return in5minback;
        }
    }

    @Override
    public long getLastBolusTime() {
        long now = System.currentTimeMillis();
        long last = 0;
        synchronized (treatments) {
            for (Treatment t : treatments) {
                if (!t.isValid)
                    continue;
                if (t.date > last && t.insulin > 0 && t.isValid && t.date <= now)
                    last = t.date;
            }
        }
        if (L.isEnabled(L.DATATREATMENTS))
            log.debug("Last bolus time: " + new Date(last).toLocaleString());
        return last;
    }

    public long getLastBolusTime(boolean isSMB) {
        long now = System.currentTimeMillis();
        long last = 0;
        synchronized (treatments) {
            for (Treatment t : treatments) {
                if (!t.isValid)
                    continue;
                if (t.date > last && t.insulin > 0 && t.isValid && t.date <= now && isSMB == t.isSMB)
                    last = t.date;
            }
        }
        if (L.isEnabled(L.DATATREATMENTS))
            log.debug("Last manual bolus time: " + new Date(last).toLocaleString());
        return last;
    }

    @Override
    public boolean isInHistoryRealTempBasalInProgress() {
        return getRealTempBasalFromHistory(System.currentTimeMillis()) != null;
    }

    @Override
    public TemporaryBasal getRealTempBasalFromHistory(long time) {
        synchronized (tempBasals) {
            return tempBasals.getValueByInterval(time);
        }
    }

    @Override
    public boolean isTempBasalInProgress() {
        return getTempBasalFromHistory(System.currentTimeMillis()) != null;
    }

    @Override
    public boolean isInHistoryExtendedBoluslInProgress() {
        return getExtendedBolusFromHistory(System.currentTimeMillis()) != null; //TODO:  crosscheck here
    }

    @Override
    public IobTotal getLastCalculationTempBasals() {
        return lastTempBasalsCalculation;
    }

    @Override
    public IobTotal getCalculationToTimeTempBasals(long time) {
        return getCalculationToTimeTempBasals(time, false, 0);
    }

    public IobTotal getCalculationToTimeTempBasals(long time, boolean truncate, long truncateTime) {
        IobTotal total = new IobTotal(time);

        InsulinInterface insulinInterface = ConfigBuilderPlugin.getPlugin().getActiveInsulin();
        if (insulinInterface == null)
            return total;

        synchronized (tempBasals) {
            for (Integer pos = 0; pos < tempBasals.size(); pos++) {
                TemporaryBasal t = tempBasals.get(pos);
                if (t.date > time) continue;
                IobTotal calc;
                Profile profile = ProfileFunctions.getInstance().getProfile(t.date);
                if (profile == null) continue;
                if (truncate && t.end() > truncateTime) {
                    TemporaryBasal dummyTemp = new TemporaryBasal();
                    dummyTemp.copyFrom(t);
                    dummyTemp.cutEndTo(truncateTime);
                    calc = dummyTemp.iobCalc(time, profile);
                } else {
                    calc = t.iobCalc(time, profile);
                }
                //log.debug("BasalIOB " + new Date(time) + " >>> " + calc.basaliob);
                total.plus(calc);
            }
        }
        if (ConfigBuilderPlugin.getPlugin().getActivePump().isFakingTempsByExtendedBoluses()) {
            IobTotal totalExt = new IobTotal(time);
            synchronized (extendedBoluses) {
                for (Integer pos = 0; pos < extendedBoluses.size(); pos++) {
                    ExtendedBolus e = extendedBoluses.get(pos);
                    if (e.date > time) continue;
                    IobTotal calc;
                    Profile profile = ProfileFunctions.getInstance().getProfile(e.date);
                    if (profile == null) continue;
                    if (truncate && e.end() > truncateTime) {
                        ExtendedBolus dummyExt = new ExtendedBolus();
                        dummyExt.copyFrom(e);
                        dummyExt.cutEndTo(truncateTime);
                        calc = dummyExt.iobCalc(time);
                    } else {
                        calc = e.iobCalc(time);
                    }
                    totalExt.plus(calc);
                }
            }
            // Convert to basal iob
            totalExt.basaliob = totalExt.iob;
            totalExt.iob = 0d;
            totalExt.netbasalinsulin = totalExt.extendedBolusInsulin;
            totalExt.hightempinsulin = totalExt.extendedBolusInsulin;
            total.plus(totalExt);
        }
        return total;
    }

    public IobTotal getCalculationToTimeTempBasals(long time, long truncateTime, AutosensResult lastAutosensResult, boolean exercise_mode, int half_basal_exercise_target, boolean isTempTarget) {
        IobTotal total = new IobTotal(time);

        InsulinInterface insulinInterface = ConfigBuilderPlugin.getPlugin().getActiveInsulin();
        if (insulinInterface == null)
            return total;

        synchronized (tempBasals) {
            for (int pos = 0; pos < tempBasals.size(); pos++) {
                TemporaryBasal t = tempBasals.get(pos);
                if (t.date > time) continue;
                IobTotal calc;
                Profile profile = ProfileFunctions.getInstance().getProfile(t.date);
                if (profile == null) continue;
                if (t.end() > truncateTime) {
                    TemporaryBasal dummyTemp = new TemporaryBasal();
                    dummyTemp.copyFrom(t);
                    dummyTemp.cutEndTo(truncateTime);
                    calc = dummyTemp.iobCalc(time, profile, lastAutosensResult, exercise_mode, half_basal_exercise_target, isTempTarget);
                } else {
                    calc = t.iobCalc(time, profile, lastAutosensResult, exercise_mode, half_basal_exercise_target, isTempTarget);
                }
                //log.debug("BasalIOB " + new Date(time) + " >>> " + calc.basaliob);
                total.plus(calc);
            }
        }
        if (ConfigBuilderPlugin.getPlugin().getActivePump().isFakingTempsByExtendedBoluses()) {
            IobTotal totalExt = new IobTotal(time);
            synchronized (extendedBoluses) {
                for (int pos = 0; pos < extendedBoluses.size(); pos++) {
                    ExtendedBolus e = extendedBoluses.get(pos);
                    if (e.date > time) continue;
                    IobTotal calc;
                    Profile profile = ProfileFunctions.getInstance().getProfile(e.date);
                    if (profile == null) continue;
                    if (e.end() > truncateTime) {
                        ExtendedBolus dummyExt = new ExtendedBolus();
                        dummyExt.copyFrom(e);
                        dummyExt.cutEndTo(truncateTime);
                        calc = dummyExt.iobCalc(time, profile, lastAutosensResult, exercise_mode, half_basal_exercise_target, isTempTarget);
                    } else {
                        calc = e.iobCalc(time, profile, lastAutosensResult, exercise_mode, half_basal_exercise_target, isTempTarget);
                    }
                    totalExt.plus(calc);
                }
            }
            // Convert to basal iob
            totalExt.basaliob = totalExt.iob;
            totalExt.iob = 0d;
            totalExt.netbasalinsulin = totalExt.extendedBolusInsulin;
            totalExt.hightempinsulin = totalExt.extendedBolusInsulin;
            total.plus(totalExt);
        }
        return total;
    }

    @Override
    public void updateTotalIOBTempBasals() {
        lastTempBasalsCalculation = getCalculationToTimeTempBasals(DateUtil.now());
    }

    @Nullable
    @Override
    public TemporaryBasal getTempBasalFromHistory(long time) {
        TemporaryBasal tb = getRealTempBasalFromHistory(time);
        if (tb != null)
            return tb;
        ExtendedBolus eb = getExtendedBolusFromHistory(time);
        if (eb != null && ConfigBuilderPlugin.getPlugin().getActivePump().isFakingTempsByExtendedBoluses())
            return new TemporaryBasal(eb);
        return null;
    }

    @Override
    public ExtendedBolus getExtendedBolusFromHistory(long time) {
        synchronized (extendedBoluses) {
            return extendedBoluses.getValueByInterval(time);
        }
    }

    @Override
    public boolean addToHistoryExtendedBolus(ExtendedBolus extendedBolus) {
        //log.debug("Adding new ExtentedBolus record" + extendedBolus.log());
        boolean newRecordCreated = MainApp.getDbHelper().createOrUpdate(extendedBolus);
        if (newRecordCreated) {
            if (extendedBolus.durationInMinutes == 0) {
                if (ConfigBuilderPlugin.getPlugin().getActivePump().isFakingTempsByExtendedBoluses())
                    NSUpload.uploadTempBasalEnd(extendedBolus.date, true, extendedBolus.pumpId);
                else
                    NSUpload.uploadExtendedBolusEnd(extendedBolus.date, extendedBolus.pumpId);
            } else if (ConfigBuilderPlugin.getPlugin().getActivePump().isFakingTempsByExtendedBoluses())
                NSUpload.uploadTempBasalStartAbsolute(new TemporaryBasal(extendedBolus), extendedBolus.insulin);
            else
                NSUpload.uploadExtendedBolus(extendedBolus);
        }
        return newRecordCreated;
    }

    @Override
    public Intervals<ExtendedBolus> getExtendedBolusesFromHistory() {
        synchronized (extendedBoluses) {
            return new NonOverlappingIntervals<>(extendedBoluses);
        }
    }

    @Override
    public NonOverlappingIntervals<TemporaryBasal> getTemporaryBasalsFromHistory() {
        synchronized (tempBasals) {
            return new NonOverlappingIntervals<>(tempBasals);
        }
    }

    @Override
    public boolean addToHistoryTempBasal(TemporaryBasal tempBasal) {
        //log.debug("Adding new TemporaryBasal record" + tempBasal.toString());
        boolean newRecordCreated = MainApp.getDbHelper().createOrUpdate(tempBasal);
        if (newRecordCreated) {
            if (tempBasal.durationInMinutes == 0)
                NSUpload.uploadTempBasalEnd(tempBasal.date, false, tempBasal.pumpId);
            else if (tempBasal.isAbsolute)
                NSUpload.uploadTempBasalStartAbsolute(tempBasal, null);
            else
                NSUpload.uploadTempBasalStartPercent(tempBasal);
        }
        return newRecordCreated;
    }

    // return true if new record is created
    @Override
    public boolean addToHistoryTreatment(DetailedBolusInfo detailedBolusInfo, boolean allowUpdate) {
        boolean medtronicPump = MedtronicUtil.isMedtronicPump();

        if (MedtronicHistoryData.doubleBolusDebug)
            log.debug("DoubleBolusDebug: addToHistoryTreatment::isMedtronicPump={}", medtronicPump);

        Treatment treatment = new Treatment();
        treatment.date = detailedBolusInfo.date;
        treatment.source = detailedBolusInfo.source;
        treatment.pumpId = detailedBolusInfo.pumpId;
        treatment.insulin = detailedBolusInfo.insulin;
        treatment.isValid = detailedBolusInfo.isValid;
        treatment.isSMB = detailedBolusInfo.isSMB;
        if (detailedBolusInfo.carbTime == 0)
            treatment.carbs = detailedBolusInfo.carbs;
        treatment.source = detailedBolusInfo.source;
        treatment.mealBolus = treatment.carbs > 0;
        treatment.boluscalc = detailedBolusInfo.boluscalc != null ? detailedBolusInfo.boluscalc.toString() : null;
        TreatmentService.UpdateReturn creatOrUpdateResult;

        if (medtronicPump && MedtronicHistoryData.doubleBolusDebug)
            log.debug("DoubleBolusDebug: addToHistoryTreatment::treatment={}", treatment);

        if (!medtronicPump)
            creatOrUpdateResult = getService().createOrUpdate(treatment);
        else
            creatOrUpdateResult = getService().createOrUpdateMedtronic(treatment, false);

        boolean newRecordCreated = creatOrUpdateResult.newRecord;
        //log.debug("Adding new Treatment record" + treatment.toString());
        if (detailedBolusInfo.carbTime != 0) {

            Treatment carbsTreatment = new Treatment();
            carbsTreatment.source = detailedBolusInfo.source;
            carbsTreatment.pumpId = detailedBolusInfo.pumpId; // but this should never happen
            carbsTreatment.date = detailedBolusInfo.date + detailedBolusInfo.carbTime * 60 * 1000L + 1000L; // add 1 sec to make them different records
            carbsTreatment.carbs = detailedBolusInfo.carbs;
            carbsTreatment.source = detailedBolusInfo.source;

            if (medtronicPump && MedtronicHistoryData.doubleBolusDebug)
                log.debug("DoubleBolusDebug: carbTime!=0, creating second treatment. CarbsTreatment={}", carbsTreatment);

            if (!medtronicPump)
                getService().createOrUpdate(carbsTreatment);
            else
                getService().createOrUpdateMedtronic(carbsTreatment, false);
            //log.debug("Adding new Treatment record" + carbsTreatment);
        }
        if (newRecordCreated && detailedBolusInfo.isValid)
            NSUpload.uploadTreatmentRecord(detailedBolusInfo);

        if (!allowUpdate && !creatOrUpdateResult.success) {
            log.error("Treatment could not be added to DB", new Exception());

            String status = String.format(MainApp.gs(R.string.error_adding_treatment_message), treatment.insulin, (int) treatment.carbs, DateUtil.dateAndTimeString(treatment.date));

            Intent i = new Intent(MainApp.instance(), ErrorHelperActivity.class);
            i.putExtra("soundid", R.raw.error);
            i.putExtra("title", MainApp.gs(R.string.error_adding_treatment_title));
            i.putExtra("status", status);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            MainApp.instance().startActivity(i);

            Bundle bundle = new Bundle();
            bundle.putString(FirebaseAnalytics.Param.ITEM_ID, "TreatmentClash");
            bundle.putString(FirebaseAnalytics.Param.VALUE, status);
            FabricPrivacy.getInstance().logCustom(bundle);
        }

        return newRecordCreated;
    }

    @Override
    public long oldestDataAvailable() {
        long oldestTime = System.currentTimeMillis();
        synchronized (tempBasals) {
            if (tempBasals.size() > 0)
                oldestTime = Math.min(oldestTime, tempBasals.get(0).date);
        }
        synchronized (extendedBoluses) {
            if (extendedBoluses.size() > 0)
                oldestTime = Math.min(oldestTime, extendedBoluses.get(0).date);
        }
        synchronized (treatments) {
            if (treatments.size() > 0)
                oldestTime = Math.min(oldestTime, treatments.get(treatments.size() - 1).date);
        }
        oldestTime -= 15 * 60 * 1000L; // allow 15 min before
        return oldestTime;
    }

    @Nullable
    @Override
    public TempTarget getTempTargetFromHistory() {
        synchronized (tempTargets) {
            return tempTargets.getValueByInterval(System.currentTimeMillis());
        }
    }

    @Nullable
    @Override
    public TempTarget getTempTargetFromHistory(long time) {
        synchronized (tempTargets) {
            return tempTargets.getValueByInterval(time);
        }
    }

    @Override
    public Intervals<TempTarget> getTempTargetsFromHistory() {
        synchronized (tempTargets) {
            return new OverlappingIntervals<>(tempTargets);
        }
    }

    @Override
    public void addToHistoryTempTarget(TempTarget tempTarget) {
        //log.debug("Adding new TemporaryBasal record" + profileSwitch.log());
        MainApp.getDbHelper().createOrUpdate(tempTarget);
        NSUpload.uploadTempTarget(tempTarget);
    }

    @Override
    @Nullable
    public ProfileSwitch getProfileSwitchFromHistory(long time) {
        synchronized (profiles) {
            return (ProfileSwitch) profiles.getValueToTime(time);
        }
    }

    @Override
    public ProfileIntervals<ProfileSwitch> getProfileSwitchesFromHistory() {
        synchronized (profiles) {
            return new ProfileIntervals<>(profiles);
        }
    }

    @Override
    public void addToHistoryProfileSwitch(ProfileSwitch profileSwitch) {
        //log.debug("Adding new TemporaryBasal record" + profileSwitch.log());
        RxBus.INSTANCE.send(new EventDismissNotification(Notification.PROFILE_SWITCH_MISSING));
        MainApp.getDbHelper().createOrUpdate(profileSwitch);
        NSUpload.uploadProfileSwitch(profileSwitch);
    }


}
