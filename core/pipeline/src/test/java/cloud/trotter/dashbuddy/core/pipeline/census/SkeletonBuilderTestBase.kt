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

/**
 * Shared fixtures for the #1145 census builder tests (split by ADR step, #1160 review UU10):
 * [SkeletonFieldFilterTest] (per-field steps 1–8), [SkeletonFrameRuleTest] (the frame-level duplicate /
 * containment rule), [SkeletonEnvelopeTest] (envelope + refusals), [SkeletonIdClassGateTest] (id / class
 * gates).
 */
abstract class SkeletonBuilderTestBase {

    protected val meta = ReplayMetadata(
        engineVersion = 7,
        rulesetFormatVersion = 2,
        rulesetReleaseTag = "rules-2026.09.30",
        appVersion = "0.9.0+abc1234",
        deviceFingerprint = "google/panther/panther:16/should/never/leave",
        platformAppVersion = "8.97.8",
    )

    protected val platform = Platform.DoorDash
    protected val day: LocalDate = LocalDate.of(2026, 9, 30)

    /** One field's slot, through the whole builder (the per-field path is private, review CC5). */
    protected fun slot(value: String?, id: String? = null): TextSlot? =
        SkeletonBuilder.build(
            UiNode(className = "android.widget.TextView", viewIdResourceName = id, text = value),
            null, meta, platform, day,
        )!!.root.text["text"]

    protected fun words(n: Int, value: String) = TextSlot(h = CensusHash.of(value), kind = "words:$n")

    /** A FrameLayout of TextViews carrying [texts]. */
    protected fun tree(vararg texts: String) = UiNode(
        className = "android.widget.FrameLayout",
        children = texts.map { UiNode(text = it, className = "android.widget.TextView") },
    ).restoreParents()

    /** A frame with a `customer_name` identity value and id-less siblings; returns the siblings' slots. */
    protected fun beside(identity: String, vararg others: String): List<TextSlot> =
        SkeletonBuilder.build(
            UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row", children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.x:id/customer_name", text = identity),
            ) + others.map { UiNode(className = "android.widget.TextView", text = it) }),
            null, meta, platform, day,
        )!!.root.children.drop(1).map { it.text.getValue("text") }

    /** As [beside], with the identity node's id suffix chosen by the test. */
    protected fun beside2(identityId: String, identity: String, vararg others: String): List<TextSlot> =
        SkeletonBuilder.build(
            UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row", children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/$identityId", text = identity),
            ) + others.map { UiNode(className = "android.widget.TextView", text = it) }),
            null, meta, platform, day,
        )!!.root.children.drop(1).map { it.text.getValue("text") }
}
