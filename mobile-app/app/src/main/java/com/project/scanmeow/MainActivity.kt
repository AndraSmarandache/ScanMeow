package com.project.scanmeow

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
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
import coil3.compose.AsyncImage
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.project.scanmeow.BuildConfig
import com.project.scanmeow.data.SupabaseDocumentsRepository
import com.project.scanmeow.data.UserCloudDocument
import com.project.scanmeow.data.supabaseSignInWithGoogleIdToken
import com.project.scanmeow.ui.home.MainHomeScreen
import com.project.scanmeow.ui.home.PdfViewerScreen
import com.project.scanmeow.ui.home.ScanAlignedReviewScreen
import com.project.scanmeow.ui.home.ScanLoadingScreen
import com.project.scanmeow.ui.home.ScanResultScreen
import com.project.scanmeow.ui.screens.ScannerScreen
import com.project.scanmeow.ui.theme.ScanMeowTheme
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
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

            // ── Google → Supabase session (JWT in accessToken) ───────────────────
            var supabaseUserId by remember { mutableStateOf<String?>(null) }
            var supabaseAccessToken by remember { mutableStateOf<String?>(null) }
            var googleEmail by remember { mutableStateOf<String?>(null) }
            var googleDisplayName by remember { mutableStateOf<String?>(null) }
            var googlePhotoUrl by remember { mutableStateOf<String?>(null) }
            var avatarLoadFailed by remember { mutableStateOf(false) }

            val webClientId = remember { BuildConfig.GOOGLE_WEB_CLIENT_ID.trim() }
            val webClientIdLooksInvalid =
                webClientId.isBlank() ||
                    webClientId.contains("xxxxx", ignoreCase = true) ||
                    webClientId.contains("your_", ignoreCase = true) ||
                    !webClientId.endsWith(".apps.googleusercontent.com")
            val signInClient = remember(webClientId) {
                if (webClientIdLooksInvalid) null
                else {
                    val options = GoogleSignInOptions.Builder()
                        .requestIdToken(webClientId)
                        .requestEmail()
                        .build()
                    GoogleSignIn.getClient(context, options)
                }
            }

            val signInLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult(),
            ) { activityResult ->
                val data = activityResult.data
                if (activityResult.resultCode != Activity.RESULT_OK) {
                    val hint = when (activityResult.resultCode) {
                        Activity.RESULT_CANCELED -> buildString {
                            append("Sign-in canceled (code 0). Common causes: ")
                            append("1) emulator without Google Play or without a Google account in Settings; ")
                            append("2) back was pressed; ")
                            append("3) missing OAuth Android client for package ")
                            append(context.packageName)
                            append(" + debug SHA-1 in Google Cloud (UI may close immediately). ")
                            append("Rebuild after saving SHA-1.")
                        }
                        else -> "Google sign-in failed (code ${activityResult.resultCode}). Check Web Client ID, Android client, and SHA-1."
                    }
                    Toast.makeText(context, hint, Toast.LENGTH_LONG).show()
                    return@rememberLauncherForActivityResult
                }
                if (data == null) {
                    Toast.makeText(context, "Google sign-in: empty response.", Toast.LENGTH_SHORT).show()
                    return@rememberLauncherForActivityResult
                }
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                try {
                    val account = task.getResult(ApiException::class.java)
                    val googleIdToken = account.idToken
                    if (googleIdToken.isNullOrBlank()) {
                        Toast.makeText(
                            context,
                            "Missing Google idToken. Verify Web Client ID and SHA-1.",
                            Toast.LENGTH_LONG,
                        ).show()
                        return@rememberLauncherForActivityResult
                    }
                    googleEmail = account.email
                    googleDisplayName = account.displayName
                    googlePhotoUrl = account.photoUrl?.toString()
                    avatarLoadFailed = false
                    scope.launch {
                        val result = runCatching {
                            supabaseSignInWithGoogleIdToken(http, googleIdToken)
                        }
                        result.onSuccess { pair ->
                            supabaseAccessToken = pair.first
                            supabaseUserId = pair.second
                        }.onFailure { e ->
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Supabase: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } catch (e: ApiException) {
                    val code = e.statusCode
                    val detail = when (code) {
                        10 -> "DEVELOPER_ERROR (10): missing or incorrect Android OAuth client (package + SHA-1) in Google Cloud."
                        12501 -> "User canceled."
                        else -> e.message ?: "unknown"
                    }
                    Toast.makeText(
                        context,
                        "Google: $detail (code $code)",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }

            ScanMeowTheme {
                if (supabaseAccessToken.isNullOrBlank() || supabaseUserId.isNullOrBlank()) {
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
                            Button(
                                onClick = {
                                    if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
                                        Toast.makeText(
                                            context,
                                            "Set supabase.url and supabase.anon.key in local.properties.",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                        return@Button
                                    }
                                    if (signInClient == null) {
                                        Toast.makeText(
                                            context,
                                            if (webClientIdLooksInvalid) {
                                                "In local.properties, google.web.client.id must be a real Web Client ID, " +
                                                    "not a placeholder. Find it in Google Cloud Credentials or old google-services.json (client_type 3)."
                                            } else {
                                                "Set google.web.client.id in local.properties."
                                            },
                                            Toast.LENGTH_LONG,
                                        ).show()
                                        return@Button
                                    }
                                    // Clear prior session so the account picker shows reliably.
                                    signInClient.signOut().addOnCompleteListener {
                                        signInLauncher.launch(signInClient.signInIntent)
                                    }
                                },
                                modifier = Modifier.padding(top = 16.dp),
                            ) {
                                Text("Sign in")
                            }
                        }
                    }
                } else {
                    var screen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }
                    var capturedJpegBytes by remember { mutableStateOf<ByteArray?>(null) }
                    var accountMenuExpanded by remember { mutableStateOf(false) }
                    var cloudDocs by remember { mutableStateOf<List<UserCloudDocument>>(emptyList()) }
                    val cloudRepo = remember { SupabaseDocumentsRepository(http) }
                    var bluetoothPcMode by remember { mutableStateOf(false) }
                    var recentDocsExpanded by remember { mutableStateOf(false) }

                    LaunchedEffect(supabaseAccessToken, supabaseUserId) {
                        cloudRepo.accessToken = supabaseAccessToken
                        val uid = supabaseUserId ?: return@LaunchedEffect
                        runCatching {
                            cloudDocs = cloudRepo.fetchDocumentsFromServer(uid)
                        }
                    }

                    DisposableEffect(supabaseUserId, supabaseAccessToken) {
                        cloudRepo.accessToken = supabaseAccessToken
                        val uid = supabaseUserId
                        if (uid.isNullOrBlank()) {
                            return@DisposableEffect onDispose { }
                        }
                        val reg = cloudRepo.attachListener(scope, uid) { cloudDocs = it }
                        onDispose { reg.remove() }
                    }

                    Box(Modifier.fillMaxSize()) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        topBar = {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding()
                                    .padding(horizontal = 8.dp)
                                    .padding(bottom = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val showBack = when (screen) {
                                    is AppScreen.AlignedReview,
                                    is AppScreen.PdfViewer,
                                    -> true
                                    else -> false
                                }
                                if (showBack) {
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
                                    ) {
                                        val showFallback = googlePhotoUrl.isNullOrBlank() || avatarLoadFailed
                                        if (showFallback) {
                                            val initial = (googleDisplayName ?: googleEmail ?: "")
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
                                                model = googlePhotoUrl,
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
                                        val displayName = googleDisplayName?.takeIf { it.isNotBlank() }
                                        val email = googleEmail?.takeIf { it.isNotBlank() }
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
                                                signInClient?.signOut()
                                                supabaseUserId = null
                                                supabaseAccessToken = null
                                                googleEmail = null
                                                googleDisplayName = null
                                                googlePhotoUrl = null
                                                avatarLoadFailed = false
                                                cloudRepo.accessToken = null
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
                                cloudDocuments = cloudDocs,
                                recentDocumentsExpanded = recentDocsExpanded,
                                onToggleRecentDocumentsExpanded = {
                                    recentDocsExpanded = !recentDocsExpanded
                                },
                                bluetoothModeForPc = bluetoothPcMode,
                                onBluetoothModeChange = { bluetoothPcMode = it },
                                onShareCloudDocuments = { docs ->
                                    if (docs.isEmpty()) return@MainHomeScreen
                                    val token = supabaseAccessToken
                                    if (token.isNullOrBlank()) {
                                        Toast.makeText(context, "Not signed in.", Toast.LENGTH_LONG).show()
                                        return@MainHomeScreen
                                    }
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            for (d in docs) {
                                                val f = File(
                                                    context.cacheDir,
                                                    "desk_${d.docId}_${System.currentTimeMillis()}.pdf",
                                                )
                                                cloudRepo.downloadPdfToFile(d, f)
                                                val bytes = f.readBytes()
                                                runCatching { f.delete() }
                                                var fn = d.fileName.trim()
                                                    .ifBlank { "scanmeow_${d.docId}.pdf" }
                                                    .replace("/", "_")
                                                if (!fn.endsWith(".pdf", ignoreCase = true)) {
                                                    fn = "$fn.pdf"
                                                }
                                                sendPdfToDesktop(bytes, fn, token)
                                            }
                                        }
                                    }.onSuccess {
                                        Toast.makeText(
                                            context,
                                            if (docs.size == 1) {
                                                context.getString(R.string.toast_sent_to_pc)
                                            } else {
                                                context.getString(R.string.toast_sent_n_to_pc, docs.size)
                                            },
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }.onFailure { e ->
                                        Toast.makeText(
                                            context,
                                            e.message ?: "TCP send failed",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                },
                                onDeleteCloudDocuments = { docs ->
                                    val uid = supabaseUserId
                                        ?: throw IllegalStateException("Not signed in")
                                    withContext(Dispatchers.IO) {
                                        for (d in docs) {
                                            cloudRepo.deleteDocument(uid, d)
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.action_delete),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                                onScanDocumentClick = { screen = AppScreen.Scanner },
                                onDocumentClick = {
                                    screen = AppScreen.PdfViewer(it)
                                },
                            )

                            AppScreen.Scanner -> ScannerScreen(
                                onImageCaptured = { filePath ->
                                    val jpeg = File(filePath).readBytes()
                                    capturedJpegBytes = jpeg
                                    screen = AppScreen.LoadingAligned
                                    scope.launch {
                                        val result = runCatching {
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
                                onBack = { screen = AppScreen.Home },
                            )

                            is AppScreen.PdfViewer -> {
                                var viewerDoc by remember(s.doc.docId) { mutableStateOf(s.doc) }
                                LaunchedEffect(s.doc.docId) {
                                    viewerDoc = s.doc
                                }
                                var pdfRenameOpen by remember { mutableStateOf(false) }
                                var pdfRenameText by remember { mutableStateOf("") }

                                var pdfFile by remember(s.doc.docId) { mutableStateOf<File?>(null) }
                                var pdfErr by remember(s.doc.docId) { mutableStateOf(false) }
                                LaunchedEffect(s.doc.docId, s.doc.storagePath) {
                                    pdfErr = false
                                    val f = File(context.cacheDir, "view_${s.doc.docId}.pdf")
                                    val meta = File(context.cacheDir, "view_${s.doc.docId}.meta")
                                    val cachedPath = if (meta.exists()) meta.readText() else ""
                                    if (
                                        f.exists() && f.length() > 0L &&
                                        cachedPath == s.doc.storagePath
                                    ) {
                                        pdfFile = f
                                        return@LaunchedEffect
                                    }
                                    pdfFile = null
                                    runCatching {
                                        cloudRepo.downloadPdfToFile(s.doc, f)
                                        meta.writeText(s.doc.storagePath)
                                        pdfFile = f
                                    }.onFailure { pdfErr = true }
                                }
                                Box(Modifier.padding(innerPadding)) {
                                    when {
                                        pdfErr -> Column(Modifier.fillMaxSize()) {
                                            Text(stringResource(R.string.pdf_viewer_error))
                                        }
                                        pdfFile != null -> PdfViewerScreen(
                                            pdfFile = pdfFile!!,
                                            title = viewerDoc.fileName,
                                            onTitleLongPress = {
                                                pdfRenameText = viewerDoc.fileName
                                                pdfRenameOpen = true
                                            },
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                        else -> ScanLoadingScreen(
                                            message = stringResource(R.string.pdf_loading),
                                            modifier = Modifier.fillMaxSize(),
                                            showDocumentScanAnimation = false,
                                        )
                                    }
                                    if (pdfRenameOpen) {
                                        AlertDialog(
                                            onDismissRequest = { pdfRenameOpen = false },
                                            title = { Text(stringResource(R.string.pdf_rename_title)) },
                                            text = {
                                                OutlinedTextField(
                                                    value = pdfRenameText,
                                                    onValueChange = { pdfRenameText = it },
                                                    singleLine = true,
                                                    label = { Text(stringResource(R.string.pdf_rename_label)) },
                                                )
                                            },
                                            confirmButton = {
                                                TextButton(
                                                    onClick = {
                                                        pdfRenameOpen = false
                                                        scope.launch {
                                                            val uid = supabaseUserId
                                                            if (uid.isNullOrBlank()) return@launch
                                                            runCatching {
                                                                cloudRepo.updateFileName(viewerDoc, pdfRenameText)
                                                            }.onSuccess {
                                                                val newName = pdfRenameText.replace("/", "_").trim()
                                                                if (newName.isNotEmpty()) {
                                                                    viewerDoc = viewerDoc.copy(fileName = newName)
                                                                    cloudDocs = cloudDocs.map { d ->
                                                                        if (d.docId == viewerDoc.docId) viewerDoc else d
                                                                    }
                                                                }
                                                            }.onFailure { e ->
                                                                Toast.makeText(
                                                                    context,
                                                                    e.message ?: "Rename failed",
                                                                    Toast.LENGTH_LONG,
                                                                ).show()
                                                            }
                                                        }
                                                    },
                                                ) { Text(stringResource(R.string.action_confirm)) }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = { pdfRenameOpen = false }) {
                                                    Text(stringResource(R.string.action_cancel))
                                                }
                                            },
                                        )
                                    }
                                }
                            }

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
                                    val jpeg = capturedJpegBytes
                                    if (jpeg == null) { screen = AppScreen.Home; return@ScanAlignedReviewScreen }
                                    val alignedBytes = s.jpeg
                                    screen = AppScreen.LoadingFinal
                                    scope.launch {
                                        val result = runCatching {
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
                                onSave = {
                                    val uid = supabaseUserId
                                    if (uid.isNullOrBlank()) {
                                        Toast.makeText(context, "Not signed in.", Toast.LENGTH_LONG).show()
                                        return@ScanResultScreen
                                    }
                                    scope.launch {
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
                                            cloudRepo.uploadPdfAndMeta(uid, pdfBytes, fileName)
                                        }.onFailure { e ->
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    context,
                                                    "Cloud save failed: ${e.message ?: "unknown"}",
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            }
                                            return@launch
                                        }

                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.toast_saved_cloud),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                            screen = AppScreen.Home
                                        }
                                    }
                                },
                                onShare = {
                                    scope.launch {
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
                                        val token = supabaseAccessToken
                                        if (token.isNullOrBlank()) {
                                            Toast.makeText(context, "Not signed in.", Toast.LENGTH_LONG).show()
                                            return@launch
                                        }
                                        runCatching {
                                            val fn = "scanmeow_${System.currentTimeMillis()}.pdf"
                                            sendPdfToDesktop(pdfBytes, fn, token)
                                        }.onSuccess {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.toast_sent_to_pc),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }.onFailure { e ->
                                            Toast.makeText(context, e.message ?: "TCP send failed", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                modifier = Modifier.padding(innerPadding),
                                cancelLabel = stringResource(R.string.action_cancel),
                                saveLabel = stringResource(R.string.action_save),
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}

private sealed interface AppScreen {
    data object Home : AppScreen
    data object Scanner : AppScreen
    data object LoadingAligned : AppScreen
    data object LoadingFinal : AppScreen
    data class AlignedReview(val jpeg: ByteArray) : AppScreen
    data class ScannedResult(val jpeg: ByteArray) : AppScreen
    data class PdfViewer(val doc: UserCloudDocument) : AppScreen
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

private suspend fun sendPdfToDesktop(pdfBytes: ByteArray, fileName: String, supabaseAccessToken: String) =
    withContext(Dispatchers.IO) {
        Socket(DESKTOP_TCP_HOST, DESKTOP_TCP_PORT).use { socket ->
            DataOutputStream(socket.getOutputStream()).use { dos ->
                dos.writeBytes("SMK2") // magic (4 bytes)

                val nameBytes = fileName.toByteArray(Charsets.UTF_8)
                require(nameBytes.size <= 65535) { "filename too long" }
                dos.writeShort(nameBytes.size) // 2 bytes
                dos.write(nameBytes) // filename

                val tokenBytes = supabaseAccessToken.toByteArray(Charsets.UTF_8)
                require(tokenBytes.size <= 65535) { "access token too long" }
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
        MainHomeScreen(
            cloudDocuments = emptyList(),
            recentDocumentsExpanded = false,
            onToggleRecentDocumentsExpanded = {},
            bluetoothModeForPc = false,
            onBluetoothModeChange = {},
        )
    }
}
