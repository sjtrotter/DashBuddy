package cloud.trotter.log.gig.core.pipeline.census

import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.contract.SkeletonVersion
import cloud.trotter.census.contract.contributor.ContributorSlots
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1626: the words a screen review may show. Only a slot the skeleton hashed keeps its word, keyed by the census
 * server's slot id, and only when the review's own cover passes it: no customer name, address, code or note, no
 * backstop hit, and none of the dasher's greeting, account or place labels. All values here are fictional.
 */
class SkeletonReviewWordsTest : SkeletonBuilderTestBase() {

    private fun built(tree: UiNode, title: String? = null, collect: Boolean = true, version: SkeletonVersion = SkeletonVersion.V2) =
        SkeletonBuilder.outcome(tree, title, meta, platform, day, version, collectWords = collect) as SkeletonBuilder.Outcome.Built

    private val screen = UiNode(
        className = "android.widget.FrameLayout",
        children = listOf(
            UiNode(className = "android.widget.TextView", text = "Pick up by"),
            UiNode(
                className = "android.widget.LinearLayout",
                children = listOf(
                    UiNode(className = "android.widget.Button", text = "Confirm pickup", contentDescription = "Confirm pickup button"),
                    UiNode(className = "android.widget.TextView", text = "$12.50"),
                ),
            ),
        ),
    ).restoreParents()

    @Test fun `words are kept only when asked and only on a v2 item`() {
        assertNull(built(screen, collect = false).reviewWords)
        assertNull(built(screen, version = SkeletonVersion.V1).reviewWords)
        val words = requireNotNull(built(screen).reviewWords)
        assertEquals("Pick up by", words["root/0/text"])
        assertEquals("Confirm pickup", words["root/1/0/text"])
        assertEquals("Confirm pickup button", words["root/1/0/desc"])
        // A money value is a shape, never a word.
        assertNull(words["root/1/1/text"])
    }

    @Test fun `every kept word sits at the server's slot id and hashes to that slot's hash`() {
        val outcome = built(screen, title = "Current dash")
        val words = requireNotNull(outcome.reviewWords)
        val slots = ContributorSlots.slots(outcome.skeleton).associateBy { it.id }
        assertTrue(words.slots.isNotEmpty())
        words.slots.forEach { id ->
            val slot = requireNotNull(slots[id]) { "no slot $id" }
            assertEquals(id, slot.slot.h, CensusHash.of(requireNotNull(words[id])))
        }
        // Every hashed slot the cover passes is kept.
        val hashed = slots.values.filter { it.slot.h != null }.map { it.id }.toSet()
        assertEquals(hashed, words.slots)
    }

    @Test fun `a name, an address, a code and a note are never kept`() {
        val values = listOf(
            "Riley S.", "Deliver to Sam Example", "1234 Sample Ridge Dr", "Testville, TX 78000",
            "Gate code 4821", "pin 0042", "\"Leave it by the blue door please\"", "Customer Notes: ring twice",
        )
        val tree = UiNode(
            className = "android.widget.FrameLayout",
            children = values.map { UiNode(className = "android.widget.TextView", text = it) } +
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/customer_name", text = "Morgan"),
        ).restoreParents()
        val words = requireNotNull(built(tree).reviewWords)
        (values + "Morgan").forEach { value ->
            words.slots.forEach { slot -> assertFalse("'$value' kept at $slot", words[slot]!!.contains(value.trim('"'))) }
        }
        assertTrue(words.isEmpty())
    }

    @Test fun `the dasher's greeting, account and place labels are not kept`() {
        val values = listOf("Hi, Jordan", "Welcome back", "Good evening", "Account settings", "Dash in Springfield", "Busy near Oak Hollow")
        val tree = UiNode(
            className = "android.widget.FrameLayout",
            children = values.map { UiNode(className = "android.widget.TextView", text = it) },
        ).restoreParents()
        val outcome = built(tree)
        assertTrue(requireNotNull(outcome.reviewWords).isEmpty())
        values.forEach { assertFalse(it, ReviewWordCover.shows(it)) }
        listOf("Pick up by", "Confirm pickup", "Arrived at store").forEach { assertTrue(it, ReviewWordCover.shows(it)) }
    }

    @Test fun `a node the address backstop selects keeps no word`() {
        val tree = UiNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                UiNode(
                    className = "android.widget.LinearLayout",
                    children = listOf(
                        UiNode(className = "android.widget.TextView", text = "1234 Sample Ridge Dr"),
                        UiNode(className = "android.widget.TextView", text = "Testville, TX 78000"),
                        UiNode(className = "android.widget.TextView", text = "\"Back gate\""),
                    ),
                ),
                UiNode(className = "android.widget.TextView", text = "Pick up by"),
            ),
        ).restoreParents()
        val words = requireNotNull(built(tree).reviewWords)
        assertEquals(setOf("root/1/text"), words.slots)
    }

    @Test fun `the words never print`() {
        val words = requireNotNull(built(screen).reviewWords)
        assertFalse(words.toString().contains("Pick up by"))
        assertFalse(built(screen).toString().contains("Pick up by"))
    }
}
