package com.aliven.cyclocomp.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliven.cyclocomp.core.Format
import com.aliven.cyclocomp.core.HuaweiWorkout
import com.aliven.cyclocomp.core.HuaweiWorkoutParser
import com.aliven.cyclocomp.sync.StoreException
import com.aliven.cyclocomp.sync.RepoPaths
import com.aliven.cyclocomp.sync.SyncSettings
import com.aliven.cyclocomp.sync.WorkoutJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private const val MAX_IMAGE_BYTES = 25 * 1024 * 1024
private val START_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * Capture-phone flow: pick a Huawei Health screenshot, read it on the phone, let the user fix
 * the main numbers, then upload the confirmed values (and, only if ticked, the image) to the inbox.
 */
@Composable
fun ScreenshotImportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var picked by remember { mutableStateOf<Uri?>(null) }
    var parsed by remember { mutableStateOf<HuaweiWorkout?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    // Editable copies of the numbers that matter most.
    var start by remember { mutableStateOf("") }
    var distance by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var avgHr by remember { mutableStateOf("") }
    var maxHr by remember { mutableStateOf("") }
    var uploadImage by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            picked = uri
            parsed = null
            busy = true
            status = "Reading…"
            recogniseImage(context, uri) { r ->
                busy = false
                if (r.error != null) {
                    status = r.error
                } else {
                    val w = HuaweiWorkoutParser.parse(HuaweiWorkoutParser.fromLines(r.lines))
                    parsed = w
                    start = w.startLocal?.format(START_FORMAT) ?: ""
                    distance = w.distanceKm?.toString() ?: ""
                    duration = w.durationS?.let { Format.duration(it * 1000L) } ?: ""
                    kcal = w.kcal?.toString() ?: ""
                    avgHr = w.avgHr?.toString() ?: ""
                    maxHr = w.maxHr?.toString() ?: ""
                    status = if (w.isCycling) null else "This does not look like a cycling workout (${w.activity ?: "unknown"}). Check before uploading."
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Huawei screenshot", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Pick a Huawei Health workout screenshot. It is read on this phone. Check the numbers, " +
                "then upload them to your data repo inbox. Your name and the map are never read or sent.",
            fontSize = 14.sp,
        )
        Button(onClick = { picker.launch("image/*") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Working…" else "Pick screenshot")
        }

        val w = parsed
        if (w != null) {
            Field("Start (yyyy-MM-dd HH:mm)", start) { start = it }
            Field("Distance km", distance) { distance = it }
            Field("Duration (h:mm:ss)", duration) { duration = it }
            Field("Calories kcal", kcal) { kcal = it }
            Field("Avg heart rate", avgHr) { avgHr = it }
            Field("Max heart rate", maxHr) { maxHr = it }

            Text("Also read (not editable here)", fontWeight = FontWeight.Bold)
            Text(
                "Zones min: " + (if (w.zoneMinutes.isEmpty()) "—" else w.zoneMinutes.entries.joinToString { "${it.key} ${it.value}" }) +
                    "\nHR recovery: ${w.hrRecoveryStart ?: "—"} → ${w.hrRecoveryEnd ?: "—"}" +
                    "\nAerobic stress: ${w.aerobicStress ?: "—"} ${w.stressLevel ?: ""}" +
                    "\nRecovery time: ${w.recoveryHours?.let { "$it h" } ?: "—"}",
                fontSize = 14.sp,
            )
            if (w.warnings.isNotEmpty()) {
                Text("Check these:\n" + w.warnings.joinToString("\n") { "- $it" }, fontSize = 14.sp)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = uploadImage, onCheckedChange = { uploadImage = it })
                Text("Also upload the screenshot itself (it shows your name and a map)", fontSize = 14.sp)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val edited = applyEdits(w, start, distance, duration, kcal, avgHr, maxHr)
                        if (edited.error != null) {
                            status = edited.error
                        } else {
                            val uri = picked
                            scope.launch {
                                busy = true
                                status = "Uploading…"
                                status = withContext(Dispatchers.IO) {
                                    upload(context, edited.workout!!, if (uploadImage) uri else null)
                                }
                                busy = false
                            }
                        }
                    },
                ) { Text("Upload to inbox") }
                OutlinedButton(
                    onClick = { parsed = null; picked = null; status = null },
                    modifier = Modifier.weight(1f),
                ) { Text("Discard") }
            }
        }
        status?.let { Text(it) }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
    )
}

private class Edited(val workout: HuaweiWorkout?, val error: String?)

private fun applyEdits(
    w: HuaweiWorkout, start: String, distance: String, duration: String,
    kcal: String, avgHr: String, maxHr: String,
): Edited {
    val startLocal = if (start.isBlank()) null else try {
        LocalDateTime.parse(start.trim(), START_FORMAT)
    } catch (e: Exception) {
        return Edited(null, "Start must look like 2026-11-03 06:42")
    }
    if (startLocal == null) return Edited(null, "Start date and time are needed to match this workout to a ride.")

    fun num(label: String, text: String): Result<Double?> =
        if (text.isBlank()) Result.success(null)
        else text.trim().replace(',', '.').toDoubleOrNull()?.let { Result.success(it) }
            ?: Result.failure(IllegalArgumentException("$label is not a number"))

    val d = num("Distance", distance).getOrElse { return Edited(null, it.message) }
    val k = num("Calories", kcal).getOrElse { return Edited(null, it.message) }
    val a = num("Avg heart rate", avgHr).getOrElse { return Edited(null, it.message) }
    val m = num("Max heart rate", maxHr).getOrElse { return Edited(null, it.message) }
    val s = if (duration.isBlank()) null else HuaweiWorkoutParser.parseDuration(duration)
        ?: return Edited(null, "Duration must look like 0:59:46")

    return Edited(
        w.copy(
            startLocal = startLocal, distanceKm = d, durationS = s,
            kcal = k?.toInt(), avgHr = a?.toInt(), maxHr = m?.toInt(),
        ),
        null,
    )
}

/** Uploads the confirmed workout JSON (and optionally the image) to `inbox/<phone>/`. Returns a status line. */
private fun upload(context: Context, workout: HuaweiWorkout, imageUri: Uri?): String {
    val cfg = SyncSettings.load(context)
        ?: return "Sync isn't set up yet. Open Sync and enter the repo, phone name and token."
    val repo = SyncSettings.repo(cfg)
    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(ZonedDateTime.now(ZoneId.systemDefault()))
    val base = "inbox/${cfg.device}/$stamp"
    return try {
        repo.putNew(base + WorkoutJson.SUFFIX, WorkoutJson.toJson(workout, cfg.device).toByteArray(Charsets.UTF_8), "Add workout values from ${cfg.device}")
        var message = "Uploaded the workout values."
        if (imageUri != null) {
            val bytes = context.contentResolver.openInputStream(imageUri)?.use { readLimited(it) }
            if (bytes == null) {
                message += " The screenshot was too large or unreadable, so it was not uploaded."
            } else {
                val ext = if (context.contentResolver.getType(imageUri) == "image/png") "png" else "jpg"
                repo.putNew("${base}_screenshot.$ext", bytes, "Add screenshot from ${cfg.device}")
                message += " Screenshot uploaded too."
            }
        }
        message
    } catch (e: StoreException) {
        e.message ?: "Upload failed"
    } catch (e: Exception) {
        e.message ?: "Upload failed"
    }
}

private fun readLimited(input: java.io.InputStream): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > MAX_IMAGE_BYTES) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
