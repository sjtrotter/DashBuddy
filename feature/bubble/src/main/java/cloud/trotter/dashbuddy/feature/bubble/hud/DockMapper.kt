package cloud.trotter.dashbuddy.feature.bubble.hud

object DockMapper {
    fun map(mode: HudMode, firstRun: Boolean): DockModel {
        if (mode == HudMode.OFFER) return DockModel(emptyList())
        return DockModel(DockKind.entries.filter { it != DockKind.SETTINGS || mode == HudMode.RESTING }
            .map { kind ->
                DockItem(kind, when {
                    // S3 owns these sheets. Keep their entry points visibly disabled until then.
                    kind == DockKind.CASH_TIP || kind == DockKind.EXPENSE -> DockItemState.DISABLED
                    kind == DockKind.LOG && mode == HudMode.LOG -> DockItemState.LIT
                    else -> DockItemState.NORMAL
                })
            })
    }
}
