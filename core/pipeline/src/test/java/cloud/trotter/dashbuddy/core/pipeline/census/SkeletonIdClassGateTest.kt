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

/** ADR-0011 §1 — the id and class gates: static shapes, the id-path PII judgement, and identity containment on ids/classes (camelCase segments, whole values). Split from `SkeletonBuilderTest` (#1160 review UU10). */
class SkeletonIdClassGateTest : SkeletonBuilderTestBase() {

    @Test
    fun `AA10 - a dynamic test-tag id is absent on the wire and in the fingerprint`() {
        fun frame(tag: String) = UiNode(
            className = "android.widget.FrameLayout",
            viewIdResourceName = "com.doordash.driverapp:id/drop_off_step_instructions_activity_host_fragment",
            children = listOf(UiNode(className = "android.widget.Button", viewIdResourceName = tag, text = "Continue")),
        ).restoreParents()
        val a = SkeletonBuilder.build(frame("PRIMARY_BUTTON_3f488d4a-0f0b-4fb9-9c86-c4e0253ba22a"), null, meta, platform, day)!!
        val b = SkeletonBuilder.build(frame("PRIMARY_BUTTON_9db4e2af-5a58-4a43-ba63-295126ceddef"), null, meta, platform, day)!!
        assertNull(a.root.children.single().id)
        assertEquals(a.fingerprint, b.fingerprint)
        assertEquals("com.doordash.driverapp:id/drop_off_step_instructions_activity_host_fragment", a.root.id)
        // The §2 PII-id step still sees the RAW id: a dynamic id ending in a PII suffix withholds.
        assertEquals(TextSlot.WITHHELD, slot("Sam", "x:id/row_1234_customer_name"))
    }

    @Test
    fun `CC1 - a non-static class name is absent on the wire and in the fingerprint`() {
        fun frame(cls: String) = UiNode(
            className = "android.widget.FrameLayout",
            viewIdResourceName = "com.x:id/host",
            children = listOf(UiNode(className = cls, text = "Continue")),
        )
        val dynamic = SkeletonBuilder.build(frame("Composable_3f488d4a"), null, meta, platform, day)!!
        val free = SkeletonBuilder.build(frame("Jane Smith's button"), null, meta, platform, day)!!
        assertNull(dynamic.root.children.single().className)
        assertNull(free.root.children.single().className)
        assertEquals(dynamic.fingerprint, free.fingerprint)
        val static = SkeletonBuilder.build(frame("androidx.compose.ui.platform.ComposeView"), null, meta, platform, day)!!
        assertEquals("androidx.compose.ui.platform.ComposeView", static.root.children.single().className)
    }

    @Test
    fun `II3 - a name-bearing test-tag id is absent, chrome ids travel`() {
        listOf("row_Deliver_to_Sam", "com.x:id/chip_Adam_S", "Pickup_for_Sam")
            .forEach { assertTrue(it, !SkeletonBuilder.isStaticId(it)) }
        listOf("bc25_fab", "a11y_clock", "Artwork Image", "com.doordash.driverapp:id/customer_name", "Tooltip-0")
            .forEach { assertTrue(it, SkeletonBuilder.isStaticId(it)) }
        val item = SkeletonBuilder.build(
            UiNode(className = "android.widget.Button", viewIdResourceName = "row_Deliver_to_Sam", text = "Go"),
            null, meta, platform, day,
        )!!
        assertNull(item.root.id)
    }

