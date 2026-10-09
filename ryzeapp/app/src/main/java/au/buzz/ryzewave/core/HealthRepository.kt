package au.buzz.ryzewave.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

/**
 * Local store for everything we get from the watch and the phone. Implemented by `data.RoomHealthRepository`
 * and exposed through `App.graph.repo`. Inserts are idempotent (same time + source = same row).
 */
interface HealthRepository {
    // ---- writes from the watch / workout tracker
    suspend fun upsertSteps(hours: List<StepsHour>)
    suspend fun upsertHr(samples: List<HrSample>)
    suspend fun upsertSpo2(samples: List<Spo2Sample>)
    suspend fun upsertSleep(stages: List<SleepStage>)
    /**
     * Replace a whole night's sleep with [stages]: delete every sleep row in the noon-to-noon night window of
     * [dayStart] (`data.Days.nightWindow`) and insert [stages] with a fresh `updatedAt`, so the Health Connect
     * export cursor picks the rewritten night up. Used by the honest-reconstruction path (a generic-asleep
     * window that keeps whatever real stages the watch did record). Default no-op for fakes.
     */
    suspend fun replaceSleepForNight(dayStart: Long, stages: List<SleepStage>) {}
    suspend fun insertWorkout(workout: Workout): Long
    suspend fun updateWorkout(workout: Workout)
    suspend fun insertTrackPoints(points: List<TrackPoint>)

    // ---- reads for the UI (Flows update live)
    fun dailySummary(dayStart: Long): Flow<DailySummary>
    fun stepsForDay(dayStart: Long): Flow<List<StepsHour>>
    fun hrBetween(from: Long, to: Long): Flow<List<HrSample>>
    fun spo2Between(from: Long, to: Long): Flow<List<Spo2Sample>>
    fun sleepForNight(dayStart: Long): Flow<List<SleepStage>>
    fun workouts(): Flow<List<Workout>>
    fun workout(id: Long): Flow<Workout?>
    fun trackPoints(workoutId: Long): Flow<List<TrackPoint>>
    /** One-shot read of [trackPoints] (the Health Connect exporter attaches them to the session as a route). */
    suspend fun trackPointsOnce(workoutId: Long): List<TrackPoint> = trackPoints(workoutId).first()
    suspend fun dailySummaries(days: Int): List<DailySummary>

    /** Resting heart rate per day ([RestingHr]); the latest row, rows changed since a time, and the writer. */
    fun latestRestingHr(): Flow<RestingHr?> = flowOf(null)
    suspend fun restingHrSince(time: Long): List<RestingHr> = emptyList()
    suspend fun upsertRestingHr(value: RestingHr) {}

    /** The most recent finished workout carrying a heart-rate recovery figure. */
    fun latestRecovery(): Flow<Workout?> = flowOf(null)
    /** Finished workouts with no recovery figure yet (one-off backfill after the upgrade). */
    suspend fun workoutsWithoutRecovery(): List<Workout> = emptyList()

    // ---- always-on GPS breadcrumb (additive; opt-in feature, see workout/BreadcrumbGate)
    suspend fun insertBreadcrumbs(points: List<Breadcrumb>) {}
    suspend fun breadcrumbsBetween(from: Long, to: Long): List<Breadcrumb> = emptyList()
    /** Deletes breadcrumbs older than [before]; returns how many. */
    suspend fun pruneBreadcrumbsBefore(before: Long): Int = 0

    // ---- sync bookkeeping / export cursor
    suspend fun lastSyncTime(kind: String): Long?
    suspend fun setLastSyncTime(kind: String, time: Long)
    suspend fun hrSince(time: Long): List<HrSample>
    suspend fun spo2Since(time: Long): List<Spo2Sample>
    suspend fun stepsSince(time: Long): List<StepsHour>
    suspend fun sleepSince(time: Long): List<SleepStage>
    /** Sleep stages starting in [from]..[to] (inclusive), for the day/night split of the resting heart rate. */
    suspend fun sleepBetween(from: Long, to: Long): List<SleepStage> = emptyList()
    suspend fun workoutsSince(time: Long): List<Workout>

