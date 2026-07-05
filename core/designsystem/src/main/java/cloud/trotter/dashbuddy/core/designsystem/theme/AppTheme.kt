package cloud.trotter.dashbuddy.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/** Maps the fixed brand palette onto Material 3 roles so stock M3 components render on-brand. */
private fun dashColorScheme(c: AppColors): ColorScheme {
    val base = if (c.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accent,
        onPrimary = c.accentText,
        primaryContainer = c.accentDim,
        onPrimaryContainer = c.accent,
        secondary = c.stOffer,
        onSecondary = c.textInv,
        secondaryContainer = c.stOfferBg,
        onSecondaryContainer = c.stOffer,
        tertiary = c.stPickup,
        onTertiary = c.textInv,
        tertiaryContainer = c.stPickupBg,
        onTertiaryContainer = c.stPickup,
        background = c.bg,
        onBackground = c.text,
        surface = c.surface,
        onSurface = c.text,
        surfaceVariant = c.surface3,
        onSurfaceVariant = c.text2,
        surfaceContainerLowest = c.bg,
        surfaceContainerLow = c.surface,
        surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface2,
        surfaceContainerHighest = c.surface3,
        outline = c.lineStrong,
        outlineVariant = c.line,
        error = c.bad,
        onError = c.textInv,
        errorContainer = c.badBg,
        onErrorContainer = c.bad,
        scrim = c.bg,
    )
}

/**
 * Root brand theme. Fixed dark/light brand schemes (no Material You dynamic color), brand
 * typography + shapes, and the brand [LocalDashColors] / [LocalDashTextStyles] / [LocalGlance].
 */
@Composable
fun DashBuddyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    glance: Float = 1f,
    content: @Composable () -> Unit,
) {
    val dash = if (darkTheme) darkAppColors() else lightAppColors()
    CompositionLocalProvider(
        LocalDashColors provides dash,
        // #318: the numeric HUD style family (AppTheme.num.*) scales by `glance` on top of
        // system fontScale — a no-op at the 1.0 default the main app window always passes.
        LocalDashTextStyles provides dashTextStyles().scaledByGlance(glance),
        LocalGlance provides glance,
    ) {
        MaterialTheme(
            colorScheme = dashColorScheme(dash),
            // Same scaling for stock M3 typography (captions/labels/chips) so the whole HUD
            // card moves together, not just the numeric heroes.
            typography = AppTypography.scaledByGlance(glance),
            shapes = AppShapes,
            content = content,
        )
    }
}

/** Ergonomic accessors for the brand tokens: `AppTheme.colors.good`, `AppTheme.num.heroNum`. */
object AppTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalDashColors.current
    val num: AppTextStyles
        @Composable @ReadOnlyComposable get() = LocalDashTextStyles.current
    val glance: Float
        @Composable @ReadOnlyComposable get() = LocalGlance.current
}
