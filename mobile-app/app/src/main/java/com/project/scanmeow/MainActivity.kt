package com.project.scanmeow

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.app.Activity
import android.os.Bundle
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.draw.clip
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import coil3.compose.AsyncImage
import com.project.scanmeow.ui.home.MainHomeScreen
import com.project.scanmeow.ui.home.ScanAlignedReviewScreen
import com.project.scanmeow.ui.home.ScanLoadingScreen
import com.project.scanmeow.ui.home.ScanResultScreen
import com.project.scanmeow.ui.theme.ScanMeowTheme
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.max
import kotlin.math.min

/** Emulator → PC host. Physical device: PC LAN IP. */
private const val SCAN_API_BASE = "http://10.0.2.2:8765"
private const val DESKTOP_TCP_HOST = "10.0.2.2" // emulator -> host
private const val DESKTOP_TCP_PORT = 5566

private const val PDF_MAX_DIM_PX = 1400
private const val PDF_JPEG_QUALITY = 75

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            // OpenCV pipeline can take tens of seconds; default OkHttp timeouts are too short
            val http = remember {
                OkHttpClient.Builder()
                    .connectTimeout(120, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .writeTimeout(120, TimeUnit.SECONDS)
                    .callTimeout(180, TimeUnit.SECONDS)
                    .build()
            }

            // ── Firebase Google Sign-In ────────────────────────────────────────
            val firebaseAuth = remember { FirebaseAuth.getInstance() }
            var firebaseUid by remember { mutableStateOf(firebaseAuth.currentUser?.uid) }
            var firebaseIdToken by remember { mutableStateOf<String?>(null) }
            var firebaseEmail by remember { mutableStateOf(firebaseAuth.currentUser?.email) }
            var firebaseDisplayName by remember { mutableStateOf(firebaseAuth.currentUser?.displayName) }
            var firebasePhotoUrl by remember { mutableStateOf(firebaseAuth.currentUser?.photoUrl?.toString()) }
            var avatarLoadFailed by remember { mutableStateOf(false) }

            val defaultWebClientId = remember {
                val resId = context.resources.getIdentifier(
                    "default_web_client_id",
                    "string",
                    context.packageName,
                )
                if (resId != 0) context.getString(resId) else ""
            }

            val signInClient = remember(defaultWebClientId) {
                if (defaultWebClientId.isBlank()) {
                    null
                } else {
                    val options = GoogleSignInOptions.Builder()
                        .requestIdToken(defaultWebClientId)
                        .requestEmail()
                        .build()
                    GoogleSignIn.getClient(context, options)
                }
            }

            LaunchedEffect(firebaseUid) {
                if (firebaseIdToken == null && firebaseAuth.currentUser != null) {
                    firebaseAuth.currentUser
                        ?.getIdToken(true)
                        ?.addOnSuccessListener { result -> firebaseIdToken = result.token }
                }
            }

            val signInLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult(),
            ) { activityResult ->
                if (activityResult.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
                val data = activityResult.data ?: return@rememberLauncherForActivityResult
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                try {
                    val account = task.getResult(ApiException::class.java)
                    val googleIdToken = account.idToken
                    if (googleIdToken.isNullOrBlank()) {
                        Toast.makeText(context, "Missing Google idToken.", Toast.LENGTH_LONG).show()
                        return@rememberLauncherForActivityResult
                    }
                    val credential = GoogleAuthProvider.getCredential(googleIdToken, null)
                    firebaseAuth.signInWithCredential(credential)
                        .addOnSuccessListener { authResult ->
                            firebaseUid = authResult.user?.uid
                            firebaseEmail = authResult.user?.email
                            firebaseDisplayName = authResult.user?.displayName
                            firebasePhotoUrl = authResult.user?.photoUrl?.toString()
                            avatarLoadFailed = false
                            authResult.user
                                ?.getIdToken(true)
                                ?.addOnSuccessListener { result -> firebaseIdToken = result.token }
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(context, "Firebase sign-in failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                } catch (e: ApiException) {
                    Toast.makeText(context, "Google sign-in failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            ScanMeowTheme {
                if (firebaseIdToken == null || firebaseUid == null) {
                    // Sign-in gate shown only when no valid Firebase session
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp),
                        ) {
                            Text(text = "Sign in with Google", style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.padding(top = 0.dp))
                            Button(
                                onClick = {
                                    if (signInClient == null) {
                                        Toast.makeText(
                                            context,
                                            "Missing google-services.json (default_web_client_id not found).",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                        return@Button
                                    }
                                    val intent = signInClient.signInIntent
                                    signInLauncher.launch(intent)
                                },
                                modifier = Modifier.padding(top = 16.dp),
                            ) {
                                Text("Sign in")
                            }
                        }
                    }
                } else {
                    var screen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }
                    var sourceDrawableId by remember { mutableIntStateOf(0) }
                    var accountMenuExpanded by remember { mutableStateOf(false) }

                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        topBar = {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp)
                                    .padding(top = 20.dp, bottom = 0.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (screen is AppScreen.AlignedReview) {
                                    IconButton(onClick = { screen = AppScreen.Home }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = stringResource(R.string.content_desc_back),
                                        )
                                    }
                                } else {
                                    Spacer(Modifier.size(40.dp))
                                }

                                Box {
                                    IconButton(
                                        onClick = { accountMenuExpanded = true },
                                        modifier = Modifier.padding(top = 8.dp),
                                    ) {
                                        val showFallback = firebasePhotoUrl.isNullOrBlank() || avatarLoadFailed
                                        if (showFallback) {
                                            val initial = (firebaseDisplayName ?: firebaseEmail ?: "")
                                                .trim()
                                                .firstOrNull()
                                                ?.uppercaseChar()
                                                ?.toString()
                                                ?: "A"
                                            Surface(
                                                modifier = Modifier.size(30.dp),
                                                shape = CircleShape,
                                                color = MaterialTheme.colorScheme.primary,
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        text = initial,
                                                        color = MaterialTheme.colorScheme.onPrimary,
                                                        style = MaterialTheme.typography.labelMedium,
                                                    )
                                                }
                                            }
                                        } else {
                                            AsyncImage(
                                                model = firebasePhotoUrl,
                                                contentDescription = "Account menu",
                                                modifier = Modifier
                                                    .size(30.dp)
                                                    .clip(CircleShape),
                                                onError = { avatarLoadFailed = true },
                                            )
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = accountMenuExpanded,
                                        onDismissRequest = { accountMenuExpanded = false },
                                    ) {
                                        val displayName = firebaseDisplayName?.takeIf { it.isNotBlank() }
                                        val email = firebaseEmail?.takeIf { it.isNotBlank() }
                                        if (displayName != null) {
                                            DropdownMenuItem(
                                                text = { Text(displayName) },
                                                onClick = {},
                                                enabled = false,
                                            )
                                        }
                                        if (email != null) {
                                            DropdownMenuItem(
                                                text = { Text(email) },
                                                onClick = {},
                                                enabled = false,
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("Sign out", color = Color(0xFFD32F2F)) },
                                            onClick = {
                                                accountMenuExpanded = false
                                                firebaseAuth.signOut()
                                                signInClient?.signOut()
                                                firebaseUid = null
                                                firebaseEmail = null
                                                firebaseDisplayName = null
                                                firebasePhotoUrl = null
                                                avatarLoadFailed = false
                                                firebaseIdToken = null
                                            },
                                        )
                                    }
                                }
                            }
                        },
                    ) { innerPadding ->
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
                                onCancel = { screen = AppScreen.Home },
                                onShare = {
                                    // Convert to compressed single-page PDF, then send to desktop with Firebase token
                                    val token = firebaseIdToken
                                    if (token.isNullOrBlank()) {
                                        Toast.makeText(context, "Missing Firebase token.", Toast.LENGTH_LONG).show()
                                        return@ScanResultScreen
                                    }
                                    scope.launch {
                                        Toast.makeText(
                                            context,
                                            "Preparing PDF & sending to desktop…",
                                            Toast.LENGTH_SHORT,
                                        ).show()

                                        val fileName = "scanmeow_${System.currentTimeMillis()}.pdf"
                                        val pdfBytes = runCatching {
                                            createCompressedPdfFromJpeg(s.jpeg)
                                        }.getOrElse { e ->
                                            Toast.makeText(
                                                context,
                                                "PDF creation failed: ${e.message ?: "unknown"}",
                                                Toast.LENGTH_LONG,
                                            ).show()
                                            return@launch
                                        }

                                        runCatching {
                                            sendPdfToDesktop(pdfBytes, fileName, token)
                                        }.onSuccess {
                                            Toast.makeText(context, "Sent to desktop.", Toast.LENGTH_SHORT).show()
                                            screen = AppScreen.Home
                                        }.onFailure { e ->
                                            Toast.makeText(
                                                context,
                                                "Send failed: ${e.message ?: "unknown"}",
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    }
                                },
                                modifier = Modifier.padding(innerPadding),
                                cancelLabel = stringResource(R.string.action_cancel),
                                shareLabel = stringResource(R.string.action_share),
                            )
                        }
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

private fun createCompressedPdfFromJpeg(jpegBytes: ByteArray): ByteArray {
    val decoded = decodeJpegForPdf(jpegBytes) ?: error("decode jpeg failed")

    // Downscale for smaller PDF size
    val maxDim = max(decoded.width, decoded.height).toFloat()
    val scale = min(1f, PDF_MAX_DIM_PX.toFloat() / maxDim)
    val scaled = if (scale < 1f) {
        val w = (decoded.width * scale).toInt().coerceAtLeast(1)
        val h = (decoded.height * scale).toInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(decoded, w, h, true)
    } else decoded

    // Extra “compression” step: JPEG re-encode then decode to reduce what the PDF embeds
    val compressedJpeg = ByteArrayOutputStream().use { baos ->
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, PDF_JPEG_QUALITY, baos)
        baos.toByteArray()
    }
    val pdfBitmap = decodeJpegForPdf(compressedJpeg) ?: scaled

    val pdfDoc = PdfDocument()
    try {
        // A4 in points at 72dpi
        val pageW = 595
        val pageH = 842
        val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, 1).create()
        val page = pdfDoc.startPage(pageInfo)
        val canvas = page.canvas

        canvas.drawColor(AndroidColor.WHITE)

        val dst = aspectFitRect(
            srcW = pdfBitmap.width,
            srcH = pdfBitmap.height,
            dstW = pageW.toFloat(),
            dstH = pageH.toFloat(),
        )
        canvas.drawBitmap(pdfBitmap, null, dst, null)

        pdfDoc.finishPage(page)

        val out = ByteArrayOutputStream()
        pdfDoc.writeTo(out)
        return out.toByteArray()
    } finally {
        pdfDoc.close()
    }
}

