package cloud.trotter.dashbuddy.feature.bubble.hud

import kotlin.math.ceil

object StateHeadline {
    fun parked(arrivedAt: Long, deadlineAt: Long?, now: Long): Headline =
        if (deadlineAt != null && now >= deadlineAt) Headline.Minutes(minutes(now - arrivedAt))
        else Headline.Clock((now - arrivedAt).coerceAtLeast(0))

    fun moving(anchorAt: Long, deadlineAt: Long?, now: Long): Headline = when {
        deadlineAt == null -> Headline.Unknown
        now >= deadlineAt -> Headline.Minutes(minutes(now - anchorAt))
        else -> Headline.Minutes(ceil((deadlineAt.toDouble() - now) / 60_000).toInt())
    }

    fun lateMinutes(deadlineAt: Long?, now: Long): Int? =
        deadlineAt?.takeIf { now >= it }?.let { minutes(now - it).coerceAtLeast(1) }

    fun waiting(since: Long?, now: Long): Headline =
        since?.let { Headline.Clock((now - it).coerceAtLeast(0)) } ?: Headline.Unknown

    private fun minutes(ms: Long): Int = (ms.coerceAtLeast(0) / 60_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
