package com.aliven.cyclocomp.sync

import android.content.Context

data class SyncConfig(val slug: String, val device: String, val token: String)

/** Where to sync. Repo name and device name are plain prefs; the token goes through [SecureStore]. */
object SyncSettings {
    private const val PREFS = "cyclocomp_sync"
    private const val TOKEN = "github_token"

    fun slug(context: Context): String = prefs(context).getString("slug", "") ?: ""
    fun device(context: Context): String = prefs(context).getString("device", "") ?: ""
    fun hasToken(context: Context): Boolean = SecureStore.get(context, TOKEN) != null

    fun save(context: Context, slug: String, device: String, token: String?) {
        prefs(context).edit().putString("slug", slug.trim()).putString("device", device.trim()).apply()
        if (!token.isNullOrBlank()) SecureStore.put(context, TOKEN, token.trim())
    }

    fun clearToken(context: Context) = SecureStore.remove(context, TOKEN)

    /** Null until repo, device and token are all set and valid. */
    fun load(context: Context): SyncConfig? {
        val slug = slug(context)
        val device = device(context)
        val token = SecureStore.get(context, TOKEN)
        if (!RepoPaths.isValidSlug(slug) || device.isBlank() || token == null) return null
        return SyncConfig(slug, RepoPaths.safeSegment(device), token)
    }

    fun repo(config: SyncConfig) = GitHubDataRepo(config.slug, config.token, UrlConnectionHttp())

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
