package cloud.trotter.dashbuddy.domain.evaluation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringUtilsTest {

    // -------------------------------------------------------------------------
    // calculateNormalizedScore
    // -------------------------------------------------------------------------

    @Test
    fun `normalizedScore - value at min returns 0`() {
        val result = ScoringUtils.calculateNormalizedScore(value = 0.0, min = 0.0, max = 10.0, weight = 1f)
        assertEquals(0.0, result, 0.0001)
    }

    @Test
    fun `normalizedScore - value at max returns weight`() {
        val result = ScoringUtils.calculateNormalizedScore(value = 10.0, min = 0.0, max = 10.0, weight = 1f)
        assertEquals(1.0, result, 0.0001)
    }

    @Test
    fun `normalizedScore - value at midpoint returns half weight`() {
        val result = ScoringUtils.calculateNormalizedScore(value = 5.0, min = 0.0, max = 10.0, weight = 1f)
        assertEquals(0.5, result, 0.0001)
    }

    @Test
    fun `normalizedScore - weight scales result`() {
        val result = ScoringUtils.calculateNormalizedScore(value = 10.0, min = 0.0, max = 10.0, weight = 0.5f)
        assertEquals(0.5, result, 0.0001)
    }

    @Test
    fun `normalizedScore - value below min clamps to 0`() {
        val result = ScoringUtils.calculateNormalizedScore(value = -5.0, min = 0.0, max = 10.0, weight = 1f)
        assertEquals(0.0, result, 0.0001)
    }

    @Test
    fun `normalizedScore - value above max clamps to weight`() {
        val result = ScoringUtils.calculateNormalizedScore(value = 20.0, min = 0.0, max = 10.0, weight = 1f)
        assertEquals(1.0, result, 0.0001)
    }

    // -------------------------------------------------------------------------
    // calculateInvertedNormalizedScore
    // -------------------------------------------------------------------------

    @Test
    fun `invertedNormalizedScore - value at min returns weight (best case)`() {
        val result = ScoringUtils.calculateInvertedNormalizedScore(value = 0.0, min = 0.0, max = 10.0, weight = 1f)
        assertEquals(1.0, result, 0.0001)
    }

    @Test
    fun `invertedNormalizedScore - value at max returns 0 (worst case)`() {
        val result = ScoringUtils.calculateInvertedNormalizedScore(value = 10.0, min = 0.0, max = 10.0, weight = 1f)
        assertEquals(0.0, result, 0.0001)
    }

    @Test
    fun `invertedNormalizedScore - value at midpoint returns half weight`() {
        val result = ScoringUtils.calculateInvertedNormalizedScore(value = 5.0, min = 0.0, max = 10.0, weight = 1f)
        assertEquals(0.5, result, 0.0001)
    }

    @Test
    fun `invertedNormalizedScore - value above max clamps to 0`() {
        val result = ScoringUtils.calculateInvertedNormalizedScore(value = 15.0, min = 0.0, max = 10.0, weight = 1f)
        assertEquals(0.0, result, 0.0001)
    }

    @Test
    fun `invertedNormalizedScore - value below min clamps to weight`() {
        val result = ScoringUtils.calculateInvertedNormalizedScore(value = -5.0, min = 0.0, max = 10.0, weight = 1f)
        assertEquals(1.0, result, 0.0001)
    }

    // -------------------------------------------------------------------------
    // determineOfferQuality
    // -------------------------------------------------------------------------

    @Test
    fun `quality - score below 40 is BAD OFFER`() {
        assertEquals("BAD OFFER", ScoringUtils.determineOfferQuality(0.0))
        assertEquals("BAD OFFER", ScoringUtils.determineOfferQuality(39.9))
    }

    @Test
    fun `quality - score 40 to 49 is DECENT OFFER`() {
        assertEquals("DECENT OFFER", ScoringUtils.determineOfferQuality(40.0))
        assertEquals("DECENT OFFER", ScoringUtils.determineOfferQuality(49.9))
    }

    @Test
    fun `quality - score 50 to 59 is GOOD OFFER`() {
        assertEquals("GOOD OFFER", ScoringUtils.determineOfferQuality(50.0))
        assertEquals("GOOD OFFER", ScoringUtils.determineOfferQuality(59.9))
    }

    @Test
    fun `quality - score 60 to 69 is GREAT OFFER`() {
        assertEquals("GREAT OFFER", ScoringUtils.determineOfferQuality(60.0))
        assertEquals("GREAT OFFER", ScoringUtils.determineOfferQuality(69.9))
    }

    @Test
    fun `quality - score 70 and above is AWESOME OFFER`() {
        assertEquals("AWESOME OFFER", ScoringUtils.determineOfferQuality(70.0))
        assertEquals("AWESOME OFFER", ScoringUtils.determineOfferQuality(100.0))
    }

    @Test
    fun `quality - boundary values are correctly assigned`() {
        // Exact boundaries fall into the higher band
        assertTrue(ScoringUtils.determineOfferQuality(40.0) != "BAD OFFER")
        assertTrue(ScoringUtils.determineOfferQuality(50.0) != "DECENT OFFER")
        assertTrue(ScoringUtils.determineOfferQuality(60.0) != "GOOD OFFER")
        assertTrue(ScoringUtils.determineOfferQuality(70.0) != "GREAT OFFER")
    }
}