private fun decodeJpegForPdf(jpegBytes: ByteArray): Bitmap? {
    val opts = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts)
}

private fun aspectFitRect(srcW: Int, srcH: Int, dstW: Float, dstH: Float): RectF {
    val srcAspect = srcW.toFloat() / max(1f, srcH.toFloat())
    val dstAspect = dstW / max(1f, dstH)
    val drawW: Float
    val drawH: Float
    if (srcAspect > dstAspect) {
        drawW = dstW
        drawH = dstW / srcAspect
    } else {
        drawH = dstH
        drawW = dstH * srcAspect
    }
    val left = (dstW - drawW) / 2f
    val top = (dstH - drawH) / 2f
    return RectF(left, top, left + drawW, top + drawH)
}

private suspend fun sendPdfToDesktop(pdfBytes: ByteArray, fileName: String, firebaseIdToken: String) =
    withContext(Dispatchers.IO) {
        Socket(DESKTOP_TCP_HOST, DESKTOP_TCP_PORT).use { socket ->
            DataOutputStream(socket.getOutputStream()).use { dos ->
                dos.writeBytes("SMK2") // magic (4 bytes)

                val nameBytes = fileName.toByteArray(Charsets.UTF_8)
                require(nameBytes.size <= 65535) { "filename too long" }
                dos.writeShort(nameBytes.size) // 2 bytes
                dos.write(nameBytes) // filename

                val tokenBytes = firebaseIdToken.toByteArray(Charsets.UTF_8)
                require(tokenBytes.size <= 65535) { "idToken too long" }
                dos.writeShort(tokenBytes.size) // 2 bytes
                dos.write(tokenBytes)

                dos.writeLong(pdfBytes.size.toLong()) // 8 bytes

                var offset = 0
                val chunk = 32 * 1024
                while (offset < pdfBytes.size) {
                    val end = min(offset + chunk, pdfBytes.size)
                    dos.write(pdfBytes, offset, end - offset)
                    offset = end
                }
                dos.flush()
            }
        }
    }

@Preview(showBackground = true)
@Composable
fun MainHomeScreenPreview() {
    ScanMeowTheme {
        MainHomeScreen()
    }
}
