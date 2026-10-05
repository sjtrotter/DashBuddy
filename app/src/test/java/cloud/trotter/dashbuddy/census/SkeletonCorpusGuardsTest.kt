package cloud.trotter.dashbuddy.census
import cloud.trotter.dashbuddy.core.pipeline.CustomerTextMarkers
import cloud.trotter.dashbuddy.core.pipeline.SensitiveTextMarkers
import cloud.trotter.dashbuddy.core.pipeline.census.FrameworkClasses
import cloud.trotter.dashbuddy.core.pipeline.census.IdPathJudgement
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder
import cloud.trotter.dashbuddy.core.pipeline.census.diagnostics.DiagnosticSkeletonBuilder
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.capture.schema.UiNodeSchema
import cloud.trotter.census.contract.CaseFold
import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.contract.ClassNameGrammar
import cloud.trotter.census.contract.ResourceIdGrammar
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.UiSkeletonNodeDto
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNodeTextField
import cloud.trotter.dashbuddy.domain.pipeline.UiTextBounds
import cloud.trotter.dashbuddy.domain.privacy.PiiShapes
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.test.util.CorpusDecoys
import cloud.trotter.dashbuddy.test.util.PropSeeds
import cloud.trotter.dashbuddy.test.util.SnapshotRedactor
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * The census corpus GUARDS (#1160 reviews AA10, CC1, JJ1, OO2, QQ1, SS5; split from `SkeletonCorpusTest` by
 * review UU10): the id/class gate pins over the committed corpus, the frame-rule flip explanation guard,
 * and the explicit chrome-recall pins.
 */
class SkeletonCorpusGuardsTest : SkeletonCorpusTestBase() {

    @Test
    fun `the id gate rejects exactly the dynamic ids in the corpus, and no rejected id is shipped`() {
        val rejected = sortedSetOf<String>()
        corpus.forEach { f ->
            walkNodes(f.tree) { n -> n.viewIdResourceName?.let { if (!IdPathJudgement.isStaticId(it)) rejected += it } }
        }
        // Review ZZ4 (reverses XX4): an all-caps tag ending in a one-letter segment reads as a name
        // (`chip_RILEY_S`), so these constants are withheld too — the accepted recall cost.
        listOf("PRIMARY_BUTTON_A", "TAB_B", "SECTION_C").forEach { assertTrue(it, !IdPathJudgement.isStaticId(it)) }
        // A static id that trips the gate is a red test here, never a silent drop (review CC7 admitted a
        // single internal space, so `Artwork Image` is static now).
        assertEquals(
            sortedSetOf(
                "PRIMARY_BUTTON_3f488d4a-0f0b-4fb9-9c86-c4e0253ba22a",
                "PRIMARY_BUTTON_62132347-ff07-4f36-988d-db9d3cfa4dbd",
                "PRIMARY_BUTTON_9db4e2af-5a58-4a43-ba63-295126ceddef",
                // #1127 dropoff_customer_unavailable fixtures (prism button ids carry a per-render UUID)
                "PRIMARY_BUTTON_23ff862f-9605-4c18-9c83-0f8d5a615fd2",
                "PRIMARY_BUTTON_904af26a-177a-408d-a440-c4a187ab8bc9",
                "SECONDARY_BUTTON_23ff862f-9605-4c18-9c83-0f8d5a615fd2",
            ),
            rejected,
        )
        built.mapNotNull { it.second }.forEach { item ->
            walkSkeleton(item.root) { n ->
                n.id?.takeIf { it != ResourceIdGrammar.FRAME_WITHHELD_ID }?.let { assertTrue(it, IdPathJudgement.isStaticId(it)) }
            }
        }
        // Review JJ1: the frame-level containment rule nulls no committed chrome id — no identity seed
        // collides with a static id anywhere in the corpus (every static raw id reaches the wire).
        val frameDropped = sortedSetOf<String>()
        for ((f, item) in built) {
            item ?: continue
            fun pair(n: UiNode, s: UiSkeletonNodeDto) {
                val raw = n.viewIdResourceName
                if (raw != null && IdPathJudgement.isStaticId(raw) && (s.id == null || s.id == ResourceIdGrammar.FRAME_WITHHELD_ID)) {
                    frameDropped += "${f.path}: $raw"
                }
                // AC2: nor does the class check null a committed static class.
                if (ClassNameGrammar.staticOrNull(n.className) != null && s.className == null) frameDropped += "${f.path}: class"
                n.children.zip(s.children).forEach { (a, b) -> pair(a, b) }
            }
            pair(f.tree, item.root)
        }
        assertEquals(sortedSetOf<String>(), frameDropped)
    }

