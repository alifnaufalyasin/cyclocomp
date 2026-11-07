package com.aliven.cyclocomp.sync

import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64

/** A signed-in account on the server. The token is the account's own session, never a shared secret. */
class PbSession(val baseUrl: String, val token: String, val userId: String, val email: String)

/** Reads the expiry time out of a session token (a JWT) without checking its signature. */
object JwtExpiry {
    fun expiresAtMs(token: String): Long? = try {
        val payload = token.split('.')[1]
        val json = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        JSONObject(json).getLong("exp") * 1000
    } catch (e: Exception) {
        null
    }
}

/** Checks a new password before anything is sent; the server has the same limits (8 to 72 characters). */
object PasswordRules {
    const val MIN = 8
    const val MAX = 72

    /** A message for the first problem found, or null when the change can be sent. */
    fun check(old: String, new: String, confirm: String): String? = when {
        old.isEmpty() -> "Enter your current password."
        new.length < MIN -> "The new password needs at least $MIN characters."
        new.length > MAX -> "The new password can have at most $MAX characters."
        new != confirm -> "The two new passwords do not match."
        new == old -> "The new password must be different from the current one."
        else -> null
    }
}

/** Turns the server's error answers into short messages a person can act on. */
internal object PbErrors {
    /** The reason a password change was refused (400): usually a wrong current password. */
    fun passwordProblem(body: String): String = try {
        val data = JSONObject(body).optJSONObject("data")
        when {
            data?.has("oldPassword") == true -> "Your current password is wrong."
            data?.optJSONObject("password") != null ->
                "The server did not accept the new password: " + data.getJSONObject("password").optString("message")
            else -> "The password could not be changed."
        }
    } catch (e: Exception) {
        "The password could not be changed."
    }

    fun failure(code: Int, body: String): StoreException {
        val serverMessage = try {
            JSONObject(body).optString("message").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
        val text = when (code) {
            401 -> "You are not signed in, or the session has expired. Sign in again."
            403 -> "The server refused this request."
            404 -> "Not found on the server."
            413 -> "That file is too large for the server."
            429 -> "Too many requests. Wait a minute and try again."
            else -> serverMessage ?: "The server answered $code."
        }
        return StoreException(code, text)
    }
}

class PocketBaseAuth(baseUrl: String, private val http: Http) {
    private val base = baseUrl.trimEnd('/')

