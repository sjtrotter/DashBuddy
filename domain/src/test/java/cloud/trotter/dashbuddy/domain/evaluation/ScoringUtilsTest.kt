package cloud.trotter.dashbuddy.domain.evaluation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import cloud.trotter.dashbuddy.domain.evaluation.OfferQuality

class ScoringUtilsTest {

    // -------------------------------------------------------------------------
    // determineOfferQuality
    // -------------------------------------------------------------------------

    @Test
    fun `quality - score below 40 is BAD OFFER`() {
        assertEquals(OfferQuality.BAD, ScoringUtils.determineOfferQuality(0.0))
        assertEquals(OfferQuality.BAD, ScoringUtils.determineOfferQuality(39.9))
    }

    @Test
    fun `quality - score 40 to 49 is DECENT OFFER`() {
        assertEquals(OfferQuality.DECENT, ScoringUtils.determineOfferQuality(40.0))
        assertEquals(OfferQuality.DECENT, ScoringUtils.determineOfferQuality(49.9))
    }

    @Test
    fun `quality - score 50 to 59 is GOOD OFFER`() {
        assertEquals(OfferQuality.GOOD, ScoringUtils.determineOfferQuality(50.0))
        assertEquals(OfferQuality.GOOD, ScoringUtils.determineOfferQuality(59.9))
    }

    @Test
    fun `quality - score 60 to 69 is GREAT OFFER`() {
        assertEquals(OfferQuality.GREAT, ScoringUtils.determineOfferQuality(60.0))
        assertEquals(OfferQuality.GREAT, ScoringUtils.determineOfferQuality(69.9))
    }

    @Test
    fun `quality - score 70 and above is AWESOME OFFER`() {
        assertEquals(OfferQuality.AWESOME, ScoringUtils.determineOfferQuality(70.0))
        assertEquals(OfferQuality.AWESOME, ScoringUtils.determineOfferQuality(100.0))
    }

    @Test
    fun `quality - boundary values are correctly assigned`() {
        // Exact boundaries fall into the higher band
        assertTrue(ScoringUtils.determineOfferQuality(40.0) != OfferQuality.BAD)
        assertTrue(ScoringUtils.determineOfferQuality(50.0) != OfferQuality.DECENT)
        assertTrue(ScoringUtils.determineOfferQuality(60.0) != OfferQuality.GOOD)
        assertTrue(ScoringUtils.determineOfferQuality(70.0) != OfferQuality.GREAT)
    }
}
