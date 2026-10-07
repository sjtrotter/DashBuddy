package cloud.trotter.dashbuddy.domain.capture

import cloud.trotter.dashbuddy.domain.capture.schema.UiNodeSchema
import cloud.trotter.dashbuddy.domain.model.accessibility.BoundingBox
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * #1147 — the TalkBack-study node fields are ADDITIVE on the wire: every one round-trips, a node
 * that leaves them at their defaults serializes exactly as before (so the committed corpus stays
 * byte-identical), they take part in node equality, and they move NO frame-identity hash.
 * Fixed old/rich wire vectors cover file decoding, both bounds formats, and nested nodes without
 * coupling this schema contract to the size of the screen-classification corpus.
 */
class UiNodeNewFieldsRoundTripTest {

    private val legacyNode = UiNode(
        text = "Accept",
        contentDescription = "Accept offer",
        stateDescription = "on",
        viewIdResourceName = "com.example:id/accept",
        className = "android.widget.Button",
        isClickable = true,
        isEnabled = true,
        isChecked = 1,
        boundsInScreen = BoundingBox(1, 2, 3, 4),
        children = listOf(UiNode(text = "child", boundsInScreen = BoundingBox(1, 2, 3, 4))),
    )

    private val richNode = legacyNode.copy(
        paneTitle = "Offer",
        roleDescription = "Button",
        hintText = "hint",
        tooltipText = "tip",
        errorText = "err",
        clickActionLabel = "Accept this offer",
        uniqueId = "uid-1",
        isVisibleToUser = false,
        isFocusable = true,
        isScreenReaderFocusable = true,
        isCheckable = true,
        isSelected = true,
        isHeading = true,
        liveRegion = 2,
        collectionRows = 5,
        collectionCols = 1,
        itemRow = 3,
        itemCol = 0,
    )

    private fun vector(name: String): String = requireNotNull(
        javaClass.getResource("/ui-node-schema/$name.json"),
    ).readText()

    @Test
    fun `old schema file decodes nested nodes without gaining new fields`() {
        val decoded = UiNodeSchema.deserialize(vector("legacy"))
        assertEquals(legacyNode, decoded)
        assertEquals(legacyNode.children, decoded.children)
        // The file mixes legacy string bounds and object bounds; encoding normalizes both.
        // The defaults/key-set test below pins that this expected tree has no new wire keys.
        assertEquals(UiNodeSchema.serialize(legacyNode), UiNodeSchema.serialize(decoded))
    }

    @Test
    fun `rich schema file preserves new fields on root and child`() {
        val wire = vector("rich")
        val expected = richNode.copy(children = listOf(
            legacyNode.children.single().copy(paneTitle = "Child pane", isFocusable = true),
        ))
        val decoded = UiNodeSchema.deserialize(wire)
        assertEquals(expected, decoded)
        assertEquals(expected.children, decoded.children)
        assertEquals(
            Json.parseToJsonElement(wire),
            Json.parseToJsonElement(UiNodeSchema.serialize(decoded)),
        )
    }

    @Test
    fun `every new field survives the DTO round trip`() {
        val back = UiNodeSchema.deserialize(UiNodeSchema.serialize(richNode))
        assertEquals(richNode, back)
        // equals is node-local; pin every new field explicitly too.
        assertEquals(richNode.paneTitle, back.paneTitle)
        assertEquals(richNode.roleDescription, back.roleDescription)
        assertEquals(richNode.hintText, back.hintText)
        assertEquals(richNode.tooltipText, back.tooltipText)
        assertEquals(richNode.errorText, back.errorText)
        assertEquals(richNode.clickActionLabel, back.clickActionLabel)
        assertEquals(richNode.uniqueId, back.uniqueId)
        assertEquals(richNode.isVisibleToUser, back.isVisibleToUser)
        assertEquals(richNode.isFocusable, back.isFocusable)
        assertEquals(richNode.isScreenReaderFocusable, back.isScreenReaderFocusable)
        assertEquals(richNode.isCheckable, back.isCheckable)
        assertEquals(richNode.isSelected, back.isSelected)
        assertEquals(richNode.isHeading, back.isHeading)
        assertEquals(richNode.liveRegion, back.liveRegion)
        assertEquals(richNode.collectionRows, back.collectionRows)
        assertEquals(richNode.collectionCols, back.collectionCols)
        assertEquals(richNode.itemRow, back.itemRow)
        assertEquals(richNode.itemCol, back.itemCol)
        assertEquals(richNode.children, back.children)
    }

