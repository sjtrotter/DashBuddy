package cloud.trotter.log.gig.core.pipeline

import cloud.trotter.log.gig.domain.pipeline.ParseRotCause
import cloud.trotter.log.gig.domain.pipeline.RecognitionIncident
import java.util.TreeMap

/**
 * The pure incident ledger of #1560: one in-memory [RecognitionIncident] per platform, the union of its
 * open causes, so one platform update posts one notice, not one per detector.
 *
 * An incident opens on its first cause (a [RecognitionHealth] UNKNOWN trip or a [ParseRotTracker] rot
 * trip) and closes when its last cause clears. The UNKNOWN cause never clears: it leaves with the process
 * (see [RecognitionHealthMonitor] for why). A cause edge on an open incident adds or replaces that cause
 * and never moves `openedAt` or `notifiedAt`. The caller applies a frame's trip edges before its clear
 * edges, then reads [closedSinceLastTake], so a frame that trips one rule and clears another keeps the
 * incident.
 *
 * A notice is due when the incident has not posted yet and the platform's last notice is at least
 * [noticeIntervalMs] old, or in the future (a clock that moved back fails open).
 *
 * No Android, no logging, no time source: every time is a frame's `obs.timestamp`. Not thread-safe on its
 * own; [RecognitionHealthMonitor] owns the lock.
 */
internal class RecognitionIncidentBook(private val noticeIntervalMs: Long = NOTICE_INTERVAL_MS) {

    /** Sorted by platform wire. */
    private val incidents = TreeMap<String, RecognitionIncident>()
    private val lastNotice = HashMap<String, Long>()
    private val closed = mutableListOf<String>()

    /** The #937 cause tripped at frame time [at]; returns whether the book changed. */
    fun unknownTripped(platformWire: String, at: Long): Boolean {
        val current = incidents[platformWire]
        if (current == null) {
            incidents[platformWire] = RecognitionIncident(platformWire, openedAt = at, screensUnrecognized = true)
            return true
        }
        if (current.screensUnrecognized) return false
        incidents[platformWire] = current.copy(screensUnrecognized = true)
        return true
    }

    /** A rule's rot tripped; returns whether the book changed. */
    fun rotTripped(platformWire: String, cause: ParseRotCause): Boolean {
        val current = incidents[platformWire]
        if (current == null) {
            incidents[platformWire] = RecognitionIncident(platformWire, openedAt = cause.trippedAt, parseRot = listOf(cause))
            return true
        }
        val causes = (current.parseRot.filterNot { it.ruleId == cause.ruleId } + cause).sortedBy { it.ruleId }
        if (causes == current.parseRot) return false
        incidents[platformWire] = current.copy(parseRot = causes)
        return true
    }

    /** A rotted rule read healthy again; returns whether the book changed. Closing an incident records its wire. */
    fun rotCleared(platformWire: String, ruleId: String): Boolean {
        val current = incidents[platformWire] ?: return false
        if (current.parseRot.none { it.ruleId == ruleId }) return false
        val causes = current.parseRot.filterNot { it.ruleId == ruleId }
        if (causes.isEmpty() && !current.screensUnrecognized) {
            incidents.remove(platformWire)
            closed += platformWire
        } else {
            incidents[platformWire] = current.copy(parseRot = causes)
        }
        return true
    }

    /** Due notice for [platformWire] at frame time [now], marking it posted; null if none is due. */
    fun takeDueNotice(platformWire: String, now: Long): RecognitionIncident? {
        val current = incidents[platformWire] ?: return null
        if (current.notifiedAt != null) return null
        val last = lastNotice[platformWire]
        val due = last == null || now - last >= noticeIntervalMs || last > now
        if (!due) return null
        val notified = current.copy(notifiedAt = now)
        incidents[platformWire] = notified
        lastNotice[platformWire] = now
        return notified
    }

    /** The open incidents, sorted by platform wire. */
    fun open(): List<RecognitionIncident> = incidents.values.toList()

    fun lastNoticeAt(): Map<String, Long> = lastNotice.toMap()

    /** Seeds the persisted last-notice times (per-wire max with what memory holds). */
    fun seedLastNoticeAt(persisted: Map<String, Long>) {
        for ((wire, at) in persisted) {
            lastNotice[wire] = maxOf(lastNotice[wire] ?: at, at)
        }
    }

    fun hasIncident(platformWire: String): Boolean = platformWire in incidents

    /** Wires whose incident closed since the last call; cleared on read. */
    fun closedSinceLastTake(): List<String> {
        val taken = closed.toList()
        closed.clear()
        return taken
    }

    companion object {
        /** At most one notice per platform per day. */
        const val NOTICE_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}
