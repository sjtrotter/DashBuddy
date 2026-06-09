package cloud.trotter.dashbuddy.core.designsystem.theme

import androidx.compose.runtime.compositionLocalOf

/**
 * Driving-mode glanceability multiplier — 1.0 normal, ~1.12 in driving mode. HUD typography
 * multiplies its size by this. The user-facing toggle lands later (#318); the plumbing lives
 * here so HUD composables can read it from day one. System `fontScale` is honored separately.
 */
val LocalGlance = compositionLocalOf { 1f }
