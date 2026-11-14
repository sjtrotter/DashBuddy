package cloud.trotter.log.gig.core.pipeline.census

import cloud.trotter.census.contract.SkeletonVersion
import cloud.trotter.log.gig.core.pipeline.PropSeeds
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1626 (Sol round 1): no customer or dasher value the phone can detect ever becomes a review word, in any field
 * position, alone or duplicated elsewhere in the frame (another field, another node, the window title, beside an
 * address block). Every frame also carries one line of app wording, so a frame that keeps nothing is not a pass.
 *
 * Not generated, by design: a bare single first name and an unmarked free-text note. Nothing on the phone tells either
 * from app wording; the server's class keeps them greyed (ADR-0011 §15, risk 12). All values are fictional.
 */
class ReviewWordCoverPropertyTest : SkeletonBuilderTestBase() {

    private companion object {
        /** #878 pinned seed; bump deliberately to explore. */
        const val SEED = 0x1626_0001L
        const val WORDING = "Pick up by"

        val FIRST = listOf("Avery", "Jordan", "Riley", "Sam", "Morgan", "Casey", "Quinn", "José", "Mary-Jo", "O'Brien", "Li")
        val LAST = listOf("Example", "Sample", "Testerson", "Placeholder", "Fakename")
        val STREETS = listOf("Sample Ridge", "Oak Hollow", "Fletchers", "Main", "Elm", "Test Creek")
        val SUFFIXES = listOf("Dr", "Ln", "St", "Ave", "Rd", "Way", "Blvd", "Ct")
        val CITIES = listOf("San Antonio", "Testville", "Springfield", "Stone Oak", "Alamo Heights")
        val ZONES = listOf("Downtown", "North Central", "Zone", "Area", "Region", "Market")
        val COLONS = listOf(":", ": ", " : ", "：", "﹕", " ：")
        val STORES = listOf("Sample Store", "Test Market", "Example Deli")
    }

    private fun RandomSource.pick(list: List<String>) = list[random.nextInt(list.size)]
    private fun RandomSource.digits(n: Int) = (1..n).joinToString("") { random.nextInt(10).toString() }
    private fun RandomSource.initial() = ('A' + random.nextInt(26)).toString()

    /** Sol round 2 S11: the same label in any casing. */
    private fun RandomSource.cased(value: String) = when (random.nextInt(4)) {
        0 -> value
        1 -> value.lowercase()
        2 -> value.uppercase()
        else -> value.mapIndexed { i, c -> if (i % 2 == 0) c.uppercaseChar() else c.lowercaseChar() }.joinToString("")
    }

    /** A value the phone can detect: customer names in marked forms, addresses, codes, notes, return lines, labels. */
    private val detectable: Arb<String> = arbitrary { rs ->
        val first = rs.pick(FIRST)
        val last = rs.pick(LAST)
        when (rs.random.nextInt(24)) {
            0 -> "$first ${rs.initial()}."
            1 -> "$first ${rs.initial()}"
            2 -> "Deliver to $first"
            3 -> "Pickup for $first ${rs.initial()}"
            4 -> "Order for $first $last"
            5 -> "${1 + rs.random.nextInt(9999)} ${rs.pick(STREETS)} ${rs.pick(SUFFIXES)}"
            6 -> "${rs.pick(CITIES)}, TX 78${rs.digits(3)}"
            7 -> "Apt ${1 + rs.random.nextInt(999)}"
            8 -> "Gate code ${rs.digits(4)}"
            9 -> "pin ${rs.digits(4)}"
            10 -> "\"Leave it by the ${rs.pick(listOf("blue", "side", "back"))} door\""
            11 -> "Customer notes${rs.pick(COLONS)}ring twice"
            12 -> "Dropoff instructions${rs.pick(COLONS)}use the side gate"
            13 -> "Return $first ${rs.initial()} to ${rs.pick(STORES)}"
            14 -> "Hi, $first"
            15 -> "Welcome back, $first"
            16 -> "Good evening, $first"
            17 -> "Account ending in ${rs.digits(4)}"
            18 -> rs.cased("Dash in ${rs.pick(CITIES)}")
            19 -> rs.cased("Busy near ${rs.pick(ZONES)}")
            22 -> rs.cased("Busy near downtown")
            23 -> rs.cased("Hotspot around ${rs.pick(CITIES)}")
            20 -> "${rs.pick(listOf("Name", "Display name", "Zone", "Customer"))}${rs.pick(COLONS)}${rs.pick(listOf(first, rs.pick(CITIES)))}"
            else -> "${first.lowercase()}${rs.digits(2)}@example.com"
        }
    }

