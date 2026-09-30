package cloud.trotter.dashbuddy.census
import cloud.trotter.dashbuddy.core.pipeline.CustomerTextMarkers
import cloud.trotter.dashbuddy.core.pipeline.SensitiveTextMarkers
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder
import cloud.trotter.dashbuddy.core.pipeline.census.diagnostics.DiagnosticSkeletonBuilder
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.capture.schema.UiNodeSchema
import cloud.trotter.dashbuddy.domain.census.contract.CaseFold
import cloud.trotter.dashbuddy.domain.census.contract.CensusHash
import cloud.trotter.dashbuddy.domain.census.contract.ClassNameGrammar
import cloud.trotter.dashbuddy.domain.census.contract.SkeletonSchema
import cloud.trotter.dashbuddy.domain.census.contract.TextSlot
import cloud.trotter.dashbuddy.domain.census.contract.UiSkeletonDto
import cloud.trotter.dashbuddy.domain.census.contract.UiSkeletonNodeDto
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNodeTextField
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
            walkNodes(f.tree) { n -> n.viewIdResourceName?.let { if (!SkeletonBuilder.isStaticId(it)) rejected += it } }
        }
        // A static id that trips the gate is a red test here, never a silent drop (review CC7 admitted a
        // single internal space, so `Artwork Image` is static now).
        assertEquals(
            sortedSetOf(
                "PRIMARY_BUTTON_3f488d4a-0f0b-4fb9-9c86-c4e0253ba22a",
                "PRIMARY_BUTTON_62132347-ff07-4f36-988d-db9d3cfa4dbd",
                "PRIMARY_BUTTON_9db4e2af-5a58-4a43-ba63-295126ceddef",
            ),
            rejected,
        )
        built.mapNotNull { it.second }.forEach { item ->
            walkSkeleton(item.root) { n -> n.id?.let { assertTrue(it, SkeletonBuilder.isStaticId(it)) } }
        }
        // Review JJ1: the frame-level containment rule nulls no committed chrome id — no identity seed
        // collides with a static id anywhere in the corpus (every static raw id reaches the wire).
        val frameDropped = sortedSetOf<String>()
        for ((f, item) in built) {
            item ?: continue
            fun pair(n: UiNode, s: UiSkeletonNodeDto) {
                val raw = n.viewIdResourceName
                if (raw != null && SkeletonBuilder.isStaticId(raw) && s.id == null) frameDropped += "${f.path}: $raw"
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
        // Review TT2: the merchant's WHOLE value protects an id built from it, and no other id.
        val merchantIds = build(
            UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/user_name", text = "Jack in the Box"),
            UiNode(className = "android.widget.ImageView", viewIdResourceName = "com.doordash.driverapp:id/jackInTheBoxLogo"),
            UiNode(className = "android.widget.ImageView", viewIdResourceName = "com.doordash.driverapp:id/boxView"),
            UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/address_line_1", text = "10927 Culebra Road"),
            UiNode(className = "android.widget.ImageView", viewIdResourceName = "com.doordash.driverapp:id/roadNameLayout"),
        )
        assertEquals(null, merchantIds[1].id)
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
