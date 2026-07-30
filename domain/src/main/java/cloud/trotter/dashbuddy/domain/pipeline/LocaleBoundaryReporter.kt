package cloud.trotter.dashbuddy.domain.pipeline

/**
 * Reports the [RecognitionLocale] boundary when a sensor service goes live (#938).
 *
 * The contract lives in `:domain` so `:core:pipeline` can call it while keeping its
 * `:domain`-only dependency (the `PlatformPreferences` read-interface pattern, #355); the
 * implementation lives in `:app`, which owns the DataStore-backed once-per-install flag and
 * the dasher-visible notification.
 */
fun interface LocaleBoundaryReporter {

    /**
     * Called from the sensor service's connected callback. **Fail-open:** the implementation
     * must neither throw nor block — a locale diagnostic must never be able to stop sensing
     * from starting.
     */
    fun onSensorServiceConnected()
}
