package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.trotter.dashbuddy.core.designsystem.component.AppGaugeRing
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.core.designsystem.time.rememberNow
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatCountdown
import cloud.trotter.dashbuddy.feature.bubble.R
import cloud.trotter.dashbuddy.feature.bubble.formatters.badgeMeta
import cloud.trotter.dashbuddy.feature.bubble.formatters.offerExpiryColor
import cloud.trotter.dashbuddy.feature.bubble.formatters.offerScoreColor

@Composable
fun OfferCard(model: OfferCardModel, onAccept: () -> Unit, onDecline: () -> Unit) {
    val now by rememberNow()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
        Column(Modifier.weight(1f, fill = false).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)).background(AppTheme.colors.surface)
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OfferHeader(model.head, now)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    OfferHero(model.head.pay)
                    model.head.addedPay?.let {
                        Box(Modifier.height(26.dp).clip(RoundedCornerShape(8.dp))
                            .background(AppTheme.colors.stOfferBg).padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.hud_offer_added, Formats.money(it)),
                                color = AppTheme.colors.stOffer, style = AppTheme.num.smNum)
                        }
                    }
                }
                if (model is OfferCardModel.Scored) OfferRing(model.ring)
            }
            OfferBadges(model.badges)
            when (model) {
                is OfferCardModel.Scored -> {
                    PayBar(model.bar)
                    model.gauges.forEach { OfferGauge(it) }
                }
                is OfferCardModel.AddOnRaw -> {
                    AddOnDeltaRow(model.tiles)
                    Text(stringResource(R.string.hud_offer_addon_note), color = AppTheme.colors.text2,
                        fontSize = 14.sp)
                }
            }
            TripLine(model.trip)
        }
        Spacer(Modifier.height(12.dp))
        OfferActions(onAccept, onDecline)
    }
}

@Composable
private fun OfferHeader(head: OfferHead, now: Long) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(head.store ?: stringResource(R.string.flow_card_offer_fallback_store), Modifier.weight(1f),
            fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.text,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        head.expiresAt?.let { expiry ->
            val left = (expiry - now).coerceAtLeast(0)
            val fraction = if (expiry > head.presentedAt) left.toDouble() / (expiry - head.presentedAt) else 0.0
            val time = formatCountdown(left)
            val description = stringResource(R.string.hud_offer_cd_left, time)
            Row(Modifier.testTag("offer.countdown").semantics { contentDescription = description },
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(time, style = AppTheme.num.xlNum, color = offerExpiryColor(fraction, AppTheme.colors))
                Text(stringResource(R.string.hud_offer_left), fontSize = 14.sp, color = AppTheme.colors.text2)
            }
        }
    }
}

@Composable
private fun OfferHero(pay: Double?) {
    val parts = pay?.let(Formats::moneyParts)
    Row(Modifier.testTag("offer.hero"), verticalAlignment = Alignment.Bottom) {
        if (parts == null) Text(EMPTY_VALUE, style = AppTheme.num.stateBigNum, color = AppTheme.colors.text)
        else {
            Text(parts.sign + parts.symbol, style = AppTheme.num.heroUnitNum, color = AppTheme.colors.text2,
                modifier = Modifier.alignByBaseline())
            Text(parts.whole, style = AppTheme.num.stateBigNum, color = AppTheme.colors.text,
                modifier = Modifier.alignByBaseline())
            Text(parts.fraction, style = AppTheme.num.heroUnitNum, color = AppTheme.colors.text2,
                modifier = Modifier.alignByBaseline())
        }
    }
}

@Composable
private fun OfferRing(ring: RingModel) {
    val description = ring.score?.let { stringResource(R.string.hud_offer_cd_score, it) }
        ?: stringResource(R.string.hud_offer_cd_no_score)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AppGaugeRing((ring.score ?: 0) / 100f, ring.score?.toString() ?: EMPTY_VALUE,
            modifier = Modifier.testTag("offer.ring").semantics { contentDescription = description },
            color = ring.score?.let { offerScoreColor(it.toDouble(), AppTheme.colors) } ?: AppTheme.colors.neutral,
            diameter = 64.dp, valueStyle = AppTheme.num.xlNum)
        ring.was?.let { Text(stringResource(R.string.hud_offer_was, it), color = AppTheme.colors.text3, fontSize = 12.sp) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OfferBadges(badges: List<OfferBadgeModel>) {
    val c = AppTheme.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        badges.forEach { badge ->
            val (label, ink) = if (badge.name == "ADD_ON") stringResource(R.string.hud_offer_addon) to c.stOffer
                else badgeMeta(badge.name, c)
            val bg = when (ink) { c.good -> c.goodBg; c.warn -> c.warnBg; else -> c.stOfferBg }
            Box(Modifier.height(26.dp).clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center) { Text(label, color = ink, fontSize = 12.sp) }
        }
    }
}
