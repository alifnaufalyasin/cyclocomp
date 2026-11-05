package com.aliven.cyclocomp

import com.aliven.cyclocomp.sync.GitSha
import com.aliven.cyclocomp.sync.PullAction
import com.aliven.cyclocomp.sync.PullPlanner
import com.aliven.cyclocomp.sync.RemoteFile
import com.aliven.cyclocomp.sync.TrainingSettingsJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncBothWaysTest {
    private fun rf(name: String, sha: String = "sha-$name") = RemoteFile("rides/2026/$name", name, sha)

    private fun plan(
        remote: List<RemoteFile>,
        local: Set<String> = emptySet(),
        lastSeen: Map<String, String> = emptyMap(),
        deletedHere: Set<String> = emptySet(),
    ) = PullPlanner.plan(remote, local, lastSeen, deletedHere)

    private fun downloads(a: List<PullAction>) = a.filterIsInstance<PullAction.Download>().map { it.file.name }
    private fun compares(a: List<PullAction>) = a.filterIsInstance<PullAction.Compare>().map { it.file.name }
    private fun deletes(a: List<PullAction>) = a.filterIsInstance<PullAction.DeleteLocal>().map { it.name }

    @Test
    fun gitShaMatchesGitsOwnBlobIds() {
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", GitSha.blob(ByteArray(0)))
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", GitSha.blob("hello\n".toByteArray()))
    }

    @Test
    fun aRideMissingOnThisPhoneIsDownloaded() {
        val a = plan(listOf(rf("a.gpx")))
        assertEquals(listOf("a.gpx"), downloads(a))
    }

    @Test
    fun unchangedRideIsLeftAloneAndChangedRideIsCompared() {
        val remote = listOf(rf("b.gpx", "s2"), rf("c.gpx", "s3-new"))
        val a = plan(remote, local = setOf("b.gpx", "c.gpx"), lastSeen = mapOf("b.gpx" to "s2", "c.gpx" to "s3-old"))
        assertTrue(downloads(a).isEmpty())
        assertEquals(listOf("c.gpx"), compares(a))
    }

    @Test
    fun aRideBothPhonesHaveButNeverSyncedHereIsCompared() {
        // e.g. a phone that recorded the ride itself, or a new install: no remembered sha yet.
        val a = plan(listOf(rf("x.gpx")), local = setOf("x.gpx"))
        assertEquals(listOf("x.gpx"), compares(a))
    }

    @Test
    fun deletionOnAnotherPhoneRemovesTheLocalCopyAndIsNotRedownloaded() {
        val remote = listOf(rf("d.gpx"), rf("d.gpx.deleted"), rf("e.gpx"), rf("e.gpx.deleted"))
        val a = plan(remote, local = setOf("d.gpx"))
        assertEquals(listOf("d.gpx"), deletes(a))
        assertTrue(downloads(a).isEmpty()) // e.gpx is tombstoned and absent here: nothing to do
    }

    @Test
    fun aRideDeletedHereIsNeverPulledBack() {
        val a = plan(listOf(rf("f.gpx")), deletedHere = setOf("f.gpx"))
        assertTrue(a.isEmpty())
    }

    @Test
    fun workoutsAreDownloadedOnceAndNeverCompared() {
        val w = "20261103_064200.workout.json"
        assertEquals(listOf(w), downloads(plan(listOf(rf(w)))))
        assertTrue(plan(listOf(rf(w, "changed")), local = setOf(w), lastSeen = mapOf(w to "old")).isEmpty())
    }

    @Test
    fun summariesAndMarkersAreNotPulled() {
        val a = plan(listOf(rf("a.json"), rf("a.gpx.deleted")))
        assertTrue(a.isEmpty())
    }

    @Test
    fun theCopyWithMoreHeartRateWins() {
        assertTrue(PullPlanner.remoteWins(localHrPoints = 0, remoteHrPoints = 3000))
        assertFalse(PullPlanner.remoteWins(localHrPoints = 3000, remoteHrPoints = 0))
        assertFalse(PullPlanner.remoteWins(localHrPoints = 10, remoteHrPoints = 10))
    }

    @Test
    fun heartRateSettingsRoundTripAndNewestWins() {
        val v = TrainingSettingsJson.Values(restHr = 58, maxHr = 188, updatedMs = 1_700_000_000_000L)
        val back = TrainingSettingsJson.fromJson(TrainingSettingsJson.toJson(v))!!
        assertEquals(58, back.restHr)
        assertEquals(188, back.maxHr)
        assertEquals(1_700_000_000_000L, back.updatedMs)
        assertNull(TrainingSettingsJson.fromJson("not json"))
        assertNull(TrainingSettingsJson.fromJson("{}"))

        assertTrue(TrainingSettingsJson.remoteIsNewer(localUpdatedMs = 0, remoteUpdatedMs = 5))
        assertFalse(TrainingSettingsJson.remoteIsNewer(localUpdatedMs = 5, remoteUpdatedMs = 5))
        assertFalse(TrainingSettingsJson.remoteIsNewer(localUpdatedMs = 9, remoteUpdatedMs = 5))
    }
}
