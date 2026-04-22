package com.project.scanmeow.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.project.scanmeow.ui.theme.OverlayBlue
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

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
    var flashEnabled by remember { mutableStateOf(false) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build()
                    imageCapture = capture
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            capture
                        )
                    } catch (e: Exception) {
                        Log.e("ScannerScreen", "Camera bind failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Document corner overlay
        Canvas(modifier = Modifier.fillMaxSize()) {
            val padding = 60.dp.toPx()
            val cornerLen = 40.dp.toPx()
            val strokeW = 4.dp.toPx()
            val left = padding
            val top = size.height * 0.15f
            val right = size.width - padding
            val bottom = size.height * 0.75f
            val color = OverlayBlue

            drawRect(Color.Black.copy(alpha = 0.4f), size = Size(size.width, top))
            drawRect(Color.Black.copy(alpha = 0.4f), topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
            drawRect(Color.Black.copy(alpha = 0.4f), topLeft = Offset(0f, top), size = Size(left, bottom - top))
            drawRect(Color.Black.copy(alpha = 0.4f), topLeft = Offset(right, top), size = Size(size.width - right, bottom - top))

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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    flashEnabled = !flashEnabled
                    imageCapture?.flashMode = if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                },
                modifier = Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f))
            ) {
                Icon(
                    if (flashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = "Flash", tint = Color.White, modifier = Modifier.size(28.dp)
                )
            }

            IconButton(
                onClick = { takePhoto(context, imageCapture, onImageCaptured) },
                modifier = Modifier.size(72.dp).clip(CircleShape).background(OverlayBlue)
            ) {
                Box(modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.White))
            }

            IconButton(
                onClick = { /* gallery */ },
                modifier = Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f))
            ) {
                Icon(Icons.Default.Image, contentDescription = "Gallery", tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
    }
}

private fun takePhoto(
    context: Context,
    imageCapture: ImageCapture?,
    onImageCaptured: (String) -> Unit
) {
    imageCapture ?: return
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
                // Already on main executor — safe to call directly
                onImageCaptured(photoFile.absolutePath)
            }
            override fun onError(exc: ImageCaptureException) {
                Log.e("ScannerScreen", "Capture failed: ${exc.message}", exc)
            }
        }
    )
}
