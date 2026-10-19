package cloud.trotter.dashbuddy.feature.bubble.hud.expense

import cloud.trotter.dashbuddy.domain.analytics.SessionRecord as Session
import java.time.Instant
import java.time.ZoneId

/** The durable last-session projection has endedAt; the live state Session does not. */
fun owner(active: String?, last: Session?, now: Long, zone: ZoneId): String? {
    if (active != null) return active
    val ended = last?.endedAt ?: return null
    return last.sessionId.takeIf {
        Instant.ofEpochMilli(ended).atZone(zone).toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    }
}
