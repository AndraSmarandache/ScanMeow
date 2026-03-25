package com.project.scanmeow.ui.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.project.scanmeow.R
import com.project.scanmeow.ui.theme.ScanBlue

private val TopBarGray = Color(0xFFD4D4D4)
private val MainAreaDarkGray = Color(0xFF3D3D3D)

/**
 * Aligned preview: light top bar with back, dark main area with image, Retake / Confirm below image.
 */
@Composable
fun ScanAlignedReviewScreen(
    alignedJpeg: ByteArray,
    isSubmitting: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(alignedJpeg) {
        BitmapFactory.decodeByteArray(alignedJpeg, 0, alignedJpeg.size)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(Modifier.fillMaxSize()) {
            Surface(
                color = TopBarGray,
                shadowElevation = 0.dp,
                tonalElevation = 0.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onCancel,
                        enabled = !isSubmitting,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.content_desc_back),
                            tint = Color(0xFF1C1C1C),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MainAreaDarkGray)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.error_invalid_image),
                            color = Color.White,
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = onCancel,
                        enabled = !isSubmitting,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Black,
                            contentColor = Color.White,
                        ),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(stringResource(R.string.action_retake))
                    }
                    Button(
                        onClick = onConfirm,
                        enabled = !isSubmitting,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ScanBlue,
                            contentColor = Color.White,
                        ),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(stringResource(R.string.action_confirm))
                    }
                }
            }
        }

        if (isSubmitting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        text = stringResource(R.string.scan_loading_final),
                        color = Color.White,
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}
