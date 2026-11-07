package com.aliven.cyclocomp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliven.cyclocomp.core.ReleaseInfo
import com.aliven.cyclocomp.core.UpdateRules
import com.aliven.cyclocomp.sync.RepoPaths
import com.aliven.cyclocomp.sync.UrlConnectionHttp
import com.aliven.cyclocomp.update.ApkInstaller
import com.aliven.cyclocomp.update.AppUpdater
import com.aliven.cyclocomp.update.UpdateSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Checks the app's releases on GitHub and installs a newer version in place. */
@Composable
fun UpdateScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = remember { ApkInstaller.installedVersionName(context) }

    var repo by remember { mutableStateOf(UpdateSettings.repo(context)) }
    var token by remember { mutableStateOf("") }
    var hasToken by remember { mutableStateOf(UpdateSettings.hasToken(context)) }
    var latest by remember { mutableStateOf<ReleaseInfo?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var readyToInstall by remember { mutableStateOf(false) }

    val save = {
        UpdateSettings.save(context, repo, token)
        token = ""
        hasToken = UpdateSettings.hasToken(context)
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("App update", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text("Installed version: ${installed ?: "unknown"}", fontSize = 16.sp)
        Text(
            "Looks for a newer release in the app's repository on GitHub. The repository is private, so a " +
                "separate read-only token is needed: a fine-grained token limited to that one repository with " +
                "Contents: read-only. It is stored encrypted on this phone.",
            fontSize = 14.sp,
        )
        OutlinedTextField(
            value = repo, onValueChange = { repo = it }, singleLine = true,
            label = { Text("App repo (owner/name)") }, modifier = Modifier.fillMaxWidth(),
            isError = repo.isNotBlank() && !RepoPaths.isValidSlug(repo.trim()),
        )
        OutlinedTextField(
            value = token, onValueChange = { token = it }, singleLine = true,
            label = { Text(if (hasToken) "Update token (saved — type to replace)" else "Update token (read-only)") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            enabled = !busy, modifier = Modifier.fillMaxWidth(),
            onClick = {
                save()
                scope.launch {
                    busy = true
                    readyToInstall = false
                    status = "Checking…"
                    val result = withContext(Dispatchers.IO) { checkLatest(context) }
                    latest = result.first
                    status = result.second
                    busy = false
                }
            },
        ) { Text("Check for update") }

        val release = latest
        if (release != null) {
            val newer = UpdateRules.isNewer(installed, release.version)
            Text(
                if (newer) "New version available: ${release.version}" else "You have the latest version (${release.version}).",
                fontWeight = FontWeight.Bold,
            )
            if (release.notes.isNotBlank()) Text(release.notes.take(800), fontSize = 13.sp)
            if (newer) {
                Button(
                    enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            busy = true
                            val outcome = withContext(Dispatchers.IO) {
                                downloadAndVerify(context, release) { bytes ->
                                    status = String.format(Locale.US, "Downloading… %.1f MB", bytes / 1_048_576.0)
                                }
                            }
                            status = outcome
                            readyToInstall = outcome == READY
                            if (readyToInstall) status = installOrAsk(context)
                            busy = false
                        }
                    },
                ) { Text("Download and install ${release.version}") }
                if (readyToInstall) {
                    OutlinedButton(onClick = { status = installOrAsk(context) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Install again")
                    }
                }
            }
        }

        status?.let { Text(it) }

        if (hasToken) {
            TextButton(onClick = { UpdateSettings.clearToken(context); hasToken = false; status = "Update token removed." }) {
                Text("Remove saved update token")
            }
        }
    }
}

private const val READY = "Downloaded and verified."

/** Returns the newest release (or null) and a message for the screen. */
private fun checkLatest(context: android.content.Context): Pair<ReleaseInfo?, String?> {
    val token = UpdateSettings.token(context) ?: return null to "Enter the update token first."
    return try {
        val updater = AppUpdater(UpdateSettings.repo(context), token, UrlConnectionHttp())
        val release = updater.latest()
        if (release == null) null to "No release with an APK has been published yet."
        else release to null
    } catch (e: Exception) {
        null to (e.message ?: "Could not check for updates")
    }
}

/** Downloads the APK, checks its size, checksum and signer. Returns [READY] or an explanation. */
private fun downloadAndVerify(context: android.content.Context, release: ReleaseInfo, onProgress: (Long) -> Unit): String {
    val token = UpdateSettings.token(context) ?: return "Enter the update token first."
    return try {
        val updater = AppUpdater(UpdateSettings.repo(context), token, UrlConnectionHttp())
        val file = ApkInstaller.updateFile(context, release.version.toString())
        // Keep only the APK being downloaded; older ones are just taking up space.
        file.parentFile?.listFiles()?.forEach { if (it.extension == "apk") it.delete() }
        updater.download(release, file, onProgress)

        val expected = updater.checksum(release)
        if (expected != null && ApkInstaller.sha256(file) != expected) {
            file.delete()
            return "The download does not match its published checksum, so it was discarded."
        }
        if (!ApkInstaller.sameSigner(context, file)) {
            file.delete()
            return "This release is signed with a different key than the installed app, so Android would refuse it. " +
                "Uninstall once and install the release APK (sync your rides first), then updates will work."
        }
        READY
    } catch (e: Exception) {
        e.message ?: "Download failed"
    }
}

/** Opens the installer, or the settings page where installing from this app has to be allowed first. */
private fun installOrAsk(context: android.content.Context): String {
    val files = java.io.File(context.cacheDir, "updates").listFiles { f -> f.extension == "apk" }.orEmpty()
    val apk = files.maxByOrNull { it.lastModified() } ?: return "No downloaded update found."
    if (!ApkInstaller.canInstall(context)) {
        context.startActivity(ApkInstaller.allowInstallsIntent(context))
        return "Allow Cyclocomp to install updates on the page that just opened, come back, and press Install again."
    }
    ApkInstaller.install(context, apk)
    return "Android will ask you to confirm the update."
}
