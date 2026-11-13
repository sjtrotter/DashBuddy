package cloud.trotter.log.gig.domain.census

/**
 * #1622: the dasher's last request to delete the census data the server holds for this phone
 * (`DELETE /v1/installs/me/data`). Stored on the phone so a request made offline survives process death and is sent
 * before anything else; never uploaded.
 */
data class CensusDeletion(
    val requestedAtMillis: Long,
    val state: State,
    /** Rows the server erased, from its confirmation ([State.DELETED] only). */
    val rows: Int? = null,
    /** The UTC day the server deleted them, `YYYY-MM-DD`, from its confirmation ([State.DELETED] only). */
    val deletedDay: String? = null,
    /** False until the phone's census queue, envelopes and unsent daily counts are cleared for this request. */
    val localCleared: Boolean = false,
) {
    enum class State(val wire: String) {
        /** Asked, not yet confirmed: every census run sends this first and sends nothing else until it resolves. */
        PENDING("pending"),

        /** The server confirmed the deletion. */
        DELETED("deleted"),

        /** The server holds nothing for this phone: it never enrolled, or the server no longer knows its ID. */
        NOTHING_HELD("nothing_held"),

        /** This phone's census ID can no longer sign a request: the server revoked it, or its key was lost. */
        NOT_POSSIBLE("not_possible");

        companion object {
            /** Fail-closed decode: an unreadable stored state is [PENDING], which blocks uploads until a retry resolves it. */
            fun fromWire(wire: String?): State = entries.firstOrNull { it.wire == wire } ?: PENDING
        }
    }

    val pending: Boolean get() = state == State.PENDING
}
