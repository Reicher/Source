package com.source.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.source.client.R
import com.source.client.model.ChatMessage
import com.source.client.model.ChatRole
import com.source.client.model.DiscoveredNode
import com.source.client.model.NodeConnectionState
import kotlinx.coroutines.launch

@Composable
internal fun MainScreen(
    state: AppScreen.Main,
    connect: (DiscoveredNode) -> Unit,
    retry: () -> Unit,
    send: (String) -> Unit,
    newConversation: () -> Unit,
    cancelInference: () -> Unit,
    logout: () -> Unit,
    selectDestination: (MainDestination) -> Unit,
    importFile: (android.net.Uri) -> Unit,
    deleteLibraryItem: (String) -> Unit,
    clearLibraryFeedback: () -> Unit,
) {
    var draft by rememberSaveable(state.chat.conversationId) { mutableStateOf("") }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var recoveryKeyToShow by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Paper) {
                NavigationBarItem(
                    selected = state.destination == MainDestination.CHAT,
                    onClick = { selectDestination(MainDestination.CHAT) },
                    icon = { Icon(Icons.Outlined.ChatBubbleOutline, null) },
                    label = { Text(stringResource(R.string.chat)) },
                )
                NavigationBarItem(
                    selected = state.destination == MainDestination.LIBRARY,
                    onClick = { selectDestination(MainDestination.LIBRARY) },
                    icon = { Icon(Icons.Outlined.Folder, null) },
                    label = { Text(stringResource(R.string.library)) },
                )
            }
        },
    ) { scaffoldPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(if (state.destination == MainDestination.CHAT) R.string.app_name else R.string.library),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                if (state.destination == MainDestination.CHAT) {
                    TextButton(onClick = newConversation, enabled = !state.chat.busy) {
                        Text(stringResource(R.string.new_conversation))
                    }
                }
                IconButton(onClick = { settingsOpen = true }) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.settings),
                        tint = Ink.copy(alpha = .78f),
                    )
                }
            }
            NodeConnectionSummary(state.status, connect, retry, Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            when (state.destination) {
                MainDestination.CHAT -> {
                    ChatTimeline(state.chat, Modifier.fillMaxWidth().weight(1f))
                    state.chat.error?.let { ErrorText(it) }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(stringResource(R.string.message)) },
                            enabled = !state.chat.busy,
                            maxLines = 5,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                if (draft.isNotBlank()) {
                                    send(draft)
                                    draft = ""
                                }
                            }),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (state.chat.busy) cancelInference() else {
                                    send(draft)
                                    draft = ""
                                }
                            },
                            enabled = state.chat.busy || draft.isNotBlank(),
                            modifier = Modifier.height(56.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(stringResource(if (state.chat.busy) R.string.cancel else R.string.send))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                MainDestination.LIBRARY -> LibraryScreen(
                    state = state.library,
                    onImport = importFile,
                    onDelete = deleteLibraryItem,
                    onFeedbackShown = clearLibraryFeedback,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
    if (settingsOpen) {
        SettingsDialog(
            userDisplayName = state.userDisplayName,
            recoveryKey = (state.status as? NodeConnectionState.Connected)?.connection?.trusted?.recoveryKey,
            onShowRecoveryKey = {
                settingsOpen = false
                recoveryKeyToShow = it
            },
            onLogout = {
                settingsOpen = false
                logout()
            },
            onDismiss = { settingsOpen = false },
        )
    }
    recoveryKeyToShow?.let { recoveryKey ->
        RecoveryKeyDialog(recoveryKey, onDismiss = { recoveryKeyToShow = null })
    }
}

@Composable
private fun ChatTimeline(chat: ChatUiState, modifier: Modifier = Modifier) {
    key(chat.conversationId) {
        ConversationTimeline(chat, modifier)
    }
}

@Composable
private fun ConversationTimeline(chat: ChatUiState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val bottomThreshold = with(LocalDensity.current) { 72.dp.roundToPx() }
    val isNearBottom by remember(listState, bottomThreshold) {
        derivedStateOf {
            val layout = listState.layoutInfo
            if (layout.totalItemsCount == 0) {
                true
            } else {
                val last = layout.visibleItemsInfo.lastOrNull()
                last != null && last.index == layout.totalItemsCount - 1 &&
                    last.offset + last.size <= layout.viewportEndOffset + bottomThreshold
            }
        }
    }
    var followingLatest by rememberSaveable { mutableStateOf(true) }
    var newerContentAvailable by rememberSaveable { mutableStateOf(false) }
    val itemCount = chat.messages.size +
        if (chat.streamingMessage != null || chat.busy) 1 else 0

    LaunchedEffect(listState) {
        var previousIndex = listState.firstVisibleItemIndex
        var previousOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow {
            Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, isNearBottom)
        }.collect { (index, offset, nearBottom) ->
            val movedTowardOlderContent = index < previousIndex ||
                (index == previousIndex && offset < previousOffset)
            when {
                movedTowardOlderContent -> {
                    followingLatest = false
                    newerContentAvailable = false
                }
                nearBottom -> {
                    followingLatest = true
                    newerContentAvailable = false
                }
            }
            previousIndex = index
            previousOffset = offset
        }
    }

    LaunchedEffect(
        chat.messages.size,
        chat.streamingMessage?.content?.length,
        chat.busy,
    ) {
        if (itemCount == 0) return@LaunchedEffect
        if (followingLatest) {
            listState.scrollToLatest(itemCount)
        } else {
            newerContentAvailable = true
        }
    }

    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(chat.messages, key = ChatMessage::id) { ChatBubble(it) }
            chat.streamingMessage?.let { message ->
                item(key = "stream-${message.id}") { ChatBubble(message) }
            }
            if (chat.busy && chat.streamingMessage == null) {
                item(key = "answering") {
                    Text(stringResource(R.string.answering), color = Ink.copy(alpha = .55f))
                }
            }
        }
        if (newerContentAvailable && !isNearBottom) {
            SmallFloatingActionButton(
                onClick = {
                    followingLatest = true
                    newerContentAvailable = false
                    scope.launch { listState.scrollToLatest(itemCount) }
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                containerColor = Paper,
                contentColor = Moss,
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.jump_to_latest),
                )
            }
        }
    }
}

