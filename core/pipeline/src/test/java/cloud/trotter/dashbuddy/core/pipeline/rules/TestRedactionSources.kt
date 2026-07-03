package cloud.trotter.dashbuddy.core.pipeline.rules

/**
 * A [ScreenRedactionSource] that never redacts — the default for capture tests
 * that don't exercise #598 redaction. Keeps the CaptureWriter direct-construction
 * sites terse.
 */
object NoRedaction : ScreenRedactionSource {
    override fun redactFor(ruleId: String): CompiledRedact? = null
}
