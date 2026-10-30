package cloud.trotter.log.gig.feature.bubble.hud

import androidx.compose.runtime.staticCompositionLocalOf

enum class FontScaleTier {
    BASE, LABELS_BESIDE, NO_JOB_COLUMN, COMPACT_HERO;

    companion object {
        fun of(fontScale: Float, glance: Float): FontScaleTier = when {
            fontScale * glance >= 1.8f -> COMPACT_HERO
            fontScale * glance >= 1.5f -> NO_JOB_COLUMN
            fontScale * glance >= 1.3f -> LABELS_BESIDE
            else -> BASE
        }
    }
}

internal val LocalHudTier = staticCompositionLocalOf { FontScaleTier.BASE }
