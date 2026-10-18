package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.state.Platform

@Composable
fun HudScreen(
    state: HudUiState,
    ribbon: RibbonModel?,
    onDockClick: (DockKind) -> Unit,
    modifier: Modifier = Modifier,
    onSelectPlatform: (Platform) -> Unit = {},
    onSetGasPrice: (Float) -> Unit = {},
    onRefreshGasPrice: () -> Unit = {},
    onResumeAutoGasPrice: () -> Unit = {},
    stateCard: @Composable () -> Unit = {},
    offer: @Composable () -> Unit = {},
    log: @Composable () -> Unit = {},
) {
    Box(modifier.fillMaxSize().background(AppTheme.colors.bg)) {
        PhaseStrip(state.stripColor, state.mode == HudMode.RESTING)
        Column(Modifier.fillMaxSize().padding(start = 22.dp, end = 16.dp, bottom = 12.dp)) {
            HudHead(state.platform, state.switchable, onSelectPlatform)
            if (state.mode == HudMode.OFFER) {
                Box(Modifier.fillMaxWidth().weight(1f).testTag("hud.slot.offer")) { offer() }
            } else {
                TopRow(state.top)
                HeroRow(state.hero)
                when {
                    state.logOpen || state.mode == HudMode.LOG ->
                        Box(Modifier.weight(1f).fillMaxWidth().testTag("hud.slot.log")) { log() }
                    state.mode == HudMode.RESTING -> {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { RestingCard(state.resting) }
                        GasRow(state.gas, onSetGasPrice, onRefreshGasPrice, onResumeAutoGasPrice)
                    }
                    else -> {
                        Box(Modifier.weight(1f).fillMaxWidth().testTag("hud.slot.state")) {
                            Column(Modifier.verticalScroll(rememberScrollState()).fillMaxWidth().heightIn(min = 196.dp)
                                .clip(RoundedCornerShape(16.dp)).background(AppTheme.colors.surface)) { stateCard() }
                        }
                    }
                }
                if (state.mode != HudMode.RESTING && ribbon != null) DashRibbon(ribbon)
                Spacer(Modifier.height(8.dp))
                Dock(state.dock, onDockClick)
            }
        }
    }
}
