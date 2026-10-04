package au.buzz.ryzewave.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FmtTest {
    @Test
    fun paceFormatsMinutesAndSeconds() {
        // 312 sec/km (5:12 /km) = ~502 sec/mi = 8:22 /mi.
        assertEquals("8:22 /mi", Fmt.pace(312.0))
        assertEquals("--:-- /mi", Fmt.pace(0.0))
        assertEquals("--:-- /mi", Fmt.pace(Double.NaN))
        assertEquals("--:-- /mi", Fmt.pace(4000.0))
        assertEquals("8:22", Fmt.paceShort(312.0))
    }

    @Test
    fun durationSwitchesToHoursAboveSixtyMinutes() {
        assertEquals("00:05", Fmt.duration(5))
        assertEquals("12:30", Fmt.duration(750))
        assertEquals("1:02:03", Fmt.duration(3723))
        assertEquals("00:00", Fmt.duration(-4))
    }

    @Test
    fun distanceAndCompactNumbers() {
        assertEquals("950 m", Fmt.metres(950.0))

        // UK-style presentation keeps sub-mile distances in metres.
        assertEquals("1500 m", Fmt.metres(1500.0))

        // 6120 m = 3.8028... miles.
        assertEquals("3.8 mi", Fmt.kmShort(6_120.0))

        assertEquals("8k", Fmt.compact(8000.0))
        assertEquals("8.5k", Fmt.compact(8500.0))
        assertEquals("500", Fmt.compact(500.0))
        assertEquals("97", Fmt.value(97.0))
        assertEquals("96.5", Fmt.value(96.5))
        assertEquals("14:00", Fmt.hourLabel(14))
    }

    @Test
    fun minutesAndRelative() {
        assertEquals("45 min", Fmt.minutes(45))
        assertEquals("7 h 5 min", Fmt.minutes(425))
        val now = 1_800_000_000_000L
        assertEquals("never", Fmt.relative(null, now))
        assertEquals("just now", Fmt.relative(now - 5_000, now))
        assertEquals("3 min ago", Fmt.relative(now - 200_000, now))
        assertEquals("2 h ago", Fmt.relative(now - 7_200_000, now))
    }

    @Test
    fun dayArithmeticIsLocalMidnight() {
        val start = Fmt.dayStart()
        assertEquals(start, Fmt.dayStart(start + 12 * 3_600_000L))
        assertEquals(Fmt.plusDays(start, 1), Fmt.dayEnd(start))
        assertEquals(start, Fmt.plusDays(Fmt.plusDays(start, -3), 3))
    }
}
