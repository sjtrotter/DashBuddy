package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.core.designsystem.theme.LocalGlance
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
    onGoalCelebrated: (String) -> Unit = {},
    onGoalClick: (() -> Unit)? = null,
    sheetOpen: Boolean = false,
    onSheetDismiss: () -> Unit = {},
    sheet: @Composable ColumnScope.() -> Unit = {},
    undo: (@Composable () -> Unit)? = null,
    stateCard: @Composable () -> Unit = {},
    offer: @Composable () -> Unit = {},
    log: @Composable () -> Unit = {},
) {
    val tier = FontScaleTier.of(LocalDensity.current.fontScale, LocalGlance.current)
    CompositionLocalProvider(LocalHudTier provides tier) {
        Box(modifier.fillMaxSize().background(AppTheme.colors.bg)) {
            PhaseStrip(state.stripColor, state.mode == HudMode.RESTING || state.paused)
            Column(Modifier.fillMaxSize().padding(start = 22.dp, end = 16.dp, bottom = 12.dp)) {
                HudHead(state.platform, state.switchable, state.paused, onSelectPlatform)
                if (state.mode == HudMode.OFFER) {
                    Box(Modifier.fillMaxWidth().weight(1f).testTag("hud.slot.offer")) { offer() }
                } else {
                    TopRow(state.top)
                    val showingLog = state.logOpen || state.mode == HudMode.LOG
                    if (showingLog) CompactHeroRow(state.hero, onGoalClick)
                    else HeroRow(state.hero, onGoalCelebrated, onGoalClick)
                    Box(Modifier.weight(1f).fillMaxWidth().testTag("hud.body")) {
                        Column(Modifier.fillMaxSize()) {
                            when {
                                showingLog -> Box(Modifier.weight(1f).fillMaxWidth().testTag("hud.slot.log")) { log() }
                                state.mode == HudMode.RESTING -> {
                                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { RestingCard(state.resting) }
                                    GasRow(state.gas, onSetGasPrice, onRefreshGasPrice, onResumeAutoGasPrice)
                                }
                                else -> Box(Modifier.weight(1f).fillMaxWidth().testTag("hud.slot.state")) {
                                    Column(Modifier.verticalScroll(rememberScrollState()).fillMaxWidth().heightIn(min = 196.dp)
                                        .clip(RoundedCornerShape(16.dp)).background(AppTheme.colors.surface)) { stateCard() }
                                }
                            }
                            if (!showingLog && state.mode != HudMode.RESTING && ribbon != null) DashRibbon(ribbon)
                            if (!showingLog) UndoSlot(state.mode, undo)
                            Spacer(Modifier.height(8.dp))
                        }
                        if (!showingLog) HudSheetHost(sheetOpen, onSheetDismiss, Modifier.fillMaxSize(), sheet)
                    }
                    Box(Modifier.testTag("hud.dock")) { Dock(state.dock, onDockClick) }
                }
            }
        }
    }
}
