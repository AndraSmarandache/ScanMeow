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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.project.scanmeow.data.model.Document
import com.project.scanmeow.ui.components.ScanMeowTopBar
import com.project.scanmeow.ui.theme.BackgroundWhite
import com.project.scanmeow.ui.theme.ScanBlue
import com.project.scanmeow.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DocumentViewScreen(
    document: Document?,
    onBack: () -> Unit,
    onShare: (Document) -> Unit,
    onDelete: (Document) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundWhite)
    ) {
        ScanMeowTopBar(showBack = true, onBackClick = onBack)

        if (document == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ScanBlue)
            }
            return@Column
        }

        // Document info
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(document.name, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(4.dp))
            val dateStr = SimpleDateFormat("d/M/yyyy, HH:mm", Locale.getDefault())
                .format(Date(document.createdAt))
            val sizeStr = if (document.sizeBytes > 0) "${document.sizeBytes / 1024} KB" else "—"
            Text("Scanned $dateStr · $sizeStr", fontSize = 13.sp, color = TextSecondary)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Document preview
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
                .background(Color(0xFFF0F0F0), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
            var loading by remember { mutableStateOf(true) }
            LaunchedEffect(document.imagePath) {
                loading = true
                bitmap = withContext(Dispatchers.IO) {
                    val opts = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                        BitmapFactory.decodeFile(document.imagePath, this)
                        var sample = 1
                        while (outWidth / sample > 1080 || outHeight / sample > 1920) sample *= 2
                        inSampleSize = sample
                        inJustDecodeBounds = false
                    }
                    BitmapFactory.decodeFile(document.imagePath, opts)?.asImageBitmap()
                }
                loading = false
            }
            when {
                loading -> CircularProgressIndicator(color = ScanBlue)
                bitmap != null -> Image(
                    bitmap = bitmap!!,
                    contentDescription = document.name,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    contentScale = ContentScale.Fit
                )
                else -> Text("Preview not available", color = Color.Gray)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Cancel", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            Button(
                onClick = { onShare(document) },
                modifier = Modifier.weight(1f).height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ScanBlue),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Share", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
