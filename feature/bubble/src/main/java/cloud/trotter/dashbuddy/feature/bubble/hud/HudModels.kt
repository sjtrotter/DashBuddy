package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.domain.state.PhaseColor
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.model.vehicle.Vehicle
import cloud.trotter.dashbuddy.feature.bubble.hud.vehicle.VehicleRowModel

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
    val vehicle: VehicleRowModel? = null,
    val dashVehicle: Vehicle? = null,
)
enum class HudMode { LIVE, OFFER, LOG, RESTING }
data class TopRowModel(val earned: String, val online: OnlineSpan, val miles: String, val dimmed: Boolean)
sealed interface OnlineSpan {
    data class Ticking(val startedAt: Long) : OnlineSpan
    data class Fixed(val ms: Long) : OnlineSpan
    data object Unknown : OnlineSpan
}
data class HeroModel(val profit: Double?, val goal: GoalPillState, val celebrateKey: String? = null, val markLoading: Boolean = false)
sealed interface GoalPillState {
    data object NoGoal : GoalPillState
    data class InProgress(val remaining: String, val progress: Float) : GoalPillState
    data object Met : GoalPillState
}
data class DockModel(val items: List<DockItem>)
data class DockItem(val kind: DockKind, val state: DockItemState)
enum class DockKind { CASH_TIP, EXPENSE, ODOMETER, LOG, NOW, SETTINGS }
enum class DockItemState { NORMAL, LIT, ACCENT, DISABLED }
data class RestingModel(
    val startedAt: Long,
    val endedAt: Long?,
    val profitPerHour: String,
    val deliveries: Int,
    val offersAccepted: Int,
    val offersReceived: Int,
)
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
