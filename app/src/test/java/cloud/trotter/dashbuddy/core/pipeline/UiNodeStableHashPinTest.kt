package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * #1160 review AA9 — frozen `UiNode.stableHash` values over committed fixtures (the
 * `CurrencyShapePinTest` doctrine).
 *
 * #1145 moved the anonymous-wrapper class set into the census contract
 * (`AnonymousWrappers.WRAPPER_CLASSES`), which `stableHash` now reads. `stableHash` feeds
 * `UnknownSuppressor` / frame identity, so an edit to that set made FOR THE CENSUS would silently move
 * frame identity everywhere. These pins make it loud.
 *
 * The expected ints were computed by an INDEPENDENT reimplementation of the pre-#1145 algorithm (a short
 * Python script: Java `String.hashCode` over UTF-16 units, 32-bit wrap, the four-class wrapper set, fold
 * — never splice), so they pin the algorithm, not a snapshot of whatever the code does today. A
 * legitimate change to `stableHash` or the wrapper set must update these deliberately, and say why.
 */
class UiNodeStableHashPinTest {

    private val pins: Map<String, Int> = mapOf(
        "offer_popup/2026-08-28_15-31-47-105__doordash__accessibility.window__UNKNOWN__c38cf0.json" to -784567536,
        "dropoff_navigation/2026-08-02_18-00-01-999__doordash__accessibility.window__dropoff_navigation__2109db.json" to 2032208347,
        "timeline/2026-03-27_18-02-09__DELIVERY_SUMMARY_COLLAPSED__b9810e60.json" to -436191884,
        "waiting_for_offer/2026-08-18_18-41-22-167__doordash__accessibility.window__waiting_for_offer__9bc1ac.json" to -1201389996,
        "UNKNOWN/negative/2026-07-19_13-32-02-456__uber__accessibility.window__home_dashboard__5a3613.json" to -1129985555,
        "pickup_arrival/20260128_190230_416_PICKUP_DETAILS_POST_ARRIVAL_PICKUP_SINGLE.json" to 1193376928,
        "dash_summary/2026-02-04_18-11-11__DASH_SUMMARY_SCREEN__edfd1832.json" to -1836030912,
    )

    @Test
    fun `stableHash is frozen over committed fixtures`() {
        val actual = pins.mapValues { (path, _) ->
            TestResourceLoader.loadNode(File("src/test/resources/snapshots/$path")).stableHash
        }
        assertEquals(pins, actual)
    }
}
