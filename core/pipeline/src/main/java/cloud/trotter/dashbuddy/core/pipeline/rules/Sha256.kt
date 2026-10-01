package cloud.trotter.dashbuddy.core.pipeline.rules

/**
 * The rule engine's sha256 entry point (#362) — a delegate to the `census-contract` owner (#1173)
 * (`cloud.trotter.census.contract.sha256OrNull`), kept so the nine call sites in this
 * package read unchanged. FAIL CLOSED: null on digest failure, never the input.
 */
internal fun sha256OrNull(input: String): String? = cloud.trotter.census.contract.sha256OrNull(input)
