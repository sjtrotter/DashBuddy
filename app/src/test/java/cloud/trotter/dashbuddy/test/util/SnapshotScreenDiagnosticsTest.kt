package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1147 review W2 — the X-Ray surfaces the handles the new node predicates can anchor on. */
class SnapshotScreenDiagnosticsTest {

    @Test
    fun `an id-less node whose only handles are the new fields is a candidate in every category`() {
        val tree = UiNode(
            children = listOf(
                UiNode(paneTitle = "Decline offer?"),
                UiNode(clickActionLabel = "Accept offer", roleDescription = "Button"),
                UiNode(hintText = "Search"),
            ),
        )
        val report = SnapshotScreenDiagnostics.categorize(tree).toMap()
        assertEquals(listOf("Decline offer?"), report["🪟 Pane (hasPaneTitle)"])
        assertEquals(listOf("Accept offer"), report["👆 ClickLabel (hasClickActionLabel)"])
        assertEquals(listOf("Button"), report["🎭 Role (hasRoleDescription)"])
        assertEquals(listOf("Search"), report["💬 Hint (hasHintText)"])
    }

    @Test
    fun `the legacy categories are unchanged and empty categories are omitted`() {
        val tree = UiNode(text = "Hi", children = listOf(UiNode(viewIdResourceName = "app:id/x", text = "Hi")))
        val report = SnapshotScreenDiagnostics.categorize(tree)
        assertEquals(listOf("🔤 Text" to listOf("Hi"), "🆔 IDs " to listOf("app:id/x")), report)
        assertTrue(SnapshotScreenDiagnostics.categorize(UiNode()).isEmpty())
    }
}
