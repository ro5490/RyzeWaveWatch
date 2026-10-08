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

/** Longer periods reuse the exact Day chart renderer and card. */
@Composable
fun HealthTrendCard(
    title: String, selectedDay: Long, rangeDays: Int,
    hr: List<HrSample>, sleep: List<SleepStage>, heartRate: Boolean,
) {
    val count = if (rangeDays == 365) 12 else rangeDays
    val start = Fmt.plusDays(selectedDay, 1L - rangeDays)
    val zone = java.time.ZoneId.systemDefault()
    val startDate = java.time.Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
    fun bucket(day: Long): Int {
        val date = java.time.Instant.ofEpochMilli(day).atZone(zone).toLocalDate()
        val elapsed = java.time.temporal.ChronoUnit.DAYS.between(startDate, date).toInt()
        return if (rangeDays == 365) elapsed * 12 / 365 else elapsed
    }
    val values = Array<Double?>(count) { null }
    if (heartRate) {
        hr.filter { it.bpm > 0 }.groupBy { bucket(it.time) }.forEach { (i, rows) ->
            if (i in 0 until count) values[i] = rows.map { it.bpm }.average()
        }
    } else {
        val minutes = DoubleArray(count)
        val present = BooleanArray(count)
        sleep.forEach { stage ->
            if (stage.stage != SleepStage.AWAKE && stage.minutes > 0) {
                val i = bucket(Fmt.dayStart(stage.start + 12L * 60 * 60 * 1000))
                if (i in 0 until count) {
                    minutes[i] = minutes[i] + stage.minutes.toDouble()
                    present[i] = true
                }
            }
        }
        for (i in 0 until count) if (present[i]) values[i] = minutes[i] / 60.0
    }
    val valid = values.filterNotNull()
    val end = Fmt.plusDays(selectedDay, 1)
    val periodName = when (rangeDays) { 7 -> "Week"; 30 -> "Month"; else -> "Year" }
    val timestamps = (0 until count).map { i ->
        if (rangeDays == 365) Fmt.plusDays(start, i * 365L / 12)
        else Fmt.plusDays(start, i.toLong())
    }
    val points = values.mapIndexedNotNull { i, v -> v?.let { Pt(timestamps[i], it) } }
    val ticks = (0 until count).filter { i ->
        count <= 7 || i == 0 || i == count - 1 ||
            i % (if (count == 12) 2 else 5) == 0
    }.map { i ->
        val date = java.time.Instant.ofEpochMilli(timestamps[i]).atZone(zone).toLocalDate()
        val label = if (rangeDays == 365) date.month.name.take(3).lowercase()
            .replaceFirstChar { it.uppercase() }
        else "${date.dayOfMonth}/${date.monthValue}"
        timestamps[i].toDouble() to label
    }
    val unit = if (heartRate) "bpm" else "h"
    val stats = if (valid.isEmpty()) emptyList() else listOf(
        "Minimum" to "${"%.1f".format(valid.minOrNull())} $unit",
        "Average" to "${"%.1f".format(valid.average())} $unit",
        "Maximum" to "${"%.1f".format(valid.maxOrNull())} $unit",
    )
    val rows = values.mapIndexedNotNull { i, v ->
        v?.let {
            val date = java.time.Instant.ofEpochMilli(timestamps[i]).atZone(zone).toLocalDate()
            date.toString() to "${"%.1f".format(it)} $unit"
        }
    }
    ChartCard(
        title = "$title · $periodName",
        container = if (heartRate) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.secondaryContainer,
        stats = stats, rowsTitle = "$periodName data", rows = rows,
    ) { colors ->
        HealthPeriodLineChart(points, start, end, ticks, heartRate, colors)
    }
}
