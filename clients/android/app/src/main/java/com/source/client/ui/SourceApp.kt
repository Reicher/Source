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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.source.client.model.AiSelection
import com.source.client.model.ChatMessage
import com.source.client.model.ChatRole
import com.source.client.model.NodeStatus

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
                is AppScreen.Setup -> SetupScreen(screen, viewModel::createIdentity)
                is AppScreen.Locked -> UnlockScreen(screen, viewModel::unlock)
                is AppScreen.Main -> MainScreen(
                    screen,
                    viewModel::scan,
                    viewModel::retry,
                    viewModel::selectAi,
                    viewModel::sendMessage,
                )
                is AppScreen.Scanner -> ScannerPermissionScreen(screen, viewModel::onQrScanned, viewModel::cancelScanner)
                is AppScreen.Pairing -> PairingScreen(screen.name)
            }
        }
    }
}

@Composable
private fun SetupScreen(state: AppScreen.Setup, submit: (String, String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    SourceColumn {
        Wordmark()
        Spacer(Modifier.height(40.dp))
        Text("Skapa din lokala identitet", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
        Text("Inget konto skapas på internet. Identiteten stannar på den här enheten.", color = Ink.copy(alpha = .64f))
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Användarnamn") }, singleLine = true)
        OutlinedTextField(
            password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Lösenord") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        OutlinedTextField(
            confirmation, { confirmation = it }, Modifier.fillMaxWidth(), label = { Text("Bekräfta lösenord") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        state.error?.let { ErrorText(it) }
        Button(
            onClick = { submit(name, password, confirmation); password = ""; confirmation = "" },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { if (state.busy) SmallProgress() else Text("Skapa identitet") }
    }
}

@Composable
private fun UnlockScreen(state: AppScreen.Locked, submit: (String) -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    SourceColumn {
        Wordmark()
        Spacer(Modifier.height(56.dp))
        Text("Lås upp Source", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Lösenord") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        state.error?.let { ErrorText(it) }
        Button(onClick = { submit(password); password = "" }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            if (state.busy) SmallProgress() else Text("Lås upp")
        }
    }
}

@Composable
private fun MainScreen(
    state: AppScreen.Main,
    connect: (com.source.client.model.DiscoveredNode) -> Unit,
    retry: () -> Unit,
    selectAi: (AiSelection) -> Unit,
    send: (String) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(state.chat.messages.size) {
        if (state.chat.messages.isNotEmpty()) listState.animateScrollToItem(state.chat.messages.lastIndex)
    }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NodeStatusBar(state.status, connect, retry, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            AiPicker(state.status, state.chat.selection, selectAi)
        }
        Spacer(Modifier.height(12.dp))
        if (state.chat.messages.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Vad vill du prata om?", color = Ink.copy(alpha = .55f))
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.chat.messages, key = ChatMessage::id) { ChatBubble(it) }
                if (state.chat.busy) item { Text("Svarar…", color = Ink.copy(alpha = .55f)) }
            }
        }
        state.chat.error?.let { ErrorText(it) }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Meddelande") },
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
                onClick = { send(draft); draft = "" },
                enabled = draft.isNotBlank() && !state.chat.busy,
                modifier = Modifier.height(56.dp),
            ) { Text("Skicka") }
        }
    }
}

@Composable
private fun AiPicker(status: NodeStatus, selection: AiSelection, select: (AiSelection) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val connectedNode = (status as? NodeStatus.Connected)?.node
    val label = when (selection) {
        AiSelection.AUTO -> "Auto"
        AiSelection.THIS_DEVICE -> "Local"
        AiSelection.NODE -> "Node"
    }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.widthIn(min = 120.dp, max = 160.dp).heightIn(min = 48.dp),
        ) {
            Text("AI: $label", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Auto") }, onClick = { select(AiSelection.AUTO); expanded = false })
            DropdownMenuItem(text = { Text("Local") }, onClick = { select(AiSelection.THIS_DEVICE); expanded = false })
            connectedNode?.let {
                DropdownMenuItem(text = { Text("Node") }, onClick = { select(AiSelection.NODE); expanded = false })
            }
        }
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
            modifier = Modifier.fillMaxWidth(.86f),
            shape = RoundedCornerShape(16.dp),
            color = if (isUser) Moss else Ink.copy(alpha = .055f),
        ) {
            Text(
                message.content,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                color = if (isUser) Color.White else Ink,
            )
        }
    }
}

@Composable
private fun NodeStatusBar(
    status: NodeStatus,
    connect: (com.source.client.model.DiscoveredNode) -> Unit,
    retry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val node = (status as? NodeStatus.Found)?.nodes?.firstOrNull()
    val label = when (status) {
        NodeStatus.Searching -> "Node · Söker…"
        NodeStatus.NoneFound -> "Node · Offline"
        is NodeStatus.Found -> node?.displayName ?: "Node"
        is NodeStatus.Connecting -> "${status.name}…"
        is NodeStatus.Connected -> status.node.displayName
        is NodeStatus.PairedOffline -> "${status.node.displayName} · Offline"
        is NodeStatus.Error -> "Node · Fel"
    }
    val indicatorColor = when (status) {
        is NodeStatus.Connected -> Moss
        is NodeStatus.Error -> MaterialTheme.colorScheme.error
        is NodeStatus.Found -> Color(0xFF9A6A24)
        else -> Ink.copy(alpha = .34f)
    }

    Surface(
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(16.dp),
        color = Ink.copy(alpha = .045f),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (status is NodeStatus.Searching || status is NodeStatus.Connecting) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Moss)
            } else {
                Box(Modifier.size(9.dp).background(indicatorColor, CircleShape))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = if (status is NodeStatus.Connected) Moss else Ink.copy(alpha = .78f),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                node != null -> TextButton(onClick = { connect(node) }) { Text("Anslut") }
                status is NodeStatus.Error && status.canRetry -> TextButton(onClick = retry) { Text("Försök") }
            }
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
                Text("Skanna QR-koden på ${state.node.displayName}", color = Color.White)
                state.error?.let { Text(it, color = Color(0xFFFFB4AB), modifier = Modifier.padding(top = 8.dp)) }
            }
            OutlinedButton(onClick = close, Modifier.align(Alignment.BottomCenter).padding(28.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                Text("Avbryt")
            }
        }
    } else {
        SourceColumn(vertical = Arrangement.Center) {
            Wordmark()
            Text("Kameraåtkomst behövs för att skanna nodens QR-kod.")
            if (denied) ErrorText("Kameraåtkomst nekades. Du kan försöka igen.")
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Tillåt kamera") }
            OutlinedButton(onClick = close) { Text("Avbryt") }
        }
    }
}

@Composable
private fun PairingScreen(name: String) {
    SourceColumn(vertical = Arrangement.Center) {
        CircularProgressIndicator(color = Moss)
        Text("Ansluter till $name…", style = MaterialTheme.typography.headlineSmall)
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

@Composable private fun Wordmark() = Text("Source", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)

@Composable private fun ErrorText(message: String) = Text(message, color = MaterialTheme.colorScheme.error)
@Composable private fun SmallProgress() = CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp, color = Color.White)
