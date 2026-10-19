package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.core.designsystem.time.rememberTimeFormatter
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.feature.bubble.R
import cloud.trotter.dashbuddy.feature.bubble.hud.expense.ExpensePick
import cloud.trotter.dashbuddy.feature.bubble.hud.expense.labelResource

@Composable
internal fun LogThinRow(item: LogItem) {
    val c = AppTheme.colors
    Column(Modifier.fillMaxWidth().testTag("hud.log.${item.key}")) {
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LogTime(rememberTimeFormatter()(item.at))
            Box(Modifier.weight(1f)) {
                when (item) {
                    is OfferRow -> Text(stringResource(when (item.outcome) {
                        AppEventType.OFFER_DECLINED -> R.string.hud_log_declined
                        AppEventType.OFFER_TIMEOUT -> R.string.hud_log_timed_out
                        else -> R.string.hud_log_accepted_row
                    }, item.store ?: stringResource(R.string.hud_log_offer_unknown_store)),
                        color = if (item.dimmed) c.neutral else c.text2, fontSize = 14.sp)
                    is ExpenseRow -> {
                        val category = stringResource(ExpensePick.Expense(item.category).labelResource)
                        val label = item.note?.takeIf { it.isNotBlank() }?.let { "$category · $it" } ?: category
                        Text(stringResource(R.string.hud_log_expense, label),
                            color = c.text2, fontSize = 14.sp)
                    }
                    is FuelRow -> LogText(logTextParts(if (item.pricePerGallon != null) R.string.hud_log_gas_price else R.string.hud_log_gas_total,
                        listOf(LogTextPart(Formats.money(item.pricePerGallon ?: item.total)))), color = c.neutral)
                    is DispatcherRow -> Text(item.text, color = c.neutral, fontSize = 12.sp)
                    is JobCardModel -> Unit
                }
            }
            when (item) {
                is OfferRow -> LogQuote(item.pay, item.miles, if (item.dimmed) c.neutral else c.text, dimmed = item.dimmed)
                is ExpenseRow -> Text(Formats.money(-item.amount), style = AppTheme.num.mdNum.copy(fontWeight = FontWeight.Bold), color = c.text)
                else -> Unit
            }
        }
        HorizontalDivider(color = c.surface2, thickness = 1.dp)
    }
}
