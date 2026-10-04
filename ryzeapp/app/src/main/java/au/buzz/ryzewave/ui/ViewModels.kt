@file:OptIn(ExperimentalCoroutinesApi::class)

package au.buzz.ryzewave.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.buzz.ryzewave.App
import au.buzz.ryzewave.Graph
import au.buzz.ryzewave.core.BloodPressureReading
import au.buzz.ryzewave.core.ConnectionState
import au.buzz.ryzewave.core.DailySummary
import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SamplingSettings
import au.buzz.ryzewave.core.SleepStage
import au.buzz.ryzewave.core.Spo2Sample
import au.buzz.ryzewave.core.StepsHour
import au.buzz.ryzewave.core.StrideSettings
import au.buzz.ryzewave.core.TrackPoint
import au.buzz.ryzewave.core.UserProfile
import au.buzz.ryzewave.core.WatchStatus
import au.buzz.ryzewave.core.Workout
import au.buzz.ryzewave.ble.WatchService
import androidx.health.connect.client.records.ExerciseSessionRecord
import au.buzz.ryzewave.health.ExportResult
import au.buzz.ryzewave.health.HealthConnectMapping
import au.buzz.ryzewave.notify.WatchNotificationListener
import au.buzz.ryzewave.workout.SportMotionCheck
import au.buzz.ryzewave.workout.HeartRateRecovery
import au.buzz.ryzewave.workout.HrEstimator
import au.buzz.ryzewave.workout.RestingHrBaseline
import au.buzz.ryzewave.workout.WorkoutController
import au.buzz.ryzewave.core.RestingHr
import au.buzz.ryzewave.core.SampleSource
import au.buzz.ryzewave.workout.StrideCalibration
import kotlin.math.roundToInt
import au.buzz.ryzewave.workout.CalibrationSteps
import au.buzz.ryzewave.workout.DefaultStrideModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val TAG = "RyzeUi"
private fun started() = SharingStarted.WhileSubscribed(5_000L)

/** Message + busy plumbing shared by the view models. */
abstract class RyzeViewModel : ViewModel() {
    protected val _message = MutableStateFlow<String?>(null)
    protected val _busy = MutableStateFlow(false)

    /** One-shot user message (snackbar); cleared with [clearMessage]. */
    val message: StateFlow<String?> = _message.asStateFlow()
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    /** Runs [block] once at a time; its non-null result becomes the message, failures too. */
    protected fun task(name: String, exclusive: Boolean = true, block: suspend () -> String?) {
        if (exclusive && _busy.value) return
        if (exclusive) _busy.value = true
        viewModelScope.launch {
            try {
                block()?.let { _message.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$name failed", e)
                _message.value = "$name failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                if (exclusive) _busy.value = false
            }
        }
    }
}

// ---- Dashboard ----------------------------------------------------------------------------------------------

class DashboardViewModel(private val graph: Graph = App.graph) : RyzeViewModel() {
    private val dayStart = MutableStateFlow(Fmt.dayStart())

    val status: StateFlow<WatchStatus> = graph.watch.status
    val profile: StateFlow<UserProfile> = graph.settings.profile.stateIn(viewModelScope, started(), UserProfile())
    val today: StateFlow<DailySummary?> =
        dayStart.flatMapLatest { graph.repo.dailySummary(it) }.stateIn(viewModelScope, started(), null)
    val sleep: StateFlow<List<SleepStage>> =
        dayStart.flatMapLatest { graph.repo.sleepForNight(it) }.stateIn(viewModelScope, started(), emptyList())

    /** Latest resting heart rate (one per day) and the latest workout with a recovery figure, for the Vitals card. */
    val restingHr: StateFlow<RestingHr?> = graph.repo.latestRestingHr().stateIn(viewModelScope, started(), null)
    val recovery: StateFlow<Workout?> = graph.repo.latestRecovery().stateIn(viewModelScope, started(), null)

    private val _liveHr = MutableStateFlow<HrSample?>(null)

    /** Last live HR sample while a measurement / workout streams; null otherwise. */
    val liveHr: StateFlow<HrSample?> = _liveHr.asStateFlow()

