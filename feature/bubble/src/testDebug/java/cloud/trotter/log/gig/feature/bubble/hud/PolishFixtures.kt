package cloud.trotter.log.gig.feature.bubble.hud

import cloud.trotter.log.gig.domain.state.PhaseColor

internal fun polishState(mode: HudMode = HudMode.LIVE, hero: HeroModel = HeroModel(71.2, GoalPillState.NoGoal)) = HudUiState(
    mode = mode, paused = false, stripColor = PhaseColor.GOOD,
    platform = null, switchable = emptyList(),
    top = TopRowModel(OnlineSpan.Fixed(9_660_000), "42.6", false),
    hero = hero, dock = DockMapper.map(mode, false, false, odometerLoaded = true), resting = null,
    firstRun = false, gas = GasRowModel(null, true),
)
