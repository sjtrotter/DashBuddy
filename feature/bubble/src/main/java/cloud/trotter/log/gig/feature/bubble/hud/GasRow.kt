package cloud.trotter.log.gig.feature.bubble.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.feature.bubble.R

@Composable
fun GasRow(model: GasRowModel, onSetPrice: (Float) -> Unit, onRefresh: () -> Unit, onResumeAuto: () -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val c = AppTheme.colors
    val label = stringResource(R.string.hud_gas_click_label)
    Column {
        Canvas(Modifier.fillMaxWidth().height(1.dp)) { drawLine(c.line, Offset.Zero, Offset(size.width, 0f)) }
        Row(Modifier.fillMaxWidth().height(56.dp).clickable(onClickLabel = label) { editing = !editing },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Outlined.LocalGasStation, null, tint = c.text2, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.hud_gas_label), color = c.text2, style = MaterialTheme.typography.labelMedium)
            Text(moneyOrEmpty(model.price?.toDouble()), color = c.text, style = AppTheme.num.mdNum)
            Text(stringResource(R.string.hud_gas_per_gal), color = c.text3, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text(stringResource(if (model.auto) R.string.hud_gas_auto else R.string.hud_gas_manual), color = c.text3,
                style = MaterialTheme.typography.labelSmall)
            Icon(Icons.Outlined.Edit, null, tint = c.text3, modifier = Modifier.size(16.dp))
        }
        if (editing) GasQuickEdit(model.price, model.auto, model.refreshing, model.refreshError, onSetPrice, onRefresh, onResumeAuto)
    }
}
