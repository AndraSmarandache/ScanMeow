package com.project.scanmeow.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.project.scanmeow.ui.theme.OverlayBlue
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ScannerScreen(
    onImageCaptured: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (hasCameraPermission) {
        CameraContent(onImageCaptured = onImageCaptured, onBack = onBack)
    } else {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Text("Camera permission required", color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant Permission")
                }
            }
        }
    }
}

@Composable
private fun CameraContent(
    onImageCaptured: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val tmp = withContext(Dispatchers.IO) {
                val f = File(context.cacheDir, "gallery_${System.currentTimeMillis()}.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    f.outputStream().use { output -> input.copyTo(output) }
                }
                f
            }
            if (tmp.exists() && tmp.length() > 0) onImageCaptured(tmp.absolutePath)
        }
    }

    var flashEnabled by remember { mutableStateOf(false) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val cameraRef = remember { mutableStateOf<Camera?>(null) }
    val previewViewRef = remember { mutableStateOf<PreviewView?>(null) }

    var isCapturing by remember { mutableStateOf(false) }
    val flashAlpha = remember { Animatable(0f) }
    val buttonScale by animateFloatAsState(
        targetValue = if (isCapturing) 0.82f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "captureScale",
    )

    // Total height occupied by the bottom bar (controls + nav bar)
    val controlsHeight = 100.dp
    val bottomBarTotalPx = with(density) { (controlsHeight + navBarHeight).toPx() }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // Camera preview
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                previewViewRef.value = previewView
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        android.util.Size(1920, 1440),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                    )
                                )
                                .build()
                        )
                        .build()
                    imageCapture = capture
                    try {
                        cameraProvider.unbindAll()
                        val cam = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            capture
                        )
                        cameraRef.value = cam
                    } catch (e: Exception) {
                        Log.e("ScannerScreen", "Camera bind failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Tap-to-focus overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val pv = previewViewRef.value ?: return@detectTapGestures
                        val cam = cameraRef.value ?: return@detectTapGestures
                        val point = pv.meteringPointFactory.createPoint(offset.x, offset.y)
                        val action = FocusMeteringAction.Builder(point).build()
                        cam.cameraControl.startFocusAndMetering(action)
                    }
                }
        )

        // Document corner overlay
        Canvas(modifier = Modifier.fillMaxSize()) {
            val padding = 28.dp.toPx()
            val cornerLen = 44.dp.toPx()
            val strokeW = 4.dp.toPx()
            val left = padding
            val top = size.height * 0.10f
            val right = size.width - padding
            val bottom = size.height - bottomBarTotalPx - 12.dp.toPx()
            val color = OverlayBlue

            drawRect(Color.Black.copy(alpha = 0.5f), size = Size(size.width, top))
            drawRect(Color.Black.copy(alpha = 0.5f), topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
            drawRect(Color.Black.copy(alpha = 0.5f), topLeft = Offset(0f, top), size = Size(left, bottom - top))
            drawRect(Color.Black.copy(alpha = 0.5f), topLeft = Offset(right, top), size = Size(size.width - right, bottom - top))

            // Top-left
            drawLine(color, Offset(left, top + cornerLen), Offset(left, top), strokeWidth = strokeW, cap = StrokeCap.Round)
            drawLine(color, Offset(left, top), Offset(left + cornerLen, top), strokeWidth = strokeW, cap = StrokeCap.Round)
            // Top-right
            drawLine(color, Offset(right - cornerLen, top), Offset(right, top), strokeWidth = strokeW, cap = StrokeCap.Round)
            drawLine(color, Offset(right, top), Offset(right, top + cornerLen), strokeWidth = strokeW, cap = StrokeCap.Round)
            // Bottom-left
            drawLine(color, Offset(left, bottom - cornerLen), Offset(left, bottom), strokeWidth = strokeW, cap = StrokeCap.Round)
            drawLine(color, Offset(left, bottom), Offset(left + cornerLen, bottom), strokeWidth = strokeW, cap = StrokeCap.Round)
            // Bottom-right
            drawLine(color, Offset(right - cornerLen, bottom), Offset(right, bottom), strokeWidth = strokeW, cap = StrokeCap.Round)
            drawLine(color, Offset(right, bottom), Offset(right, bottom - cornerLen), strokeWidth = strokeW, cap = StrokeCap.Round)
        }

        // Camera shutter flash
        val alpha = flashAlpha.value
        if (alpha > 0f) {
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = alpha)))
        }

        // Bottom controls bar — sits above the system navigation bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black)
                .navigationBarsPadding()
                .height(controlsHeight)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 40.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Flash button
                IconButton(
                    onClick = {
                        flashEnabled = !flashEnabled
                        imageCapture?.flashMode = if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                    },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        if (flashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "Flash",
                        tint = if (flashEnabled) Color(0xFFFFD600) else Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Capture button — white circle with blue ring
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .scale(buttonScale)
                        .clip(CircleShape)
                        .background(if (isCapturing) OverlayBlue.copy(alpha = 0.6f) else OverlayBlue),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = {
                            if (!isCapturing) {
                                isCapturing = true
                                scope.launch {
                                    flashAlpha.snapTo(0.92f)
                                    flashAlpha.animateTo(0f, animationSpec = tween(280))
                                }
                                takePhoto(
                                    context = context,
                                    imageCapture = imageCapture,
                                    onSuccess = { path ->
                                        isCapturing = false
                                        onImageCaptured(path)
                                    },
                                    onError = { isCapturing = false },
                                )
                            }
                        },
                        modifier = Modifier.size(80.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                    }
                }

                // Gallery button
                IconButton(
                    onClick = { galleryLauncher.launch("image/*") },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = "Gallery",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

private fun takePhoto(
    context: Context,
    imageCapture: ImageCapture?,
    onSuccess: (String) -> Unit,
    onError: () -> Unit = {},
) {
    if (imageCapture == null) { onError(); return }
    val photoFile = File(
        context.filesDir,
        "scan_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
    )
    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                onSuccess(photoFile.absolutePath)
            }
            override fun onError(exc: ImageCaptureException) {
                Log.e("ScannerScreen", "Capture failed: ${exc.message}", exc)
                onError()
            }
        }
    )
}
