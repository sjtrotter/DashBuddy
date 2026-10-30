package cloud.trotter.log.gig.feature.bubble.hud

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.feature.bubble.R

@Composable
internal fun AddOnDeltaRow(tiles: DeltaTiles) {
    val values = listOf(
        (tiles.minutes?.toString() ?: EMPTY_VALUE) to stringResource(R.string.hud_unit_min),
        (tiles.miles?.let { Formats.decimal(it) } ?: EMPTY_VALUE) to stringResource(R.string.hud_offer_unit_mi),
        (tiles.stops?.toString() ?: EMPTY_VALUE) to pluralStringResource(R.plurals.hud_offer_stops, tiles.stops ?: 0),
        (tiles.items?.toString() ?: EMPTY_VALUE) to pluralStringResource(R.plurals.hud_offer_items, tiles.items ?: 0))
    Row(Modifier.fillMaxWidth().testTag("offer.addon.tiles"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { (value, label) ->
            Column(Modifier.weight(1f)) {
                Text(value, style = AppTheme.num.xlNum, color = AppTheme.colors.text)
                Text(label, fontSize = 12.sp, color = AppTheme.colors.text3)
            }
        }
    }
}
