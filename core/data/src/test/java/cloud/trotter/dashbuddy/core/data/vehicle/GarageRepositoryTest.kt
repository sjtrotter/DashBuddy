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
import timber.log.Timber

class GarageRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun garage(scope: CoroutineScope) = GarageRepository(VehicleDataSource(PreferenceDataStoreFactory.create(
        scope = scope, produceFile = { tmp.newFile("vehicles.preferences_pb") })))
    @Test fun `adding makes it active`() = runTest {
        val repo = garage(backgroundScope)
        val civic = repo.addVehicle("Civic", 100)
        val truck = repo.addVehicle("Truck", 200)
        assertEquals(listOf(civic, truck), repo.garage.first().vehicles)
        assertEquals(truck, repo.garage.first().active)
        assertEquals(200L, truck.createdAt)
        assertNotEquals(civic.id, truck.id)
        repo.selectVehicle(civic.id)
        assertEquals(civic, repo.garage.first().active)
    }
    @Test fun `name trimmed and bounded`() = runTest {
        val repo = garage(backgroundScope)
        assertEquals("Civic", repo.addVehicle("  Civic  ", 100).name)
        assertEquals(30, repo.addVehicle("x".repeat(30), 100).name.length)
        listOf("  ", "x".repeat(31)).forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.addVehicle(name, 100) } }
        }
        assertEquals(2, repo.garage.first().vehicles.size)
    }
    @Test fun `an unknown id is ignored`() = runTest {
        val source = VehicleDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("unknown.preferences_pb") }))
        val repo = GarageRepository(source)
        repo.addVehicle("Civic", 100)
        val before = source.stored.first()
        repo.selectVehicle("missing")
        assertEquals(before, source.stored.first())
    }

    @Test fun `each repository warns once for an unreadable garage`() = runTest {
        val source = VehicleDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("warnings.preferences_pb") }))
        source.save("invalid json", null)
        val warnings = mutableListOf<Pair<String?, String>>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority == 5 && tag == "Garage") warnings += tag to message
            }
        }
        Timber.plant(tree)
        try {
            repeat(2) { index ->
                val repo = GarageRepository(source)
                repeat(2) { repo.garage.first() }
                assertEquals(index + 1, warnings.size)
            }
        } finally {
            Timber.uproot(tree)
        }
    }
    @Test fun `an unreadable garage refuses to add`() = runTest {
        val source = VehicleDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("corrupt.preferences_pb") }))
        val raw = """[{"id":"good","name":"Civic","createdAt":0},{"id":"bad","name":" ","createdAt":0}]"""
        source.save(raw, "good")
        val before = source.stored.first()
        val repo = GarageRepository(source)
        assertTrue(repo.garage.first().vehicles.isEmpty())
        assertNull(repo.garage.first().active)
        val error = runCatching { repo.addVehicle("Truck", 100) }.exceptionOrNull()
        assertTrue(error is VehiclesUnreadableException)
        assertEquals("vehicles unreadable", error?.message)
        assertEquals(before, source.stored.first())
        val selectError = runCatching { repo.selectVehicle("good") }.exceptionOrNull()
        assertTrue(selectError is VehiclesUnreadableException)
        assertEquals("vehicles unreadable", selectError?.message)
        assertEquals(before, source.stored.first())
    }

}
