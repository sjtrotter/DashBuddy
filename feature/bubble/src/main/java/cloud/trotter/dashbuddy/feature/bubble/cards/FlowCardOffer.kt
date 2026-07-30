package cloud.trotter.dashbuddy.feature.bubble.cards

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.component.AppChip
import cloud.trotter.dashbuddy.core.designsystem.component.AppGaugeRing
import cloud.trotter.dashbuddy.core.designsystem.theme.AppColors
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.core.designsystem.time.rememberNow
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatCountdown
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.model.offer.OfferBadge
import cloud.trotter.dashbuddy.domain.model.offer.joinDisplayStores
import cloud.trotter.dashbuddy.domain.model.order.OrderBadge
import cloud.trotter.dashbuddy.feature.bubble.R
import cloud.trotter.dashbuddy.feature.bubble.formatters.displayLabel
import cloud.trotter.dashbuddy.feature.bubble.formatters.offerBadgeIcon
import cloud.trotter.dashbuddy.feature.bubble.formatters.offerScoreColor
import cloud.trotter.dashbuddy.feature.bubble.formatters.offerVerdictColor
import cloud.trotter.dashbuddy.feature.bubble.formatters.offerVerdictContainer
import cloud.trotter.dashbuddy.feature.bubble.formatters.offerVerdictLabel

// ---------------------------------------------------------------------------
// Offer — summary line, body, live countdown, badges, and the manual
// Accept/Decline action row (#943 split of FlowCardItem.kt — moves only).
// ---------------------------------------------------------------------------

@Composable
internal fun offerSummary(snap: FlowCardSnapshot.Offer): String {
    val store = snap.storeNames.firstOrNull()?.takeIf { it.isNotBlank() } ?: stringResource(R.string.flow_card_offer_fallback_store)
    val pay = snap.payAmount?.let { " · ${Formats.money(it)}" } ?: ""
    // Outcome is rendered as a trailing chip in the header — see
    // CardHeader — so we omit it from the summary text.
    return "$store$pay"
}

