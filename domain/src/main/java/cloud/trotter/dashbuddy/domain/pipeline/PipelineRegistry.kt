package cloud.trotter.dashbuddy.domain.pipeline

/**
 * Registry of known pipeline identifiers and their current API versions.
 * Used by [ReplayMetadata] and the [RulesetLoader] compatibility check.
 */
object PipelineRegistry {
    val pipelines: Map<String, Int> = mapOf(
        "accessibility" to 1,
        "accessibility.window" to 1,
        "accessibility.click" to 1,
        "accessibility.long_click" to 1,
        "accessibility.scroll" to 1,
        "accessibility.focus" to 1,
        "notification" to 1,
    )
}
