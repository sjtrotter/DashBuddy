package cloud.trotter.dashbuddy.core.pipeline.census
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder.FilterStep
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder.Outcome
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder.Refusal
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.census.contract.CensusFingerprint
import cloud.trotter.dashbuddy.domain.census.contract.CensusHash
import cloud.trotter.dashbuddy.domain.census.contract.SkeletonSchema
import cloud.trotter.dashbuddy.domain.census.contract.TextSlot
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNodeTextField
import cloud.trotter.dashbuddy.domain.state.Platform
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0011 §1/§8 — the envelope and the refusals: sensitive frame/title, oversize, INVALID_TREE vs BUILD_FAILED, the tree-bound sensitive verdict, stamps, and the built item. Split from `SkeletonBuilderTest` (#1160 review UU10). */
class SkeletonEnvelopeTest : SkeletonBuilderTestBase() {

    @Test
    fun `a sensitive frame yields no skeleton`() {
        val out = SkeletonBuilder.outcome(tree("Transfer out", "\$45.66 available"), null, meta, platform, day)
        assertEquals(Outcome.Refused(Refusal.SENSITIVE_FRAME), out)
        assertNull(SkeletonBuilder.build(tree("Transfer", "\$45.66"), null, meta, platform, day))
    }

    @Test
    fun `a sensitive window title yields no skeleton`() {
        val out = SkeletonBuilder.outcome(tree("Continue"), "DasherDirect", meta, platform, day)
        assertEquals(Outcome.Refused(Refusal.SENSITIVE_TITLE), out)
    }

    @Test
    fun `an oversize item yields no skeleton`() {
        val big = UiNode(
            className = "android.widget.FrameLayout",
            children = (1..3000).map {
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.x:id/row_$it", text = "Row")
            },
        ).restoreParents()
        assertEquals(Outcome.Refused(Refusal.OVERSIZE), SkeletonBuilder.outcome(big, null, meta, platform, day))
    }

    @Test
    fun `the typed API emits the wire forms of platform and day (review GG6)`() {
        val item = SkeletonBuilder.build(tree("Continue"), null, meta, Platform.Uber, LocalDate.of(2026, 2, 3))!!
        assertEquals("uber", item.platform)
        assertEquals("2026-02-03", item.day)
    }

    @Test
    fun `a built skeleton carries the envelope, the title slot and every text field by wire key`() {
        val node = UiNode(
            className = "android.widget.Button",
            viewIdResourceName = "com.x:id/cta",
            text = "Accept",
            contentDescription = "Accept offer",
            stateDescription = "  ",
            hintText = "Jane S",
            uniqueId = "store_Chipotle",
            isClickable = true,
            isEnabled = true,
            isChecked = 2,
        )
        val item = SkeletonBuilder.build(UiNode(children = listOf(node)).restoreParents(), "Offer", meta, platform, day)
        assertNotNull(item!!)
        assertEquals(SkeletonSchema.SCHEMA_ID, item.schemaId)
        assertEquals(1, item.hashDomain)
        assertEquals(SkeletonBuilder.FILTER_REV, item.filterRev)
        assertEquals("8.97.8", item.platformAppVersion)
        assertEquals("0.9.0+abc1234", item.appVersion)
        assertEquals("rules-2026.09.30", item.rulesetReleaseTag)
        assertEquals(7, item.engineVersion)
        assertEquals(2, item.rulesetFormatVersion)
        assertEquals(CensusFingerprint.of(item.root), item.fingerprint)
        assertEquals(words(1, "Offer"), item.windowTitle)
        val child = item.root.children.single()
        assertEquals(true, child.isClickable)
        assertEquals(2, child.isChecked)
        assertEquals(
            mapOf(
                UiNodeTextField.TEXT.wire to words(1, "Accept"),
                UiNodeTextField.CONTENT_DESCRIPTION.wire to words(2, "Accept offer"),
                UiNodeTextField.HINT_TEXT.wire to TextSlot.WITHHELD,
                // uid is a text slot like any other: hashed through the filter, never sent clear.
                UiNodeTextField.UNIQUE_ID.wire to words(1, "store_Chipotle"),
            ),
            child.text,
        )
        val json = SkeletonSchema.serialize(item)
        listOf("Accept", "Chipotle", "Jane", "Offer", "should/never/leave").forEach {
            assertTrue("'$it' must not appear in $json", !json.contains(it))
        }
    }

    @Test
    fun `AA4 - a pathological deep tree is refused, never thrown`() {
        var node = UiNode(className = "android.widget.TextView", text = "Accept")
        repeat(200_000) { node = UiNode(className = "android.widget.ScrollView", viewIdResourceName = "x:id/n", children = listOf(node)) }
        // Never a throw, and never mis-reported as a sensitive frame (review GG3): whichever stage
        // overflows first — the marker scan (its fail-closed sentinel) or anything after it — the
        // outcome is BUILD_FAILED.
        val out = SkeletonBuilder.outcome(node, null, meta, platform, day)
        assertEquals(Outcome.Refused(Refusal.BUILD_FAILED), out)
        assertNull(SkeletonBuilder.build(node, null, meta, platform, day))
    }

    @Test
    fun `AA2 BB1 BB2 - a NUL or malformed UTF-16 in a class or id refuses the tree`() {
        listOf(
            UiNode(className = "android.widget.TextView\u0000N", text = "Accept"),
            // Review BB2: a NUL-bearing ID must refuse too, not be dropped to null by the grammar gate.
            UiNode(className = "android.widget.TextView", viewIdResourceName = "x:id/a\u0000", text = "Accept"),
            // Review BB1: a lone surrogate (class or id) is malformed UTF-16.
            UiNode(className = "\uD800", text = "Accept"),
            UiNode(className = "android.widget.TextView", viewIdResourceName = "x:id/a\uDC00", text = "Accept"),
        ).forEach { bad ->
            val child = UiNode(className = "android.widget.FrameLayout", viewIdResourceName = "x:id/host", children = listOf(bad))
            assertEquals(Outcome.Refused(Refusal.INVALID_TREE), SkeletonBuilder.outcome(child, null, meta, platform, day))
        }
    }

    @Test
    fun `AA7 - Built carries the canonical JSON it measured`() {
        val out = SkeletonBuilder.outcome(tree("Continue"), null, meta, platform, day) as Outcome.Built
        assertEquals(SkeletonSchema.serialize(out.skeleton), out.json)
        assertEquals(out.json.toByteArray(Charsets.UTF_8).size, out.itemBytes)
    }

    @Test
    fun `CC2 - an over-long optional stamp is truncated, never a refusal`() {
        val long = meta.copy(platformAppVersion = "9".repeat(90), rulesetReleaseTag = "r".repeat(70))
        val out = SkeletonBuilder.outcome(tree("Continue"), null, long, platform, day)
        assertTrue("$out", out is Outcome.Built)
        val item = (out as Outcome.Built).skeleton
        assertEquals("9".repeat(64), item.platformAppVersion)
        assertEquals("r".repeat(64), item.rulesetReleaseTag)
        // Review PP7: a truncation that would split a surrogate pair cuts BEFORE it (the stamp survives).
        val split = meta.copy(appVersion = "a".repeat(63) + "\uD83D\uDE97" + "tail")
        assertEquals("a".repeat(63), SkeletonBuilder.build(tree("Continue"), null, split, platform, day)!!.appVersion)
    }

    @Test
    fun `II5 - a builder defect is BUILD_FAILED, never INVALID_TREE`() {
        val defect = SkeletonBuilder.FrameFilter(
            judge = { SkeletonBuilder.withholdingStep(it) },
            unfiltered = { throw IllegalArgumentException("a bad TextSlot") },
        )
        assertEquals(
            Outcome.Refused(Refusal.BUILD_FAILED),
            SkeletonBuilder.outcome(tree("Continue"), null, meta, platform, day, defect),
        )
    }

    @Test
    fun `II6 - a corrupt tri-state is refused as INVALID_TREE, not laundered to unchecked`() {
        val bad = UiNode(className = "android.widget.CheckBox", text = "Leave at door", isChecked = 7)
        assertEquals(Outcome.Refused(Refusal.INVALID_TREE), SkeletonBuilder.outcome(bad, null, meta, platform, day))
    }

    @Test
    fun `PP8 SS2 - a caller verdict is honoured only for its own tree`() {
        val clean = tree("Continue")
        assertEquals(
            Outcome.Refused(Refusal.SENSITIVE_FRAME),
            SkeletonBuilder.outcome(clean, null, meta, platform, day, SkeletonBuilder.SensitiveVerdict.Hit(clean, "shape:x")),
        )
        assertEquals(
            Outcome.Refused(Refusal.BUILD_FAILED),
            SkeletonBuilder.outcome(clean, null, meta, platform, day, SkeletonBuilder.SensitiveVerdict.ScanFailed(clean)),
        )
        assertTrue(SkeletonBuilder.outcome(clean, null, meta, platform, day, SkeletonBuilder.SensitiveVerdict.Clear(clean)) is Outcome.Built)
        assertEquals(SkeletonBuilder.SensitiveVerdict.Clear(clean), SkeletonBuilder.SensitiveVerdict.of(clean, null))
        // SS2: a `Clear` computed on ANOTHER tree (even a structurally identical one) is ignored — the
        // builder scans the banking tree itself and refuses it.
        val banking = tree("Transfer out")
        val lookalike = tree("Transfer out")
        assertEquals(
            Outcome.Refused(Refusal.SENSITIVE_FRAME),
            SkeletonBuilder.outcome(banking, null, meta, platform, day, SkeletonBuilder.SensitiveVerdict.Clear(clean)),
        )
        assertEquals(
            Outcome.Refused(Refusal.SENSITIVE_FRAME),
            SkeletonBuilder.outcome(banking, null, meta, platform, day, SkeletonBuilder.SensitiveVerdict.Clear(lookalike)),
        )
        // With no verdict, the builder scans the tree itself.
        assertEquals(Outcome.Refused(Refusal.SENSITIVE_FRAME), SkeletonBuilder.outcome(tree("Transfer out"), null, meta, platform, day, null))
    }
}
