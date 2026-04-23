package com.project.scanmeow

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.project.scanmeow.data.supabaseRefreshSession
import com.project.scanmeow.data.supabaseSignInWithGoogleIdToken
import com.project.scanmeow.bluetooth.BluetoothDevicePickerDialog
import com.project.scanmeow.bluetooth.sendPdfViaBluetooth
import com.project.scanmeow.discovery.discoverScanApiBase
import com.project.scanmeow.ui.home.CropAdjustScreen
import com.project.scanmeow.ui.home.MainHomeScreen
import com.project.scanmeow.ui.home.PdfPreviewScreen
import com.project.scanmeow.ui.home.PdfViewerScreen
import com.project.scanmeow.ui.home.ScanAlignedReviewScreen
import com.project.scanmeow.ui.home.ScanLoadingScreen
import com.project.scanmeow.ui.home.ScanResultScreen
import com.project.scanmeow.ui.screens.ScannerScreen
import com.project.scanmeow.ui.theme.ScanMeowTheme
import android.bluetooth.BluetoothDevice
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
import androidx.compose.ui.geometry.Offset
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

private val SCAN_API_FALLBACK = BuildConfig.SCAN_API_BASE
private const val NOTIF_CHANNEL_ID = "scan_complete"
private const val NOTIF_ID = 1001
private const val DESKTOP_TCP_HOST = "10.0.2.2" // emulator -> host
private const val DESKTOP_TCP_PORT = 5566