    private val _measuring = MutableStateFlow(false)
    val measuring: StateFlow<Boolean> = _measuring.asStateFlow()

    /**
     * Most recent P32/UTE blood-pressure spot result.
     *
     * This first implementation deliberately keeps BP in memory only.
     * Once C7 spot measurement is verified on the real P32 we can add
     * repository persistence and C8 FA history synchronization.
     */
    private val _bloodPressure = MutableStateFlow<BloodPressureReading?>(null)
    val bloodPressure: StateFlow<BloodPressureReading?> = _bloodPressure.asStateFlow()

    init {
        // "Today" follows the wall clock, so a screen left open rolls over at midnight (DayClock ticks every minute).
        viewModelScope.launch {
            DayClock.dayStarts().collect { dayStart.value = it }
        }
        viewModelScope.launch {
            graph.watch.liveHr.collect { s -> _liveHr.value = s }
        }
    }

    fun refreshDay() {
        dayStart.value = Fmt.dayStart()
    }

    /** True when the app may open a GATT connection (BLUETOOTH_CONNECT on Android 12+). */
    fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                App.instance,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * Goes through [WatchService] (which owns the link and its retry loop) rather than calling the link directly,
     * so a connect started here and a disconnect from the Dashboard are not undone by the service.
     */
    fun connect() = task("Connect") {
        if (!hasBluetoothPermission()) {
            return@task "Bluetooth permission missing: allow \"Nearby devices\" for RyzeWave in Android settings"
        }
        if (graph.settings.watchMac.first() == null) {
            graph.settings.setWatchMac(UiDefaults.WATCH_MAC)
        }
        val app = App.instance
        WatchService.start(app)
        WatchService.connect(app)
        null
    }

    fun disconnect() = task("Disconnect") {
        WatchService.pause(App.instance)
        graph.watch.disconnect()
        null
    }

    fun sync() = task("Sync") {
        val r = graph.watch.syncAll()
        r.error?.let { "Sync problem: $it" }
            ?: "Synced ${r.steps} step hours, ${r.hr} HR, ${r.spo2} SpO2, ${r.sleep} sleep records"
    }

    /** ~30 s of live HR (`E5 11`), shown in the vitals card. */
    fun measureHr() {
        if (_measuring.value) return
        _measuring.value = true
        viewModelScope.launch {
            try {
                graph.watch.startLiveHr()
                delay(30_000L)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "Live HR failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                try {
                    graph.watch.stopLiveHr()
                } catch (e: Exception) {
                    Log.d(TAG, "stopLiveHr: ${e.message}")
                }
                _measuring.value = false
                _liveHr.value = null
            }
        }
    }

    /** Spot SpO2 test (about 60 s). */
    fun spo2Test() = task("SpO2 test") {
        val pct = graph.watch.spo2SpotTest()
        if (pct == null) {
            "SpO2 test failed (keep the watch still on your wrist)"
        } else {
            "SpO2 $pct %"
        }
    }

    /**
     * Run the P32/UTE legacy blood-pressure spot test.
     *
     * WatchApiImpl sends C7 11 and waits for:
     *
     * C7 00 00 SS DD
     *
     * where SS is systolic and DD is diastolic.
     */
    fun bloodPressureTest() = task("Blood pressure") {
        val result = graph.watch.bloodPressureSpotTest()

        if (result == null) {
            "Blood pressure test failed"
        } else {
            _bloodPressure.value = result
            "Blood pressure ${result.systolic}/${result.diastolic} mmHg"
        }
    }

    fun connected(): Boolean =
        status.value.state == ConnectionState.CONNECTED ||
            status.value.state == ConnectionState.SYNCING
}

// ---- History ------------------------------------------------------------------------------------------------

class HistoryViewModel(private val graph: Graph = App.graph) : RyzeViewModel() {
    private val _day = MutableStateFlow(Fmt.dayStart())
    private val _rangeDays = MutableStateFlow(UiDefaults.HISTORY_RANGES.first())

    /** Current local day start, re-evaluated every minute so "today" rolls over at midnight while the app runs. */
    private val todayStart = MutableStateFlow(Fmt.dayStart())

