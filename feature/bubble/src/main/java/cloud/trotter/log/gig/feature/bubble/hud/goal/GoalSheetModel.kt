package cloud.trotter.log.gig.feature.bubble.hud.goal

data class GoalSheetModel(
    val goalDollars: Int?,
    val todayProfit: Double?,
    val busy: Boolean = false,
    val failed: Boolean = false,
)
