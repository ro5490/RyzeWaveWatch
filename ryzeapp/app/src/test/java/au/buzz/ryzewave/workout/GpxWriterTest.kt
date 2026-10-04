package au.buzz.ryzewave.workout

import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SampleSource
import au.buzz.ryzewave.core.TrackPoint
import au.buzz.ryzewave.core.Workout
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxWriterTest {
    private val utc: ZoneId = ZoneId.of("UTC")
    private val s = 1_700_000_000_000L   // 2023-11-14T22:13:20Z

    private val walk = Workout(
        id = 7,
        start = s,
        end = s + 600_000L,
        sportType = 1,
        distanceMeters = 1000.0,
        durationSeconds = 600,
        avgHr = 120,
        maxHr = 150,
        calories = 80,
    )

    private fun pt(
        time: Long,
        lat: Double,
        accepted: Boolean = true,
        alt: Double? = null,
    ) = TrackPoint(
        workoutId = 7,
        time = time,
        lat = lat,
        lon = 153.0251,
        accuracyM = 5f,
        speedMps = 1f,
        altitudeM = alt,
        accepted = accepted,
    )

    private fun count(haystack: String, needle: String): Int {
        var n = 0
        var i = haystack.indexOf(needle)

        while (i >= 0) {
            n++
            i = haystack.indexOf(needle, i + needle.length)
        }

        return n
    }

    @Test
    fun writesAcceptedPointsAsOneTrack() {
        val pts = listOf(
            pt(s, -27.0),
            pt(
                s + 1000L,
                -27.0001,
                accepted = false,
            ),
            pt(
                s + 2000L,
                -27.0002,
                alt = 12.34,
            ),
        )

        val gpx = GpxWriter.toGpx(
            walk,
            pts,
            zone = utc,
        )

        assertTrue(
            gpx.startsWith(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
            ),
        )

        assertTrue(
            gpx.contains(
                "<gpx version=\"1.1\"",
            ),
        )

        assertTrue(
            gpx.contains(
                "xmlns=\"http://www.topografix.com/GPX/1/1\"",
            ),
        )

        assertEquals(
            1,
            count(gpx, "<trk>"),
        )

        assertEquals(
            1,
            count(gpx, "<trkseg>"),
        )

        assertEquals(
            2,
            count(gpx, "<trkpt "),
        )

        assertTrue(
            gpx.contains(
                "<trkpt lat=\"-27.0000000\" lon=\"153.0251000\">",
            ),
        )

        assertTrue(
            gpx.contains(
                "<ele>12.3</ele>",
            ),
        )

        assertTrue(
            gpx.contains(
                "<time>2023-11-14T22:13:20Z</time>",
            ),
        )

        assertTrue(
            gpx.contains(
                "<name>Ryze Wave workout 2023-11-14 22:13</name>",
            ),
        )

        assertTrue(
            gpx.contains(
                "<type>Outdoor Walking</type>",
            ),
        )

        // UK-style display keeps distances below one mile in metres.
        assertTrue(
            gpx.contains(
                "1000 m, 10:00, avg HR 120, max HR 150, 80 kcal, Outdoor Walking (sport type 1)",
            ),
        )

        assertTrue(
            gpx.trim().endsWith("</gpx>"),
        )

        assertFalse(
            gpx.contains("gpxtpx:hr"),
        )
    }

    @Test
    fun includeRejectedKeepsEveryPoint() {
        val pts = listOf(
            pt(s, -27.0),
            pt(
                s + 1000L,
                -27.0001,
                accepted = false,
            ),
            pt(
                s + 2000L,
                -27.0002,
            ),
        )

        assertEquals(
            3,
            count(
                GpxWriter.toGpx(
                    walk,
                    pts,
                    includeRejected = true,
                    zone = utc,
                ),
                "<trkpt ",
            ),
        )
    }

    @Test
    fun longGapsStartANewSegment() {
        val pts = listOf(
            pt(s, -27.0),
            pt(
                s + 1000L,
                -27.0001,
            ),
            pt(
                s + 120_000L,
                -27.001,
            ),
            pt(
                s + 121_000L,
                -27.0011,
            ),
        )

        val gpx = GpxWriter.toGpx(
            walk,
            pts,
            zone = utc,
        )

        assertEquals(
            2,
            count(gpx, "<trkseg>"),
        )

        assertEquals(
            2,
            count(gpx, "</trkseg>"),
        )

        assertEquals(
            4,
            count(gpx, "<trkpt "),
        )
    }

    @Test
    fun heartRateUsesTheNearestSampleWithinFiveSeconds() {
        val hr = listOf(
            HrSample(
                s + 300L,
                110,
                SampleSource.WORKOUT,
            ),
            HrSample(
                s + 2100L,
                130,
                SampleSource.WORKOUT,
            ),
            HrSample(
                s + 60_000L,
                170,
                SampleSource.WORKOUT,
            ),
        )

        val pts = listOf(
            pt(s, -27.0),
            pt(
                s + 2000L,
                -27.0001,
            ),
            pt(
                s + 30_000L,
                -27.001,
            ),
        )

        val gpx = GpxWriter.toGpx(
            walk,
            pts,
            hr,
            zone = utc,
        )

        assertEquals(
            2,
            count(gpx, "<gpxtpx:hr>"),
        )

        assertTrue(
            gpx.contains(
                "<gpxtpx:hr>110</gpxtpx:hr>",
            ),
        )

        assertTrue(
            gpx.contains(
                "<gpxtpx:hr>130</gpxtpx:hr>",
            ),
        )

        assertFalse(
            gpx.contains(
                "<gpxtpx:hr>170</gpxtpx:hr>",
            ),
        )

        assertTrue(
            gpx.contains(
                "xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\"",
            ),
        )
    }

    @Test
    fun emptyTrackIsStillWellFormed() {
        val gpx = GpxWriter.toGpx(
            walk,
            emptyList(),
            zone = utc,
        )

        assertEquals(
            0,
            count(gpx, "<trkpt "),
        )

        assertTrue(
            gpx.contains("<trkseg/>"),
        )

        assertTrue(
            gpx.contains("</trk>"),
        )
    }

    /**
     * The `<type>` is the watch's sport name;
     * type 1 alone (used for walks before the picker)
     * is split by speed.
     */
    @Test
    fun typeIsTheSportNameWithTypeOneSplitByAverageSpeed() {
        val run = walk.copy(
            distanceMeters = 1500.0,
            durationSeconds = 600,
        ) // 2.5 m/s

        assertEquals(
            "Outdoor Running",
            GpxWriter.sportName(run),
        )

        assertEquals(
            "Outdoor Walking",
            GpxWriter.sportName(walk),
        )

        assertEquals(
            "Outdoor Walking",
            GpxWriter.sportName(
                walk.copy(durationSeconds = 0),
            ),
        )

        // Slow ride is still a ride.
        assertEquals(
            "Cycling",
            GpxWriter.sportName(
                walk.copy(sportType = 2),
            ),
        )

        // Fast walk is still a walk.
        assertEquals(
            "Outdoor Walking",
            GpxWriter.sportName(
                run.copy(sportType = 0x23),
            ),
        )

        assertEquals(
            "Hiking",
            GpxWriter.sportName(
                run.copy(sportType = 8),
            ),
        )

        assertEquals(
            "Sport 3",
            GpxWriter.sportName(
                run.copy(sportType = 3),
            ),
        )

        val gpx = GpxWriter.toGpx(
            walk.copy(sportType = 0x24),
            emptyList(),
            zone = utc,
        )

        assertTrue(
            gpx.contains(
                "<type>Trail Running</type>",
            ),
        )

        assertTrue(
            gpx.contains(
                "80 kcal, Trail Running (sport type 36)",
            ),
        )
    }

    @Test
    fun escapesXmlSpecials() {
        assertEquals(
            "a&lt;b&amp;c&quot;d&apos;e&gt;",
            GpxWriter.escape(
                "a<b&c\"d'e>",
            ),
        )

        val gpx = GpxWriter.toGpx(
            walk,
            emptyList(),
            zone = utc,
            name = "Buzz's <run>",
        )

        assertTrue(
            gpx.contains(
                "<name>Buzz&apos;s &lt;run&gt;</name>",
            ),
        )
    }

    @Test
    fun isoTimestampsAreWholeSecondsUtc() {
        assertEquals(
            "1970-01-01T00:00:01Z",
            GpxWriter.iso(1000L),
        )

        assertEquals(
            "1970-01-01T00:00:01Z",
            GpxWriter.iso(1999L),
        )

        assertEquals(
            "2023-11-14T22:13:20Z",
            GpxWriter.iso(s),
        )
    }

    @Test
    fun exportFileNameUsesIdAndStart() {
        assertEquals(
            "workout-7-20231114-2213.gpx",
            GpxExporter.fileName(
                walk,
                utc,
            ),
        )
    }
}
