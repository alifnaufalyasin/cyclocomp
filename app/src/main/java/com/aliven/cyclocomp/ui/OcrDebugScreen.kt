package com.aliven.cyclocomp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliven.cyclocomp.core.HuaweiWorkoutParser
import com.aliven.cyclocomp.core.OcrLayout
import com.aliven.cyclocomp.core.OcrLine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Debug tool for M5b: shows what on-device text recognition makes of a screenshot, so the
 * Huawei field matching can be written against real output. Nothing leaves the phone.
 */
@Composable
fun OcrDebugScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var output by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            busy = true
            output = null
            recognise(context, uri) { result ->
                output = result
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Read screenshot (debug)", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Pick a Huawei Health screenshot. The text is read on this phone only. " +
                "The result below can be copied or shared so the field matching can be built from it. " +
                "Check it for personal details (name, place names) before you send it.",
            fontSize = 14.sp,
        )
        Button(onClick = { picker.launch("image/*") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Reading…" else "Pick screenshot")
        }
        output?.let { text ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copy(context, text) }, modifier = Modifier.weight(1f)) { Text("Copy") }
                OutlinedButton(onClick = { shareText(context, text) }, modifier = Modifier.weight(1f)) { Text("Share") }
            }
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

private fun recognise(context: Context, uri: Uri, done: (String) -> Unit) {
    val image = try {
        InputImage.fromFilePath(context, uri)
    } catch (e: Exception) {
        done("Could not open the image: ${e.message}")
        return
    }
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    recognizer.process(image)
        .addOnSuccessListener { result ->
            val lines = result.textBlocks.flatMap { block ->
                block.lines.mapNotNull { line ->
                    line.boundingBox?.let { OcrLine(line.text, it.left, it.top, it.right, it.bottom) }
                }
            }
            val parsed = try {
                HuaweiWorkoutParser.parse(HuaweiWorkoutParser.fromLines(lines)).describe()
            } catch (e: Exception) {
                "Could not interpret this screenshot: ${e.message}"
            }
            done(
                "Image ${image.width}x${image.height}, ${lines.size} lines\n\n" +
                    "== Read as Huawei workout ==\n" + parsed + "\n\n" +
                    "== By row (y = pixel position, | separates cells) ==\n" +
                    OcrLayout.formatRows(lines) +
                    "\n\n== Raw text, recognizer order ==\n" + result.text
            )
        }
        .addOnFailureListener { e -> done("Text recognition failed: ${e.message}") }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("ocr", text))
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Share recognised text"))
}
