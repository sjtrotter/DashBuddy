package cloud.trotter.log.gig.feature.bubble.hud

import cloud.trotter.log.gig.domain.analytics.SessionRecord
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.presentation
import cloud.trotter.log.gig.feature.bubble.hud.vehicle.VehicleChipModel

import java.time.LocalDate

object HudStateMapper {
    /**
     * #1443: every hero and resting input reads the one [shift]; [lastSession] (cross-platform) decides first
     * run only. [todayEarned] is the goal's app earnings across every app.
     */
    fun map(
        region: PlatformRegion?, flow: Flow, platform: Platform?, switchable: List<Platform>,
        shift: DisplayedShift?, miles: Double, lastSession: SessionRecord?,
        goal: Double?, todayEarned: Double?, hasActiveCard: Boolean,
        hasOfferCard: Boolean, logOpen: Boolean, gas: GasRowModel, cashTipCue: Boolean = false,
        goalDay: LocalDate? = null, mark: MarkRead = MarkRead.Loading,
        odometerLoaded: Boolean, odometerCue: Boolean = false, expenseCue: Boolean = false,
        goalSheetOpen: Boolean = false, editedGoalKey: String? = null,
        vehicleChip: VehicleChipModel? = null,
        odometerShown: Boolean = true,
    ): HudUiState {
        val mode = HudModeResolver.resolve(flow, region?.mode, region?.session != null, hasActiveCard, hasOfferCard, logOpen)
        val firstRun = region?.session == null && lastSession == null
        val atRest = mode == HudMode.RESTING
        val paused = !atRest && region?.mode == Mode.Paused
        val record = shift?.record
        val top = when {
            firstRun -> TopRowMapper.firstRun()
            // An end grace rests the HUD while the session is still live: its span keeps ticking.
            atRest && region?.session == null -> record?.let(TopRowMapper::resting) ?: TopRowMapper.firstRun()
            else -> TopRowMapper.live(region?.session?.startedAt, miles)
        }
        val resting = record?.let { row ->
            RestingModel(row.startedAt, row.endedAt,
                moneyOrEmpty(perHour(shift?.earnings, row.endedAt?.minus(row.startedAt))),
                row.deliveries, row.offersAccepted, row.offersReceived)
        }
        val dock = DockMapper.map(mode, firstRun, cashTipCue, logShown = logOpen,
            odometerLoaded = odometerLoaded, odometerCue = odometerCue, expenseCue = expenseCue, odometerShown = odometerShown)
        val goalState = GoalPillMapper.map(goal, todayEarned)
        val celebration = GoalCelebrationResolver.resolve(goalState, mode, goalDay, goal, mark, logOpen, goalSheetOpen, editedGoalKey)
        val hero = HeroModel(shift?.earnings, goalState, celebration, markLoading = mark == MarkRead.Loading,
            cashTips = shift?.cashTips?.takeIf { it > 0.0 })
        return HudUiState(mode, paused, flow.presentation.stripColor,
            if (!atRest) platform else if (firstRun) null else shift?.platform, switchable, top,
            hero, dock, resting, firstRun, gas, logOpen, vehicleChip)
    }
}
