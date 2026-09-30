package cloud.trotter.dashbuddy.ui.main.setup.consent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.feature.settings.CapabilityConsentScreen
import cloud.trotter.dashbuddy.feature.settings.eventReceiptDisclosure
import cloud.trotter.dashbuddy.feature.settings.eventReceiptSettingsPath

/**
 * #1151 review LL4 — the shell that replaces EVERY `MainActivity` destination while
 * [EventReceiptConsentUiState.blocked] (`BuildConfig.DEBUG && consent == DECLINED`, the pure
 * [buildEventReceiptConsentState]; a release build can never reach it). Two ways out only: the
 * Automation & Consent screen, rendered right here so its switch is reachable (turning it on
 * unblocks the app live), and Exit.
 */
@Composable
fun DebugEventReceiptShell(onExit: () -> Unit) {
    var showConsentSettings by rememberSaveable { mutableStateOf(false) }
    if (showConsentSettings) {
        BackHandler { showConsentSettings = false }
        CapabilityConsentScreen(onBack = { showConsentSettings = false })
    } else {
        DebugEventReceiptBlock(
            onOpenSettings = { showConsentSettings = true },
            onExit = onExit,
        )
    }
}

/** The debug block's full-screen notice (see [DebugEventReceiptShell]). */
@Composable
fun DebugEventReceiptBlock(
    onOpenSettings: () -> Unit,
    onExit: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.errorContainer) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.debug_event_receipt_block_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.debug_event_receipt_block_body, eventReceiptSettingsPath()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(12.dp))
            // The same disclosure the prompt and the switch show — one string (review LL10/LL12).
            Text(
                text = eventReceiptDisclosure(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.debug_event_receipt_block_open_settings))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.debug_event_receipt_block_exit))
            }
        }
    }
}
