package au.buzz.ryzewave.ui

import au.buzz.ryzewave.protocol.SportTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutMetricsTest {

    /** GPS present and producing distance: the numbers come from GPS, plainly labelled, never marked estimated. */
    @Test
    fun gpsPresentUsesGps() {
        val m = WorkoutMetrics.compute(
            gpsDistanceMeters = 2000.0,
            gpsPaceSecPerKm = 300.0,
            gpsAvailable = true,
            gpsStale = false,
            steps = 5000,
            walkStrideMeters = 0.70,
            runStrideMeters = 0.95,
            sportType = SportTypes.OUTDOOR_RUNNING,
            elapsedSeconds = 600,
        )

        assertFalse(m.estimated)
        assertEquals(WorkoutMetrics.LABEL_GPS, m.distanceLabel)

        // Internally distance remains metres.
        assertEquals(2000.0, m.distanceMeters, 0.0)

        // 2000 m = 1.2427... miles.
        assertEquals("1.24 mi", m.distanceText)

        // Internally pace remains seconds per kilometre.
        assertEquals(300.0, m.paceSecPerKm, 0.0)

        // 5:00 /km = ~8:03 /mi.
        assertEquals("8:03 /mi", m.paceText)
    }

    /** No GPS distance but the watch is counting steps: distance = steps x stride, pace derived, both flagged est. */
    @Test
    fun gpsAbsentWithStepsUsesStepEstimate() {
        val m = WorkoutMetrics.compute(
            gpsDistanceMeters = 0.0,
            gpsPaceSecPerKm = 0.0,
            gpsAvailable = false,
            gpsStale = false,
            steps = 2000,
            walkStrideMeters = 0.70,
            runStrideMeters = 0.95,
            sportType = SportTypes.OUTDOOR_RUNNING,
            elapsedSeconds = 600,
        )

        assertTrue(m.estimated)
        assertEquals(WorkoutMetrics.LABEL_STEPS, m.distanceLabel)

        // Running sport uses the configured running stride.
        assertEquals(
            2000 * 0.95,
            m.distanceMeters,
            1e-9,
        )

        // 1900 m = 1.1806... miles.
        assertEquals("1.18 mi", m.distanceText)

        // Internal pace remains seconds/km.
        assertEquals(
            600.0 / (2000 * 0.95) * 1000.0,
            m.paceSecPerKm,
            1e-9,
        )

        assertTrue(
            "estimated pace is tagged",
            m.paceText.endsWith(WorkoutMetrics.EST_SUFFIX),
        )

        // ~5:16 /km = ~8:28 /mi.
        assertTrue(m.paceText.startsWith("8:"))
    }

    /** Neither GPS distance nor steps: pace stays a bare "--:-- /mi" (never a misleading estimate). */
    @Test
    fun nothingGivesDashes() {
        val m = WorkoutMetrics.compute(
            gpsDistanceMeters = 0.0,
            gpsPaceSecPerKm = 0.0,
            gpsAvailable = false,
            gpsStale = false,
            steps = null,
            walkStrideMeters = 0.70,
            runStrideMeters = 0.95,
            sportType = SportTypes.OUTDOOR_RUNNING,
            elapsedSeconds = 30,
        )

        assertFalse(m.estimated)
        assertEquals(WorkoutMetrics.LABEL_GPS, m.distanceLabel)
        assertEquals("0 m", m.distanceText)
        assertEquals("--:-- /mi", m.paceText)
    }

    /** Zero steps is the same as none. */
    @Test
    fun zeroStepsGivesDashes() {
        val m = WorkoutMetrics.compute(
            gpsDistanceMeters = 0.0,
            gpsPaceSecPerKm = 0.0,
            gpsAvailable = true,
            gpsStale = false,
            steps = 0,
            walkStrideMeters = 0.70,
            runStrideMeters = 0.95,
            sportType = SportTypes.OUTDOOR_RUNNING,
            elapsedSeconds = 30,
        )

        assertFalse(m.estimated)
        assertEquals("--:-- /mi", m.paceText)
    }

    /** GPS worked then went stale (location off mid-run): switch to the step estimate. */
    @Test
    fun staleGpsMidRunSwitchesToSteps() {
        val m = WorkoutMetrics.compute(
            gpsDistanceMeters = 1500.0,
            gpsPaceSecPerKm = 0.0,
            gpsAvailable = true,
            gpsStale = true,
            steps = 2500,
            walkStrideMeters = 0.70,
            runStrideMeters = 0.90,
            sportType = SportTypes.OUTDOOR_RUNNING,
            elapsedSeconds = 900,
        )

        assertTrue(m.estimated)
        assertEquals(WorkoutMetrics.LABEL_STEPS, m.distanceLabel)
        assertEquals(
            2500 * 0.90,
            m.distanceMeters,
            1e-9,
        )
    }

    /** GPS reported unavailable while its distance is frozen > 0: not "live", so the estimate fills in. */
    @Test
    fun gpsUnavailableWithFrozenDistanceUsesSteps() {
        val m = WorkoutMetrics.compute(
            gpsDistanceMeters = 1200.0,
            gpsPaceSecPerKm = 0.0,
            gpsAvailable = false,
            gpsStale = false,
            steps = 1000,
            walkStrideMeters = 0.70,
            runStrideMeters = 0.95,
            sportType = SportTypes.OUTDOOR_WALKING,
            elapsedSeconds = 800,
        )

        assertTrue(m.estimated)

        // Walking sport uses the configured walking stride.
        assertEquals(
            1000 * 0.70,
            m.distanceMeters,
            1e-9,
        )
    }

    /** An estimate with no elapsed time yet cannot form a pace: distance still shown, pace stays dashes. */
    @Test
    fun estimateWithoutElapsedHasNoPace() {
        val m = WorkoutMetrics.compute(
            gpsDistanceMeters = 0.0,
            gpsPaceSecPerKm = 0.0,
            gpsAvailable = false,
            gpsStale = false,
            steps = 100,
            walkStrideMeters = 0.70,
            runStrideMeters = 0.95,
            sportType = SportTypes.OUTDOOR_WALKING,
            elapsedSeconds = 0,
        )

        assertTrue(m.estimated)
        assertEquals(
            100 * 0.70,
            m.distanceMeters,
            1e-9,
        )
        assertEquals("--:-- /mi", m.paceText)
    }

    @Test
    fun strideSelectionBySportAndCadence() {
        // Running sport -> run stride; walking sport -> walk stride.
        assertEquals(
            0.95,
            WorkoutMetrics.strideFor(
                SportTypes.OUTDOOR_RUNNING,
                1000,
                300,
                0.70,
                0.95,
            ),
            1e-9,
        )

        assertEquals(
            0.70,
            WorkoutMetrics.strideFor(
                SportTypes.OUTDOOR_WALKING,
                1000,
                300,
                0.70,
                0.95,
            ),
            1e-9,
        )

        // Ambiguous sport (Free Training 0x19):
        // high cadence -> run, low cadence -> walk.
        val fastCadenceSteps =
            WorkoutMetrics.RUN_CADENCE_SPM.toInt() + 20

        assertEquals(
            0.95,
            WorkoutMetrics.strideFor(
                0x19,
                fastCadenceSteps,
                60,
                0.70,
                0.95,
            ),
            1e-9,
        )

        assertEquals(
            0.70,
            WorkoutMetrics.strideFor(
                0x19,
                90,
                60,
                0.70,
                0.95,
            ),
            1e-9,
        )

        // A missing stride falls back to the other so the estimate still works.
        assertEquals(
            0.95,
            WorkoutMetrics.strideFor(
                SportTypes.OUTDOOR_WALKING,
                1000,
                300,
                0.0,
                0.95,
            ),
            1e-9,
        )

        assertEquals(
            0.70,
            WorkoutMetrics.strideFor(
                SportTypes.OUTDOOR_RUNNING,
                1000,
                300,
                0.70,
                0.0,
            ),
            1e-9,
        )
    }

    /** `of(WorkoutUiState)` wires the state fields straight through to [WorkoutMetrics.compute]. */
    @Test
    fun ofReadsTheUiState() {
        val state = WorkoutUiState(
            phase = WorkoutPhase.RUNNING,
            sportType = SportTypes.OUTDOOR_RUNNING,
            elapsedSeconds = 600,
            distanceMeters = 0.0,
            paceSecPerKm = 0.0,
            gpsAvailable = false,
            gpsStale = false,
            steps = 2000,
            walkStrideMeters = 0.70,
            runStrideMeters = 0.95,
        )

        val m = WorkoutMetrics.of(state)

        assertTrue(m.estimated)
        assertEquals(
            2000 * 0.95,
            m.distanceMeters,
            1e-9,
        )
    }
}
