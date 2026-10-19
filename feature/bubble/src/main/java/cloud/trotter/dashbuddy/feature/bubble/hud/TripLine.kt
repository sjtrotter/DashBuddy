package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.feature.bubble.R

@Composable
internal fun TripLine(model: TripLineModel) {
    val c = AppTheme.colors
    Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(4.dp).padding(horizontal = 14.dp)) {
            drawLine(c.surface3, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 4.dp.toPx())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            model.nodes.forEach { node ->
                val label = node.overflow?.let { stringResource(R.string.hud_offer_cd_more, it) } ?: stringResource(when (node.icon) {
                    TrackIcon.STORE -> R.string.hud_offer_cd_store
                    TrackIcon.HOUSE -> R.string.hud_offer_cd_house
                    else -> R.string.hud_offer_cd_pin
                })
                val ink = if (node.added) c.stOffer else when (node.icon) {
                    TrackIcon.STORE -> c.stPickup; TrackIcon.HOUSE -> c.stDropoff; else -> c.text2
                }
                Box(Modifier.size(28.dp).background(if (node.added) c.stOfferBg else c.surface, CircleShape)
                    .testTag("offer.trip.node").semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                    if (node.overflow != null) Text(stringResource(R.string.hud_offer_more, node.overflow), color = ink, style = AppTheme.num.smNum)
                    else Icon(when (node.icon) {
                        TrackIcon.STORE -> Icons.Filled.Store; TrackIcon.HOUSE -> Icons.Filled.Home; else -> Icons.Filled.Navigation
                    }, null, Modifier.size(18.dp), tint = ink)
                }
            }
        }
    }
}
