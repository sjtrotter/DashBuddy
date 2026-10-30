package cloud.trotter.log.gig.feature.bubble.hud

import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.domain.analytics.SessionRecord
import cloud.trotter.log.gig.domain.format.Formats

object TopRowMapper {
    fun live(earnings: Double?, startedAt: Long?, miles: Double) = TopRowModel(
        moneyOrEmpty(earnings), startedAt?.let { OnlineSpan.Ticking(it) } ?: OnlineSpan.Unknown,
        Formats.decimal(miles, 1), false,
    )
    fun resting(last: SessionRecord) = TopRowModel(
        moneyOrEmpty(last.reportedEarnings),
        last.endedAt?.let { OnlineSpan.Fixed(it - last.startedAt) } ?: OnlineSpan.Unknown,
        last.miles?.let { Formats.decimal(it, 1) } ?: EMPTY_VALUE, true,
    )
    fun firstRun() = TopRowModel(EMPTY_VALUE, OnlineSpan.Unknown, EMPTY_VALUE, true)
}
