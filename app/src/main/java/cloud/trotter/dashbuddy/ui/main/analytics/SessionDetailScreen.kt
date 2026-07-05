package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.dashbuddy.core.designsystem.component.AppCallout
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.component.AppChip
import cloud.trotter.dashbuddy.core.designsystem.component.AppStatTile
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.DeliveryRecord
import cloud.trotter.dashbuddy.domain.analytics.SessionDetail
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatClockTime
import cloud.trotter.dashbuddy.domain.format.formatDuration
import cloud.trotter.dashbuddy.domain.format.formatShortDate
import kotlin.math.roundToInt

/** Below this the unattributed pay is effectively zero — no callout (avoids a "$0.00" flag). */
private const val UNATTRIBUTED_EPSILON = 0.005

/** Placeholder shown for a figure that has no measurable value yet (dashboard parity). */
private const val EMPTY_VALUE = "—"

/**
 * The read-only per-dash drill-down (#650 PR A): one dash expanded top→bottom — a header card
 * (date/time span, platform, duration, gross/miles/deliveries), the per-dash unattributed-pay flag,
 * and the per-delivery breakdown in completion order. A **review** surface (Principle 1 — UDF, state
 * in; no intents, corrections are PR B): reactive-fresh via the read-model Flow, no `rememberNow()`
 * tick (a historical dash's figures are fixed; the #657 reframe). Every number routes through the
 * [Formats]/[formatShortDate]/[formatClockTime]/[formatDuration] SSOTs. Store names are merchants —
 * fine to render (Principle 7 governs logs, not this UI); no customer hashes are surfaced.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    onBack: () -> Unit,
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dash detail") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val detail = uiState.detail
        when {
            detail != null -> DashDetailContent(
                detail = detail,
                modifier = Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
                    .fillMaxSize(),
            )
            // Post-load, a null detail means no session row exists for this id.
            !uiState.loading -> CenteredMessage("Dash not found.", Modifier.padding(padding))
            // Pre-first-emission: keep the frame empty (the read-model emits promptly).
            else -> Box(Modifier.padding(padding).fillMaxSize())
        }
    }
}

@Composable
private fun DashDetailContent(detail: SessionDetail, modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HeaderCard(detail)
        if (detail.unattributedPay > UNATTRIBUTED_EPSILON) {
            AppCallout(
                text = "${Formats.money(detail.unattributedPay)} unaccounted on this dash — the " +
                    "platform reported more than the captured deliveries. Corrections land in the " +
                    "next phase (#650).",
                container = AppTheme.colors.warnBg,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        DeliveriesCard(detail.deliveries)
    }
}

@Composable
private fun HeaderCard(detail: SessionDetail) {
    val c = AppTheme.colors
    val session = detail.session
    val hasReported = session.reportedEarnings != null
    val gross = session.reportedEarnings ?: detail.deliveredPay
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = formatShortDate(session.startedAt),
                    style = MaterialTheme.typography.titleMedium,
                    color = c.text,
                )
                Spacer(Modifier.height(2.dp))
                val endText = session.endedAt?.let { formatClockTime(it) } ?: EMPTY_VALUE
                Text(
                    text = "${formatClockTime(session.startedAt)}–$endText",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.text3,
                )
            }
            // Platform label is registry-resolved (never a literal) — Principle 8.
            AppChip(text = session.platform.shortName.ifEmpty { session.platform.displayName })
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Duration ${dashDuration(session.startedAt, session.endedAt, session.reportedDurationMillis)}",
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppStatTile(
                label = if (hasReported) "Gross (reported)" else "Gross (captured)",
                value = Formats.money(gross),
                modifier = Modifier.weight(1f),
            )
            AppStatTile(
                label = "Miles",
                value = session.miles?.let { Formats.decimal(it) } ?: EMPTY_VALUE,
                modifier = Modifier.weight(1f),
            )
            AppStatTile(
                label = "Deliveries",
                value = Formats.commaInt(session.deliveries),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The dash's wall-clock duration: measured `endedAt − startedAt` when the dash closed, else the
 * platform-reported duration, else an em dash. All through the [formatDuration] SSOT.
 */
private fun dashDuration(startedAt: Long, endedAt: Long?, reportedDurationMillis: Long?): String =
    when {
        endedAt != null -> formatDuration(endedAt - startedAt)
        reportedDurationMillis != null -> formatDuration(reportedDurationMillis)
        else -> EMPTY_VALUE
    }

@Composable
private fun DeliveriesCard(deliveries: List<DeliveryRecord>) {
    val c = AppTheme.colors
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "DELIVERIES", style = MaterialTheme.typography.labelMedium, color = c.text3)
        Spacer(Modifier.height(10.dp))
        if (deliveries.isEmpty()) {
            EmptyRow("No deliveries captured for this dash.")
        } else {
            deliveries.forEachIndexed { index, delivery ->
                if (index > 0) Spacer(Modifier.height(14.dp))
                DeliveryRow(delivery)
            }
        }
    }
}

@Composable
private fun DeliveryRow(delivery: DeliveryRecord) {
    val c = AppTheme.colors
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(
                text = delivery.storeName ?: "Unknown store",
                style = MaterialTheme.typography.bodyMedium,
                color = c.text,
            )
            Text(
                text = formatClockTime(delivery.completedAt),
                style = MaterialTheme.typography.bodySmall,
                color = c.text3,
            )
            travelLine(delivery)?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = c.text3)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = delivery.realizedPay?.let { Formats.money(it) } ?: EMPTY_VALUE,
                style = AppTheme.num.smNum,
                color = c.text,
            )
            delivery.tip?.let {
                Text(
                    text = "incl. ${Formats.money(it)} tip",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.text3,
                )
            }
            val net = delivery.netProfit
            Text(
                text = "net ${net?.let { Formats.money(it) } ?: EMPTY_VALUE}",
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    net == null -> c.text3
                    net >= 0.0 -> c.good
                    else -> c.bad
                },
                textAlign = TextAlign.End,
            )
        }
    }
}

/** "3.2 mi · 14 min" — only the parts actually measured; null when neither is present. */
private fun travelLine(delivery: DeliveryRecord): String? {
    val parts = buildList {
        delivery.realizedMiles?.let { add("${Formats.decimal(it)} mi") }
        delivery.realizedMinutes?.let { add("${it.roundToInt()} min") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun EmptyRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = AppTheme.colors.text3,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun CenteredMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.text3)
    }
}
