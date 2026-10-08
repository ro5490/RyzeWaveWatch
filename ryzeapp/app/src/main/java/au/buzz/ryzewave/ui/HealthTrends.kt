package au.buzz.ryzewave.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
                val gridColor = MaterialTheme.colorScheme.outlineVariant
                var zoom by remember(rangeDays, heartRate) { mutableIntStateOf(1) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { zoom = (zoom - 1).coerceAtLeast(1) }, enabled = zoom > 1) { Text("−") }
                    OutlinedButton(onClick = { zoom = (zoom + 1).coerceAtMost(8) }, enabled = zoom < 8) { Text("+") }
                    TextButton(onClick = { zoom = 1 }, enabled = zoom != 1) { Text("Reset zoom") }
                }
                val scroll = rememberScrollState()
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val viewportWidth = maxWidth
                    Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
                        val graphWidth = viewportWidth * zoom.toFloat()
                        Canvas(Modifier.width(graphWidth).height(210.dp)) {
                            val top = 12.dp.toPx()
                            val bottom = size.height - 14.dp.toPx()
                            val graphHeight = bottom - top
                            for (j in 0..4) {
                                val y = top + graphHeight * j / 4f
                                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                            }
                            val dx = if (count > 1) size.width / (count - 1) else 0f
                            val radius = 3.dp.toPx()
                            var previous: Offset? = null
                            values.forEachIndexed { i, value ->
                                if (value == null) {
                                    previous = null // Never join a gap in recorded data.
                                } else {
                                    val point = Offset(i * dx, bottom - (value / maximum).toFloat() * graphHeight)
                                    previous?.let { drawLine(color, it, point, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round) }
                                    drawCircle(color, radius, point)
                                    previous = point
                                }
                            }
                        }
                    }
                }
                Text(if (rangeDays == 365) "12 chronological periods · oldest to newest" else "Daily values · oldest to newest", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
