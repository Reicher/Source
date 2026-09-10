package com.source.client.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.source.client.R
import com.source.client.model.ChatMessage
import com.source.client.model.ChatRole
import com.source.client.model.NodeStatus
import com.source.client.model.VaultProfile

private val Ink = Color(0xFF17201D)
private val Paper = Color(0xFFFAF9F6)
private val Moss = Color(0xFF315E4D)

@Composable
fun SourceApp(screen: AppScreen, viewModel: SourceViewModel) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = Moss,
            onPrimary = Color.White,
            background = Paper,
            surface = Paper,
            onBackground = Ink,
            onSurface = Ink,
        ),
    ) {
        Surface(Modifier.fillMaxSize()) {
            when (screen) {
                is AppScreen.Accounts -> AccountsScreen(screen, viewModel::selectProfile, viewModel::showCreateIdentity)
                is AppScreen.Setup -> SetupScreen(screen, viewModel::createIdentity, viewModel::showAccounts)
                is AppScreen.Locked -> UnlockScreen(screen, viewModel::unlock, viewModel::showAccounts)
                is AppScreen.Main -> MainScreen(
                    screen,
                    viewModel::scan,
                    viewModel::retry,
                    viewModel::sendMessage,
                    viewModel::cancelInference,
                    viewModel::logout,
                )
                is AppScreen.Scanner -> ScannerPermissionScreen(screen, viewModel::onQrScanned, viewModel::cancelScanner)
                is AppScreen.Recovery -> RecoveryScreen(screen, viewModel::recover, viewModel::cancelRecovery)
                is AppScreen.Pairing -> PairingScreen(screen.name)
            }
        }
    }
}

@Composable
private fun AccountsScreen(
    state: AppScreen.Accounts,
    select: (VaultProfile) -> Unit,
    create: () -> Unit,
) {
    SourceColumn {
        Wordmark()
        Spacer(Modifier.height(40.dp))
        Text(stringResource(R.string.select_user), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
        Text(stringResource(R.string.users_have_separate_data), color = Ink.copy(alpha = .64f))
        Spacer(Modifier.height(24.dp))
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.profiles, key = VaultProfile::id) { profile ->
                OutlinedButton(
                    onClick = { select(profile) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text(profile.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick = create, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.create_new_user)) }
    }
}

@Composable
private fun SetupScreen(
    state: AppScreen.Setup,
    submit: (String, String, String) -> Unit,
    cancel: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    SourceColumn {
        Wordmark()
        Spacer(Modifier.height(40.dp))
        Text(stringResource(R.string.create_user), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
        Text(stringResource(R.string.create_user_description), color = Ink.copy(alpha = .64f))
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.user_name)) }, singleLine = true)
        OutlinedTextField(
            password, { password = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.password)) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        OutlinedTextField(
            confirmation, { confirmation = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.confirm_password)) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        state.error?.let { ErrorText(it) }
        Button(
            onClick = { submit(name, password, confirmation); password = ""; confirmation = "" },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { if (state.busy) SmallProgress() else Text(stringResource(R.string.create_user)) }
        if (state.canCancel) TextButton(onClick = cancel, enabled = !state.busy) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
private fun UnlockScreen(state: AppScreen.Locked, submit: (String) -> Unit, switchUser: () -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    SourceColumn {
        Wordmark()
        Spacer(Modifier.height(56.dp))
        Text(stringResource(R.string.sign_in_as, state.profile.displayName), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            password, { password = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.password)) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        state.error?.let { ErrorText(it) }
        Button(onClick = { submit(password); password = "" }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            if (state.busy) SmallProgress() else Text(stringResource(R.string.sign_in))
        }
        TextButton(onClick = switchUser, enabled = !state.busy) { Text(stringResource(R.string.switch_user)) }
    }
}

@Composable
private fun MainScreen(
    state: AppScreen.Main,
    connect: (com.source.client.model.DiscoveredNode) -> Unit,
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
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { settingsOpen = true }) {
                Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings), tint = Ink.copy(alpha = .78f))
            }
        }
        NodeStatusSummary(state.status, connect, retry, Modifier.fillMaxWidth())
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
                    if (state.chat.busy) cancelInference()
                    else {
                        send(draft)
                        draft = ""
                    }
                },
                enabled = state.chat.busy || draft.isNotBlank(),
                modifier = Modifier.height(56.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text(stringResource(if (state.chat.busy) R.string.cancel else R.string.send)) }
        }
        Spacer(Modifier.height(12.dp))
    }
    if (settingsOpen) {
        SettingsDialog(
            userDisplayName = state.userDisplayName,
            recoveryKey = (state.status as? NodeStatus.Connected)?.node?.recoveryKey,
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
        AlertDialog(
            onDismissRequest = { recoveryKeyToShow = null },
            title = { Text(stringResource(R.string.recovery_key)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.recovery_key_safety))
                    SelectionContainer { Text(recoveryKey) }
                }
            },
            confirmButton = { TextButton(onClick = { recoveryKeyToShow = null }) { Text(stringResource(R.string.done)) } },
        )
    }
}

