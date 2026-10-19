package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme

/** Android Back registration belongs to the app, which owns activity-compose. */
val LocalHudSheetBackHandler = staticCompositionLocalOf<@Composable (Boolean, () -> Unit) -> Unit> {
    { _, _ -> }
}

/** The caller supplies exactly the body frame between the full hero and the dock. */
@Composable
fun HudSheetHost(
    sheetOpen: Boolean,
    onSheetDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheet: @Composable ColumnScope.() -> Unit = {},
) {
    LocalHudSheetBackHandler.current(sheetOpen, onSheetDismiss)
    if (rememberReducedMotion()) {
        if (sheetOpen) SheetFrame(modifier, sheet)
    } else {
        AnimatedVisibility(sheetOpen, modifier.clipToBounds(),
            enter = slideInVertically(tween(400, easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f))) { it },
            exit = slideOutVertically(tween(200, easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f))) { it }) {
            SheetFrame(Modifier.fillMaxSize(), sheet)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetFrame(modifier: Modifier, sheet: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().testTag("hud.sheet")
        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
        .background(AppTheme.colors.surface2), horizontalAlignment = Alignment.CenterHorizontally) {
        BottomSheetDefaults.DragHandle(modifier = Modifier.testTag("hud.sheet.handle"))
        sheet()
    }
}

/** S3 supplies the strip; this reserves its place without introducing an undo action. */
@Composable
internal fun UndoSlot(mode: HudMode, undo: (@Composable () -> Unit)?, visible: @Composable () -> Boolean = { undo != null }) {
    if (mode == HudMode.LIVE || (mode == HudMode.RESTING && visible())) {
        Box(Modifier.fillMaxWidth().height(48.dp).testTag("hud.slot.undo"), contentAlignment = Alignment.Center) {
            undo?.invoke()
        }
    }
}
