package cloud.trotter.log.gig.feature.bubble.hud

import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.domain.analytics.SessionRecord
import cloud.trotter.log.gig.domain.format.Formats

/** #1443: Online and Miles; the shift's earnings are the hero's, never repeated here. */
object TopRowMapper {
    fun live(startedAt: Long?, miles: Double) = TopRowModel(
        startedAt?.let { OnlineSpan.Ticking(it) } ?: OnlineSpan.Unknown,
        Formats.decimal(miles, 1), false,
    )
    fun resting(last: SessionRecord) = TopRowModel(
        last.endedAt?.let { OnlineSpan.Fixed(it - last.startedAt) } ?: OnlineSpan.Unknown,
        last.miles?.let { Formats.decimal(it, 1) } ?: EMPTY_VALUE, true,
    )
    fun firstRun() = TopRowModel(OnlineSpan.Unknown, EMPTY_VALUE, true)
}
