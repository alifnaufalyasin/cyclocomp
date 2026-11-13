package com.aliven.cyclocomp.sync

import android.content.Context
import com.aliven.cyclocomp.core.RideNames
import com.aliven.cyclocomp.recording.RideRecorder
import com.aliven.cyclocomp.core.RideCandidate
import java.io.File

/**
 * Ride-phone side of the two-phone workflow: pulls watch GPX files that the capture phone put
 * in `inbox/<phone>/`, then merges heart rate into the matching local ride or imports the file
 * as a new ride. A `<file>.done` marker is written next to each handled file so it is not
 * processed twice (by either phone). Files left unhandled are reported and retried next time.
 */
object InboxImporter {
    fun run(context: Context, repo: DataStore): List<String> {
        RideRecorder.init(context.applicationContext)
        val ridesDir = RideRecorder.ridesDir()
        val local = RideImporter.loadCandidates(ridesDir)
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
                } catch (e: StoreException) {
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
        repo: DataStore,
        entry: RepoEntry,
        ridesDir: File,
        local: MutableList<RideCandidate>,
    ): String {
        val bytes = repo.getRaw(entry.path) ?: return "${entry.name}: disappeared from the repo"
        if (entry.name.endsWith(WorkoutJson.SUFFIX)) return handleWorkout(repo, entry, bytes, ridesDir)

        return when (val outcome = RideImporter.importTrack(bytes, ridesDir, local) { RideSync.markDirty(context, it.name) }) {
            is RideImportOutcome.Skipped -> "${entry.name}: skipped, ${outcome.reason}"

            is RideImportOutcome.AlreadyHasHr -> {
                markDone(repo, entry, "ride already has heart rate")
                "${entry.name}: ${outcome.rideName} already has heart rate, marked done"
            }

            is RideImportOutcome.NoHrToAdd ->
                "${entry.name}: matched ${outcome.rideName} but no heart-rate sample lines up in time; left in inbox"

            is RideImportOutcome.Merged -> {
                markDone(repo, entry, "merged into ${outcome.rideName}")
                "${entry.name}: added heart rate to ${outcome.rideName} (${outcome.points} points)"
            }

            is RideImportOutcome.Created -> {
                markDone(repo, entry, "imported as ${outcome.rideName}")
                "${entry.name}: imported as new ride ${outcome.rideName}"
            }

            is RideImportOutcome.AlreadyImported -> {
                markDone(repo, entry, "already imported as ${outcome.rideName}")
                "${entry.name}: already imported as ${outcome.rideName}, marked done"
            }
        }
    }

    /**
     * A confirmed screenshot workout is stored beside the rides. It is matched to a ride later
     * (at display time, by start and distance), so it does not matter which of the GPX and the
     * screenshot reaches this phone first.
     */
    private fun handleWorkout(repo: DataStore, entry: RepoEntry, bytes: ByteArray, ridesDir: File): String {
        val stored = WorkoutJson.fromJson(bytes.toString(Charsets.UTF_8))
            ?: return "${entry.name}: not a workout file this app wrote; left in inbox"
        val start = stored.startEpochMs ?: return "${entry.name}: no start time in the file; left in inbox"
        val target = File(ridesDir, RideNames.stamp(start) + WorkoutStore.EXTENSION_SUFFIX)
        if (target.exists()) {
            markDone(repo, entry, "already stored as ${target.name}")
            return "${entry.name}: already stored as ${target.name}, marked done"
        }
        RideImporter.writeAtomically(target, bytes.toString(Charsets.UTF_8))
        markDone(repo, entry, "stored as ${target.name}")
        return "${entry.name}: stored workout ${target.name} (calories, zones, recovery)"
    }

    private fun markDone(repo: DataStore, entry: RepoEntry, note: String) {
        repo.putNew("${entry.path}.done", note.toByteArray(Charsets.UTF_8), "Mark ${entry.name} imported")
    }
}