private const val PDF_MAX_DIM_PX = 1400
private const val PDF_JPEG_QUALITY = 75

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL_ID, "Scan complete", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Notifies when document processing is ready" }
            )
        }
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            // Connect fast; reads stay long because OpenCV warp can take tens of seconds
            val http = remember {
                OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .writeTimeout(120, TimeUnit.SECONDS)
                    .callTimeout(180, TimeUnit.SECONDS)
                    .build()
            }

            // ── Auto-discover scan API via mDNS ─────────────────────────────────
            var scanApiBase by remember { mutableStateOf(SCAN_API_FALLBACK) }
            LaunchedEffect(Unit) {
                val found = discoverScanApiBase(context)
                if (found != null) {
                    scanApiBase = found
                    android.util.Log.i("ScanMeow", "mDNS: found API at $found")
                }
            }

            // ── Notification permission (Android 13+) ────────────────────────────
            val notifPermLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) {}
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            // ── Google → Supabase session (JWT in accessToken) ───────────────────
            var supabaseUserId by remember { mutableStateOf<String?>(null) }
            var supabaseAccessToken by remember { mutableStateOf<String?>(null) }
            var googleEmail by remember { mutableStateOf<String?>(null) }
            var googleDisplayName by remember { mutableStateOf<String?>(null) }
            var googlePhotoUrl by remember { mutableStateOf<String?>(null) }
            var avatarLoadFailed by remember { mutableStateOf(false) }
            var sessionRestoring by remember { mutableStateOf(true) }

            // ── Restore persisted session on startup ─────────────────────────────
            LaunchedEffect(Unit) {
                val prefs = context.getSharedPreferences("session", android.content.Context.MODE_PRIVATE)
                val savedRefresh = prefs.getString("refresh_token", null)
                if (!savedRefresh.isNullOrBlank()) {
                    runCatching { supabaseRefreshSession(http, savedRefresh) }
                        .onSuccess { (newAccess, newId, newRefresh) ->
                            supabaseAccessToken = newAccess
                            supabaseUserId = newId
                            googleEmail = prefs.getString("email", null)
                            googleDisplayName = prefs.getString("display_name", null)
                            googlePhotoUrl = prefs.getString("photo_url", null)
                            prefs.edit()
                                .putString("access_token", newAccess)
                                .putString("refresh_token", newRefresh)
                                .apply()
                        }
                        .onFailure { prefs.edit().clear().apply() }
                }
                sessionRestoring = false
            }

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
                        result.onSuccess { (access, id, refresh) ->
                            supabaseAccessToken = access
                            supabaseUserId = id
                            context.getSharedPreferences("session", android.content.Context.MODE_PRIVATE)
                                .edit()
                                .putString("access_token", access)
                                .putString("refresh_token", refresh)
                                .putString("user_id", id)
                                .putString("email", googleEmail)
                                .putString("display_name", googleDisplayName)
                                .putString("photo_url", googlePhotoUrl)
                                .apply()
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
                if (sessionRestoring) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color(0xFF0D0D1F)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = com.project.scanmeow.ui.theme.ScanBlue)
                    }
                } else if (supabaseAccessToken.isNullOrBlank() || supabaseUserId.isNullOrBlank()) {
                    val logoScale = remember { Animatable(0.4f) }
                    val logoAlpha = remember { Animatable(0f) }
                    val textAlpha = remember { Animatable(0f) }
                    val textOffsetY = remember { Animatable(28f) }
                    val buttonAlpha = remember { Animatable(0f) }
                    val buttonOffsetY = remember { Animatable(20f) }

                    LaunchedEffect(Unit) {
                        launch { logoAlpha.animateTo(1f, tween(700)) }
                        launch {
                            logoScale.animateTo(
                                1f,
                                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                            )
                        }
                        delay(350)
                        launch { textAlpha.animateTo(1f, tween(600)) }
                        launch { textOffsetY.animateTo(0f, tween(600)) }
                        delay(250)
                        launch { buttonAlpha.animateTo(1f, tween(500)) }
                        launch { buttonOffsetY.animateTo(0f, tween(500)) }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                drawRect(Color(0xFF0D0D0D))
                                drawRect(
                                    brush = Brush.radialGradient(
                                        listOf(Color(0xFFBF5000).copy(alpha = 0.75f), Color.Transparent),
                                        center = Offset(size.width * 0.88f, size.height * 0.08f),
                                        radius = size.width * 0.65f,
                                    ),
                                )
                                drawRect(
                                    brush = Brush.radialGradient(
                                        listOf(Color(0xFFD46000).copy(alpha = 0.60f), Color.Transparent),
                                        center = Offset(size.width * 0.12f, size.height * 0.88f),
                                        radius = size.width * 0.55f,
                                    ),
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_scanmeow_logo),
                                contentDescription = "ScanMeow",
                                modifier = Modifier
                                    .fillMaxWidth(0.92f)
                                    .alpha(logoAlpha.value)
                                    .scale(logoScale.value),
                            )
                            Spacer(Modifier.height(32.dp))
                            Text(
                                text = "Scan & organize your documents",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .alpha(textAlpha.value)
                                    .offset(y = textOffsetY.value.dp),
                            )
                            Spacer(Modifier.height(56.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .alpha(buttonAlpha.value)
                                    .offset(y = buttonOffsetY.value.dp),
                            ) {
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
                                        signInClient.signOut().addOnCompleteListener {
                                            signInLauncher.launch(signInClient.signInIntent)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF4285F4),
                                        contentColor = Color.White,
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(Color.White),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Image(
                                            painter = painterResource(R.drawable.ic_google_g),
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        "Continue with Google",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    var screen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }
                    var capturedJpegBytes by remember { mutableStateOf<ByteArray?>(null) }
                    var accountMenuExpanded by remember { mutableStateOf(false) }
                    var cloudDocs by remember { mutableStateOf<List<UserCloudDocument>>(emptyList()) }
                    val cloudRepo = remember { SupabaseDocumentsRepository(http) }
                    var btTargetDevice by remember { mutableStateOf<BluetoothDevice?>(null) }
                    var showBtPicker by remember { mutableStateOf(false) }
                    var recentDocsExpanded by remember { mutableStateOf(false) }
                    var sessionPages by remember { mutableStateOf<List<ByteArray>>(emptyList()) }

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

                    if (showBtPicker) {
                        BluetoothDevicePickerDialog(
                            onDismiss = { showBtPicker = false },
                            onDeviceSelected = { device ->
                                btTargetDevice = device
                                showBtPicker = false
                            },
                        )
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
                                    is AppScreen.PdfPreview,
                                    -> true
                                    else -> false
                                }
                                if (showBack) {
                                    IconButton(onClick = {
                                        if (screen is AppScreen.PdfPreview) sessionPages = emptyList()
                                        screen = AppScreen.Home
                                    }) {
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
                                                context.getSharedPreferences("session", android.content.Context.MODE_PRIVATE)
                                                    .edit().clear().apply()
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
                                btDeviceName = btTargetDevice?.let {
                                    runCatching { @Suppress("MissingPermission") it.name }.getOrDefault(it.address)
                                },
                                onBluetoothTap = { showBtPicker = true },
                                onShareCloudDocuments = { docs ->
                                    if (docs.isEmpty()) return@MainHomeScreen
                                    val token = supabaseAccessToken
                                    if (token.isNullOrBlank()) {
                                        Toast.makeText(context, "Not signed in.", Toast.LENGTH_LONG).show()
                                        return@MainHomeScreen
                                    }
                                    if (btTargetDevice == null) {
                                        showBtPicker = true
                                        return@MainHomeScreen
                                    }
                                    scope.launch {
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
                                                    if (!fn.endsWith(".pdf", ignoreCase = true)) fn = "$fn.pdf"
                                                    sendPdfViaBluetooth(btTargetDevice!!, bytes, fn, token)
                                                }
                                            }
                                        }.onSuccess {
                                            Toast.makeText(
                                                context,
                                                if (docs.size == 1) context.getString(R.string.toast_sent_to_pc)
                                                else context.getString(R.string.toast_sent_n_to_pc, docs.size),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }.onFailure { e ->
                                            Toast.makeText(
                                                context,
                                                e.message ?: "Send failed",
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
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
                                    screen = AppScreen.LoadingAligned
                                    scope.launch {
                                        try {
                                            val jpeg = withContext(Dispatchers.IO) {
                                                File(filePath).readBytes().withExifRotation()
                                            }
                                            capturedJpegBytes = jpeg
                                            val (corners, w, h) = http.postDetect(scanApiBase, jpeg)
                                            screen = AppScreen.CropAdjust(jpeg, corners, w, h)
                                            notifyScanReady(context)
                                        } catch (e: Exception) {
                                            if (e is kotlinx.coroutines.CancellationException) throw e
                                            screen = AppScreen.Home
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.toast_scan_failed, e.message ?: "unknown"),
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    }
                                },
                                onBack = { sessionPages = emptyList(); screen = AppScreen.Home },
                            )

                            is AppScreen.CropAdjust -> CropAdjustScreen(
                                jpegBytes = s.jpeg,
                                corners = s.corners,
                                imageWidth = s.imgWidth,
                                imageHeight = s.imgHeight,
                                onRetake = { screen = AppScreen.Scanner },
                                onConfirm = { adjustedCorners ->
                                    val jpeg = s.jpeg
                                    screen = AppScreen.LoadingFinal
                                    scope.launch {
                                        val result = runCatching {
                                            http.postWarp(scanApiBase, jpeg, adjustedCorners)
                                        }
                                        result.onSuccess { bytes ->
                                            screen = AppScreen.ScannedResult(bytes)
                                        }.onFailure { e ->
                                            screen = AppScreen.CropAdjust(s.jpeg, s.corners, s.imgWidth, s.imgHeight)
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.toast_finalize_failed, e.message ?: "unknown"),
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    }
                                },
                                modifier = Modifier.padding(innerPadding),
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
                                                base = scanApiBase,
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
                                allPages = sessionPages + listOf(s.jpeg),
                                onDiscard = {
                                    sessionPages = emptyList()
                                    screen = AppScreen.Home
                                },
                                onAddPage = {
                                    sessionPages = sessionPages + s.jpeg
                                    screen = AppScreen.Scanner
                                },
                                onSave = {
                                    val allPages = sessionPages + listOf(s.jpeg)
                                    screen = AppScreen.LoadingFinal
                                    scope.launch {
                                        val pdfBytes = runCatching {
                                            withContext(Dispatchers.IO) { createMultiPagePdfFromJpegs(allPages) }
                                        }.getOrElse { e ->
                                            screen = AppScreen.ScannedResult(s.jpeg)
                                            Toast.makeText(
                                                context,
                                                "PDF creation failed: ${e.message ?: "unknown"}",
                                                Toast.LENGTH_LONG,
                                            ).show()
                                            return@launch
                                        }
                                        screen = AppScreen.PdfPreview(pdfBytes, allPages)
                                    }
                                },
                                onDownloadPage = { pageBytes ->
                                    scope.launch {
                                        runCatching {
                                            saveJpegToGallery(context, pageBytes, "scanmeow_${System.currentTimeMillis()}.jpg")
                                        }.onSuccess {
                                            Toast.makeText(context, "Page saved to gallery", Toast.LENGTH_SHORT).show()
                                        }.onFailure { e ->
                                            Toast.makeText(context, "Save failed: ${e.message ?: "unknown"}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                modifier = Modifier.padding(innerPadding),
                            )
                            is AppScreen.PdfPreview -> {
                                var showSendConfirm by remember { mutableStateOf(false) }
                                val pcName = btTargetDevice?.let {
                                    runCatching { @Suppress("MissingPermission") it.name }.getOrDefault(it.address)
                                } ?: "PC"
                                if (showSendConfirm) {
                                    AlertDialog(
                                        onDismissRequest = { showSendConfirm = false },
                                        title = { Text("Send to $pcName?") },
                                        confirmButton = {
                                            TextButton(onClick = {
                                                showSendConfirm = false
                                                val token = supabaseAccessToken
                                                if (token.isNullOrBlank()) {
                                                    Toast.makeText(context, "Not signed in.", Toast.LENGTH_LONG).show()
                                                    return@TextButton
                                                }
                                                val fn = "scanmeow_${System.currentTimeMillis()}.pdf"
                                                scope.launch {
                                                    runCatching {
                                                        sendPdfViaBluetooth(btTargetDevice!!, s.pdfBytes, fn, token)
                                                    }.onSuccess {
                                                        Toast.makeText(context, context.getString(R.string.toast_sent_to_pc), Toast.LENGTH_SHORT).show()
                                                    }.onFailure { e ->
                                                        Toast.makeText(context, e.message ?: "Send failed", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            }) { Text("Send") }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { showSendConfirm = false }) { Text("Cancel") }
                                        },
                                    )
                                }
                                PdfPreviewScreen(
                                    pdfBytes = s.pdfBytes,
                                    onAddMore = {
                                        sessionPages = s.allPages
                                        screen = AppScreen.Scanner
                                    },
                                    onDownload = {
                                        scope.launch {
                                            runCatching {
                                                savePdfToDownloads(context, s.pdfBytes, "scanmeow_${System.currentTimeMillis()}.pdf")
                                            }.onSuccess {
                                                Toast.makeText(context, "PDF saved to Downloads", Toast.LENGTH_SHORT).show()
                                            }.onFailure { e ->
                                                Toast.makeText(context, "Save failed: ${e.message ?: "unknown"}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    onShare = {
                                        if (btTargetDevice == null) showBtPicker = true
                                        else showSendConfirm = true
                                    },
                                    modifier = Modifier.padding(innerPadding),
                                )
                            }
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
    data class CropAdjust(
        val jpeg: ByteArray,
        val corners: List<Offset>,
        val imgWidth: Int,
        val imgHeight: Int,
    ) : AppScreen
    data class AlignedReview(val jpeg: ByteArray) : AppScreen
    data class ScannedResult(val jpeg: ByteArray) : AppScreen
    data class PdfViewer(val doc: UserCloudDocument) : AppScreen
    data class PdfPreview(val pdfBytes: ByteArray, val allPages: List<ByteArray>) : AppScreen
}

private suspend fun OkHttpClient.postScanMultipart(
    base: String,
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
    val url = "$base/scan?binarize=$b&ai_enhance=$ai&upright=$u"
    val req = Request.Builder().url(url).post(body).build()
    newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) error("HTTP ${resp.code}")
        resp.body?.bytes() ?: error("empty body")
    }
}

private fun createMultiPagePdfFromJpegs(pages: List<ByteArray>): ByteArray {
    if (pages.isEmpty()) error("No pages to compile")
    val pdfDoc = PdfDocument()
    try {
        pages.forEachIndexed { idx, jpeg ->
            val src = decodeJpegForPdf(jpeg) ?: return@forEachIndexed
            val scaleF = min(1f, PDF_MAX_DIM_PX.toFloat() / max(src.width, src.height).toFloat())
            val bmp = if (scaleF < 1f)
                Bitmap.createScaledBitmap(src, (src.width * scaleF).toInt().coerceAtLeast(1), (src.height * scaleF).toInt().coerceAtLeast(1), true)
            else src
            val reJpeg = ByteArrayOutputStream().use { baos -> bmp.compress(Bitmap.CompressFormat.JPEG, PDF_JPEG_QUALITY, baos); baos.toByteArray() }
            val finalBmp = decodeJpegForPdf(reJpeg) ?: bmp
            val pageW = 595; val pageH = 842
            val page = pdfDoc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, idx + 1).create())
            page.canvas.drawColor(AndroidColor.WHITE)
            page.canvas.drawBitmap(finalBmp, null, aspectFitRect(finalBmp.width, finalBmp.height, pageW.toFloat(), pageH.toFloat()), null)
            pdfDoc.finishPage(page)
        }
        return ByteArrayOutputStream().also { pdfDoc.writeTo(it) }.toByteArray()
    } finally {
        pdfDoc.close()
    }
}

private fun createCompressedPdfFromJpeg(jpegBytes: ByteArray): ByteArray =
    createMultiPagePdfFromJpegs(listOf(jpegBytes))

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

private data class DetectResult(val corners: List<Offset>, val width: Int, val height: Int)

private suspend fun OkHttpClient.postDetect(base: String, jpegBytes: ByteArray): DetectResult =
    withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "page.jpg", jpegBytes.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val req = Request.Builder().url("$base/detect").post(body).build()
        newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string() ?: error("empty body"))
            val arr = json.getJSONArray("corners")
            val corners = (0 until arr.length()).map { i ->
                val pt = arr.getJSONArray(i)
                Offset(pt.getDouble(0).toFloat(), pt.getDouble(1).toFloat())
            }
            DetectResult(corners, json.getInt("width"), json.getInt("height"))
        }
    }

private suspend fun OkHttpClient.postWarp(
    base: String,
    jpegBytes: ByteArray,
    corners: List<Offset>,
): ByteArray = withContext(Dispatchers.IO) {
    val (tl, tr, br, bl) = corners
    val url = "$base/warp?binarize=true&upright=false" +
        "&tl_x=${tl.x}&tl_y=${tl.y}" +
        "&tr_x=${tr.x}&tr_y=${tr.y}" +
        "&br_x=${br.x}&br_y=${br.y}" +
        "&bl_x=${bl.x}&bl_y=${bl.y}"
    val body = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("file", "page.jpg", jpegBytes.toRequestBody("image/jpeg".toMediaType()))
        .build()
    val req = Request.Builder().url(url).post(body).build()
    newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) error("HTTP ${resp.code}")
        resp.body?.bytes() ?: error("empty body")
    }
}

private fun notifyScanReady(context: android.content.Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        != PackageManager.PERMISSION_GRANTED
    ) return
    val pi = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    NotificationManagerCompat.from(context).notify(
        NOTIF_ID,
        NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_scanmeow_logo)
            .setContentTitle("Document ready")
            .setContentText("Tap to adjust crop and save")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build(),
    )
}

private suspend fun saveJpegToGallery(context: android.content.Context, bytes: ByteArray, fileName: String) =
    withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "DCIM/ScanMeow")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: error("MediaStore insert failed")
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("openOutputStream failed")
            values.clear()
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM),
                "ScanMeow",
            )
            dir.mkdirs()
            java.io.File(dir, fileName).writeBytes(bytes)
        }
    }

private suspend fun savePdfToDownloads(context: android.content.Context, bytes: ByteArray, fileName: String) =
    withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/ScanMeow")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: error("MediaStore insert failed")
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("openOutputStream failed")
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "ScanMeow",
            )
            dir.mkdirs()
            java.io.File(dir, fileName).writeBytes(bytes)
        }
    }

private fun ByteArray.withExifRotation(): ByteArray {
    val degrees = java.io.ByteArrayInputStream(this).use { stream ->
        when (ExifInterface(stream).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )) {
            ExifInterface.ORIENTATION_ROTATE_90  -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }
    if (degrees == 0f) return this
    val src = BitmapFactory.decodeByteArray(this, 0, size) ?: return this
    val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height,
        Matrix().apply { postRotate(degrees) }, true)
    return ByteArrayOutputStream().also {
        rotated.compress(Bitmap.CompressFormat.JPEG, 95, it)
    }.toByteArray()
}

@Preview(showBackground = true)
@Composable
fun MainHomeScreenPreview() {
    ScanMeowTheme {
        MainHomeScreen(
            cloudDocuments = emptyList(),
            recentDocumentsExpanded = false,
            onToggleRecentDocumentsExpanded = {},
        )
    }
}
