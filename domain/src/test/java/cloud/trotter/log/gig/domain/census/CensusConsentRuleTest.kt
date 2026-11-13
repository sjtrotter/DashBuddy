package cloud.trotter.log.gig.domain.census

import cloud.trotter.log.gig.domain.capability.ConsentReceipt
import cloud.trotter.log.gig.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Test

/** #1621: the one consent rule, and the platforms a saved choice covers. */
class CensusConsentRuleTest {
    private val endpoint = "https://census.example"
    private fun receipt(granted: Boolean, at: String = endpoint) =
        CensusConsentReceipt(ConsentReceipt(1L, "1.0", 20, granted), at)

    @Test fun `on only with the switch on and a grant for exactly this endpoint`() {
        assertEquals(CensusConsent.ON, CensusConsent.of(true, receipt(true), endpoint))
        assertEquals(CensusConsent.OFF, CensusConsent.of(false, receipt(true), endpoint))
        assertEquals(CensusConsent.OFF, CensusConsent.of(true, null, endpoint))
        assertEquals(CensusConsent.OFF, CensusConsent.of(true, receipt(false), endpoint))
        assertEquals(CensusConsent.ENDPOINT_CHANGED, CensusConsent.of(true, receipt(true, "https://old.example"), endpoint))
        assertEquals(CensusConsent.OFF, CensusConsent.of(true, receipt(false, "https://old.example"), endpoint))
        assertEquals(CensusConsent.ENDPOINT_CHANGED, CensusConsent.of(true, receipt(true, "$endpoint/"), endpoint))
    }

    @Test fun `no saved choice covers every readable platform and a saved one never grows`() {
        val readable = listOf(Platform.DoorDash, Platform.Uber)
        assertEquals(readable.toSet(), CensusChoices(null, true).platformsAmong(readable))
        assertEquals(setOf(Platform.DoorDash), CensusChoices(setOf(Platform.DoorDash), true).platformsAmong(readable))
        assertEquals(emptySet<Platform>(), CensusChoices(setOf(Platform.Instacart), true).platformsAmong(readable))
    }
}