    @Test
    fun `frame-level flips are explained by the seeding rule (reviews OO2, QQ1, SS5)`() {
        val unexplained = mutableListOf<String>()
        var committedFlips = 0
        var substitutedFlips = 0
        var bareFlips = 0
        for ((f, _) in built) {
            val (n, u) = frameFlips(f.path, platformOf(f.path), f.tree)
            committedFlips += n
            unexplained += u
            // QQ1: substitute RAW identity values back into the mask slots, so seeding actually happens.
            // (b)'s two families ("Jordan T" / "Morgan K" …) are caught per FIELD by the name shape, so
            // they never exercise the frame rule; a third, BARE-first-name family ("Jordan" / "Morgan" in
            // name masks — a realistic `user_name` render) is caught only by the frame rule's seeding.
            if (corpusHasNoMask(f.tree)) continue
            for (variant in 0..2) {
                val substituted = mapTree(f.tree) { s ->
                    s?.let { MASK_TOKEN.replace(it) { m -> if (variant == 2) barePseudonym(m.value) else pseudonym(m.value, variant) } }
                }
                val (sn, su) = frameFlips("${f.path}#v$variant", platformOf(f.path), substituted)
                if (variant == 2) bareFlips += sn else substitutedFlips += sn
                unexplained += su
            }
        }
        var handFlips = 0
        for ((name, tree) in guardFixtures) {
            val (n, u) = frameFlips("handwritten:$name", Platform.DoorDash, tree)
            handFlips += n
            unexplained += u
        }
        println("frame-rule flips: committed=$committedFlips substitutedB=$substitutedFlips bareNames=$bareFlips handwritten=$handFlips")
        assertTrue(unexplained.take(20).joinToString("\n"), unexplained.isEmpty())
        // The guard must never go vacuous again: it has to see real seeding.
        // The guard must never go vacuous again. The committed corpus is masked and its substitutions are
        // caught per field (only one committed fixture has an id-less mask beside a masked NAME, and that
        // one is marker-led), so the floor is pinned on the hand-written shapes: AA1 (3) + desc-only
        // name (1) + the ADDRESS and EXACT exact duplicates (2).
        assertEquals("hand-written frame-rule flips", 6, handFlips)
    }

    /**
     * Review SS5: the flip guard proves SELF-CONSISTENCY (every flip matches the seeding rule), not chrome
     * RECALL — a NAME run that over-withholds is "explained" by construction. So chrome recall is pinned
     * explicitly: these slots, classes and ids must survive beside the identity values that once
     * suppressed them (the LL1 address words, the SS1 merchant under `user_name`, the NN3 TalkBack desc).
     */
    @Test
    fun `the code-point-safe text cap changes no committed value (review AL4)`() {
        val changed = mutableListOf<String>()
        corpus.forEach { f ->
            walkNodes(f.tree) { n ->
                (n.scrubbableStrings().mapNotNull { it.second } + listOfNotNull(n.className, n.viewIdResourceName)).forEach { v ->
                    if (UiTextBounds.cap(v) != v.take(UiTextBounds.MAX_TEXT_LENGTH)) changed += f.path
                }
            }
        }
        assertEquals(emptyList<String>(), changed)
    }