    init {
        viewModelScope.launch {
            DayClock.dayStarts().collect { today ->
                val previous = todayStart.value
                todayStart.value = today
                // Only follow the clock when the user was looking at "today"; a day they picked stays put.
                _day.update { if (it == previous || it > today) today else it }
            }
        }
    }

    val day: StateFlow<Long> = _day.asStateFlow()
    val rangeDays: StateFlow<Int> = _rangeDays.asStateFlow()

    val hr: StateFlow<List<HrSample>> =
        _day.flatMapLatest { graph.repo.hrBetween(it, Fmt.dayEnd(it)) }
            .stateIn(viewModelScope, started(), emptyList())

    val spo2: StateFlow<List<Spo2Sample>> =
        _day.flatMapLatest { graph.repo.spo2Between(it, Fmt.dayEnd(it)) }
            .stateIn(viewModelScope, started(), emptyList())

    val steps: StateFlow<List<StepsHour>> =
        _day.flatMapLatest { graph.repo.stepsForDay(it) }
            .stateIn(viewModelScope, started(), emptyList())

    val summary: StateFlow<DailySummary?> =
        _day.flatMapLatest { graph.repo.dailySummary(it) }
            .stateIn(viewModelScope, started(), null)

    /** Stages of the night that ended on the morning of the selected day (previous noon .. noon). */
    val sleep: StateFlow<List<SleepStage>> =
        _day.flatMapLatest { graph.repo.sleepForNight(it) }
            .stateIn(viewModelScope, started(), emptyList())

    val profile: StateFlow<UserProfile> =
        graph.settings.profile.stateIn(viewModelScope, started(), UserProfile())

    /** Last 7 / 30 days ending today, zero-filled; reloaded after every sync and on profile / stride changes. */
    val daily: StateFlow<List<DailySummary>> = combine(
        _rangeDays,
        todayStart,
        graph.watch.status.map { it.lastSyncTime }.distinctUntilChanged(),
        graph.settings.profile,
        combine(graph.settings.stride, steps) { _, _ -> Unit },
    ) { range, today, _, _, _ -> range to today }
        .mapLatest { (range, today) ->
            ChartData.fillDays(graph.repo.dailySummaries(range), range, today)
        }
        .stateIn(viewModelScope, started(), emptyList())

    fun previousDay() {
        _day.update { Fmt.plusDays(it, -1) }
    }

    fun nextDay() {
        _day.update { if (Fmt.isToday(it)) it else Fmt.plusDays(it, 1) }
    }

    fun today() {
        _day.value = Fmt.dayStart()
    }

    fun selectDay(dayStart: Long) {
        val today = Fmt.dayStart()
        _day.value = if (dayStart > today) today else Fmt.dayStart(dayStart)
    }

    fun setRange(days: Int) {
        _rangeDays.value = days
    }
}

// ---- Workout --------------------------------------------------------------------------------------------------

class WorkoutViewModel(
    private val graph: Graph = App.graph,
    private val bridge: WorkoutBridge = WorkoutBridgeHolder.get(App.instance),
) : RyzeViewModel() {
    val state: StateFlow<WorkoutUiState> = bridge.state
    val status: StateFlow<WatchStatus> = graph.watch.status
    val workouts: StateFlow<List<Workout>> =
        graph.repo.workouts().stateIn(viewModelScope, started(), emptyList())

    private val _sportType = MutableStateFlow(UiDefaults.DEFAULT_SPORT_TYPE)

    /** Sport id for the next workout; persisted in settings (`workout_sport_type`) so it survives restarts. */
    val sportType: StateFlow<Int> = _sportType.asStateFlow()

    private val _hrTrace = MutableStateFlow<List<Pt>>(emptyList())

    /** Live HR of the current session for the on-screen chart (capped). */
    val hrTrace: StateFlow<List<Pt>> = _hrTrace.asStateFlow()

    init {
        viewModelScope.launch {
            graph.settings.workoutSportType.collect { _sportType.value = it }
        }

        viewModelScope.launch {
            graph.watch.liveHr.collect { s ->
                if (bridge.state.value.active && s.bpm > 0) {
                    _hrTrace.update { old ->
                        val next = old + Pt(s.time, s.bpm.toDouble())
                        if (next.size > MAX_TRACE) {
                            next.drop(next.size - MAX_TRACE)
                        } else {
                            next
                        }
                    }
                }
            }
        }
    }

    fun setSportType(type: Int) {
        _sportType.value = type
        viewModelScope.launch {
            graph.settings.setWorkoutSportType(type)
        }
    }

    fun start() {
        _hrTrace.value = emptyList()
        bridge.start(_sportType.value)
    }

    fun pause() = bridge.pause()
    fun resume() = bridge.resume()
    fun stop() = bridge.stop()

    companion object {
        const val MAX_TRACE = 4 * 3600
    }
}

