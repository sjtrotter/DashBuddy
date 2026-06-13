package cloud.trotter.dashbuddy.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Glanceability-tuned type scale (px → sp): hero 30 · xl 22 · lg 18 · md 15 · sm 13 · xs 11 · micro 10. */
object AppTypeScale {
    val hero = 30.sp
    val xl = 22.sp
    val lg = 18.sp
    val md = 15.sp
    val sm = 13.sp
    val xs = 11.sp
    val micro = 10.sp
}

/**
 * Numeric / hero styles — Space Grotesk with tabular figures (`tnum`) so live-ticking values
 * (timers, $/hr, miles, scores) don't jitter. **Rule:** any value that ticks or is a metric
 * uses one of these styles. Plus the uppercase `chip` label style.
 */
@Immutable
data class AppTextStyles(
    val heroNum: TextStyle,
    val xlNum: TextStyle,
    val lgNum: TextStyle,
    val mdNum: TextStyle,
    val smNum: TextStyle,
    val xsNum: TextStyle,
    val chip: TextStyle,
)

private fun num(size: TextUnit, weight: FontWeight = FontWeight.Bold, ls: Double = -0.01): TextStyle =
    TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = weight,
        fontSize = size,
        letterSpacing = ls.em,
        fontFeatureSettings = "tnum",
    )

fun dashTextStyles(): AppTextStyles = AppTextStyles(
    heroNum = num(AppTypeScale.hero, FontWeight.Bold, -0.02),
    xlNum = num(AppTypeScale.xl),
    lgNum = num(AppTypeScale.lg),
    mdNum = num(AppTypeScale.md),
    smNum = num(AppTypeScale.sm),
    xsNum = num(AppTypeScale.xs),
    chip = TextStyle(
        fontFamily = HankenGrotesk,
        fontWeight = FontWeight.Bold,
        fontSize = AppTypeScale.xs,
        letterSpacing = 0.06.em,
    ),
)

val LocalDashTextStyles = staticCompositionLocalOf { dashTextStyles() }

/** Material 3 Typography — Hanken Grotesk across the board, sized to the design scale. */
val AppTypography: Typography = Typography().let { b ->
    val ui = HankenGrotesk
    b.copy(
        displayLarge = b.displayLarge.copy(fontFamily = ui),
        displayMedium = b.displayMedium.copy(fontFamily = ui),
        displaySmall = b.displaySmall.copy(fontFamily = ui),
        headlineLarge = b.headlineLarge.copy(fontFamily = ui),
        headlineMedium = b.headlineMedium.copy(fontFamily = ui, fontSize = AppTypeScale.xl, fontWeight = FontWeight.SemiBold),
        headlineSmall = b.headlineSmall.copy(fontFamily = ui, fontSize = AppTypeScale.lg, fontWeight = FontWeight.SemiBold),
        titleLarge = b.titleLarge.copy(fontFamily = ui, fontSize = AppTypeScale.lg),
        titleMedium = b.titleMedium.copy(fontFamily = ui, fontSize = AppTypeScale.md, fontWeight = FontWeight.SemiBold),
        titleSmall = b.titleSmall.copy(fontFamily = ui, fontSize = AppTypeScale.sm, fontWeight = FontWeight.SemiBold),
        bodyLarge = b.bodyLarge.copy(fontFamily = ui, fontSize = AppTypeScale.md),
        bodyMedium = b.bodyMedium.copy(fontFamily = ui, fontSize = AppTypeScale.sm),
        bodySmall = b.bodySmall.copy(fontFamily = ui, fontSize = AppTypeScale.xs),
        labelLarge = b.labelLarge.copy(fontFamily = ui, fontSize = AppTypeScale.sm, fontWeight = FontWeight.SemiBold),
        labelMedium = b.labelMedium.copy(fontFamily = ui, fontSize = AppTypeScale.xs, fontWeight = FontWeight.Medium),
        labelSmall = b.labelSmall.copy(fontFamily = ui, fontSize = AppTypeScale.micro, fontWeight = FontWeight.Medium),
    )
}