private suspend fun LazyListState.scrollToLatest(itemCount: Int) {
    if (itemCount <= 0) return
    val lastIndex = itemCount - 1
    var lastItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == lastIndex }
    if (lastItem == null) {
        scrollToItem(lastIndex)
        lastItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == lastIndex }
    }
    lastItem ?: return
    val overflow = lastItem.offset + lastItem.size - layoutInfo.viewportEndOffset
    if (overflow > 0) scrollBy(overflow.toFloat())
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == ChatRole.USER
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(if (isUser) .82f else .88f),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 5.dp,
                bottomEnd = if (isUser) 5.dp else 18.dp,
            ),
            color = if (isUser) Moss.copy(alpha = .1f) else Ink.copy(alpha = .05f),
        ) {
            Text(
                message.content,
                modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
                color = Ink,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun NodeConnectionSummary(
    status: NodeConnectionState,
    connect: (DiscoveredNode) -> Unit,
    retry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val node = (status as? NodeConnectionState.Found)?.nodes?.firstOrNull()
    val label = when (status) {
        NodeConnectionState.Discovering -> stringResource(R.string.this_device)
        is NodeConnectionState.Found -> node?.displayName ?: stringResource(R.string.node)
        is NodeConnectionState.Pairing -> status.name
        is NodeConnectionState.Recovering -> status.name
        is NodeConnectionState.Authenticating -> status.trusted.displayName
        is NodeConnectionState.Connected -> status.connection.trusted.displayName
        is NodeConnectionState.Disconnected -> stringResource(R.string.this_device)
        is NodeConnectionState.Failed -> stringResource(R.string.this_device)
    }
    val indicatorColor = when (status) {
        is NodeConnectionState.Connected -> Moss
        is NodeConnectionState.Failed -> MaterialTheme.colorScheme.error
        is NodeConnectionState.Found -> Color(0xFF9A6A24)
        else -> Ink.copy(alpha = .34f)
    }

    Row(
        modifier = modifier.heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status is NodeConnectionState.Discovering || status is NodeConnectionState.Authenticating ||
            status is NodeConnectionState.Pairing || status is NodeConnectionState.Recovering
        ) {
            CircularProgressIndicator(Modifier.size(9.dp), strokeWidth = 1.5.dp, color = Moss)
        } else {
            Box(Modifier.size(8.dp).background(indicatorColor, CircleShape))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = if (status is NodeConnectionState.Connected) Moss else Ink.copy(alpha = .58f),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when {
            node != null -> TextButton(onClick = { connect(node) }) { Text(stringResource(R.string.connect)) }
            status is NodeConnectionState.Failed && status.canRetry -> TextButton(onClick = retry) {
                Text(stringResource(R.string.try_again))
            }
        }
    }
}
