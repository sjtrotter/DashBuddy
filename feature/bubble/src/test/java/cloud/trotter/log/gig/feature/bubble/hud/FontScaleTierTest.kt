package cloud.trotter.log.gig.feature.bubble.hud

import org.junit.Assert.*
import org.junit.Test

class FontScaleTierTest {
    @Test fun below130_isBase() { assertEquals(FontScaleTier.BASE, FontScaleTier.of(1.299f, 1f)) }
    @Test fun at130_labelsBeside() { assertEquals(FontScaleTier.LABELS_BESIDE, FontScaleTier.of(1.3f, 1f)) }
    @Test fun at150_dropsJobColumn() { assertEquals(FontScaleTier.NO_JOB_COLUMN, FontScaleTier.of(1.5f, 1f)) }
    @Test fun at180_compactHero() { assertEquals(FontScaleTier.COMPACT_HERO, FontScaleTier.of(1.8f, 1f)) }
    @Test fun at200_compactHero() { assertEquals(FontScaleTier.COMPACT_HERO, FontScaleTier.of(2f, 1f)) }
    @Test fun glance_multipliesFontScale() { assertEquals(FontScaleTier.NO_JOB_COLUMN, FontScaleTier.of(1.4f, 1.12f)) }
    @Test fun tiersAreCumulative() {
        assertTrue(FontScaleTier.COMPACT_HERO >= FontScaleTier.NO_JOB_COLUMN)
        assertTrue(FontScaleTier.NO_JOB_COLUMN >= FontScaleTier.LABELS_BESIDE)
        assertTrue(FontScaleTier.LABELS_BESIDE > FontScaleTier.BASE)
    }
}
