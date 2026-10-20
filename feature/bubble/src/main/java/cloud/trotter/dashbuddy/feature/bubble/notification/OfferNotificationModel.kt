package cloud.trotter.dashbuddy.feature.bubble.notification

import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.feature.bubble.hud.*

/** Text and drawing inputs only; no snapshot economics or evaluation happens at this edge. */
data class OfferNotificationModel(
    val store: String?,
    val pay: Double?,
    val expiresAt: Long?,
    val ring: RingModel?,
    val bar: PayBarModel?,
    val rates: List<RateText>,
    val addOnFigures: String?,
) {
    companion object {
        fun from(card: OfferCardModel): OfferNotificationModel {
            val scored = card as? OfferCardModel.Scored
            return OfferNotificationModel(card.head.store, card.head.pay, card.head.expiresAt,
                scored?.ring, scored?.bar, scored?.gauges?.map { it.rateParts() }.orEmpty(),
                (card as? OfferCardModel.AddOnRaw)?.tiles?.let(::addOnFigures))
        }
    }
}

data class RateText(val value: String, val unit: String, val tone: GaugeTone)

/** The HUD already formats the value in GaugeMapper; the small suffix is separate, never reparsed. */
internal fun GaugeModel.rateParts(): RateText = RateText(rateText, when (kind) {
    GaugeKind.HOUR -> "/hr"
    GaugeKind.MILE -> "/mi"
    GaugeKind.ITEM -> "/ea"
}, tone)

/** Former bubble_offer_addon_* copy, owned here now that the notification is a pure text model. */
private fun addOnFigures(tiles: DeltaTiles): String = buildList {
    tiles.minutes?.let { add("+$it min") }
    tiles.miles?.let { add("+${Formats.decimal(it)} mi") }
    tiles.stops?.let { add("+$it " + if (it == 1) "stop" else "stops") }
    tiles.items?.let { add("+$it " + if (it == 1) "item" else "items") }
}.joinToString(" · ")
