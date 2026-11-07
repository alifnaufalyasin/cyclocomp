package com.aliven.cyclocomp.sync

import android.content.Context
import com.aliven.cyclocomp.core.GpxParser
import com.aliven.cyclocomp.core.HrMerger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Brings this phone's rides in line with the repo, so any phone shows the same history. */
object RidesPuller {
    /** Every file under `rides/<year>/` in the repo. */
    fun fetchRemote(repo: DataStore): List<RemoteFile> {
        val remote = mutableListOf<RemoteFile>()
        for (year in repo.list("rides").filter { it.type == "dir" }) {
            for (e in repo.list(year.path)) {
                if (e.type == "file") remote.add(RemoteFile(e.path, e.name, e.sha))
            }
        }
        return remote
    }

    fun run(context: Context, repo: DataStore, ridesDir: File, remote: List<RemoteFile>): List<String> {
        val lines = mutableListOf<String>()
        val local = ridesDir.listFiles().orEmpty().map { it.name }.toSet()
        val actions = PullPlanner.plan(remote, local, SyncState.lastSeenSha(context), SyncState.deletedHere(context))
        var downloaded = 0
        var updated = 0
        var removed = 0

        for (action in actions) {
            try {
                when (action) {
                    is PullAction.Download -> {
                        val bytes = repo.getRaw(action.file.path) ?: continue
                        writeAtomically(File(ridesDir, action.file.name), bytes)
                        remember(context, action.file)
                        downloaded++
                    }
                    is PullAction.Compare -> {
                        val bytes = repo.getRaw(action.file.path) ?: continue
                        val target = File(ridesDir, action.file.name)
                        val localHr = hrPoints(target.readBytes())
                        val remoteHr = hrPoints(bytes)
                        if (PullPlanner.remoteWins(localHr, remoteHr)) {
                            writeAtomically(target, bytes)
                            updated++
                        } else if (localHr > remoteHr) {
                            RideSync.markDirty(context, target.name) // this phone has the richer copy: push it
                        }
                        remember(context, action.file)
                    }
                    is PullAction.DeleteLocal -> {
                        File(ridesDir, action.name).delete()
                        removed++
                    }
                }
            } catch (e: StoreException) {
                lines.add("${(action as? PullAction.Download)?.file?.name ?: ""}: ${e.message}")
                if (e.code == 401 || e.code == 403) break
            } catch (e: Exception) {
                lines.add("pull failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        lines.add("Rides from repo: $downloaded new, $updated updated, $removed removed")
        return lines
    }

    /** A pulled file already exists in the repo, so it must not be uploaded again. */
    private fun remember(context: Context, file: RemoteFile) {
        SyncState.setLastSeenSha(context, file.name, file.sha)
        RideSync.markUploaded(context, file.name)
    }

    private fun hrPoints(gpx: ByteArray): Int =
        try {
            HrMerger.countWithHr(GpxParser.parse(gpx.inputStream()))
        } catch (e: Exception) {
            0
        }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
