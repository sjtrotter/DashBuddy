package cloud.trotter.dashbuddy.core.datastore.vehicle

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class VehicleDataSourceTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun `save writes list and active id together`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("vehicles.preferences_pb") })
        val source = VehicleDataSource(store)
        val emissions = async(UnconfinedTestDispatcher(testScheduler)) { source.stored.take(2).toList() }
        source.stored.first()
        source.save("list", "civic")
        assertEquals(listOf(VehicleDataSource.StoredVehicles(null, null), VehicleDataSource.StoredVehicles("list", "civic")), emissions.await())
        assertEquals("list", source.vehiclesJson.first())
        assertEquals("civic", source.activeVehicleId.first())
        source.clear()
        assertEquals(VehicleDataSource.StoredVehicles(null, null), source.stored.first())
    }
}
