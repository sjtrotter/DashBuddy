package cloud.trotter.log.gig.core.pipeline.accessibility.input

/** The deliberate event/topology differences, after acquisition and an empty overlay scan. */
internal enum class ActivePackage { ENABLED, NOT_ENABLED_OWN, NOT_ENABLED_OTHER, UNREADABLE }
internal enum class FrameAction { MAP_ACTIVE, QUIET, FRONT_ALL, FRONT_ABOVE, NATIVE_STAND_IN, SKIP_UNREADABLE }

internal fun eventAfterNoOverlay(active: ActivePackage): FrameAction = when (active) {
    ActivePackage.ENABLED -> FrameAction.MAP_ACTIVE
    ActivePackage.NOT_ENABLED_OWN -> FrameAction.FRONT_ALL
    ActivePackage.NOT_ENABLED_OTHER -> FrameAction.FRONT_ALL
    ActivePackage.UNREADABLE -> FrameAction.NATIVE_STAND_IN
}

internal fun topologyAfterNoOverlay(active: ActivePackage): FrameAction = when (active) {
    ActivePackage.ENABLED -> FrameAction.QUIET
    ActivePackage.NOT_ENABLED_OWN -> FrameAction.FRONT_ALL
    ActivePackage.NOT_ENABLED_OTHER -> FrameAction.FRONT_ABOVE
    ActivePackage.UNREADABLE -> FrameAction.SKIP_UNREADABLE
}

/** Event policy does not need another package read: both non-enabled rows have the same action. */
internal fun eventActivePackage(active: ActiveWindow<*, *>): ActivePackage = when (active) {
    is ActiveWindow.Enabled -> ActivePackage.ENABLED
    is ActiveWindow.NotEnabled -> ActivePackage.NOT_ENABLED_OTHER
    is ActiveWindow.Unknown -> ActivePackage.UNREADABLE
}
