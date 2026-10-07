package cloud.trotter.dashbuddy.guard

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Checks English XML resource copy; does not cover Kotlin literals. */
class BannedCopyGuardTest {
    private val banned = Regex(
        """\b(delve|leverage|robust|streamline|harness|seamless|effortless|unlock|supercharge|quietly|deeply|fundamentally|remarkably|utilize)\b""",
        RegexOption.IGNORE_CASE,
    )

    @Test
    fun englishMainStringsFollowCopyRules() {
        val root = RepoRoot.locate()
        val files = listOf("app", "feature", "core").flatMap { module ->
            File(root, module).walkTopDown()
                .onEnter { it.name != "build" && it.name != ".gradle" }
                .filter { file ->
                    val folder = file.parentFile?.name.orEmpty()
                    file.isFile &&
                        file.extension == "xml" &&
                        "/src/main/res/" in file.invariantSeparatorsPath &&
                        (
                            folder == "values" ||
                            folder == "values-en" ||
                            folder.startsWith("values-en-")
                        )
                }
                .toList()
        }

        val paths = files.map {
            it.relativeTo(root).invariantSeparatorsPath
        }.toSet()
        assertTrue(
            "Missing expected English resource files",
            paths.containsAll(
                setOf(
                    "app/src/main/res/values/strings.xml",
                    "feature/settings/src/main/res/values/strings.xml",
                    "feature/setup/src/main/res/values/strings.xml",
                    "feature/dashboard/src/main/res/values/strings.xml",
                    "feature/bubble/src/main/res/values/strings.xml",
                )
            ),
        )

        val app = "app/src/main/res/values/strings.xml"
        val settings = "feature/settings/src/main/res/values/strings.xml"
        val placeholders = setOf(
            "$app:bubble_offer_card_metric_placeholder",
            "$app:weekly_plan_window_unpriced_value",
        )
        // Developer-only text (out of copy scope), frozen at its current em-dash count per key.
        val debugEmDashes = mapOf(
            "$settings:developer_settings_bubble_session_pinned_explainer" to 1,
            "$settings:developer_settings_census_share_captures_explainer" to 2,
        )
        val seenExceptions = mutableSetOf<String>()
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature(
            "http://apache.org/xml/features/disallow-doctype-decl", true
        )

        files.forEach { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            val nodes = factory.newDocumentBuilder()
                .parse(file).getElementsByTagName("*")

            for (i in 0 until nodes.length) {
                val node = nodes.item(i) as Element
                val parent = node.parentNode as? Element
                val key = when {
                    node.tagName == "string" -> node.getAttribute("name")
                    node.tagName == "item" && parent?.tagName == "plurals" ->
                        "${parent.getAttribute("name")}[${node.getAttribute("quantity")}]"
                    else -> continue
                }
                val id = "$path:$key"
                val value = node.textContent

                assertFalse("$id contains a banned word", banned.containsMatchIn(value))
                assertFalse("$id contains !", '!' in value)

                when (id) {
                    in placeholders -> {
                        assertEquals("$id must remain the empty-value glyph", "—", value)
                        seenExceptions += id
                    }
                    in debugEmDashes -> {
                        assertEquals("$id changed its developer-only exception", debugEmDashes.getValue(id), value.count { it == '—' })
                        seenExceptions += id
                    }
                    else -> assertFalse("$id contains a prose em-dash", '—' in value)
                }
            }
        }
        assertEquals(placeholders + debugEmDashes.keys, seenExceptions)
    }
}
