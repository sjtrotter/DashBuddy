package cloud.trotter.dashbuddy.feature.bubble.hud.cashtip

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.DeliveryRecord
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatClockTime
import cloud.trotter.dashbuddy.feature.bubble.R
import cloud.trotter.dashbuddy.feature.bubble.hud.keypad.Keypad

/** Pure entry surface; every tap is an intent owned by the app host. */
@Composable
fun ColumnScope.CashTipSheet(
    model: CashTipSheetModel,
    onSelectDelivery: (Long) -> Unit,
    onExpand: () -> Unit,
    onBack: () -> Unit,
    onSign: (CashTipSign) -> Unit,
    onAmount: (Long) -> Unit,
    onOther: () -> Unit,
    onDigit: (Int) -> Unit,
    onPoint: () -> Unit,
    onBackspace: () -> Unit,
    onDoneOther: () -> Unit,
    onCommit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.hud_cash_tip_title), Modifier.weight(1f), color = AppTheme.colors.text,
                style = MaterialTheme.typography.titleSmall)
            Text(Formats.money(model.totalCents / 100.0), color = AppTheme.colors.text, style = AppTheme.num.mdNum)
            if (model.page == CashTipPage.EXPANDED) TextButton(onClick = onBack) {
                Text(stringResource(R.string.hud_cash_tip_fewer))
                Icon(Icons.Outlined.KeyboardArrowUp, null)
            }
        }
        when (model.page) {
            CashTipPage.EXPANDED -> Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize().testTag("cashTip.deliveries"), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(model.ordered, key = { it.eventSequenceId }) { row ->
                        DeliveryRow(row, row.eventSequenceId == model.selectedDeliveryId, !model.busy, onSelectDelivery)
                    }
                }
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(16.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, AppTheme.colors.surface))))
            }
            CashTipPage.OTHER_AMOUNT -> {
                Row(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(model.otherAmount.ifEmpty { EMPTY_VALUE }, Modifier.weight(1f),
                        color = AppTheme.colors.text, style = AppTheme.num.mdNum)
                    Keypad(onDigit, onPoint, onBackspace)
                }
                Button(onClick = onDoneOther, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.hud_cash_tip_done))
                }
            }
            CashTipPage.COMPACT -> {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    if (model.deliveries.isEmpty()) Text(stringResource(R.string.hud_cash_tip_empty), color = AppTheme.colors.text2)
                    model.ordered.take(2).forEach { row ->
                        DeliveryRow(row, row.eventSequenceId == model.selectedDeliveryId, !model.busy, onSelectDelivery)
                    }
                    if (model.deliveries.isNotEmpty()) TextButton(onClick = onExpand, enabled = !model.busy,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.hud_cash_tip_another), Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            CashTipSign.entries.forEach { sign ->
                                TextButton(onClick = { onSign(sign) }, enabled = !model.busy,
                                    modifier = Modifier.sizeIn(minWidth = 40.dp, minHeight = 40.dp).testTag("cashTip.sign.$sign"),
                                    contentPadding = PaddingValues(4.dp)) {
                                    Text(if (sign == CashTipSign.PLUS) "+" else "−",
                                        color = if (model.sign == sign) AppTheme.colors.accent else AppTheme.colors.text3)
                                }
                            }
                        }
                        listOf(1, 5, 10, 20).forEach { dollars ->
                            TextButton(onClick = { onAmount(dollars * 100L) }, enabled = !model.busy,
                                modifier = Modifier.weight(1f), contentPadding = PaddingValues(2.dp)) {
                                Text(Formats.money0(dollars.toDouble()))
                            }
                        }
                        TextButton(onClick = onOther, enabled = !model.busy,
                            modifier = Modifier.weight(1.3f), contentPadding = PaddingValues(2.dp)) {
                            Text(stringResource(R.string.hud_cash_tip_other))
                        }
                    }
                }
                Button(onClick = onCommit, enabled = model.canCommit, modifier = Modifier.fillMaxWidth().testTag("cashTip.commit")) {
                    Text(stringResource(if (model.sign == CashTipSign.MINUS) R.string.hud_cash_tip_remove else R.string.hud_cash_tip_add,
                        Formats.money(model.totalCents / 100.0)))
                }
            }
        }
        if (model.failed) Text(stringResource(R.string.hud_cash_tip_failed), color = AppTheme.colors.warn,
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun DeliveryRow(row: DeliveryRecord, selected: Boolean, enabled: Boolean, onSelect: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = { onSelect(row.eventSequenceId) }, enabled = enabled,
            modifier = Modifier.testTag("cashTip.delivery.${row.eventSequenceId}"))
        TextButton(onClick = { onSelect(row.eventSequenceId) }, enabled = enabled, modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(row.storeName ?: stringResource(R.string.hud_cash_tip_delivery), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = AppTheme.colors.text, style = MaterialTheme.typography.labelLarge)
                Text(formatClockTime(row.completedAt), color = AppTheme.colors.text3, style = MaterialTheme.typography.labelSmall)
            }
            Text(row.realizedPay?.let(Formats::money) ?: EMPTY_VALUE, color = AppTheme.colors.text2,
                style = AppTheme.num.cardNum)
        }
    }
}
