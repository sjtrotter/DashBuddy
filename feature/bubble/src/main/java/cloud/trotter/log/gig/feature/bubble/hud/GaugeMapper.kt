package cloud.trotter.log.gig.feature.bubble.hud

import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.domain.offer.story.*
import kotlin.math.roundToInt

object GaugeMapper {
    fun map(rate: Fact, per: Fact?): GaugeModel {
        val kind = when (rate.kind) {
            FactKind.PER_HOUR -> GaugeKind.HOUR
            FactKind.PER_MILE -> GaugeKind.MILE
            FactKind.PER_ITEM -> GaugeKind.ITEM
            else -> error("Not a rate: ${rate.kind}")
        }
        val denominator = per?.value
        val delta = per?.delta
        val target = rate.target
        val previous = rate.previous
        fun number(n: Double) = if (kind == GaugeKind.MILE) Formats.decimal(n) else n.roundToInt().toString()
        val known = rate.value?.takeIf { it.isFinite() }
        val above = rate.versus?.let { it == Versus.ABOVE }
        val scale = maxOf(known ?: 0.0, previous ?: 0.0, target ?: 0.0) * 1.2
        fun fraction(n: Double) = if (scale > 0) (n / scale).toFloat().coerceIn(0f, 1f) else 0f
        return GaugeModel(kind, denominator?.let(::number) ?: EMPTY_VALUE,
            delta?.let { (if (it >= 0) "+" else "") + number(it) },
            known?.let { when (kind) {
                GaugeKind.HOUR -> Formats.money0(it)
                GaugeKind.MILE -> Formats.money(it)
                GaugeKind.ITEM -> Formats.moneyOrCents(it)
            } } ?: EMPTY_VALUE,
            when {
                known == null -> GaugeTone.NEUTRAL
                known < 0 -> GaugeTone.WARN
                above == true -> GaugeTone.GOOD
                above == false -> GaugeTone.WARN
                else -> GaugeTone.NEUTRAL
            }, known?.let(::fraction) ?: 0f,
            target?.takeIf { known != null }?.let(::fraction),
            previous?.takeIf { known != null }?.let(::fraction), above)
    }
}
