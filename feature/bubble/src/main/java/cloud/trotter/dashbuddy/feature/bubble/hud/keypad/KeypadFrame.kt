package cloud.trotter.dashbuddy.feature.bubble.hud.keypad

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** Keeps the action reachable and the entry block against it, with slack above. */
@Composable
fun ColumnScope.KeypadFrame(
    title: (@Composable () -> Unit)?,
    block: @Composable ColumnScope.() -> Unit,
    button: @Composable () -> Unit,
) {
    title?.invoke()
    Box(Modifier.fillMaxWidth().weight(1f)) {
        // Let the block use all space left after the action, up to its natural
        // height. Bottom alignment puts unused space above the entire block.
        Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
            Column(Modifier.weight(1f, fill = false).testTag("keypad.block")
                .verticalScroll(rememberScrollState()), content = block)
            button()
        }
    }
}
