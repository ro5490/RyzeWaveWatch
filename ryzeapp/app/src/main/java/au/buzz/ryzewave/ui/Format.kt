package au.buzz.ryzewave.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

/** UI-wide constants. */
object UiDefaults {
    const val WATCH_MAC = "78:02:B7:60:59:0F"
    const val WATCH_NAME_PREFIX = "P32 SmartWatch"
    const val DEFAULT_SPORT_TYPE = 1
    val SPO2_INTERVALS = listOf(5, 10, 20, 30, 60)
    val HR_HIGH_ALARMS = listOf(140, 150, 160, 170, 180)   // watch buzzes when HR rises above the chosen value
    val HR_LOW_ALARMS = listOf(40, 45, 50, 55, 60, 65, 70) // watch buzzes when HR falls below it (kept well under sleeping HR)
    val HISTORY_RANGES = listOf(7, 30)
}

/** The watch MAC as typed in Settings > Watch: normalised (trimmed, upper-cased) only when compared and saved. */
object MacText {
    /** `aa:bb:cc:dd:ee:ff` / ` 78:02:b7:37:91:e5 ` -> `AA:BB:CC:DD:EE:FF` / `78:02:B7:37:91:E5`. */
    fun normalise(text: String): String = text.trim().uppercase(Locale.ROOT)

    private val MAC_RE = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

    /** True when [normalise] of [text] is a colon-separated 48-bit address. */
    fun isValid(text: String): Boolean = MAC_RE.matches(normalise(text))
}

/** Time / number formatting helpers. Times are epoch milliseconds in the phone's local zone. */
object Fmt {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
    private val longDateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
    private val shortDateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d/M", Locale.getDefault())
    private val weekdayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
    private val dateTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.getDefault())

    private const val METERS_PER_MILE = 1609.344

    fun time(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(timeFmt)
    fun date(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(dateFmt)
    fun longDate(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(longDateFmt)
    fun shortDate(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(shortDateFmt)
    fun weekday(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(weekdayFmt)
    fun dateTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(dateTimeFmt)

    /** "8,432" */
    fun int(n: Int): String = String.format(Locale.getDefault(), "%,d", n)

    /** "8k", "8.5k", "950". */
    fun compact(v: Double): String {
        if (v >= 1000.0) {
            val k = v / 1000.0
            return if (k == floor(k)) "${k.toInt()}k" else String.format(Locale.US, "%.1fk", k)
        }
        return value(v)
    }

    /** Whole numbers without decimals, otherwise one decimal. */
    fun value(v: Double): String =
        if (v == floor(v)) v.toInt().toString() else String.format(Locale.US, "%.1f", v)

    /**
     * Distance in miles.
     *
     * The function name is retained as km() so existing call sites do not
     * need to be changed. The underlying value is still supplied in metres.
     *
     * Example: 5000 metres -> "3.11 mi"
     */
    fun km(meters: Double): String =
        String.format(Locale.US, "%.2f mi", meters / METERS_PER_MILE)

    /**
     * Short distance in miles.
     *
     * Example: 5000 metres -> "3.1 mi"
     */
    fun kmShort(meters: Double): String =
        String.format(Locale.US, "%.1f mi", meters / METERS_PER_MILE)

    /**
     * Display short distances in metres and longer distances in miles.
     *
     * Examples:
     * 850 metres  -> "850 m"
     * 1200 metres -> "1200 m"
     * 1609 metres -> "1.00 mi"
     */
    fun metres(meters: Double): String =
        if (meters < METERS_PER_MILE) {
            "${meters.roundToInt()} m"
        } else {
            km(meters)
        }

    /**
     * Convert seconds per kilometre to seconds per mile for display.
     *
     * Example:
     * 5:00 /km -> 8:03 /mi
     */
    fun pace(secPerKm: Double): String {
        if (secPerKm.isNaN() || secPerKm <= 0.0 || secPerKm > 3600.0) {
            return "--:-- /mi"
        }

        val secPerMile = secPerKm * 1.609344
        val s = secPerMile.roundToInt()

        return String.format(
            Locale.US,
            "%d:%02d /mi",
            s / 60,
            s % 60,
        )
    }

    /**
     * Imperial pace without the unit, for chart axes.
     *
     * Input remains seconds per kilometre. The displayed value is converted
     * to the equivalent minutes/seconds per mile.
     */
    fun paceShort(secPerKm: Double): String {
        if (secPerKm.isNaN() || secPerKm <= 0.0 || secPerKm > 3600.0) {
            return "--:--"
        }

        val secPerMile = secPerKm * 1.609344
        val s = secPerMile.roundToInt()

        return String.format(
            Locale.US,
            "%d:%02d",
            s / 60,
            s % 60,
        )
    }

    /** "h:mm:ss" or "mm:ss". */
    fun duration(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%02d:%02d", m, sec)
    }

    /** Elapsed time for chart ticks: "12:30" (mm:ss) below an hour, "1:02:00" above. */
    fun elapsed(seconds: Int): String = duration(seconds)

    fun relative(ms: Long?, now: Long = System.currentTimeMillis()): String {
        if (ms == null) return "never"
        val d = (now - ms).coerceAtLeast(0) / 1000
        return when {
            d < 60 -> "just now"
            d < 3600 -> "${d / 60} min ago"
            d < 86400 -> "${d / 3600} h ago"
            else -> date(ms)
        }
    }

    fun minutes(min: Int): String =
        if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"

    fun hourLabel(hour: Int): String =
        String.format(Locale.US, "%02d:00", hour)

    // ---- day arithmetic (local midnight)

    fun dayStart(ms: Long = System.currentTimeMillis()): Long =
        Instant.ofEpochMilli(ms)
            .atZone(zone)
            .toLocalDate()
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    fun plusDays(dayStart: Long, n: Long): Long =
        Instant.ofEpochMilli(dayStart)
            .atZone(zone)
            .toLocalDate()
            .plusDays(n)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    fun dayEnd(dayStart: Long): Long = plusDays(dayStart, 1)

    fun localDate(ms: Long): LocalDate =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun isToday(dayStart: Long): Boolean = dayStart == dayStart()
}
