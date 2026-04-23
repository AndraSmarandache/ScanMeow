package com.project.scanmeow.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import kotlin.math.min

// Must match _SCANMEOW_BT_CHANNEL in the desktop app
private const val BT_CHANNEL = 6

suspend fun sendPdfViaBluetooth(
    device: BluetoothDevice,
    pdfBytes: ByteArray,
    fileName: String,
    accessToken: String,
) = withContext(Dispatchers.IO) {
    BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()

    // Use a direct RFCOMM channel connection so no SDP lookup is needed on the PC side
    @Suppress("DiscouragedPrivateApi")
    val socket = device::class.java
        .getMethod("createRfcommSocket", Int::class.java)
        .invoke(device, BT_CHANNEL) as BluetoothSocket

    try {
        socket.connect()
        DataOutputStream(socket.outputStream).use { dos ->
            dos.writeBytes("SMK2")
            val nameBytes = fileName.toByteArray(Charsets.UTF_8)
            dos.writeShort(nameBytes.size)
            dos.write(nameBytes)
            val tokenBytes = accessToken.toByteArray(Charsets.UTF_8)
            dos.writeShort(tokenBytes.size)
            dos.write(tokenBytes)
            dos.writeLong(pdfBytes.size.toLong())
            var offset = 0
            val chunk = 32 * 1024
            while (offset < pdfBytes.size) {
                val end = min(offset + chunk, pdfBytes.size)
                dos.write(pdfBytes, offset, end - offset)
                offset = end
            }
            dos.flush()
        }
    } finally {
        runCatching { socket.close() }
    }
}

@Composable
fun BluetoothDevicePickerDialog(
    onDismiss: () -> Unit,
    onDeviceSelected: (BluetoothDevice) -> Unit,
) {
    val adapter = BluetoothAdapter.getDefaultAdapter()
    val paired: List<BluetoothDevice> = remember {
        if (adapter == null) emptyList()
        else runCatching {
            @Suppress("MissingPermission")
            adapter.bondedDevices?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select PC") },
        text = {
            if (paired.isEmpty()) {
                Text("No paired Bluetooth devices found.\nPair your PC first in Android Bluetooth settings.")
            } else {
                LazyColumn {
                    items(paired) { device ->
                        val name = runCatching {
                            @Suppress("MissingPermission") device.name ?: device.address
                        }.getOrDefault(device.address)
                        ListItem(
                            headlineContent = { Text(name) },
                            supportingContent = {
                                Text(device.address, style = MaterialTheme.typography.labelSmall)
                            },
                            modifier = Modifier.clickable { onDeviceSelected(device) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
