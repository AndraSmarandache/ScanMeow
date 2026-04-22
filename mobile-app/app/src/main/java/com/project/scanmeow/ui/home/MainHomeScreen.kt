package com.project.scanmeow.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.project.scanmeow.R
import com.project.scanmeow.data.UserCloudDocument
import com.project.scanmeow.ui.theme.ScanBlue
import com.project.scanmeow.ui.theme.ScanMeowTheme
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CloudDocumentRow(
    doc: UserCloudDocument,
    selectionMode: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggleSelected: () -> Unit,
    onBeginSelection: () -> Unit,
    modifier: Modifier = Modifier,
    showChevron: Boolean = true,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (selectionMode) onToggleSelected() else onOpen()
                },
                onLongClick = {
                    if (selectionMode) onToggleSelected() else onBeginSelection()
                },
            ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .then(
                            if (selected) {
                                Modifier.background(ScanBlue, CircleShape)
                            } else {
                                Modifier.border(BorderStroke(2.dp, ScanBlue), CircleShape)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
            }
            Icon(
                imageVector = Icons.Filled.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = doc.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${formatDocSizePlaceholder()} · ${formatRelativeTime(doc.createdAtMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showChevron && !selectionMode) {
                IconButton(onClick = onOpen) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                        contentDescription = stringResource(R.string.content_desc_open_document),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private fun formatDocSizePlaceholder(): String = "PDF"

private fun formatRelativeTime(millis: Long): String {
    val now = System.currentTimeMillis()
    val diff = (now - millis).coerceAtLeast(0)
    val mins = TimeUnit.MILLISECONDS.toMinutes(diff)
    if (mins < 1) return "Just now"
    if (mins < 60) return "$mins min ago"
    val hrs = TimeUnit.MILLISECONDS.toHours(diff)
    if (hrs < 24) return "$hrs h ago"
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    if (days < 7) return "$days d ago"
    val weeks = days / 7
    return "$weeks w ago"
}

private enum class CloudDocSort {
    NAME_ASC,
    NAME_DESC,
    DATE_NEWEST,
    DATE_OLDEST,
}

private fun List<UserCloudDocument>.sortedForHome(sort: CloudDocSort): List<UserCloudDocument> =
    when (sort) {
        CloudDocSort.NAME_ASC -> sortedBy { it.fileName.lowercase(Locale.getDefault()) }
        CloudDocSort.NAME_DESC -> sortedByDescending { it.fileName.lowercase(Locale.getDefault()) }
        CloudDocSort.DATE_NEWEST -> sortedByDescending { it.createdAtMillis }
        CloudDocSort.DATE_OLDEST -> sortedBy { it.createdAtMillis }
    }

@Composable
fun MainHomeScreen(
    modifier: Modifier = Modifier,
    cloudDocuments: List<UserCloudDocument>,
    recentDocumentsExpanded: Boolean,
    onToggleRecentDocumentsExpanded: () -> Unit,
    bluetoothModeForPc: Boolean,
    onBluetoothModeChange: (Boolean) -> Unit,
    onScanDocumentClick: () -> Unit = {},
    onDocumentClick: (UserCloudDocument) -> Unit = {},
    onShareCloudDocuments: suspend (List<UserCloudDocument>) -> Unit = {},
    onDeleteCloudDocuments: suspend (List<UserCloudDocument>) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var docSelectionMode by remember { mutableStateOf(false) }
    var selectedDocIds by remember { mutableStateOf(setOf<String>()) }
    var pendingDelete by remember { mutableStateOf<List<UserCloudDocument>?>(null) }
    var cloudDocSort by remember { mutableStateOf(CloudDocSort.DATE_NEWEST) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    val sortedCloudDocuments = remember(cloudDocuments, cloudDocSort) {
        cloudDocuments.sortedForHome(cloudDocSort)
    }

    val recentLimit = 5
    val exitSelection = {
        docSelectionMode = false
        selectedDocIds = emptySet()
    }

    pendingDelete?.let { toDelete ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.action_delete)) },
            text = {
                Text(
                    stringResource(R.string.delete_n_docs_confirm, toDelete.size),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        val list = toDelete
                        scope.launch {
                            runCatching {
                                onDeleteCloudDocuments(list)
                            }.onSuccess {
                                exitSelection()
                            }.onFailure {
                                android.widget.Toast.makeText(
                                    context,
                                    it.message ?: "Delete failed",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    val docListScroll = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        // Pinned header: logo and scan action do not scroll with the document list.
        ComposeImage(
            painter = painterResource(R.drawable.app_logo),
            contentDescription = stringResource(R.string.content_desc_logo),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 120.dp),
            contentScale = ContentScale.Fit,
        )

        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Button(
                onClick = onScanDocumentClick,
                modifier = Modifier.heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ScanBlue,
                    contentColor = Color.White,
                ),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(
                    imageVector = Icons.Filled.Image,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(text = stringResource(R.string.scan_document))
            }
        }

        Spacer(Modifier.height(20.dp))

        // Section title row stays pinned; only the document list scrolls below.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(R.string.recent_documents),
                    style = MaterialTheme.typography.titleMedium,
                )
                Box {
                    IconButton(onClick = { sortMenuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Filled.ExpandMore,
                            contentDescription = stringResource(R.string.sort_documents),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    DropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sort_name_az)) },
                            onClick = {
                                cloudDocSort = CloudDocSort.NAME_ASC
                                sortMenuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sort_name_za)) },
                            onClick = {
                                cloudDocSort = CloudDocSort.NAME_DESC
                                sortMenuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sort_date_newest)) },
                            onClick = {
                                cloudDocSort = CloudDocSort.DATE_NEWEST
                                sortMenuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sort_date_oldest)) },
                            onClick = {
                                cloudDocSort = CloudDocSort.DATE_OLDEST
                                sortMenuExpanded = false
                            },
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.wrapContentWidth(),
            ) {
                if (docSelectionMode) {
                    TextButton(onClick = exitSelection) {
                        Text(stringResource(R.string.selection_done))
                    }
                    IconButton(
                        onClick = {
                            val docs = sortedCloudDocuments.filter { it.docId in selectedDocIds }
                            if (docs.isNotEmpty()) {
                                scope.launch {
                                    runCatching { onShareCloudDocuments(docs) }.onFailure { e ->
                                        android.widget.Toast.makeText(
                                            context,
                                            e.message ?: "Share failed",
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            }
                        },
                        enabled = selectedDocIds.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = stringResource(R.string.content_desc_selection_share),
                            tint = if (selectedDocIds.isNotEmpty()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        )
                    }
                    IconButton(
                        onClick = {
                            val docs = sortedCloudDocuments.filter { it.docId in selectedDocIds }
                            if (docs.isNotEmpty()) pendingDelete = docs
                        },
                        enabled = selectedDocIds.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.content_desc_selection_delete),
                            tint = if (selectedDocIds.isNotEmpty()) {
                                Color(0xFFC62828)
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        )
                    }
                        } else if (sortedCloudDocuments.size > recentLimit) {
                    TextButton(onClick = onToggleRecentDocumentsExpanded) {
                        Text(
                            text = if (recentDocumentsExpanded) {
                                stringResource(R.string.show_less)
                            } else {
                                stringResource(R.string.see_all)
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        val recent = if (recentDocumentsExpanded) {
            sortedCloudDocuments
        } else {
            sortedCloudDocuments.take(recentLimit)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(docListScroll),
        ) {
            if (recent.isEmpty()) {
                Text(
                    text = stringResource(R.string.documents_empty_cloud),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                recent.forEach { doc ->
                    CloudDocumentRow(
                        doc = doc,
                        selectionMode = docSelectionMode,
                        selected = doc.docId in selectedDocIds,
                        onOpen = { onDocumentClick(doc) },
                        onToggleSelected = {
                            selectedDocIds =
                                if (doc.docId in selectedDocIds) {
                                    selectedDocIds - doc.docId
                                } else {
                                    selectedDocIds + doc.docId
                                }
                        },
                        onBeginSelection = {
                            docSelectionMode = true
                            selectedDocIds = selectedDocIds + doc.docId
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        BluetoothRow(
            bluetoothModeForPc = bluetoothModeForPc,
            onBluetoothModeChange = onBluetoothModeChange,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun BluetoothRow(
    bluetoothModeForPc: Boolean,
    onBluetoothModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Filled.Bluetooth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.bluetooth),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (bluetoothModeForPc) {
                            stringResource(R.string.bluetooth_mode_bluetooth)
                        } else {
                            stringResource(R.string.bluetooth_mode_tcp)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = bluetoothModeForPc,
                onCheckedChange = onBluetoothModeChange,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MainHomeScreenPreview() {
    ScanMeowTheme {
        MainHomeScreen(
            cloudDocuments = emptyList(),
            recentDocumentsExpanded = false,
            onToggleRecentDocumentsExpanded = {},
            bluetoothModeForPc = false,
            onBluetoothModeChange = {},
        )
    }
}
