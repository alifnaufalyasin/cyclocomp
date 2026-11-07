package com.aliven.cyclocomp.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.aliven.cyclocomp.core.ReleaseInfo
import com.aliven.cyclocomp.core.ReleaseParser
import com.aliven.cyclocomp.core.UpdateRules
import com.aliven.cyclocomp.sync.Http
import com.aliven.cyclocomp.sync.RepoPaths
import com.aliven.cyclocomp.sync.SecureStore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class UpdateException(message: String) : Exception(message)

/**
 * Where to look for new versions. The app's own repo is private, so reading its releases needs a
 * read-only token; it is kept separate from the data-repo token so that one has no access to the code.
 */
object UpdateSettings {
    private const val PREFS = "cyclocomp_update"
    private const val TOKEN = "update_token"
    const val DEFAULT_REPO = "alifnaufalyasin/cyclocomp"

    fun repo(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("repo", DEFAULT_REPO) ?: DEFAULT_REPO

    fun hasToken(context: Context): Boolean = SecureStore.get(context, TOKEN) != null
    fun token(context: Context): String? = SecureStore.get(context, TOKEN)

    fun save(context: Context, repo: String, token: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("repo", repo.trim()).apply()
        if (!token.isNullOrBlank()) SecureStore.put(context, TOKEN, token.trim())
    }

    fun clearToken(context: Context) = SecureStore.remove(context, TOKEN)
}

class AppUpdater(
    private val repo: String,
    private val token: String,
    private val http: Http,
    private val baseUrl: String = "https://api.github.com",
) {
    init {
        require(RepoPaths.isValidSlug(repo)) { "Repo must look like owner/name" }
    }

    private fun headers(accept: String) = mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to accept,
        "X-GitHub-Api-Version" to "2022-11-28",
        "User-Agent" to "cyclocomp",
    )

    /** The newest release with an APK, or null when there is none yet. */
    fun latest(): ReleaseInfo? {
        val r = http.request("GET", "$baseUrl/repos/$repo/releases/latest", headers("application/vnd.github+json"), null)
        return when (r.code) {
            200 -> ReleaseParser.parse(r.text)
            404 -> null // no release published yet (or the token cannot see the repo)
            401 -> throw UpdateException("Update token rejected (wrong, expired or revoked)")
            403 -> throw UpdateException("Update token lacks permission, or GitHub rate limit reached")
            else -> throw UpdateException("GitHub answered ${r.code}")
        }
    }

    /** The published SHA-256 of the APK, or null if the release has none. */
    fun checksum(release: ReleaseInfo): String? {
        val id = release.checksumAssetId ?: return null
        val r = http.request("GET", "$baseUrl/repos/$repo/releases/assets/$id", headers("application/octet-stream"), null)
        return if (r.code == 200) UpdateRules.parseChecksum(r.text) else null
    }

    /**
     * Streams the APK into [target]. GitHub answers the asset request with a redirect to a signed
     * download address, which HttpURLConnection follows. [onProgress] receives bytes so far.
     */
    fun download(release: ReleaseInfo, target: File, onProgress: (Long) -> Unit) {
        val conn = URL("$baseUrl/repos/$repo/releases/assets/${release.assetId}").openConnection() as HttpURLConnection
        try {
            headers("application/octet-stream").forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            if (conn.responseCode != 200) throw UpdateException("Download failed (HTTP ${conn.responseCode})")
            target.parentFile?.mkdirs()
            var total = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        onProgress(total)
                    }
                }
            }
            if (release.assetBytes > 0 && total != release.assetBytes) {
                target.delete()
                throw UpdateException("Download incomplete ($total of ${release.assetBytes} bytes)")
            }
        } finally {
            conn.disconnect()
        }
    }
}

object ApkInstaller {
    fun updateFile(context: Context, version: String) = File(context.cacheDir, "updates/cyclocomp-$version.apk")

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * True when the downloaded APK is signed with the same key as the installed app. Android would
     * refuse a differently signed update anyway; checking first gives a clear message. On Android
     * 8-9 the signing check is not available, so those versions rely on the system installer.
     */
    fun sameSigner(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)?.signingInfo ?: return false
        val installed = pm.getPackageInfo(context.packageName, flags).signingInfo ?: return false
        val a = archive.apkContentsSigners.map { it.toByteArray().toList() }.toSet()
        val b = installed.apkContentsSigners.map { it.toByteArray().toList() }.toSet()
        return a.isNotEmpty() && a == b
    }

    fun installedVersionName(context: Context): String? =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the system page where the user allows this app to install updates (one time). */
    fun allowInstallsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Hands the APK to the system installer; Android asks the user to confirm. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
