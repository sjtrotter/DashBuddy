package cloud.trotter.log.gig.ui.main.playbook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.component.AppPlaceholder
import cloud.trotter.log.gig.core.designsystem.component.AppPlaceholderLine

/**
 * The Playbook before its first read: grey blocks shaped like the plan card's lines, the heatmap and
 * the top three store rows. Never an empty plan or an empty store list. TalkBack reads it once as
 * "Loading".
 */
@Composable
internal fun PlaybookPlaceholder(modifier: Modifier = Modifier) {
    val loading = stringResource(R.string.common_loading)
    Column(
        modifier
            .fillMaxSize()
            .padding(16.dp)
            .clearAndSetSemantics { contentDescription = loading },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppPlaceholderLine(0.40f, contentDescription = null)
            AppPlaceholderLine(0.85f, height = 20.dp, contentDescription = null)
            AppPlaceholderLine(0.60f, contentDescription = null)
        }
        AppPlaceholder(Modifier.fillMaxWidth().height(200.dp), contentDescription = null)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(STORE_ROWS) { AppPlaceholder(Modifier.fillMaxWidth().height(40.dp), contentDescription = null) }
        }
    }
}

/** Simple mode shows the top three stores. */
private const val STORE_ROWS = 3
