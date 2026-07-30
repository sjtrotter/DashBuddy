package cloud.trotter.dashbuddy.ui.main.setup.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #944 — the permission gate's queue projection, hoisted out of composition into
 * [missingPermissions]. It used to be a `remember(five booleans)` block inside
 * `PermissionsBottomSheet`, i.e. untestable; these pin the two things the sheet depends on — that
 * the ask-order is fixed (the sheet renders the head of this list, one card at a time) and that
 * "nothing missing" is a real empty list, which is the edge that closes the gate.
 */
class PermissionsUiStateTest {

    private fun poll(
        accessibility: Boolean = true,
        listener: Boolean = true,
        location: Boolean = true,
        postNotifications: Boolean = true,
        bubbles: Boolean = true,
    ) = missingPermissions(
        accessibilityGranted = accessibility,
        notificationListenerGranted = listener,
        locationGranted = location,
        postNotificationsGranted = postNotifications,
        bubblesGranted = bubbles,
    )

    @Test
    fun `everything granted leaves an empty queue`() {
        val missing = poll()

        assertEquals(emptyList<PermissionType>(), missing)
        assertTrue(PermissionsUiState(missing).allGranted)
    }

    @Test
    fun `missing permissions queue in the fixed ask-order`() {
        val missing = poll(
            accessibility = false,
            listener = false,
            location = false,
            postNotifications = false,
            bubbles = false,
        )

        assertEquals(
            listOf(
                PermissionType.Accessibility,
                PermissionType.NotificationListener,
                PermissionType.Location,
                PermissionType.PostNotifications,
                PermissionType.Bubbles,
            ),
            missing,
        )
        // The head is the card the sheet shows first.
        assertEquals(PermissionType.Accessibility, missing.first())
        assertFalse(PermissionsUiState(missing).allGranted)
    }

    @Test
    fun `granting the head advances the queue to the next card`() {
        val first = poll(accessibility = false, location = false)
        assertEquals(PermissionType.Accessibility, first.first())

        // The re-poll a resume (or launcher result) triggers, with accessibility now granted.
        val second = poll(location = false)

        assertEquals(listOf(PermissionType.Location), second)
    }

    @Test
    fun `each permission maps to its own card`() {
        assertEquals(listOf(PermissionType.Accessibility), poll(accessibility = false))
        assertEquals(listOf(PermissionType.NotificationListener), poll(listener = false))
        assertEquals(listOf(PermissionType.Location), poll(location = false))
        assertEquals(listOf(PermissionType.PostNotifications), poll(postNotifications = false))
        assertEquals(listOf(PermissionType.Bubbles), poll(bubbles = false))
    }

    @Test
    fun `state defaults to nothing missing`() {
        assertTrue(PermissionsUiState().allGranted)
        assertEquals(emptyList<PermissionType>(), PermissionsUiState().missing)
    }
}
