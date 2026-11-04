package com.aliven.cyclocomp.ui

import android.content.Context
import android.net.Uri
import com.aliven.cyclocomp.core.OcrLine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OcrResult(
    val lines: List<OcrLine>,
    val rawText: String,
    val width: Int,
    val height: Int,
    val error: String? = null,
)

/** Runs on-device text recognition on an image; [done] is called on the main thread. */
fun recogniseImage(context: Context, uri: Uri, done: (OcrResult) -> Unit) {
    val image = try {
        InputImage.fromFilePath(context, uri)
    } catch (e: Exception) {
        done(OcrResult(emptyList(), "", 0, 0, "Could not open the image: ${e.message}"))
        return
    }
    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
        .addOnSuccessListener { result ->
            val lines = result.textBlocks.flatMap { block ->
                block.lines.mapNotNull { line ->
                    line.boundingBox?.let { OcrLine(line.text, it.left, it.top, it.right, it.bottom) }
                }
            }
            done(OcrResult(lines, result.text, image.width, image.height))
        }
        .addOnFailureListener { e ->
            done(OcrResult(emptyList(), "", image.width, image.height, "Text recognition failed: ${e.message}"))
        }
}
