package com.aliven.cyclocomp.sync

import org.json.JSONObject
import java.security.MessageDigest

/** Git's blob id for [bytes], so a local file can be compared with the sha GitHub lists. */
object GitSha {
    fun blob(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

class RemoteFile(val path: String, val name: String, val sha: String)

sealed interface PullAction {
    /** Not on this phone yet: download it. */
    class Download(val file: RemoteFile) : PullAction

    /** Both phones have it but the repo copy changed: decide which has more data. */
    class Compare(val file: RemoteFile) : PullAction

    /** Deleted on another phone: remove the local copy. */
    class DeleteLocal(val name: String) : PullAction
}

/**
 * Decides what a phone has to do to catch up with the repo's `rides/` folders.
 *
 * Every phone ends up with the same rides, and nothing is lost: a ride deleted on one phone is
 * recorded as a `<ride>.gpx.deleted` marker, so another phone does not bring it back.
 */
object PullPlanner {
    const val TOMBSTONE_SUFFIX = ".deleted"

    fun isRide(name: String) = name.endsWith(".gpx", ignoreCase = true)
    fun isWorkout(name: String) = name.endsWith(WorkoutStore.EXTENSION_SUFFIX)

    fun plan(
        remote: List<RemoteFile>,
        localNames: Set<String>,
        /** Repo sha seen the last time this phone synced each file. */
        lastSeenSha: Map<String, String>,
        /** Rides the user deleted on this phone (kept even if the marker is not uploaded yet). */
        deletedHere: Set<String>,
    ): List<PullAction> {
        val tombstoned = remote.filter { it.name.endsWith(TOMBSTONE_SUFFIX) }
            .map { it.name.removeSuffix(TOMBSTONE_SUFFIX) }.toSet()
        val actions = mutableListOf<PullAction>()
        for (f in remote) {
            when {
                isRide(f.name) -> when {
                    f.name in deletedHere -> Unit
                    f.name in tombstoned -> if (f.name in localNames) actions.add(PullAction.DeleteLocal(f.name))
                    f.name !in localNames -> actions.add(PullAction.Download(f))
                    lastSeenSha[f.name] == f.sha -> Unit
                    else -> actions.add(PullAction.Compare(f))
                }
                // A screenshot workout never changes once written.
                isWorkout(f.name) -> if (f.name !in localNames) actions.add(PullAction.Download(f))
            }
        }
        return actions
    }

    /** Heart rate is only ever added to a ride, so the copy with more samples is the newer one. */
    fun remoteWins(localHrPoints: Int, remoteHrPoints: Int) = remoteHrPoints > localHrPoints
}

/** Heart-rate settings shared between phones; the most recently saved copy wins. */
object TrainingSettingsJson {
    class Values(val restHr: Int, val maxHr: Int, val updatedMs: Long)

    fun toJson(v: Values): String = JSONObject()
        .put("rest_hr", v.restHr).put("max_hr", v.maxHr).put("updated_ms", v.updatedMs).toString(2)

    fun fromJson(text: String): Values? = try {
        val o = JSONObject(text)
        Values(o.getInt("rest_hr"), o.getInt("max_hr"), o.getLong("updated_ms"))
    } catch (e: Exception) {
        null
    }

    /** True when the repo copy is newer than this phone's own (a never-saved phone has updatedMs 0). */
    fun remoteIsNewer(localUpdatedMs: Long, remoteUpdatedMs: Long) = remoteUpdatedMs > localUpdatedMs
}
