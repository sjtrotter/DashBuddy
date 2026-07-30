package cloud.trotter.dashbuddy.core.designsystem.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import cloud.trotter.dashbuddy.core.designsystem.theme.AppColors

/**
 * The **rate heat scale** — how an "your own $/hr for this hour" cell is tinted.
 *
 * One owner for three visually distinct states (#977 pulled it here from the Patterns tab, which had
 * been its only consumer until the Home plan strip needed the identical scale; a feature module can
 * never reach an `:app` private, and a colour ramp with two copies is one that can only ever drift
 * apart — Principle 5):
 *
 *  - **insufficient** (`rate == null`, below the heatmap's coverage floor) → a dim `surface3`, which
 *    reads as "no data";
 *  - **worked, kept nothing** (`rate <= 0.0` with real coverage) → the cold `badBg` (call sites pair
 *    it with a `bad` border) — deliberately NOT the same swatch as insufficient, so a masked cell can
 *    never be misread as a measured zero;
 *  - **positive** → [ramp], scaled to the driver's own best cell.
 *
 * Plain functions taking [AppColors] rather than composables: the callers are already inside a
 * `AppTheme.colors` scope, and this way the mapping is a pure value transform.
 */
object AppHeatScale {

    /**
     * The tint for one cell: [rate] is the cell's masked-or-real rate and [maxRate] the top of the
     * scale (the driver's own best cell). A non-positive [maxRate] falls back to the top of the ramp
     * for any positive rate — with no scale to speak of, "some" is as high as it goes.
     */
    fun cellColor(rate: Double?, maxRate: Double, colors: AppColors): Color {
        val value = rate ?: return colors.surface3
        if (value <= 0.0) return colors.badBg
        val fraction = if (maxRate > 0.0) (value / maxRate).coerceIn(0.0, 1.0) else 1.0
        return ramp(fraction.toFloat(), colors)
    }

    /**
     * The positive-rate ramp `goodBg → good`, starting at **0.3** of the way up rather than at the raw
     * ~12–14%-alpha `goodBg` — otherwise a low-positive cell is an indistinguishable faint tint next to
     * the `surface3`/`badBg` cells in light mode. The lowest positive rate is thus a clearly-green
     * swatch, and the best cell is solid `good`.
     */
    fun ramp(fraction: Float, colors: AppColors): Color =
        lerp(colors.goodBg, colors.good, 0.3f + 0.7f * fraction.coerceIn(0f, 1f))
}