    // ---- Health Connect export ledger (what was written, by client record id, with a content fingerprint)
    /** The stored fingerprint of each of [ids] that has been exported; ids never exported are absent. */
    suspend fun exportedFingerprints(ids: Collection<String>): Map<String, Long> = emptyMap()
    /** Records (or markers) written to Health Connect at [time], keyed by client record id. */
    suspend fun markExported(fingerprints: Map<String, Long>, time: Long) {}
    /** Forgets everything exported, so the next export re-sends the whole history (e.g. after wiping Health Connect). */
    suspend fun clearExported() {}
}

/** Small typed settings store (DataStore). Implemented in `data.SettingsStore`, exposed as `App.graph.settings`. */
interface SettingsStore {
    val watchMac: Flow<String?>
    val profile: Flow<UserProfile>
    val sampling: Flow<SamplingSettings>
    val stride: Flow<StrideSettings>
    val healthConnectEnabled: Flow<Boolean>
    suspend fun setWatchMac(mac: String?)
    suspend fun setProfile(p: UserProfile)
    suspend fun setSampling(s: SamplingSettings)
    suspend fun setStride(s: StrideSettings)
    suspend fun setHealthConnectEnabled(on: Boolean)

    // ---- notification forwarding (additive, defaults = feature off) ----
    /** Master switch for forwarding phone notifications to the watch. Default false. */
    val notificationsEnabled: Flow<Boolean> get() = flowOf(false)
    /** Package names whose notifications are forwarded. Default empty. */
    val allowedPackages: Flow<Set<String>> get() = flowOf(emptySet())
    /** Debug: forward every app regardless of [allowedPackages]. Default false. */
    val forwardAllNotifications: Flow<Boolean> get() = flowOf(false)
    suspend fun setNotificationsEnabled(on: Boolean) {}
    suspend fun setAllowedPackages(packages: Set<String>) {}
    suspend fun setForwardAllNotifications(on: Boolean) {}

    // ---- workout (additive, default = Outdoor Running) ----
    /** Sport id (`protocol.SportTypes`) the next workout is started with. Default 1 = Outdoor Running. */
    val workoutSportType: Flow<Int> get() = flowOf(DEFAULT_WORKOUT_SPORT_TYPE)
    suspend fun setWorkoutSportType(type: Int) {}

    // ---- stuck-workout detector (additive) ----
    // ---- always-on GPS breadcrumb (additive) ----
    /** Opt-in: record a GPS breadcrumb while the phone detects movement, outside workouts. Default false. */
    val breadcrumbEnabled: Flow<Boolean> get() = flowOf(false)
    suspend fun setBreadcrumbEnabled(on: Boolean) {}

    // ---- watch link (additive) ----
    /**
     * Whether the app keeps (re)connecting to the watch on its own. Default true. Turned off by the Home screen's
     * Disconnect button and persisted, so a phone that should stay hands-off (the Moto test phone while Buzz's Pixel
     * owns the watch's single BLE link) does not grab the watch again after a reinstall or reboot (2026-09-06).
     */
    val autoConnect: Flow<Boolean> get() = flowOf(true)
    suspend fun setAutoConnect(on: Boolean) {}

    /** Master switch of the stuck-in-exercise-mode detector (`workout.StuckWorkoutMonitor`). Default true. */
    val stuckDetectorEnabled: Flow<Boolean> get() = flowOf(true)
    /**
     * Also auto-stop an *app-started* workout that shows no activity after the grace (a watch-started one is always
     * auto-stopped while the detector is on; an app-started one otherwise only gets the warning). Default false.
     */
    val stuckAutoStopAppWorkouts: Flow<Boolean> get() = flowOf(false)
    suspend fun setStuckDetectorEnabled(on: Boolean) {}
    suspend fun setStuckAutoStopAppWorkouts(on: Boolean) {}

    val displayTimeoutSeconds: Flow<Int> get() = flowOf(5)
    suspend fun setDisplayTimeoutSeconds(seconds: Int) {}
    val quietHoursEnabled: Flow<Boolean> get() = flowOf(false)
    val quietHoursStart: Flow<Int> get() = flowOf(22)
    val quietHoursEnd: Flow<Int> get() = flowOf(7)
    suspend fun setQuietHours(enabled: Boolean, startHour: Int, endHour: Int) {}
    val weatherIntervalHours: Flow<Int> get() = flowOf(1)
    suspend fun setWeatherIntervalHours(hours: Int) {}

    companion object {
        const val DEFAULT_WORKOUT_SPORT_TYPE = 1
    }
}
