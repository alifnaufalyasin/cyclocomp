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
