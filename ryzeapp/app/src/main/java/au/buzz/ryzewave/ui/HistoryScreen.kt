@file:OptIn(ExperimentalMaterial3Api::class)

package au.buzz.ryzewave.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import au.buzz.ryzewave.core.DailySummary
import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SampleSource
import au.buzz.ryzewave.core.SleepStage
import au.buzz.ryzewave.core.Spo2Sample
import au.buzz.ryzewave.core.StepsHour

/**
 * History: one shared date navigator, then five chart cards (heart rate, SpO2, steps per hour, daily steps +
 * distance, the night's sleep) styled like the vendor app's blood-oxygen card: coloured header with the chart, a stats row and an
 * expandable list of the day's values.
 */
@Composable
fun HistoryScreen(vm: HistoryViewModel = viewModel()) {
    val day by vm.day.collectAsStateWithLifecycle()
    val hr by vm.hr.collectAsStateWithLifecycle()
    val spo2 by vm.spo2.collectAsStateWithLifecycle()
    val steps by vm.steps.collectAsStateWithLifecycle()
    val sleep by vm.sleep.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val daily by vm.daily.collectAsStateWithLifecycle()
    val range by vm.rangeDays.collectAsStateWithLifecycle()
    val healthRange by vm.healthRangeDays.collectAsStateWithLifecycle()
    val hrTrend by vm.hrTrend.collectAsStateWithLifecycle()
    val sleepTrend by vm.sleepTrend.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DayNavigator(day, onPrevious = vm::previousDay, onNext = vm::nextDay, onToday = vm::today)
        HealthRangePicker(healthRange, vm::setHealthRange)
        if (healthRange == 1) HrCard(day, hr)
        else HealthTrendCard("Heart rate", day, healthRange, hrTrend, sleepTrend, true)
        val firmware by au.buzz.ryzewave.App.graph.watch.status.collectAsStateWithLifecycle()
        if (!firmware.firmware.orEmpty().startsWith("RB112UDG", ignoreCase = true)) Spo2Card(day, spo2)
        StepsHourCard(day, steps, summary, profile.stepGoal)
        DailyCard(daily, range, profile.stepGoal, day, onRange = vm::setRange, onSelectDay = vm::selectDay)
        if (healthRange == 1) SleepCard(day, sleep)
        else HealthTrendCard("Sleep", day, healthRange, hrTrend, sleepTrend, false)
    }
}

@Composable
fun DayNavigator(day: Long, onPrevious: () -> Unit, onNext: () -> Unit, onToday: () -> Unit) {
    val today = Fmt.isToday(day)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) { Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous day") }
        Column(Modifier.clickable(onClick = onToday), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Fmt.longDate(day), style = MaterialTheme.typography.titleMedium)
            Text(
                if (today) "Today" else "${Fmt.weekday(day)} · tap for today",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onNext, enabled = !today) { Icon(Icons.Filled.ChevronRight, contentDescription = "Next day") }
    }
}

private fun dataTitle(day: Long): String = if (Fmt.isToday(day)) "Today's data" else "${Fmt.date(day)} data"

private fun sourceTag(source: SampleSource): String = when (source) {
    SampleSource.HISTORY -> ""
    SampleSource.LIVE -> " · live"
    SampleSource.WORKOUT -> " · workout"
    SampleSource.AUTO -> " · auto"
}

@Composable
private fun HrCard(day: Long, samples: List<HrSample>) {
    val series = remember(samples) { ChartData.hrPoints(samples.filter { it.source == SampleSource.HISTORY }) }
    val dots = remember(samples) { ChartData.hrPoints(samples.filter { it.source != SampleSource.HISTORY }) }
    val stats = remember(samples) {
        // the same mean (same samples, same rounding) as the chart's "avg" label
        if (samples.isEmpty()) emptyList() else listOf(
            "Minimum" to "${samples.minOf { it.bpm }} bpm",
            "Average" to "${ChartData.meanValue(ChartData.hrPoints(samples))} bpm",
            "Maximum" to "${samples.maxOf { it.bpm }} bpm",
        )
    }
    val rows = remember(samples) {
        samples.sortedByDescending { it.time }.map { Fmt.time(it.time) to "${it.bpm} bpm${sourceTag(it.source)}" }
    }
    ChartCard(
        title = "Heart rate",
        container = MaterialTheme.colorScheme.errorContainer,
        stats = stats, rowsTitle = dataTitle(day), rows = rows,
    ) { colors -> HrDayChart(series, dots, day, colors = colors) }
}

