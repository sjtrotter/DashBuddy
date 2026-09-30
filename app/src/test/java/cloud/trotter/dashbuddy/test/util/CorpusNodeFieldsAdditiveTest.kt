package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.domain.capture.dto.UiNodeDto
import cloud.trotter.dashbuddy.domain.capture.schema.UiNodeSchema
import cloud.trotter.dashbuddy.domain.capture.toDomain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1147 review X4 — the #1147 node fields are ADDITIVE for the committed corpus: every node tree in
 * every committed snapshot fixture decodes and re-encodes (through the production `UiNodeSchema`)
 * without gaining any of the new wire keys on ANY node. That is the property "every fixture stays
 * byte-identical" rests on: the new keys default-omit, so no committed frame, golden or replay
 * session can change shape because of them. `INBOX/` and the `UNKNOWN/` staging area are
 * uncommitted and skipped; the committed `UNKNOWN/negative/` corpus is included.
 */
class CorpusNodeFieldsAdditiveTest {

    private val newKeys = setOf(
        "pane", "role", "hint", "tooltip", "error", "clickLabel", "uid",
        "visible", "focusable", "srFocusable", "checkable", "selected", "heading", "live",
        "collRows", "collCols", "itemRow", "itemCol",
    )
    private val lenient = Json { ignoreUnknownKeys = true }
    private val root = File("src/test/resources/snapshots")

    private fun committed(f: File): Boolean {
        val rel = f.relativeTo(root).invariantSeparatorsPath
        if (rel.startsWith("INBOX/")) return false
        if (rel.startsWith("UNKNOWN/") && !rel.startsWith("UNKNOWN/negative/")) return false
        return f.name != "approved-parse-output.json"
    }

    /** The top-most JSON objects that are node trees (carry an object `bounds`); a subtree is its root's. */
    private fun nodeTrees(e: JsonElement, out: MutableList<JsonObject>) {
        when (e) {
            is JsonObject ->
                if (e["bounds"] is JsonObject) out += e else e.values.forEach { nodeTrees(it, out) }
            is JsonArray -> e.forEach { nodeTrees(it, out) }
            else -> {}
        }
    }

    private fun keysAnywhere(e: JsonElement, out: MutableSet<String>) {
        when (e) {
            is JsonObject -> { out += e.keys; e.values.forEach { keysAnywhere(it, out) } }
            is JsonArray -> e.forEach { keysAnywhere(it, out) }
            else -> {}
        }
    }

    @Test
    fun `no committed fixture gains a new node key on decode then encode`() {
        val files = root.walkTopDown().filter { it.isFile && it.extension == "json" && committed(it) }.toList()
        var trees = 0
        val offenders = mutableListOf<String>()
        for (file in files) {
            val found = mutableListOf<JsonObject>()
            nodeTrees(Json.parseToJsonElement(file.readText()), found)
            for (tree in found) {
                val node = lenient.decodeFromJsonElement(UiNodeDto.serializer(), tree).toDomain()
                val keys = mutableSetOf<String>()
                keysAnywhere(Json.parseToJsonElement(UiNodeSchema.serialize(node)), keys)
                val gained = keys intersect newKeys
                if (gained.isNotEmpty()) offenders += "${file.relativeTo(root)}: $gained"
                trees++
            }
        }
        assertTrue("the corpus walk must not be vacuous (files=${files.size}, trees=$trees)", files.size > 800 && trees > 800)
        assertTrue("fixtures gained #1147 keys:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
