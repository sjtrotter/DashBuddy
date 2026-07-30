package cloud.trotter.dashbuddy.feature.bubble.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.model.pay.displayLabel
import cloud.trotter.dashbuddy.feature.bubble.R

// ---------------------------------------------------------------------------
// PostTask — receipt-style summary + body (#943 split of FlowCardItem.kt —
// moves only).
// ---------------------------------------------------------------------------

@Composable
internal fun postTaskSummary(snap: FlowCardSnapshot.PostTask): String {
    val store = snap.storeName?.let { "$it · " } ?: ""
    return store + Formats.money(snap.totalPay)
}

/**
 * PostTask body: receipt-style. Hero is total pay. Below: per-item
 * breakdown (base + tip) shown compactly.
 */
@Composable
internal fun PostTaskBody(snap: FlowCardSnapshot.PostTask) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        HeroBig(Formats.money(snap.totalPay))
        Caption(stringResource(R.string.flow_card_post_task_delivery_total))
        snap.parsedPay?.let { pay ->
            Column(
                modifier = Modifier.padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                pay.appPayComponents.forEach { item ->
                    BreakdownRow(item.type, Formats.money(item.amount))
                }
                pay.customerTips.forEach { tip ->
                    BreakdownRow(stringResource(R.string.flow_card_post_task_tip_format, tip.displayLabel), Formats.money(tip.amount))
                }
            }
        }
        snap.sessionEarningsAtCompletion?.let {
            Caption(stringResource(R.string.flow_card_post_task_session_format, Formats.money(it)))
        }
    }
}

@Composable
private fun BreakdownRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}