@Composable
private fun Spo2Card(day: Long, samples: List<Spo2Sample>) {
    val series = remember(samples) { ChartData.spo2Points(samples.filter { it.source == SampleSource.HISTORY || it.source == SampleSource.AUTO }) }
    val spots = remember(samples) { ChartData.spo2Points(samples.filter { it.source == SampleSource.LIVE || it.source == SampleSource.WORKOUT }) }
    val stats = remember(samples) {
        if (samples.isEmpty()) emptyList() else listOf(
            "Minimum SpO2" to "${samples.minOf { it.percent }} %",
            "Maximum SpO2" to "${samples.maxOf { it.percent }} %",
        )
    }
    val rows = remember(samples) {
        samples.sortedByDescending { it.time }.map { Fmt.time(it.time) to "${it.percent} %${sourceTag(it.source)}" }
    }
    ChartCard(
        title = "Blood oxygen",
        container = MaterialTheme.colorScheme.primaryContainer,
        stats = stats, rowsTitle = dataTitle(day), rows = rows,
    ) { colors -> Spo2DayChart(series, spots, day, colors = colors) }
}

@Composable
private fun StepsHourCard(day: Long, steps: List<StepsHour>, summary: DailySummary?, goal: Int) {
    val slots = remember(steps, day) { ChartData.hourSlots(steps, day) }
    val total = summary?.steps ?: steps.sumOf { it.total }
    val stats = remember(summary, steps) {
        if (steps.isEmpty()) emptyList() else listOf(
            "Total" to Fmt.int(total),
            "Walk" to Fmt.int(summary?.walkSteps ?: steps.sumOf { it.walk }),
            "Run" to Fmt.int(summary?.runSteps ?: steps.sumOf { it.run }),
            "Distance" to Fmt.km(summary?.distanceMeters ?: 0.0),
        )
    }
    val rows = remember(slots) {
        slots.withIndex().reversed().mapNotNull { (i, s) ->
            if (s == null || s.total <= 0) null
            else Fmt.hourLabel(i) to "${Fmt.int(s.total)} (walk ${Fmt.int(s.walk)}, run ${Fmt.int(s.run)})"
        }
    }
    ChartCard(
        title = "Steps per hour",
        container = MaterialTheme.colorScheme.tertiaryContainer,
        stats = stats, rowsTitle = dataTitle(day), rows = rows,
    ) { colors -> StepsHourChart(slots, goal, colors = colors) }
}

@Composable
private fun DailyCard(
    days: List<DailySummary>,
    range: Int,
    goal: Int,
    selectedDay: Long,
    onRange: (Int) -> Unit,
    onSelectDay: (Long) -> Unit,
) {
    val todayStart = Fmt.dayStart()
    val total = days.sumOf { it.steps }
    val withData = days.count { it.steps > 0 }
    val stats = remember(days) {
        if (total == 0) emptyList() else listOf(
            "Total" to Fmt.int(total),
            "Average / day" to Fmt.int(if (withData > 0) total / withData else 0),
            "Distance" to Fmt.km(days.sumOf { it.distanceMeters }),
        )
    }
    val rows = remember(days) {
        days.reversed().filter { it.steps > 0 }.map { Fmt.date(it.dayStart) to "${Fmt.int(it.steps)} steps · ${Fmt.kmShort(it.distanceMeters)}" }
    }
    ChartCard(
        title = "Daily steps & distance",
        container = MaterialTheme.colorScheme.secondaryContainer,
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                UiDefaults.HISTORY_RANGES.forEach { d ->
                    FilterChip(selected = range == d, onClick = { onRange(d) }, label = { Text(when (d) { 7 -> "Week"; 30 -> "Month"; 365 -> "Year"; else -> "$d d" }) })
                }
            }
        },
        stats = stats, rowsTitle = "Last $range days", rows = rows,
    ) { colors ->
        DailyStepsChart(days, todayStart, goal, selectedDay = selectedDay, onDaySelected = onSelectDay, colors = colors)
    }
}

