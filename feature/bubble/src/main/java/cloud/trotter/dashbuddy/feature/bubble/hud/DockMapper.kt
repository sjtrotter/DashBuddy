package cloud.trotter.dashbuddy.feature.bubble.hud

object DockMapper {
    fun map(mode: HudMode, firstRun: Boolean = false, cashTipCue: Boolean = false, logShown: Boolean = mode == HudMode.LOG,
        odometerLoaded: Boolean, odometerCue: Boolean = false, expenseCue: Boolean = false): DockModel {
        if (mode == HudMode.OFFER) return DockModel(emptyList())
        val kinds = when {
            mode != HudMode.RESTING -> listOf(DockKind.CASH_TIP, DockKind.EXPENSE, DockKind.ODOMETER, DockKind.LOG)
            firstRun -> listOf(DockKind.ODOMETER, DockKind.LOG, DockKind.SETTINGS)
            else -> listOf(DockKind.CASH_TIP, DockKind.ODOMETER, DockKind.LOG, DockKind.SETTINGS)
        }
        return DockModel(kinds
            .map { if (it == DockKind.LOG && logShown) DockKind.NOW else it }
            .map { kind ->
                DockItem(kind, when {
                    kind == DockKind.ODOMETER && !odometerLoaded -> DockItemState.DISABLED
                    kind == DockKind.ODOMETER && odometerCue -> DockItemState.ACCENT
                    kind == DockKind.CASH_TIP && firstRun -> DockItemState.DISABLED
                    kind == DockKind.CASH_TIP && cashTipCue -> DockItemState.ACCENT
                    kind == DockKind.EXPENSE && expenseCue -> DockItemState.ACCENT
                    kind == DockKind.NOW -> DockItemState.LIT
                    else -> DockItemState.NORMAL
                })
            })
    }
}
