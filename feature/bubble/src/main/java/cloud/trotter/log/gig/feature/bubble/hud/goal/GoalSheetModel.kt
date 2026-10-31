package cloud.trotter.log.gig.feature.bubble.hud.goal

data class GoalSheetModel(
    val goalDollars: Int?,
    /** #1443: today's app earnings across every app, no cash or costs. */
    val todayEarned: Double?,
    val busy: Boolean = false,
    val failed: Boolean = false,
)
