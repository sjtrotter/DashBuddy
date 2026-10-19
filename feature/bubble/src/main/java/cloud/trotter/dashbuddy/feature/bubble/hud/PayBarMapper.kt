package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.domain.format.Formats

object PayBarMapper {
    fun map(pay: Double?, net: Double?, hasDistance: Boolean, evaluated: Boolean): PayBarModel {
        if (!evaluated || pay == null) return PayBarModel.Unscored
        if (!hasDistance) return PayBarModel.DistanceUnknown
        if (net == null) return PayBarModel.Unscored
        val gross = Formats.cents(pay)
        val profit = Formats.cents(net)
        val car = gross - profit
        return if (profit < 0) PayBarModel.Loss(-profit, car)
        else PayBarModel.Split(profit, car,
            if (gross > 0) (profit.toFloat() / gross).coerceIn(0f, 1f) else 0f)
    }
}
