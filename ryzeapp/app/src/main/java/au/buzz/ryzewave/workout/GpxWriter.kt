package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.TrackPoint
import au.buzz.ryzewave.core.Workout
import au.buzz.ryzewave.protocol.SportTypes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * GPX 1.1 serialiser (pure Kotlin, no Android). One `<trk>` per workout; a new `<trkseg>` starts whenever
 * two consecutive points are more than [SEGMENT_GAP_MS] apart (a pause or a GPS outage). Heart rate goes
 * into the Garmin TrackPointExtension (`gpxtpx:hr`) using the nearest sample within [HR_MATCH_WINDOW_MS].
 */
object GpxWriter {
    const val CREATOR = "Dapper's SmartTrax"
    const val SEGMENT_GAP_MS = 60_000L
    const val HR_MATCH_WINDOW_MS = 5_000L

    private val nameFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    fun toGpx(
        workout: Workout,
        points: List<TrackPoint>,
        hrSamples: List<HrSample> = emptyList(),
        includeRejected: Boolean = false,
        zone: ZoneId = ZoneId.systemDefault(),
        name: String? = null,
    ): String = buildString { write(this, workout, points, hrSamples, includeRejected, zone, name) }

    fun write(
        out: Appendable,
        workout: Workout,
        points: List<TrackPoint>,
        hrSamples: List<HrSample> = emptyList(),
        includeRejected: Boolean = false,
        zone: ZoneId = ZoneId.systemDefault(),
        name: String? = null,
    ) {
        val track = points.asSequence()
            .filter { includeRejected || it.accepted }
            .sortedBy { it.time }
            .toList()
        val hr = hrSamples.sortedBy { it.time }
        val trackName = name ?: defaultName(workout, zone)

        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<gpx version=\"1.1\" creator=\"").append(escape(CREATOR)).append("\"\n")
        out.append("     xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
        out.append("     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
        out.append("     xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\"\n")
        out.append("     xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        out.append("  <metadata>\n")
        out.append("    <name>").append(escape(trackName)).append("</name>\n")
        out.append("    <time>").append(iso(workout.start)).append("</time>\n")
        out.append("  </metadata>\n")
        out.append("  <trk>\n")
        out.append("    <name>").append(escape(trackName)).append("</name>\n")
        out.append("    <type>").append(sportName(workout)).append("</type>\n")
        out.append("    <desc>").append(escape(description(workout))).append("</desc>\n")

        var open = false
        var previousTime = Long.MIN_VALUE
        var hrIndex = 0
        for (p in track) {
            if (open && p.time - previousTime > SEGMENT_GAP_MS) {
                out.append("    </trkseg>\n")
                open = false
            }
            if (!open) {
                out.append("    <trkseg>\n")
                open = true
            }
            out.append("      <trkpt lat=\"").append(coord(p.lat)).append("\" lon=\"").append(coord(p.lon)).append("\">\n")
            p.altitudeM?.let { out.append("        <ele>").append(String.format(Locale.ROOT, "%.1f", it)).append("</ele>\n") }
            out.append("        <time>").append(iso(p.time)).append("</time>\n")
            // advance to the HR sample nearest to this point
            while (hrIndex + 1 < hr.size && hr[hrIndex + 1].time <= p.time) hrIndex++
            val bpm = nearestHr(hr, hrIndex, p.time)
            if (bpm != null) {
                out.append("        <extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>")
                    .append(bpm.toString())
                    .append("</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>\n")
            }
            out.append("      </trkpt>\n")
            previousTime = p.time
        }
        if (open) out.append("    </trkseg>\n")
        if (track.isEmpty()) out.append("    <trkseg/>\n")
        out.append("  </trk>\n")
        out.append("</gpx>\n")
    }

    fun defaultName(workout: Workout, zone: ZoneId = ZoneId.systemDefault()): String =
        "Ryze Wave workout " + nameFormat.format(Instant.ofEpochMilli(workout.start).atZone(zone))

    /**
     * The `<type>`: the sport's name from the watch's menu ([SportTypes]). Type 1 (Outdoor Running) is what every
     * workout recorded before the sport picker used, walks included, so for type 1 alone the average speed decides
     * (>= 2 m/s Outdoor Running, otherwise Outdoor Walking — [SportTypes.effectiveId]); other ids are named as-is.
     */
    fun sportName(workout: Workout): String {
        val avgSpeed = if (workout.durationSeconds > 0) workout.distanceMeters / workout.durationSeconds else 0.0
        return SportTypes.name(SportTypes.effectiveId(workout.sportType, avgSpeed, workout.start))
    }

    fun iso(epochMs: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs).truncatedTo(ChronoUnit.SECONDS))

    private fun description(w: Workout): String = buildString {
        append(WorkoutFormat.distance(w.distanceMeters)).append(", ")
        append(WorkoutFormat.elapsed(w.durationSeconds))
        w.avgHr?.let { append(", avg HR ").append(it) }
        w.maxHr?.let { append(", max HR ").append(it) }
        append(", ").append(w.calories).append(" kcal, ").append(sportName(w)).append(" (sport type ").append(w.sportType).append(')')
    }

    private fun nearestHr(hr: List<HrSample>, index: Int, time: Long): Int? {
        if (hr.isEmpty()) return null
        var best: HrSample? = null
        for (i in index..(index + 1)) {
            val s = hr.getOrNull(i) ?: continue
            if (abs(s.time - time) <= HR_MATCH_WINDOW_MS && (best == null || abs(s.time - time) < abs(best.time - time))) best = s
        }
        return best?.bpm
    }

    private fun coord(v: Double): String = String.format(Locale.ROOT, "%.7f", v)

    fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}
