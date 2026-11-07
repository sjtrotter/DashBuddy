package cloud.trotter.log.gig.ui.main.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

/** Line widths of the placeholder card, so the rows read as text of varied length. */
private val CARD_LINES = listOf(0.62f, 0.40f, 0.80f, 0.30f, 0.55f, 0.88f, 0.45f)

/**
 * The hub before its first read: grey blocks shaped like the pager, the hero figure, a card of rows
 * and two tiles. Never $0.00 and never an empty-period line. TalkBack reads it once as "Loading".
 */
@Composable
internal fun HubPlaceholder(modifier: Modifier = Modifier) {
    val loading = stringResource(R.string.common_loading)
    Column(
        modifier
            .fillMaxSize()
            .padding(16.dp)
            .clearAndSetSemantics { contentDescription = loading },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppPlaceholder(Modifier.fillMaxWidth().height(48.dp), contentDescription = null)
        AppPlaceholder(Modifier.fillMaxWidth(0.55f).height(40.dp), contentDescription = null)
        AppPlaceholder(Modifier.fillMaxWidth().height(40.dp), contentDescription = null)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CARD_LINES.forEach { AppPlaceholderLine(it, contentDescription = null) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppPlaceholder(Modifier.weight(1f).height(84.dp), contentDescription = null)
            AppPlaceholder(Modifier.weight(1f).height(84.dp), contentDescription = null)
        }
    }
}
