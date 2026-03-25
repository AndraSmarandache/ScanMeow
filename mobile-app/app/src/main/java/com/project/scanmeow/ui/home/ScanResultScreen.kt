package com.project.scanmeow.ui.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.project.scanmeow.ui.theme.ScanBlue

@Composable
fun ScanResultScreen(
    scannedJpeg: ByteArray,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    doneLabel: String = "Done",
) {
    val bitmap = remember(scannedJpeg) {
        BitmapFactory.decodeByteArray(scannedJpeg, 0, scannedJpeg.size)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text("Could not decode server image.")
            }
        }
        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = ScanBlue,
                contentColor = Color.White,
            ),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(doneLabel)
        }
    }
}
