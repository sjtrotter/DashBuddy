package cloud.trotter.log.gig.domain.census

/** Outcome vocabulary of one uploader run; wire strings are persisted and rendered raw on the developer screen. */
enum class CensusRunOutcome(val wire: String) {
    DISABLED("disabled"),
    DEFERRED("deferred"),            // detail = seconds until the stored deadline, when known
    ENROLLED("enrolled"),
    ENROL_CONFLICT("enrol_conflict"),
    ENROL_REJECTED("enrol_rejected"), // detail = HTTP status (none when no schema overlaps); reason = server reason
    KEYSTORE_TRANSIENT("keystore_transient"),
    HEALTH_POSTED("health_posted"), // detail = reports accepted this run
    HEALTH_REJECTED("health_rejected"), // detail = reports rejected this run
    SPOOL_EMPTY("spool_empty"),
    STALE_REMOVED("stale_removed"),  // detail = stale items removed locally when nothing else happened this run
    UPLOADED("uploaded"),            // detail = accepted items this run
    ENVELOPES_POSTED("envelopes_posted"), // detail = envelopes accepted this run
    ENVELOPES_REJECTED("envelopes_rejected"), // detail = envelopes rejected this run
    NOT_TRUSTED("not_trusted"),
    DUPLICATE("duplicate"),          // detail = duplicate items this run
    REJECTED("rejected"),            // detail = batch-quality rejected items this run
    OVERSIZED("oversized"),
    BAD_REQUEST("bad_request"),
    UNAUTHORIZED("unauthorized"),
    CLOCK_SKEW("clock_skew"),
    REVOKED("revoked"),
    FAILURE("failure"),
    RESET("reset"),
    /** #1622: the identity reset was refused: the server has not confirmed deleting the enrolled identity's data. */
    RESET_REFUSED("reset_refused"),
    UNKNOWN("unknown");

    companion object {
        /** Fail-closed decode: an unrecognized stored token renders as UNKNOWN, never throws. */
        fun fromWire(wire: String?): CensusRunOutcome = entries.firstOrNull { it.wire == wire } ?: UNKNOWN
    }
}

/**
 * Why the server refused an enrolment, decoded from its `error` field through this closed set (#1522). Any other
 * string becomes [UNKNOWN_REASON]; the raw server text is never stored or logged.
 */
enum class CensusEnrolRejectReason(val wire: String) {
    UNSUPPORTED_SCHEMA("unsupported_schema"),
    BAD_REQUEST("bad_request"),
    UNAUTHORIZED("unauthorized"),
    UNKNOWN_REASON("unknown_reason");

    companion object {
        /** Fail-closed decode of a server reply or a stored token: null stays null, anything unknown is UNKNOWN_REASON. */
        fun fromWire(wire: String?): CensusEnrolRejectReason? =
            wire?.let { entries.firstOrNull { reason -> reason.wire == it } ?: UNKNOWN_REASON }
    }
}

/** The last uploader run (or identity reset), persisted in developer settings so it survives process death. */
data class CensusLastRun(
    val atMillis: Long,
    val outcome: CensusRunOutcome,
    val detail: Int? = null,
    val envelopesPosted: Int = 0,
    val reason: CensusEnrolRejectReason? = null,
) {
    /** Developer-screen token, e.g. `uploaded 12`, `enrol_rejected 422 unsupported_schema`, `unauthorized`. */
    fun token(): String = listOfNotNull(outcome.wire, detail?.toString(), reason?.wire).joinToString(" ")
}
