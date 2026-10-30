package cloud.trotter.log.gig.feature.bubble.hud

import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.domain.format.Formats
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object GaugeMapper {
    fun map(kind: GaugeKind, denominator: Double?, rate: Double?, target: Double?,
        previous: Double? = null, delta: Double? = null): GaugeModel {
        fun number(n: Double) = if (kind == GaugeKind.MILE) Formats.decimal(n) else n.roundToInt().toString()
        fun precision(n: Double) = if (kind == GaugeKind.HOUR)
            String.format(Locale.ROOT, "%.0f", abs(n)).toDouble() * if (n < 0) -1 else 1
            else Formats.cents(n).toDouble()
        val known = rate?.takeIf { it.isFinite() }
        val above = if (known != null && target != null) precision(known) >= precision(target) else null
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
