package au.buzz.ryzewave.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SleepStage
import kotlin.math.max

/** Shared day/week/month/year period selection for both health charts. */
@Composable
fun HealthRangePicker(days: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(1 to "Day", 7 to "Week", 30 to "Month", 365 to "Year").forEach { (n, label) ->
            FilterChip(selected = days == n, onClick = { onChange(n) }, label = { Text(label) })
        }
    }
}

/** Missing days remain gaps, never zero measurements. Year view groups into 12 approximately monthly buckets. */
@Composable
fun HealthTrendCard(
    title: String,
    selectedDay: Long,
    rangeDays: Int,
    hr: List<HrSample>,
    sleep: List<SleepStage>,
    heartRate: Boolean,
) {
    val count = if (rangeDays == 365) 12 else rangeDays
    val values = Array<Double?>(count) { null }
    val start = Fmt.plusDays(selectedDay, 1L - rangeDays)
    fun bucket(day: Long): Int {
        val elapsed = java.time.temporal.ChronoUnit.DAYS.between(
            java.time.Instant.ofEpochMilli(start).atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
            java.time.Instant.ofEpochMilli(day).atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
        ).toInt()
        return if (rangeDays == 365) elapsed * 12 / 365 else elapsed
    }
    if (heartRate) {
        val groups = hr.filter { it.bpm > 0 }.groupBy { bucket(it.time) }
        for ((i, rows) in groups) if (i in 0 until count) values[i] = rows.map { it.bpm }.average()
    } else {
        // A night is attributed to its wake-up day (noon-to-noon).
        val minutes = DoubleArray(count)
        val present = BooleanArray(count)
        for (stage in sleep) {
            if (stage.stage == SleepStage.AWAKE || stage.minutes <= 0) continue
            val wakeDay = Fmt.dayStart(stage.start + 12L * 60 * 60 * 1000)
            val i = bucket(wakeDay)
            if (i in 0 until count) {
                minutes[i] = minutes[i] + stage.minutes.toDouble()
                present[i] = true
            }
        }
        for (i in 0 until count) if (present[i]) values[i] = minutes[i] / 60.0
    }
    val valid = values.filterNotNull()
    val maximum = max(if (heartRate) 120.0 else 10.0, (valid.maxOrNull() ?: 0.0) * 1.1)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$title · ${if (rangeDays == 365) "Year" else if (rangeDays == 30) "Month" else "Week"}", style = MaterialTheme.typography.titleMedium)
            if (valid.isEmpty()) Text("No recorded data in this period")
            else {
                Text("${valid.size} periods with data · average ${"%.1f".format(valid.average())} ${if (heartRate) "bpm" else "h asleep"}")
                val color = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxWidth().height(190.dp)) {
                    val gap = size.width / count
                    values.forEachIndexed { i, value ->
                        if (value != null) {
                            val x = (i + 0.5f) * gap
                            val y = size.height * (1f - (value / maximum).toFloat())
                            drawLine(color, Offset(x, size.height), Offset(x, y), strokeWidth = (gap * 0.65f).coerceAtLeast(1f))
                        }
                    }
                }
                Text(if (rangeDays == 365) "12 chronological periods · oldest to newest" else "Daily values · oldest to newest", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
