package cloud.trotter.dashbuddy.domain.capture

/**
 * Provides live [ReplayMetadata] for the current engine/ruleset/pipeline state.
 * Interface in `:domain`; the implementation lives in `:core:pipeline` with the capture flow.
 */
interface ReplayMetadataProvider {
    fun current(): ReplayMetadata
}
