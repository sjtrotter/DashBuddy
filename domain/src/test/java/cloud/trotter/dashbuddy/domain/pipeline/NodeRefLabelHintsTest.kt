package cloud.trotter.dashbuddy.domain.pipeline

import cloud.trotter.dashbuddy.domain.model.accessibility.BoundingBox
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1093 — the bind-time label hints a bounds-derived tap candidate must carry at fire time.
 * Hashes only (the ref rides the journal/snapshots), letter-bearing labels only (an amount repeats
 * across a receipt), and EVERY hint required (one shared label is not identity).
 */
class NodeRefLabelHintsTest {

    private fun ref(vararg labels: String) = NodeRef(
        viewIdSuffix = null, text = null, classNameHint = "android.view.View",
        boundsInScreen = BoundingBox(0, 0, 10, 10), pathFingerprint = "fp",
        labelHintHashes = labels.mapNotNull(NodeRef::hintHash),
    )

    @Test
    fun `numeric or letter-less labels yield no hint`() {
        assertNull(NodeRef.hintKeyOrNull("\$40.57"))
        assertNull(NodeRef.hintKeyOrNull("799"))
        assertNull(NodeRef.hintKeyOrNull("   "))
        assertNull(NodeRef.hintHash("1 out of 3".filter { !it.isLetter() }))
        assertEquals("this offer", NodeRef.hintKeyOrNull("  This Offer  "))
    }

    @Test
    fun `agreement needs every hint, normalized, and never a hint-less ref`() {
        val r = ref("This offer", "Expand", "\$40.57")
        assertEquals("the amount contributed no hint", 2, r.labelHintHashes.size)
        assertTrue(r.agreesWithLabels(listOf("this OFFER ", "Expand", "\$40.57")))
        assertFalse("one of two hints is not identity", r.agreesWithLabels(listOf("This offer", "\$40.57")))
        assertFalse("a shared amount is not identity", r.agreesWithLabels(listOf("This dash so far", "\$40.57")))
        assertFalse(ref().agreesWithLabels(listOf("This offer", "Expand")))
    }

    @Test
    fun `the serialized ref carries hashes, never the label text`() {
        val json = Json.encodeToString(NodeRef.serializer(), ref("This offer", "123 Main St"))
        assertFalse(json.contains("This offer", ignoreCase = true))
        assertFalse(json.contains("Main St", ignoreCase = true))
        assertTrue(json.contains("labelHintHashes"))
        // A pre-#1093 ref (no field) still decodes.
        val legacy = Json.decodeFromString(NodeRef.serializer(),
            """{"viewIdSuffix":null,"text":null,"classNameHint":null,"boundsInScreen":{"left":0,"top":0,"right":1,"bottom":1},"pathFingerprint":"fp"}""")
        assertTrue(legacy.labelHintHashes.isEmpty())
        assertFalse(legacy.agreesWithLabels(listOf("anything")))
    }
}
