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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
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
import cloud.trotter.dashbuddy.domain.analytics.PayBasis
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
                onAddManualDelivery = viewModel::addManualDelivery,
                onAdjustPay = viewModel::adjustPay,
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
private fun DashDetailContent(
    detail: SessionDetail,
    onAddManualDelivery: (pay: Double, tip: Double?, storeName: String?, note: String?) -> Unit,
    onAdjustPay: (targetEventSequenceId: Long, newPay: Double, note: String?) -> Unit,
    modifier: Modifier,
) {
    // Correction dialog state — hoisted here, stateless children below (Principle 1 / 3).
    var showAddDialog by remember { mutableStateOf(false) }
    var adjustTarget by remember { mutableStateOf<DeliveryRecord?>(null) }

    val hasCallout = detail.unattributedPay > UNATTRIBUTED_EPSILON

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HeaderCard(detail)
        if (hasCallout) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppCallout(
                    text = "${Formats.money(detail.unattributedPay)} unaccounted on this dash — the " +
                        "platform reported more than the captured deliveries.",
                    container = AppTheme.colors.warnBg,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { showAddDialog = true }) { Text("Add missed delivery") }
            }
        }
        // With no callout, the add entry point lives at the bottom of the deliveries card — a missed
        // delivery can exist without a reported excess.
        DeliveriesCard(
            deliveries = detail.deliveries,
            showAddButton = !hasCallout,
            onAddMissed = { showAddDialog = true },
            onAdjust = { adjustTarget = it },
        )
    }

    if (showAddDialog) {
        AddMissedDeliveryDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { pay, tip, store, note ->
                onAddManualDelivery(pay, tip, store, note)
                showAddDialog = false
            },
        )
    }
    adjustTarget?.let { target ->
        AdjustPayDialog(
            currentPay = target.realizedPay,
            onDismiss = { adjustTarget = null },
            onConfirm = { newPay, note ->
                onAdjustPay(target.eventSequenceId, newPay, note)
                adjustTarget = null
            },
        )
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
private fun DeliveriesCard(
    deliveries: List<DeliveryRecord>,
    showAddButton: Boolean,
    onAddMissed: () -> Unit,
    onAdjust: (DeliveryRecord) -> Unit,
) {
    val c = AppTheme.colors
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "DELIVERIES", style = MaterialTheme.typography.labelMedium, color = c.text3)
        Spacer(Modifier.height(10.dp))
        if (deliveries.isEmpty()) {
            EmptyRow("No deliveries captured for this dash.")
        } else {
            deliveries.forEachIndexed { index, delivery ->
                if (index > 0) Spacer(Modifier.height(14.dp))
                DeliveryRow(delivery, onAdjust = { onAdjust(delivery) })
            }
        }
        if (showAddButton) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onAddMissed) { Text("Add missed delivery") }
        }
    }
}

@Composable
private fun DeliveryRow(delivery: DeliveryRecord, onAdjust: () -> Unit) {
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
            // #691: a receipt-less shop delivery's pay is an equal-split ESTIMATE of the accepted
            // offer, not a captured receipt — disclose it (never-silent, the #689 precedent).
            if (delivery.payBasis == PayBasis.OFFER_PAY) {
                Text(
                    text = "est. offer pay",
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
        IconButton(onClick = onAdjust) {
            Icon(
                Icons.Default.Edit,
                contentDescription = "Adjust pay",
                tint = AppTheme.colors.text3,
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

/** A blank or non-blank-but-positive number parses; a non-blank invalid one does not. */
private fun optionalMoney(text: String): Double? = text.takeIf { it.isNotBlank() }?.toDoubleOrNull()
private fun String.isBlankOrValidMoney(): Boolean = isBlank() || (toDoubleOrNull()?.let { it > 0.0 } == true)
private fun String.isValidMoney(): Boolean = toDoubleOrNull()?.let { it > 0.0 } == true

/**
 * Add-a-missed-delivery dialog (#650) — store (optional), pay (required, > 0), tip (optional, > 0
 * when present), note (optional). Confirm stays disabled until pay is valid and any entered tip is
 * valid. Stateless w/ hoisted local field state per the codebase idiom.
 */
@Composable
private fun AddMissedDeliveryDialog(
    onDismiss: () -> Unit,
    onConfirm: (pay: Double, tip: Double?, storeName: String?, note: String?) -> Unit,
) {
    var store by remember { mutableStateOf("") }
    var pay by remember { mutableStateOf("") }
    var tip by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val valid = pay.isValidMoney() && tip.isBlankOrValidMoney()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add missed delivery") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyField(store, { store = it }, "Store (optional)", numeric = false)
                MoneyField(pay, { pay = it }, "Pay")
                MoneyField(tip, { tip = it }, "Tip (optional)")
                MoneyField(note, { note = it }, "Note (optional)", numeric = false)
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onConfirm(
                        pay.toDouble(),
                        optionalMoney(tip),
                        store.trim().takeIf { it.isNotBlank() },
                        note.trim().takeIf { it.isNotBlank() },
                    )
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Adjust-pay dialog (#650) — prefilled with the delivery's current pay, note optional. Confirm stays
 * disabled until the new pay is a positive number.
 */
@Composable
private fun AdjustPayDialog(
    currentPay: Double?,
    onDismiss: () -> Unit,
    onConfirm: (newPay: Double, note: String?) -> Unit,
) {
    // Prefill with a parse-faithful value (NOT Formats.decimal, which rounds to 1 digit and would
    // silently drop cents from the round-trip).
    var pay by remember { mutableStateOf(currentPay?.toString() ?: "") }
    var note by remember { mutableStateOf("") }
    val valid = pay.isValidMoney()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adjust pay") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyField(pay, { pay = it }, "Pay")
                MoneyField(note, { note = it }, "Note (optional)", numeric = false)
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onConfirm(pay.toDouble(), note.trim().takeIf { it.isNotBlank() }) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MoneyField(value: String, onValueChange: (String) -> Unit, label: String, numeric: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth(),
    )
}
