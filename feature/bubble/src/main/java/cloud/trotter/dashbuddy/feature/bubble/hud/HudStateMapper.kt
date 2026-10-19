package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.domain.analytics.SessionNet
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.presentation

import java.time.LocalDate

object HudStateMapper {
    fun map(
        region: PlatformRegion?, flow: Flow, platform: Platform?, switchable: List<Platform>,
        earnings: Double?, miles: Double, lastSession: SessionRecord?, net: SessionNet?,
        countExpenses: Boolean, goal: Double?, todayProfit: Double?, hasActiveCard: Boolean,
        hasOfferCard: Boolean, logOpen: Boolean, gas: GasRowModel, cashTipCue: Boolean = false,
        goalDay: LocalDate? = null, mark: MarkRead = MarkRead.Loading,
        odometerLoaded: Boolean, odometerCue: Boolean = false,
    ): HudUiState {
        val mode = HudModeResolver.resolve(flow, region?.mode, region?.session != null, hasActiveCard, hasOfferCard, logOpen)
        val firstRun = region?.session == null && lastSession == null
        val atRest = mode == HudMode.RESTING
        val paused = !atRest && region?.mode == Mode.Paused
        val profit = net?.kept(countExpenses)
        val top = when {
            atRest && lastSession != null -> TopRowMapper.resting(lastSession)
            firstRun -> TopRowMapper.firstRun()
            else -> TopRowMapper.live(earnings, region?.session?.startedAt, miles)
        }
        val resting = lastSession?.let { last ->
            val span = last.endedAt?.minus(last.startedAt)
            RestingModel(last.startedAt, last.endedAt,
                moneyOrEmpty(profitPerHour(profit, span)),
                last.deliveries, last.offersAccepted, last.offersReceived)
        }
        val dock = DockMapper.map(mode, firstRun, cashTipCue, logShown = logOpen,
            odometerLoaded = odometerLoaded, odometerCue = odometerCue)
        val goalState = GoalPillMapper.map(goal, todayProfit)
        val celebration = GoalCelebrationResolver.resolve(goalState, mode, goalDay, goal, mark, logOpen)
        return HudUiState(mode, paused, flow.presentation.stripColor,
            if (atRest) lastSession?.platform else platform, switchable, top,
            HeroModel(profit, goalState, celebration, markLoading = mark == MarkRead.Loading), dock,
            resting, firstRun, gas, logOpen)
    }
}
