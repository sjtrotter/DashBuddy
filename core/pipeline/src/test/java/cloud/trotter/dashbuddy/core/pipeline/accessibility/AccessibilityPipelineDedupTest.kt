package cloud.trotter.dashbuddy.core.pipeline.accessibility

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AccessibilityPipelineDedupTest {

    private fun primaryActionButton(): UiNode = UiNode(
        viewIdResourceName = "com.doordash.driverapp:id/primary_action_button",
        className = "android.widget.Button",
        isClickable = true,
    )

    private fun otherButton(): UiNode = UiNode(
        viewIdResourceName = "com.doordash.driverapp:id/secondary_action_button",
        className = "android.widget.Button",
        isClickable = true,
    )

    @Test
    fun `same node and same screen produces same hash`() {
        val a = clickDedupHash(primaryActionButton(), "offer_popup_confirm_decline")
        val b = clickDedupHash(primaryActionButton(), "offer_popup_confirm_decline")
        assertEquals(a, b)
    }

    @Test
    fun `same node on different screens produces different hash`() {
        // The bug this fix addresses: a primary_action_button tapped on
        // pickup_pre_arrival ("Arrived at store") and again on
        // offer_popup_confirm_decline ("Decline offer") must not collide.
        val onPickup = clickDedupHash(primaryActionButton(), "pickup_pre_arrival")
        val onConfirmDecline = clickDedupHash(primaryActionButton(), "offer_popup_confirm_decline")
        assertNotEquals(onPickup, onConfirmDecline)
    }

    @Test
    fun `same node with null screen on both produces same hash`() {
        val a = clickDedupHash(primaryActionButton(), null)
        val b = clickDedupHash(primaryActionButton(), null)
        assertEquals(a, b)
    }

    @Test
    fun `different nodes on same screen produce different hash`() {
        val primary = clickDedupHash(primaryActionButton(), "offer_popup")
        val other = clickDedupHash(otherButton(), "offer_popup")
        assertNotEquals(primary, other)
    }
}
