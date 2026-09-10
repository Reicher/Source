package com.source.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.source.client.R
import com.source.client.model.VaultProfile

@Composable
internal fun AccountsScreen(
    state: AppScreen.Accounts,
    select: (VaultProfile) -> Unit,
    create: () -> Unit,
) {
    SourceColumn {
        Wordmark()
        Spacer(Modifier.height(40.dp))
        Text(
            stringResource(R.string.select_user),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
        )
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
        Button(onClick = create, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.create_new_user))
        }
    }
}

@Composable
internal fun SetupScreen(
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
        Text(
            stringResource(R.string.create_user),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
        )
        Text(stringResource(R.string.create_user_description), color = Ink.copy(alpha = .64f))
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            name,
            { name = it },
            Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.user_name)) },
            singleLine = true,
        )
        OutlinedTextField(
            password,
            { password = it },
            Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        OutlinedTextField(
            confirmation,
            { confirmation = it },
            Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.confirm_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        state.error?.let { ErrorText(it) }
        Button(
            onClick = {
                submit(name, password, confirmation)
                password = ""
                confirmation = ""
            },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.busy) SmallProgress() else Text(stringResource(R.string.create_user))
        }
        if (state.canCancel) {
            TextButton(onClick = cancel, enabled = !state.busy) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@Composable
internal fun UnlockScreen(
    state: AppScreen.Locked,
    submit: (String) -> Unit,
    switchUser: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    SourceColumn {
        Wordmark()
        Spacer(Modifier.height(56.dp))
        Text(
            stringResource(R.string.sign_in_as, state.profile.displayName),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            password,
            { password = it },
            Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        state.error?.let { ErrorText(it) }
        Button(
            onClick = {
                submit(password)
                password = ""
            },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.busy) SmallProgress() else Text(stringResource(R.string.sign_in))
        }
        TextButton(onClick = switchUser, enabled = !state.busy) {
            Text(stringResource(R.string.switch_user))
        }
    }
}
