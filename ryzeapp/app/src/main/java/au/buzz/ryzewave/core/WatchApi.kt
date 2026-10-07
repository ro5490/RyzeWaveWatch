package au.buzz.ryzewave.core

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One blood-pressure result reported by the watch.
 *
 * This represents the watch's legacy UTE/GloryFit BP estimate.
 * It is not intended to imply clinical/medical accuracy.
 */
data class BloodPressureReading(
    val systolic: Int,
    val diastolic: Int,
    val time: Long = System.currentTimeMillis(),
)

/**
 * Watch-face capabilities reported by the classic GloryFit/UTE `26 01`
 * configuration query.
 */
data class WatchFaceConfig(
    val dialNumber: Long,
    val width: Int,
    val height: Int,
    val screenType: Int,
    val maxDataSize: Long,
    val compatibleLevel: Int?,
    val cornerAngle: Int?,
)


data class WatchFaceUploadProgress(
    val bytesSent: Long,
    val totalBytes: Long,
)

/**
 * Everything the app can ask the watch to do. Implemented by the BLE layer (`ble.WatchService` +
 * `ble.WatchGatt`) and exposed through `App.graph.watch`. All suspend functions run on the BLE
 * queue: one GATT operation at a time, replies matched by opcode as in ../../../../../docs/PROTOCOL.md.
 */
interface WatchApi {
    val status: StateFlow<WatchStatus>

    /** Live heart-rate samples from `E5 11 00 <hr>` (dynamic mode) and `FD 01 <hr>` (workout). */
    val liveHr: SharedFlow<HrSample>

    /** Unsolicited packets the UI may want to react to (SpO2 result pushes, HR summaries, find-phone…). */
    val events: SharedFlow<WatchEvent>

    suspend fun connect(mac: String)
    suspend fun disconnect()

    /** Set time, user profile, goals and sampling settings — done on every connect (a factory-reset watch has none). */
    suspend fun applySettings(profile: UserProfile, sampling: SamplingSettings)

    /** Fetch steps, HR, SpO2 and sleep history since the last sync and persist them through the repository. */
    suspend fun syncAll(): SyncResult

    suspend fun startLiveHr()
    suspend fun stopLiveHr()

    /** Runs a spot SpO2 test (about 60 s). Returns the percentage, or null on failure/timeout. */
    suspend fun spo2SpotTest(): Int?

    /**
     * Runs the legacy UTE/GloryFit blood-pressure spot test.
     *
     * P32/RB112UDG uses C7 11 to start measurement and reports a completed
     * result as C7 00 00 <systolic> <diastolic>.
     */

    suspend fun bloodPressureSpotTest(): BloodPressureReading? = null

    /**
     * Reads the watch-face configuration using the classic GloryFit/UTE
     * `26 01` query. This is read-only and does not prepare or upload a dial.
     */
    suspend fun getWatchFaceConfig(): WatchFaceConfig? = null

    /**
     * Installs a validated classic GloryFit `.BIN` watch face.
     *
     * Implementations must reject malformed containers before sending `26 02`.
     * Returns true only after the watch reports `26 03 00`.
     */
    suspend fun uploadWatchFace(
        data: ByteArray,
        onProgress: (WatchFaceUploadProgress) -> Unit = {},
    ): Boolean = false

    suspend fun startWorkout(sportType: Int = 1)

    /**
     * Ask the watch whether a sport screen is open and which (`FD AA` -> `FD AA <state> <type>`), the nearest
     * thing to reading its display. Null when the watch does not answer. Default no-op for fakes.
     */
    suspend fun queryWorkout(): au.buzz.ryzewave.protocol.SportState? = null

    /** Push live metrics to the watch face once per second while a workout runs (`FD 44`). */
    suspend fun updateWorkout(
        durationSeconds: Int,
        distanceMeters: Double,
        paceSecPerKm: Double,
        calories: Int
    )

    suspend fun pauseWorkout()
    suspend fun resumeWorkout()
    suspend fun stopWorkout()

    suspend fun findWatch()
    suspend fun readBattery(): Int?

    /**
     * Pushes one notification text to the watch (`C5` chunks, each acked, then `C5 FD`). [type] is the icon per
     * docs/PROTOCOL.md §6 (never 0 = call from the listener); [text] is "<app or sender>: <body>", sanitised and
     * cut at 127 characters by the protocol layer. Sends are serialised (one burst at a time). Returns true when
     * the watch acknowledged the whole message, false when it was skipped (not connected, empty text).
     * Additive member: the default is "not sent" so existing implementations keep compiling.
     */
    suspend fun sendNotification(type: Int, text: String): Boolean = false
}

/** A workout control the *watch* originated (its physical buttons), to be applied to the app's controller. */
enum class WorkoutControlAction {
    START,
    PAUSE,
    RESUME,
    STOP
}

sealed class WatchEvent {
    data class Spo2Result(
        val time: Long,
        val percent: Int?
    ) : WatchEvent()

    data class HrSummary(
        val time: Long,
        val max: Int,
        val min: Int,
        val avg: Int
    ) : WatchEvent()

    data class RealtimeSteps(
        val stepsHour: StepsHour
    ) : WatchEvent()

    /** `D1 0A 01` (the watch is looking for the phone: ring) / `D1 0A 00` (stop ringing). */
    data class FindPhone(
        val start: Boolean
    ) : WatchEvent()

    /**
     * A pause/resume/stop the user pressed on the *watch* (an unsolicited `FD 22`/`FD 33`/`FD 00`, not the echo
     * of a command the app just sent). The workout controller applies it exactly as it does the app's own
     * buttons, without sending the control back to the watch (which already changed state itself).
     */
    data class WorkoutControl(
        val action: WorkoutControlAction
    ) : WatchEvent()

    /**
     * The 14-byte realtime workout push (`FD <type> <hr> …`): [steps] is the watch's per-session step count
     * (bytes 7-9, rising through the workout). The controller keeps the maximum as the workout's step total.
     */
    data class WorkoutRealtime(
        val sportType: Int,
        val steps: Int,
        val calories: Int,
        val distanceMeters: Double
    ) : WatchEvent()

    data class Raw(
        val channel: String,
        val hex: String
    ) : WatchEvent()
}
