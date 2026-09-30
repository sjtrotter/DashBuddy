package cloud.trotter.dashbuddy.core.pipeline.census
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder.FilterStep
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder.Outcome
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder.Refusal
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.census.contract.CensusFingerprint
import cloud.trotter.dashbuddy.domain.census.contract.CensusHash
import cloud.trotter.dashbuddy.domain.census.contract.SkeletonSchema
import cloud.trotter.dashbuddy.domain.census.contract.TextSlot
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNodeTextField
import cloud.trotter.dashbuddy.domain.state.Platform
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0011 §2 — the FRAME-LEVEL duplicate / containment rule: exact seeds, NAME runs, identity kinds (NAME / ADDRESS / EXACT / CONTENT), case folding, and the memoized per-frame filter. Split from `SkeletonBuilderTest` (#1160 review UU10). */
class SkeletonFrameRuleTest : SkeletonBuilderTestBase() {

    @Test
    fun `AA1 - a value withheld anywhere in the frame is withheld everywhere in it`() {
        // The codex fixture: the parent repeats the child's customer name, and only the child's id marks it.
        val frame = UiNode(
            className = "android.widget.LinearLayout",
            viewIdResourceName = "com.doordash.driverapp:id/row",
            contentDescription = "Sam",
            children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/customer_name", text = "Sam"),
                UiNode(className = "android.widget.TextView", text = "Sam "),
                UiNode(className = "android.widget.TextView", text = "Accept"),
            ),
        ).restoreParents()
        // In isolation "Sam" is words:1 and would hash.
        assertEquals(words(1, "Sam"), slot("Sam"))
        val item = SkeletonBuilder.build(frame, "Sam", meta, platform, day)!!
        assertEquals(TextSlot.WITHHELD, item.root.text.getValue("desc"))
        assertEquals(TextSlot.WITHHELD, item.root.children[0].text.getValue("text"))
        assertEquals(TextSlot.WITHHELD, item.root.children[1].text.getValue("text"))
        assertEquals(TextSlot.WITHHELD, item.windowTitle)
        assertEquals(words(1, "Accept"), item.root.children[2].text.getValue("text"))
    }

    @Test
    fun `CC3 - an intake-only PII id withholds its own field but does not seed the frame`() {
        val frame = UiNode(
            className = "android.widget.LinearLayout",
            viewIdResourceName = "com.doordash.driverapp:id/sheet",
            children = listOf(
                UiNode(className = "android.widget.TextView", text = "Hand it to me"),
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/step_description", text = "Hand it to me"),
            ),
        )
        val item = SkeletonBuilder.build(frame, null, meta, platform, day)!!
        assertEquals(words(4, "Hand it to me"), item.root.children[0].text.getValue("text"))
        assertEquals(TextSlot.WITHHELD, item.root.children[1].text.getValue("text"))
        // ...while an ID_MARKERS id (value IS PII) still propagates frame-wide (the AA1 fixture shape).
        val pii = UiNode(
            className = "android.widget.LinearLayout",
            contentDescription = "Sam",
            children = listOf(UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/customer_name", text = "Sam")),
        )
        assertEquals(TextSlot.WITHHELD, SkeletonBuilder.build(pii, null, meta, platform, day)!!.root.text.getValue("desc"))
    }

    @Test
    fun `DD2 - the value filter runs ONCE per distinct trimmed value across both passes`() {
        val counts = HashMap<String, Int>()
        val filter = SkeletonBuilder.FrameFilter(judge = { v ->
            counts.merge(v, 1, Int::plus)
            SkeletonBuilder.withholdingStep(v)
        })
        val tree = UiNode(
            className = "android.widget.LinearLayout",
            children = listOf(
                UiNode(className = "android.widget.TextView", text = "Accept", contentDescription = " Accept "),
                UiNode(className = "android.widget.TextView", text = "Accept", hintText = "Jane S"),
                UiNode(className = "android.widget.TextView", text = "Jane S"),
            ),
        )
        val pending = filter.scan(tree)
        val title = filter.field("Accept", SkeletonBuilder.IdClass.NONE)!!
        val root = filter.emit(pending)
        filter.slot(title)
        assertEquals(words(1, "Accept"), root.children[0].text.getValue("text"))
        assertEquals(TextSlot.WITHHELD, root.children[2].text.getValue("text"))
        assertEquals(mapOf("Accept" to 1, "Jane S" to 1), counts)
    }

    @Test
    fun `EE1 - a CONTENT id does not seed the frame, and an identity id seeds only from text and desc`() {
        val content = UiNode(
            className = "android.widget.LinearLayout",
            viewIdResourceName = "com.doordash.driverapp:id/sheet",
            children = listOf(
                UiNode(className = "android.widget.TextView", text = "Required"),
                UiNode(className = "android.widget.TextView", text = "Raise to 50%"),
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/description_text_view", text = "Required"),
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.x:id/description_text_view", text = "Raise to 50%"),
            ),
        )
        val item = SkeletonBuilder.build(content, null, meta, platform, day)!!
        assertEquals(words(1, "Required"), item.root.children[0].text.getValue("text"))
        assertEquals(TextSlot(kind = "mixed"), item.root.children[1].text.getValue("text"))
        assertEquals(TextSlot.WITHHELD, item.root.children[2].text.getValue("text"))
        assertEquals(TextSlot.WITHHELD, item.root.children[3].text.getValue("text"))

        val role = UiNode(
            className = "android.widget.LinearLayout",
            viewIdResourceName = "com.x:id/row",
            children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/customer_name", text = "Sam", roleDescription = "Button"),
                UiNode(className = "android.widget.Button", text = "Button", contentDescription = "Sam"),
            ),
        )
        val r = SkeletonBuilder.build(role, null, meta, platform, day)!!
        // The identity id's own fields are all withheld (step 1)...
        assertEquals(TextSlot.WITHHELD, r.root.children[0].text.getValue("role"))
        // ...but only its TEXT seeds the frame: "Sam" propagates, the role's "Button" does not.
        assertEquals(TextSlot.WITHHELD, r.root.children[1].text.getValue("desc"))
        assertEquals(words(1, "Button"), r.root.children[1].text.getValue("text"))
    }

    @Test
    fun `GG1 - an identity value embedded in an id-less node is withheld by token containment`() {
        fun frame(vararg others: String) = UiNode(
            className = "android.widget.LinearLayout",
            viewIdResourceName = "com.x:id/row",
            children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/customer_name", text = "Adam"),
            ) + others.map { UiNode(className = "android.widget.TextView", text = it) },
        )
        val item = SkeletonBuilder.build(frame("Adam's order", "Adam, 2 items", "ADAM", "Add a tip", "Adamant"), null, meta, platform, day)!!
        val slots = item.root.children.drop(1).map { it.text.getValue("text") }
        assertEquals(TextSlot.WITHHELD, slots[0])
        assertEquals(TextSlot.WITHHELD, slots[1])
        assertEquals(TextSlot.WITHHELD, slots[2])
        assertEquals(words(3, "Add a tip"), slots[3])
        // A different run (a longer word) is not the identity token.
        assertEquals(words(1, "Adamant"), slots[4])
        // Review II1: a two-letter identity value seeds its run too ("Jo's pick" is withheld).
        val jo = SkeletonBuilder.build(
            UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row", children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.x:id/customer_name", text = "Jo"),
                UiNode(className = "android.widget.TextView", text = "Jo's pick"),
                UiNode(className = "android.widget.TextView", text = "Jo"),
            )),
            null, meta, platform, day,
        )!!
        assertEquals(TextSlot.WITHHELD, jo.root.children[1].text.getValue("text"))
        assertEquals(TextSlot.WITHHELD, jo.root.children[2].text.getValue("text"))
    }

    @Test
    fun `HH1 - identity containment uses one Unicode case fold`() {
        assertEquals(listOf(TextSlot.WITHHELD), beside("ΝΙΚΟΣ", "νικος's order"))
        assertEquals(listOf(TextSlot.WITHHELD), beside("Groß", "GROSS's order"))
    }

    @Test
    fun `HH2 - the seed threshold counts letter code points`() {
        // ONE supplementary-plane letter: two UTF-16 units, but one letter — seeds no run (II1: the
        // threshold is two letters).
        val one = "\uD801\uDC00"
        assertEquals(listOf(words(2, "$one pick"), TextSlot.WITHHELD), beside(one, "$one pick", one))
        // Two such letters (four units) do seed.
        val pair = one + "\uD801\uDC01"
        assertEquals(listOf(TextSlot.WITHHELD), beside(pair, "$pair pick"))
    }

    @Test
    fun `HH4 - identity-dependent chrome suppression - a common first name withholds same-word chrome`() {
        // The documented cost of GG1's containment rule (ADR §7 / residual risk 9).
        assertEquals(listOf(TextSlot.WITHHELD), beside("May", "May need returns"))
        assertEquals(listOf(words(3, "May need returns")), beside("Sam", "May need returns"))
    }

    @Test
    fun `II1 - a two-letter first name is covered, a single letter seeds nothing`() {
        assertEquals(listOf(TextSlot.WITHHELD), beside("Li", "Li's order"))
        // Whole-run equality: "li" does not match inside another word.
        assertEquals(listOf(words(2, "Limited offer")), beside("Li", "Limited offer"))
        assertEquals(listOf(words(2, "A tip")), beside("A", "A tip"))
    }

    @Test
    fun `LL1 - an ADDRESS seeds its exact value only, a NAME its runs too, a mask nothing`() {
        fun frame(identityId: String, identity: String, vararg nodes: UiNode) = UiNode(
            className = "android.widget.LinearLayout",
            viewIdResourceName = "com.x:id/sheet",
            children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.x:id/$identityId", text = identity),
            ) + nodes,
        )
        val address = SkeletonBuilder.build(
            frame(
                "address_line_1", "Bay View Commons",
                UiNode(className = "android.widget.TextView", text = "View details"),
                UiNode(className = "android.widget.TextView", text = "Bay View Commons"),
                UiNode(className = "android.widget.ImageView", viewIdResourceName = "com.x:id/roadNameLayout"),
                UiNode(className = "android.widget.ImageView", viewIdResourceName = "com.x:id/bayViewIcon"),
            ),
            null, meta, platform, day,
        )!!.root.children
        assertEquals(words(2, "View details"), address[1].text.getValue("text"))
        // AA1's exact-duplicate rule still holds for an address.
        assertEquals(TextSlot.WITHHELD, address[2].text.getValue("text"))
        assertEquals("com.x:id/roadNameLayout", address[3].id)
        assertEquals("com.x:id/bayViewIcon", address[4].id)

        // A mask seeds nothing — neither its exact value's words nor ids carrying "redacted"/"address".
        val masked = SkeletonBuilder.build(
            frame(
                "customer_name", "[redacted:ab12]",
                UiNode(className = "android.widget.TextView", text = "Redacted items"),
                UiNode(className = "android.widget.ImageView", viewIdResourceName = "com.x:id/redactedBadge"),
            ),
            null, meta, platform, day,
        )!!.root.children
        assertEquals(words(2, "Redacted items"), masked[1].text.getValue("text"))
        assertEquals("com.x:id/redactedBadge", masked[2].id)
        val addressMask = SkeletonBuilder.build(
            frame(
                "address_line_1", "[address]",
                UiNode(className = "android.widget.ImageView", viewIdResourceName = "com.x:id/addressIcon"),
            ),
            null, meta, platform, day,
        )!!.root.children
        assertEquals("com.x:id/addressIcon", addressMask[1].id)

        // A NAME still seeds runs: "Adam's order" and `chipAdam` beside `customer_name` "Adam".
        assertEquals(listOf(TextSlot.WITHHELD), beside("Adam", "Adam's order"))
    }

    @Test
    fun `MM2 - a capital sharp S matches its small form`() {
        assertEquals(listOf(TextSlot.WITHHELD), beside("Groß", "GRO\u1E9E"))
        assertEquals(listOf(TextSlot.WITHHELD), beside("GRO\u1E9E", "Groß"))
    }

    @Test
    fun `NN1 NN2 SS1 - order_cx_name seeds runs, user_name seeds its exact value only`() {
        fun frame(id: String) = UiNode(
            className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row", children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/$id", text = "Riley"),
                UiNode(className = "android.widget.TextView", text = "Riley"),
                UiNode(className = "android.widget.TextView", text = "Call Riley"),
            ),
        )
        val cx = SkeletonBuilder.build(frame("order_cx_name"), null, meta, platform, day)!!.root.children
        assertEquals(TextSlot.WITHHELD, cx[1].text.getValue("text"))
        assertEquals(TextSlot.WITHHELD, cx[2].text.getValue("text"))
        // SS1: user_name is EXACT — its exact duplicate is withheld, its words never seed.
        val user = SkeletonBuilder.build(frame("user_name"), null, meta, platform, day)!!.root.children
        assertEquals(TextSlot.WITHHELD, user[1].text.getValue("text"))
        assertEquals(words(2, "Call Riley"), user[2].text.getValue("text"))
    }

    @Test
    fun `NN3 PP2 - runs come from the text, or from the desc only when the text is blank`() {
        val out = SkeletonBuilder.build(
            UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row", children = listOf(
                UiNode(
                    className = "android.widget.TextView",
                    viewIdResourceName = "com.x:id/customer_name",
                    text = "Adam",
                    contentDescription = "Customer name Adam",
                ),
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.x:id/customer_name_label", text = "Customer"),
                UiNode(className = "android.widget.TextView", text = "Name"),
                UiNode(className = "android.widget.TextView", text = "Adam's order"),
            )),
            null, meta, platform, day,
        )!!.root.children
        assertEquals("com.x:id/customer_name", out[0].id)
        assertEquals("com.x:id/customer_name_label", out[1].id)
        assertEquals(words(1, "Customer"), out[1].text.getValue("text"))
        assertEquals(words(1, "Name"), out[2].text.getValue("text"))
        assertEquals(TextSlot.WITHHELD, out[3].text.getValue("text"))
        // Review PP2: a NAME rendered ONLY in its desc seeds runs from the desc, so it still propagates.
        val descOnly = SkeletonBuilder.build(
            UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row", children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.x:id/customer_name", contentDescription = "Adam"),
                UiNode(className = "android.widget.TextView", text = "Adam"),
                UiNode(className = "android.widget.TextView", text = "Adam's order"),
                UiNode(className = "android.widget.TextView", text = "Customer"),
            )),
            null, meta, platform, day,
        )!!.root.children
        assertEquals(TextSlot.WITHHELD, descOnly[1].text.getValue("text"))
        assertEquals(TextSlot.WITHHELD, descOnly[2].text.getValue("text"))
        assertEquals(words(1, "Customer"), descOnly[3].text.getValue("text"))
    }

    @Test
    fun `PP6 - an EXACT id seeds its exact value, never runs`() {
        fun frame(title: String, vararg others: String) = SkeletonBuilder.build(
            UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row", children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.x:id/tvTitle", text = title),
            ) + others.map { UiNode(className = "android.widget.ImageView", contentDescription = it) }),
            null, meta, platform, day,
        )!!.root.children
        val chat = frame("Adam", "Adam", "Adam's order")
        assertEquals(TextSlot.WITHHELD, chat[0].text.getValue("text"))
        assertEquals(TextSlot.WITHHELD, chat[1].text.getValue("desc"))
        // No runs: an embedding is not withheld by an EXACT seed (a NAME id would do that).
        assertEquals(words(2, "Adam's order"), chat[2].text.getValue("desc"))
        val sheet = frame("Pick up order", "Pick up order", "Pick up")
        assertEquals(TextSlot.WITHHELD, sheet[1].text.getValue("desc"))
        assertEquals(words(2, "Pick up"), sheet[2].text.getValue("desc"))
    }

    @Test
    fun `SS1 - a merchant name under user_name suppresses no chrome and no class`() {
        val out = SkeletonBuilder.build(
            UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row", children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/user_name", text = "Jack in the Box"),
                UiNode(className = "android.widget.TextView", text = "Head to the store"),
                UiNode(className = "android.widget.TextView", text = "Sign in"),
                UiNode(className = "android.view.View", viewIdResourceName = "com.x:id/boxView", text = "Total"),
            )),
            null, meta, platform, day,
        )!!.root.children
        assertEquals(words(4, "Head to the store"), out[1].text.getValue("text"))
        assertEquals(words(2, "Sign in"), out[2].text.getValue("text"))
        assertEquals("android.view.View", out[3].className)
        assertEquals("com.x:id/boxView", out[3].id)
    }

    @Test
    fun `UU6 - a masked NAME text falls through to its desc for runs`() {
        val out = SkeletonBuilder.build(
            UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row", children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.x:id/customer_name", text = "[redacted:ab12]", contentDescription = "Adam"),
                UiNode(className = "android.widget.TextView", text = "Adam's order"),
            )),
            null, meta, platform, day,
        )!!.root.children
        assertEquals(TextSlot.WITHHELD, out[1].text.getValue("text"))
    }
}
