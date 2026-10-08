package au.buzz.ryzewave

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import au.buzz.ryzewave.ble.createWatchApi
import au.buzz.ryzewave.core.ConnectionState
import au.buzz.ryzewave.data.DataStoreSettingsStore
import au.buzz.ryzewave.data.Db
import au.buzz.ryzewave.data.RoomHealthRepository
import au.buzz.ryzewave.findphone.AndroidFindPhoneAlerter
import au.buzz.ryzewave.findphone.FindPhoneRinger
import au.buzz.ryzewave.workout.HeartRateRecovery
import au.buzz.ryzewave.core.RestingHr
import au.buzz.ryzewave.ui.Fmt
import kotlinx.coroutines.CancellationException
import au.buzz.ryzewave.health.HealthConnectExporter
import au.buzz.ryzewave.notify.NotificationForwarder
import au.buzz.ryzewave.core.SampleSource
import au.buzz.ryzewave.ui.WorkoutBridgeHolder
import au.buzz.ryzewave.workout.AndroidMotionSampler
import au.buzz.ryzewave.workout.AndroidSpeaker
import au.buzz.ryzewave.workout.AndroidStuckWorkoutAlerter
import au.buzz.ryzewave.workout.DefaultStrideModel
import au.buzz.ryzewave.workout.RestingHrBaseline
import au.buzz.ryzewave.workout.StateAnnouncer
import au.buzz.ryzewave.workout.StuckWorkoutMonitor
import au.buzz.ryzewave.workout.WorkoutController
import au.buzz.ryzewave.workout.WorkoutService
import au.buzz.ryzewave.workout.WorkoutSession
import au.buzz.ryzewave.workout.WorkoutUiBridge
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Builds the process-wide [Graph]: Room repository + DataStore settings (data), stride model (workout),
 * the BLE [au.buzz.ryzewave.core.WatchApi] (ble) and the Health Connect exporter (health). Also installs the
 * foreground-service workout controller behind the UI's `WorkoutBridge` and exports new data to Health
 * Connect after every successful watch sync when the user has enabled it.
 *
 * Must not touch `App.graph` (it is being assigned from the return value of [create]).
 */
object GraphFactory {
    /** How far past a workout's end the heart-rate stream is read for recovery: the two-minute probe plus slack. */
    private const val RECOVERY_MARGIN_MS = 3 * 60_000L
    /** Bump when HeartRateRecovery's rule changes, so every stored figure is recomputed once. */
    private const val RECOVERY_RULE_KIND = "recovery_rule"
    private const val RECOVERY_RULE_VERSION = 2L
    private const val TAG = "GraphFactory"

    /** Process-lifetime scope for the BLE link, the reconnect loop and the post-sync export hook. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("graph"))

    fun create(app: App): Graph {
        val settings = DataStoreSettingsStore(app)
        val stride = DefaultStrideModel()
        val repo = RoomHealthRepository(Db.get(app), stride, settings)
        val watch = createWatchApi(app, repo, settings, scope)
        val health = HealthConnectExporter(app, repo, settings, stride)

        /**
         * Heart-rate recovery for a finished workout ([HeartRateRecovery]: HRR = peak − rate one/two minutes after
         * the last bout), written onto its row. Returns true when a figure was stored.
         */
        suspend fun recordRecovery(workoutId: Long, force: Boolean = false): Boolean {
            val w = repo.workout(workoutId).first() ?: return false
            val end = w.end ?: return false
            val pts = repo.trackPointsOnce(workoutId)
            val hr = repo.hrBetween(w.start, end + RECOVERY_MARGIN_MS).first()
            val r = HeartRateRecovery.of(pts, hr)
            if (r == null || (r.drop1min == null && r.drop2min == null)) {
                // under the new rule this workout has no recovery figure: clear a stale one
                if (force && (w.hrr1 != null || w.hrr2 != null)) repo.updateWorkout(w.copy(hrrPeak = null, hrr1 = null, hrr2 = null))
                return false
            }
            if (w.hrr1 == r.drop1min && w.hrr2 == r.drop2min && w.hrrPeak == r.peakHr) return true
            repo.updateWorkout(w.copy(hrrPeak = r.peakHr, hrr1 = r.drop1min, hrr2 = r.drop2min))
            Log.i(TAG, "workout $workoutId recovery: peak ${r.peakHr}, -${r.drop1min} at 1 min, -${r.drop2min} at 2 min")
            return true
        }

