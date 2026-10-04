package au.buzz.ryzewave.ui

import au.buzz.ryzewave.protocol.SportTypes

/**
 * The live Distance + Pace shown on the Workout card, choosing between the phone's GPS track and a step-based
 * estimate. Pure (no Android), so the choice and formatting are unit tested.
 *
 * GPS is the default source and stays authoritative for the stored workout ([WorkoutUiState.distanceMeters] is the
 * GPS distance — this helper never changes it). The step estimate is a *display aid* that only fills in when GPS is
 * not currently producing distance — location off / no accepted fix for a while (`gpsStale`), or no GPS at all
 * (distance still 0) — AND the watch has reported session steps. Then distance = steps x stride and pace is derived
 * from it, both clearly labelled "(from steps)" / "(est)" so they are never confused with a GPS measurement.
 *
 * Internally distance remains metres and pace remains seconds per kilometre. Display formatting converts these
 * values to UK-style miles and minutes per mile.
 */
data class WorkoutMetrics(
    /** Distance to show (metres): the GPS distance, or the step estimate when [estimated]. */
    val distanceMeters: Double,

    /** Label for the distance stat: [LABEL_GPS] or [LABEL_STEPS]. */
    val distanceLabel: String,

    /** Formatted distance, e.g. "1.20 mi" / "450 m" / "0 m". */
    val distanceText: String,

    /** Internal pace in seconds per kilometre. */
    val paceSecPerKm: Double,

    /** Formatted pace, e.g. "8:22 /mi", "9:55 /mi (est)", or "--:-- /mi". */
    val paceText: String,

    /** True when both figures come from the step estimate rather than GPS. */
    val estimated: Boolean,
) {
    companion object {
        const val LABEL_GPS = "Distance (GPS)"
        const val LABEL_STEPS = "Distance (from steps)"
        const val EST_SUFFIX = " (est)"
        const val NO_PACE = "--:-- /mi"

        /** Cadence (steps/min) at or above which the running stride is used when the sport does not decide it. */
        const val RUN_CADENCE_SPM = 140.0

        /** Sports whose distance is naturally a running stride. */
        private val RUN_SPORTS = setOf(
            SportTypes.OUTDOOR_RUNNING,
            0x15, // Treadmill
            0x1B, // Indoor Running
            0x24, // Trail Running
            0x73, // Marathon
        )

        /** Sports that are clearly walking. */
        private val WALK_SPORTS = setOf(
            0x08, // Hiking
            0x09, // Walking
            SportTypes.OUTDOOR_WALKING,
        )

        fun of(state: WorkoutUiState): WorkoutMetrics = compute(
            gpsDistanceMeters = state.distanceMeters,
            gpsPaceSecPerKm = state.paceSecPerKm,
            gpsAvailable = state.gpsAvailable,
            gpsStale = state.gpsStale,
            steps = state.steps,
            walkStrideMeters = state.walkStrideMeters,
            runStrideMeters = state.runStrideMeters,
            sportType = state.sportType,
            elapsedSeconds = state.elapsedSeconds,
        )

        /** Picks the walk or run stride (metres/step) for [sportType], breaking ambiguous sports on live cadence. */
        fun strideFor(
            sportType: Int,
            steps: Int?,
            elapsedSeconds: Int,
            walkStrideMeters: Double,
            runStrideMeters: Double,
        ): Double {
            val walk =
                walkStrideMeters.takeIf { it.isFinite() && it > 0.0 }
                    ?: runStrideMeters

            val run =
                runStrideMeters.takeIf { it.isFinite() && it > 0.0 }
                    ?: walkStrideMeters

            return when (sportType) {
                in RUN_SPORTS -> run
                in WALK_SPORTS -> walk

                else -> {
                    val cadenceSpm =
                        if (steps != null && steps > 0 && elapsedSeconds > 0) {
                            steps * 60.0 / elapsedSeconds
                        } else {
                            0.0
                        }

                    if (cadenceSpm >= RUN_CADENCE_SPM) run else walk
                }
            }
        }

        fun compute(
            gpsDistanceMeters: Double,
            gpsPaceSecPerKm: Double,
            gpsAvailable: Boolean,
            gpsStale: Boolean,
            steps: Int?,
            walkStrideMeters: Double,
            runStrideMeters: Double,
            sportType: Int,
            elapsedSeconds: Int,
        ): WorkoutMetrics {
            val gpsLive =
                gpsAvailable &&
                    !gpsStale &&
                    gpsDistanceMeters > 0.0

            val stride = strideFor(
                sportType = sportType,
                steps = steps,
                elapsedSeconds = elapsedSeconds,
                walkStrideMeters = walkStrideMeters,
                runStrideMeters = runStrideMeters,
            )

            val canEstimate =
                !gpsLive &&
                    steps != null &&
                    steps > 0 &&
                    stride.isFinite() &&
                    stride > 0.0

            if (canEstimate) {
                val dist = steps!! * stride

                val pace =
                    if (dist > 0.0 && elapsedSeconds > 0) {
                        elapsedSeconds / dist * 1000.0
                    } else {
                        0.0
                    }

                val base = Fmt.pace(pace)

                return WorkoutMetrics(
                    distanceMeters = dist,
                    distanceLabel = LABEL_STEPS,
                    distanceText = Fmt.metres(dist),
                    paceSecPerKm = pace,

                    // Fmt.pace renders unknown / absurd pace as "--:-- /mi".
                    // Only a real pace receives the "(est)" suffix.
                    paceText =
                        if (base.startsWith("--")) {
                            NO_PACE
                        } else {
                            base + EST_SUFFIX
                        },

                    estimated = true,
                )
            }

            return WorkoutMetrics(
                distanceMeters = gpsDistanceMeters,
                distanceLabel = LABEL_GPS,
                distanceText = Fmt.metres(gpsDistanceMeters),
                paceSecPerKm = gpsPaceSecPerKm,
                paceText = Fmt.pace(gpsPaceSecPerKm),
                estimated = false,
            )
        }
    }
}