@Composable
private fun SettingsDialog(
    userDisplayName: String,
    recoveryKey: String?,
    onShowRecoveryKey: (String) -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column {
                Text(
                    userDisplayName,
                    color = Ink.copy(alpha = .6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = Ink.copy(alpha = .1f))
                recoveryKey?.let { key ->
                    TextButton(
                        onClick = { onShowRecoveryKey(key) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        Text(stringResource(R.string.show_recovery_key), modifier = Modifier.fillMaxWidth())
                    }
                }
                TextButton(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Text(stringResource(R.string.sign_out), modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
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
private fun NodeStatusSummary(
    status: NodeStatus,
    connect: (com.source.client.model.DiscoveredNode) -> Unit,
    retry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val node = (status as? NodeStatus.Found)?.nodes?.firstOrNull()
    val label = when (status) {
        NodeStatus.Searching -> stringResource(R.string.this_device)
        NodeStatus.NoneFound -> stringResource(R.string.this_device)
        is NodeStatus.Found -> node?.displayName ?: stringResource(R.string.node)
        is NodeStatus.Connecting -> status.name
        is NodeStatus.Connected -> status.node.displayName
        is NodeStatus.PairedOffline -> stringResource(R.string.this_device)
        is NodeStatus.Error -> stringResource(R.string.this_device)
    }
    val indicatorColor = when (status) {
        is NodeStatus.Connected -> Moss
        is NodeStatus.Error -> MaterialTheme.colorScheme.error
        is NodeStatus.Found -> Color(0xFF9A6A24)
        else -> Ink.copy(alpha = .34f)
    }

    Row(
        modifier = modifier.heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status is NodeStatus.Searching || status is NodeStatus.Connecting) {
            CircularProgressIndicator(Modifier.size(9.dp), strokeWidth = 1.5.dp, color = Moss)
        } else {
            Box(Modifier.size(8.dp).background(indicatorColor, CircleShape))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = if (status is NodeStatus.Connected) Moss else Ink.copy(alpha = .58f),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when {
            node != null -> TextButton(onClick = { connect(node) }) { Text(stringResource(R.string.connect)) }
            status is NodeStatus.Error && status.canRetry -> TextButton(onClick = retry) { Text(stringResource(R.string.try_again)) }
        }
    }
}

@Composable
private fun ScannerPermissionScreen(state: AppScreen.Scanner, scanned: (String) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        denied = !it
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    if (granted) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            QrCamera(onQrCode = scanned)
            Column(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = .6f)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.scan_node_qr_code, state.node.displayName), color = Color.White)
                state.error?.let { Text(it, color = Color(0xFFFFB4AB), modifier = Modifier.padding(top = 8.dp)) }
            }
            OutlinedButton(onClick = close, Modifier.align(Alignment.BottomCenter).padding(28.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                Text(stringResource(R.string.cancel))
            }
        }
    } else {
        SourceColumn(vertical = Arrangement.Center) {
            Wordmark()
            Text(stringResource(R.string.camera_permission_required))
            if (denied) ErrorText(stringResource(R.string.camera_permission_denied))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.allow_camera)) }
            OutlinedButton(onClick = close) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@Composable
private fun PairingScreen(name: String) {
    SourceColumn(vertical = Arrangement.Center) {
        CircularProgressIndicator(color = Moss)
        Text(stringResource(R.string.connecting_to_node, name), style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun RecoveryScreen(
    state: AppScreen.Recovery,
    recover: (String) -> Unit,
    cancel: () -> Unit,
) {
    var recoveryKey by rememberSaveable { mutableStateOf("") }
    SourceColumn(vertical = Arrangement.Center) {
        Wordmark()
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.recover_user), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
        Text(stringResource(R.string.enter_recovery_key_for_node, state.invitation.nodeName), color = Ink.copy(alpha = .64f))
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            recoveryKey,
            { recoveryKey = it },
            Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.recovery_key)) },
            singleLine = true,
        )
        state.error?.let { ErrorText(it) }
        Button(
            onClick = {
                val submittedKey = recoveryKey
                recoveryKey = ""
                recover(submittedKey)
            },
            enabled = recoveryKey.isNotBlank() && !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { if (state.busy) SmallProgress() else Text(stringResource(R.string.recover)) }
        TextButton(onClick = cancel, enabled = !state.busy) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
private fun SourceColumn(vertical: Arrangement.Vertical = Arrangement.Top, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 48.dp),
        verticalArrangement = vertical,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable private fun Wordmark() = Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)

@Composable private fun ErrorText(message: String) = Text(message, color = MaterialTheme.colorScheme.error)
@Composable private fun SmallProgress() = CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp, color = Color.White)
