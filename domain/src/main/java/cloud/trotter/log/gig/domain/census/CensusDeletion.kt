package cloud.trotter.log.gig.domain.census

/**
 * #1622: the dasher's last request to delete the census data the server holds for this phone
 * (`DELETE /v1/installs/me/data`). Stored on the phone so a request made offline survives process death and is sent
 * before anything else; never uploaded.
 */
data class CensusDeletion(
    val requestedAtMillis: Long,
    val state: State,
    /** False until the phone's census queue, envelopes and unsent daily counts are cleared for this request. */
    val localCleared: Boolean = false,
    /**
     * The enrolled census install ID the request is for, bound at its first attempt. It is only ever sent with that
     * ID's key; if the phone no longer holds it, the request cannot be sent ([State.NOT_POSSIBLE]). Null before the
     * first attempt, and for [State.NOTHING_SENT].
     */
    val installId: String? = null,
    /** Rows the server erased, from its confirmation ([State.DELETED] only). */
    val rows: Int? = null,
    /** The server's UTC day of that confirmation, `YYYY-MM-DD` ([State.DELETED] only). */
    val deletedDay: String? = null,
    /** Why a [State.PENDING] request has not gone through yet, for the screen; null before the first attempt. */
    val waiting: Waiting? = null,
) {
    enum class State(val wire: String) {
        /** Asked, not yet confirmed: every census run sends this first and sends nothing else until it resolves. */
        PENDING("pending"),

        /** The server confirmed the deletion. */
        DELETED("deleted"),

        /**
         * No request was sent: the phone had no enrolled census ID at the time. This says nothing about the server;
         * the record is cleared once the phone enrols again.
         */
        NOTHING_SENT("nothing_sent"),

        /** This phone's census ID can no longer sign a request: the server revoked it, or its key was lost. */
        NOT_POSSIBLE("not_possible");

        companion object {
            /** Fail-closed decode: an unreadable stored state is [PENDING], which blocks uploads until a retry resolves it. */
            fun fromWire(wire: String?): State = entries.firstOrNull { it.wire == wire } ?: PENDING
        }
    }

    /** Why a pending request is still waiting; each says what gig.log keeps doing, never that it failed for good. */
    enum class Waiting(val wire: String) {
        /** No answer: offline, the server unreachable or busy. */
        UNREACHABLE("unreachable"),

        /** The census server does not take deletion requests yet (it answers 404 until its update). */
        NOT_YET("not_yet"),

        /** The server did not accept this phone's ID or signature; gig.log asks again, keeping the ID. */
        UNCONFIRMED("unconfirmed");

        companion object {
            fun fromWire(wire: String?): Waiting? = entries.firstOrNull { it.wire == wire }
        }
    }

    val pending: Boolean get() = state == State.PENDING

    /**
     * Uploads stay blocked until the server answered AND the phone's queue is cleared: a request whose clear failed
     * keeps blocking after the server's confirmation, until a retry clears it.
     */
    val resolved: Boolean get() = !pending && localCleared
}