    @Test
    fun `a node at the defaults serializes only the pre-1147 keys`() {
        val obj = Json.parseToJsonElement(UiNodeSchema.serialize(legacyNode)).jsonObject
        assertEquals(
            setOf("text", "desc", "state", "id", "class", "isClickable", "isEnabled", "isChecked", "bounds", "children"),
            obj.keys,
        )
        val child = Json.parseToJsonElement(UiNodeSchema.serialize(legacyNode.children.single())).jsonObject
        assertEquals(setOf("text", "bounds"), child.keys)
    }

    @Test
    fun `isEditable serializes as the editable key only when true and round-trips (#919)`() {
        val on = legacyNode.copy(isEditable = true)
        val obj = Json.parseToJsonElement(UiNodeSchema.serialize(on)).jsonObject
        assertEquals(true, obj["editable"]?.jsonPrimitive?.boolean)
        assertEquals(on, UiNodeSchema.deserialize(UiNodeSchema.serialize(on)))
        assertTrue(UiNodeSchema.deserialize(UiNodeSchema.serialize(on)).isEditable)
        assertTrue(UiNodeSchema.deserialize(UiNodeSchema.serialize(on)).isTextInput)
        // false stays omitted — the pre-#919 key set is pinned by the test above.
        assertEquals(null, Json.parseToJsonElement(UiNodeSchema.serialize(legacyNode)).jsonObject["editable"])
    }

    @Test
    fun `a pre-1147 envelope deserializes to the dominant defaults`() {
        val legacyJson = """{"text":"x","bounds":{"left":0,"top":0,"right":1,"bottom":1}}"""
        val n = UiNodeSchema.deserialize(legacyJson)
        assertEquals(UiNode(text = "x", boundsInScreen = BoundingBox(0, 0, 1, 1)), n)
        assertEquals(true, n.isVisibleToUser)
        assertEquals(-1, n.collectionRows)
        assertEquals(-1, n.itemCol)
        assertEquals(0, n.liveRegion)
        // and re-serializes byte-identically
        assertEquals(legacyJson, UiNodeSchema.serialize(n))
    }

    @Test
    fun `equals and hashCode include the new fields`() {
        assertNotEquals(legacyNode, richNode)
        for (variant in listOf(
            legacyNode.copy(paneTitle = "p"),
            legacyNode.copy(clickActionLabel = "c"),
            legacyNode.copy(uniqueId = "u"),
            legacyNode.copy(isVisibleToUser = false),
            legacyNode.copy(isSelected = true),
            legacyNode.copy(liveRegion = 1),
            legacyNode.copy(itemRow = 0),
            legacyNode.copy(isEditable = true), // #919
        )) {
            assertNotEquals(legacyNode, variant)
            assertNotEquals(legacyNode.hashCode(), variant.hashCode())
        }
        assertEquals(richNode.hashCode(), richNode.copy().hashCode())
    }

    @Test
    fun `frame-identity hashes and allText are unchanged when only new fields differ`() {
        assertEquals(legacyNode.structuralHash, richNode.structuralHash)
        assertEquals(legacyNode.stableHash, richNode.stableHash)
        assertEquals(legacyNode.contentHash, richNode.contentHash)
        assertEquals(legacyNode.allText, richNode.allText)
        assertEquals(legacyNode.allTextLowerJoined, richNode.allTextLowerJoined)
    }
}
