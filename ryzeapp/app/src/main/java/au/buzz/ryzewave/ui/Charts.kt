@file:OptIn(ExperimentalTextApi::class)

package au.buzz.ryzewave.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import au.buzz.ryzewave.core.DailySummary
import au.buzz.ryzewave.core.StepsHour
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * Hand-drawn Compose Canvas charts (no chart library). Every chart: fillMaxWidth().height(200.dp), axis ticks,
 * theme aware (colours come from the surrounding content colour + Material scheme), empty-state text when
 * there is no data, tap to read a value.
 */

val ChartHeight: Dp = 200.dp

/** A chart can be wider than its viewport when the user zooms in. */
val LocalChartWidth = compositionLocalOf<androidx.compose.ui.unit.Dp?> { null }

@Composable
fun Modifier.chartSize(): Modifier {
    val width = LocalChartWidth.current
    return (if (width == null) this.fillMaxWidth() else this.width(width)).height(ChartHeight)
}

/** Colours a chart draws with; defaults derive from the current content colour so they work on any card. */
data class ChartColors(
    val line: Color,
    val secondary: Color,
    val text: Color,
    val grid: Color,
    val bar: Color,
    val bar2: Color,
    val accent: Color,
    /** Background behind the chart (hollow dot centres, tooltip text). */
    val dotFill: Color,
)

@Composable
fun chartColors(dotFill: Color = MaterialTheme.colorScheme.surface): ChartColors {
    val content = LocalContentColor.current
    val scheme = MaterialTheme.colorScheme
    return ChartColors(
        line = content,
        secondary = scheme.tertiary,
        text = content.copy(alpha = 0.85f),
        grid = content.copy(alpha = 0.35f),
        bar = scheme.primary,
        bar2 = scheme.tertiary,
        accent = scheme.error,
        dotFill = dotFill,
    )
}

@Composable
fun EmptyChart(text: String, modifier: Modifier = Modifier) {
    Box(modifier.chartSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current.copy(alpha = 0.7f),
        )
    }
}

// ---- shared drawing -------------------------------------------------------------------------------------

@Composable
private fun rememberGutters(
    measurer: TextMeasurer, style: TextStyle,
    leftLabels: List<String>, rightLabels: List<String>, topLabels: Boolean = false,
): Gutters {
    val density = LocalDensity.current
    return remember(leftLabels, rightLabels, style, topLabels) {
        val pad = with(density) { 6.dp.toPx() }
        val labelH = measurer.measure("00:00", style).size.height.toFloat()
        val leftW = (leftLabels.maxOfOrNull { measurer.measure(it, style).size.width } ?: 0).toFloat()
        val rightW = (rightLabels.maxOfOrNull { measurer.measure(it, style).size.width } ?: 0).toFloat()
        Gutters(
            left = leftW + pad,
            right = if (rightLabels.isEmpty()) pad * 2 else rightW + pad,
            top = if (topLabels) labelH + pad else labelH / 2f + 2f,
            bottom = labelH + pad / 2f,
        )
    }
}

private val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))

private fun DrawScope.drawHGrid(
    f: ChartFrame, values: List<Double>, labels: List<String>,
    m: TextMeasurer, style: TextStyle, colors: ChartColors,
) {
    val pad = 4.dp.toPx()
    values.forEachIndexed { i, v ->
        val y = f.y(v)
        drawLine(colors.grid, Offset(f.left, y), Offset(f.right, y), strokeWidth = 1.dp.toPx(), pathEffect = dash)
        val label = labels.getOrNull(i) ?: return@forEachIndexed
        val s = m.measure(label, style).size
        drawText(m, label, Offset(f.left - s.width - pad, y - s.height / 2f), style)
    }
}

/** Labels on the right edge at plot fractions (0 = bottom, 1 = top). */
private fun DrawScope.drawRightLabels(
    f: ChartFrame, fractions: List<Double>, labels: List<String>,
    m: TextMeasurer, style: TextStyle,
) {
    val pad = 4.dp.toPx()
    fractions.forEachIndexed { i, fr ->
        val label = labels.getOrNull(i) ?: return@forEachIndexed
        val y = f.bottom - (fr * f.height).toFloat()
        val s = m.measure(label, style).size
        drawText(m, label, Offset(f.right + pad, y - s.height / 2f), style)
    }
}

