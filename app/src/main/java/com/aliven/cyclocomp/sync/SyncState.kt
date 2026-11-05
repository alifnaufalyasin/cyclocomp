package com.aliven.cyclocomp.sync

import android.content.Context
import org.json.JSONObject

/** What this phone remembers between syncs. Per-phone, never uploaded. */
object SyncState {
    private const val PREFS = "cyclocomp_sync_state"
    private const val LAST_SHA = "last_seen_sha"
    private const val DELETED_HERE = "deleted_here"
    private const val PENDING_TOMBSTONES = "pending_tombstones"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastSeenSha(context: Context): Map<String, String> {
        val text = prefs(context).getString(LAST_SHA, null) ?: return emptyMap()
        return try {
            val o = JSONObject(text)
            o.keys().asSequence().associateWith { o.getString(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun setLastSeenSha(context: Context, name: String, sha: String) {
        val o = JSONObject(lastSeenSha(context) as Map<*, *>)
        o.put(name, sha)
        prefs(context).edit().putString(LAST_SHA, o.toString()).apply()
    }

    fun deletedHere(context: Context): Set<String> =
        prefs(context).getStringSet(DELETED_HERE, emptySet()).orEmpty().toSet()

    fun pendingTombstones(context: Context): Set<String> =
        prefs(context).getStringSet(PENDING_TOMBSTONES, emptySet()).orEmpty().toSet()

    /** The user deleted a ride here: never pull it back, and tell the other phones at the next sync. */
    fun recordDeletion(context: Context, rideName: String) {
        val p = prefs(context)
        p.edit()
            .putStringSet(DELETED_HERE, deletedHere(context) + rideName)
            .putStringSet(PENDING_TOMBSTONES, pendingTombstones(context) + rideName)
            .apply()
    }

    fun tombstoneUploaded(context: Context, rideName: String) {
        prefs(context).edit().putStringSet(PENDING_TOMBSTONES, pendingTombstones(context) - rideName).apply()
    }
}
