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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.camera.core.ExperimentalGetImage
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
                AppScreen.Loading -> CenteredProgress()
                is AppScreen.Setup -> SetupScreen(screen, viewModel::createIdentity)
                is AppScreen.Locked -> UnlockScreen(screen, viewModel::unlock)
                is AppScreen.Main -> MainScreen(screen.status, viewModel::scan, viewModel::retry)
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
private fun MainScreen(status: NodeStatus, connect: (com.source.client.model.DiscoveredNode) -> Unit, retry: () -> Unit) {
    SourceColumn(vertical = Arrangement.Center) {
        Wordmark()
        Spacer(Modifier.height(52.dp))
        when (status) {
            NodeStatus.Searching -> {
                CircularProgressIndicator(color = Moss)
                Text("Söker efter Source Node…", color = Ink.copy(alpha = .64f))
            }
            NodeStatus.NoneFound -> StatusText("Ingen Source Node hittades", "Source fungerar lokalt även utan en nod.")
            is NodeStatus.Found -> {
                val node = status.nodes.first()
                StatusText("Source Node hittades", node.displayName)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { connect(node) }) { Text("Anslut") }
            }
            is NodeStatus.Connecting -> {
                CircularProgressIndicator(color = Moss)
                StatusText("Ansluter…", status.name)
            }
            is NodeStatus.Connected -> StatusText("Ansluten", status.node.displayName, connected = true)
            is NodeStatus.PairedOffline -> StatusText("Source Node är inte tillgänglig", status.node.displayName)
            is NodeStatus.Error -> {
                StatusText("Det gick inte att ansluta", status.message)
                if (status.canRetry) OutlinedButton(onClick = retry) { Text("Försök igen") }
            }
        }
    }
}

@Composable
@androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
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

@Composable
private fun StatusText(title: String, detail: String, connected: Boolean = false) {
    Text(title, style = MaterialTheme.typography.headlineSmall, color = if (connected) Moss else Ink, fontWeight = FontWeight.Medium)
    Text(detail, color = Ink.copy(alpha = .64f))
}

@Composable private fun ErrorText(message: String) = Text(message, color = MaterialTheme.colorScheme.error)
@Composable private fun SmallProgress() = CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp, color = Color.White)
@Composable private fun CenteredProgress() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Moss) }
