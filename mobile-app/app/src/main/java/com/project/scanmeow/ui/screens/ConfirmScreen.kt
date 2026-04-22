package com.project.scanmeow.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.project.scanmeow.ui.components.ScanMeowTopBar
import com.project.scanmeow.ui.theme.BackgroundWhite
import com.project.scanmeow.ui.theme.ScanBlue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ConfirmScreen(
    imagePath: String?,
    onRetake: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var docName by remember { mutableStateOf("Scanned document") }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(imagePath) {
        loading = true
        bitmap = if (imagePath != null) {
            withContext(Dispatchers.IO) {
                val opts = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                    BitmapFactory.decodeFile(imagePath, this)
                    inSampleSize = calcSampleSize(outWidth, outHeight, 1080, 1920)
                    inJustDecodeBounds = false
                }
                BitmapFactory.decodeFile(imagePath, opts)?.asImageBitmap()
            }
        } else null
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundWhite)
    ) {
        ScanMeowTopBar(showBack = true, onBackClick = onRetake)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp)
                .background(Color(0xFFF0F0F0), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            when {
                loading -> CircularProgressIndicator()
                bitmap != null -> Image(
                    bitmap = bitmap!!,
                    contentDescription = "Scanned document",
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    contentScale = ContentScale.Fit
                )
                else -> Text("Could not load image", color = Color.Gray)
            }
        }

        OutlinedTextField(
            value = docName,
            onValueChange = { docName = it },
            label = { Text("Document name") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onRetake,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Retake", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            Button(
                onClick = { if (imagePath != null) onConfirm(docName) },
                modifier = Modifier.weight(1f).height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ScanBlue),
                shape = RoundedCornerShape(10.dp),
                enabled = imagePath != null && !loading
            ) {
                Text("Confirm", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

private fun calcSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
    var sample = 1
    if (height > reqHeight || width > reqWidth) {
        val halfH = height / 2
        val halfW = width / 2
        while (halfH / sample >= reqHeight && halfW / sample >= reqWidth) sample *= 2
    }
    return sample
}
