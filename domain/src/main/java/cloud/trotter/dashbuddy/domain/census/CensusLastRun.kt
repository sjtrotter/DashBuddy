package cloud.trotter.dashbuddy.domain.census

/** Outcome vocabulary of one uploader run; wire strings are persisted and rendered raw on the developer screen. */
enum class CensusRunOutcome(val wire: String) {
    DISABLED("disabled"),
    DEFERRED("deferred"),            // detail = seconds until the stored deadline, when known
    ENROLLED("enrolled"),
    ENROL_CONFLICT("enrol_conflict"),
    ENROL_REJECTED("enrol_rejected"), // detail = HTTP status
    KEYSTORE_TRANSIENT("keystore_transient"),
    SPOOL_EMPTY("spool_empty"),
    UPLOADED("uploaded"),            // detail = accepted items this run
    DUPLICATE("duplicate"),          // detail = duplicate items this run
    REJECTED("rejected"),            // detail = batch-quality rejected items this run
    OVERSIZED("oversized"),
    BAD_REQUEST("bad_request"),
    UNAUTHORIZED("unauthorized"),
    CLOCK_SKEW("clock_skew"),
    REVOKED("revoked"),
    FAILURE("failure"),
    RESET("reset"),
    UNKNOWN("unknown");

    companion object {
        /** Fail-closed decode: an unrecognized stored token renders as UNKNOWN, never throws. */
        fun fromWire(wire: String?): CensusRunOutcome = entries.firstOrNull { it.wire == wire } ?: UNKNOWN
    }
}

/** The last uploader run (or identity reset), persisted in developer settings so it survives process death. */
data class CensusLastRun(val atMillis: Long, val outcome: CensusRunOutcome, val detail: Int? = null) {
    /** Developer-screen token, e.g. `uploaded 12`, `enrol_rejected 401`, `unauthorized`. */
    fun token(): String = if (detail == null) outcome.wire else "${outcome.wire} $detail"
}
