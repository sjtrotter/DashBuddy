package cloud.trotter.dashbuddy.feature.bubble

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.feature.bubble.R
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.model.cards.CardStack
import cloud.trotter.dashbuddy.domain.model.chat.ChatMessage
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.feature.bubble.cards.FlowCardItem

// ---------------------------------------------------------------------------
// Dashboard layout
// ---------------------------------------------------------------------------

@Composable
fun DashboardView(
    cardStack: CardStack,
    region: PlatformRegion?,
    messages: List<ChatMessage>,
    lastSession: SessionRecord?,
    focusedPlatform: Platform?,
    gasPrice: Float?,
    isGasPriceAuto: Boolean,
    isGasPriceRefreshing: Boolean = false,
    showGasPriceRefreshError: Boolean = false,
    onSetGasPrice: (Float) -> Unit,
    onRefreshGasPrice: () -> Unit = {},
    onResumeAutoGasPrice: () -> Unit = {},
    onOpenVehicleSettings: () -> Unit,
    onOpenChat: () -> Unit,
    onAccept: () -> Unit = {},
    onDecline: () -> Unit = {},
) {
    // rememberSaveable (#367): expansion state survives rotation/process-restore.
    val expandedIds = rememberSaveable(
        saver = listSaver(
            save = { map -> map.filterValues { it }.keys.toList() },
            restore = { saved -> mutableStateMapOf<String, Boolean>().apply { saved.forEach { put(it, true) } } },
        ),
    ) { mutableStateMapOf<String, Boolean>() }
    val listState = rememberLazyListState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when {
            // Idle/offline with no ACTIVE dash → the idle dashboard card (gas/vehicle
            // just-in-time actions + last-session summary). Keyed on `active == null`, NOT
            // `cardStack.isEmpty`: a completed session's timeline (non-active cards) must not
            // suppress the idle card, or the gas quick-edit becomes unreachable after the first
            // dash — `displayedSessionId` falls back to the most-recent session forever, so the
            // stack is never empty again (#722/#693 reachability). The full completed timeline
            // still shows while actively dashing and via the analytics drill-down.
            (region == null || region.mode == Mode.Offline) && cardStack.active == null -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Box(modifier = Modifier.padding(16.dp)) {
                        ModeIdle(
                            session = lastSession,
                            focusedPlatform = focusedPlatform,
                            gasPrice = gasPrice,
                            isGasPriceAuto = isGasPriceAuto,
                            isGasPriceRefreshing = isGasPriceRefreshing,
                            showGasPriceRefreshError = showGasPriceRefreshError,
                            onSetGasPrice = onSetGasPrice,
                            onRefreshGasPrice = onRefreshGasPrice,
                            onResumeAutoGasPrice = onResumeAutoGasPrice,
                            onOpenVehicleSettings = onOpenVehicleSettings,
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
            }
            cardStack.isEmpty -> {
                Text(
                    text = stringResource(R.string.bubble_screen_waiting_for_activity),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
            }
            else -> {
                // reverseLayout = true pins the active card to the bottom of
                // the viewport and grows the history upward. Items are
                // declared in reverse chronological order (active first,
                // then completed newest→oldest) so that visually:
                //   top    = oldest completed card
                //   middle = newer completed cards
                //   bottom = active (live) card
                // No autoscroll required — Compose naturally keeps the
                // first-declared item (the active card) at the bottom.
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    state = listState,
                    reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    cardStack.active?.let { live ->
                        item(key = "live:${live.id}") {
                            FlowCardItem(
                                snapshot = live,
                                isActive = true,
                                expanded = true,
                                onToggleExpand = { /* active always expanded */ },
                                onAccept = onAccept,
                                onDecline = onDecline,
                            )
                        }
                    }
                    items(
                        // distinctBy is a last-line crash guard: a duplicate
                        // key here is a fatal Compose exception, so never trust
                        // the upstream list to be unique (FlowCardMapper already
                        // dedups, but the HUD must not crash if a new source of
                        // collisions ever slips through).
                        items = cardStack.completed.distinctBy { it.id }.asReversed(),
                        key = { it.id },
                    ) { snap ->
                        val expanded = expandedIds[snap.id] == true
                        FlowCardItem(
                            snapshot = snap,
                            isActive = false,
                            expanded = expanded,
                            onToggleExpand = { expandedIds[snap.id] = !expanded },
                        )
                    }
                }
            }
        }
        LatestMessageTicker(messages = messages, onClick = onOpenChat)
    }
}
