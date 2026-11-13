package com.aliven.cyclocomp.sync

import com.aliven.cyclocomp.core.GpxParser
import com.aliven.cyclocomp.core.GpxWriter
import com.aliven.cyclocomp.core.HrMerger
import com.aliven.cyclocomp.core.ImportDecision
import com.aliven.cyclocomp.core.ImportPlanner
import com.aliven.cyclocomp.core.RideCandidate
import com.aliven.cyclocomp.core.RideNames
import com.aliven.cyclocomp.core.RideSummaries
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** What happened to one GPX file that was offered as a ride. */
sealed interface RideImportOutcome {
    /** Kept as a new ride. */
    class Created(val rideName: String) : RideImportOutcome

    /** Matched an existing ride and added its heart rate. */
    class Merged(val rideName: String, val points: Int) : RideImportOutcome

    class AlreadyHasHr(val rideName: String) : RideImportOutcome

    /** Matched an existing ride, but no heart-rate sample of the file lines up with it. */
    class NoHrToAdd(val rideName: String) : RideImportOutcome

    class AlreadyImported(val rideName: String) : RideImportOutcome

    class Skipped(val reason: String) : RideImportOutcome
}

/**
 * Turns a GPX file (from the watch, Strava, ...) into a ride: merges its heart rate into the
 * matching ride, or keeps it as a new one. Shared by the inbox import and the History screen.
 */
object RideImporter {
    /**
     * [local] lists the rides already present and grows when a ride is created, so several files of
     * one batch see each other. [onMerged] is told which ride file was rewritten.
     * Throws if the bytes are not a readable GPX.
     */
    fun importTrack(
        bytes: ByteArray,
        ridesDir: File,
        local: MutableList<RideCandidate>,
        onMerged: (File) -> Unit = {},
    ): RideImportOutcome {
        val watch = GpxParser.parse(bytes.inputStream())
        return when (val decision = ImportPlanner.decide(local, watch)) {
            is ImportDecision.Skip -> RideImportOutcome.Skipped(decision.reason)

            is ImportDecision.Merge -> {
                val file = File(ridesDir, decision.rideName)
                val phone = file.inputStream().use { GpxParser.parse(it) }
                if (HrMerger.countWithHr(phone) > 0) return RideImportOutcome.AlreadyHasHr(decision.rideName)
                val merged = HrMerger.merge(phone, watch)
                val n = HrMerger.countWithHr(merged)
                // Matched by start and distance, yet no watch sample is near any phone timestamp (clock offset?).
                if (n == 0) return RideImportOutcome.NoHrToAdd(decision.rideName)
                writeAtomically(file, GpxWriter.write("Ride ${file.nameWithoutExtension}", merged))
                onMerged(file)
                RideImportOutcome.Merged(decision.rideName, n)
            }

            ImportDecision.New -> {
                val stamp = RideNames.stamp(watch.first().first().timeMs)
                val target = File(ridesDir, "$stamp.gpx")
                if (target.exists()) return RideImportOutcome.AlreadyImported(target.name)
                writeAtomically(target, GpxWriter.write("Imported $stamp", watch))
                RideSummaries.summarize(watch)?.let { local.add(RideCandidate(target.name, it.startMs, it.distanceM)) }
                RideImportOutcome.Created(target.name)
            }
        }
    }

    fun loadCandidates(ridesDir: File): MutableList<RideCandidate> =
        ridesDir.listFiles { f -> f.extension == "gpx" }.orEmpty().mapNotNull { f ->
            try {
                val s = f.inputStream().use { RideSummaries.summarize(GpxParser.parse(it)) }
                s?.let { RideCandidate(f.name, it.startMs, it.distanceM) }
            } catch (e: Exception) {
                null
            }
        }.toMutableList()

    fun writeAtomically(target: File, content: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(content)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