    private val fields: List<(String) -> UiNode> = listOf(
        { UiNode(className = "android.widget.TextView", text = it) },
        { UiNode(className = "android.widget.TextView", contentDescription = it) },
        { UiNode(className = "android.widget.TextView", stateDescription = it) },
        { UiNode(className = "android.widget.TextView", hintText = it) },
        { UiNode(className = "android.widget.TextView", tooltipText = it) },
        { UiNode(className = "android.widget.TextView", paneTitle = it) },
        { UiNode(className = "android.widget.TextView", errorText = it) },
        { UiNode(className = "android.widget.Button", clickActionLabel = it, isClickable = true) },
    )

    /** [value] in one field position, duplicated in another position and in the window title; one line of wording. */
    private fun assertNoWord(value: String, at: Int, again: Int, title: Boolean, besideAddress: Boolean) {
        val nodes = mutableListOf(UiNode(className = "android.widget.TextView", text = WORDING), fields[at](value), fields[again](value))
        if (besideAddress) {
            nodes += UiNode(
                className = "android.widget.LinearLayout",
                children = listOf(
                    UiNode(className = "android.widget.TextView", text = "1234 Sample Ridge Dr"),
                    UiNode(className = "android.widget.TextView", text = "Testville, TX 78000"),
                    UiNode(className = "android.widget.TextView", text = value),
                ),
            )
        }
        val tree = UiNode(className = "android.widget.FrameLayout", children = nodes).restoreParents()
        val outcome = SkeletonBuilder.outcome(tree, value.takeIf { title }, meta, platform, day, SkeletonVersion.V2, collectWords = true)
        val words = (outcome as? SkeletonBuilder.Outcome.Built)?.reviewWords ?: return
        assertEquals("'$value' at $at/$again title=$title address=$besideAddress", setOf(WORDING), words.slots.mapNotNull { words[it] }.toSet())
    }

    @Test
    fun `property - no detectable customer or dasher value becomes a review word in any position or duplicate`() = runTest {
        val case: Arb<List<Any>> = arbitrary { rs ->
            listOf(detectable.sample(rs).value, rs.random.nextInt(fields.size), rs.random.nextInt(fields.size), rs.random.nextBoolean(), rs.random.nextBoolean())
        }
        checkAll(PropSeeds.samples(600), PropSeeds.config(SEED), case) { (value, at, again, title, address) ->
            assertNoWord(value as String, at as Int, again as Int, title as Boolean, address as Boolean)
        }
    }

    @Test
    fun `every generated form and field is covered at least once`() {
        val rs = RandomSource.seeded(SEED)
        repeat(400) { i ->
            val value = detectable.sample(rs).value
            fields.indices.forEach { at -> assertNoWord(value, at, (at + i) % fields.size, title = i % 2 == 0, besideAddress = i % 3 == 0) }
        }
    }

