package com.project.scanmeow.ui.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.project.scanmeow.ui.theme.ScanBlue
import kotlin.math.sqrt

private val OverlayBlue = Color(0xFF2196F3)
private val HandleFill = Color.White
private val OverlayDim = Color(0x662196F3)

/**
 * CamScanner-style crop adjustment screen.
 * corners: TL, TR, BR, BL in image pixel coordinates.
 */
@Composable
fun CropAdjustScreen(
    jpegBytes: ByteArray,
    corners: List<Offset>,        // TL, TR, BR, BL in image pixel coords
    imageWidth: Int,
    imageHeight: Int,
    onRetake: () -> Unit,
    onConfirm: (List<Offset>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(jpegBytes) {
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    // Mutable corners in image pixel space
    var pts by remember(corners) { mutableStateOf(corners) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A)),
    ) {
        Column(Modifier.fillMaxSize()) {

            // ── Image + handles area ─────────────────────────────────────────
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(12.dp),
            ) {
                if (bitmap == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Could not load image", color = Color.White)
                    }
                } else {
                    val availW = constraints.maxWidth.toFloat()
                    val availH = constraints.maxHeight.toFloat()
                    val bmpW   = bitmap.width.toFloat()
                    val bmpH   = bitmap.height.toFloat()
                    val scale  = minOf(availW / bmpW, availH / bmpH)
                    val dispW  = bmpW * scale
                    val dispH  = bmpH * scale
                    val offX   = (availW - dispW) / 2f
                    val offY   = (availH - dispH) / 2f

                    fun imgToScreen(p: Offset) = Offset(p.x * scale + offX, p.y * scale + offY)
                    fun screenToImg(p: Offset) = Offset(
                        ((p.x - offX) / scale).coerceIn(0f, bmpW),
                        ((p.y - offY) / scale).coerceIn(0f, bmpH),
                    )

                    val handleRadiusDp = 14.dp
                    val touchRadiusDp  = 32.dp

                    Box(Modifier.fillMaxSize()) {
                        // Image
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )

                        // Overlay: dim outside quad + blue border + handles
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    val handleRadiusPx = touchRadiusDp.toPx()
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val pos  = down.position
                                        // Find closest handle
                                        val idx = pts
                                            .map { imgToScreen(it) }
                                            .indexOfFirst { dist(it, pos) < handleRadiusPx }
                                        if (idx >= 0) {
                                            drag(down.id) { change ->
                                                change.consume()
                                                pts = pts.toMutableList().also { list ->
                                                    list[idx] = screenToImg(change.position)
                                                }
                                            }
                                        }
                                    }
                                }
                        ) {
                            val sc = pts.map { imgToScreen(it) }
                            val (tl, tr, br, bl) = sc

                            // Dim the area outside the quad using EvenOdd fill —
                            // the outer rect and inner quad together leave the quad transparent
                            val dimPath = Path().apply {
                                fillType = PathFillType.EvenOdd
                                moveTo(0f, 0f); lineTo(size.width, 0f)
                                lineTo(size.width, size.height); lineTo(0f, size.height); close()
                                moveTo(tl.x, tl.y); lineTo(tr.x, tr.y)
                                lineTo(br.x, br.y); lineTo(bl.x, bl.y); close()
                            }
                            drawPath(dimPath, OverlayDim)

                            val quadPath = Path().apply {
                                moveTo(tl.x, tl.y); lineTo(tr.x, tr.y)
                                lineTo(br.x, br.y); lineTo(bl.x, bl.y); close()
                            }
                            // Blue quad border
                            drawPath(quadPath, OverlayBlue, style = Stroke(width = 2.dp.toPx()))

                            // Corner handles
                            val r = handleRadiusDp.toPx()
                            sc.forEach { pt ->
                                drawCircle(OverlayBlue, radius = r, center = pt)
                                drawCircle(HandleFill,  radius = r * 0.55f, center = pt)
                            }
                        }
                    }
                }
            }

            // ── Buttons ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A1A))
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onRetake,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3D3D3D), contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("Retake", fontSize = 16.sp) }

                Button(
                    onClick = { onConfirm(pts) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = ScanBlue, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("Confirm", fontSize = 16.sp) }
            }
        }
    }
}

private fun dist(a: Offset, b: Offset): Float {
    val dx = a.x - b.x; val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}
