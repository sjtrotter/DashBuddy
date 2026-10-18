package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.domain.analytics.SessionNet
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.presentation

object HudStateMapper {
    fun map(
        region: PlatformRegion?, flow: Flow, platform: Platform?, switchable: List<Platform>,
        earnings: Double?, miles: Double, lastSession: SessionRecord?, net: SessionNet?,
        countExpenses: Boolean, goal: Double?, todayProfit: Double?, hasActiveCard: Boolean,
        logOpen: Boolean, gas: GasRowModel,
    ): HudUiState {
        val mode = HudModeResolver.resolve(flow, region?.mode, hasActiveCard, logOpen)
        val firstRun = region?.session == null && lastSession == null
        val atRest = mode == HudMode.RESTING
        val profit = net?.kept(countExpenses)
        val top = when {
            atRest && lastSession != null -> TopRowMapper.resting(lastSession)
            firstRun -> TopRowMapper.firstRun()
            else -> TopRowMapper.live(earnings, region?.session?.startedAt, miles)
        }
        val resting = lastSession?.let { last ->
            val span = last.endedAt?.minus(last.startedAt)
            RestingModel(last.startedAt, last.endedAt,
                moneyOrEmpty(if (span != null && span > 0) profit?.div(span / 3_600_000.0) else null),
                last.deliveries, last.offersAccepted, last.offersReceived)
        }
        // Resting wins in the base mode resolver, but its enabled Log block must still
        // open the chat. Keep the resting identity and four-block dock while the log is open.
        val dock = DockMapper.map(mode, firstRun).let { model ->
            if (!logOpen) model else model.copy(items = model.items.map { item ->
                if (item.kind == DockKind.LOG) item.copy(state = DockItemState.LIT) else item
            })
        }
        return HudUiState(mode, flow.presentation.stripColor,
            if (atRest) lastSession?.platform else platform, switchable, top,
            HeroModel(profit, GoalPillMapper.map(goal, todayProfit)), dock,
            resting, firstRun, gas, logOpen)
    }
}
