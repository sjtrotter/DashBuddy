package cloud.trotter.log.gig.core.data.fuel

import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.location.LocationDataSource
import cloud.trotter.log.gig.domain.model.location.UserLocation
import cloud.trotter.log.gig.domain.model.vehicle.FuelType
import cloud.trotter.log.gig.domain.provider.FuelPriceDataSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** #1478: a saved lookup carries the injected clock's time; a failed lookup writes nothing. */
class FuelPriceRepositoryTest {
    private val clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)
    private val gas: FuelPriceDataSource = mock()
    private val location: LocationDataSource = mock()
    private val prefs: AppPreferencesRepository = mock()
    private val repository = FuelPriceRepository(gas, location, prefs, clock)
    private val here: UserLocation = mock()

    @Test
    fun `a daily save stamps the price with the injected clock`() = runTest {
        whenever(location.getUserLocation()).thenReturn(here)
        whenever(gas.getFuelPrice(here, FuelType.REGULAR)).thenReturn(Result.success(3.29f))

        assertEquals(3.29f, repository.fetchAndSaveCurrentGasPrice(FuelType.REGULAR).getOrNull())

        verify(prefs).updateGasPrice(3.29f, NOW)
    }

    @Test
    fun `resume auto stamps the price with the injected clock`() = runTest {
        whenever(location.getUserLocation()).thenReturn(here)
        whenever(gas.getFuelPrice(here, FuelType.PREMIUM)).thenReturn(Result.success(4.10f))

        assertEquals(4.10f, repository.fetchAndResumeAutoGasPrice(FuelType.PREMIUM).getOrNull())

        verify(prefs).updateGasPriceAuto(4.10f, NOW)
    }

    @Test
    fun `a failed lookup writes nothing`() = runTest {
        whenever(location.getUserLocation()).thenReturn(here)
        whenever(gas.getFuelPrice(here, FuelType.REGULAR)).thenReturn(Result.failure(IllegalStateException("no key")))

        assertTrue(repository.fetchAndSaveCurrentGasPrice(FuelType.REGULAR).isFailure)
        assertTrue(repository.fetchAndResumeAutoGasPrice(FuelType.REGULAR).isFailure)

        verify(prefs, never()).updateGasPrice(any(), any())
        verify(prefs, never()).updateGasPriceAuto(any(), any())
    }

    @Test
    fun `no location writes nothing`() = runTest {
        whenever(location.getUserLocation()).thenReturn(null)

        assertTrue(repository.fetchAndSaveCurrentGasPrice(FuelType.REGULAR).isFailure)

        verify(prefs, never()).updateGasPrice(any(), any())
    }

    private companion object {
        const val NOW = 1_762_850_000_000L
    }
}
