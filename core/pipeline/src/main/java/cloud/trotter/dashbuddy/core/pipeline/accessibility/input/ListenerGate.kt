package cloud.trotter.dashbuddy.core.pipeline.accessibility.input

import android.view.accessibility.AccessibilityEvent

/**
 * The pure admit decision of [AccessibilityListener.onAccessibilityEvent] (#1148 D2), extracted
 * so it is unit-testable without the service.
 *
 * - An unhandled type is never admitted (the listener's debug branch logs it separately).
 * - `TYPE_WINDOWS_CHANGED` is admitted with ANY package, including null — but only while at least
 *   one platform is enabled (#1148 review G6; with none, there is nothing to look for): the system fires the
 *   topology event with no (or a foreign) package, and gating it on the event's package meant the
 *   windows pipeline never got to inspect the actual windows. Package scope for that path is
 *   enforced downstream on the FETCHED roots (`WindowsChangedPipeline` skips any root whose
 *   package is not in `Platform.watchedPackages`) — the #4 self-recognition guard is unchanged.
 * - Every other handled type keeps the event-package gate against the enabled platforms.
 */
internal object ListenerGate {
    fun admit(
        type: Int,
        pkg: String?,
        enabledPackages: Set<String>,
        handledTypes: Set<Int>,
    ): Boolean {
        if (type !in handledTypes) return false
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) return enabledPackages.isNotEmpty()
        return pkg in enabledPackages
    }
}
