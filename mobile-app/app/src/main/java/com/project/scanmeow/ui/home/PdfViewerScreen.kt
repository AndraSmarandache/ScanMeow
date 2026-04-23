package com.project.scanmeow.ui.home

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.project.scanmeow.R
import java.io.File
import kotlin.math.max

private const val PDF_RENDER_CACHE_MAX = 3

private val pdfRenderCache =
    object : LinkedHashMap<String, List<Bitmap>>(PDF_RENDER_CACHE_MAX + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Bitmap>>?): Boolean =
            size > PDF_RENDER_CACHE_MAX
    }

private fun pdfRenderCacheKey(file: File): String =
    "${file.absolutePath}::${file.lastModified()}"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PdfViewerScreen(
    pdfFile: File,
    title: String,
    onTitleLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val cacheKey = remember(pdfFile.absolutePath, pdfFile.lastModified()) {
        pdfRenderCacheKey(pdfFile)
    }
    var pageBitmaps by remember(cacheKey) { mutableStateOf<List<Bitmap>>(emptyList()) }
    var loadErr by remember(cacheKey) { mutableStateOf(false) }

    LaunchedEffect(cacheKey) {
        loadErr = false
        val cached = synchronized(pdfRenderCache) { pdfRenderCache[cacheKey] }
        if (cached != null) {
            pageBitmaps = cached
            return@LaunchedEffect
        }
        pageBitmaps = runCatching {
            renderPdfToBitmaps(pdfFile).also { list ->
                if (list.isNotEmpty()) {
                    synchronized(pdfRenderCache) {
                        pdfRenderCache[cacheKey] = list
                    }
                }
            }
        }.getOrElse {
            loadErr = true
            emptyList()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .then(
                    if (onTitleLongPress != null) {
                        Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = onTitleLongPress,
                        )
                    } else {
                        Modifier
                    },
                ),
        )
        when {
            loadErr -> Text(
                text = stringResource(R.string.pdf_viewer_error),
                modifier = Modifier.padding(16.dp),
            )
            pageBitmaps.isEmpty() -> Text(
                text = stringResource(R.string.pdf_loading),
                modifier = Modifier.padding(16.dp),
            )
            else -> {
                val scroll = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll),
                ) {
                    pageBitmaps.forEach { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.FillWidth,
                        )
                    }
                }
            }
        }
    }
}

internal fun renderPdfToBitmaps(file: File): List<Bitmap> {
    val out = ArrayList<Bitmap>()
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        PdfRenderer(pfd).use { renderer ->
            val maxSide = 2048
            for (i in 0 until renderer.pageCount) {
                renderer.openPage(i).use { page ->
                    val baseW = page.width
                    val baseH = page.height
                    val scale = if (max(baseW, baseH) > maxSide) {
                        maxSide.toFloat() / max(baseW, baseH)
                    } else {
                        1f
                    }
                    val w = (baseW * scale).toInt().coerceAtLeast(1)
                    val h = (baseH * scale).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    val matrix = Matrix().apply { setScale(scale, scale) }
                    page.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    out.add(bmp)
                }
            }
        }
    }
    return out
}
