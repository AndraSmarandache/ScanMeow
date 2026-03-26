package com.project.scanmeow

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.project.scanmeow.ui.home.MainHomeScreen
import com.project.scanmeow.ui.home.ScanAlignedReviewScreen
import com.project.scanmeow.ui.home.ScanLoadingScreen
import com.project.scanmeow.ui.home.ScanResultScreen
import com.project.scanmeow.ui.theme.ScanMeowTheme
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Emulator → PC host. Physical device: PC LAN IP. */
private const val SCAN_API_BASE = "http://10.0.2.2:8765"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            // OpenCV pipeline can take tens of seconds; default OkHttp timeouts are too short.
            val http = remember {
                OkHttpClient.Builder()
                    .connectTimeout(120, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .writeTimeout(120, TimeUnit.SECONDS)
                    .callTimeout(180, TimeUnit.SECONDS)
                    .build()
            }

            ScanMeowTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    var screen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }
                    var sourceDrawableId by remember { mutableIntStateOf(0) }

                    when (val s = screen) {
                        AppScreen.Home -> MainHomeScreen(
                            modifier = Modifier.padding(innerPadding),
                            onScanDocumentClick = { drawableId ->
                                if (drawableId == 0) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.toast_missing_demo_document),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    return@MainHomeScreen
                                }
                                sourceDrawableId = drawableId
                                screen = AppScreen.LoadingAligned
                                scope.launch {
                                    val result = runCatching {
                                        val jpeg = drawableToJpegBytes(context.resources, drawableId)
                                        http.postScanMultipart(
                                            jpegBytes = jpeg,
                                            binarize = false,
                                            aiEnhance = false,
                                            upright = true,
                                        )
                                    }
                                    result.onSuccess { bytes ->
                                        screen = AppScreen.AlignedReview(bytes)
                                    }.onFailure { e ->
                                        screen = AppScreen.Home
                                        Toast.makeText(
                                            context,
                                            context.getString(
                                                R.string.toast_scan_failed,
                                                e.message ?: "unknown",
                                            ),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            },
                        )

                        AppScreen.LoadingAligned -> ScanLoadingScreen(
                            message = stringResource(R.string.scan_loading_aligned),
                            modifier = Modifier.padding(innerPadding),
                        )

                        AppScreen.LoadingFinal -> ScanLoadingScreen(
                            message = stringResource(R.string.scan_loading_final),
                            modifier = Modifier.padding(innerPadding),
                        )

                        is AppScreen.AlignedReview -> ScanAlignedReviewScreen(
                            alignedJpeg = s.jpeg,
                            onCancel = { screen = AppScreen.Home },
                            onConfirm = {
                                val alignedBytes = s.jpeg
                                screen = AppScreen.LoadingFinal
                                scope.launch {
                                    val result = runCatching {
                                        val jpeg = drawableToJpegBytes(context.resources, sourceDrawableId)
                                        http.postScanMultipart(
                                            jpegBytes = jpeg,
                                            binarize = true,
                                            aiEnhance = true,
                                            upright = true,
                                        )
                                    }
                                    result.onSuccess { bytes ->
                                        screen = AppScreen.ScannedResult(bytes)
                                    }.onFailure { e ->
                                        screen = AppScreen.AlignedReview(alignedBytes)
                                        Toast.makeText(
                                            context,
                                            context.getString(
                                                R.string.toast_finalize_failed,
                                                e.message ?: "unknown",
                                            ),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            },
                            modifier = Modifier.padding(innerPadding),
                        )

                        is AppScreen.ScannedResult -> ScanResultScreen(
                            scannedJpeg = s.jpeg,
                            onDone = { screen = AppScreen.Home },
                            modifier = Modifier.padding(innerPadding),
                            doneLabel = stringResource(R.string.scan_done),
                        )
                    }
                }
            }
        }
    }
}

private sealed interface AppScreen {
    data object Home : AppScreen
    data object LoadingAligned : AppScreen
    data object LoadingFinal : AppScreen
    data class AlignedReview(val jpeg: ByteArray) : AppScreen
    data class ScannedResult(val jpeg: ByteArray) : AppScreen
}

private fun drawableToJpegBytes(
    res: android.content.res.Resources,
    @DrawableRes id: Int,
    quality: Int = 92,
): ByteArray {
    val bmp = BitmapFactory.decodeResource(res, id)
        ?: error("Could not decode drawable $id")
    ByteArrayOutputStream().use { stream ->
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }
}

private suspend fun OkHttpClient.postScanMultipart(
    jpegBytes: ByteArray,
    binarize: Boolean,
    aiEnhance: Boolean,
    upright: Boolean,
): ByteArray = withContext(Dispatchers.IO) {
    val body = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart(
            "file",
            "page.jpg",
            jpegBytes.toRequestBody("image/jpeg".toMediaType()),
        )
        .build()
    val b = if (binarize) "true" else "false"
    val ai = if (aiEnhance) "true" else "false"
    val u = if (upright) "true" else "false"
    val url = "$SCAN_API_BASE/scan?binarize=$b&ai_enhance=$ai&upright=$u"
    val req = Request.Builder().url(url).post(body).build()
    newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) error("HTTP ${resp.code}")
        resp.body?.bytes() ?: error("empty body")
    }
}

@Preview(showBackground = true)
@Composable
fun MainHomeScreenPreview() {
    ScanMeowTheme {
        MainHomeScreen()
    }
}
