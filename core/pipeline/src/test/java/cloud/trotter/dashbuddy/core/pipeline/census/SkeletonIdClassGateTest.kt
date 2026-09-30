package cloud.trotter.dashbuddy.core.pipeline.census
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder.FilterStep
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder.Outcome
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder.Refusal
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.census.contract.CensusFingerprint
import cloud.trotter.dashbuddy.domain.census.contract.CensusHash
import cloud.trotter.dashbuddy.domain.census.contract.SkeletonSchema
import cloud.trotter.dashbuddy.domain.census.contract.TextSlot
import cloud.trotter.dashbuddy.domain.model.accessibility.AnonymousWrappers
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
            .forEach { assertTrue(it, !IdPathJudgement.isStaticId(it)) }
        listOf("bc25_fab", "a11y_clock", "Artwork Image", "com.doordash.driverapp:id/customer_name", "Tooltip-0")
            .forEach { assertTrue(it, IdPathJudgement.isStaticId(it)) }
        val item = SkeletonBuilder.build(
            UiNode(className = "android.widget.Button", viewIdResourceName = "row_Deliver_to_Sam", text = "Go"),
            null, meta, platform, day,
        )!!
        // AH1: a PII-judged static-shaped id is the sentinel, never null.
        assertEquals("~", item.root.id)
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
        assertEquals("~", adam.root.children[1].id)
        val gold = SkeletonBuilder.build(frame("com.x:id/chip_Gold"), null, meta, platform, day)!!
        assertEquals("com.x:id/chip_Gold", gold.root.children[1].id)
        // AC3: the sentinel is customer-independent — two customers' colliding tags fingerprint alike.
        val beth = SkeletonBuilder.build(
            frame("com.x:id/chip_Beth").let { f ->
                f.copy(children = listOf(f.children[0].copy(text = "Beth"), f.children[1]))
            },
            null, meta, platform, day,
        )!!
        assertEquals("~", beth.root.children[1].id)
        assertEquals(beth.fingerprint, adam.fingerprint)
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
        assertEquals("~", tagOf("chipAdam"))
        assertEquals("~", tagOf("XMLAdamRow"))
        assertEquals("~", tagOf("chip_adam"))
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
        assertEquals("~", out[1].id)
        assertEquals("~", out[2].id)
        // Review AC2 (narrowing ZZ3): a third-party class carrying a NAME run is absent.
        assertNull(out[3].className)
        assertEquals("com.x:id/chipMcGold", out[4].id)
        assertEquals(TextSlot.WITHHELD, out[5].text.getValue("text"))
    }

    @Test
    fun `NN6 - an over-long id is refused before the shape regex`() {
        assertTrue(!IdPathJudgement.isStaticId("com.x:id/" + "a".repeat(10_000)))
    }

    @Test
    fun `PP1 PP4 - camelCase ids are judged, single-letter chrome ids are not names`() {
        listOf("deliverToSam", "pickupForSam", "chipAdamS", "com.x:id/chip_Adam_S").forEach {
            assertTrue(it, !IdPathJudgement.isStaticId(it))
        }
        listOf("deliverButton", "pickupHeader", "option_a", "tab_b", "icon_x", "plan_b", "roadNameLayout").forEach {
            assertTrue(it, IdPathJudgement.isStaticId(it))
        }
    }

    @Test
    fun `SS3 - the id path withholds only name-like lead-in tails and capitalized name shapes`() {
        listOf("deliver_to_Sam", "deliverToSam", "pickupForSam", "chip_Adam_S", "chipAdamS", "com.x:id/row_Deliver_to_Sam")
            .forEach { assertTrue(it, !IdPathJudgement.isStaticId(it)) }
        listOf("deliver_to_label", "order_for_header", "pickup_for_title", "tabB", "optionA", "tab_B", "option_a", "icon_x")
            .forEach { assertTrue(it, IdPathJudgement.isStaticId(it)) }
    }

    @Test
    fun `TT2 ZZ3 - a name-capable whole value protects a sibling id, an ADDRESS never, a text slot never`() {
        fun ids(identityId: String, identity: String, vararg tags: String) = SkeletonBuilder.build(
            UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row", children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/$identityId", text = identity),
            ) + tags.map { UiNode(className = "android.widget.ImageView", viewIdResourceName = "com.x:id/$it") }),
            null, meta, platform, day,
        )!!.root.children.drop(1).map { it.id }
        assertEquals(listOf("~", "com.x:id/chipGold"), ids("user_name", "Riley", "chipRiley", "chipGold"))
        // Review ZZ3: a PERSON_OR_MERCHANT whole value is never shape-gated — a merchant's logo id carrying
        // its whole name is absent (the accepted recall cost); a chrome id sharing one word travels.
        assertEquals(listOf("~", "com.x:id/boxView"), ids("user_name", "Jack in the Box", "jackInTheBoxLogo", "boxView"))
        assertEquals(listOf("com.x:id/roadNameLayout"), ids("address_line_1", "10927 Culebra Road", "roadNameLayout"))
        // A text slot is untouched by the whole-value rule: "Call Riley" beside user_name "Riley" hashes.
        assertEquals(listOf(words(2, "Call Riley")), beside2("tvTitle", "Riley", "Call Riley"))
    }

    @Test
    fun `UU5 WW6 - the capital predicate is code-point based, and a non-ASCII id fails the grammar first`() {
        // The predicate itself (review WW6: the id grammar is ASCII, so it must be tested directly).
        assertTrue(IdPathJudgement.isCapitalAt("to \uD801\uDC08dam", 3))
        assertTrue(!IdPathJudgement.isCapitalAt("to adam", 3))
        // Relabelled grammar case: a supplementary-plane letter is not in the static id grammar at all.
        assertTrue(!IdPathJudgement.isStaticId("deliver_to_\uD801\uDC08dam"))
        assertTrue(IdPathJudgement.isStaticId("deliver_to_label"))
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
        assertEquals(listOf("~"), ids("tvTitle", "Order Details", "orderDetailsHeader"))
        assertEquals(listOf("~"), ids("user_name", "Riley", "chipRiley"))
        // `chipRileyS` carries the id-path name shape: PII-judged, so the sentinel (AH1).
        assertEquals(listOf("~"), ids("user_name", "Riley S", "chipRileyS"))
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
        assertEquals(listOf<String?>("~"), search.map { it.id })
        assertEquals(listOf<String?>("android.widget.SearchView"), search.map { it.className })
        assertEquals(listOf("~"), ids("tvTitle", "Riley", "chipRiley"))
        assertEquals(listOf("~"), ids("tvTitle", "Riley Smith", "chipRileySmith"))
        assertEquals(listOf("~"), ids("tvTitle", "Riley S", "chipRileyS"))
        // PERSON_OR_MERCHANT: a single token included; a merchant's whole name too.
        assertEquals(listOf("~"), ids("user_name", "Riley", "chipRiley"))
        assertEquals(listOf("~"), ids("user_name", "Jack in the Box", "jackInTheBoxLogo"))
        // NAME: the whole value joins across tokens ("Mary Jo" → `chipMaryJo`).
        assertEquals(listOf("~"), ids("customer_name", "Mary Jo", "chipMaryJo"))
        // ADDRESS never adds a whole-value run.
        assertEquals(listOf("com.x:id/mainStreetLabel"), ids("address_line_1", "Main Street", "mainStreetLabel"))
    }

    @Test
    fun `ZZ4 - an all-caps tag ending in one letter reads as a name - the accepted recall cost`() {
        listOf("TAB_B", "SECTION_C", "PRIMARY_BUTTON_A", "chip_RILEY_S", "com.x:id/chip_RILEY_S")
            .forEach { assertTrue(it, !IdPathJudgement.isStaticId(it)) }
        assertTrue(!IdPathJudgement.isStaticId("chip_Adam_S"))
        // A lowercase-led tag stays chrome.
        listOf("tab_B", "option_a", "tabB").forEach { assertTrue(it, IdPathJudgement.isStaticId(it)) }
    }

    private fun idsBeside(identityId: String, identity: String, vararg tags: String) = SkeletonBuilder.build(
        UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row", children = listOf(
            UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/$identityId", text = identity),
        ) + tags.map { UiNode(className = "android.widget.ImageView", viewIdResourceName = "com.x:id/$it") }),
        null, meta, platform, day,
    )!!.root.children.drop(1).map { it.id }

    @Test
    fun `AB2 - a supplementary-plane name protects a tag built from it`() {
        // Osage (U+104B0..): a Capitalized supplementary-plane name; the static id grammar is ASCII, so the
        // tag side is exercised through the containment predicate the id path uses.
        val name = "\uD801\uDCB0\uD801\uDCD8\uD801\uDCD8"
        val filter = FrameFilter(judge = SkeletonBuilder::withholdingStep)
        filter.scan(UiNode(className = "android.widget.TextView", viewIdResourceName = "com.x:id/tvTitle", text = name))
        assertTrue(filter.containsIdentityRun("chip" + name, idForm = true))
        assertTrue(!filter.containsIdentityRun("chipGold", idForm = true))
    }

    @Test
    fun `AB3 - a multi-token whole value matches across id separators`() {
        assertEquals(listOf("~", "~", "~", "com.x:id/row_mary_label"), idsBeside("tvTitle", "Mary Jo", "row_mary_jo", "chip-mary-jo", "rowMaryJo", "row_mary_label"))
        assertEquals(listOf("~"), idsBeside("customer_name", "Mary Jo", "row_mary_jo"))
    }

    @Test
    fun `AB4 - only idProtect rows with 3 or more letters add a whole-value id run`() {
        assertEquals(listOf("com.x:id/ok_button"), idsBeside("tvLastMessage", "Ok", "ok_button"))
        assertEquals(listOf("com.x:id/thanks_button"), idsBeside("tvLastMessage", "Thanks", "thanks_button"))
        assertEquals(listOf("com.x:id/ok_button"), idsBeside("tvTitle", "Ok", "ok_button"))
        assertEquals(listOf("~"), idsBeside("tvTitle", "Riley", "chipRiley"))
        assertEquals(listOf("com.x:id/mainStreetLabel"), idsBeside("address_line_1", "Main Street", "mainStreetLabel"))
        // A NAME's 2-letter word run still protects (its letter runs, not the whole-value rule).
        assertEquals(listOf("~"), idsBeside("customer_name", "Li", "chipLi"))
    }

    @Test
    fun `AC1 - NAME runs match across id separator joins`() {
        assertEquals(listOf("~", "~", "~", "com.x:id/row_mc_gold"),
            idsBeside("customer_name", "McKenna Smith", "row_mc_kenna", "row-mc-kenna", "rowMcKenna", "row_mc_gold"))
    }

    private fun classesBeside(identityId: String, identity: String, vararg classes: String) = SkeletonBuilder.build(
        UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row", children = listOf(
            UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/$identityId", text = identity),
        ) + classes.map { UiNode(className = it, viewIdResourceName = "com.x:id/cell") }),
        null, meta, platform, day,
    )!!.root.children.drop(1).map { it.className }

    @Test
    fun `AC2 - a class carrying a NAME run is absent, a title or merchant word never forks a class`() {
        assertEquals(listOf<String?>(null, "com.x.GoldButton"), classesBeside("customer_name", "Riley", "com.x.RileyButton", "com.x.GoldButton"))
        assertEquals(listOf<String?>("android.widget.TextView"), classesBeside("tvTitle", "Text", "android.widget.TextView"))
        assertEquals(listOf<String?>("androidx.appcompat.widget.SearchView"), classesBeside("tvTitle", "Search", "androidx.appcompat.widget.SearchView"))
        assertEquals(listOf<String?>("com.x.RileyButton"), classesBeside("user_name", "Jack in the Box", "com.x.RileyButton"))
        // A wrapper class is never checked — wrapper eligibility cannot depend on the customer.
        assertEquals(listOf<String?>("android.widget.LinearLayout"), classesBeside("customer_name", "Linear", "android.widget.LinearLayout"))
    }

    @Test
    fun `AC3 - a frame-withheld id keeps its node in the fingerprint as the sentinel`() {
        fun frame(title: String) = UiNode(
            className = "android.widget.FrameLayout", viewIdResourceName = "com.x:id/host", children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/tvTitle", text = title),
                UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/chip_container", children = listOf(
                    UiNode(className = "android.widget.TextView", text = "Continue"),
                )),
            ),
        )
        val chip = SkeletonBuilder.build(frame("Chip"), null, meta, platform, day)!!
        val other = SkeletonBuilder.build(frame("Other"), null, meta, platform, day)!!
        assertEquals("~", chip.root.children[1].id)
        assertEquals("com.x:id/chip_container", other.root.children[1].id)
        // The container is NOT spliced as a wrapper: the frame-withheld tree equals the same tree with the
        // sentinel id, and differs from the spliced (null-id) tree.
        val sentinelTree = other.root.copy(children = listOf(other.root.children[0], other.root.children[1].copy(id = "~")))
        val splicedTree = other.root.copy(children = listOf(other.root.children[0], other.root.children[1].copy(id = null)))
        assertEquals(CensusFingerprint.of(sentinelTree), chip.fingerprint)
        assertTrue(CensusFingerprint.of(splicedTree) != chip.fingerprint)
    }

    @Test
    fun `AF2 - a framework class is never containment-checked, an app class is`() {
        assertEquals(listOf<String?>("com.google.android.material.chip.Chip"), classesBeside("customer_name", "Chip", "com.google.android.material.chip.Chip"))
        assertEquals(listOf<String?>("androidx.cardview.widget.CardView"), classesBeside("customer_name", "Card", "androidx.cardview.widget.CardView"))
        assertEquals(listOf<String?>(null), classesBeside("customer_name", "Riley", "com.x.RileyButton"))
        // Every wrapper class is a KNOWN framework class, so wrapper eligibility can never depend on the customer.
        AnonymousWrappers.WRAPPER_CLASSES.forEach { cls -> assertTrue(cls, cls in FrameworkClasses.KNOWN) }
    }

    @Test
    fun `AF4 - the process-wide isStaticId memo is stable past its bound`() {
        val ids = (0 until 700).map { "com.x:id/tag_" + ('a' + it % 26) + ('a' + it / 26 % 26) + "_x" + it.toString(36) }
        val first = ids.map { IdPathJudgement.isStaticId(it) }
        assertEquals(first, ids.map { IdPathJudgement.isStaticId(it) })
        assertEquals(listOf(true, false, false), listOf("chip_Gold", "chip_Adam_S", "row_Deliver_to_Sam").map { IdPathJudgement.isStaticId(it) })
    }

    @Test
    fun `AG2 - a framework PREFIX is not proof, only a known framework class is exempt`() {
        assertEquals(
            listOf<String?>(null, null, null),
            classesBeside("customer_name", "Riley", "androidx.RileyButton", "android.widget.RileyView", "com.google.android.material.RileyChip"),
        )
        assertEquals(listOf<String?>("com.google.android.material.chip.Chip"), classesBeside("customer_name", "Chip", "com.google.android.material.chip.Chip"))
    }

    @Test
    fun `AG3 - a user_name with a trailing initial seeds runs`() {
        assertEquals(listOf("~"), idsBeside("user_name", "Riley S", "chipRiley"))
        assertEquals(listOf(TextSlot.WITHHELD), beside2("user_name", "Riley S", "Text Riley"))
        assertEquals(listOf(TextSlot.WITHHELD), beside2("user_name", "Mary Jo S.", "Mary's order"))
        assertEquals(listOf(words(2, "Sign in")), beside2("user_name", "In-N-Out Burger", "Sign in"))
    }

    @Test
    fun `AH1 - a PII-judged id and a frame-withheld id fingerprint alike on a wrapper node`() {
        fun frame(customer: String, tag: String) = SkeletonBuilder.build(
            UiNode(className = "android.widget.FrameLayout", viewIdResourceName = "com.x:id/host", children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/customer_name", text = customer),
                UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/$tag", children = listOf(
                    UiNode(className = "android.widget.TextView", text = "Continue"),
                )),
            )),
            null, meta, platform, day,
        )!!
        val riley = frame("Riley", "chip_Riley_S")
        val li = frame("Li", "chip_Li")
        assertEquals("~", riley.root.children[1].id)
        assertEquals("~", li.root.children[1].id)
        assertEquals(riley.fingerprint, li.fingerprint)
    }

    @Test
    fun `AI1 - an inventoried framework class the corpus never renders is exempt too`() {
        assertEquals(listOf<String?>("com.google.android.material.card.MaterialCardView"),
            classesBeside("customer_name", "Card", "com.google.android.material.card.MaterialCardView"))
        assertEquals(listOf<String?>("android.widget.GridLayout"), classesBeside("customer_name", "Grid", "android.widget.GridLayout"))
        fun print(customer: String) = SkeletonBuilder.build(
            UiNode(className = "android.widget.FrameLayout", viewIdResourceName = "com.x:id/host", children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/customer_name", text = customer),
                UiNode(className = "com.google.android.material.card.MaterialCardView", children = listOf(
                    UiNode(className = "android.widget.GridLayout"),
                )),
            )),
            null, meta, platform, day,
        )!!.fingerprint
        assertEquals(print("Card Grid"), print("Riley"))
        // The fake-prefix vectors still null.
        assertEquals(
            listOf<String?>(null, null, null),
            classesBeside("customer_name", "Riley", "androidx.RileyButton", "android.widget.RileyView", "com.google.android.material.RileyChip"),
        )
        assertTrue(FrameworkClasses.KNOWN.size > FrameworkClasses.CORPUS.size)
    }

    @Test
    fun `AI2 AL2 - a missing or corrupt inventory only shrinks KNOWN (fail closed)`() {
        assertEquals(emptySet<String>(), FrameworkClasses.parseInventory(null))
        assertEquals(emptySet<String>(), FrameworkClasses.parseInventory("android.widget.GridLayout\n".byteInputStream()))
        val good = FrameworkClasses.inventoryText(listOf("android.widget.GridLayout"))
        assertEquals(setOf("android.widget.GridLayout"), FrameworkClasses.parseInventory(good.byteInputStream()))
        // The shipped resource loads and carries the inventory.
        assertTrue("android.widget.GridLayout" in FrameworkClasses.KNOWN)
    }

    @Test
    fun `AJ6 - one tri-state verdict per id`() {
        assertEquals(IdPathJudgement.IdVerdict.DYNAMIC, IdPathJudgement.verdict("PRIMARY_BUTTON_3f488d4a-0f0b-4fb9-9c86-c4e0253ba22a"))
        assertEquals(IdPathJudgement.IdVerdict.PII_WITHHELD, IdPathJudgement.verdict("com.x:id/chip_Riley_S"))
        assertEquals(IdPathJudgement.IdVerdict.STATIC, IdPathJudgement.verdict("com.x:id/chip_Gold"))
        assertTrue(IdPathJudgement.isStaticId("com.x:id/chip_Gold"))
    }

    @Test
    fun `AK5 AL2 - a malformed, truncated, failing or invalid inventory is discarded whole`() {
        val good = FrameworkClasses.inventoryText(listOf("android.widget.GridLayout", "android.widget.TextView")).toByteArray()
        assertEquals(setOf("android.widget.GridLayout", "android.widget.TextView"), FrameworkClasses.parseInventory(good.inputStream()))
        // Malformed UTF-8 (a valid header over a body with a bad byte).
        val badBody = byteArrayOf(0x61, 0xC3.toByte(), 0x28, 0x0A)
        assertEquals(emptySet<String>(), FrameworkClasses.parseInventory(("#sha256=00\n".toByteArray() + badBody).inputStream()))
        // Truncated: the header's sha256 no longer matches the body.
        assertEquals(emptySet<String>(), FrameworkClasses.parseInventory(good.copyOf(good.size - 5).inputStream()))
        // A mid-stream exception.
        val failing = object : java.io.InputStream() {
            var i = 0
            override fun read(): Int = if (i < good.size / 2) good[i++].toInt() and 0xFF else throw java.io.IOException("boom")
        }
        assertEquals(emptySet<String>(), FrameworkClasses.parseInventory(failing))
        // One entry that is not a static class name spoils the whole resource, even with a matching header.
        val invalid = FrameworkClasses.inventoryText(listOf("android.widget.TextView", "not a class!"))
        assertEquals(emptySet<String>(), FrameworkClasses.parseInventory(invalid.byteInputStream()))
    }

    @Test
    fun `AM1 - any loader failure, an Error included, degrades to an empty inventory`() {
        val erroring = object : java.io.InputStream() {
            override fun read(): Int = throw NoSuchMethodError("readNBytes")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw NoSuchMethodError("readNBytes")
        }
        assertEquals(emptySet<String>(), FrameworkClasses.parseInventory(erroring))
        // A stream delivered in small chunks still loads (the API-1 bounded read loop).
        val good = FrameworkClasses.inventoryText(listOf("android.widget.GridLayout")).toByteArray()
        val trickle = object : java.io.InputStream() {
            var i = 0
            override fun read(): Int = if (i < good.size) good[i++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (i >= good.size) return -1
                b[off] = good[i++]
                return 1
            }
        }
        assertEquals(setOf("android.widget.GridLayout"), FrameworkClasses.parseInventory(trickle))
    }
}
