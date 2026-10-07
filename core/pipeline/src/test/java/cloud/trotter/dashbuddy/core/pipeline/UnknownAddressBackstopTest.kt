package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.capture.schema.UiNodeSchema
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1116 — the UNKNOWN-only address-block backstop over synthetic trees. Every value is invented. The
 * layouts are the fielded Timeline order-detail sheet (flat and nested), the July three-branch content
 * block, and the review counterexamples of the closed PR #1265 (see the class KDoc of
 * [UnknownAddressBackstop]).
 */
class UnknownAddressBackstopTest {

    private fun id(suffix: String) = "com.doordash.driverapp:id/$suffix"

    private fun text(value: String, idSuffix: String? = null, clickable: Boolean = false) = UiNode(
        text = value,
        viewIdResourceName = idSuffix?.let { id(it) },
        className = "android.widget.TextView",
        isClickable = clickable,
    )

    private fun box(vararg children: UiNode, idSuffix: String? = null) =
        UiNode(className = "android.view.View", viewIdResourceName = idSuffix?.let { id(it) }, children = children.toList())

    /** The window frame the fielded sheet renders under, `Close sheet` beside the body. */
    private fun frame(vararg body: UiNode, closeSheet: Boolean = true): UiNode = UiNode(
        className = "android.widget.FrameLayout",
        children = listOf(
            UiNode(
                viewIdResourceName = "android:id/content",
                className = "android.widget.FrameLayout",
                children = listOfNotNull(
                    if (closeSheet) UiNode(contentDescription = "Close sheet", className = "android.view.View", isClickable = true) else null,
                    box(*body),
                ),
            ),
        ),
    )

    private fun scrub(tree: UiNode): UiNode = CustomerTextMarkers.scrubUnknown(tree, UnknownAddressBackstop.select(tree))

    private fun serialize(tree: UiNode) = UiNodeSchema.serialize(tree)

    private fun texts(tree: UiNode): List<String?> = buildList {
        fun walk(n: UiNode) {
            add(n.text)
            n.children.forEach { walk(it) }
        }
        walk(tree)
    }

    private fun textOf(tree: UiNode, original: String, source: UiNode): String? {
        // Same preorder position in the scrubbed copy as [original] in [source].
        val index = texts(source).indexOf(original)
        assertTrue("test setup: '$original' must be in the source tree", index >= 0)
        return texts(tree)[index]
    }

    private fun assertMasked(source: UiNode, out: UiNode, vararg values: String) {
        for (v in values) assertEquals("'$v' must plain-mask exactly", "[redacted]", textOf(out, v, source))
    }

    private fun assertKept(source: UiNode, out: UiNode, vararg values: String) {
        for (v in values) assertEquals("'$v' is chrome and must survive", v, textOf(out, v, source))
    }

    private val street = "1234 Sample Ridge Dr"
    private val city = "San Antonio, TX 78200"
    private val note = "\"Leave it on the sample porch\""

    /** The 8.99.20 nested render: address pair, option row, note and code in sibling branches. */
    private fun nestedSheet(vararg extra: UiNode) = frame(
        text("Deliver to Avery K"),
        box(
            box(box(text(street), text(city), box())),
            box(box(text("Leave it at my door"), box()), box(box()), box(), text(note)),
            box(box(text("000"))),
            *extra,
        ),
    )

    @Test
    fun `the nested fielded render masks street, city, note and code and keeps chrome`() {
        val tree = nestedSheet()
        val out = scrub(tree)
        assertMasked(tree, out, street, city, note, "000")
        assertKept(tree, out, "Leave it at my door")
        assertTrue("Close sheet survives", serialize(out).contains("Close sheet"))
        assertEquals("the task line is the prefix backstop's", "[redacted]", textOf(out, "Deliver to Avery K", tree))
    }

    @Test
    fun `the flat render masks the block inside the shared container`() {
        val tree = frame(text("Deliver to Avery K"), text(street), text(city), text("Leave it at my door"), text(note), text("000"))
        val out = scrub(tree)
        assertMasked(tree, out, street, city, note, "000")
        assertKept(tree, out, "Leave it at my door")
    }

    @Test
    fun `the July three-branch content block masks the code and note in sibling branches`() {
        val tree = frame(
            text("Pickup for Avery K"),
            box(
                box(text(street), text(city)),
                box(text("Hand it to me"), text("\"Gate 4417, ring twice\"")),
                box(text("4417")),
            ),
        )
        val out = scrub(tree)
        assertMasked(tree, out, street, city, "\"Gate 4417, ring twice\"", "4417")
        assertKept(tree, out, "Hand it to me")
    }

