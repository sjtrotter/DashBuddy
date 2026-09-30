package cloud.trotter.dashbuddy.ui.main

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1151 review MM4 — a deep link that arrives while the debug block is up survives recreation and is
 * delivered exactly once when the shell becomes navigable.
 */
class MainShellViewModelTest {

    @Test
    fun `parked while blocked, recreation, then delivered - exactly one navigation`() {
        val navigations = mutableListOf<String>()

        // Blocked: the intent's route is parked; deliver() is not composed, so nothing navigates.
        val before = SavedStateHandle()
        val vm1 = MainShellViewModel(before)
        vm1.offer("analytics")
        assertTrue(navigations.isEmpty())

        // Recreation: a new ViewModel restored from the saved state (the intent extra is gone).
        val restored = SavedStateHandle(mapOf(MainShellViewModel.KEY_PENDING_ROUTE to before.get<String>(MainShellViewModel.KEY_PENDING_ROUTE)))
        val vm2 = MainShellViewModel(restored)
        assertEquals("analytics", vm2.pendingRoute.value)

        // Allow: navigable — delivered once, then cleared; a second pass delivers nothing.
        assertTrue(vm2.deliver { navigations += it })
        assertFalse(vm2.deliver { navigations += it })
        assertEquals(listOf("analytics"), navigations)
        assertNull(vm2.pendingRoute.value)
    }

    @Test
    fun `a later offer after a delivery still reaches the flow`() {
        val vm = MainShellViewModel(SavedStateHandle())
        vm.offer("analytics")
        vm.deliver {}
        vm.offer("analytics")
        assertEquals("analytics", vm.pendingRoute.value)
    }

    @Test
    fun `a failed navigation keeps the route parked`() {
        val vm = MainShellViewModel(SavedStateHandle())
        vm.offer("settings")
        runCatching { vm.deliver { error("nav threw") } }
        assertEquals("settings", vm.pendingRoute.value)
    }

    @Test
    fun `a null offer is ignored`() {
        val vm = MainShellViewModel(SavedStateHandle())
        vm.offer(null)
        assertNull(vm.pendingRoute.value)
    }
}