    private fun json(method: String, path: String, token: String?, body: JSONObject?): HttpResponse {
        val headers = mutableMapOf("Accept" to "application/json", "User-Agent" to "cyclocomp")
        if (body != null) headers["Content-Type"] = "application/json"
        if (token != null) headers["Authorization"] = token
        return try {
            http.request(method, base + path, headers, body?.toString()?.toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            throw StoreException(0, "Server not reachable (${e.message ?: "network error"})")
        }
    }

    fun login(email: String, password: String): PbSession {
        val r = json(
            "POST", "/api/collections/users/auth-with-password", null,
            JSONObject().put("identity", email.trim()).put("password", password),
        )
        if (r.code == 400) throw StoreException(400, "Wrong email or password.")
        if (r.code != 200) throw PbErrors.failure(r.code, r.text)
        return session(r.text, email.trim())
    }

    /** Gives the same account a fresh token that lasts longer. Fails with 401 if the old one has expired. */
    fun refresh(s: PbSession): PbSession {
        val r = json("POST", "/api/collections/users/auth-refresh", s.token, null)
        if (r.code != 200) throw PbErrors.failure(r.code, r.text)
        return session(r.text, s.email)
    }

    /**
     * Asks the server to email a password-reset link. The server answers the same way whether or not the
     * address has an account, so this cannot be used to find out who has one.
     */
    fun requestPasswordReset(email: String) {
        val address = email.trim()
        if (!address.contains('@') || address.length < 3) throw StoreException(400, "Enter the email address of your account.")
        val r = json("POST", "/api/collections/users/request-password-reset", null, JSONObject().put("email", address))
        when (r.code) {
            200, 204 -> return
            400, 500 -> throw StoreException(r.code, "The server could not send the email. Ask the server owner to set up email sending.")
            else -> throw PbErrors.failure(r.code, r.text)
        }
    }

    /**
     * Changes the password of the signed-in account. The server asks for the current password, so
     * nobody can do this with only a stolen session. The old session token stops working once the
     * password changes, so this signs in again and returns the new session.
     */
    fun changePassword(s: PbSession, oldPassword: String, newPassword: String): PbSession {
        val r = json(
            "PATCH", "/api/collections/users/records/${s.userId}", s.token,
            JSONObject().put("oldPassword", oldPassword).put("password", newPassword).put("passwordConfirm", newPassword),
        )
        if (r.code == 400) throw StoreException(400, PbErrors.passwordProblem(r.text))
        if (r.code != 200) throw PbErrors.failure(r.code, r.text)
        return login(s.email, newPassword)
    }

    /** Deletes the account; the server removes every file the account owns along with it. */
    fun deleteAccount(s: PbSession) {
        val r = json("DELETE", "/api/collections/users/records/${s.userId}", s.token, null)
        if (r.code != 204 && r.code != 200) throw PbErrors.failure(r.code, r.text)
    }

    private fun session(body: String, fallbackEmail: String): PbSession {
        val o = JSONObject(body)
        val record = o.getJSONObject("record")
        return PbSession(base, o.getString("token"), record.getString("id"), record.optString("email").ifEmpty { fallbackEmail })
    }
}

/**
 * Data store backed by the self-hosted account server. Every file is one record of the `files`
 * collection (owner, path, sha, content); the server's rules make each account see only its own.
 */
class PocketBaseStore(
    session: PbSession,
    private val http: Http,
    /** Called when the session token was renewed, so the new one can be saved. */
    private val onSession: (PbSession) -> Unit = {},
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : DataStore {
    private var s = session
    private var checkedFresh = false

    private class Rec(val id: String, val collectionId: String, val path: String, val sha: String, val content: String)

    // ------------------------------------------------------------------ DataStore

    override fun checkAccess() {
        val r = send("GET", "/api/collections/files/records?perPage=1&fields=id")
        if (r.code != 200) throw PbErrors.failure(r.code, r.text)
    }

    override fun putNew(path: String, bytes: ByteArray, message: String): PutResult {
        val r = create(path, bytes)
        return when {
            r.code == 200 || r.code == 201 -> PutResult.CREATED
            // The server refuses a second file with the same path; make sure that is the reason.
            r.code == 400 && findOne(path) != null -> PutResult.EXISTS
            else -> throw PbErrors.failure(r.code, r.text)
        }
    }

    override fun putOrUpdate(path: String, bytes: ByteArray, message: String) {
        checkSize(bytes)
        val existing = findOne(path)
        val r = if (existing == null) {
            create(path, bytes)
        } else {
            val mp = Multipart.build(mapOf("sha" to GitSha.blob(bytes)), "content", fileName(path), bytes)
            send("PATCH", "/api/collections/files/records/${existing.id}", mp.bytes, mp.contentType)
        }
        if (r.code != 200 && r.code != 201) throw PbErrors.failure(r.code, r.text)
    }

    override fun list(dir: String): List<RepoEntry> {
        val folder = dir.trim('/')
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        // Prefix scan on the path: "dir/" <= path < "dir0" ('0' is the character after '/').
        val filter = if (prefix.isEmpty()) "path != ''" else "path >= ${lit(prefix)} && path < ${lit(folder + "0")}"
        val children = linkedMapOf<String, RepoEntry>()
        for (rec in records(filter)) {
            if (!rec.path.startsWith(prefix)) continue
            val rest = rec.path.removePrefix(prefix)
            if (rest.isEmpty()) continue
            val slash = rest.indexOf('/')
            if (slash < 0) {
                children[rest] = RepoEntry(rest, "file", rec.path, rec.sha)
            } else {
                val name = rest.substring(0, slash)
                children.putIfAbsent(name, RepoEntry(name, "dir", prefix + name))
            }
        }
        return children.values.sortedBy { it.name }
    }

    override fun getRaw(path: String): ByteArray? {
        val rec = findOne(path) ?: return null
        if (rec.content.isBlank()) return null
        val t = send("POST", "/api/files/token")
        if (t.code != 200) throw PbErrors.failure(t.code, t.text)
        val token = JSONObject(t.text).getString("token")
        val r = send("GET", "/api/files/${rec.collectionId}/${rec.id}/${enc(rec.content)}?token=${enc(token)}")
        return when (r.code) {
            200 -> r.body
            404 -> null
            else -> throw PbErrors.failure(r.code, r.text)
        }
    }

    // ------------------------------------------------------------------ requests

    private fun create(path: String, bytes: ByteArray): HttpResponse {
        checkSize(bytes)
        val mp = Multipart.build(
            mapOf("owner" to s.userId, "path" to path, "sha" to GitSha.blob(bytes)),
            "content", fileName(path), bytes,
        )
        return send("POST", "/api/collections/files/records", mp.bytes, mp.contentType)
    }

    private fun findOne(path: String): Rec? = records("path = ${lit(path)}").firstOrNull { it.path == path }

    private fun records(filter: String): List<Rec> {
        val out = mutableListOf<Rec>()
        var page = 1
        while (true) {
            val r = send(
                "GET",
                "/api/collections/files/records?perPage=500&page=$page&sort=path" +
                    "&fields=${enc("id,collectionId,path,sha,content")}&filter=${enc(filter)}",
            )
            if (r.code != 200) throw PbErrors.failure(r.code, r.text)
            val o = JSONObject(r.text)
            val items = o.getJSONArray("items")
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                out.add(
                    Rec(
                        item.getString("id"), item.optString("collectionId"), item.getString("path"),
                        item.optString("sha"), item.optString("content"),
                    )
                )
            }
            if (page >= o.optInt("totalPages", 1)) break
            page++
        }
        return out
    }

    private fun send(method: String, path: String, body: ByteArray? = null, contentType: String? = null): HttpResponse {
        ensureFresh()
        val headers = mutableMapOf("Authorization" to s.token, "Accept" to "application/json", "User-Agent" to "cyclocomp")
        if (contentType != null) headers["Content-Type"] = contentType
        return try {
            http.request(method, s.baseUrl + path, headers, body)
        } catch (e: IOException) {
            throw StoreException(0, "Server not reachable (${e.message ?: "network error"})")
        }
    }

    /** Renews the session once per run when less than a week of it is left, so regular use never signs you out. */
    private fun ensureFresh() {
        if (checkedFresh) return
        checkedFresh = true
        val expires = JwtExpiry.expiresAtMs(s.token) ?: return
        if (expires - nowMs() >= REFRESH_WITHIN_MS) return
        try {
            s = PocketBaseAuth(s.baseUrl, http).refresh(s)
            onSession(s)
        } catch (e: StoreException) {
            // A rejected token means the session is over; anything else (e.g. no network) is retried later.
            if (e.code == 401 || e.code == 400) throw StoreException(401, "Your session has expired. Sign in again.")
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun checkSize(bytes: ByteArray) {
        if (bytes.size > MAX_FILE_BYTES) throw StoreException(413, "That file is too large for the server (max 25 MB).")
    }

    private fun fileName(path: String) = RepoPaths.safeSegment(path.substringAfterLast('/'))

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    /** A string literal for the server's filter language. */
    private fun lit(v: String) = "'" + v.replace("\\", "\\\\").replace("'", "\\'") + "'"

    companion object {
        const val MAX_FILE_BYTES = 25 * 1024 * 1024
        private const val REFRESH_WITHIN_MS = 7L * 24 * 3600 * 1000
    }
}
