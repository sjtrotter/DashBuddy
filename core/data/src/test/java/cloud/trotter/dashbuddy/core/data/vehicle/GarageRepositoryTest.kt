package cloud.trotter.dashbuddy.core.data.vehicle

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.dashbuddy.core.datastore.vehicle.VehicleDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GarageRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun garage(scope: CoroutineScope) = GarageRepository(VehicleDataSource(PreferenceDataStoreFactory.create(
        scope = scope, produceFile = { tmp.newFile("vehicles.preferences_pb") })))
    @Test fun `adding makes it active`() = runTest {
        val repo = garage(backgroundScope)
        val civic = repo.addVehicle("Civic", 100)
        val truck = repo.addVehicle("Truck", 200)
        assertEquals(listOf(civic, truck), repo.vehicles.first())
        assertEquals(truck, repo.activeVehicle.first())
        assertEquals(200L, truck.createdAt)
        assertNotEquals(civic.id, truck.id)
        repo.selectVehicle(civic.id)
        assertEquals(civic, repo.activeVehicle.first())
        repo.clear()
        assertTrue(repo.vehicles.first().isEmpty())
        assertNull(repo.activeVehicle.first())
    }
    @Test fun `name trimmed and bounded`() = runTest {
        val repo = garage(backgroundScope)
        assertEquals("Civic", repo.addVehicle("  Civic  ", 100).name)
        assertEquals(30, repo.addVehicle("x".repeat(30), 100).name.length)
        listOf("  ", "x".repeat(31)).forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.addVehicle(name, 100) } }
        }
        assertEquals(2, repo.vehicles.first().size)
    }
}
