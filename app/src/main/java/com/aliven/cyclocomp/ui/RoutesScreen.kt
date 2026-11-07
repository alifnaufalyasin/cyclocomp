package com.aliven.cyclocomp.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliven.cyclocomp.core.Course
import com.aliven.cyclocomp.core.CourseParser
import com.aliven.cyclocomp.core.CourseSteps
import com.aliven.cyclocomp.core.Format
import com.aliven.cyclocomp.core.NavRoute
import com.aliven.cyclocomp.core.RouteNames
import com.aliven.cyclocomp.map.RouteMap
import com.aliven.cyclocomp.nav.NavSession
import com.aliven.cyclocomp.sync.RouteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_ROUTE_BYTES = 8 * 1024 * 1024

private class LoadedCourse(val course: Course, val route: NavRoute)

@Composable
private fun rememberCourse(file: File): LoadedCourse? {
    val loaded by produceState<LoadedCourse?>(null, file) {
        value = withContext(Dispatchers.IO) {
            try {
                file.inputStream().use { CourseParser.parse(it) }?.let { LoadedCourse(it, CourseSteps.toNavRoute(it)) }
            } catch (e: Exception) {
                null
            }
        }
    }
    return loaded
}

/** Saved GPX routes: import a file, look at it on the map, follow it during a ride, or delete it. */
@Composable
fun RoutesScreen(onBack: () -> Unit, onFollowing: () -> Unit) {
    val context = LocalContext.current
    var routes by remember { mutableStateOf(RouteStore.list(context)) }
    var selected by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { readAtMost(it, MAX_ROUTE_BYTES) }
            } catch (e: Exception) {
                null
            }
            if (bytes == null) {
                status = "Could not read that file (it may be larger than 8 MB)."
            } else {
                val name = (displayNameOf(context, uri) ?: "").removeSuffix(".gpx").removeSuffix(".GPX")
                RouteStore.importGpx(context, name, bytes)
                    .onSuccess {
                        routes = RouteStore.list(context)
                        status = "Imported ${RouteNames.displayName(it.name)}. Sync to share it with your other phones."
                    }
                    .onFailure { status = it.message ?: "Could not import that file." }
            }
        }
    }

    val current = selected
    if (current != null && current.exists()) {
        RouteDetail(
            file = current,
            onBack = { selected = null },
            onFollow = { course ->
                NavSession.followCourse(course)
                onFollowing()
            },
            onDelete = { confirmDelete = true },
        )
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete this route?") },
                text = { Text("${RouteNames.displayName(current.name)} will be removed here and, at the next sync, from your other phones.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        RouteStore.delete(context, current)
                        routes = RouteStore.list(context)
                        selected = null
                        status = "Route deleted."
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            )
        }
        return
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Routes", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Planned routes you can follow without a routing server. Import a GPX file made in a route " +
                "planner or exported from another app.",
            fontSize = 14.sp,
        )
        Button(onClick = { picker.launch("*/*") }, modifier = Modifier.fillMaxWidth()) { Text("Import GPX route") }
        status?.let { Text(it, fontSize = 14.sp) }

        if (routes.isEmpty()) {
            Text("No routes yet.")
        } else {
            routes.forEach { file ->
                val loaded = rememberCourse(file)
                Column(Modifier.fillMaxWidth().clickable { selected = file; status = null }) {
                    Text(RouteNames.displayName(file.name), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Text(if (loaded == null) "…" else "${Format.km(loaded.route.distanceM)} km", fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun RouteDetail(file: File, onBack: () -> Unit, onFollow: (Course) -> Unit, onDelete: () -> Unit) {
    val loaded = rememberCourse(file)
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(RouteNames.displayName(file.name), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        if (loaded == null) {
            Text("Loading… (or this file could not be read)")
        } else {
            RouteMap(loaded.route.points, Modifier.fillMaxWidth().height(260.dp))
            Text("Length: ${Format.km(loaded.route.distanceM)} km", fontSize = 16.sp)
            val turns = loaded.route.steps.count { it.type == "turn" }
            Text(
                "$turns turn cues worked out from the shape of the route. Leaving the line is reported, " +
                    "not rerouted.",
                fontSize = 13.sp,
            )
            Button(onClick = { onFollow(loaded.course) }, modifier = Modifier.fillMaxWidth()) { Text("Follow this route") }
        }
        OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Delete") }
    }
}
