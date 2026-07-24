package cloud.trotter.dashbuddy.ui.bubble

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.feature.bubble.DashboardView
import cloud.trotter.dashbuddy.feature.bubble.FullChatView
import cloud.trotter.dashbuddy.feature.bubble.SessionMetricsActions
import cloud.trotter.dashbuddy.feature.bubble.StatusBadgeTitle
import cloud.trotter.dashbuddy.ui.main.MainActivity
import cloud.trotter.dashbuddy.ui.main.navigation.Screen
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BubbleScreen(
    viewModel: BubbleViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val appState by viewModel.appState.collectAsStateWithLifecycle()
    val focusedPlatform by viewModel.focusedPlatform.collectAsStateWithLifecycle()
    val focusedRegion by viewModel.focusedRegion.collectAsStateWithLifecycle()
    val sessionMiles by viewModel.sessionMiles.collectAsStateWithLifecycle()
    val sessionEarnings by viewModel.sessionEarnings.collectAsStateWithLifecycle()
    val lastSession by viewModel.lastSession.collectAsStateWithLifecycle()
    val gasPrice by viewModel.gasPrice.collectAsStateWithLifecycle()
    val isGasPriceAuto by viewModel.isGasPriceAuto.collectAsStateWithLifecycle()
    val isGasPriceRefreshing by viewModel.isGasPriceRefreshing.collectAsStateWithLifecycle()
    val cardStack by viewModel.cardStack.collectAsStateWithLifecycle()
    var showFullChat by remember { mutableStateOf(false) }

    // Collapse the bubble to its head after the user acts on an offer.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.collapse.collect { context.findActivity()?.finish() }
    }

    // Transient "couldn't refresh" flash for the gas quick-edit (#722) — a one-shot signal, not
    // a toast (the worker's WARN already logs the reason, #692 levels). Composable-owned local
    // state is appropriate here: it's a self-clearing visual flash, not a fact the dasher could be
    // misled by if lost to a config change.
    var showGasPriceRefreshError by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.gasPriceRefreshFailed.collect {
            showGasPriceRefreshError = true
            delay(3_000)
            showGasPriceRefreshError = false
        }
    }

    // Vehicle just-in-time action (#693): deep-link into the main app's economy/vehicle settings.
    val onOpenVehicleSettings: () -> Unit = {
        context.startActivity(MainActivity.routeIntent(context, Screen.EconomySettings.route))
    }

    val flow = appState.regions.flow

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (showFullChat) {
                        Text(stringResource(R.string.bubble_screen_chat_history_title))
                    } else {
                        StatusBadgeTitle(
                            region = focusedRegion,
                            flow = flow,
                            platform = focusedPlatform,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    if (showFullChat) {
                        IconButton(onClick = { showFullChat = false }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.bubble_screen_content_desc_close_chat),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        SessionMetricsActions(
                            region = focusedRegion,
                            earnings = sessionEarnings,
                            miles = sessionMiles,
                            lastSession = lastSession
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            if (showFullChat) {
                FullChatView(messages)
            } else {
                DashboardView(
                    cardStack = cardStack,
                    region = focusedRegion,
                    messages = messages,
                    lastSession = lastSession,
                    focusedPlatform = focusedPlatform,
                    gasPrice = gasPrice,
                    isGasPriceAuto = isGasPriceAuto,
                    isGasPriceRefreshing = isGasPriceRefreshing,
                    showGasPriceRefreshError = showGasPriceRefreshError,
                    onSetGasPrice = viewModel::setGasPrice,
                    onRefreshGasPrice = viewModel::refreshGasPrice,
                    onResumeAutoGasPrice = viewModel::resumeAutoGasPrice,
                    onOpenVehicleSettings = onOpenVehicleSettings,
                    onOpenChat = { showFullChat = true },
                    onAccept = { viewModel.acceptOffer() },
                    onDecline = { viewModel.declineOffer() },
                )
            }
        }
    }
}

/** Unwrap a Compose [LocalContext] (often a ContextThemeWrapper) to the hosting Activity. */
private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
