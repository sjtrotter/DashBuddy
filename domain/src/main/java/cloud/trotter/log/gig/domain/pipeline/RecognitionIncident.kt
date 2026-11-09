package cloud.trotter.log.gig.domain.pipeline

/**
 * One platform's open recognition incident (#1560): the union of its open causes. PII-free by construction:
 * a platform wire, our own rule ids, shape and field names, and frame times.
 */
data class RecognitionIncident(
    val platformWire: String,
    /** Frame time (`obs.timestamp`) of the first cause's trip. The Home card's anchor. */
    val openedAt: Long,
    /** The #937 cause is open: too many recent screens classified UNKNOWN. Process-scoped; never a percentage. */
    val screensUnrecognized: Boolean = false,
    /** Rotted rules, sorted by rule id. */
    val parseRot: List<ParseRotCause> = emptyList(),
    /** Frame time this incident's one notice was due; null until then. */
    val notifiedAt: Long? = null,
)

/** One rule that matches screens but stopped reading their shape-required fields (#1560). */
data class ParseRotCause(
    val ruleId: String,
    /** The matched branch's shape; always a key of `ParsedFieldsFactory.REQUIRED_FIELDS_BY_SHAPE`. */
    val shape: String,
    /** Null shape-required fields, in `REQUIRED_FIELDS_BY_SHAPE` order; never empty. */
    val nullFields: List<String>,
    val trippedAt: Long,
)