private fun DrawScope.drawXTicks(
    f: ChartFrame, ticks: List<Pair<Double, String>>,
    m: TextMeasurer, style: TextStyle, colors: ChartColors,
) {
    val tick = 3.dp.toPx()
    drawLine(colors.grid, Offset(f.left, f.bottom), Offset(f.right, f.bottom), strokeWidth = 1.dp.toPx())
    for ((v, label) in ticks) {
        val x = f.x(v)
        drawLine(colors.grid, Offset(x, f.bottom), Offset(x, f.bottom + tick), strokeWidth = 1.dp.toPx())
        val s = m.measure(label, style).size
        val left = (x - s.width / 2f).coerceIn(0f, (size.width - s.width).coerceAtLeast(0f))
        drawText(m, label, Offset(left, f.bottom + tick), style)
    }
}

private fun DrawScope.drawPolyline(f: ChartFrame, pts: List<Pt>, color: Color, width: Float, effect: PathEffect? = null) {
    if (pts.size < 2) return
    val path = Path()
    pts.forEachIndexed { i, p ->
        val x = f.x(p.time.toDouble())
        val y = f.y(p.value)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
}

private fun DrawScope.drawHollowDot(center: Offset, radius: Float, color: Color, fill: Color) {
    drawCircle(fill, radius, center)
    drawCircle(color, radius, center, style = Stroke(width = 1.5.dp.toPx()))
}

private fun DrawScope.drawTooltip(
    f: ChartFrame, x: Float, y: Float, text: String,
    m: TextMeasurer, style: TextStyle, colors: ChartColors,
) {
    drawLine(colors.grid, Offset(x, f.top), Offset(x, f.bottom), strokeWidth = 1.dp.toPx())
    drawCircle(colors.accent, 4.dp.toPx(), Offset(x, y))
    val s = m.measure(text, style).size
    val padX = 6.dp.toPx()
    val padY = 3.dp.toPx()
    val w = s.width + 2 * padX
    val h = s.height + 2 * padY
    val left = (x - w / 2f).coerceIn(0f, (size.width - w).coerceAtLeast(0f))
    var top = y - h - 8.dp.toPx()
    if (top < 0f) top = (y + 8.dp.toPx()).coerceAtMost((size.height - h).coerceAtLeast(0f))
    drawRoundRect(colors.line, Offset(left, top), Size(w, h), CornerRadius(4.dp.toPx()))
    drawText(m, text, Offset(left + padX, top + padY), style.copy(color = colors.dotFill))
}

/**
 * Where a value label next to a point goes: centred on the point, above or below it, kept inside the plot's x range
 * (with a small inset so it never touches the y-axis label column, e.g. SpO2's "100" tick and a "99" point label).
 */
private fun DrawScope.floatingLabelBox(f: ChartFrame, text: String, x: Float, y: Float, above: Boolean, m: TextMeasurer, style: TextStyle): Box {
    val s = m.measure(text, style).size
    val inset = 4.dp.toPx()
    val left = (x - s.width / 2f).coerceIn(f.left + inset, (f.right - s.width).coerceAtLeast(f.left + inset))
    val top = if (above) (y - s.height - 3.dp.toPx()).coerceAtLeast(0f) else (y + 3.dp.toPx()).coerceAtMost(size.height - s.height)
    return Box(left, top, left + s.width, top + s.height)
}

private fun DrawScope.drawFloatingLabel(box: Box, text: String, m: TextMeasurer, style: TextStyle) {
    drawText(m, text, Offset(box.left, box.top), style)
}

/**
 * A label that annotates a horizontal reference line (the HR "avg", the steps "goal"): drawn just above or below the
 * line, at the plot edge or position where it hides the least data ([LabelLayout.pick] over the candidates — right
 * edge first, then left, centre, quarter points; above before below), on a translucent card-coloured pill so the line
 * or dots that remain underneath cannot cut through the glyphs. Returns the box it occupied.
 */
private fun DrawScope.drawLineLabel(
    f: ChartFrame, lineY: Float, text: String, m: TextMeasurer, style: TextStyle, fill: Color,
    points: List<Pair<Float, Float>>, pointRadius: Float, polylines: List<List<Pair<Float, Float>>>,
    blocks: List<Box> = emptyList(), avoid: List<Box> = emptyList(),
): Box {
    val s = m.measure(text, style).size
    val padX = 3.dp.toPx()
    val padY = 1.dp.toPx()
    val w = s.width + 2 * padX
    val h = s.height + 2 * padY
    val gap = 2.dp.toPx()
    val above = (lineY - gap - h).coerceAtLeast(f.top - padY)
    val below = (lineY + gap).coerceAtMost(f.bottom - h)
    val xs = listOf(f.right - w, f.left, f.left + (f.width - w) / 2f, f.left + (f.width - w) * 0.25f, f.left + (f.width - w) * 0.75f)
    val candidates = xs.flatMap { x -> listOf(Box(x, above, x + w, above + h), Box(x, below, x + w, below + h)) }
    val box = candidates[LabelLayout.pick(candidates, points, pointRadius, polylines, blocks, avoid)]
    drawRoundRect(fill.copy(alpha = 0.85f), Offset(box.left, box.top), Size(w, h), CornerRadius(3.dp.toPx()))
    drawText(m, text, Offset(box.left + padX, box.top + padY), style)
    return box
}

/** Ticks every [stepHours] hours across a local day (positions in epoch ms). */
private fun dayTicks(dayStart: Long, stepHours: Int = 6): List<Pair<Double, String>> =
    (0..24 step stepHours).map { h -> Pair((dayStart + h * ChartData.HOUR_MS).toDouble(), Fmt.hourLabel(h)) }

// ---- 1 + 2: time-of-day line charts (HR, SpO2) ------------------------------------------------------

/**
 * Heart rate for one day: the 10-minute `F7` series as a line broken at missing bins, dots for live /
 * workout / auto samples, min / max / avg labels, tap to read a value. Y axis 40..180 (extends upwards).
 */
@Composable
fun HrDayChart(
    series: List<Pt>,
    samples: List<Pt>,
    dayStart: Long,
    modifier: Modifier = Modifier,
    colors: ChartColors = chartColors(),
) {
    TimeLineChart(
        series = series, samples = samples,
        xMin = dayStart, xMax = Fmt.dayEnd(dayStart),
        yMin = 40.0, gridValues = listOf(40.0, 75.0, 110.0, 145.0, 180.0),
        xTicks = dayTicks(dayStart), unit = "bpm",
        maxGapMs = 15 * ChartData.MINUTE_MS, showAvg = true,
        emptyText = "No heart rate data for this day",
        colors = colors, modifier = modifier,
    )
}

/** SpO2 for one day: the 10-minute series plus spot tests; y axis 85..100 % with the vendor's 88/91/94/97/100 lines. */
@Composable
fun Spo2DayChart(
    series: List<Pt>,
    spots: List<Pt>,
    dayStart: Long,
    modifier: Modifier = Modifier,
    colors: ChartColors = chartColors(),
) {
    TimeLineChart(
        series = series, samples = spots,
        xMin = dayStart, xMax = Fmt.dayEnd(dayStart),
        yMin = 85.0, gridValues = listOf(88.0, 91.0, 94.0, 97.0, 100.0),
        xTicks = dayTicks(dayStart), unit = "%",
        maxGapMs = 2 * ChartData.HOUR_MS, showAvg = false,
        emptyText = "No blood oxygen data for this day",
        colors = colors, modifier = modifier,
    )
}

@Composable
private fun TimeLineChart(
    series: List<Pt>,
    samples: List<Pt>,
    xMin: Long,
    xMax: Long,
    yMin: Double,
    gridValues: List<Double>,
    xTicks: List<Pair<Double, String>>,
    unit: String,
    maxGapMs: Long,
    showAvg: Boolean,
    emptyText: String,
    colors: ChartColors,
    modifier: Modifier,
) {
    if (series.isEmpty() && samples.isEmpty()) {
        EmptyChart(emptyText, modifier)
        return
    }
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = colors.text)
    val all = remember(series, samples) { (series + samples).sortedBy { it.time } }
    val grid = remember(gridValues, all) { extendGrid(gridValues, all.maxOf { it.value }) }
    val yLabels = remember(grid) { grid.map { Fmt.value(it) } }
    val gutters = rememberGutters(measurer, style, yLabels, emptyList())
    val segments = remember(series, maxGapMs) { ChartData.segments(series, maxGapMs) }
    val minPt = remember(all) { all.minByOrNull { it.value } }
    val maxPt = remember(all) { all.maxByOrNull { it.value } }
    val avg = remember(all) { all.map { it.value }.average() }
    val yMax = grid.last()
    var selected by remember(all) { mutableStateOf<Pt?>(null) }

    Canvas(
        modifier
            .chartSize()
            .pointerInput(all) {
                detectTapGestures { pos ->
                    val f = ChartFrame.of(size.width.toFloat(), size.height.toFloat(), gutters, xMin.toDouble(), xMax.toDouble(), yMin, yMax)
                    selected = nearestByX(all, f, pos.x, 24.dp.toPx())
                }
            },
    ) {
        val f = ChartFrame.of(size.width, size.height, gutters, xMin.toDouble(), xMax.toDouble(), yMin, yMax)
        drawHGrid(f, grid, yLabels, measurer, style, colors)
        drawXTicks(f, xTicks, measurer, style, colors)
        for (seg in segments) {
            if (seg.size == 1) {
                val p = seg[0]
                drawHollowDot(Offset(f.x(p.time.toDouble()), f.y(p.value)), 2.5.dp.toPx(), colors.line, colors.dotFill)
            } else {
                drawPolyline(f, seg, colors.line, 2.dp.toPx())
            }
        }
        if (series.size <= 200) {
            for (p in series) drawHollowDot(Offset(f.x(p.time.toDouble()), f.y(p.value)), 2.5.dp.toPx(), colors.line, colors.dotFill)
        }
        for (p in samples) drawCircle(colors.accent, 3.dp.toPx(), Offset(f.x(p.time.toDouble()), f.y(p.value)))
        val extremes = if (all.size > 1 && maxPt != null && minPt != null && maxPt !== minPt) listOf(
            Fmt.value(maxPt.value) to floatingLabelBox(f, Fmt.value(maxPt.value), f.x(maxPt.time.toDouble()), f.y(maxPt.value), true, measurer, style),
            Fmt.value(minPt.value) to floatingLabelBox(f, Fmt.value(minPt.value), f.x(minPt.time.toDouble()), f.y(minPt.value), false, measurer, style),
        ) else emptyList()
        if (showAvg && all.size > 1) {
            val y = f.y(avg)
            drawLine(colors.secondary, Offset(f.left, y), Offset(f.right, y), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            // the label goes where it covers the fewest dots / line segments and never over the min / max labels
            val px = all.map { f.x(it.time.toDouble()) to f.y(it.value) }
            val lines = segments.map { seg -> seg.map { f.x(it.time.toDouble()) to f.y(it.value) } }
            drawLineLabel(
                f, y, "avg ${ChartData.meanValue(all)}", measurer, style.copy(color = colors.secondary), colors.dotFill,
                points = px, pointRadius = 3.dp.toPx(), polylines = lines, avoid = extremes.map { it.second },
            )
        }
        for ((text, box) in extremes) drawFloatingLabel(box, text, measurer, style)
        selected?.let { p ->
            drawTooltip(f, f.x(p.time.toDouble()), f.y(p.value), "${Fmt.time(p.time)} · ${Fmt.value(p.value)} $unit", measurer, style, colors)
        }
    }
}

// ---- 3: steps per hour ----------------------------------------------------------------------------------

/**
 * Steps per hour for one day: walk (bar) and run (bar2) stacked, the cumulative total as a line on a right
 * axis and the daily goal as a dashed line on that axis. Tap a bar to read it.
 */
@Composable
fun StepsHourChart(
    slots: List<StepsHour?>,
    goal: Int,
    modifier: Modifier = Modifier,
    colors: ChartColors = chartColors(),
) {
    val total = slots.sumOf { it?.total ?: 0 }
    if (slots.size != 24 || total <= 0) {
        EmptyChart("No steps recorded for this day", modifier)
        return
    }
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = colors.text)
    val cum = remember(slots) { ChartData.cumulativeSteps(slots) }
    val leftTop = remember(slots) { ChartData.niceCeil(slots.maxOf { (it?.total ?: 0).toDouble() }) }
    val rightTop = remember(slots, goal) { ChartData.niceCeil(maxOf(goal, total).toDouble()) }
    val leftGrid = remember(leftTop) { listOf(0.0, 0.25, 0.5, 0.75, 1.0).map { it * leftTop } }
    val leftLabels = remember(leftGrid) { leftGrid.map { Fmt.compact(it) } }
    val rightFractions = listOf(0.0, 0.5, 1.0)
    val rightLabels = remember(rightTop) { rightFractions.map { Fmt.compact(it * rightTop) } }
    val gutters = rememberGutters(measurer, style, leftLabels, rightLabels)
    val lastIdx = slots.indexOfLast { it != null }
    val ticks = remember { (0..24 step 6).map { Pair(it.toDouble(), Fmt.hourLabel(it)) } }
    var selected by remember(slots) { mutableIntStateOf(-1) }

    Canvas(
        modifier
            .chartSize()
            .pointerInput(slots) {
                detectTapGestures { pos ->
                    val f = ChartFrame.of(size.width.toFloat(), size.height.toFloat(), gutters, 0.0, 24.0, 0.0, leftTop)
                    val idx = floor(f.xValue(pos.x)).toInt()
                    selected = if (pos.x in f.left..f.right && idx in 0..23 && idx != selected) idx else -1
                }
            },
    ) {
        val f = ChartFrame.of(size.width, size.height, gutters, 0.0, 24.0, 0.0, leftTop)
        drawHGrid(f, leftGrid, leftLabels, measurer, style, colors)
        drawRightLabels(f, rightFractions, rightLabels, measurer, style.copy(color = colors.secondary))
        drawXTicks(f, ticks, measurer, style, colors)
        val slotW = f.width / 24f
        val barW = slotW * 0.7f
        for (i in 0 until 24) {
            val s = slots[i] ?: continue
            if (s.total <= 0) continue
            val run = s.run.coerceIn(0, s.total)
            val walk = s.total - run
            val x0 = f.left + i * slotW + (slotW - barW) / 2f
            val yWalk = f.y(walk.toDouble())
            val yTop = f.y(s.total.toDouble())
            if (walk > 0) drawRect(colors.bar, Offset(x0, yWalk), Size(barW, f.bottom - yWalk))
            if (run > 0) drawRect(colors.bar2, Offset(x0, yTop), Size(barW, yWalk - yTop))
            if (i == selected) drawRect(colors.accent, Offset(x0, yTop), Size(barW, f.bottom - yTop), style = Stroke(width = 2.dp.toPx()))
        }
        // cumulative total on the right axis
        val cumLine = ArrayList<Pair<Float, Float>>()
        if (lastIdx >= 0) {
            val path = Path()
            path.moveTo(f.left, f.bottom)
            cumLine += f.left to f.bottom
            for (i in 0..lastIdx) {
                val x = f.left + (i + 1) * slotW
                val y = f.bottom - (cum[i] / rightTop * f.height).toFloat()
                path.lineTo(x, y)
                cumLine += x to y
            }
            drawPath(path, colors.secondary, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        // goal line
        if (goal > 0) {
            val gy = f.bottom - (goal / rightTop * f.height).toFloat()
            drawLine(colors.accent, Offset(f.left, gy), Offset(f.right, gy), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            val bars = (0 until 24).mapNotNull { i ->
                val s = slots[i] ?: return@mapNotNull null
                if (s.total <= 0) null else Box(f.left + i * slotW + (slotW - barW) / 2f, f.y(s.total.toDouble()), f.left + i * slotW + (slotW + barW) / 2f, f.bottom)
            }
            drawLineLabel(
                f, gy, "goal ${Fmt.compact(goal.toDouble())}", measurer, style.copy(color = colors.accent), colors.dotFill,
                points = emptyList(), pointRadius = 0f, polylines = listOf(cumLine), blocks = bars,
            )
        }
        if (selected in 0..23) {
            val s = slots[selected]
            val steps = s?.total ?: 0
            val x = f.left + selected * slotW + slotW / 2f
            val y = f.y(steps.toDouble())
            val text = "${Fmt.hourLabel(selected)}–${Fmt.hourLabel(selected + 1)} · ${Fmt.int(steps)} steps · total ${Fmt.int(cum[selected])}"
            drawTooltip(f, x, y, text, measurer, style, colors)
        }
    }
}

// ---- 4: daily steps + distance ----------------------------------------------------------------------------

/**
 * Daily steps (bars) with the stride-model distance as a line on a right axis, for the last 7 / 30 days.
 * Today is highlighted; the selected day (if any) is outlined. Tap a bar to read it and select that day.
 */
@Composable
fun DailyStepsChart(
    days: List<DailySummary>,
    todayStart: Long,
    goal: Int,
    selectedDay: Long? = null,
    onDaySelected: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier,
    colors: ChartColors = chartColors(),
) {
    if (days.isEmpty() || days.all { it.steps <= 0 }) {
        EmptyChart("No step history yet", modifier)
        return
    }
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = colors.text)
    val n = days.size
    val leftTop = remember(days, goal) { ChartData.niceCeil(maxOf(days.maxOf { it.steps }, goal).toDouble()) }
    val maxKm = remember(days) { days.maxOf { it.distanceMeters } / 1000.0 }
    val rightTop = remember(maxKm) { ChartData.niceCeil(maxKm.coerceAtLeast(1.0)) }
    val leftGrid = remember(leftTop) { listOf(0.0, 0.25, 0.5, 0.75, 1.0).map { it * leftTop } }
    val leftLabels = remember(leftGrid) { leftGrid.map { Fmt.compact(it) } }
    val rightFractions = listOf(0.0, 0.5, 1.0)
    val rightLabels = remember(rightTop) { rightFractions.map { "${Fmt.value(it * rightTop)} km" } }
    val gutters = rememberGutters(measurer, style, leftLabels, rightLabels)
    val ticks = remember(days) {
        if (n <= 7) days.mapIndexed { i, d -> Pair(i + 0.5, Fmt.weekday(d.dayStart)) }
        else days.mapIndexedNotNull { i, d -> if ((n - 1 - i) % 5 == 0) Pair(i + 0.5, Fmt.shortDate(d.dayStart)) else null }
    }
    var tapped by remember(days) { mutableIntStateOf(-1) }

    Canvas(
        modifier
            .chartSize()
            .pointerInput(days) {
                detectTapGestures { pos ->
                    val f = ChartFrame.of(size.width.toFloat(), size.height.toFloat(), gutters, 0.0, n.toDouble(), 0.0, leftTop)
                    val idx = floor(f.xValue(pos.x)).toInt()
                    if (pos.x in f.left..f.right && idx in 0 until n) {
                        tapped = if (idx == tapped) -1 else idx
                        onDaySelected?.invoke(days[idx].dayStart)
                    } else {
                        tapped = -1
                    }
                }
            },
    ) {
        val f = ChartFrame.of(size.width, size.height, gutters, 0.0, n.toDouble(), 0.0, leftTop)
        drawHGrid(f, leftGrid, leftLabels, measurer, style, colors)
        drawRightLabels(f, rightFractions, rightLabels, measurer, style.copy(color = colors.secondary))
        drawXTicks(f, ticks, measurer, style, colors)
        val slotW = f.width / n
        val barW = slotW * (if (n <= 7) 0.6f else 0.7f)
        days.forEachIndexed { i, d ->
            val x0 = f.left + i * slotW + (slotW - barW) / 2f
            val yTop = f.y(d.steps.toDouble())
            val isToday = d.dayStart == todayStart
            if (d.steps > 0) drawRect(if (isToday) colors.accent else colors.bar, Offset(x0, yTop), Size(barW, f.bottom - yTop))
            if (selectedDay != null && d.dayStart == selectedDay && !isToday) {
                drawRect(colors.line, Offset(x0, yTop), Size(barW, f.bottom - yTop), style = Stroke(width = 2.dp.toPx()))
            }
        }
        // distance line (right axis, km)
        val distPts = days.mapIndexed { i, d -> Offset(f.left + i * slotW + slotW / 2f, f.bottom - (d.distanceMeters / 1000.0 / rightTop * f.height).toFloat()) }
        if (distPts.size >= 2) {
            val path = Path()
            distPts.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
            drawPath(path, colors.secondary, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        for (p in distPts) drawHollowDot(p, 2.5.dp.toPx(), colors.secondary, colors.dotFill)
        if (goal > 0) {
            val gy = f.y(goal.toDouble())
            drawLine(colors.accent, Offset(f.left, gy), Offset(f.right, gy), strokeWidth = 1.dp.toPx(), pathEffect = dash)
        }
        if (tapped in 0 until n) {
            val d = days[tapped]
            val x = f.left + tapped * slotW + slotW / 2f
            val text = "${Fmt.date(d.dayStart)} · ${Fmt.int(d.steps)} steps · ${Fmt.kmShort(d.distanceMeters)}"
            drawTooltip(f, x, f.y(d.steps.toDouble()), text, measurer, style, colors)
        }
    }
}

// ---- 5: workout detail ----------------------------------------------------------------------------------

/**
 * Workout detail: HR over elapsed time with pace (from the GPS tracker) as a second series on an inverted
 * right axis (faster = higher), and a dashed marker at every whole kilometre. Times are epoch ms; the chart
 * converts them to seconds since [startTime].
 */
@Composable
fun WorkoutChart(
    hr: List<Pt>,
    pace: List<Pt>,
    kmMarkers: List<Pt>,
    startTime: Long,
    durationSeconds: Int,
    modifier: Modifier = Modifier,
    colors: ChartColors = chartColors(),
) {
    val paceOk = remember(pace) { pace.filter { it.value > 0.0 && it.value <= 1800.0 } }
    if (hr.isEmpty() && paceOk.isEmpty()) {
        EmptyChart("No workout data recorded", modifier)
        return
    }
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = colors.text)
    // Samples before the start (a live HR stream that was already running) would land left of the plot area.
    val hrE = remember(hr, startTime) { hr.map { Pt((it.time - startTime) / 1000L, it.value) }.filter { it.time >= 0L }.sortedBy { it.time } }
    val paceE = remember(paceOk, startTime) { paceOk.map { Pt((it.time - startTime) / 1000L, it.value) }.filter { it.time >= 0L }.sortedBy { it.time } }
    val kmE = remember(kmMarkers, startTime) { kmMarkers.map { Pt((it.time - startTime) / 1000L, it.value) } }
    val xMax = remember(hrE, paceE, durationSeconds) {
        maxOf(durationSeconds.toLong(), hrE.lastOrNull()?.time ?: 0L, paceE.lastOrNull()?.time ?: 0L, 60L).toDouble()
    }
    val grid = remember(hrE) { extendGrid(listOf(40.0, 75.0, 110.0, 145.0, 180.0), hrE.maxOfOrNull { it.value } ?: 0.0) }
    val yLabels = remember(grid) { grid.map { Fmt.value(it) } }
    val pMin = remember(paceE) { paceE.minOfOrNull { it.value }?.let { floor(it / 60.0) * 60.0 } ?: 0.0 }
    val pMax = remember(paceE, pMin) { (paceE.maxOfOrNull { it.value }?.let { ceil(it / 60.0) * 60.0 } ?: 0.0).let { if (it <= pMin) pMin + 60.0 else it } }
    val rightFractions = listOf(1.0, 0.5, 0.0)
    val rightLabels = remember(paceE, pMin, pMax) {
        if (paceE.isEmpty()) emptyList() else listOf(Fmt.paceShort(pMin), Fmt.paceShort((pMin + pMax) / 2), Fmt.paceShort(pMax))
    }
    val gutters = rememberGutters(measurer, style, yLabels, rightLabels, topLabels = kmE.isNotEmpty())
    val ticks = remember(xMax) { listOf(0.0, 0.25, 0.5, 0.75, 1.0).map { Pair(it * xMax, Fmt.elapsed((it * xMax).roundToInt())) } }
    val hrSegs = remember(hrE) { ChartData.segments(hrE, 30L) }
    val paceSegs = remember(paceE) { ChartData.segments(paceE, 30L) }
    val yMax = grid.last()
    var selected by remember(hrE, paceE) { mutableStateOf<Pt?>(null) }
    val hitList = remember(hrE, paceE) { if (hrE.isNotEmpty()) hrE else paceE }

    Canvas(
        modifier
            .chartSize()
            .pointerInput(hitList) {
                detectTapGestures { pos ->
                    val f = ChartFrame.of(size.width.toFloat(), size.height.toFloat(), gutters, 0.0, xMax, 40.0, yMax)
                    selected = nearestByX(hitList, f, pos.x, 24.dp.toPx())
                }
            },
    ) {
        val f = ChartFrame.of(size.width, size.height, gutters, 0.0, xMax, 40.0, yMax)
        fun paceY(v: Double): Float = f.top + (((v - pMin) / (pMax - pMin)).coerceIn(0.0, 1.0) * f.height).toFloat()
        drawHGrid(f, grid, yLabels, measurer, style, colors)
        drawRightLabels(f, rightFractions, rightLabels, measurer, style.copy(color = colors.secondary))
        drawXTicks(f, ticks, measurer, style, colors)
        for (k in kmE) {
            val x = f.x(k.time.toDouble())
            if (x < f.left || x > f.right) continue
            drawLine(colors.grid, Offset(x, f.top), Offset(x, f.bottom), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            val label = "${k.value.roundToInt()} km"
            val s = measurer.measure(label, style).size
            drawText(measurer, label, Offset((x - s.width / 2f).coerceIn(0f, (size.width - s.width).coerceAtLeast(0f)), (f.top - s.height - 2f).coerceAtLeast(0f)), style)
        }
        clipRect(f.left, f.top, f.right, f.bottom) {
            for (seg in paceSegs) {
                if (seg.size < 2) continue
                val path = Path()
                seg.forEachIndexed { i, p ->
                    val x = f.x(p.time.toDouble())
                    val y = paceY(p.value)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, colors.secondary, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            for (seg in hrSegs) {
                if (seg.size == 1) drawCircle(colors.line, 2.dp.toPx(), Offset(f.x(seg[0].time.toDouble()), f.y(seg[0].value)))
                else drawPolyline(f, seg, colors.line, 2.dp.toPx())
            }
        }
        selected?.let { p ->
            val nearPace = nearestByX(paceE, f, f.x(p.time.toDouble()), 24.dp.toPx())
            val hrPart = if (hrE.isNotEmpty()) " · ${Fmt.value(p.value)} bpm" else ""
            val pacePart = nearPace?.let { " · ${Fmt.pace(it.value)}" } ?: ""
            val y = if (hrE.isNotEmpty()) f.y(p.value) else paceY(p.value)
            drawTooltip(f, f.x(p.time.toDouble()), y, Fmt.elapsed(p.time.toInt()) + hrPart + pacePart, measurer, style, colors)
        }
    }
}

// ---- 6: sleep hypnogram ------------------------------------------------------------------------------------

/**
 * Block colours by stage, in the strip / card colour language: deep = primary, light = faded primary,
 * REM = tertiary, awake = error, generic-asleep ("unstaged") = a neutral grey so it reads as sleep without
 * claiming a real stage.
 */
fun sleepStageColor(stage: Int, colors: ChartColors): Color = when (stage) {
    SleepMath.DEEP -> colors.bar
    SleepMath.LIGHT -> colors.bar.copy(alpha = 0.45f)
    SleepMath.REM -> colors.bar2
    SleepMath.AWAKE -> colors.accent
    SleepMath.ASLEEP -> colors.text.copy(alpha = 0.30f)
    else -> colors.grid
}

/**
 * One night as a hypnogram: five lanes (awake, REM, light, deep, generic-asleep, top to bottom) with a filled block per stage,
 * a thin connector where consecutive stages change lane, and ticks at whole hours from bed to rise. Tap a
 * block to read its times and length.
 */
@Composable
fun SleepHypnogram(
    chart: SleepChart?,
    modifier: Modifier = Modifier,
    colors: ChartColors = chartColors(),
) {
    if (chart == null || chart.blocks.isEmpty()) {
        EmptyChart("No sleep recorded for this night", modifier)
        return
    }
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = colors.text)
    val laneLabels = remember { (0 until SleepChartData.LANE_COUNT).map { SleepChartData.laneLabel(it) } }
    val gutters = rememberGutters(measurer, style, laneLabels, emptyList())
    val ticks = remember(chart) { SleepChartData.hourTicks(chart.start, chart.end) }
    val xMin = chart.start.toDouble()
    val xMax = chart.end.toDouble()
    val lanes = SleepChartData.LANE_COUNT.toDouble()
    var selected by remember(chart) { mutableStateOf<SleepBlock?>(null) }

    Canvas(
        modifier
            .chartSize()
            .pointerInput(chart) {
                detectTapGestures { pos ->
                    val f = ChartFrame.of(size.width.toFloat(), size.height.toFloat(), gutters, xMin, xMax, 0.0, lanes)
                    val t = f.xValue(pos.x).toLong()
                    val hit = if (pos.x in f.left..f.right) chart.blocks.firstOrNull { t >= it.start && t < it.end } else null
                    selected = if (hit != null && hit != selected) hit else null
                }
            },
    ) {
        val f = ChartFrame.of(size.width, size.height, gutters, xMin, xMax, 0.0, lanes)
        val laneH = f.height / SleepChartData.LANE_COUNT
        val pad = 4.dp.toPx()
        for (lane in 0 until SleepChartData.LANE_COUNT) {
            val yTop = f.top + lane * laneH
            if (lane > 0) drawLine(colors.grid, Offset(f.left, yTop), Offset(f.right, yTop), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            val label = laneLabels[lane]
            val s = measurer.measure(label, style).size
            drawText(measurer, label, Offset(f.left - s.width - pad, yTop + laneH / 2f - s.height / 2f), style)
        }
        drawXTicks(f, ticks, measurer, style, colors)
        val inset = 2.dp.toPx()
        var prev: SleepBlock? = null
        for (b in chart.blocks) {
            val x0 = f.x(b.start.toDouble())
            val x1 = f.x(b.end.toDouble())
            val yTop = f.top + b.lane * laneH + inset
            drawRect(sleepStageColor(b.stage, colors), Offset(x0, yTop), Size((x1 - x0).coerceAtLeast(1f), laneH - 2 * inset))
            if (prev != null && prev.lane != b.lane) {
                val yA = f.top + prev.lane * laneH + laneH / 2f
                val yB = f.top + b.lane * laneH + laneH / 2f
                drawLine(colors.grid, Offset(x0, yA), Offset(x0, yB), strokeWidth = 1.dp.toPx())
            }
            prev = b
        }
        selected?.let { b ->
            val x0 = f.x(b.start.toDouble())
            val x1 = f.x(b.end.toDouble())
            val yTop = f.top + b.lane * laneH + inset
            drawRect(colors.line, Offset(x0, yTop), Size((x1 - x0).coerceAtLeast(1f), laneH - 2 * inset), style = Stroke(width = 2.dp.toPx()))
            val text = "${Fmt.time(b.start)}–${Fmt.time(b.end)} · ${SleepMath.stageName(b.stage)} ${b.minutes} min"
            drawTooltip(f, (x0 + x1) / 2f, yTop + (laneH - 2 * inset) / 2f, text, measurer, style, colors)
        }
    }
}


/** Longer-period health trends rendered by the SAME chart engine as the Day heart-rate view. */
@Composable
fun HealthPeriodLineChart(
    points: List<Pt>,
    start: Long,
    end: Long,
    ticks: List<Pair<Double, String>>,
    heartRate: Boolean,
    colors: ChartColors = chartColors(),
) {
    TimeLineChart(
        series = points, samples = emptyList(),
        xMin = start, xMax = end,
        yMin = if (heartRate) 40.0 else 0.0,
        gridValues = if (heartRate) listOf(40.0, 75.0, 110.0, 145.0, 180.0)
                     else listOf(0.0, 3.0, 6.0, 9.0, 12.0),
        xTicks = ticks,
        unit = if (heartRate) "bpm" else "h",
        maxGapMs = if (heartRate) 2L * ChartData.HOUR_MS * 24
                   else 2L * ChartData.HOUR_MS * 24,
        showAvg = true,
        emptyText = "No recorded data in this period",
        colors = colors,
        modifier = Modifier,
    )
}
