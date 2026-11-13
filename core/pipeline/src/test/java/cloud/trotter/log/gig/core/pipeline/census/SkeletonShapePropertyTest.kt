package cloud.trotter.log.gig.core.pipeline.census

import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.census.contract.KindClassifier
import cloud.trotter.census.contract.ShapeClassifier
import cloud.trotter.census.contract.SkeletonVersion
import cloud.trotter.log.gig.core.pipeline.PropSeeds
import cloud.trotter.log.gig.core.pipeline.census.NotificationSkeletonBuilder.Outcome
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.model.notification.RawNotificationData
import cloud.trotter.log.gig.domain.state.Platform
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0011 §13 / §21 (#1619): no generated personal value ever becomes a shape — not a customer's name, address,
 * phone number or code, and not the dasher's own name, greeting, account identifier or location-bearing label. Each
 * value is checked through the classifier on its canonical form AND through the v2 builder in every text position
 * (text, desc, hint, window title, notification field and action label). All names and places are fictional.
 *
 * **Determinism (#878).** [SEED] pins PR CI; `-Dgiglog.propExplore=true` explores unseeded ([PropSeeds]).
 */
class SkeletonShapePropertyTest : SkeletonBuilderTestBase() {

    private companion object {
        /** #878 pinned seed — pins PR CI. Bump deliberately to explore new samples. */
        const val SEED = 0x1619_0001L

        val FIRST = listOf("Avery", "Jordan", "Riley", "Sam", "Morgan", "Casey", "Quinn", "José", "Mary-Jo", "O'Brien", "Li")
        val LAST = listOf("Example", "Sample", "Testerson", "Placeholder", "Fakename")
        val STREETS = listOf("Sample Ridge", "Oak Hollow", "Fletchers", "Main", "Elm", "Test Creek")
        val SUFFIXES = listOf("Dr", "Ln", "St", "Ave", "Rd", "Way", "Blvd", "Ct", "")
        val CITIES = listOf("San Antonio", "Testville", "Springfield", "Stone Oak", "Alamo Heights", "Medical Center")
        val ZONES = listOf("Downtown", "North Central", "Zone", "Area", "Region", "Market")
    }

    private fun RandomSource.pick(list: List<String>) = list[random.nextInt(list.size)]
    private fun RandomSource.digits(n: Int) = (1..n).joinToString("") { random.nextInt(10).toString() }
    private fun RandomSource.initial() = ('A' + random.nextInt(26)).toString()

    /** A customer's personal value, in one of the forms a screen or notification renders. */
    private val customerValue: Arb<String> = arbitrary { rs ->
        val first = rs.pick(FIRST)
        val last = rs.pick(LAST)
        when (rs.random.nextInt(14)) {
            0 -> first
            1 -> "$first ${rs.initial()}"
            2 -> "$first ${rs.initial()}."
            3 -> "$first $last"
            4 -> "${1 + rs.random.nextInt(9999)} ${rs.pick(STREETS)} ${rs.pick(SUFFIXES)}".trim()
            5 -> "${rs.pick(CITIES)}, TX ${rs.digits(5)}"
            6 -> "Apt ${1 + rs.random.nextInt(999)}"
            7 -> "Unit ${rs.digits(2)}${rs.initial()}"
            8 -> "(210) 555-${rs.digits(4)}"
            9 -> "210-555-${rs.digits(4)}"
            10 -> "+1 210 555 ${rs.digits(4)}"
            11 -> listOf("Gate code ", "pin ", "Code: ", "#", "Locker ", "Door ").let { rs.pick(it) } + rs.digits(3 + rs.random.nextInt(4))
            12 -> "Order #${rs.digits(2)}${rs.initial()}${rs.digits(3)}"
            else -> "${rs.digits(1 + rs.random.nextInt(6))}"
        }
    }

    /** The dasher's own data: their name, greeting, account identifiers and location-bearing labels. */
    private val dasherValue: Arb<String> = arbitrary { rs ->
        val first = rs.pick(FIRST)
        when (rs.random.nextInt(12)) {
            0 -> "Hi, $first"
            1 -> "Hello $first!"
            2 -> "Welcome back, $first ${rs.initial()}."
            3 -> "Good evening, $first"
            4 -> "Dasher ID ${rs.digits(7)}"
            5 -> "Account ending in ${rs.digits(4)}"
            6 -> "••••${rs.digits(4)}"
            7 -> "Acct #${rs.digits(8)}"
            8 -> "${first.lowercase()}${rs.digits(2)}@example.com"
            9 -> "${rs.pick(ZONES)} ${rs.digits(1 + rs.random.nextInt(3))}"
            10 -> "Dash in ${rs.pick(CITIES)}"
            else -> "${rs.pick(CITIES)} ${rs.digits(5)}"
        }
    }

    private fun assertNoShape(value: String) {
        CensusHash.canonical(value)?.let { assertNull("'$value' fits a template", ShapeClassifier.classify(it)) }
        assertNull("'$value' fits a template raw", ShapeClassifier.classify(value.trim()))

        val item = SkeletonBuilder.build(
            UiNode(
                className = "android.widget.FrameLayout",
                children = listOf(UiNode(className = "android.widget.TextView", text = value, contentDescription = value, hintText = value)),
            ).restoreParents(),
            value, meta, platform, day, SkeletonVersion.V2,
        )
        item?.let { built -> CensusSkeletonSchema.slots(built).forEach { assertTrue("'$value' became a shape", it.kind != KindClassifier.SHAPE) } }

        val raw = RawNotificationData(
            value, value, value, null,
            packageName = Platform.DoorDash.packageName!!, postTime = 1, isClearable = true, channelId = "synthetic-updates",
            actionLabels = listOf(value),
        )
        val outcome = NotificationSkeletonBuilder.outcome(raw, meta, platform, day, SkeletonVersion.V2)
        (outcome as? Outcome.Built)?.let { built ->
            CensusSkeletonSchema.slots(built.skeleton).forEach { assertTrue("'$value' became a notification shape", it.kind != KindClassifier.SHAPE) }
        }
    }

    @Test
    fun `property - no generated customer value ever produces a shape`() = runTest {
        checkAll(PropSeeds.samples(400), PropSeeds.config(SEED), customerValue) { assertNoShape(it) }
    }

    @Test
    fun `property - no generated dasher value ever produces a shape`() = runTest {
        checkAll(PropSeeds.samples(400), PropSeeds.config(SEED + 1), dasherValue) { assertNoShape(it) }
    }

    @Test
    fun `property - a 12-hour clock is one class whatever the hour and meridiem`() = runTest {
        val clock: Arb<Pair<String, String>> = arbitrary { rs ->
            val hour = 1 + rs.random.nextInt(12)
            val minute = rs.random.nextInt(60).toString().padStart(2, '0')
            "$hour:$minute AM" to "$hour:$minute PM"
        }
        checkAll(PropSeeds.samples(200), PropSeeds.config(SEED + 2), clock) { (am, pm) ->
            val template = ShapeClassifier.classify(am)
            assertTrue(am, template != null)
            assertEquals(pm, template, ShapeClassifier.classify(pm))
        }
    }
}
