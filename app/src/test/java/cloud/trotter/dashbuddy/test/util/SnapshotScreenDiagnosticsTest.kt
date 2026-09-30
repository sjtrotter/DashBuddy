package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNodeTextField
import cloud.trotter.dashbuddy.test.util.SnapshotScreenDiagnostics.headerFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1147 review W2/Z7 — the X-Ray surfaces the handles the node predicates can anchor on. */
class SnapshotScreenDiagnosticsTest {

    @Test
    fun `every non-excluded UiNodeTextField entry is an X-Ray category`() {
        val headers = SnapshotScreenDiagnostics.CATEGORIES.map { it.first }
        for (field in UiNodeTextField.entries) {
            assertEquals(
                "$field category presence",
                field !in SnapshotScreenDiagnostics.EXCLUDED_FIELDS,
                headerFor(field) in headers,
            )
        }
        assertTrue(SnapshotScreenDiagnostics.ID_HEADER in headers)
    }

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
        assertEquals(listOf("Decline offer?"), report[headerFor(UiNodeTextField.PANE_TITLE)])
        assertEquals(listOf("Accept offer"), report[headerFor(UiNodeTextField.CLICK_ACTION_LABEL)])
        assertEquals(listOf("Button"), report[headerFor(UiNodeTextField.ROLE_DESCRIPTION)])
        assertEquals(listOf("Search"), report[headerFor(UiNodeTextField.HINT_TEXT)])
    }

    @Test
    fun `text and id still report and empty categories are omitted`() {
        val tree = UiNode(text = "Hi", children = listOf(UiNode(viewIdResourceName = "app:id/x", text = "Hi")))
        val report = SnapshotScreenDiagnostics.categorize(tree)
        assertEquals(
            listOf(headerFor(UiNodeTextField.TEXT) to listOf("Hi"), SnapshotScreenDiagnostics.ID_HEADER to listOf("app:id/x")),
            report,
        )
        assertTrue(SnapshotScreenDiagnostics.categorize(UiNode()).isEmpty())
    }
}
