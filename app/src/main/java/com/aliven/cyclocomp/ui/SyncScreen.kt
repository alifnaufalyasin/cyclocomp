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
import com.aliven.cyclocomp.recording.RideRecorder
import com.aliven.cyclocomp.sync.InboxImporter
import com.aliven.cyclocomp.sync.RepoPaths
import com.aliven.cyclocomp.sync.RideSync
import com.aliven.cyclocomp.sync.SyncSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SyncScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var slug by remember { mutableStateOf(SyncSettings.slug(context)) }
    var device by remember { mutableStateOf(SyncSettings.device(context)) }
    var token by remember { mutableStateOf("") }
    var hasToken by remember { mutableStateOf(SyncSettings.hasToken(context)) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val save = {
        SyncSettings.save(context, slug, device, token)
        token = ""
        hasToken = SyncSettings.hasToken(context)
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Cloud sync", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Rides and shared files go to your private data repo on GitHub. Use a fine-grained token " +
                "limited to that one repo with Contents: read and write. The token is stored encrypted on this phone.",
            fontSize = 14.sp,
        )
        OutlinedTextField(
            value = slug, onValueChange = { slug = it }, singleLine = true,
            label = { Text("Data repo (owner/name)") }, modifier = Modifier.fillMaxWidth(),
            isError = slug.isNotBlank() && !RepoPaths.isValidSlug(slug.trim()),
        )
        OutlinedTextField(
            value = device, onValueChange = { device = it }, singleLine = true,
            label = { Text("This phone's name (e.g. ride-phone)") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token, onValueChange = { token = it }, singleLine = true,
            label = { Text(if (hasToken) "Token (saved — type to replace)" else "GitHub token") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { save(); status = "Saved." }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text("Save")
            }
            OutlinedButton(
                onClick = {
                    save()
                    scope.launch {
                        busy = true
                        status = "Testing…"
                        status = withContext(Dispatchers.IO) {
                            val cfg = SyncSettings.load(context)
                            if (cfg == null) "Fill in repo, phone name and token first."
                            else try {
                                SyncSettings.repo(cfg).checkAccess()
                                "Connected to ${cfg.slug}."
                            } catch (e: Exception) {
                                e.message ?: "Connection failed"
                            }
                        }
                        busy = false
                    }
                },
                enabled = !busy, modifier = Modifier.weight(1f),
            ) { Text("Test") }
        }

        Button(
            onClick = {
                save()
                scope.launch {
                    busy = true
                    status = "Uploading rides…"
                    status = withContext(Dispatchers.IO) {
                        val cfg = SyncSettings.load(context)
                        if (cfg == null) "Fill in repo, phone name and token first."
                        else RideSync.describe(
                            RideSync.uploadRides(context, SyncSettings.repo(cfg), RideRecorder.rides())
                        )
                    }
                    busy = false
                }
            },
            enabled = !busy, modifier = Modifier.fillMaxWidth(),
        ) { Text("Upload rides now") }

        OutlinedButton(
            onClick = {
                save()
                scope.launch {
                    busy = true
                    status = "Checking inbox…"
                    status = withContext(Dispatchers.IO) {
                        val cfg = SyncSettings.load(context)
                        if (cfg == null) "Fill in repo, phone name and token first."
                        else try {
                            InboxImporter.run(context, SyncSettings.repo(cfg)).joinToString("\n")
                        } catch (e: Exception) {
                            e.message ?: "Import failed"
                        }
                    }
                    busy = false
                }
            },
            enabled = !busy, modifier = Modifier.fillMaxWidth(),
        ) { Text("Import watch GPX from inbox") }

        if (hasToken) {
            TextButton(onClick = {
                SyncSettings.clearToken(context)
                hasToken = false
                status = "Token removed from this phone."
            }, enabled = !busy) { Text("Remove saved token") }
        }

        status?.let { Text(it) }

        Text(
            "To send Huawei Health screenshots or a GPX from another phone: open the file, tap Share, " +
                "choose Cyclocomp. It lands in inbox/<phone name>/ in the repo.",
            fontSize = 14.sp,
        )
    }
}
