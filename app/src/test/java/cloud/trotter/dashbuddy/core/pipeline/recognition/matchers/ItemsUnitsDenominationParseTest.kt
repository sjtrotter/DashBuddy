package cloud.trotter.dashbuddy.core.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.test.util.SessionReplay
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #823 Phase 1 — the units-vs-items **denomination** survives the production parse. Feeds real
 * offer captures through the production screen ruleset (via [SessionReplay]'s classifier, the same
 * one [GoldenSnapshotRegressionTest] guards) and asserts [cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer.itemCountIsUnits]:
 *
 *  - a units-only render (`(32 units)`, the dev's fielded H-E-B shape) → `itemCountIsUnits == true`,
 *    with [itemCount] still the platform-shown number (32 — the card shows what DoorDash said);
 *  - an items•units render (`(4 items • 5 units)`) → `false` (the leading count is items).
 *
 * This closes the recognition→parse wiring the evaluator's ratio conversion depends on; the
 * time-math itself is covered by `ShopTimeModelTest`.
 */
class ItemsUnitsDenominationParseTest {

    private fun parseOffer(pathFromResources: String): ParsedFields.OfferFields {
        val file = File("src/test/resources/$pathFromResources")
        require(file.isFile) { "Missing snapshot: ${file.absolutePath}" }
        val frame = SessionReplay.ReplayFrame(
            file = file.name,
            node = TestResourceLoader.loadNode(file),
            capturedAtMs = 0L,
            wire = Platform.DoorDash.wire,
            captureId = null,
        )
        val obs: Observation.Screen = SessionReplay.replayRecognition(listOf(frame)).single()
        return obs.parsed as? ParsedFields.OfferFields
            ?: error("${file.name} did not parse as an offer — parsed as: ${obs.parsed}")
    }

    @Test
    fun `a units-only offer parses as units-denominated with the platform-shown count`() {
        val offer = parseOffer("snapshots/sessions/ghost_offer_2026_06_14/01_real_offer_heb_1630.json").parsedOffer
        assertTrue("H-E-B '(32 units)' must read as units-denominated", offer.itemCountIsUnits)
        assertEquals("the surfaced count stays the platform-shown units figure", 32, offer.itemCount)
    }

    @Test
    fun `an items-and-units offer parses as items-denominated (leading count is items)`() {
        val offer = parseOffer("snapshots/offer_popup/20260128_163448_533_OFFER_POPUP.json").parsedOffer
        assertTrue("'(4 items • 5 units)' leads with items → not units-denominated", !offer.itemCountIsUnits)
        assertEquals(4, offer.itemCount)
    }
}
