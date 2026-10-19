package cloud.trotter.dashbuddy.feature.bubble.hud

object DockMapper {
    fun map(mode: HudMode, firstRun: Boolean = false, cashTipCue: Boolean = false, logShown: Boolean = mode == HudMode.LOG): DockModel {
        if (mode == HudMode.OFFER) return DockModel(emptyList())
        return DockModel(DockKind.entries.filter { it != DockKind.NOW && (it != DockKind.SETTINGS || mode == HudMode.RESTING) }
            .map { if (it == DockKind.LOG && logShown) DockKind.NOW else it }
            .map { kind ->
                DockItem(kind, when {
                    kind == DockKind.CASH_TIP && firstRun -> DockItemState.DISABLED
                    kind == DockKind.CASH_TIP && cashTipCue -> DockItemState.ACCENT
                    // S3 owns these sheets; the card cue is visible and clickable before S3.
                    kind == DockKind.CASH_TIP || kind == DockKind.EXPENSE -> DockItemState.DISABLED
                    kind == DockKind.NOW -> DockItemState.LIT
                    else -> DockItemState.NORMAL
                })
            })
    }
}
