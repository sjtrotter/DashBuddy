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
        labelHintsComplete = true,
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

    /** #1149 — the label-only re-find needs the EXACT set: no missing hint, no extra letter-bearing label. */
    @Test
    fun `the exact fingerprint rejects supersets, subsets, hint-less and possibly-truncated refs`() {
        val r = ref("This offer", "Expand")
        assertTrue(r.fingerprintMatches(listOf("this offer", "EXPAND", "\$40.57", "This offer")))
        assertFalse("a parent card with more text is a superset", r.fingerprintMatches(listOf("This offer", "Expand", "Continue dashing")))
        assertFalse("a subset is not the control", r.fingerprintMatches(listOf("This offer")))
        assertFalse(ref().fingerprintMatches(emptyList()))
        // P7: truncation is recorded at bind (labelHintsComplete), not guessed from the size — a
        // complete 6-label set IS provable.
        val full = ref("a1", "b1", "c1", "d1", "e1", "f1")
        assertEquals(NodeRef.MAX_LABEL_HINTS, full.labelHintHashes.size)
        assertTrue(full.fingerprintMatches(listOf("a1", "b1", "c1", "d1", "e1", "f1")))
    }

    private fun ui(text: String? = null, desc: String? = null, clickable: Boolean = false, vararg children: cloud.trotter.dashbuddy.domain.model.accessibility.UiNode) =
        cloud.trotter.dashbuddy.domain.model.accessibility.UiNode(text = text, contentDescription = desc, isClickable = clickable, children = children.toList())

    /**
     * #1149 review I2 — one horizon on both sides: the bind-time hints stop at LABEL_SCAN_DEPTH and at
     * every clickable descendant, exactly like the executor's live scan, so the fingerprint built at
     * bind time is the one the live scan can reproduce.
     */
    @Test
    fun `bind-time hint labels honour the shared depth horizon and clickable ownership`() {
        val deep = ui(null, null, false, ui(null, null, false, ui(null, null, false, ui("Too deep"))))
        val row = ui(null, null, true,
            ui("This offer"),
            ui(null, "Expand"),
            ui("Breakdown", null, true), // a nested control: its label is its own
            deep,
        ).restoreParents()
        val scan = NodeRef.hintLabelsOf(row)
        assertEquals(listOf("This offer", "Expand"), scan.labels)
        assertTrue("the depth bound is the shared horizon, not incompleteness", scan.complete)

        val hints = scan.labels.mapNotNull(NodeRef::hintHash).distinct()
        val bound = ref("This offer", "Expand")
        assertEquals(bound.labelHintHashes, hints)
        // The fire-time scan over the same shape reads the same horizon, so the fingerprint matches.
        assertTrue(bound.fingerprintMatches(listOf("This offer", "Expand")))
    }

    @Test
    fun `bind-time hint labels stop at the shared fetch cap`() {
        val many = ui(null, null, true, *Array(NodeRef.LABEL_SCAN_NODES + 5) { ui("Label $it") }).restoreParents()
        val scan = NodeRef.hintLabelsOf(many)
        assertEquals(NodeRef.LABEL_SCAN_NODES, scan.labels.size)
        assertFalse(scan.complete)
    }

    /** #1149 review J3: an incomplete bind set, or a legacy ref with no completeness field, is never an exact fingerprint. */
    @Test
    fun `completeness rides the ref — incomplete and legacy refs are never exact`() {
        val incomplete = ref("This offer", "Expand").copy(labelHintsComplete = false)
        assertFalse(incomplete.hasExactFingerprint)
        assertFalse(incomplete.fingerprintMatches(listOf("This offer", "Expand")))
        val legacy = Json.decodeFromString(NodeRef.serializer(),
            """{"viewIdSuffix":null,"text":null,"classNameHint":null,"boundsInScreen":{"left":0,"top":0,"right":1,"bottom":1},"pathFingerprint":"fp","labelHintHashes":["${NodeRef.hintHash("Expand")}"]}""")
        assertFalse(legacy.labelHintsComplete)
        assertFalse(legacy.hasExactFingerprint)
        assertTrue(ref("This offer", "Expand").hasExactFingerprint)
    }

    /** #1149 review L7: the count pre-check agrees with the hashed comparison; the subset test includes ∅. */
    @Test
    fun `visible consistency is a subset test and the fingerprint count pre-check is exact`() {
        val r = ref("This offer", "Expand")
        assertTrue(r.visibleConsistentWith(emptyList()))
        assertTrue(r.visibleConsistentWith(listOf("This offer", "\$40.57")))
        assertFalse(r.visibleConsistentWith(listOf("This offer", "Order details")))
        assertFalse(r.visibleConsistentWith(listOf("a", "b", "c")))
        assertFalse("one key short", r.fingerprintMatches(listOf("This offer")))
        assertTrue("duplicates collapse", r.fingerprintMatches(listOf("This offer", "this offer ", "Expand")))
    }

    /** #1149 review P7: bindHintsOf records truncation — exactly 6 labels is complete, 7 is not. */
    @Test
    fun `bind-time truncation at MAX_LABEL_HINTS is recorded, not guessed`() {
        fun owner(n: Int) = cloud.trotter.dashbuddy.domain.model.accessibility.UiNode(
            className = "android.view.View", isClickable = true,
            children = List(n) { cloud.trotter.dashbuddy.domain.model.accessibility.UiNode(text = "Label ${'a' + it}") },
        ).restoreParents()
        val six = NodeRef.bindHintsOf(owner(NodeRef.MAX_LABEL_HINTS))
        assertTrue(six.complete)
        assertEquals(NodeRef.MAX_LABEL_HINTS, six.labelHintHashes.size)
        val seven = NodeRef.bindHintsOf(owner(NodeRef.MAX_LABEL_HINTS + 1))
        assertFalse(seven.complete)
        assertEquals(NodeRef.MAX_LABEL_HINTS, seven.labelHintHashes.size)
    }
}
