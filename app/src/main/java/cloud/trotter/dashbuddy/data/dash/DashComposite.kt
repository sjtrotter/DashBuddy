package cloud.trotter.dashbuddy.data.dash

import androidx.room.Embedded
import androidx.room.Relation
import cloud.trotter.dashbuddy.data.links.dashZone.DashZoneEntity
import cloud.trotter.dashbuddy.data.offer.OfferEntity
import cloud.trotter.dashbuddy.data.order.OrderEntity
import cloud.trotter.dashbuddy.data.pay.AppPayEntity
import cloud.trotter.dashbuddy.data.pay.TipEntity

// Level 3: Order with its Tips
data class OrderWithTips(
    @Embedded val order: OrderEntity,

    @Relation(
        parentColumn = "id",
        entityColumn = "orderId"
    )
    val tips: List<TipEntity>
)

// Level 2: Offer with Orders and AppPay
data class OfferComposite(
    @Embedded val offer: OfferEntity,

    @Relation(
        entity = OrderEntity::class,
        parentColumn = "id",
        entityColumn = "offerId"
    )
    val orders: List<OrderWithTips>,

    @Relation(
        parentColumn = "id",
        entityColumn = "offerId"
    )
    val appPay: List<AppPayEntity>
)

// Level 1: The Root Dash Object
data class DashComposite(
    @Embedded val dash: DashEntity,

    @Relation(
        entity = OfferEntity::class,
        parentColumn = "id",
        entityColumn = "dashId"
    )
    val offers: List<OfferComposite>,

    @Relation(
        parentColumn = "id",
        entityColumn = "dashId"
    )
    val zoneLinks: List<DashZoneEntity>
)