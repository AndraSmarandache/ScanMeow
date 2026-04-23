package com.project.scanmeow.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HomeScreen(
    recentDocuments: List<Document>,
    isBluetoothConnected: Boolean,
    onScanClick: () -> Unit,
    onDocumentClick: (Document) -> Unit,
    onSeeAllClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundWhite)
    ) {
        ScanMeowTopBar()

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onScanClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ScanBlue),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text("Scanare document", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Recent documents", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary)
            Text(
                "See all >",
                color = ScanBlue,
                fontSize = 14.sp,
                modifier = Modifier.clickable { onSeeAllClick() }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (recentDocuments.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No documents yet.\nTap 'Scanare document' to start.",
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(recentDocuments) { doc ->
                    DocumentListItem(document = doc, onClick = { onDocumentClick(doc) })
                }
            }
        }

        BluetoothStatusBar(isConnected = isBluetoothConnected)
    }
}

@Composable
fun DocumentListItem(document: Document, onClick: () -> Unit) {
    val dateStr = SimpleDateFormat("d/M/yyyy, HH:mm", Locale.getDefault())
        .format(Date(document.createdAt))
    val sizeStr = if (document.sizeBytes > 0) "${document.sizeBytes / 1024} KB" else "—"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceGray)
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(ListItemBlue),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = ScanBlue, modifier = Modifier.size(26.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(document.name, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = TextPrimary)
            Text("$dateStr · $sizeStr", fontSize = 12.sp, color = TextSecondary)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary)
    }
}

@Composable
fun BluetoothStatusBar(isConnected: Boolean) {
    val color = if (isConnected) ConnectedGreen else DisconnectedGray
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceGray)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Bluetooth, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            if (isConnected) "Bluetooth: Connected" else "Bluetooth: Disconnected",
            fontSize = 14.sp,
            color = color,
            fontWeight = FontWeight.Medium
        )
        if (isConnected) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(ConnectedGreen))
        }
    }
}
