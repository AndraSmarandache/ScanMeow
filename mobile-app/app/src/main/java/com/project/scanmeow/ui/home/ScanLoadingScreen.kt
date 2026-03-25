package com.project.scanmeow.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.project.scanmeow.ui.theme.ScanBlue
import com.project.scanmeow.ui.theme.ScanMeowTheme

/**
 * Infinite “laser line” over a paper outline — reads as “document scanning”.
 * Uses [rememberInfiniteTransition] + [animateFloat]; drawing is in [Canvas] (no extra deps).
 */
@Composable
private fun DocumentScanAnimation(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "docScan")
    val scanT by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "scanT",
    )

    Canvas(modifier = modifier.size(width = 152.dp, height = 196.dp)) {
        val w = size.width
        val h = size.height
        val pad = 6.dp.toPx()
        val innerPad = 8.dp.toPx()
        val corner = CornerRadius(10.dp.toPx(), 10.dp.toPx())
        val page = Size(w - 2 * pad, h - 2 * pad)

        drawRoundRect(
            color = Color(0xFFE8EAE8),
            topLeft = Offset(pad, pad),
            size = page,
            cornerRadius = corner,
        )
        drawRoundRect(
            color = Color(0xFF8A8A8A),
            topLeft = Offset(pad, pad),
            size = page,
            cornerRadius = corner,
            style = Stroke(width = 1.5.dp.toPx()),
        )

        val lineLeft = pad + innerPad
        val lineRight = w - pad - innerPad
        val y0 = pad + innerPad
        val y1 = h - pad - innerPad
        val y = y0 + (y1 - y0) * scanT

        drawLine(
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.42f to ScanBlue.copy(alpha = 0.25f),
                    0.5f to ScanBlue.copy(alpha = 0.95f),
                    0.58f to ScanBlue.copy(alpha = 0.25f),
                    1f to Color.Transparent,
                ),
                startX = lineLeft,
                endX = lineRight,
            ),
            start = Offset(lineLeft, y),
            end = Offset(lineRight, y),
            strokeWidth = 4.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun ScanLoadingScreen(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DocumentScanAnimation()
            Spacer(Modifier.height(28.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ScanLoadingScreenPreview() {
    ScanMeowTheme {
        ScanLoadingScreen(message = "Scanning document…")
    }
}
