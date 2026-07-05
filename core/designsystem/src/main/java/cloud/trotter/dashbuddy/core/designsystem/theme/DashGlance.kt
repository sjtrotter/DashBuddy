package cloud.trotter.dashbuddy.core.designsystem.theme

import androidx.compose.runtime.compositionLocalOf

/**
 * Driving-mode glanceability multiplier — 1.0 normal, [DRIVING_GLANCE_MULTIPLIER] in driving
 * mode. HUD typography multiplies its size by this (see `AppTheme.num`/
 * `dashTextStyles` scaling). The user-facing toggle (#318) lives in Settings → General and
 * flows into [cloud.trotter.dashbuddy.ui.bubble.BubbleActivity] only — the main app window
 * never scales. System `fontScale` is honored separately (multiplied on top, not replaced).
 */
val LocalGlance = compositionLocalOf { 1f }

/** The single SSOT for the driving-mode glance bump (#318) — HUD-only, never the main window. */
const val DRIVING_GLANCE_MULTIPLIER = 1.12f
