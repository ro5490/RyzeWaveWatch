package au.buzz.ryzewave.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutFormatTest {

    @Test
    fun elapsed() {
        assertEquals("00:00", WorkoutFormat.elapsed(0))
        assertEquals("01:05", WorkoutFormat.elapsed(65))
        assertEquals("1:01:01", WorkoutFormat.elapsed(3661))
        assertEquals("00:00", WorkoutFormat.elapsed(-5))
    }

    @Test
    fun pace() {
        // 312 sec/km = ~8:22 /mi.
        assertEquals("8:22 /mi", WorkoutFormat.pace(312.0))

        assertEquals("--:-- /mi", WorkoutFormat.pace(0.0))
        assertEquals("--:-- /mi", WorkoutFormat.pace(Double.NaN))
        assertEquals("--:-- /mi", WorkoutFormat.pace(6000.0))
    }

    @Test
    fun distanceAndSpeed() {
        assertEquals("0 m", WorkoutFormat.distance(0.0))
        assertEquals("999 m", WorkoutFormat.distance(999.0))

        // UK presentation keeps distances below one mile in metres.
        assertEquals("1234 m", WorkoutFormat.distance(1234.0))

        // 2000 m = 1.24 miles.
        assertEquals("1.24 mi", WorkoutFormat.distance(2000.0))

        // 1.5 m/s = ~3.4 mph.
        assertEquals("3.4 mph", WorkoutFormat.speed(1.5))
    }

    @Test
    fun gpsLabel() {
        assertEquals("no GPS", WorkoutFormat.gps(null, false))
        assertEquals("GPS searching", WorkoutFormat.gps(null, true))
        assertEquals("GPS ±8 m", WorkoutFormat.gps(8.4f, true))
    }

    @Test
    fun notificationLines() {
        val st = WorkoutState(
            state = WorkoutPhase.RUNNING,
            elapsedSeconds = 754,
            distanceMeters = 2000.0,
            paceSecPerKm = 312.0,
            speedMps = 3.2,
            calories = 96,
            lastHr = 132,
            avgHr = 125,
            maxHr = 140,
            gpsAccuracyM = 8f,
            gpsAvailable = true,
            trackPointCount = 700,
            acceptedPointCount = 680,
        )

        assertEquals(
            "12:34 · 1.24 mi · 8:22 /mi · HR 132 · GPS ±8 m",
            WorkoutFormat.summary(st),
        )

        val detail = WorkoutFormat.detail(st)

        assertTrue(
            detail.contains(
                "Time 12:34   Distance 1.24 mi",
            ),
        )

        assertTrue(
            detail.contains(
                "Pace 8:22 /mi   Speed 7.2 mph",
            ),
        )

        assertTrue(
            detail.contains(
                "HR 132 (avg 125, max 140)   96 kcal",
            ),
        )

        assertTrue(
            detail.contains(
                "GPS ±8 m (680/700 fixes used)",
            ),
        )

        assertEquals(
            "00:00 · 0 m · --:-- /mi · no GPS",
            WorkoutFormat.summary(WorkoutState()),
        )
    }

    @Test
    fun stateDerivedValues() {
        // Internal calculations deliberately remain metric.
        assertEquals(
            300.0,
            WorkoutState(
                elapsedSeconds = 600,
                distanceMeters = 2000.0,
            ).averagePaceSecPerKm,
            1e-9,
        )

        assertEquals(
            0.0,
            WorkoutState(
                elapsedSeconds = 600,
            ).averagePaceSecPerKm,
            0.0,
        )

        assertFalse(WorkoutState().isActive)
        assertTrue(
            WorkoutState(
                state = WorkoutPhase.PAUSED,
            ).isActive,
        )
        assertFalse(
            WorkoutState(
                state = WorkoutPhase.PAUSED,
            ).isRunning,
        )
        assertTrue(
            WorkoutState(
                state = WorkoutPhase.RUNNING,
            ).isRunning,
        )
    }
}
