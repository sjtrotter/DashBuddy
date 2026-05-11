package cloud.trotter.dashbuddy.core.state

/**
 * Provides event metadata JSON for durable log entries.
 *
 * Abstraction over [DashBuddyApplication.createMetadata()] so that
 * [EffectMap] can live in `:core:state` without depending on the
 * application class. `:app` provides the implementation via Hilt.
 */
fun interface MetadataProvider {
    fun createMetadata(): String
}
