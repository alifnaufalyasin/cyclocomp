package com.aliven.cyclocomp.sync

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

class RepoEntry(val name: String, val type: String, val path: String)

enum class PutResult { CREATED, EXISTS }

class GitHubException(val code: Int, message: String) : Exception(message)

object RepoPaths {
    private val slug = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

    fun isValidSlug(s: String) = slug.matches(s)

    /** One path segment safe to use in a repo path (device names, uploaded file names). */
    fun safeSegment(s: String): String {
        val cleaned = s.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_')
        return cleaned.ifEmpty { "file" }.take(80)
    }
}

/**
 * Minimal client for a private data repo over the GitHub contents API. Files are only ever
 * created, never overwritten: a name that already exists is reported as [PutResult.EXISTS].
 */
class GitHubDataRepo(
    private val slug: String,
    private val token: String,
    private val http: Http,
    private val baseUrl: String = "https://api.github.com",
) {
    fun putNew(path: String, bytes: ByteArray, message: String): PutResult {
        val body = JSONObject()
            .put("message", message)
            .put("content", Base64.getEncoder().encodeToString(bytes))
            .toString().toByteArray(Charsets.UTF_8)
        val r = http.request("PUT", contentsUrl(path), headers(), body)
        return when {
            r.code == 200 || r.code == 201 -> PutResult.CREATED
            // GitHub answers 422 "sha wasn't supplied" when the file already exists.
            r.code == 422 && r.text.contains("sha") -> PutResult.EXISTS
            else -> throw failure(r)
        }
    }

    fun list(dir: String): List<RepoEntry> {
        val r = http.request("GET", contentsUrl(dir), headers(), null)
        if (r.code == 404) return emptyList()
        if (r.code != 200) throw failure(r)
        val trimmed = r.text.trimStart()
        if (!trimmed.startsWith("[")) return emptyList() // a single file, not a directory
        val arr = JSONArray(trimmed)
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            RepoEntry(o.getString("name"), o.getString("type"), o.getString("path"))
        }
    }

    fun getRaw(path: String): ByteArray? {
        val r = http.request("GET", contentsUrl(path), headers("application/vnd.github.raw+json"), null)
        if (r.code == 404) return null
        if (r.code != 200) throw failure(r)
        return r.body
    }

    /** Throws [GitHubException] with a readable message if the repo can't be reached with this token. */
    fun checkAccess() {
        val r = http.request("GET", "$baseUrl/repos/$slug", headers(), null)
        if (r.code != 200) throw failure(r)
    }

    private fun contentsUrl(path: String): String =
        "$baseUrl/repos/$slug/contents/" + path.split("/").joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }

    private fun headers(accept: String = "application/vnd.github+json") = mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to accept,
        "X-GitHub-Api-Version" to "2022-11-28",
        "User-Agent" to "cyclocomp",
    )

    private fun failure(r: HttpResponse): GitHubException {
        val hint = when (r.code) {
            401 -> "token rejected (wrong, expired or revoked)"
            403 -> "token lacks permission (needs Contents read/write on this repo) or rate limited"
            404 -> "repo not found, or the token can't see it"
            else -> "unexpected response"
        }
        return GitHubException(r.code, "GitHub ${r.code}: $hint")
    }
}
