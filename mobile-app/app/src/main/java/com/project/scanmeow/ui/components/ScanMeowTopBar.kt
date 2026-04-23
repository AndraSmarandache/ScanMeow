package com.project.scanmeow.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.project.scanmeow.R
import com.project.scanmeow.ui.theme.MeowOrange
import com.project.scanmeow.ui.theme.ScanBlue

@Composable
fun ScanMeowTopBar(
    showBack: Boolean = false,
    onBackClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showBack) {
            IconButton(onClick = onBackClick, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = ScanBlue
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Image(
            painter = painterResource(id = R.drawable.ic_scanmeow_logo),
            contentDescription = "ScanMeow Logo",
            modifier = Modifier.size(40.dp)
        )

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = ScanBlue, fontWeight = FontWeight.Bold, fontSize = 22.sp)) {
                    append("Scan")
                }
                withStyle(SpanStyle(color = MeowOrange, fontWeight = FontWeight.Bold, fontSize = 22.sp)) {
                    append("Meow")
                }
            }
        )
    }
}