    @Test
    fun `a value the address block holds is covered everywhere in the frame`() {
        val note = "Leave by porch"
        val tree = UiNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                UiNode(
                    className = "android.widget.LinearLayout",
                    children = listOf(
                        UiNode(className = "android.widget.TextView", text = "1234 Sample Ridge Dr", contentDescription = note),
                        UiNode(className = "android.widget.TextView", text = "Testville, TX 78000"),
                    ),
                ),
                UiNode(className = "android.widget.TextView", text = note),
                UiNode(className = "android.widget.TextView", text = "  leave   by porch ".replace("leave", "Leave")),
                UiNode(className = "android.widget.TextView", text = WORDING),
            ),
        ).restoreParents()
        val outcome = SkeletonBuilder.outcome(tree, note, meta, platform, day, SkeletonVersion.V2, collectWords = true) as SkeletonBuilder.Outcome.Built
        val words = requireNotNull(outcome.reviewWords)
        assertEquals(setOf(WORDING), words.slots.mapNotNull { words[it] }.toSet())
    }

    // ── The allow-list (Sol round 2) ─────────────────────────────────────────────────────────────────────────────────

    /** A bare name or place, unmarked: only the allow-list stands between it and a review word. */
    private val bare: Arb<String> = arbitrary { rs ->
        when (rs.random.nextInt(5)) {
            0 -> rs.pick(FIRST)
            1 -> "${rs.pick(FIRST)} ${rs.pick(LAST)}"
            2 -> rs.cased(rs.pick(CITIES))
            3 -> "${rs.pick(STREETS)} ${rs.pick(SUFFIXES)}"
            else -> rs.cased(rs.pick(FIRST))
        }
    }

    private fun keptWords(value: String): Set<String> {
        val tree = UiNode(
            className = "android.widget.FrameLayout",
            children = listOf(UiNode(className = "android.widget.TextView", text = WORDING), UiNode(className = "android.widget.TextView", text = value)),
        ).restoreParents()
        val outcome = SkeletonBuilder.outcome(tree, null, meta, platform, day, SkeletonVersion.V2, collectWords = true)
        val words = (outcome as? SkeletonBuilder.Outcome.Built)?.reviewWords ?: return emptySet()
        return words.slots.mapNotNull { words[it] }.toSet() - WORDING
    }

    @Test
    fun `property - a generated name never survives, and a place survives only when every token is a common word`() = runTest {
        checkAll(PropSeeds.samples(600), PropSeeds.config(SEED + 1), bare) { value ->
            val kept = keptWords(value)
            val isName = FIRST.any { value.equals(it, ignoreCase = true) || value.startsWith("$it ") }
            if (isName) assertEquals("the name '$value' survived", emptySet<String>(), kept)
            // Risk 12, stated: a place made only of common words ("Medical Center") can survive; nothing else can.
            kept.forEach { word -> assertTrue("'$word' survived with a token outside the lists", ReviewAllowList.allows(word)) }
        }
        FIRST.forEach { assertFalse("the name '$it' is on the allow-list", ReviewAllowList.allows(it)) }
    }

    @Test
    fun `the bundled rules' chrome is on the allow-list, and plain rule anchors survive as review words`() {
        val anchors = ruleLiterals().filter { WORDS_ONLY.matches(it) && it.split(' ').size in 1..8 }
        assertTrue("no rule anchors read", anchors.size > 50)
        anchors.forEach { assertTrue("rule chrome '$it' is not on the allow-list", ReviewAllowList.allows(it)) }
        assertTrue("the rules' brand word", ReviewAllowList.contains("doordash"))
        // Through the builder: an anchor the denylist also passes is held as a review word.
        val passing = anchors.filter { ReviewWordCover.shows(it) }.take(40)
        assertTrue(passing.size >= 10)
        passing.forEach { anchor ->
            val tree = UiNode(className = "android.widget.FrameLayout", children = listOf(UiNode(className = "android.widget.TextView", text = anchor))).restoreParents()
            val built = SkeletonBuilder.outcome(tree, null, meta, platform, day, SkeletonVersion.V2, collectWords = true) as SkeletonBuilder.Outcome.Built
            val words = requireNotNull(built.reviewWords)
            assertEquals(anchor, setOf(anchor), words.slots.mapNotNull { words[it] }.toSet())
        }
    }

    @Test
    fun `the common-word list is the committed one, whole`() {
        assertTrue("the common list did not load (hash, size or encoding)", ReviewAllowList.size > 14_000)
        listOf("the", "de", "la", "pedido", "commande", "accept").forEach { assertTrue(it, ReviewAllowList.contains(it)) }
        assertTrue(ReviewAllowList.parse(verifyHash = true) { "#sha256=00\nthe\n".byteInputStream() }.isEmpty())
    }

    private val WORDS_ONLY = Regex("""[\p{L}]+( [\p{L}]+)*""")

    /** Every `hasText` literal of the generated rules, read off disk like the corpus tests. */
    private fun ruleLiterals(): List<String> {
        var dir = java.io.File(".").absoluteFile.normalize()
        while (!java.io.File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile ?: error("no repo root")
        val rules = java.io.File(dir, "core/pipeline/build/generated/assets/importMatchersRules/rules")
        val hasText = Regex(""""hasText"\s*:\s*"([^"\\]*)"""")
        return rules.listFiles { f -> f.extension == "json" }.orEmpty().flatMap { file ->
            hasText.findAll(file.readText()).map { it.groupValues[1].trim() }.toList()
        }.distinct()
    }
}
