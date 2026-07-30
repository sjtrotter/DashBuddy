package cloud.trotter.dashbuddy.domain.model.accessibility

import cloud.trotter.dashbuddy.domain.capture.dto.BoundingBoxDto
import cloud.trotter.dashbuddy.domain.capture.dto.UiNodeDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #835 — the [UiNodeTextField] scrubbable-fields SSOT.
 *
 * `stateDescription` was captured and serialized but sat outside every scrub
 * layer because each layer hand-listed `text` + `contentDescription`. These pin
 * the enumeration that closed it: its membership, its wire names, and the
 * deliberate NON-membership of the recognition-side [UiNode.allText].
 */
class UiNodeScrubbableFieldsTest {

    private fun node(
        text: String? = null,
        desc: String? = null,
        state: String? = null,
    ) = UiNode(text = text, contentDescription = desc, stateDescription = state)

    /**
     * The value a fully-populated [UiNodeDto] carries in [field]. An exhaustive
     * `when` on purpose: a new [UiNodeTextField] entry breaks compilation here
     * until it is wired into the DTO check below, so the wire-name pin can never
     * silently skip a field.
     */
    private fun dtoSentinel(field: UiNodeTextField): String = when (field) {
        UiNodeTextField.TEXT -> "TEXT-VALUE"
        UiNodeTextField.CONTENT_DESCRIPTION -> "DESC-VALUE"
        UiNodeTextField.STATE_DESCRIPTION -> "STATE-VALUE"
    }

    @Test
    fun `scrubbableStrings enumerates every serialized string field of the node`() {
        val n = node(text = "T", desc = "D", state = "S")
        assertEquals(
            listOf(
                UiNodeTextField.TEXT to "T",
                UiNodeTextField.CONTENT_DESCRIPTION to "D",
                UiNodeTextField.STATE_DESCRIPTION to "S",
            ),
            n.scrubbableStrings(),
        )
    }

    @Test
    fun `scrubbableStrings reports raw values including nulls`() {
        val n = node(text = "T")
        assertEquals(listOf("T", null, null), n.scrubbableStrings().map { it.second })
    }

    @Test
    fun `mapScrubbableStrings rewrites every field and leaves the rest of the node alone`() {
        val original = UiNode(
            text = "T",
            contentDescription = "D",
            stateDescription = "S",
            viewIdResourceName = "com.app:id/thing",
            className = "android.widget.TextView",
            isClickable = true,
            boundsInScreen = BoundingBox(1, 2, 3, 4),
            children = listOf(node(text = "child")),
        )

        val masked = original.mapScrubbableStrings { if (it != null) "[x]" else null }

        assertEquals(listOf("[x]", "[x]", "[x]"), masked.scrubbableStrings().map { it.second })
        // Non-string identity fields and the children list are untouched.
        assertEquals(original.viewIdResourceName, masked.viewIdResourceName)
        assertEquals(original.className, masked.className)
        assertEquals(original.isClickable, masked.isClickable)
        assertEquals(original.boundsInScreen, masked.boundsInScreen)
        assertEquals(original.children, masked.children)
        // Copy semantics: the source node is never mutated.
        assertEquals("T", original.text)
    }

    @Test
    fun `mapScrubbableStrings sees nulls so a transform can distinguish absent from empty`() {
        val seen = mutableListOf<String?>()
        node(text = "T", state = "S").mapScrubbableStrings { seen.add(it); it }
        assertEquals(listOf("T", null, "S"), seen)
    }

    @Test
    fun `allScrubbableText collects every non-blank field across the whole tree`() {
        val tree = UiNode(
            text = "root",
            stateDescription = "  ", // blank -> dropped, same policy as allText
            children = listOf(
                node(desc = "childDesc", state = "childState"),
                node(text = "leaf"),
            ),
        )
        assertEquals(
            listOf("root", "childDesc", "childState", "leaf"),
            tree.allScrubbableText(),
        )
    }

    /**
     * Decision 1 of the #835 plan, pinned: recognition must NOT change. `allText`
     * is what every rule predicate matches on, so folding `stateDescription` into
     * it would shift classification corpus-wide — the privacy layers read
     * [UiNode.allScrubbableText] instead.
     */
    @Test
    fun `allText deliberately excludes stateDescription while allScrubbableText includes it`() {
        val tree = UiNode(
            text = "visible",
            contentDescription = "described",
            stateDescription = "stateful",
        )
        assertEquals(listOf("visible", "described"), tree.allText)
        assertFalse("recognition must not see state", tree.allTextLowerJoined.contains("stateful"))
        assertTrue("the scrub layers must see state", tree.allScrubbableText().contains("stateful"))
    }

    /**
     * [UiNodeTextField.wire] is the capture-envelope JSON key. Annotation
     * arguments must be compile-time constants, so `UiNodeDto`'s `@SerialName`s
     * cannot reference the enum — this is the tripwire that keeps the two copies
     * from drifting (a JSON-level consumer, the corpus `SnapshotRedactor`, keys
     * off the enum).
     */
    @Test
    fun `wire names match the capture DTO serial names`() {
        val dto = UiNodeDto(
            text = dtoSentinel(UiNodeTextField.TEXT),
            contentDescription = dtoSentinel(UiNodeTextField.CONTENT_DESCRIPTION),
            stateDescription = dtoSentinel(UiNodeTextField.STATE_DESCRIPTION),
            boundsInScreen = BoundingBoxDto(0, 0, 0, 0),
        )
        val obj = Json.parseToJsonElement(Json.encodeToString(UiNodeDto.serializer(), dto)).jsonObject

        for (field in UiNodeTextField.entries) {
            assertEquals(
                "UiNodeDto must serialize ${field.name} under the wire key '${field.wire}'",
                dtoSentinel(field),
                obj[field.wire]?.jsonPrimitive?.content,
            )
            assertEquals(field, UiNodeTextField.fromWire(field.wire))
        }
    }
}
