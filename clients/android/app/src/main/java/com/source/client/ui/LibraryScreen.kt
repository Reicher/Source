package com.source.client.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
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
    onRemoveFromDevice: (String) -> Unit,
    onDeleteFromSource: (String) -> Unit,
    onOpen: (String) -> Unit,
    onOpenKnowledge: (String) -> Unit,
    onFeedbackShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImport)
    }
    val snackbar = remember { SnackbarHostState() }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<LibraryUiItem?>(null) }
    LaunchedEffect(state.feedback) {
        state.feedback?.let { feedback ->
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
            LazyColumn(Modifier.fillMaxSize()) {
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
                items(state.items, key = LibraryUiItem::id) { item ->
                    LibraryItemRow(
                        item = item,
                        expanded = expandedId == item.id,
                        onToggle = { expandedId = if (expandedId == item.id) null else item.id },
                        onOpen = { onOpen(item.id) },
                        onOpenKnowledge = { onOpenKnowledge(item.id) },
                        onRemoveFromDevice = { onRemoveFromDevice(item.id) },
                        onDeleteFromSource = { deleting = item },
                    )
                    HorizontalDivider(color = Ink.copy(alpha = .08f))
                }
                item(key = "bottom-space") { Spacer(Modifier.height(88.dp)) }
            }
        }
        FloatingActionButton(
            onClick = { picker.launch(SUPPORTED_TEXT_MIME_TYPES) },
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
            title = { Text(stringResource(R.string.library_delete_title, item.filename)) },
            text = { Text(stringResource(R.string.library_delete_location_message)) },
            confirmButton = {
                Column(Modifier.fillMaxWidth()) {
                    if (item.canRemoveFromDevice) {
                        OutlinedButton(
                            onClick = {
                                deleting = null
                                onRemoveFromDevice(item.id)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.library_remove_from_device)) }
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(
                        onClick = {
                            deleting = null
                            onDeleteFromSource(item.id)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(R.string.library_delete_from_source)) }
                    TextButton(
                        onClick = { deleting = null },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }
}

@Composable
private fun LibraryItemRow(
    item: LibraryUiItem,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onOpenKnowledge: () -> Unit,
    onRemoveFromDevice: () -> Unit,
    onDeleteFromSource: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = typeIcon(item),
                contentDescription = null,
                modifier = Modifier.size(21.dp),
                tint = Moss.copy(alpha = .78f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                item.filename,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(10.dp))
            BronzeStatusIndicator(item.bronzeStatus)
            Spacer(Modifier.width(7.dp))
            KnowledgeStatusIndicator(item.knowledgeStatus, item.knowledgeProgress)
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 45.dp, end = 8.dp, bottom = 9.dp)) {
                DetailLine(stringResource(R.string.library_size), formatBytes(item.byteCount))
                DetailLine(stringResource(R.string.library_added), formatDate(item.createdAtMillis))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.previewKind != null && item.localAvailable) {
                        Button(onClick = onOpen) { Text(stringResource(R.string.library_view)) }
                        Spacer(Modifier.width(8.dp))
                    }
                    OutlinedButton(onClick = onOpenKnowledge) { Text(stringResource(R.string.knowledge)) }
                    if (item.canDeleteFromSource) {
                        IconButton(onClick = onDeleteFromSource) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, modifier = Modifier.width(92.dp), style = MaterialTheme.typography.bodySmall, color = Ink.copy(.5f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = Ink.copy(.72f))
    }
}

@Composable
private fun BronzeStatusIndicator(status: BronzeNodeStatus) {
    val description = stringResource(when (status) {
        BronzeNodeStatus.NOT_ON_NODE -> R.string.library_bronze_not_on_node
        BronzeNodeStatus.UPLOADING -> R.string.library_bronze_uploading
        BronzeNodeStatus.STORED -> R.string.library_bronze_stored
    })
    Box(
        Modifier.size(width = 26.dp, height = 20.dp).clearAndSetSemantics { contentDescription = description },
    ) {
        Icon(
            Icons.Outlined.Storage,
            contentDescription = null,
            modifier = Modifier.size(19.dp).align(Alignment.CenterStart),
            tint = if (status == BronzeNodeStatus.STORED) Moss else Ink.copy(alpha = .42f),
        )
        val badge = when (status) {
            BronzeNodeStatus.NOT_ON_NODE -> null
            BronzeNodeStatus.UPLOADING -> Icons.Outlined.ArrowUpward
            BronzeNodeStatus.STORED -> Icons.Outlined.Check
        }
        badge?.let {
            Icon(
                it,
                contentDescription = null,
                modifier = Modifier.size(12.dp).align(Alignment.BottomEnd),
                tint = if (status == BronzeNodeStatus.STORED) Moss else Color(0xFF9A6A24),
            )
        }
    }
}

@Composable
private fun KnowledgeStatusIndicator(status: KnowledgeStatus, progress: SilverBatchProgress?) {
    val description = stringResource(when (status) {
        KnowledgeStatus.WAITING -> R.string.knowledge_waiting
        KnowledgeStatus.PROCESSING -> R.string.knowledge_processing
        KnowledgeStatus.SYNCING_TO_CLIENT -> R.string.knowledge_syncing_to_client
        KnowledgeStatus.CURRENT -> R.string.knowledge_current_local
        KnowledgeStatus.ERROR -> R.string.knowledge_error
    })
    Row(
        modifier = Modifier.height(20.dp).clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (status == KnowledgeStatus.PROCESSING && progress != null) {
                "✦ ${progress.completedBatches}/${progress.totalBatches}"
            } else {
                "✦"
            },
            style = MaterialTheme.typography.labelMedium,
            color = when (status) {
                KnowledgeStatus.CURRENT -> Moss
                KnowledgeStatus.ERROR -> MaterialTheme.colorScheme.error
                KnowledgeStatus.PROCESSING, KnowledgeStatus.SYNCING_TO_CLIENT -> Color(0xFF9A6A24)
                KnowledgeStatus.WAITING -> Ink.copy(alpha = .36f)
            },
        )
        val badge = when (status) {
            KnowledgeStatus.SYNCING_TO_CLIENT -> Icons.Outlined.ArrowDownward
            KnowledgeStatus.CURRENT -> Icons.Outlined.Check
            KnowledgeStatus.ERROR -> Icons.Outlined.ErrorOutline
            else -> null
        }
        badge?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(12.dp))
        }
    }
}

private fun typeIcon(item: LibraryUiItem): ImageVector {
    return when {
        item.previewKind == LibraryPreviewKind.TEXT || item.sourceType == "conversation" -> Icons.Outlined.Description
        else -> Icons.AutoMirrored.Outlined.InsertDriveFile
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun LibraryPreviewScreen(state: LibraryPreviewUiState, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.filename, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            is LibraryPreviewUiState.Loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = Moss) }
            is LibraryPreviewUiState.Failed -> Box(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) { Text(state.message, color = MaterialTheme.colorScheme.error) }
            is LibraryPreviewUiState.Ready -> when (val content = state.content) {
                is LibraryPreviewContent.Text -> SelectionContainer {
                    Text(
                        content.value,
                        modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
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
