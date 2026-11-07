package com.aliven.cyclocomp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.aliven.cyclocomp.sync.PasswordRules
import com.aliven.cyclocomp.sync.PbSession
import com.aliven.cyclocomp.sync.PocketBaseAuth
import com.aliven.cyclocomp.sync.ServerAddress
import com.aliven.cyclocomp.sync.SyncSettings
import com.aliven.cyclocomp.sync.UrlConnectionHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sign in to (or out of) the account on the Cyclocomp server, and delete the account with all its data. */
@Composable
fun AccountScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(AccountSettings.load(context)) }
    var server by remember { mutableStateOf(AccountSettings.serverUrl(context)) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    val serverBad = server.isNotBlank() && ServerAddress.normalize(server) == null

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Account", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        val current = session
        if (current != null) {
            Text("Signed in as ${current.email}", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text("Server: ${current.baseUrl}", fontSize = 13.sp)
            Text(
                "Your rides and workouts are stored in this account. Sign in on another phone with the same " +
                    "email and password to see the same data there.",
                fontSize = 14.sp,
            )
            OutlinedButton(
                enabled = !busy, modifier = Modifier.fillMaxWidth(),
                onClick = {
                    AccountSettings.clear(context)
                    session = null
                    status = "Signed out on this phone. Your data stays in the account."
                },
            ) { Text("Sign out") }

            Text("Change password", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                "Other phones signed in to this account will ask you to sign in again with the new password.",
                fontSize = 13.sp,
            )
            OutlinedTextField(
                value = oldPassword, onValueChange = { oldPassword = it }, singleLine = true,
                label = { Text("Current password") }, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = newPassword, onValueChange = { newPassword = it }, singleLine = true,
                label = { Text("New password (at least ${PasswordRules.MIN} characters)") },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = confirmPassword, onValueChange = { confirmPassword = it }, singleLine = true,
                label = { Text("New password again") }, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                enabled = !busy, modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val problem = PasswordRules.check(oldPassword, newPassword, confirmPassword)
                    if (problem != null) {
                        status = problem
                    } else {
                        scope.launch {
                            busy = true
                            status = "Changing password…"
                            val result = withContext(Dispatchers.IO) {
                                try {
                                    Result.success(
                                        PocketBaseAuth(current.baseUrl, UrlConnectionHttp())
                                            .changePassword(current, oldPassword, newPassword)
                                    )
                                } catch (e: Exception) {
                                    Result.failure<PbSession>(e)
                                }
                            }
                            result.onSuccess {
                                AccountSettings.save(context, it)
                                session = it
                                oldPassword = ""
                                newPassword = ""
                                confirmPassword = ""
                                status = "Password changed."
                            }.onFailure {
                                status = it.message ?: "Could not change the password"
                            }
                            busy = false
                        }
                    }
                },
            ) { Text("Change password") }

            Text("Delete my account", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                "Removes the account and every ride, workout and setting stored for it on the server. " +
                    "Data already on this phone is kept. This cannot be undone.",
                fontSize = 14.sp,
            )
            OutlinedButton(
                enabled = !busy, modifier = Modifier.fillMaxWidth(),
                onClick = { confirmDelete = true },
            ) { Text("Delete account and server data") }
        } else {
            Text(
                "Sign in with the email and password you were given. Accounts are created by the server owner.",
                fontSize = 14.sp,
            )
            OutlinedTextField(
                value = server, onValueChange = { server = it }, singleLine = true,
                label = { Text("Server address (https://…)") }, modifier = Modifier.fillMaxWidth(),
                isError = serverBad,
                supportingText = { if (serverBad) Text("Needs an https:// address") },
            )
            OutlinedTextField(
                value = email, onValueChange = { email = it }, singleLine = true,
                label = { Text("Email") }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it }, singleLine = true,
                label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = !busy && !serverBad && server.isNotBlank() && email.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val base = ServerAddress.normalize(server)
                    if (base == null) {
                        status = "Enter the server address, starting with https://"
                    } else {
                        scope.launch {
                            busy = true
                            status = "Signing in…"
                            val result = withContext(Dispatchers.IO) {
                                try {
                                    Result.success(PocketBaseAuth(base, UrlConnectionHttp()).login(email, password))
                                } catch (e: Exception) {
                                    Result.failure<PbSession>(e)
                                }
                            }
                            result.onSuccess {
                                AccountSettings.saveServerUrl(context, base)
                                AccountSettings.save(context, it)
                                SyncSettings.setBackend(context, Backend.ACCOUNT)
                                session = it
                                password = ""
                                status = "Signed in. Open Sync and tap Sync now."
                            }.onFailure {
                                status = it.message ?: "Could not sign in"
                            }
                            busy = false
                        }
                    }
                },
            ) { Text("Sign in") }
        }

        status?.let { Text(it) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete your account?") },
            text = {
                Text(
                    "Everything stored for ${session?.email ?: "this account"} on the server will be erased, " +
                        "and other phones signed in to it will lose access. Rides already on this phone stay. " +
                        "This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val s = session
                    if (s != null) {
                        scope.launch {
                            busy = true
                            status = "Deleting…"
                            val error = withContext(Dispatchers.IO) {
                                try {
                                    PocketBaseAuth(s.baseUrl, UrlConnectionHttp()).deleteAccount(s)
                                    null
                                } catch (e: Exception) {
                                    e.message ?: "Could not delete the account"
                                }
                            }
                            if (error == null) {
                                AccountSettings.clear(context)
                                session = null
                                status = "Account deleted."
                            } else {
                                status = error
                            }
                            busy = false
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
