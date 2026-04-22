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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.project.scanmeow.ui.components.ScanMeowTopBar
import com.project.scanmeow.ui.theme.BackgroundWhite
import com.project.scanmeow.ui.theme.ScanBlue

@Composable
fun ConfirmScreen(
    imagePath: String?,
    onRetake: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var docName by remember { mutableStateOf("Scanned document") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundWhite)
    ) {
        ScanMeowTopBar(showBack = true, onBackClick = onRetake)

        // Document preview
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp)
                .background(Color(0xFFF0F0F0), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (imagePath != null) {
                val bitmap = remember(imagePath) {
                    BitmapFactory.decodeFile(imagePath)?.asImageBitmap()
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Scanned document",
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text("Could not load image", color = Color.Gray)
                }
            } else {
                Text("No image captured", color = Color.Gray)
            }
        }

        // Document name input
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

        // Action buttons
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
                enabled = imagePath != null
            ) {
                Text("Confirm", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
