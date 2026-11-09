package com.aliven.cyclocomp

import com.aliven.cyclocomp.core.GpxParser
import com.aliven.cyclocomp.core.GpxWriter
import com.aliven.cyclocomp.core.HrMerger
import com.aliven.cyclocomp.core.TrackPoint
import com.aliven.cyclocomp.sync.RideImportOutcome
import com.aliven.cyclocomp.sync.RideImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RideImporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val t0 = 1_762_500_000_000L

    // A straight ride: a point every 10 s, about 55 m apart.
    private fun gpx(startMs: Long = t0, points: Int = 40, hr: Int? = null): ByteArray =
        GpxWriter.write(
            "test",
            listOf(List(points) { i -> TrackPoint(startMs + i * 10_000L, i * 0.0005, 0.0, hr = hr) }),
        ).toByteArray()

    private fun dir(): File = tmp.newFolder()

    @Test fun aNewTrackBecomesARide() {
        val d = dir()
        val local = RideImporter.loadCandidates(d)
        val r = RideImporter.importTrack(gpx(), d, local)
        assertTrue(r is RideImportOutcome.Created)
        val name = (r as RideImportOutcome.Created).rideName
        assertTrue(File(d, name).exists())
        assertEquals(1, local.size) // later files of the same batch see it
    }

    @Test fun theSameFileTwiceDoesNotCreateAnotherRide() {
        val d = dir()
        val local = RideImporter.loadCandidates(d)
        RideImporter.importTrack(gpx(), d, local)
        val again = RideImporter.importTrack(gpx(), d, local)
        assertTrue(again is RideImportOutcome.NoHrToAdd) // it matches the ride, but has nothing to add
        assertEquals(1, d.listFiles { f -> f.extension == "gpx" }!!.size)
    }

    @Test fun heartRateIsMergedIntoTheMatchingRide() {
        val d = dir()
        val local = RideImporter.loadCandidates(d)
        val created = RideImporter.importTrack(gpx(), d, local) as RideImportOutcome.Created
        val touched = mutableListOf<File>()
        val r = RideImporter.importTrack(gpx(hr = 140), d, local) { touched.add(it) }
        assertTrue(r is RideImportOutcome.Merged)
        assertEquals(listOf(created.rideName), touched.map { it.name })
        val merged = File(d, created.rideName).inputStream().use { GpxParser.parse(it) }
        assertTrue(HrMerger.countWithHr(merged) > 0)
    }

    @Test fun aRideThatAlreadyHasHeartRateIsLeftAlone() {
        val d = dir()
        val local = RideImporter.loadCandidates(d)
        RideImporter.importTrack(gpx(hr = 130), d, local)
        val r = RideImporter.importTrack(gpx(hr = 150), d, local)
        assertTrue(r is RideImportOutcome.AlreadyHasHr)
    }

    @Test fun sameStartButVeryDifferentDistanceIsSkipped() {
        val d = dir()
        val local = RideImporter.loadCandidates(d)
        RideImporter.importTrack(gpx(points = 40), d, local)
        val r = RideImporter.importTrack(gpx(points = 90), d, local)
        assertTrue(r is RideImportOutcome.Skipped)
        assertEquals(1, d.listFiles { f -> f.extension == "gpx" }!!.size)
    }

    @Test fun aFarAwayStartIsAnotherRide() {
        val d = dir()
        val local = RideImporter.loadCandidates(d)
        RideImporter.importTrack(gpx(), d, local)
        val r = RideImporter.importTrack(gpx(startMs = t0 + 3_600_000L), d, local)
        assertTrue(r is RideImportOutcome.Created)
        assertEquals(2, d.listFiles { f -> f.extension == "gpx" }!!.size)
    }

    @Test fun somethingThatIsNotAGpxCreatesNoRide() {
        val d = dir()
        val local = RideImporter.loadCandidates(d)
        val outcome = try {
            RideImporter.importTrack("this is not a gpx file".toByteArray(), d, local)
        } catch (e: Exception) {
            null // the parser may refuse it outright; either way nothing is created
        }
        assertTrue(outcome == null || outcome is RideImportOutcome.Skipped)
        assertEquals(0, d.listFiles { f -> f.extension == "gpx" }!!.size)
    }

    @Test fun existingRidesAreLoadedAsCandidates() {
        val d = dir()
        RideImporter.importTrack(gpx(), d, RideImporter.loadCandidates(d))
        assertEquals(1, RideImporter.loadCandidates(d).size)
    }
}
