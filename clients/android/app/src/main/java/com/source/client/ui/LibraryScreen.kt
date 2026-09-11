package com.source.client.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.source.client.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun LibraryScreen(
    state: LibraryUiState,
    onImport: (android.net.Uri) -> Unit,
    onDelete: (String) -> Unit,
    onFeedbackShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImport)
    }
    val snackbar = remember { SnackbarHostState() }
    var deleting by remember { mutableStateOf<LibraryUiItem?>(null) }
    LaunchedEffect(state.feedback) {
        state.feedback?.let { feedback ->
            // Consume the one-shot event before this suspends. Navigating away
            // cancels showSnackbar, but must not leave the event to replay.
            onFeedbackShown()
            snackbar.showSnackbar(feedback)
        }
    }

    Box(modifier) {
        if (state.items.isEmpty() && !state.importing) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.library_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.library_empty_description),
                    color = Ink.copy(alpha = .58f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.importing) {
                    item(key = "importing") {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Moss)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.library_importing), color = Ink.copy(alpha = .65f))
                        }
                    }
                }
                items(state.items, key = LibraryUiItem::id) { uiItem ->
                    LibraryItemRow(uiItem, onDelete = { deleting = it })
                }
                item(key = "bottom-space") { Spacer(Modifier.height(88.dp)) }
            }
        }
        FloatingActionButton(
            onClick = { picker.launch(arrayOf("*/*")) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 20.dp),
            containerColor = Moss,
            contentColor = Color.White,
        ) {
            if (state.importing) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.library_add_file))
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp))
    }

    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.library_delete_title)) },
            text = { Text(stringResource(R.string.library_delete_message, item.filename)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(item.id)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun LibraryItemRow(item: LibraryUiItem, onDelete: (LibraryUiItem) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Ink.copy(alpha = .045f),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 15.dp, top = 13.dp, bottom = 13.dp, end = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.InsertDriveFile,
                contentDescription = null,
                tint = Moss.copy(alpha = .78f),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.filename,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    formatBytes(item.byteCount),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.copy(alpha = .58f),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatDate(item.createdAtMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink.copy(alpha = .5f),
                    )
                    Spacer(Modifier.width(7.dp))
                    Surface(Modifier.size(7.dp), shape = CircleShape, color = syncColor(item.syncState)) {}
                    Spacer(Modifier.width(5.dp))
                    Text(
                        syncLabel(item.syncState),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink.copy(alpha = .5f),
                    )
                }
            }
            if (item.deletable) {
                IconButton(onClick = { onDelete(item) }) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.library_delete_item, item.filename),
                        tint = Ink.copy(alpha = .55f),
                    )
                }
            }
        }
    }
}

@Composable
private fun syncLabel(state: LibrarySyncState): String = stringResource(
    when (state) {
        LibrarySyncState.LOCAL -> R.string.library_sync_local
        LibrarySyncState.SYNCING -> R.string.library_sync_syncing
        LibrarySyncState.SYNCED -> R.string.library_sync_synced
        LibrarySyncState.FAILED -> R.string.library_sync_failed
    },
)

@Composable
private fun syncColor(state: LibrarySyncState): Color = when (state) {
    LibrarySyncState.SYNCED -> Moss
    LibrarySyncState.FAILED -> MaterialTheme.colorScheme.error
    LibrarySyncState.SYNCING -> Color(0xFF9A6A24)
    LibrarySyncState.LOCAL -> Ink.copy(alpha = .28f)
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit += 1
    }
    return if (value >= 10) String.format(Locale.ROOT, "%.0f %s", value, units[unit])
    else String.format(Locale.ROOT, "%.1f %s", value, units[unit])
}

private fun formatDate(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