/**
 * The night that ended on the selected morning (previous noon .. noon, `repo.sleepForNight`): hypnogram, the
 * totals line, bed / rise times, and the stage list in bed-to-rise order.
 */
@Composable
private fun SleepCard(day: Long, stages: List<SleepStage>) {
    val chart = remember(stages) { SleepChartData.build(stages) }
    val stats = remember(chart) {
        if (chart == null) emptyList() else listOf(
            "Bed" to Fmt.time(chart.start),
            "Rise" to Fmt.time(chart.end),
            "Asleep" to SleepChartData.hoursMinutes(chart.summary.totalMin),
            "Awake" to SleepChartData.hoursMinutes(chart.summary.awakeMin),
        )
    }
    val rows = remember(chart) {
        chart?.blocks.orEmpty().map { b -> Fmt.time(b.start) to "${SleepMath.stageName(b.stage)} · ${b.minutes} min" }
    }
    val rowsTitle = if (Fmt.isToday(day)) "Last night's stages" else "Stages, night to ${Fmt.date(day)}"
    ChartCard(
        title = "Sleep",
        container = MaterialTheme.colorScheme.surfaceVariant,
        stats = stats, rowsTitle = rowsTitle, rows = rows,
    ) { colors ->
        Column {
            SleepHypnogram(chart, colors = colors)
            if (chart != null) {
                Spacer(Modifier.height(8.dp))
                Text(SleepChartData.totalsLine(chart.summary), style = MaterialTheme.typography.bodySmall)
                Text(
                    "Bed ${Fmt.time(chart.start)} · rise ${Fmt.time(chart.end)} · ${Fmt.date(chart.start)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * The vendor-style card: coloured header holding the title and chart, a stats row, then an expandable
 * "Today's data (n)" list of time / value rows.
 */
@Composable
fun ChartCard(
    title: String,
    container: Color,
    stats: List<Pair<String, String>>,
    rowsTitle: String,
    rows: List<Pair<String, String>>,
    trailing: (@Composable () -> Unit)? = null,
    chart: @Composable (ChartColors) -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Surface(color = container, contentColor = contentColorFor(container)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    trailing?.invoke()
                }
                Spacer(Modifier.height(8.dp))
                var zoom by rememberSaveable(title + "-zoom") { mutableStateOf(1) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Zoom", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { zoom = (zoom - 1).coerceAtLeast(1) }, enabled = zoom > 1) { Text("−") }
                    Text("${zoom}×", style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { zoom = (zoom + 1).coerceAtMost(8) }, enabled = zoom < 8) { Text("+") }
                    TextButton(onClick = { zoom = 1 }, enabled = zoom != 1) { Text("Reset") }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val viewportWidth = maxWidth
                    androidx.compose.foundation.layout.Box(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    ) {
                        CompositionLocalProvider(LocalChartWidth provides viewportWidth * zoom) {
                            chart(chartColors(dotFill = container))
                        }
                    }
                }
            }
        }
        if (stats.isNotEmpty()) StatsRow(stats)
        HorizontalDivider()
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text("$rowsTitle (${rows.size})", modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
        if (expanded) DataRows(rows)
    }
}

@Composable
private fun StatsRow(stats: List<Pair<String, String>>) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        stats.forEach { (label, value) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DataRows(rows: List<Pair<String, String>>) {
    Column(Modifier.padding(bottom = 8.dp)) {
        if (rows.isEmpty()) {
            Text(
                "Nothing recorded",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        rows.take(MAX_ROWS).forEach { (time, value) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(time, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (rows.size > MAX_ROWS) {
            Text(
                "… and ${rows.size - MAX_ROWS} more",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

private const val MAX_ROWS = 300
