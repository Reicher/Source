package com.source.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

@Composable
internal fun MainScreen(
    state: AppScreen.Main,
    connect: (DiscoveredNode) -> Unit,
    retry: () -> Unit,
    send: (String) -> Unit,
    cancelInference: () -> Unit,
    logout: () -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var recoveryKeyToShow by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(state.chat.messages.size, state.chat.streamingMessage?.content?.length) {
        val lastIndex = state.chat.messages.size +
            if (state.chat.streamingMessage != null || state.chat.busy) 0 else -1
        if (lastIndex >= 0) listState.scrollToItem(lastIndex)
    }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
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
        if (state.chat.messages.isEmpty()) {
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.chat.messages, key = ChatMessage::id) { ChatBubble(it) }
                state.chat.streamingMessage?.let { message ->
                    item(key = "stream-${message.id}") { ChatBubble(message) }
                }
                if (state.chat.busy && state.chat.streamingMessage == null) {
                    item { Text(stringResource(R.string.answering), color = Ink.copy(alpha = .55f)) }
                }
            }
        }
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
                    if (state.chat.busy) {
                        cancelInference()
                    } else {
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
