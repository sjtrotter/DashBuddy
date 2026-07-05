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

/** Scales a single style's font size by [glance] — a no-op copy at the 1.0 default. */
private fun TextStyle.scaledByGlance(glance: Float): TextStyle =
    if (glance == 1f) this else copy(fontSize = fontSize * glance)

/**
 * Driving / glance mode (#318): every numeric HUD style scales by [glance] on top of whatever
 * system `fontScale` already applied to the base sp values — the two multiply, neither replaces
 * the other. Called from [DashBuddyTheme] with the live [LocalGlance] value; a no-op at 1.0 so
 * the main app window (which never passes `glance`) is unaffected.
 */
fun AppTextStyles.scaledByGlance(glance: Float): AppTextStyles = if (glance == 1f) this else copy(
    heroNum = heroNum.scaledByGlance(glance),
    xlNum = xlNum.scaledByGlance(glance),
    lgNum = lgNum.scaledByGlance(glance),
    mdNum = mdNum.scaledByGlance(glance),
    smNum = smNum.scaledByGlance(glance),
    xsNum = xsNum.scaledByGlance(glance),
    chip = chip.scaledByGlance(glance),
)

/**
 * Scales every M3 [Typography] slot by [glance] (#318) — the HUD also renders plain body/label
 * text (captions, chips) via `MaterialTheme.typography.*`, not just the [AppTextStyles] numeric
 * family, so both need to move together for the toggle to visibly enlarge the whole card.
 */
fun Typography.scaledByGlance(glance: Float): Typography = if (glance == 1f) this else copy(
    displayLarge = displayLarge.scaledByGlance(glance),
    displayMedium = displayMedium.scaledByGlance(glance),
    displaySmall = displaySmall.scaledByGlance(glance),
    headlineLarge = headlineLarge.scaledByGlance(glance),
    headlineMedium = headlineMedium.scaledByGlance(glance),
    headlineSmall = headlineSmall.scaledByGlance(glance),
    titleLarge = titleLarge.scaledByGlance(glance),
    titleMedium = titleMedium.scaledByGlance(glance),
    titleSmall = titleSmall.scaledByGlance(glance),
    bodyLarge = bodyLarge.scaledByGlance(glance),
    bodyMedium = bodyMedium.scaledByGlance(glance),
    bodySmall = bodySmall.scaledByGlance(glance),
    labelLarge = labelLarge.scaledByGlance(glance),
    labelMedium = labelMedium.scaledByGlance(glance),
    labelSmall = labelSmall.scaledByGlance(glance),
)

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