        /**
         * Resting heart rate for today: the 10th percentile of the periodic samples over the last 24 hours
         * ([RestingHrBaseline]), stored per calendar day and refreshed at every sync.
         */
        suspend fun recordRestingHr() {
            try {
                val now = System.currentTimeMillis()
                val from = now - RestingHrBaseline.LOOKBACK_MS
                val samples = repo.hrSince(from).filter { it.time <= now }
                if (!RestingHrBaseline.hasEnough(samples)) return
                // a stage that began up to two hours before the window still covers its first samples
                val stages = repo.sleepBetween(from - 2 * 3600_000L, now)
                val bpm = RestingHrBaseline.daytime(samples, RestingHrBaseline.spans(stages, includeAwake = true))
                val sleepBpm = RestingHrBaseline.sleeping(samples, RestingHrBaseline.spans(stages, includeAwake = false))
                repo.upsertRestingHr(RestingHr(dayStart = Fmt.dayStart(now), bpm = bpm, computedAt = now, sleepBpm = sleepBpm))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "resting heart rate not recorded", e)
            }
        }

        /** Health Connect: export what is new (steps / HR / SpO2 / sleep / workouts) when the user has enabled it. */
        suspend fun exportNew(reason: String) {
            try {
                if (!settings.healthConnectEnabled.first()) return
                val result = health.exportNew()
                Log.i(TAG, "Health Connect export after $reason: $result")
            } catch (t: Throwable) {
                Log.w(TAG, "Health Connect export after $reason failed", t)
            }
        }

        // No workout can be active at process start, so any endTime-NULL row is an orphan (a crash, or the
        // former stop-cancellation race) — close it so it stops showing "in progress" and counts in the day.
        val processStart = System.currentTimeMillis()
        scope.launch {
            try {
                val closed = repo.closeOrphanedWorkouts(before = processStart)
                if (closed > 0) {
                    Log.i(TAG, "closed $closed orphaned workout row(s)")
                    exportNew("orphan repair")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "orphaned-workout repair failed", t)
            }
        }

        // Workout: one controller for the foreground service and the Workout screen. A finished workout goes to
        // Health Connect straight away (session + distance + its HR samples), not only after the next watch sync.
        // A watch-button START is escalated into a real tracked session (2026-09-10: it was announced but nothing
        // tracked): start the foreground service with fromWatch — or, when that is impossible (location off,
        // no fine-location, background FGS refused), say so honestly instead of the plain "workout started".
        val speaker = AndroidSpeaker(app)
        fun onWatchStart() {
            val fineLocation = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            val lm = app.getSystemService(LocationManager::class.java)
            val locationOn = lm != null && LocationManagerCompat.isLocationEnabled(lm)
            if (!fineLocation || !locationOn) {
                Log.w(TAG, "watch start not escalated: fineLocation=$fineLocation locationOn=$locationOn")
                speaker.speak(StateAnnouncer.STARTED_WATCH_ONLY)
                return
            }
            try {
                WorkoutService.start(app, WorkoutService.DEFAULT_SPORT_TYPE, fromWatch = true)
                Log.i(TAG, "watch start escalated to the foreground workout service")
            } catch (e: Exception) {
                Log.w(TAG, "watch start: foreground service refused", e)
                speaker.speak(StateAnnouncer.STARTED_WATCH_ONLY)
            }
        }
        val controller = WorkoutController(
            repo = repo,
            watch = watch,
            settings = settings,
            scope = WorkoutSession.scope,
            onError = { message, cause -> Log.w(TAG, message, cause) },
            onFinished = { workout ->
                scope.launch {
                    recordRecovery(workout.id)
                    exportNew("workout ${workout.id}")
                }
            },
            onWatchStartRequested = ::onWatchStart,
        )
        WorkoutSession.install(controller)
        WorkoutBridgeHolder.install(WorkoutUiBridge(app, controller, WorkoutSession.scope))

        // Health Connect: push whatever is new after each completed sync (lastSyncTime changes when syncAll finishes).
        scope.launch {
            watch.status
                .map { it.lastSyncTime }
                .filterNotNull()
                .distinctUntilChanged()
                .collect {
                    if (watch.status.value.state == ConnectionState.SYNCING) return@collect
                    recordRestingHr()
                    exportNew("sync")
                }
        }

        // Backfill: every finished workout gets its recovery figure once, and again whenever the rule changes
        // (RECOVERY_RULE_VERSION, kept in the sync-cursor table so old figures are not left stale).
        scope.launch {
            try {
                val stored = repo.lastSyncTime(RECOVERY_RULE_KIND)
                val todo = if (stored == RECOVERY_RULE_VERSION) repo.workoutsWithoutRecovery()
                else repo.workouts().first().filter { it.end != null }
                var done = 0
                for (w in todo) if (recordRecovery(w.id, force = stored != RECOVERY_RULE_VERSION)) done++
                if (todo.isNotEmpty()) Log.i(TAG, "heart-rate recovery computed for $done of ${todo.size} workouts (rule $RECOVERY_RULE_VERSION)")
                repo.setLastSyncTime(RECOVERY_RULE_KIND, RECOVERY_RULE_VERSION)
            } catch (e: Exception) {
                Log.w(TAG, "recovery backfill failed", e)
            }
        }

        // Health Connect: the daily DistanceRecords are steps × stride, so a change of the *effective* stride
        // (calibration, manual edit, reset — or a profile height change while the strides are derived from it)
        // re-exports the affected days right away; the planner's stride marker decides which days moved.
        scope.launch {
            combine(settings.profile, settings.stride) { profile, s -> stride.walkStrideM(profile, s) to stride.runStrideM(profile, s) }
                .distinctUntilChanged()
                .drop(1)                                    // the stored value at start-up is not a change
                .collect { (walk, run) -> exportNew("stride change (walk $walk m, run $run m)") }
        }

        // Opt-in GPS breadcrumb: the switch in Settings starts/stops the location foreground service.
        scope.launch {
            settings.breadcrumbEnabled.distinctUntilChanged().collect { on ->
                Log.i(TAG, "breadcrumb ${if (on) "on: starting" else "off: stopping"} the service")
                if (on) au.buzz.ryzewave.workout.BreadcrumbService.start(app) else au.buzz.ryzewave.workout.BreadcrumbService.stop(app)
            }
        }

        // Phone -> watch notifications: the listener service hands posted notifications to this forwarder.
        val notifications = NotificationForwarder(
            settings, watch, scope, ownPackage = app.packageName,
            log = { m, t -> if (t == null) Log.i(TAG, "notify: $m") else Log.w(TAG, "notify: $m", t) },
        )

        // Find my phone: the watch's `D1 0A 01` rings the phone (alarm ringtone + vibration + Stop notification)
        // until `D1 0A 00`, the Stop action (through WatchService) or 30 s. Lives in the process the foreground
        // service keeps alive, so it rings with the screen off.
        val findPhone = FindPhoneRinger(
            AndroidFindPhoneAlerter(app), scope,
            log = { m, t -> if (t == null) Log.i(AndroidFindPhoneAlerter.TAG, m) else Log.w(AndroidFindPhoneAlerter.TAG, m, t) },
        )
        scope.launch { watch.events.collect { findPhone.onEvent(it) } }

        val musicController = au.buzz.ryzewave.notify.WatchMusicController(app)
        scope.launch { watch.events.collect { musicController.onEvent(it) } }

        // Stuck-in-exercise-mode detector (docs/PLAN.md): every workout — the app's own and one the watch started by
        // itself — is judged against its sport's expected activity signature over a rolling window. No expected
        // signal for the whole window → spoken warning + high-priority notification with Stop, then an auto-stop
        // after a grace (short at night with the wearer's HR at sleeping level: the former night workout guard).
        // App-started workouts are only auto-stopped when the Settings switch says so. Lives in the process the
        // BLE foreground service keeps alive, so it works with the screen off; speech goes through the shared engine.
        val stuckMonitor = StuckWorkoutMonitor(
            watch = watch,
            controller = controller,
            scope = scope,
            alerter = AndroidStuckWorkoutAlerter(app),
            speaker = speaker::speak,
            motion = AndroidMotionSampler(app),
            restingBaseline = {
                val now = System.currentTimeMillis()
                RestingHrBaseline.of(repo.hrBetween(now - RestingHrBaseline.LOOKBACK_MS, now).first())
            },
            periodicHr = { from, to ->
                repo.hrBetween(from, to).first().filter { it.source == SampleSource.AUTO || it.source == SampleSource.HISTORY }
            },
            detectorEnabled = { settings.stuckDetectorEnabled.first() },
            autoStopAppWorkouts = { settings.stuckAutoStopAppWorkouts.first() },
            log = { Log.i(AndroidStuckWorkoutAlerter.TAG, it) },
        )

        return Graph(
            repo = repo, settings = settings, watch = watch, health = health, notifications = notifications,
            findPhone = findPhone, speaker = speaker, stuckMonitor = stuckMonitor,
        )
    }
}
