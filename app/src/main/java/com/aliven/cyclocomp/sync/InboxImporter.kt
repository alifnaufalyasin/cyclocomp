package com.aliven.cyclocomp.sync

import android.content.Context
import com.aliven.cyclocomp.core.GpxParser
import com.aliven.cyclocomp.core.GpxWriter
import com.aliven.cyclocomp.core.HrMerger
import com.aliven.cyclocomp.core.ImportDecision
import com.aliven.cyclocomp.core.ImportPlanner
import com.aliven.cyclocomp.core.RideCandidate
import com.aliven.cyclocomp.core.RideNames
import com.aliven.cyclocomp.core.RideSummaries
import com.aliven.cyclocomp.core.TrackPoint
import com.aliven.cyclocomp.recording.RideRecorder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Ride-phone side of the two-phone workflow: pulls watch GPX files that the capture phone put
 * in `inbox/<phone>/`, then merges heart rate into the matching local ride or imports the file
 * as a new ride. A `<file>.done` marker is written next to each handled file so it is not
 * processed twice (by either phone). Files left unhandled are reported and retried next time.
 */
object InboxImporter {
    fun run(context: Context, repo: GitHubDataRepo): List<String> {
        RideRecorder.init(context.applicationContext)
        val ridesDir = RideRecorder.ridesDir()
        val local = loadCandidates(ridesDir).toMutableList()
        val lines = mutableListOf<String>()

        for (device in repo.list("inbox").filter { it.type == "dir" }) {
            val entries = repo.list(device.path)
            val names = entries.map { it.name }.toSet()
            val pending = entries.filter {
                it.type == "file" && "${it.name}.done" !in names &&
                    (it.name.endsWith(".gpx", ignoreCase = true) || it.name.endsWith(WorkoutJson.SUFFIX))
            }
            for (entry in pending) {
                try {
                    lines.add(handle(context, repo, entry, ridesDir, local))
                } catch (e: GitHubException) {
                    lines.add("${entry.name}: ${e.message}")
                    if (e.code == 401 || e.code == 403) return lines // affects every file
                } catch (e: Exception) {
                    lines.add("${entry.name}: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
        if (lines.isEmpty()) lines.add("Nothing new in the inbox.")
        return lines
    }

    private fun handle(
        context: Context,
        repo: GitHubDataRepo,
        entry: RepoEntry,
        ridesDir: File,
        local: MutableList<RideCandidate>,
    ): String {
        val bytes = repo.getRaw(entry.path) ?: return "${entry.name}: disappeared from the repo"
        if (entry.name.endsWith(WorkoutJson.SUFFIX)) return handleWorkout(repo, entry, bytes, ridesDir)
        val watch = GpxParser.parse(bytes.inputStream())

        return when (val decision = ImportPlanner.decide(local, watch)) {
            is ImportDecision.Skip -> "${entry.name}: skipped, ${decision.reason}"

            is ImportDecision.Merge -> {
                val file = File(ridesDir, decision.rideName)
                val phone = file.inputStream().use { GpxParser.parse(it) }
                if (HrMerger.countWithHr(phone) > 0) {
                    markDone(repo, entry, "ride already has heart rate")
                    return "${entry.name}: ${decision.rideName} already has heart rate, marked done"
                }
                val merged = HrMerger.merge(phone, watch)
                val n = HrMerger.countWithHr(merged)
                if (n == 0) {
                    // Matched by start/distance, yet no watch sample is near any phone timestamp (clock offset?).
                    return "${entry.name}: matched ${decision.rideName} but no heart-rate sample lines up in time; left in inbox"
                }
                writeAtomically(file, GpxWriter.write("Ride ${file.nameWithoutExtension}", merged))
                RideSync.markDirty(context, file.name)
                markDone(repo, entry, "merged into ${file.name}")
                "${entry.name}: added heart rate to ${decision.rideName} ($n points)"
            }

            ImportDecision.New -> {
                val first: TrackPoint = watch.first().first()
                val stamp = RideNames.stamp(first.timeMs)
                val target = File(ridesDir, "$stamp.gpx")
                if (target.exists()) {
                    markDone(repo, entry, "already imported as ${target.name}")
                    return "${entry.name}: already imported as ${target.name}, marked done"
                }
                writeAtomically(target, GpxWriter.write("Imported $stamp", watch))
                RideSummaries.summarize(watch)?.let { local.add(RideCandidate(target.name, it.startMs, it.distanceM)) }
                markDone(repo, entry, "imported as ${target.name}")
                "${entry.name}: imported as new ride ${target.name}"
            }
        }
    }

    /**
     * A confirmed screenshot workout is stored beside the rides. It is matched to a ride later
     * (at display time, by start and distance), so it does not matter which of the GPX and the
     * screenshot reaches this phone first.
     */
    private fun handleWorkout(repo: GitHubDataRepo, entry: RepoEntry, bytes: ByteArray, ridesDir: File): String {
        val stored = WorkoutJson.fromJson(bytes.toString(Charsets.UTF_8))
            ?: return "${entry.name}: not a workout file this app wrote; left in inbox"
        val start = stored.startEpochMs ?: return "${entry.name}: no start time in the file; left in inbox"
        val target = File(ridesDir, RideNames.stamp(start) + WorkoutStore.EXTENSION_SUFFIX)
        if (target.exists()) {
            markDone(repo, entry, "already stored as ${target.name}")
            return "${entry.name}: already stored as ${target.name}, marked done"
        }
        writeAtomically(target, bytes.toString(Charsets.UTF_8))
        markDone(repo, entry, "stored as ${target.name}")
        return "${entry.name}: stored workout ${target.name} (calories, zones, recovery)"
    }

    private fun markDone(repo: GitHubDataRepo, entry: RepoEntry, note: String) {
        repo.putNew("${entry.path}.done", note.toByteArray(Charsets.UTF_8), "Mark ${entry.name} imported")
    }

    private fun loadCandidates(ridesDir: File): List<RideCandidate> =
        ridesDir.listFiles { f -> f.extension == "gpx" }.orEmpty().mapNotNull { f ->
            try {
                val s = f.inputStream().use { RideSummaries.summarize(GpxParser.parse(it)) }
                s?.let { RideCandidate(f.name, it.startMs, it.distanceM) }
            } catch (e: Exception) {
                null
            }
        }

    private fun writeAtomically(target: File, content: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(content)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