    @Test
    fun `detection ignores ids, headers, clickability, Pick up by and a missing Close sheet`() {
        val variants = mapOf(
            "missing task header" to frame(box(text(street, "row_street"), text(city, "unrelated_label")), text(note)),
            "unrelated ids" to nestedSheet(box(idSuffix = "some_new_container"), text("Order includes", "lblOrderIncludes")),
            "pick-up-by line" to nestedSheet(text("Pick up by 12:28 PM")),
            "no Close sheet" to frame(box(box(text(street), text(city)), text("000")), closeSheet = false),
            "clickable rows" to frame(box(text(street, clickable = true), text(city, clickable = true)), text("000", clickable = true)),
        )
        for ((what, tree) in variants) {
            val out = scrub(tree)
            assertEquals("$what: street", "[redacted]", textOf(out, street, tree))
            assertEquals("$what: city", "[redacted]", textOf(out, city, tree))
            assertFalse("$what: no raw ZIP", serialize(out).contains("78200"))
            assertFalse("$what: no raw street", serialize(out).contains("Sample Ridge"))
        }
        val withPickUpBy = nestedSheet(text("Pick up by 12:28 PM"))
        assertKept(withPickUpBy, scrub(withPickUpBy), "Pick up by 12:28 PM", "Leave it at my door")
    }

    @Test
    fun `ids, classes, flags and structure are preserved on a masked node`() {
        val tree = frame(box(text(street, "row_street", clickable = true), text(city)), text("Leave it at my door"))
        val out = scrub(tree)
        val src = serialize(tree)
        val masked = serialize(out)
        assertTrue("the id survives", masked.contains("row_street"))
        assertEquals(
            "only the text changed",
            src.replace(street, "[redacted]").replace(city, "[redacted]"),
            masked,
        )
    }

    @Test
    fun `every scrubbable field of a selected node masks, nulls and empties stay`() {
        val streetNode = UiNode(
            text = street,
            contentDescription = "Street",
            stateDescription = "s",
            paneTitle = "p",
            roleDescription = "Button",
            hintText = "h",
            tooltipText = "t",
            errorText = "e",
            clickActionLabel = "Open",
            uniqueId = "u1",
        )
        val emptyFields = UiNode(text = city, contentDescription = "", stateDescription = null)
        val tree = frame(box(streetNode, emptyFields))
        val out = scrub(tree)
        val outStreet = out.children[0].children[1].children[0].children[0]
        for ((field, value) in outStreet.scrubbableStrings()) assertEquals("$field", "[redacted]", value)
        val outCity = out.children[0].children[1].children[0].children[1]
        assertEquals("[redacted]", outCity.text)
        assertEquals("an empty field stays empty", "", outCity.contentDescription)
        assertEquals("a null field stays null", null, outCity.stateDescription)
    }

    @Test
    fun `a pair carried only in a non-text field is detected`() {
        val tree = frame(box(UiNode(contentDescription = street), UiNode(hintText = city)), text("000"))
        val out = scrub(tree)
        val block = out.children[0].children[1].children[0]
        assertEquals("[redacted]", block.children[0].contentDescription)
        assertEquals("[redacted]", block.children[1].hintText)
    }

    @Test
    fun `street and city lines inside one field are a pair`() {
        val joined = "$street\n$city"
        val tree = frame(box(text("Delivery details"), text(joined)), text("000"))
        val out = scrub(tree)
        assertMasked(tree, out, joined)
        assertKept(tree, out, "Delivery details")
    }

    @Test
    fun `quotes and short codes - every form the review named`() {
        for (secret in listOf("\"000\"", "“Gate 4417", "\"4417\"", "000", "4417", "123456", "\"")) {
            val tree = frame(text("Header"), text(street), text(city), text("Leave it at my door"), text(secret))
            assertMasked(tree, scrub(tree), secret)
        }
        val seven = frame(text("Header"), text(street), text(city), text("1234567"))
        assertKept(seven, scrub(seven), "1234567")
    }

    @Test
    fun `a quote or code immediately above a city line is the weak cluster`() {
        for (secret in listOf("4417", "\"4417\"")) {
            val tree = frame(text("Order details"), text(secret), text(city), text("Leave it at my door"))
            val out = scrub(tree)
            assertMasked(tree, out, secret, city)
            assertKept(tree, out, "Order details", "Leave it at my door")
        }
    }

    @Test
    fun `quoted notes and codes may intervene, any other text breaks the pair`() {
        val intervened = frame(text("Header"), text(street), text(note), text("000"), text(city))
        assertMasked(intervened, scrub(intervened), street, note, "000", city)

        val broken = frame(text("Header"), text(street), text("Pick up by 12:28 PM"), text(city))
        val out = scrub(broken)
        assertKept(broken, out, street, "Pick up by 12:28 PM", city)
    }