/**
 * Offer body (redesign): a live expiry bar, a score ring beside the net $/hr
 * hero, a verdict banner (action + reason + quality), and badge pills. The
 * DoorDash expiry countdown also ticks in the header. (The Accept/Decline
 * action row + auto-action countdown land in Stage 2/3 of #110.)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OfferBody(snap: FlowCardSnapshot.Offer, isActive: Boolean) {
    val c = AppTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Live expiry progress bar (DoorDash's own offer countdown), active only.
        val expiresAt = snap.expiresAt
        val countdownSeconds = snap.countdownSeconds
        if (isActive && expiresAt != null && countdownSeconds != null && countdownSeconds > 0) {
            val now by rememberNow()
            val secsLeft = ((expiresAt - now) / 1000f).coerceAtLeast(0f)
            val frac = (secsLeft / countdownSeconds).coerceIn(0f, 1f)
            val barColor = offerExpiryColor(frac.toDouble(), c)
            Box(
                Modifier.fillMaxWidth().height(4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(c.line),
            ) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(barColor))
            }
        }

        // Score ring + net $/hr hero.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            snap.evaluationScore?.let { score ->
                // Ring colors track the evaluator's REAL decision boundaries (#400) — via the
                // [offerScoreColor] SSOT the notification gauge shares (#942).
                val sc = offerScoreColor(score, c)
                AppGaugeRing(
                    progress = (score / 100.0).toFloat(),
                    value = score.toInt().toString(),
                    label = stringResource(R.string.flow_card_score_gauge_label),
                    color = sc,
                    diameter = 60.dp,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                val hourly = snap.dollarsPerHour
                when {
                    hourly != null -> Text(
                        stringResource(R.string.flow_card_hourly_hero_format, Formats.money0(hourly)),
                        style = AppTheme.num.heroNum, color = c.text, maxLines = 1,
                    )
                    snap.payAmount != null -> Text(Formats.money(snap.payAmount!!), style = AppTheme.num.heroNum, color = c.text, maxLines = 1)
                }
                val net = snap.netPayAmount ?: snap.payAmount
                val netLabel = stringResource(R.string.flow_card_net_secondary_format, net?.let { Formats.money(it) } ?: "")
                val distanceLabel = snap.distanceMiles?.let { stringResource(R.string.flow_card_distance_secondary_format, Formats.decimal(it)) }
                val perMileLabel = snap.dollarsPerMile?.let { stringResource(R.string.flow_card_per_mile_secondary_format, Formats.money(it)) }
                val secondary = buildString {
                    if (net != null) append(netLabel)
                    distanceLabel?.let {
                        if (isNotEmpty()) append(" · ")
                        append(it)
                    }
                    perMileLabel?.let {
                        if (isNotEmpty()) append(" · ")
                        append(it)
                    }
                }
                if (secondary.isNotBlank()) {
                    Text(secondary, style = AppTheme.num.smNum, color = c.text2, maxLines = 1)
                }
            }
            // (#461: the item count now rides the [cart N] shop badge below, not the hero tier.)
        }

        // Verdict banner — action word + reason + quality chip, tinted by the action. The word and
        // its two tints come from the [offerVerdictLabel]/[offerVerdictColor] SSOT the heads-up
        // notification shares (#942); this used to be three independent `when`s over the raw enum
        // NAME (the #283 stringly-typed shape), which is how "REVIEW" here became "MANUAL REVIEW".
        snap.evaluationAction?.let { name ->
            val action = runCatching { OfferAction.valueOf(name) }.getOrNull()
            val vColor = offerVerdictColor(action, c)
            val vBg = offerVerdictContainer(action, c)
            val vIcon = when (action) {
                OfferAction.ACCEPT -> Icons.Default.Check
                OfferAction.DECLINE -> Icons.Default.Close
                else -> Icons.Default.Info
            }
            Surface(shape = MaterialTheme.shapes.small, color = vBg) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(vIcon, contentDescription = null, tint = vColor, modifier = Modifier.size(18.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            offerVerdictLabel(action),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = vColor,
                        )
                    }
                    snap.qualityLevel?.let { AppChip(it.displayLabel(), color = c.text3, container = c.surface3) }
                }
            }
        }

        // Badge row (#461): icon badges tinted by their brand color; the SHOP badge is co-icon-text
        // [🛒 N] — the shopping-chat cart + the item count. Badges without a drawable fall back to a
        // text pill so nothing is dropped.
        if (snap.badges.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                snap.badges.forEach { b ->
                    val (label, col) = badgeMeta(b, c)
                    when {
                        b == FlowCardSnapshot.Offer.SHOP_BADGE -> BadgeIcon(
                            iconRes = R.drawable.ic_chat_shopping_cart,
                            label = snap.itemCount.takeIf { it > 0 }?.toString(),
                            color = col,
                        )
                        else -> badgeIcon(b)
                            ?.let { BadgeIcon(iconRes = it, label = null, color = col) }
                            ?: AppChip(label, color = col, container = c.surface3)
                    }
                }
            }
        }

        // Footer: stores, joined by the :domain display SSOT (#942 — the heads-up notification
        // used to say "A + B" for the same offer this card called "A & B").
        val storeText = snap.storeNames.joinDisplayStores().ifBlank { null }
        if (storeText != null) Caption(storeText)
    }
}

/** Live m:ss offer-expiry countdown for the active offer card header. */
@Composable
internal fun OfferCountdownText(expiresAt: Long, countdownSeconds: Int) {
    val c = AppTheme.colors
    val now by rememberNow()
    val secsLeft = ((expiresAt - now) / 1000.0).coerceAtLeast(0.0)
    val frac = if (countdownSeconds > 0) (secsLeft / countdownSeconds).coerceIn(0.0, 1.0) else 0.0
    val col = offerExpiryColor(frac, c)
    Text(
        text = formatCountdown((secsLeft * 1000).toLong()),
        style = AppTheme.num.smNum,
        color = col,
        fontWeight = FontWeight.Bold,
    )
}

