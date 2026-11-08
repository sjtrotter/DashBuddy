package cloud.trotter.log.gig.ui.main.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.component.AppCard
import cloud.trotter.log.gig.core.designsystem.component.AppPlaceholder
import cloud.trotter.log.gig.core.designsystem.component.AppPlaceholderLine
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.core.designsystem.time.rememberNow
import cloud.trotter.log.gig.domain.analytics.ANALYTICS_MONEY_EPSILON
import cloud.trotter.log.gig.domain.analytics.SessionDetail
import cloud.trotter.log.gig.domain.analytics.checks.MoneyCheck
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.domain.format.formatClockTime

/** At or above this font scale the page's side-by-side rows stack (the 200 % layouts). */
private const val LARGE_TEXT_FONT_SCALE = 1.5f

@Composable
internal fun isLargeText(): Boolean = LocalDensity.current.fontScale >= LARGE_TEXT_FONT_SCALE

/** "$12.50", or "$12.50 est." when [estimated]. */
@Composable
internal fun moneyText(amount: Double, estimated: Boolean): String =
    Formats.money(amount).let { if (estimated) stringResource(R.string.shift_page_estimated_format, it) else it }

/** "DoorDash, 4:58 PM to 11:04 PM", or "DoorDash, since 4:58 PM" while the shift runs. */
@Composable
internal fun ShiftAppTimesLine(detail: SessionDetail) {
    val s = detail.session
    val app = s.platform.displayName
    val start = formatClockTime(s.startedAt)
    Text(
        text = s.endedAt?.let { stringResource(R.string.shift_page_app_times_format, app, start, formatClockTime(it)) }
            ?: stringResource(R.string.shift_page_app_since_format, app, start),
        style = MaterialTheme.typography.bodyMedium,
        color = AppTheme.colors.text2,
    )
}

/** Earned, with the cash inside it named under it; the em dash when nothing is known. */
@Composable
internal fun EarnedBlock(money: ShiftMoney) {
    Column {
        Text(stringResource(R.string.shift_page_earned_label), style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.text2)
        Text(money.earned?.let { Formats.money(it) } ?: EMPTY_VALUE, style = AppTheme.num.heroNum, color = AppTheme.colors.text)
        if (money.cash > ANALYTICS_MONEY_EPSILON) {
            Text(
                stringResource(R.string.shift_page_cash_included_format, Formats.money(money.cash)),
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.text2,
            )
        }
    }
}

/** Hours online, miles driven and Est. Profit/hr; the hours tick while the shift runs. Stacked at large text. */
@Composable
internal fun ShiftFigures(detail: SessionDetail, money: ShiftMoney) {
    val ended = detail.session.endedAt
    val online = if (ended != null) {
        onlineMillis(detail, ended)
    } else {
        val now by rememberNow()
        onlineMillis(detail, now)
    }
    val figures = listOf(
        Formats.decimal(online / 3_600_000.0) to stringResource(R.string.shift_page_hours_online),
        (detail.session.miles?.let { Formats.decimal(it, 0) } ?: EMPTY_VALUE) to stringResource(R.string.shift_page_miles_driven),
        (money.profitPerHour(online)?.let { Formats.money(it) } ?: EMPTY_VALUE) to stringResource(R.string.shift_page_profit_per_hour),
    )
    val tile: @Composable (Modifier, Pair<String, String>) -> Unit = { modifier, (value, label) ->
        AppCard(modifier) {
            Text(value, style = AppTheme.num.lgNum, color = AppTheme.colors.text)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.text2)
        }
    }
    if (isLargeText()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { figures.forEach { tile(Modifier.fillMaxWidth(), it) } }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { figures.forEach { tile(Modifier.weight(1f), it) } }
    }
}

/** The app's total beside delivery pay, and one line naming the higher; no line when they agree. */
@Composable
internal fun MoneyCheckBlock(check: MoneyCheck, appName: String) {
    val c = AppTheme.colors
    val totalLabel = stringResource(R.string.shift_page_app_total_format, appName)
    val payLabel = stringResource(R.string.shift_page_delivery_pay)
    AppCard(Modifier.fillMaxWidth()) {
        CheckRow(totalLabel, Formats.money(check.appTotal))
        CheckRow(payLabel, moneyText(check.deliveryPay, check.deliveryPayEstimated))
        check.higher?.let { higher ->
            Text(
                higherLine(higher, check.difference, appName),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = c.text,
            )
        }
    }
}

@Composable
private fun CheckRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.text)
        Text(value, style = AppTheme.num.smNum, color = AppTheme.colors.text)
    }
}

/** The shift's corrections: add a missed delivery, set the app total and correct miles (ended shifts). Stacked at large text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CorrectionButtons(ended: Boolean, onAddMissed: () -> Unit, onSetTotal: () -> Unit, onCorrectMiles: () -> Unit) {
    val buttons: List<Pair<Int, () -> Unit>> = buildList {
        add(R.string.session_detail_add_missed_delivery to onAddMissed)
        if (ended) {
            add(R.string.shift_page_set_app_total to onSetTotal)
            add(R.string.session_detail_correct_miles to onCorrectMiles)
        }
    }
    val button: @Composable (Pair<Int, () -> Unit>) -> Unit = { (label, onClick) ->
        TextButton(onClick = onClick) { Text(stringResource(label)) }
    }
    if (isLargeText()) {
        Column { buttons.forEach { button(it) } }
    } else {
        // Wraps whole buttons to the next line instead of breaking a label across two.
        FlowRow { buttons.forEach { button(it) } }
    }
}

/** Line widths of the placeholder timeline, so the rows read as text of varied length. */
private val TIMELINE_LINES = listOf(0.70f, 0.45f, 0.85f, 0.35f, 0.60f)

/** The page before its first read: grey blocks shaped like the summary and the timeline; TalkBack reads "Loading". */
@Composable
internal fun ShiftPagePlaceholder(modifier: Modifier = Modifier) {
    val loading = stringResource(R.string.common_loading)
    Column(
        modifier.fillMaxSize().padding(16.dp).clearAndSetSemantics { contentDescription = loading },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppPlaceholderLine(0.6f, contentDescription = null)
        AppPlaceholder(Modifier.fillMaxWidth(0.5f).height(56.dp), contentDescription = null)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { AppPlaceholder(Modifier.weight(1f).height(64.dp), contentDescription = null) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TIMELINE_LINES.forEach { AppPlaceholderLine(it, contentDescription = null) }
        }
    }
}