/** Everything the workout-detail screen draws, derived from the stored rows. */
data class WorkoutDetail(
    val workout: Workout? = null,
    val hr: List<Pt> = emptyList(),
    val pace: List<Pt> = emptyList(),
    val kmMarkers: List<Pt> = emptyList(),
    val pointCount: Int = 0,
    val acceptedCount: Int = 0,
    val gpsDistanceMeters: Double = 0.0,

    /** Every stored GPS fix of the workout (accepted and rejected), oldest first, for the track plot. */
    val points: List<TrackPoint> = emptyList(),

    /** Heart-rate recovery after the last stride; null without enough samples ([HeartRateRecovery]). */
    val recovery: HeartRateRecovery.Recovery? = null,

    /** How many of the workout's heart-rate samples the estimator has replaced (repaired) and how many it flags. */
    val hrRepaired: Int = 0,
    val hrFlagged: Int = 0,

    /** Does the GPS agree with the declared sport? Null until the workout has finished. */
    val motion: SportMotionCheck.Result? = null,
)

class WorkoutDetailViewModel(private val graph: Graph = App.graph) : RyzeViewModel() {
    private val id = MutableStateFlow(0L)

    val workout: StateFlow<Workout?> =
        id.flatMapLatest {
            if (it == 0L) flowOf(null) else graph.repo.workout(it)
        }.stateIn(viewModelScope, started(), null)

    private val points: StateFlow<List<TrackPoint>> =
        id.flatMapLatest {
            if (it == 0L) flowOf(emptyList()) else graph.repo.trackPoints(it)
        }.stateIn(viewModelScope, started(), emptyList())

    private val hr: StateFlow<List<HrSample>> =
        workout.flatMapLatest { w ->
            if (w == null) {
                flowOf(emptyList())
            } else {
                graph.repo.hrBetween(
                    w.start,
                    (w.end ?: (w.start + w.durationSeconds * 1000L)) + HR_MARGIN_MS
                )
            }
        }.stateIn(viewModelScope, started(), emptyList())

    val detail: StateFlow<WorkoutDetail> =
        combine(workout, points, hr) { w, pts, samples ->
            val cum = ChartData.cumulativeDistance(pts)
            WorkoutDetail(
                workout = w,
                hr = ChartData.hrPoints(samples),
                pace = ChartData.paceSeries(cum),
                kmMarkers = ChartData.kmMarkers(cum),
                pointCount = pts.size,
                acceptedCount = pts.count { it.accepted },
                gpsDistanceMeters = cum.lastOrNull()?.value ?: 0.0,
                points = pts,
                motion = w?.let { ww ->
                    ww.end?.let {
                        SportMotionCheck.check(
                            ww.sportType,
                            pts,
                            ww.durationSeconds * 1000L,
                            ww.distanceMeters
                        )
                    }
                },
                recovery =
                    if (pts.isNotEmpty() && samples.isNotEmpty()) {
                        HeartRateRecovery.of(pts, samples)
                    } else {
                        null
                    },
                hrRepaired = samples.count { it.repaired },
                hrFlagged = samples.count { it.flagged },
            )
        }.stateIn(viewModelScope, started(), WorkoutDetail())

