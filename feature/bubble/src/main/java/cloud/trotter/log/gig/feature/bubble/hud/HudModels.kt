package cloud.trotter.log.gig.feature.bubble.hud

import cloud.trotter.log.gig.domain.state.PhaseColor
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.feature.bubble.hud.vehicle.VehicleChipModel

data class HudUiState(
    val mode: HudMode,
    val paused: Boolean,
    val stripColor: PhaseColor,
    val platform: Platform?,
    val switchable: List<Platform>,
    val top: TopRowModel,
    val hero: HeroModel,
    val dock: DockModel,
    val resting: RestingModel?,
    val firstRun: Boolean,
    val gas: GasRowModel,
    val logOpen: Boolean = false,
    val vehicleChip: VehicleChipModel? = null,
)
enum class HudMode { LIVE, OFFER, LOG, RESTING }
data class TopRowModel(val online: OnlineSpan, val miles: String, val dimmed: Boolean)
sealed interface OnlineSpan {
    data class Ticking(val startedAt: Long) : OnlineSpan
    data class Fixed(val ms: Long) : OnlineSpan
    data object Unknown : OnlineSpan
}
/** #1443: [earnings] is the displayed shift's earnings, unlabelled; [cashTips] is its cash, shown apart when > 0. */
data class HeroModel(
    val earnings: Double?,
    val goal: GoalPillState,
    val celebrateKey: String? = null,
    val markLoading: Boolean = false,
    val cashTips: Double? = null,
)
sealed interface GoalPillState {
    data object NoGoal : GoalPillState
    data class InProgress(val remaining: String, val progress: Float) : GoalPillState
    data object Met : GoalPillState
}
data class DockModel(val items: List<DockItem>)
data class DockItem(val kind: DockKind, val state: DockItemState)
enum class DockKind { CASH_TIP, EXPENSE, ODOMETER, LOG, NOW, SETTINGS }
enum class DockItemState { NORMAL, LIT, ACCENT, DISABLED }
/**
 * The resting card's shift (#1443). Null fields are unknown and render as the em dash: a shift whose row has not
 * been projected yet is pending, never "No shift yet" (that copy is first run only, a null model). [online] is the
 * Earned/hr span: fixed once the shift ended, ticking from the live start during an end grace.
 */
data class RestingModel(
    val startedAt: Long?,
    val endedAt: Long?,
    val earnings: Double?,
    val online: OnlineSpan,
    val deliveries: Int?,
    val offersAccepted: Int?,
    val offersReceived: Int?,
) {
    /** Earned/hr: [earnings] over the online time; a ticking span reads [now]. Null renders as the em dash. */
    fun earnedPerHour(now: Long): Double? = perHour(earnings, when (val span = online) {
        is OnlineSpan.Fixed -> span.ms
        is OnlineSpan.Ticking -> now - span.startedAt
        OnlineSpan.Unknown -> null
    })
}
data class GasRowModel(
    val price: Float?,
    val auto: Boolean,
    val refreshing: Boolean = false,
    val refreshError: Boolean = false,
)
data class RibbonModel(
    val startAt: Long,
    val segments: List<RibbonSegment>,
    val marks: List<OfferMark>,
    val tail: RibbonKind?,
)
data class RibbonSegment(val kind: RibbonKind, val startAt: Long, val endAt: Long?)
data class OfferMark(val at: Long, val accepted: Boolean)
enum class RibbonKind { WAIT, PICKUP, DROPOFF }
data class RibbonRect(val kind: RibbonKind, val left: Float, val right: Float, val live: Boolean)
data class RibbonMark(val x: Float, val accepted: Boolean)
data class RibbonGeometry(val rects: List<RibbonRect>, val marks: List<RibbonMark>, val knobX: Float?)
