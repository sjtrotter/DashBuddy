package cloud.trotter.dashbuddy.ui.main.setup.consent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import kotlinx.coroutines.delay
import cloud.trotter.dashbuddy.feature.settings.CapabilityConsentScreen
import cloud.trotter.dashbuddy.feature.settings.eventReceiptDisclosure
import cloud.trotter.dashbuddy.feature.settings.eventReceiptSettingsPath

/**
 * #1151 review LL4 — the shell that replaces EVERY `MainActivity` destination while
 * [EventReceiptConsentUiState.blocked] (`BuildConfig.DEBUG && consent == DECLINED`, the pure
 * [buildEventReceiptConsentState]; a release build can never reach it). It is the backstop a dasher
 * sees on reopening a declined debug build, whose listener has already disabled the service (QQ3/QQ4).
 * Two ways out only: the
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

/**
 * Review MM3/NN2 — the neutral gate a DEBUG build shows while the consent is not read yet: no NavHost,
 * no route consumption. It resolves within the first store read; if it has not after
 * [LOADING_EXIT_AFTER_MS] it shows a one-line notice and Exit, so it can never be a silent blank.
 */
@Composable
fun DebugEventReceiptLoading(onExit: () -> Unit) {
    // PP8: a plain flag flipped after delay() — no elapsed-time bookkeeping.
    var showExit by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LOADING_EXIT_AFTER_MS)
        showExit = true
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (showExit) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.debug_event_receipt_loading_notice),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.debug_event_receipt_block_exit))
                }
            }
        }
    }
}

/** NN2 — how long the loading gate stays blank before it offers Exit. */
const val LOADING_EXIT_AFTER_MS = 3_000L

/** The debug block's full-screen notice (see [DebugEventReceiptShell]). */
@Composable
fun DebugEventReceiptBlock(
    onOpenSettings: () -> Unit,
    onExit: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.errorContainer) {
        // Review OO3: the notice + disclosure scroll; the two buttons stay pinned below them, so a
        // short window (split screen, large fonts) can never push Exit off-screen.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
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
            }
            Spacer(Modifier.height(16.dp))
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
