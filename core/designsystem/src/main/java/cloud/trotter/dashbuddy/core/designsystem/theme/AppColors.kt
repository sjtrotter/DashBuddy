package cloud.trotter.dashbuddy.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * DashBuddy brand semantic color tokens — transcribed from the design handoff
 * (`styles/dashbuddy.css`). Dark is the primary theme (drivers work at night).
 *
 * Design-specific semantics (phase chips, status badges, good/warn/bad, offer/pickup)
 * read from [LocalDashColors] — NOT Material 3 roles — so they stay meaningful and fixed,
 * never wallpaper-derived. Material 3 roles are still mapped (see AppTheme) so stock M3
 * components render on-brand.
 *
 * `*Bg` tokens are the base color at 14% (dark) / 12% (light) alpha.
 */
@Immutable
data class AppColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val surfaceFrost: Color,
    val line: Color,
    val lineStrong: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val textInv: Color,
    val accent: Color,
    val accentDim: Color,
    val accentText: Color,
    val good: Color,
    val goodBg: Color,
    val warn: Color,
    val warnBg: Color,
    val bad: Color,
    val badBg: Color,
    val neutral: Color,
    val neutralBg: Color,
    val stOffer: Color,
    val stOfferBg: Color,
    val stPickup: Color,
    val stPickupBg: Color,
    val isDark: Boolean,
)

/** Dark theme — primary. */
fun darkAppColors(): AppColors = AppColors(
    bg = Color(0xFF0C1014),
    surface = Color(0xFF141A21),
    surface2 = Color(0xFF1B2530),
    surface3 = Color(0xFF243240),
    surfaceFrost = Color(0xB81B2530), // rgba(27,37,48,0.72)
    line = Color(0x14FFFFFF),         // white 8%
    lineStrong = Color(0x29FFFFFF),   // white 16%
    text = Color(0xFFEEF3F7),
    text2 = Color(0xFFAAB6C2),
    text3 = Color(0xFF6B7886),
    textInv = Color(0xFF0C1014),
    accent = Color(0xFF46E0C8),
    accentDim = Color(0xFF1F4D49),
    accentText = Color(0xFF052B27),
    good = Color(0xFF3DDC84),
    goodBg = Color(0x243DDC84),       // 14%
    warn = Color(0xFFFFC24B),
    warnBg = Color(0x24FFC24B),
    bad = Color(0xFFFF5D5D),
    badBg = Color(0x24FF5D5D),
    neutral = Color(0xFF8B97A4),
    neutralBg = Color(0x248B97A4),
    stOffer = Color(0xFF5B9DFF),
    stOfferBg = Color(0x295B9DFF),    // 16%
    stPickup = Color(0xFFB78BFF),
    stPickupBg = Color(0x29B78BFF),
    isDark = true,
)

/** Light theme — follows system; semantic tokens stay meaningful. */
fun lightAppColors(): AppColors = AppColors(
    bg = Color(0xFFEEF2F4),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF3F6F8),
    surface3 = Color(0xFFE7EDF1),
    surfaceFrost = Color(0xD1FFFFFF), // rgba(255,255,255,0.82)
    line = Color(0x140D141E),         // black 8%
    lineStrong = Color(0x290D141E),   // black 16%
    text = Color(0xFF111A22),
    text2 = Color(0xFF46555F),
    text3 = Color(0xFF8593A0),
    textInv = Color(0xFFFFFFFF),
    accent = Color(0xFF0B9E8C),
    accentDim = Color(0xFFC8EFE9),
    accentText = Color(0xFFFFFFFF),
    good = Color(0xFF1D9E54),
    goodBg = Color(0x1F1D9E54),       // 12%
    warn = Color(0xFFC98400),
    warnBg = Color(0x1FC98400),
    bad = Color(0xFFD23B3B),
    badBg = Color(0x1FD23B3B),
    neutral = Color(0xFF6C7884),
    neutralBg = Color(0x1F6C7884),
    stOffer = Color(0xFF2F6FE0),
    stOfferBg = Color(0x1F2F6FE0),
    stPickup = Color(0xFF7A4EE0),
    stPickupBg = Color(0x1F7A4EE0),
    isDark = false,
)

/** Brand semantic tokens. Provided by [DashBuddyTheme]; read via `AppTheme.colors`. */
val LocalDashColors = staticCompositionLocalOf { darkAppColors() }
