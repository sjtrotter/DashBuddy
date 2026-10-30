package cloud.trotter.log.gig.feature.bubble.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.feature.bubble.R

/** Oldest to newest on screen; index zero is the newest item at the bottom. */
@Composable
fun LogView(items: List<LogItem>, onNow: () -> Unit, onUndo: (UndoTarget) -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    Box(modifier.fillMaxSize().testTag("hud.log")) {
        if (items.isEmpty()) Text(stringResource(R.string.hud_log_empty), color = AppTheme.colors.neutral,
            modifier = Modifier.align(Alignment.BottomStart).padding(vertical = 7.dp))
        LazyColumn(Modifier.fillMaxSize().testTag("hud.log.list"), state = listState, reverseLayout = true) {
            items(items.asReversed(), key = { it.key }) { item ->
                when (item) {
                    is JobCardModel -> LogJobCard(item, onNow, onUndo)
                    else -> LogThinRow(item)
                }
            }
        }
        if (listState.canScrollForward) Box(Modifier.fillMaxWidth().height(36.dp).align(Alignment.TopCenter)
            .background(Brush.verticalGradient(listOf(AppTheme.colors.bg, Color.Transparent))).testTag("hud.log.fade"))
    }
}
