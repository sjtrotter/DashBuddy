package cloud.trotter.dashbuddy.domain.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The evidence-capture policy truth table (#426): master AND category, with
 * uncategorized captures denied unconditionally.
 */
class EvidenceConfigTest {

    private val allOn = EvidenceConfig(
        masterEnabled = true,
        saveOffers = true,
        saveDeliverySummaries = true,
        saveDashSummaries = true,
    )

    @Test
    fun `master off denies every category`() {
        val config = allOn.copy(masterEnabled = false)
        EvidenceCategory.entries.forEach { category ->
            assertFalse("master off must deny $category", config.allows(category))
        }
    }

    @Test
    fun `the factory default denies everything - the app's own default-off promise`() {
        val config = EvidenceConfig()
        EvidenceCategory.entries.forEach { category ->
            assertFalse("default config must deny $category", config.allows(category))
        }
        assertFalse(config.allows(null))
    }

    @Test
    fun `master on gates per category`() {
        val config = allOn.copy(saveDeliverySummaries = false)
        assertTrue(config.allows(EvidenceCategory.OFFER))
        assertFalse(config.allows(EvidenceCategory.DELIVERY_SUMMARY))
        assertTrue(config.allows(EvidenceCategory.DASH_SUMMARY))
    }

    @Test
    fun `uncategorized capture is denied even with everything on - fail closed`() {
        assertFalse(allOn.allows(null))
    }

    @Test
    fun `category wire mapping round-trips and rejects unknowns`() {
        EvidenceCategory.entries.forEach { category ->
            assertEquals(category, EvidenceCategory.fromWire(category.wire))
        }
        assertNull(EvidenceCategory.fromWire("selfie"))
        assertNull(EvidenceCategory.fromWire(null))
    }
}
