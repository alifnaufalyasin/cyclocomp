package com.aliven.cyclocomp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Simple bar chart; the last bar (the week in progress) is drawn lighter. */
@Composable
fun BarChart(title: String, unit: String, values: List<Float>, modifier: Modifier = Modifier) {
    if (values.isEmpty()) return
    val top = values.max()
    val color = MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxWidth()) {
        Text(String.format(Locale.US, "%s  (max %.1f %s)", title, top, unit))
        Canvas(Modifier.fillMaxWidth().height(100.dp)) {
            val span = if (top > 0f) top else 1f
            val slot = size.width / values.size
            values.forEachIndexed { i, v ->
                val h = size.height * v / span
                drawRect(
                    color = if (i == values.lastIndex) color.copy(alpha = 0.5f) else color,
                    topLeft = androidx.compose.ui.geometry.Offset(i * slot + slot * 0.15f, size.height - h),
                    size = androidx.compose.ui.geometry.Size(slot * 0.7f, h),
                )
            }
        }
    }
}

@Composable
fun LineChart(title: String, unit: String, values: List<Float>, modifier: Modifier = Modifier) {
    if (values.size < 2) return
    val lo = values.min()
    val hi = values.max()
    val color = MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxWidth()) {
        Text(String.format(Locale.US, "%s  %.0f – %.0f %s", title, lo, hi, unit))
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val span = if (hi - lo > 0f) hi - lo else 1f
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = size.width * i / (values.size - 1)
                val y = size.height * (1f - (v - lo) / span)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 3f))
        }
    }
}
