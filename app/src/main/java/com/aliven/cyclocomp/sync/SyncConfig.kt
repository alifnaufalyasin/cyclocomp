package com.aliven.cyclocomp.sync

import android.content.Context

/** Which kind of storage this phone syncs to. */
enum class Backend { GITHUB, ACCOUNT }

/** Everything the sync code needs: which phone this is, a label for messages, and where to store. */
class SyncConfig(val device: String, val label: String, val store: DataStore)

/**
 * Where to sync. Phone name, repo name and the chosen backend are plain preferences; secrets (the
 * GitHub token, the account session) go through [SecureStore].
 */
object SyncSettings {
    private const val PREFS = "cyclocomp_sync"
    private const val TOKEN = "github_token"

    fun backend(context: Context): Backend =
        if (prefs(context).getString("backend", null) == Backend.ACCOUNT.name) Backend.ACCOUNT else Backend.GITHUB

    fun setBackend(context: Context, backend: Backend) {
        prefs(context).edit().putString("backend", backend.name).apply()
    }

    fun slug(context: Context): String = prefs(context).getString("slug", "") ?: ""
    fun device(context: Context): String = prefs(context).getString("device", "") ?: ""
    fun hasToken(context: Context): Boolean = SecureStore.get(context, TOKEN) != null

    fun save(context: Context, slug: String, device: String, token: String?) {
        prefs(context).edit().putString("slug", slug.trim()).putString("device", device.trim()).apply()
        if (!token.isNullOrBlank()) SecureStore.put(context, TOKEN, token.trim())
    }

    /** Only the phone name (used by both backends). */
    fun saveDevice(context: Context, device: String) {
        prefs(context).edit().putString("device", device.trim()).apply()
    }

    fun clearToken(context: Context) = SecureStore.remove(context, TOKEN)

    /** Null until the chosen backend is fully set up (repo + token, or a signed-in account) and the phone has a name. */
    fun load(context: Context): SyncConfig? {
        val device = device(context)
        if (device.isBlank()) return null
        val safeDevice = RepoPaths.safeSegment(device)
        return when (backend(context)) {
            Backend.GITHUB -> {
                val slug = slug(context)
                val token = SecureStore.get(context, TOKEN)
                if (!RepoPaths.isValidSlug(slug) || token == null) null
                else SyncConfig(safeDevice, slug, GitHubDataRepo(slug, token, UrlConnectionHttp()))
            }
            Backend.ACCOUNT -> {
                val session = AccountSettings.load(context) ?: return null
                val store = PocketBaseStore(session, UrlConnectionHttp(), onSession = { AccountSettings.save(context, it) })
                SyncConfig(safeDevice, session.email, store)
            }
        }
    }

    fun repo(config: SyncConfig): DataStore = config.store

    /** What to tell the user is missing when [load] returns null. */
    fun missingMessage(context: Context): String = when {
        device(context).isBlank() -> "Give this phone a name first."
        backend(context) == Backend.ACCOUNT -> "Sign in to your account first."
        else -> "Fill in the repo, phone name and token first."
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** The signed-in account on the self-hosted server. The session token is stored encrypted. */
object AccountSettings {
    private const val PREFS = "cyclocomp_account"
    private const val TOKEN = "account_token"

    /** The last server address typed in, kept even when signed out so it need not be typed again. */
    fun serverUrl(context: Context): String = prefs(context).getString("server_url", "") ?: ""

    fun saveServerUrl(context: Context, url: String) {
        prefs(context).edit().putString("server_url", url.trim()).apply()
    }

    fun load(context: Context): PbSession? {
        val p = prefs(context)
        val url = p.getString("server_url", null)?.takeIf { it.isNotBlank() } ?: return null
        val id = p.getString("user_id", null) ?: return null
        val email = p.getString("email", null) ?: return null
        val token = SecureStore.get(context, TOKEN) ?: return null
        return PbSession(url, token, id, email)
    }

    fun save(context: Context, session: PbSession) {
        prefs(context).edit()
            .putString("server_url", session.baseUrl).putString("user_id", session.userId)
            .putString("email", session.email).apply()
        SecureStore.put(context, TOKEN, session.token)
    }

    /** Signing out only forgets the session on this phone; the account and its data stay on the server. */
    fun clear(context: Context) {
        SecureStore.remove(context, TOKEN)
        prefs(context).edit().remove("user_id").remove("email").apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
