package cloud.trotter.dashbuddy.core.pipeline.rules

/**
 * Narrow read seam (#598) the capture stage uses to fetch a recognized screen
 * rule's compiled `redact` directives, without depending on the whole rule
 * interpreter. Implemented by [JsonRuleInterpreter] (Hilt-bound); tests pass a
 * stub.
 */
interface ScreenRedactionSource {
    /**
     * The compiled `redact` block for the screen rule [ruleId], or null when the
     * rule declares none (nothing to mask). UNKNOWN captures have no ruleId and
     * never reach this — their disk write is governed by the SensitiveTextMarkers
     * backstop instead.
     */
    fun redactFor(ruleId: String): CompiledRedact?
}