    /**
     * Replay the physiological estimator over this workout and replace the readings it rejects as wrist dropouts
     * with its estimate, keeping the watch's value beside them. Then the row's average, maximum and calories are
     * recomputed from the corrected series and the workout is re-exported.
     */
    fun repairHeartRate() = task("Repair heart rate") {
        val w = workout.value ?: return@task "No workout loaded"
        val end = w.end ?: return@task "Workout not finished"
        val pts = graph.repo.trackPoints(w.id).first()
        val samples = graph.repo.hrBetween(w.start, end + HR_MARGIN_MS)
            .first()
            .filter { it.source == SampleSource.WORKOUT }

        if (pts.isEmpty() || samples.isEmpty()) {
            return@task "Nothing to work with: ${pts.size} fixes, ${samples.size} heart-rate samples"
        }

        val resting = RestingHrBaseline.of(
            graph.repo.hrSince(w.start - RestingHrBaseline.LOOKBACK_MS)
                .filter { it.time < w.start }
        )

        val replayed = HrEstimator.replay(pts, samples, resting)
        val changed = HrEstimator.repair(replayed)

        if (changed.isEmpty()) {
            val already = samples.count { it.repaired }
            return@task if (already > 0) {
                "Already repaired ($already samples); nothing new looked wrong"
            } else {
                "Nothing looked wrong: the watch's readings fit the effort"
            }
        }

        graph.repo.upsertHr(changed)

        val corrected = replayed.map { r ->
            changed.firstOrNull { it.time == r.time } ?: r
        }.filter { it.time in w.start..end }

        val profile = graph.settings.profile.first()
        val wKg = profile.weightKg.toDouble()

        fun perMin(bpm: Int) =
            maxOf(
                WorkoutController.hrCaloriesPerMinute(
                    bpm,
                    wKg,
                    profile.age,
                    profile.male
                ),
                WorkoutController.STANDING_MET * wKg / 60.0
            )

        var delta = 0.0
        val byTime = changed.sortedBy { it.time }

        for (i in 1 until byTime.size) {
            val dtMin = (byTime[i].time - byTime[i - 1].time) / 60_000.0
            if (dtMin <= 0.0 || dtMin > 0.5) continue
            delta +=
                (perMin(byTime[i].bpm) - perMin(byTime[i].measured!!)) * dtMin
        }

        val kcal = w.calories + delta
        val avg = corrected.map { it.bpm }.average().toInt()
        val mx = corrected.maxOf { it.bpm }
        val rec = HeartRateRecovery.of(pts, corrected)

        graph.repo.updateWorkout(
            w.copy(
                avgHr = avg,
                maxHr = mx,
                calories = kcal.roundToInt(),
                hrrPeak = rec?.peakHr ?: w.hrrPeak,
                hrr1 = rec?.drop1min ?: w.hrr1,
                hrr2 = rec?.drop2min ?: w.hrr2,
            )
        )

        val lo = changed.minOf { it.time }
        val hi = changed.maxOf { it.time }

        val summary =
            "Repaired ${changed.size} readings (${Fmt.time(lo)}-${Fmt.time(hi)}), " +
                "watch ${changed.map { it.measured!! }.average().toInt()} -> about " +
                "${changed.map { it.bpm }.average().toInt()} bpm; average now $avg, max $mx, " +
                "${kcal.roundToInt()} kcal (${if (delta >= 0) "+" else ""}${delta.roundToInt()})"

        if (!graph.settings.healthConnectEnabled.first()) {
            return@task summary
        }

        try {
            graph.health.exportNew()
            "$summary, re-exported to Health Connect"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "$summary; Health Connect re-export failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun load(workoutId: Long) {
        id.value = workoutId
    }

    /** Writes `<external files>/gpx/workout-<id>-<stamp>.gpx` and reports the path. */
    fun exportGpx() = task("GPX export") {
        val w = workout.value ?: return@task "No workout loaded"
        val pts = graph.repo.trackPoints(w.id).first()

        if (pts.none { it.accepted }) {
            return@task "No GPS track to export"
        }

        val file = withContext(Dispatchers.IO) {
            val app = App.instance
            val dir = File(
                app.getExternalFilesDir(null) ?: app.filesDir,
                "gpx"
            ).apply {
                mkdirs()
            }

            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT)
                .format(
                    Instant.ofEpochMilli(w.start)
                        .atZone(ZoneId.systemDefault())
                )

            File(dir, "workout-${w.id}-$stamp.gpx").also {
                it.writeText(Gpx.write(w, pts))
            }
        }

        "Saved ${file.absolutePath}"
    }

