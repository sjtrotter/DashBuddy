package cloud.trotter.dashbuddy.ui.main.ratings

import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.domain.model.ratings.RatingsSnapshot
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * #316 — the Ratings screen must project the focused platform's RatingsSnapshot
 * (already in app state) and stay null-safe when no ratings have been observed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RatingsViewModelTest {

    private val stateManager: StateManagerV2 = mock()

    private fun stateWith(ratings: RatingsSnapshot?): AppState = AppState(
        regions = Regions(
            flow = FlowRegion(flow = Flow.Idle, activePlatform = Platform.DoorDash),
            platforms = mapOf(
                Platform.DoorDash to PlatformRegion(
                    platform = Platform.DoorDash,
                    ratings = ratings,
                ),
            ),
        ),
    )

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `maps a snapshot onto the ui state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val snapshot = RatingsSnapshot(
            capturedAt = 42L,
            acceptanceRate = 71.0,
            completionRate = 99.0,
            onTimeRate = 95.0,
            customerRating = 4.97,
            deliveriesLast30Days = 120,
            lifetimeDeliveries = 812,
            originalItemsFoundRate = 88.0,
        )
        whenever(stateManager.state).thenReturn(MutableStateFlow(stateWith(snapshot)))

        val viewModel = RatingsViewModel(stateManager)
        val job = launch { viewModel.uiState.collect {} }
        testScheduler.advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertTrue(ui.hasData)
        assertEquals("DoorDash", ui.platformName)
        assertEquals(4.97, ui.customerRating!!, 1e-9)
        assertEquals(95.0, ui.onTimeRate!!, 1e-9)
        assertEquals(812, ui.lifetimeDeliveries)
        assertTrue(ui.hasShoppingQuality) // originalItemsFoundRate present
        job.cancel()
    }

    @Test
    fun `null ratings yields no-data state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        whenever(stateManager.state).thenReturn(MutableStateFlow(stateWith(null)))

        val viewModel = RatingsViewModel(stateManager)
        val job = launch { viewModel.uiState.collect {} }
        testScheduler.advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertFalse(ui.hasData)
        assertEquals("DoorDash", ui.platformName) // still labelled for the empty hint
        assertNull(ui.customerRating)
        assertFalse(ui.hasShoppingQuality)
        job.cancel()
    }
}
