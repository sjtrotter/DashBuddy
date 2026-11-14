package cloud.trotter.log.gig.domain.census.contributor

import cloud.trotter.log.gig.domain.capability.ConsentReceipt
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * #1625: the server's contributor grant for one census install ID (`POST /v1/contributor/grant`). [grantedDay] and
 * [expiresDay] are the server's UTC days; [expiresDay] is the first day the grant no longer works (30 days on).
 * [obtainedAtMillis] is the phone's clock when the grant arrived, so a later deletion request can be told apart.
 */
@Serializable
data class ContributorGrant(
    val installId: String,
    val grantedDay: String,
    val expiresDay: String,
    val obtainedAtMillis: Long,
) {
    /** Live on [todayUtc] when that day is before [expiresDay]. An unreadable day is not live (fail closed). */
    fun liveOn(todayUtc: LocalDate): Boolean =
        runCatching { todayUtc.isBefore(LocalDate.parse(expiresDay)) }.getOrDefault(false)

    /** The instant the grant stops working: the start of [expiresDay], UTC. Null when the day cannot be read. */
    fun expiresAtMillis(): Long? = expiryMillis(expiresDay)

    companion object {
        /** The start of a server UTC day (`YYYY-MM-DD`) in epoch millis; null when it cannot be read. */
        fun expiryMillis(day: String?): Long? = day?.let {
            runCatching { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
        }
    }
}

/** #1625: the dasher's answer to the reviewer invitation. Accept is not an answer here: a grant records it. */
@Serializable
enum class InvitationAnswer {
    NONE,

    /** No repeat for [ContributorInvitation.NOT_NOW_DAYS] days. */
    NOT_NOW,

    /** No invitation again until the dasher accepts from Settings. */
    NO_THANKS,
}

/** #1625: why the last grant ended. Every end stops contributor uploads and drops the reviews waiting on the phone. */
@Serializable
enum class ContributorEnd {
    /** The server's 30 days ran out. The invitation asks again after a later shift; nothing renews by itself. */
    EXPIRED,

    /** The operator revoked it (`403 grant_revoked`, or `revoked` on the state read). */
    REVOKED,

    /** The dasher stopped reviewing. */
    STOPPED,

    /** The census was turned off, or its consent no longer stands. */
    CENSUS_OFF,

    /** The server revoked this phone's census ID itself (`401 revoked`): the census is off and the ID forgotten. */
    INSTALL_REVOKED,

    /** The dasher asked to delete their census data; the server deletes a live grant with it. */
    DATA_DELETED,

    /** This phone's census ID is no longer the one the grant was issued to. */
    ID_CHANGED,

    /** The server's state read answered that this install holds no grant. */
    SERVER_NONE,
}

/** #1625: a grant request the server refused (`403`). */
@Serializable
enum class GrantRefusal {
    /** The server issues grants to an install enrolled at least 8 UTC days ago. */
    INSTALL_TOO_NEW,

    /** The server read the request as not eligible. The phone only ever sends `true`, so this is not expected. */
    NOT_ELIGIBLE,

    /** The operator's revocation stands. */
    GRANT_REVOKED,
}

/**
 * #1625: everything the phone keeps about the contributor tier, in one record so a reader never pairs one field with
 * another's past. It never holds an eligibility count. Stored on the phone only, excluded from backup and transfer.
 */
@Serializable
data class ContributorRecord(
    val answer: InvitationAnswer = InvitationAnswer.NONE,
    /** When the dasher last answered the invitation, or last asked for a grant. */
    val answeredAtMillis: Long? = null,
    /** When the invitation was offered after a shift (the notification); null again once answered. */
    val invitedAtMillis: Long? = null,
    val grant: ContributorGrant? = null,
    /** The dasher's last decision on the reviewer disclosure: Accept is granted, No thanks and Stop are not. */
    val receipt: ConsentReceipt? = null,
    /** Why the last grant ended; null while one is live, and before the first. */
    val ended: ContributorEnd? = null,
    /** The last refused grant request; cleared by a grant. */
    val refusal: GrantRefusal? = null,
    /**
     * Moves on every decision that must outrank a grant request already in flight: an answer, Stop, or any end. A grant
     * request captures it before sending; a response that finds it moved is discarded.
     */
    val generation: Long = 0,
) {
    fun granted(grant: ContributorGrant, nowMillis: Long) = copy(
        grant = grant, answer = InvitationAnswer.NONE, answeredAtMillis = nowMillis, invitedAtMillis = null,
        ended = null, refusal = null,
    )

    fun refused(refusal: GrantRefusal, nowMillis: Long): ContributorRecord {
        val revoked = refusal == GrantRefusal.GRANT_REVOKED && grant != null
        return copy(
            refusal = refusal, answeredAtMillis = nowMillis, invitedAtMillis = null,
            grant = if (revoked) null else grant, ended = if (revoked) ContributorEnd.REVOKED else ended,
        )
    }

    /** The grant ends for [reason]; only the dasher's Stop is a "No thanks". */
    fun ended(reason: ContributorEnd, nowMillis: Long, receipt: ConsentReceipt? = null) = copy(
        generation = generation + 1,
        grant = null,
        ended = reason,
        invitedAtMillis = null,
        answer = if (reason == ContributorEnd.STOPPED) InvitationAnswer.NO_THANKS else answer,
        answeredAtMillis = if (reason == ContributorEnd.STOPPED) nowMillis else answeredAtMillis,
        refusal = if (reason == ContributorEnd.REVOKED) GrantRefusal.GRANT_REVOKED else refusal,
        receipt = receipt ?: this.receipt,
    )

    fun answered(answer: InvitationAnswer, nowMillis: Long, receipt: ConsentReceipt? = null) = copy(
        generation = generation + 1,
        answer = answer, answeredAtMillis = nowMillis, invitedAtMillis = null, receipt = receipt ?: this.receipt,
    )
}

/** #1625: what the Census screen's "Screen reviews" row shows. */
enum class ContributorEntry {
    /** Nothing to show or change: no row. */
    HIDDEN,

    /** The invitation was offered after a shift and is open. */
    INVITED,

    /** A live grant. */
    ON,

    /** Answered, refused or ended: the row says off and can be opened to accept again. */
    OFF,
}

/**
 * #1625: THE invitation rule. The invitation (a notification, and the Census screen's row) is offered only after a
 * shift ends, never while one runs, and at most once until the dasher answers. Not now holds it back for
 * [NOT_NOW_DAYS] days; No thanks until the dasher accepts from Settings. An expired grant asks again after a later
 * shift: nothing renews by itself.
 */
object ContributorInvitation {
    const val NOT_NOW_DAYS = 14

    /** After `install_too_new`, the server's 8-day rule: the invitation asks again after a shift this much later. */
    const val TOO_NEW_RETRY_DAYS = 2

    private const val DAY_MILLIS = 86_400_000L

    /** Whether the invitation may be offered now: eligible, no shift running, no live grant, no standing answer. */
    fun open(record: ContributorRecord, eligible: Boolean, shiftLive: Boolean, nowMillis: Long, todayUtc: LocalDate): Boolean {
        if (!eligible || shiftLive) return false
        if (record.grant?.liveOn(todayUtc) == true) return false
        val answeredAt = record.answeredAtMillis ?: Long.MIN_VALUE / 2
        return when {
            record.answer == InvitationAnswer.NO_THANKS -> false
            record.answer == InvitationAnswer.NOT_NOW && nowMillis < answeredAt + NOT_NOW_DAYS * DAY_MILLIS -> false
            record.refusal == GrantRefusal.GRANT_REVOKED -> false
            record.refusal == GrantRefusal.INSTALL_TOO_NEW && nowMillis < answeredAt + TOO_NEW_RETRY_DAYS * DAY_MILLIS -> false
            else -> true
        }
    }

    /** Whether the notification may post after a shift: [open], and not already offered since the last answer. */
    fun shouldNotify(record: ContributorRecord, eligible: Boolean, shiftLive: Boolean, nowMillis: Long, todayUtc: LocalDate) =
        record.invitedAtMillis == null && open(record, eligible, shiftLive, nowMillis, todayUtc)

    fun entry(
        record: ContributorRecord,
        censusOn: Boolean,
        eligible: Boolean,
        shiftLive: Boolean,
        nowMillis: Long,
        todayUtc: LocalDate,
    ): ContributorEntry = when {
        record.grant?.liveOn(todayUtc) == true -> ContributorEntry.ON
        !censusOn -> ContributorEntry.HIDDEN
        record.invitedAtMillis != null && open(record, eligible, shiftLive, nowMillis, todayUtc) -> ContributorEntry.INVITED
        record.answer != InvitationAnswer.NONE || record.refusal != null || record.ended != null -> ContributorEntry.OFF
        else -> ContributorEntry.HIDDEN
    }
}
