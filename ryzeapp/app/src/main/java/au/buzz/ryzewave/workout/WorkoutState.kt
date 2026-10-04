package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.HrSample
import java.util.Locale
import kotlin.math.roundToInt

/** Lifecycle of the current workout. STOPPED is also the idle state before anything has been started. */
enum class WorkoutPhase { RUNNING, PAUSED, STOPPED }

/** Who ended the workout: the app's button, the watch's button, or the stuck-workout detector's auto-stop. */
enum class StopReason { USER, WATCH, NO_ACTIVITY }

/**
 * Live view of the workout, published by [WorkoutController.state] once per second and on every GPS fix /
 * HR sample. After [WorkoutController.stop] the phase is STOPPED and the final numbers stay in place so the
 * UI can show a summary ([workoutId] points at the stored row).
 */
data class WorkoutState(
    val state: WorkoutPhase = WorkoutPhase.STOPPED,
    val workoutId: Long? = null,
    val sportType: Int = 1,
    val startTime: Long? = null,

    /** Active seconds (pauses excluded). */
    val elapsedSeconds: Int = 0,

    val distanceMeters: Double = 0.0,

    /** Rolling-window pace in seconds per km; 0.0 = unknown / not moving. */
    val paceSecPerKm: Double = 0.0,

    val speedMps: Double = 0.0,

    /** MET-based kcal estimate (speed × weight). */
    val calories: Int = 0,

    /** Best belief of the current heart rate: the watch's reading, or the estimate while the watch is judged wrong. */
    val lastHr: Int? = null,

    val avgHr: Int? = null,
    val maxHr: Int? = null,

    /** What physiology predicts right now ([WorkoutController]'s [HrEstimator]); null before the first sample. */
    val hrEstimate: Int? = null,

    /** True while the watch's readings are being rejected as implausible (a wrist dropout). */
    val hrSuspect: Boolean = false,

    /** Something to say about the heart-rate reading; [hrWarningSeq] is bumped each time it is set. */
    val hrWarning: String? = null,

    val hrWarningSeq: Int = 0,

    /** All HR samples received since start (source WORKOUT), for the live chart. */
    val hrSamples: List<HrSample> = emptyList(),

    /** Accuracy of the most recent fix in metres; null until the first fix. */
    val gpsAccuracyM: Float? = null,

    /** False when the location provider says GPS is unavailable (or before the first fix). */
    val gpsAvailable: Boolean = false,

    /**
     * True while GPS distance has stopped growing — no accepted fix for longer than [WorkoutController.STALE_FIX_MS]
     * (a tunnel, indoors, or location switched off mid-run). The live UI switches to the step-based estimate then.
     */
    val gpsStale: Boolean = false,

    val trackPointCount: Int = 0,
    val acceptedPointCount: Int = 0,

    /** Watch's per-session step count (max seen); null until the first realtime push carries one. */
    val steps: Int? = null,

    /** Metres per walking / running step for this session's profile+calibration, for the step-based distance estimate. */
    val walkStrideMeters: Double = 0.0,
    val runStrideMeters: Double = 0.0,

    /** Phone step-counter tally for this workout (paused steps excluded); null when unavailable. */
    val phoneSteps: Int? = null,

    /** Last watch/database problem, cleared on the next successful watch call. The workout keeps running. */
    val error: String? = null,

    /**
     * Problem reported by the host service (missing location permission, GPS unavailable, foreground start
     * refused). Independent of [error]: it stays until the service clears it, so a watch echo cannot wipe it.
     */
    val hostError: String? = null,

    /** Bumped on every [error] / [hostError] change, so a UI transition can notice a fresh failure. */
    val errorSeq: Int = 0,

    /** Why the last workout ended (set with the STOPPED phase); null while active or before any workout. */
    val stopReason: StopReason? = null,
) {
    /** Everything worth showing, host problems first. */
    val message: String?
        get() = listOfNotNull(hostError, error)
            .joinToString(" · ")
            .ifEmpty { null }

    val isActive: Boolean
        get() = state != WorkoutPhase.STOPPED

    val isRunning: Boolean
        get() = state == WorkoutPhase.RUNNING

    /** Average pace over the whole workout (s/km), 0.0 when there is no distance yet. */
    val averagePaceSecPerKm: Double
        get() =
            if (distanceMeters > 0.0 && elapsedSeconds > 0) {
                elapsedSeconds / distanceMeters * 1000.0
            } else {
                0.0
            }
}

/** Text helpers shared by the notification and the UI. Pure Kotlin. */
object WorkoutFormat {

    private const val METERS_PER_MILE = 1609.344
    private const val MPS_TO_MPH = 2.2369362920544

    fun elapsed(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60

        return if (h > 0) {
            String.format(
                Locale.ROOT,
                "%d:%02d:%02d",
                h,
                m,
                sec,
            )
        } else {
            String.format(
                Locale.ROOT,
                "%02d:%02d",
                m,
                sec,
            )
        }
    }

    /**
     * Input remains seconds per kilometre internally.
     * Display is converted to minutes/seconds per mile.
     */
    fun pace(secPerKm: Double): String {
        if (
            secPerKm <= 0.0 ||
            !secPerKm.isFinite() ||
            secPerKm > 5999.0
        ) {
            return "--:-- /mi"
        }

        val secPerMile = secPerKm * 1.609344
        val total = secPerMile.roundToInt()

        return String.format(
            Locale.ROOT,
            "%d:%02d /mi",
            total / 60,
            total % 60,
        )
    }

    /**
     * UK-style distance display:
     * under one mile remains metres, otherwise miles.
     */
    fun distance(meters: Double): String =
        if (meters < METERS_PER_MILE) {
            String.format(
                Locale.ROOT,
                "%d m",
                meters.toInt(),
            )
        } else {
            String.format(
                Locale.ROOT,
                "%.2f mi",
                meters / METERS_PER_MILE,
            )
        }

    /** Converts metres/second to miles/hour for display. */
    fun speed(mps: Double): String =
        String.format(
            Locale.ROOT,
            "%.1f mph",
            mps * MPS_TO_MPH,
        )

    fun gps(
        accuracyM: Float?,
        available: Boolean,
    ): String = when {
        accuracyM == null ->
            if (available) {
                "GPS searching"
            } else {
                "no GPS"
            }

        else ->
            String.format(
                Locale.ROOT,
                "GPS ±%d m",
                accuracyM.toInt(),
            )
    }

    /**
     * One-line notification summary, for example:
     * "12:34 · 1.23 mi · 8:22 /mi · HR 132 · GPS ±8 m"
     */
    fun summary(st: WorkoutState): String = buildString {
        append(elapsed(st.elapsedSeconds))
            .append(" · ")
            .append(distance(st.distanceMeters))

        append(" · ")
            .append(pace(st.paceSecPerKm))

        st.lastHr?.let {
            append(" · HR ").append(it)
        }

        append(" · ")
            .append(gps(st.gpsAccuracyM, st.gpsAvailable))
    }

    /** Expanded notification text, one metric group per line. */
    fun detail(st: WorkoutState): String = listOf(
        "Time ${elapsed(st.elapsedSeconds)}   Distance ${distance(st.distanceMeters)}",
        "Pace ${pace(st.paceSecPerKm)}   Speed ${speed(st.speedMps)}",
        "HR ${st.lastHr ?: "--"} (avg ${st.avgHr ?: "--"}, max ${st.maxHr ?: "--"})   ${st.calories} kcal",
        "${gps(st.gpsAccuracyM, st.gpsAvailable)} (${st.acceptedPointCount}/${st.trackPointCount} fixes used)",
    ).joinToString("\n")
}