    @Test
    fun `chrome recall - named chrome slots, classes and ids are not suppressed by identity seeding (review SS5)`() {
        fun build(vararg nodes: UiNode): List<UiSkeletonNodeDto> = SkeletonBuilder.build(
            UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.doordash.driverapp:id/sheet", children = nodes.toList())
                .restoreParents(),
            null, META, Platform.DoorDash, DAY,
        )!!.root.children
        fun text(n: UiSkeletonNodeDto) = n.text.getValue("text")
        fun hashed(value: String, slot: TextSlot) =
            assertTrue("'$value' must hash (chrome recall)", slot.h != null && slot.kind.startsWith("words:"))

        val address = build(
            UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/address_line_1", text = "Bay View Commons"),
            UiNode(className = "android.widget.TextView", text = "View details"),
            UiNode(className = "android.widget.ImageView", viewIdResourceName = "com.doordash.driverapp:id/roadNameLayout"),
        )
        hashed("View details", text(address[1]))
        assertEquals("com.doordash.driverapp:id/roadNameLayout", address[2].id)
        assertEquals("android.widget.ImageView", address[2].className)

        val merchant = build(
            UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/user_name", text = "Jack in the Box"),
            UiNode(className = "android.widget.TextView", text = "Head to the store"),
            UiNode(className = "android.widget.TextView", text = "Sign in"),
            UiNode(className = "android.widget.TextView", text = "Total"),
        )
        hashed("Head to the store", text(merchant[1]))
        // Reviews TT2, ZZ3: a merchant's whole value protects only an id built from its WHOLE name (the logo
        // id — the accepted recall cost); `boxView` and `roadNameLayout` (ADDRESS adds no run) travel.
        val merchantIds = build(
            UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/user_name", text = "Jack in the Box"),
            UiNode(className = "android.widget.ImageView", viewIdResourceName = "com.doordash.driverapp:id/jackInTheBoxLogo"),
            UiNode(className = "android.widget.ImageView", viewIdResourceName = "com.doordash.driverapp:id/boxView"),
            UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/address_line_1", text = "10927 Culebra Road"),
            UiNode(className = "android.widget.ImageView", viewIdResourceName = "com.doordash.driverapp:id/roadNameLayout"),
        )
        assertEquals("~", merchantIds[1].id)
        assertEquals("com.doordash.driverapp:id/boxView", merchantIds[2].id)
        assertEquals("com.doordash.driverapp:id/roadNameLayout", merchantIds[4].id)
        hashed("Sign in", text(merchant[2]))
        hashed("Total", text(merchant[3]))
        merchant.forEach { assertEquals("android.widget.TextView", it.className) }

        val talkback = build(
            UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/customer_name", text = "Adam", contentDescription = "Customer name Adam"),
            UiNode(className = "android.widget.TextView", text = "Customer"),
            UiNode(className = "android.widget.TextView", text = "Name"),
            UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/customer_name_label", text = "Customer name"),
        )
        hashed("Customer", text(talkback[1]))
        hashed("Name", text(talkback[2]))
        assertEquals("com.doordash.driverapp:id/customer_name", talkback[0].id)
        assertEquals("com.doordash.driverapp:id/customer_name_label", talkback[3].id)
    }

    @Test
    fun `every framework-prefixed class the corpus renders is a known framework class (review AG2)`() {
        val unlisted = sortedSetOf<String>()
        corpus.forEach { f ->
            walkNodes(f.tree) { n ->
                n.className?.let { cls ->
                    if (FrameworkClasses.PACKAGES.any { cls.startsWith(it) } && cls !in FrameworkClasses.KNOWN) unlisted += cls
                }
            }
        }
        assertEquals(sortedSetOf<String>(), unlisted)
    }

    @Test
    fun `the class gate rejects no committed class, and no rejected class is shipped (review CC1)`() {
        val rejected = sortedSetOf<String>()
        corpus.forEach { f ->
            walkNodes(f.tree) { n -> n.className?.let { if (!ClassNameGrammar.isStatic(it)) rejected += it } }
        }
        // Empty today: a new dynamic or text-like class in the corpus turns this red, never a silent drop.
        assertEquals(sortedSetOf<String>(), rejected)
        built.mapNotNull { it.second }.forEach { item ->
            walkSkeleton(item.root) { n -> n.className?.let { assertTrue(it, ClassNameGrammar.isStatic(it)) } }
        }
    }
}
