package com.source.client.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.source.client.R

@Composable
internal fun ScannerPermissionScreen(
    state: AppScreen.Scanner,
    scanned: (String) -> Unit,
    close: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
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
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = .6f))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.scan_node_qr_code, state.node.displayName), color = Color.White)
                state.error?.let {
                    Text(it, color = Color(0xFFFFB4AB), modifier = Modifier.padding(top = 8.dp))
                }
            }
            OutlinedButton(
                onClick = close,
                modifier = Modifier.align(Alignment.BottomCenter).padding(28.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    } else {
        SourceColumn(vertical = Arrangement.Center) {
            Wordmark()
            Text(stringResource(R.string.camera_permission_required))
            if (denied) ErrorText(stringResource(R.string.camera_permission_denied))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                Text(stringResource(R.string.allow_camera))
            }
            OutlinedButton(onClick = close) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@Composable
internal fun PairingScreen(name: String) {
    SourceColumn(vertical = Arrangement.Center) {
        CircularProgressIndicator(color = Moss)
        Text(stringResource(R.string.connecting_to_node, name), style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
internal fun RecoveryScreen(
    state: AppScreen.Recovery,
    recover: (String) -> Unit,
    cancel: () -> Unit,
) {
    var recoveryKey by rememberSaveable { mutableStateOf("") }
    SourceColumn(vertical = Arrangement.Center) {
        Wordmark()
        Spacer(Modifier.height(32.dp))
        Text(
            stringResource(R.string.recover_user),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            stringResource(R.string.enter_recovery_key_for_node, state.invitation.nodeName),
            color = Ink.copy(alpha = .64f),
        )
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
        ) {
            if (state.busy) SmallProgress() else Text(stringResource(R.string.recover))
        }
        TextButton(onClick = cancel, enabled = !state.busy) { Text(stringResource(R.string.cancel)) }
    }
}