    /**
     * Rebuilds this workout's track and distance from the always-on GPS breadcrumb (Settings) for the workout's
     * time window — for a session whose own tracking failed.
     */
    fun rebuildFromBreadcrumb() = task("Rebuild from breadcrumb") {
        val w = workout.value ?: return@task "No workout loaded"
        val end = w.end ?: return@task "Workout not finished"
        val crumbs = graph.repo.breadcrumbsBetween(w.start, end)

        val r = au.buzz.ryzewave.workout.BreadcrumbReconstruction
            .forWorkout(w.id, w.start, end, crumbs)
            ?: return@task "No breadcrumb points in this workout's window (${crumbs.size} found)"

        graph.repo.insertTrackPoints(r.points)
        graph.repo.updateWorkout(
            w.copy(distanceMeters = r.distanceMeters)
        )

        val summary =
            "Rebuilt from ${r.points.size} breadcrumb points (${r.accepted} accepted): " +
                Fmt.metres(r.distanceMeters)

        if (!graph.settings.healthConnectEnabled.first()) {
            return@task summary
        }

        try {
            graph.health.exportNew()
            "$summary, re-exported to Health Connect"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "$summary; Health Connect re-export failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun setExerciseType(exerciseType: Int) = task("Exercise type") {
        val w = workout.value ?: return@task "No workout loaded"
        graph.repo.updateWorkout(
            w.copy(exerciseTypeOverride = exerciseType)
        )

        val label = HealthConnectMapping.exerciseTypeLabel(exerciseType)

        if (!graph.settings.healthConnectEnabled.first()) {
            return@task "Exercise type set to $label"
        }

        try {
            graph.health.exportNew()
            "Exercise type set to $label and re-exported to Health Connect"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Exercise type set to $label; Health Connect re-export failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    companion object {
        private const val HR_MARGIN_MS = 10_000L

        /** The exercise types offered by the detail-screen selector, in display order. */
        val EXERCISE_TYPE_CHOICES: List<Pair<Int, String>> = listOf(
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to "Running",
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING to "Walking",
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING to "Hiking",
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING to "Biking",
            ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT to "Other",
        )
    }
}

// ---- Settings -------------------------------------------------------------------------------------------------

class SettingsViewModel(
    private val graph: Graph = App.graph,
    private val scanner: BleScanner = BleScanner(App.instance),
) : RyzeViewModel() {
    val mac: StateFlow<String> =
        graph.settings.watchMac.map { it ?: UiDefaults.WATCH_MAC }
            .stateIn(viewModelScope, started(), UiDefaults.WATCH_MAC)

    val profile: StateFlow<UserProfile> =
        graph.settings.profile.stateIn(viewModelScope, started(), UserProfile())

    val sampling: StateFlow<SamplingSettings> =
        graph.settings.sampling.stateIn(viewModelScope, started(), SamplingSettings())

    val stride: StateFlow<StrideSettings> =
        graph.settings.stride.stateIn(viewModelScope, started(), StrideSettings())

    val healthConnectEnabled: StateFlow<Boolean> =
        graph.settings.healthConnectEnabled.stateIn(viewModelScope, started(), false)

    val status: StateFlow<WatchStatus> = graph.watch.status

    /** Outcome of the most recent Health Connect export (manual or after a sync), for the Health section. */
    val lastExport: StateFlow<ExportResult?> = graph.health.lastResult
    val exporting: StateFlow<Boolean> = graph.health.exporting

    /** Most recent finished workout with a usable GPS distance, for stride calibration. */
    val calibrationWorkout: StateFlow<Workout?> =
        graph.repo.workouts()
            .map { list ->
                list.firstOrNull {
                    it.end != null &&
                        it.distanceMeters >= MIN_CALIBRATION_M &&
                        it.durationSeconds > 0
                }
            }
            .stateIn(viewModelScope, started(), null)

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _scanResults = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scanResults: StateFlow<List<ScannedDevice>> = _scanResults.asStateFlow()

    private var scanJob: Job? = null

    fun saveMac(value: String) {
        val mac = MacText.normalise(value)

        if (!MacText.isValid(mac)) {
            _message.value = "MAC must look like 78:02:B7:37:91:E5"
            return
        }

        task("Save MAC", exclusive = false) {
            val previous = graph.settings.watchMac.first()
            graph.settings.setWatchMac(mac)

            WatchService.connect(App.instance)

            if (previous != null && previous != mac) {
                "Watch address saved; connecting to $mac"
            } else {
                "Watch address saved"
            }
        }
    }

    fun chooseDevice(device: ScannedDevice) {
        stopScan()
        saveMac(device.mac)
    }

    fun startScan() {
        if (scanJob?.isActive == true) return

        if (!scanner.hasPermissions()) {
            _message.value =
                "Bluetooth scan permission missing (grant it in Android settings)"
            return
        }

        _scanResults.value = emptyList()
        _scanning.value = true

        scanJob = viewModelScope.launch {
            try {
                withTimeoutOrNull(SCAN_MS) {
                    scanner.scan().collect {
                        _scanResults.value = it
                    }
                }

                if (_scanResults.value.isEmpty()) {
                    _message.value =
                        "No Ryze Wave watch found (is it awake and not connected elsewhere?)"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value =
                    "Scan failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _scanning.value = false
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _scanning.value = false
    }

    fun saveProfile(p: UserProfile) = task("Save profile", exclusive = false) {
        graph.settings.setProfile(p)
        applyToWatch(p, sampling.value)
    }

    fun saveSampling(s: SamplingSettings) = task("Save sampling", exclusive = false) {
        graph.settings.setSampling(s)
        applyToWatch(profile.value, s)
    }

    fun saveStride(s: StrideSettings) = task("Save stride", exclusive = false) {
        graph.settings.setStride(s)
        "Stride saved"
    }

    fun resetStride() = saveStride(StrideSettings())

    fun calibrateFromLastWorkout() = task("Calibration") {
        val w = calibrationWorkout.value
            ?: return@task "No GPS workout of at least ${MIN_CALIBRATION_M.toInt()} m yet"

        w.end ?: return@task "Workout not finished"

        val points = graph.repo.trackPointsOnce(w.id)
        val outcome = StrideCalibration.calibrate(
            points,
            profile.value,
            w.exerciseTypeOverride ?: w.sportType
        )

        if (!outcome.changedAnything) {
            return@task listOf(
                outcome.message,
                outcome.notes.joinToString("; ")
            ).filter { it.isNotBlank() }.joinToString(". ")
        }

        val current = stride.value

        graph.settings.setStride(
            current.copy(
                walkStrideM = outcome.walk?.strideM ?: current.walkStrideM,
                runStrideM = outcome.run?.strideM ?: current.runStrideM,
            )
        )

        listOf(
            outcome.message,
            outcome.notes.joinToString("; ")
        ).filter { it.isNotBlank() }.joinToString(". ")
    }

    fun setHealthConnectEnabled(on: Boolean) = task("Health Connect", exclusive = false) {
        graph.settings.setHealthConnectEnabled(on)
        null
    }

    fun exportNow() = task("Export") {
        val r = graph.health.exportAll()
        exportMessage(r)
    }

    fun findWatch() = task("Find watch") {
        graph.watch.findWatch()
        "The watch should be vibrating"
    }

    // ---- notifications to the watch ----

    val notificationsEnabled: StateFlow<Boolean> =
        graph.settings.notificationsEnabled.stateIn(viewModelScope, started(), false)

    val allowedPackages: StateFlow<Set<String>> =
        graph.settings.allowedPackages.stateIn(viewModelScope, started(), emptySet())

    val forwardAllNotifications: StateFlow<Boolean> =
        graph.settings.forwardAllNotifications.stateIn(viewModelScope, started(), false)

    private val _installedApps = MutableStateFlow<List<InstalledApp>?>(null)

    /** Launcher apps for the per-app toggles; null until [loadInstalledApps] has finished (loaded off the main thread). */
    val installedApps: StateFlow<List<InstalledApp>?> = _installedApps.asStateFlow()

    private val _notificationAccess = MutableStateFlow(false)

    /** Whether the user has granted notification access (refreshed by [refreshNotificationAccess] on resume). */
    val notificationAccess: StateFlow<Boolean> = _notificationAccess.asStateFlow()

    fun refreshNotificationAccess() {
        _notificationAccess.value =
            WatchNotificationListener.isAccessGranted(App.instance)
    }

    fun loadInstalledApps() {
        if (_installedApps.value != null) return

        viewModelScope.launch {
            _installedApps.value = withContext(Dispatchers.IO) {
                InstalledApps.launcherApps(App.instance)
            }
        }
    }

    fun setNotificationsEnabled(on: Boolean) =
        task("Notifications", exclusive = false) {
            graph.settings.setNotificationsEnabled(on)
            null
        }

    fun setForwardAllNotifications(on: Boolean) =
        task("Notifications", exclusive = false) {
            graph.settings.setForwardAllNotifications(on)
            null
        }

    fun setPackageAllowed(packageName: String, allowed: Boolean) =
        task("Notifications", exclusive = false) {
            val current = graph.settings.allowedPackages.first()
            graph.settings.setAllowedPackages(
                if (allowed) current + packageName else current - packageName
            )
            null
        }

    // ---- stuck-workout detector ----

    val stuckDetectorEnabled: StateFlow<Boolean> =
        graph.settings.stuckDetectorEnabled.stateIn(viewModelScope, started(), true)

    val stuckAutoStopAppWorkouts: StateFlow<Boolean> =
        graph.settings.stuckAutoStopAppWorkouts.stateIn(viewModelScope, started(), false)

    fun setStuckDetectorEnabled(on: Boolean) =
        task("Stuck-workout detector", exclusive = false) {
            graph.settings.setStuckDetectorEnabled(on)
            null
        }

    fun setStuckAutoStopAppWorkouts(on: Boolean) =
        task("Stuck-workout detector", exclusive = false) {
            graph.settings.setStuckAutoStopAppWorkouts(on)
            null
        }

    // ---- GPS breadcrumb (opt-in) ----

    val breadcrumbEnabled: StateFlow<Boolean> =
        graph.settings.breadcrumbEnabled.stateIn(viewModelScope, started(), false)

    fun setBreadcrumbEnabled(on: Boolean) =
        task("GPS breadcrumb", exclusive = false) {
            graph.settings.setBreadcrumbEnabled(on)
            if (on) {
                "Breadcrumb on: records while you move, off when still or driving"
            } else {
                "Breadcrumb off"
            }
        }

    fun sendTestNotification() = task("Test notification") {
        if (!status.value.isConnected()) {
            return@task "Watch not connected"
        }

        if (graph.notifications.sendTest()) {
            "Test notification acknowledged by the watch"
        } else {
            "Test notification was not sent (${graph.notifications.lastError ?: "see log"})"
        }
    }

    private suspend fun applyToWatch(
        p: UserProfile,
        s: SamplingSettings
    ): String? {
        val st = status.value.state

        if (
            st != ConnectionState.CONNECTED &&
            st != ConnectionState.SYNCING
        ) {
            return "Saved; the watch gets it on the next connect"
        }

        return try {
            graph.watch.applySettings(p, s)
            "Saved and sent to the watch"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Saved, but the watch did not take it: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    override fun onCleared() {
        stopScan()
        super.onCleared()
    }

    companion object {
        const val SCAN_MS = 15_000L
        const val MIN_CALIBRATION_M = 200.0
        const val MIN_CALIBRATION_STEPS = 200
        const val RUN_SPEED_MPS = 2.0
    }
}

/**
 * The snackbar after "Export now": a no-op export (everything already in Health Connect, the ledger) says so
 * instead of "Exported 0 records" — [ExportResult.skipped] is the number of unchanged candidates.
 */
fun exportMessage(r: ExportResult): String = when {
    !r.ok ->
        "Health Connect export failed: ${r.message ?: r.status.name}"

    r.status == ExportResult.Status.NOTHING_TO_EXPORT ->
        if (r.skipped > 0) {
            "Nothing new to export: ${r.skipped} records already in Health Connect"
        } else {
            "Nothing to export yet"
        }

    else ->
        "Exported ${r.inserted} records to Health Connect" +
            (if (r.failed > 0) ", ${r.failed} rejected" else "")
}
