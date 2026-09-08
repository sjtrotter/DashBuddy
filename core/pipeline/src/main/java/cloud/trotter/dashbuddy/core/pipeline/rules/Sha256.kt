package cloud.trotter.dashbuddy.core.pipeline.rules

/**
 * The rule engine's sha256 entry point (#362) — since #1093 a delegate to the `:domain` owner
 * (`cloud.trotter.dashbuddy.domain.util.sha256OrNull`), kept so the nine call sites in this
 * package read unchanged. FAIL CLOSED: null on digest failure, never the input.
 */
internal fun sha256OrNull(input: String): String? = cloud.trotter.dashbuddy.domain.util.sha256OrNull(input)
