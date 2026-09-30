package cloud.trotter.dashbuddy.core.pipeline.accessibility.input

import android.view.accessibility.AccessibilityEvent

/**
 * The pure admit decision of [AccessibilityListener.onAccessibilityEvent] (#1148 D2), extracted
 * so it is unit-testable without the service.
 *
 * - An unhandled type is never admitted (the listener's debug branch logs it separately).
 * - `TYPE_WINDOWS_CHANGED` is admitted with ANY package, including null: the system fires the
 *   topology event with no (or a foreign) package, and gating it on the event's package meant the
 *   windows pipeline never got to inspect the actual windows. Package scope for that path is
 *   enforced downstream on the FETCHED roots (`WindowsChangedPipeline` skips any root whose
 *   package is not in `Platform.watchedPackages`) — the #4 self-recognition guard is unchanged.
 * - Every other handled type keeps the event-package gate against the enabled platforms.
 *
 * [isDebug] is part of the signature so the decision is fully explicit, but the rule is the same
 * in both build types (debug only widens WHICH events arrive, not which are admitted).
 */
internal object ListenerGate {
    fun admit(
        type: Int,
        pkg: String?,
        enabledPackages: Set<String>,
        @Suppress("UNUSED_PARAMETER") isDebug: Boolean,
        handledTypes: Set<Int>,
    ): Boolean {
        if (type !in handledTypes) return false
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) return true
        return pkg in enabledPackages
    }
}
