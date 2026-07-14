package cloud.trotter.dashbuddy.domain.evaluation

import kotlinx.serialization.Serializable

/**
 * Quality tier of an evaluated offer (#366). A typed verdict, not UI copy —
 * display labels live in the UI layer (which #57 can localize).
 */
@Serializable
enum class OfferQuality {
    AWESOME,
    GREAT,
    GOOD,
    DECENT,
    BAD,

    /** Protect-stats mode forced an accept; scoring was bypassed. */
    PROTECTED,

    /** A merchant BLOCK rule hard-declined the offer before scoring. */
    BLOCKED,

    /**
     * The dasher turned off shopping orders ([EvaluationConfig.allowShopping] = false)
     * and this offer contains a shop-for-items leg, so it was hard-declined before
     * scoring (#762 D12).
     */
    SHOP_DECLINED,

    /** No verdict — e.g. no scoring rules are enabled. */
    UNKNOWN,
}
