package com.source.client.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

internal val Ink = Color(0xFF17201D)
internal val Paper = Color(0xFFFAF9F6)
internal val Moss = Color(0xFF315E4D)

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
                    viewModel::newConversation,
                    viewModel::cancelInference,
                    viewModel::logout,
                    viewModel::selectDestination,
                    viewModel::importFile,
                    viewModel::removeLibraryItemFromDevice,
                    viewModel::deleteLibraryItemFromSource,
                    viewModel::openLibraryItem,
                    viewModel::closeLibraryPreview,
                    viewModel::clearLibraryFeedback,
                    viewModel::openKnowledgeSource,
                    viewModel::pauseSilverRefinement,
                    viewModel::resumeSilverRefinement,
                )
                is AppScreen.Scanner -> ScannerPermissionScreen(
                    screen,
                    viewModel::onQrScanned,
                    viewModel::cancelScanner,
                )
                is AppScreen.Recovery -> RecoveryScreen(screen, viewModel::recover, viewModel::cancelRecovery)
                is AppScreen.Pairing -> PairingScreen(screen.name)
            }
        }
    }
}
