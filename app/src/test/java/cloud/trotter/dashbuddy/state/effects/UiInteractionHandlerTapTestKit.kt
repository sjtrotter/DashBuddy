package cloud.trotter.dashbuddy.state.effects

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper.TreeLimits
import cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper.toUiNode
import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.model.accessibility.BoundingBox
import cloud.trotter.dashbuddy.domain.pipeline.NodeRef
import cloud.trotter.dashbuddy.domain.pipeline.UiTextBounds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * #1149 — the shared harness for the tap-resolution tests (review T10 split the 1 100-line
 * `UiInteractionHandlerOwnerTest` by concern): mocked live nodes whose children's `parent` points back
 * at them, window roots, handlers over one enumeration ([AccessibilitySource.LiveRoots]), the receipt
 * row + expand ref, the confirm-decline id ref, and the production bind ([NodeRef.bindHintsOf] over a
 * mapped node).
 */
abstract class UiInteractionHandlerTapTestKit {

    protected val pkg = "com.doordash.driverapp"

    protected val titleId = "com.doordash.driverapp:id/textView_prism_button_title"

    /** A mocked live node whose children's `parent` points back at it (the owner walk climbs it). */
    @Suppress("DEPRECATION") // getActions(): the legacy bitmask the P6 predicate reads
    protected fun view(
        cls: String = "android.view.View", clickable: Boolean = false, advertisesClick: Boolean = false,
        bounds: Rect = Rect(0, 0, 10, 10), text: String? = null, desc: String? = null,
        children: List<AccessibilityNodeInfo> = emptyList(), refreshes: Boolean = true,
        packageName: String = pkg,
    ): AccessibilityNodeInfo {
        val node = mock<AccessibilityNodeInfo>()
        whenever(node.packageName).thenReturn(packageName)
        whenever(node.className).thenReturn(cls)
        whenever(node.text).thenReturn(text)
        whenever(node.contentDescription).thenReturn(desc)
        whenever(node.isClickable).thenReturn(clickable)
        whenever(node.actions).thenReturn(if (advertisesClick) AccessibilityNodeInfo.ACTION_CLICK else 0) // P6: the bitmask
        whenever(node.actionList).thenReturn(
            if (advertisesClick) listOf(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK) else emptyList(),
        )
        whenever(node.childCount).thenReturn(children.size)
        whenever(node.getChild(any())).thenAnswer { children.getOrNull(it.getArgument(0)) }
        for (c in children) whenever(c.parent).thenReturn(node)
        whenever(node.getBoundsInScreen(any())).thenAnswer { (it.arguments[0] as Rect).set(bounds) }
        whenever(node.performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))).thenReturn(true)
        whenever(node.refresh()).thenReturn(refreshes)
        return node
    }

    /** A cheap leaf for budget tests (thousands of them): only what the walk reads. */
    protected fun filler(): AccessibilityNodeInfo = mock<AccessibilityNodeInfo>().also { whenever(it.packageName).thenReturn(pkg) }

    protected fun windowRoot(vararg children: AccessibilityNodeInfo, byId: List<AccessibilityNodeInfo> = emptyList()): AccessibilityNodeInfo {
        val root = view(cls = "android.widget.FrameLayout", bounds = Rect(0, 0, 1080, 2400), children = children.toList())
        whenever(root.packageName).thenReturn(pkg)
        whenever(root.findAccessibilityNodeInfosByViewId(eq(titleId))).thenReturn(byId)
        return root
    }

    protected fun handler(root: AccessibilityNodeInfo): UiInteractionHandler {
        val source = mock<AccessibilitySource> {
            on { getLiveWindowRoots() } doReturn AccessibilitySource.LiveRoots(root, listOf(root))
        }
        return UiInteractionHandler(source)
    }

    protected fun AccessibilityNodeInfo.clicks(n: Int) = verify(this, times(n)).performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))

    protected fun AccessibilityNodeInfo.neverClicked() = verify(this, never()).performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))

    protected val idRef = NodeRef(
        viewIdSuffix = titleId, text = null, classNameHint = null,
        boundsInScreen = BoundingBox(0, 0, 10, 10), pathFingerprint = "",
    )

    protected suspend fun confirmDecline(h: UiInteractionHandler, ref: NodeRef = idRef) = h.performVerifiedClick(
        ref = ref, expectedPackage = pkg,
        expectation = RuleAction.CONFIRM_DECLINE.verification, description = "confirm decline",
    )

    protected val rowRect = Rect(36, 1774, 1044, 1900)

    protected fun payRow(top: Int = 1774, clickable: Boolean = true, advertisesClick: Boolean = false) = view(
        clickable = clickable, advertisesClick = advertisesClick, bounds = Rect(36, top, 1044, top + 126), children = listOf(
            view(cls = "android.widget.TextView", bounds = Rect(72, top + 40, 241, top + 87), text = "This offer"),
            view(bounds = Rect(250, top + 45, 286, top + 81), desc = "Expand"),
        ),
    )

    protected val expandRef = NodeRef(
        viewIdSuffix = null, text = null, classNameHint = "android.view.View",
        boundsInScreen = BoundingBox(rowRect.left, rowRect.top, rowRect.right, rowRect.bottom), pathFingerprint = "",
        labelHintHashes = listOfNotNull(NodeRef.hintHash("This offer"), NodeRef.hintHash("Expand")),
        labelHintsComplete = true,
        ownerClassHint = "android.view.View", // the id-less row binds itself: bound node = owner (#1149 L2)
    )

    protected suspend fun expand(h: UiInteractionHandler, ref: NodeRef = expandRef) = h.performVerifiedClick(
        ref = ref, expectedPackage = pkg,
        expectation = RuleAction.EXPAND_EARNINGS.verification, description = "expand earnings",
    )

    /** A row whose own title sits at depth 4 — past the shared horizon on BOTH sides. */
    protected fun deepTitledRow(nullChild: Boolean): AccessibilityNodeInfo {
        val deep = view(children = listOf(view(children = listOf(view(children = listOf(view(text = "Full breakdown")))))))
        val row = view(clickable = true, bounds = Rect(36, 1374, 1044, 1500), children = listOf(
            view(cls = "android.widget.TextView", text = "This offer"), view(desc = "Expand"), deep,
        ))
        if (nullChild) whenever(row.childCount).thenReturn(4) // slot 3 (depth 1) advertises a child that reads null
        return row
    }

    protected fun handler(roots: List<AccessibilityNodeInfo>, active: AccessibilityNodeInfo, unreadableWindows: Int = 0): UiInteractionHandler {
        val source = mock<AccessibilitySource> {
            on { getLiveWindowRoots() } doReturn AccessibilitySource.LiveRoots(active, roots, unreadableWindows)
        }
        return UiInteractionHandler(source)
    }

    /** A row whose info chevron ADVERTISES ACTION_CLICK without the flag (the Compose shape). */
    protected fun rowWithActionOnlyChevron(top: Int) = view(clickable = true, bounds = Rect(36, top, 1044, top + 126), children = listOf(
        view(cls = "android.widget.TextView", text = "This offer"),
        view(desc = "Expand"),
        view(clickable = false, advertisesClick = true, desc = "Details"),
    ))

    /** Bind the ref the production way: native mapping → the :domain label horizon (what Ruleset.buildNodeRef hashes). */
    protected fun bindRef(live: AccessibilityNodeInfo): NodeRef = bindRefOf(live.toUiNode()!!)

    /** The production bind: NodeRef.bindHintsOf over a mapped node (what Ruleset.buildNodeRef hashes). */
    protected fun bindRefOf(bound: cloud.trotter.dashbuddy.domain.model.accessibility.UiNode): NodeRef {
        val h = NodeRef.bindHintsOf(bound)
        return expandRef.copy(labelHintHashes = h.labelHintHashes, labelHintsComplete = h.complete, ownerClassHint = h.ownerClassHint)
    }
}
