package cloud.trotter.log.gig.domain.census

import kotlinx.serialization.Serializable

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
     * The census install IDs to delete, bound at the first attempt: the identities an identity reset retired, then the
     * current enrolled one, each with its own outcome. Each is only ever sent with its own key. Null before the first
     * attempt; empty when the phone held no census ID ([State.NOTHING_SENT]).
     */
    val targets: List<Target>? = null,
    /** Why a [State.PENDING] request has not gone through yet, for the screen; null before the first attempt. */
    val waiting: Waiting? = null,
) {
    enum class State(val wire: String) {
        /** Asked, not yet confirmed: every census run sends this first and sends nothing else until it resolves. */
        PENDING("pending"),

        /** The server confirmed the deletion. */
        DELETED("deleted"),

        /**
         * No request was sent: the phone held no census ID at the time (none enrolled and none retired). This says
         * nothing about the server; the record is cleared once the phone enrols again.
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

    /** One install ID of the request and the server's confirmation for it. */
    @Serializable
    data class Target(
        val installId: String,
        val outcome: Outcome = Outcome.PENDING,
        /** Rows the server erased under this ID, from its confirmation. */
        val rows: Int? = null,
        /** The server's UTC day of that confirmation, `YYYY-MM-DD`. */
        val day: String? = null,
    ) {
        override fun toString(): String = "Target(${installId.take(8)}…, $outcome, rows=$rows, day=$day)"
    }

    @Serializable
    enum class Outcome {
        PENDING,
        /** The server confirmed the deletion under this ID. */
        DELETED,
        /** The phone no longer holds this ID's key (revoked, or the key was lost): nothing can be sent for it. */
        NOT_POSSIBLE,
    }

    /** Rows erased across the confirmed IDs. */
    val rows: Int get() = targets.orEmpty().sumOf { it.rows ?: 0 }

    /** The confirmed IDs' days, oldest first, distinct. */
    val confirmedDays: List<String> get() =
        targets.orEmpty().filter { it.outcome == Outcome.DELETED }.mapNotNull { it.day }.distinct().sorted()

    /** IDs the phone could send nothing for. */
    val notPossibleCount: Int get() = targets.orEmpty().count { it.outcome == Outcome.NOT_POSSIBLE }

    val pending: Boolean get() = state == State.PENDING

    /**
     * Uploads stay blocked until the server answered AND the phone's queue is cleared: a request whose clear failed
     * keeps blocking after the server's confirmation, until a retry clears it.
     */
    val resolved: Boolean get() = !pending && localCleared
}
