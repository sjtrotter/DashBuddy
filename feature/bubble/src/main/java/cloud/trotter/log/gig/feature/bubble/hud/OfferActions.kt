package cloud.trotter.log.gig.feature.bubble.hud

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.feature.bubble.R

@Composable
internal fun OfferActions(onAccept: () -> Unit, onDecline: () -> Unit) {
    val c = AppTheme.colors
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onAccept, Modifier.weight(1f).height(64.dp).testTag("offer.accept"),
                shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.accentText)) {
                Text(stringResource(R.string.flow_card_action_accept))
            }
            OutlinedButton(onDecline, Modifier.weight(1f).height(64.dp).testTag("offer.decline"),
                shape = RoundedCornerShape(32.dp), border = BorderStroke(1.5.dp, c.lineStrong),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text)) {
                Text(stringResource(R.string.flow_card_action_decline))
            }
        }
    }
}