/**
 * Badge enum name → `ic_badge_*` drawable (#461); null means "no icon, render the text pill". The
 * SHOP badge is handled by the caller (the shopping-chat cart + the item count, [🛒 N]).
 * Delegates to the [offerBadgeIcon] SSOT (#578) so the bubble card and the offer notification share
 * one map and can't drift.
 */
@DrawableRes
private fun badgeIcon(name: String): Int? = offerBadgeIcon(name)

/**
 * Icon badge in the [AppChip] pill family (#461): a brand-tinted icon, optionally with a trailing
 * label — the SHOP badge rides its item count here ([🛒 N]). Mirrors AppChip's chip tokens.
 */
@Composable
private fun BadgeIcon(
    @DrawableRes iconRes: Int,
    label: String?,
    color: Color,
    container: Color = AppTheme.colors.surface3,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 7.dp, vertical = 4.dp),
    ) {
        Icon(painterResource(iconRes), contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        if (label != null) Text(label, style = AppTheme.num.chip, color = color)
    }
}

/**
 * Maps a badge wire name to its label + brand color for the pill row.
 *
 * The **label** is not this function's to own (#942): every badge is an
 * [OfferBadge]/[OrderBadge] constant carrying a `displayName`, and this map used to keep a second,
 * drifted set of labels that silently missed ≥8 branches — those fell to a lowercased-enum mangle
 * ("Age restricted 21 plus"). Only the synthetic
 * [SHOP_BADGE][FlowCardSnapshot.Offer.SHOP_BADGE] marker, which is no enum constant, keeps a
 * string-resource label of its own.
 *
 * The **color** is the one thing this owns — and it is keyed on the typed enums, not on raw name
 * strings (the #283 stringly-typed shape).
 */
@Composable
private fun badgeMeta(name: String, c: AppColors): Pair<String, Color> {
    if (name == FlowCardSnapshot.Offer.SHOP_BADGE) {
        return stringResource(R.string.flow_card_badge_shop_and_deliver) to c.stPickup
    }
    OfferBadge.entries.firstOrNull { it.name == name }?.let { badge ->
        return badge.displayName to when (badge) {
            OfferBadge.HIGH_PAYING -> c.good
            OfferBadge.PRIORITY_ACCESS -> c.stOffer
            else -> c.neutral
        }
    }
    OrderBadge.entries.firstOrNull { it.name == name }?.let { badge ->
        return badge.displayName to when (badge) {
            OrderBadge.RED_CARD -> c.bad
            OrderBadge.ALCOHOL -> c.warn
            OrderBadge.LARGE_ORDER, OrderBadge.PIZZA_BAG -> c.neutral
        }
    }
    // Unreachable today — the snapshot's badge list is minted from the two enums plus SHOP — but a
    // future marker renders as itself rather than vanishing.
    return name to c.neutral
}

/** Manual Accept / Decline buttons on the active offer card (#110 Stage 2b). */
@Composable
internal fun OfferActionRow(onAccept: () -> Unit, onDecline: () -> Unit) {
    val c = AppTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onDecline,
            modifier = Modifier.weight(1f),
            border = BorderStroke(1.5.dp, c.bad),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = c.bad),
        ) { Text(stringResource(R.string.flow_card_action_decline), fontWeight = FontWeight.Bold) }
        Button(
            onClick = onAccept,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = c.good, contentColor = c.textInv),
        ) { Text(stringResource(R.string.flow_card_action_accept), fontWeight = FontWeight.Bold) }
    }
}

/** THE offer-expiry color tiers (#406) — previously written twice in this file. */
private fun offerExpiryColor(frac: Double, c: AppColors): Color = when {
    frac > 0.45 -> c.good
    frac > 0.2 -> c.warn
    else -> c.bad
}
