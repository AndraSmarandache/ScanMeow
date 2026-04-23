package com.project.scanmeow.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.project.scanmeow.data.model.Document
import com.project.scanmeow.ui.components.ScanMeowTopBar
import com.project.scanmeow.ui.theme.*

@Composable
fun BluetoothOffScreen(
    onEnableBluetooth: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundWhite),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ScanMeowTopBar(showBack = true, onBackClick = onBack)

        Spacer(modifier = Modifier.weight(1f))

        Box(
            modifier = Modifier
                .size(110.dp)
                .clip(CircleShape)
                .background(ListItemBlue),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.BluetoothDisabled,
                contentDescription = null,
                tint = ScanBlue,
                modifier = Modifier.size(60.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("Bluetooth is off", fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = TextPrimary)

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "Enable Bluetooth to send your document\nto the desktop app.",
            color = TextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onEnableBluetooth,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp)
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ScanBlue),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Enable Bluetooth", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
fun SendingScreen(
    document: Document?,
    progress: Float,
    onCancel: () -> Unit
) {
    val docName = document?.name ?: "Untitled document"
    val sizeStr = if ((document?.sizeBytes ?: 0L) > 0) "${(document!!.sizeBytes) / 1024} KB" else "—"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundWhite),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ScanMeowTopBar()

        Spacer(modifier = Modifier.weight(1f))

        Box(
            modifier = Modifier
                .size(110.dp)
                .clip(CircleShape)
                .background(ListItemBlue),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Send,
                contentDescription = null,
                tint = ScanBlue,
                modifier = Modifier.size(54.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "Sending document to desktop...",
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            color = TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "$docName · $sizeStr",
            fontSize = 13.sp,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(24.dp))

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = ScanBlue,
            trackColor = ListItemBlue
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "${(progress * 100).toInt()}%",
            fontSize = 13.sp,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp)
                .height(50.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Cancel", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}
