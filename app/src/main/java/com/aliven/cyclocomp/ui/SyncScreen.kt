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
import com.aliven.cyclocomp.sync.AccountSettings
import com.aliven.cyclocomp.sync.Backend
import com.aliven.cyclocomp.sync.FullSync
import com.aliven.cyclocomp.sync.RepoPaths
import com.aliven.cyclocomp.sync.SyncSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SyncScreen(
    onBack: () -> Unit,
    onOpenOcrDebug: () -> Unit,
    onOpenScreenshot: () -> Unit,
    onOpenUpdate: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var backend by remember { mutableStateOf(SyncSettings.backend(context)) }
    var slug by remember { mutableStateOf(SyncSettings.slug(context)) }
    var device by remember { mutableStateOf(SyncSettings.device(context)) }
    var token by remember { mutableStateOf("") }
    var hasToken by remember { mutableStateOf(SyncSettings.hasToken(context)) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val accountEmail = remember { AccountSettings.load(context)?.email }

    val save = {
        if (backend == Backend.GITHUB) {
            SyncSettings.save(context, slug, device, token)
            token = ""
            hasToken = SyncSettings.hasToken(context)
        } else {
            SyncSettings.saveDevice(context, device)
        }
    }
    val choose = { b: Backend ->
        backend = b
        SyncSettings.setBackend(context, b)
        status = null
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Cloud sync", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val modifier = Modifier.weight(1f)
            if (backend == Backend.ACCOUNT) {
                Button(onClick = { choose(Backend.ACCOUNT) }, modifier = modifier) { Text("Account") }
                OutlinedButton(onClick = { choose(Backend.GITHUB) }, modifier = modifier) { Text("GitHub repo") }
            } else {
                OutlinedButton(onClick = { choose(Backend.ACCOUNT) }, modifier = modifier) { Text("Account") }
                Button(onClick = { choose(Backend.GITHUB) }, modifier = modifier) { Text("GitHub repo") }
            }
        }

        if (backend == Backend.GITHUB) {
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
        } else {
            Text(
                "Your data is kept in your own account on the Cyclocomp server. Only you can see it.",
                fontSize = 14.sp,
            )
            Text(
                if (accountEmail != null) "Signed in as $accountEmail" else "Not signed in.",
                fontWeight = FontWeight.SemiBold,
            )
            Button(onClick = onOpenAccount, modifier = Modifier.fillMaxWidth()) {
                Text(if (accountEmail != null) "Manage account" else "Sign in")
            }
        }
        OutlinedTextField(
            value = device, onValueChange = { device = it }, singleLine = true,
            label = { Text("This phone's name (e.g. ride-phone)") }, modifier = Modifier.fillMaxWidth(),
        )
        if (backend == Backend.GITHUB) {
            OutlinedTextField(
                value = token, onValueChange = { token = it }, singleLine = true,
                label = { Text(if (hasToken) "Token (saved — type to replace)" else "GitHub token") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

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
                            if (cfg == null) SyncSettings.missingMessage(context)
                            else try {
                                SyncSettings.repo(cfg).checkAccess()
                                "Connected to ${cfg.label}."
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
                    status = "Syncing…"
                    status = withContext(Dispatchers.IO) {
                        val cfg = SyncSettings.load(context)
                        if (cfg == null) SyncSettings.missingMessage(context)
                        else FullSync.run(context, cfg).joinToString("\n")
                    }
                    busy = false
                }
            },
            enabled = !busy, modifier = Modifier.fillMaxWidth(),
        ) { Text("Sync now (download · import · upload)") }
        Text(
            "Works the same on every phone: it downloads rides and workouts from the repo, imports watch " +
                "GPX and screenshot values waiting in the inbox, and uploads anything the repo is missing.",
            fontSize = 12.sp,
        )

        if (backend == Backend.GITHUB && hasToken) {
            TextButton(onClick = {
                SyncSettings.clearToken(context)
                hasToken = false
                status = "Token removed from this phone."
            }, enabled = !busy) { Text("Remove saved token") }
        }

        status?.let { Text(it) }

        Button(onClick = onOpenScreenshot, modifier = Modifier.fillMaxWidth()) {
            Text("Import Huawei screenshot (this phone → inbox)")
        }

        OutlinedButton(onClick = onOpenUpdate, modifier = Modifier.fillMaxWidth()) {
            Text("App update")
        }

        OutlinedButton(onClick = onOpenOcrDebug, modifier = Modifier.fillMaxWidth()) {
            Text("Read screenshot (debug)")
        }

        Text(
            "To send Huawei Health screenshots or a GPX from another phone: open the file, tap Share, " +
                "choose Cyclocomp. It lands in inbox/<phone name>/ in the repo.",
            fontSize = 14.sp,
        )
    }
}
