package cloud.trotter.dashbuddy.domain.capture

/**
 * Provides live [ReplayMetadata] for the current engine/ruleset/pipeline state.
 * Interface in `:domain`, implementation in `:app` (needs Android context + BuildConfig).
 */
interface ReplayMetadataProvider {
    fun current(): ReplayMetadata
}
