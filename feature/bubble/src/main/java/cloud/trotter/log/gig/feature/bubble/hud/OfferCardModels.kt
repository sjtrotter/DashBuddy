package cloud.trotter.log.gig.feature.bubble.hud

import cloud.trotter.log.gig.domain.model.offer.OfferBadge
import cloud.trotter.log.gig.domain.model.order.OrderBadge

/** UI-only output: no offer identity, parsed offer, event or persistence payload can escape here. */
sealed interface OfferCardModel {
    val head: OfferHead
    val badges: List<OfferBadgeModel>
    val trip: TripLineModel

    sealed interface Scored : OfferCardModel {
        val bar: PayBarModel
        val gauges: List<GaugeModel>
    }
    data class Standard(
        override val head: OfferHead, override val badges: List<OfferBadgeModel>,
        override val trip: TripLineModel,
        override val bar: PayBarModel, override val gauges: List<GaugeModel>,
    ) : Scored
    data class Combined(
        override val head: OfferHead, override val badges: List<OfferBadgeModel>,
        override val trip: TripLineModel,
        override val bar: PayBarModel, override val gauges: List<GaugeModel>,
    ) : Scored
    data class AddOnRaw(
        override val head: OfferHead, override val badges: List<OfferBadgeModel>,
        override val trip: TripLineModel, val tiles: DeltaTiles,
    ) : OfferCardModel
}

data class OfferHead(val store: String?, val pay: Double?, val presentedAt: Long,
    val expiresAt: Long?, val addedPay: Double? = null)
sealed interface PayBarModel {
    data class Split(val profitCents: Long, val carCents: Long, val profitFraction: Float) : PayBarModel
    data class Loss(val lossCents: Long, val carCents: Long) : PayBarModel
    data object DistanceUnknown : PayBarModel
    data object Unscored : PayBarModel
}
enum class GaugeKind { HOUR, MILE, ITEM }
enum class GaugeTone { GOOD, WARN, NEUTRAL }
data class GaugeModel(
    val kind: GaugeKind, val denominatorText: String, val deltaText: String?, val rateText: String,
    val tone: GaugeTone, val fill: Float, val tickAt: Float?, val ghostAt: Float?, val aboveTarget: Boolean?,
)
data class TripLineModel(val nodes: List<TripNode>)
data class TripNode(val icon: TrackIcon, val added: Boolean = false, val overflow: Int? = null)
data class DeltaTiles(val minutes: Long?, val miles: Double?, val stops: Int?, val items: Int?)
sealed interface OfferBadgeModel {
    data class Rule(val hit: cloud.trotter.log.gig.domain.offer.story.RuleHit) : OfferBadgeModel
    data class Platform(val badge: OfferBadge) : OfferBadgeModel
    data class Order(val badge: OrderBadge) : OfferBadgeModel
    data object Shop : OfferBadgeModel
    data object AddOn : OfferBadgeModel
}
