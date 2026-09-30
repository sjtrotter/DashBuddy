package cloud.trotter.dashbuddy.core.pipeline.accessibility.input

/**
 * #1151 review MM9 — apply a value now, or park it and retry later. [apply] returns false when it
 * could not take effect (the service's `serviceInfo` was null on a transient binder failure); the
 * value is then held and [retryPending] re-applies it (the listener calls it on every event, where it
 * is a no-op while nothing is pending). [onDeferred] fires once per newly parked value, never per
 * retry, so a stuck binder cannot flood the log. A newer value replaces a parked one.
 */
internal class PendingApply<T : Any>(
    private val apply: (T) -> Boolean,
    private val onDeferred: () -> Unit,
) {
    @Volatile
    private var pending: T? = null

    val hasPending: Boolean get() = pending != null

    fun onConsent(value: T) {
        if (apply(value)) {
            pending = null
        } else {
            pending = value
            onDeferred()
        }
    }

    fun retryPending() {
        val value = pending ?: return
        if (apply(value)) pending = null
    }
}