    @Test
    fun `JJ1 - an id carrying the frame's identity run is absent for the wire and the fingerprint`() {
        fun frame(tag: String?) = UiNode(
            className = "android.widget.LinearLayout",
            viewIdResourceName = "com.x:id/row",
            children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.x:id/customer_name", text = "Adam"),
                UiNode(className = "android.widget.Button", viewIdResourceName = tag, text = "Continue"),
            ),
        )
        val adam = SkeletonBuilder.build(frame("com.x:id/chip_Adam"), null, meta, platform, day)!!
        assertNull(adam.root.children[1].id)
        val gold = SkeletonBuilder.build(frame("com.x:id/chip_Gold"), null, meta, platform, day)!!
        assertEquals("com.x:id/chip_Gold", gold.root.children[1].id)
        val none = SkeletonBuilder.build(frame(null), null, meta, platform, day)!!
        assertEquals(none.fingerprint, adam.fingerprint)
        // Without an identity id on the frame, the same tag travels (ADR residual risk 10).
        val alone = SkeletonBuilder.build(
            UiNode(className = "android.widget.Button", viewIdResourceName = "com.x:id/chip_Adam", text = "Continue"),
            null, meta, platform, day,
        )!!
        assertEquals("com.x:id/chip_Adam", alone.root.id)
    }

    @Test
    fun `KK1 - ids split at camelCase boundaries, text slots do not`() {
        fun tagOf(tag: String): String? = SkeletonBuilder.build(
            UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row", children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.x:id/customer_name", text = "Adam"),
                UiNode(className = "android.widget.Button", viewIdResourceName = "com.x:id/$tag", text = "Continue"),
            )),
            null, meta, platform, day,
        )!!.root.children[1].id
        assertNull(tagOf("chipAdam"))
        assertNull(tagOf("XMLAdamRow"))
        assertNull(tagOf("chip_adam"))
        assertEquals("com.x:id/chipAdamant", tagOf("chipAdamant"))
        assertEquals("com.x:id/chipGold", tagOf("chipGold"))
        // A text slot keeps the plain letter-run split: "chipAdam" is one run, not the identity run.
        assertEquals(listOf(words(1, "chipAdam")), beside("Adam", "chipAdam"))
    }

    @Test
    fun `MM1 - a name with internal capitals matches across contiguous camel segments`() {
        fun node(id: String?, cls: String = "android.widget.Button") =
            UiNode(className = cls, viewIdResourceName = id, text = "Continue")
        val out = SkeletonBuilder.build(
            UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row", children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.x:id/customer_name", text = "McKenna"),
                node("com.x:id/chip_McKenna"),
                node("com.x:id/chipMcKenna"),
                node(null, cls = "com.x.McKennaButton"),
                node("com.x:id/chipMcGold"),
                UiNode(className = "android.widget.TextView", text = "McKenna's order"),
            )),
            null, meta, platform, day,
        )!!.root.children
        assertNull(out[1].id)
        assertNull(out[2].id)
        // Review ZZ3: a class name is never containment-checked — a compiled type name is not frame data.
        assertEquals("com.x.McKennaButton", out[3].className)
        assertEquals("com.x:id/chipMcGold", out[4].id)
        assertEquals(TextSlot.WITHHELD, out[5].text.getValue("text"))
    }

    @Test
    fun `NN6 - an over-long id is refused before the shape regex`() {
        assertTrue(!SkeletonBuilder.isStaticId("com.x:id/" + "a".repeat(10_000)))
    }

    @Test
    fun `PP1 PP4 - camelCase ids are judged, single-letter chrome ids are not names`() {
        listOf("deliverToSam", "pickupForSam", "chipAdamS", "com.x:id/chip_Adam_S").forEach {
            assertTrue(it, !SkeletonBuilder.isStaticId(it))
        }
        listOf("deliverButton", "pickupHeader", "option_a", "tab_b", "icon_x", "plan_b", "roadNameLayout").forEach {
            assertTrue(it, SkeletonBuilder.isStaticId(it))
        }
    }

    @Test
    fun `SS3 - the id path withholds only name-like lead-in tails and capitalized name shapes`() {
        listOf("deliver_to_Sam", "deliverToSam", "pickupForSam", "chip_Adam_S", "chipAdamS", "com.x:id/row_Deliver_to_Sam")
            .forEach { assertTrue(it, !SkeletonBuilder.isStaticId(it)) }
        listOf("deliver_to_label", "order_for_header", "pickup_for_title", "tabB", "optionA", "tab_B", "option_a", "icon_x")
            .forEach { assertTrue(it, SkeletonBuilder.isStaticId(it)) }
    }

    @Test
    fun `TT2 ZZ3 - a name-capable whole value protects a sibling id, an ADDRESS never, a text slot never`() {
        fun ids(identityId: String, identity: String, vararg tags: String) = SkeletonBuilder.build(
            UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row", children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/$identityId", text = identity),
            ) + tags.map { UiNode(className = "android.widget.ImageView", viewIdResourceName = "com.x:id/$it") }),
            null, meta, platform, day,
        )!!.root.children.drop(1).map { it.id }
        assertEquals(listOf(null, "com.x:id/chipGold"), ids("user_name", "Riley", "chipRiley", "chipGold"))
        // Review ZZ3: a PERSON_OR_MERCHANT whole value is never shape-gated — a merchant's logo id carrying
        // its whole name is absent (the accepted recall cost); a chrome id sharing one word travels.
        assertEquals(listOf(null, "com.x:id/boxView"), ids("user_name", "Jack in the Box", "jackInTheBoxLogo", "boxView"))
        assertEquals(listOf("com.x:id/roadNameLayout"), ids("address_line_1", "10927 Culebra Road", "roadNameLayout"))
        // A text slot is untouched by the whole-value rule: "Call Riley" beside user_name "Riley" hashes.
        assertEquals(listOf(words(2, "Call Riley")), beside2("user_name", "Riley", "Call Riley"))
    }

    @Test
    fun `UU5 WW6 - the capital predicate is code-point based, and a non-ASCII id fails the grammar first`() {
        // The predicate itself (review WW6: the id grammar is ASCII, so it must be tested directly).
        assertTrue(SkeletonBuilder.isCapitalAt("to \uD801\uDC08dam", 3))
        assertTrue(!SkeletonBuilder.isCapitalAt("to adam", 3))
        // Relabelled grammar case: a supplementary-plane letter is not in the static id grammar at all.
        assertTrue(!SkeletonBuilder.isStaticId("deliver_to_\uD801\uDC08dam"))
        assertTrue(SkeletonBuilder.isStaticId("deliver_to_label"))
    }

    @Test
    fun `VV1 ZZ3 - every EXACT value protects an id by its whole value, an ADDRESS none`() {
        fun ids(identityId: String, identity: String, vararg tags: String) = SkeletonBuilder.build(
            UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row", children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/$identityId", text = identity),
            ) + tags.map { UiNode(className = "android.widget.ImageView", viewIdResourceName = "com.x:id/$it") }),
            null, meta, platform, day,
        )!!.root.children.drop(1).map { it.id }
        // ZZ3: fail closed — a chrome title under `tvTitle` nulls an id built from it on its frame.
        assertEquals(listOf(null), ids("tvTitle", "Order Details", "orderDetailsHeader"))
        assertEquals(listOf(null), ids("user_name", "Riley", "chipRiley"))
        assertEquals(listOf(null), ids("user_name", "Riley S", "chipRileyS"))
        assertEquals(listOf("com.x:id/mainStreetLabel"), ids("address_line_1", "Main St", "mainStreetLabel"))
    }

    @Test
    fun `XX3 ZZ3 - NAME, PERSON_OR_MERCHANT and EXACT protect ids by their whole value, single tokens included`() {
        fun nodes(identityId: String, identity: String, vararg tags: String) = SkeletonBuilder.build(
            UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row", children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/$identityId", text = identity),
            ) + tags.map { UiNode(className = "android.widget.SearchView", viewIdResourceName = "com.x:id/$it") }),
            null, meta, platform, day,
        )!!.root.children.drop(1)
        fun ids(identityId: String, identity: String, vararg tags: String) = nodes(identityId, identity, *tags).map { it.id }
        // EXACT: a one-word chrome title nulls an id built from it; the class never moves.
        val search = nodes("tvTitle", "Search", "search_bar")
        assertEquals(listOf<String?>(null), search.map { it.id })
        assertEquals(listOf<String?>("android.widget.SearchView"), search.map { it.className })
        assertEquals(listOf(null), ids("tvTitle", "Riley", "chipRiley"))
        assertEquals(listOf(null), ids("tvTitle", "Riley Smith", "chipRileySmith"))
        assertEquals(listOf(null), ids("tvTitle", "Riley S", "chipRileyS"))
        // PERSON_OR_MERCHANT: a single token included; a merchant's whole name too.
        assertEquals(listOf(null), ids("user_name", "Riley", "chipRiley"))
        assertEquals(listOf(null), ids("user_name", "Jack in the Box", "jackInTheBoxLogo"))
        // NAME: the whole value joins across tokens ("Mary Jo" → `chipMaryJo`).
        assertEquals(listOf(null), ids("customer_name", "Mary Jo", "chipMaryJo"))
        // ADDRESS never adds a whole-value run.
        assertEquals(listOf("com.x:id/mainStreetLabel"), ids("address_line_1", "Main Street", "mainStreetLabel"))
    }

    @Test
    fun `ZZ4 - an all-caps tag ending in one letter reads as a name - the accepted recall cost`() {
        listOf("TAB_B", "SECTION_C", "PRIMARY_BUTTON_A", "chip_RILEY_S", "com.x:id/chip_RILEY_S")
            .forEach { assertTrue(it, !SkeletonBuilder.isStaticId(it)) }
        assertTrue(!SkeletonBuilder.isStaticId("chip_Adam_S"))
        // A lowercase-led tag stays chrome.
        listOf("tab_B", "option_a", "tabB").forEach { assertTrue(it, SkeletonBuilder.isStaticId(it)) }
    }
}
