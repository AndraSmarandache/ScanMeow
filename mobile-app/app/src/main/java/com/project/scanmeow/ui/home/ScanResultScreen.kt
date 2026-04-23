package com.project.scanmeow.ui.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.project.scanmeow.ui.theme.ScanBlue

@Composable
fun ScanResultScreen(
    scannedJpeg: ByteArray,
    allPages: List<ByteArray> = listOf(scannedJpeg),
    onDiscard: () -> Unit,
    onAddPage: () -> Unit,
    onSave: () -> Unit,
    onDownloadPage: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    val safePages = if (allPages.isEmpty()) listOf(scannedJpeg) else allPages
    var selectedIdx by remember(safePages.size) { mutableIntStateOf(safePages.lastIndex) }

    val displayBitmap = remember(selectedIdx, safePages) {
        val bytes = safePages.getOrNull(selectedIdx) ?: scannedJpeg
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    val stripState = rememberLazyListState()
    LaunchedEffect(safePages.size) {
        if (safePages.size > 1) stripState.scrollToItem(safePages.lastIndex)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (safePages.size > 1) {
                Text(
                    text = "${safePages.size} pages",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onDownloadPage(safePages[selectedIdx]) }) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = "Download page",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // Page thumbnail strip (only when multiple pages)
        if (safePages.size > 1) {
            LazyRow(
                state = stripState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(86.dp)
                    .background(Color(0xFF111111))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(safePages) { idx, pageBytes ->
                    val thumb = remember(pageBytes) {
                        BitmapFactory.decodeByteArray(pageBytes, 0, pageBytes.size)
                    }
                    Box(
                        modifier = Modifier
                            .width(60.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .border(
                                width = if (idx == selectedIdx) 2.dp else 1.dp,
                                color = if (idx == selectedIdx) ScanBlue else Color(0xFF555555),
                                shape = RoundedCornerShape(4.dp),
                            )
                            .clickable { selectedIdx = idx },
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        if (thumb != null) {
                            Image(
                                bitmap = thumb.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        Text(
                            text = "${idx + 1}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.55f))
                                .padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }

        // Main page preview
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (displayBitmap != null) {
                Image(
                    bitmap = displayBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text("Could not decode image.")
            }
        }

        // Action buttons
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onDiscard,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3D3D3D),
                        contentColor = Color.White,
                    ),
                    shape = MaterialTheme.shapes.medium,
                ) { Text("Discard") }

                Button(
                    onClick = onAddPage,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1A1A2E),
                        contentColor = Color.White,
                    ),
                    shape = MaterialTheme.shapes.medium,
                ) { Text("Add Page") }
            }

            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ScanBlue,
                    contentColor = Color.White,
                ),
                shape = MaterialTheme.shapes.medium,
            ) {
                val label = if (safePages.size == 1) "Save PDF" else "Save PDF (${safePages.size} pages)"
                Text(label)
            }
        }
    }
}