    @Test
    fun `a mask token inside or at the end of a raw note does not exempt it`() {
        for (raw in listOf("\"Leave by [redacted]", "\"ring [redacted:ab12]", "\"[redacted] gate 4417\"")) {
            val tree = frame(text("Header"), text(street), text(city), text(raw))
            assertMasked(tree, scrub(tree), raw)
        }
    }

    @Test
    fun `multiple blocks mask independently and an unrelated container between them is byte-identical`() {
        val unrelated = box(text("Earnings"), text("12"), text("\"Top Dasher\" status"))
        val tree = frame(
            box(text("Stop 1"), text(street), text(city), text("000")),
            unrelated,
            box(text("Stop 2"), text("55 Other Lane"), text("Austin, TX 78701-1234"), text("\"Side door\"")),
        )
        val out = scrub(tree)
        assertMasked(tree, out, street, city, "000", "55 Other Lane", "Austin, TX 78701-1234", "\"Side door\"")
        assertKept(tree, out, "Stop 1", "Stop 2")
        val body = { t: UiNode -> t.children[0].children[1].children[1] }
        assertEquals("unrelated container untouched", serialize(body(tree)), serialize(body(out)))
    }

    @Test
    fun `ordinary chrome without an address pair is untouched`() {
        val tree = frame(
            text("Leave it at my door"), text("Hand it to me"), text("Copy address"), text("15 min Drive"),
            text("000"), text("\"Promo\""), text("Pick up by 12:28 PM"),
        )
        assertTrue(UnknownAddressBackstop.select(tree).isEmpty())
        val cityOnly = frame(text("Header"), text(city), text("Leave it at my door"))
        assertTrue("a lone city line is not claimed", UnknownAddressBackstop.select(cityOnly).isEmpty())
        val reversed = frame(text("Header"), text(city), text(street))
        assertTrue("city ahead of street is not claimed", UnknownAddressBackstop.select(reversed).isEmpty())
    }

    @Test
    fun `the scrub is idempotent and never mutates the source tree`() {
        val tree = nestedSheet()
        val before = serialize(tree)
        val once = scrub(tree)
        assertEquals("source tree unchanged", before, serialize(tree))
        assertEquals("idempotent", serialize(once), serialize(scrub(once)))
    }

    @Test
    fun `selection is by position, never structural equality`() {
        // Two structurally equal "000" nodes: one inside the block, one in an unrelated container.
        val tree = frame(
            box(text("Header"), text(street), text(city), text("000")),
            box(text("Badge"), text("Count"), text("000")),
        )
        val out = scrub(tree)
        val body = out.children[0].children[1]
        assertEquals("[redacted]", body.children[0].children[3].text)
        assertEquals("000", body.children[1].children[2].text)
    }

    private fun nodeCount(tree: UiNode): Int = 1 + tree.children.sumOf { nodeCount(it) }

    private fun assertLinear(what: String, tree: UiNode): UnknownAddressBackstop.Selection {
        val selection = UnknownAddressBackstop.select(tree)
        val n = nodeCount(tree)
        assertTrue("$what: ${selection.steps} visits for $n nodes", selection.steps <= 6L * n)
        return selection
    }

    @Test
    fun `a maximum-size tree with nested overlapping scopes is visited linearly`() {
        // 60 nested levels (the mapper's depth cap), each a block (street, city, code) plus a container to the
        // next level, and wide filler to the 4,000-node cap — every scope overlaps every deeper one. Counted
        // node visits, never a stopwatch (#947).
        fun level(depth: Int): UiNode {
            val inner = if (depth == 0) text("Bottom") else level(depth - 1)
            return box(text("$depth Main St"), text(city), text("$depth"), inner)
        }
        val filler = (0 until 3_700).map { text("Row $it") }
        val tree = UiNode(children = listOf(level(59), box(*filler.toTypedArray())))
        val selection = assertLinear("nested scopes", tree)
        assertEquals("60 streets + 60 cities + 60 codes", 180, selection.count)
        val out = serialize(scrub(tree))
        assertFalse(out.contains("Main St"))
        assertTrue(out.contains("Row 3699") && out.contains("Bottom"))
    }

    @Test
    fun `a flat list of many pairs and a long wrapper chain are visited linearly`() {
        val pairs = (0 until 1_300).flatMap { listOf(text("$it Main St"), text(city), text("$it")) }
        val flat = UiNode(children = pairs)
        assertEquals(3_900, assertLinear("many pairs in one list", flat).count)

        var chain: UiNode = box(text(street), text(city))
        repeat(1_000) { chain = box(chain) }
        assertEquals(2, assertLinear("wrapper chain", chain).count)
    }
}
