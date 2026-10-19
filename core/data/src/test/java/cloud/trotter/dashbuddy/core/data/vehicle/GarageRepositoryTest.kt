package cloud.trotter.dashbuddy.core.data.vehicle

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.dashbuddy.core.datastore.vehicle.VehicleDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import timber.log.Timber
import java.io.IOException
import kotlinx.coroutines.CancellationException

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
        assertTrue(repo.garage.first().readable)
        assertEquals(200L, truck.createdAt)
        assertNotEquals(civic.id, truck.id)
        assertTrue(repo.selectVehicle(civic.id))
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
        assertFalse(repo.selectVehicle("missing"))
        assertEquals(before, source.stored.first())
    }

    @Test fun `an unreadable garage says so on read`() = runTest {
        val source = VehicleDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("unreadable.preferences_pb") }))
        val repo = GarageRepository(source)
        assertTrue(repo.garage.first().readable)
        source.save("invalid json", "stale")
        val before = source.stored.first()
        val garage = repo.garage.first()
        assertFalse(garage.readable)
        assertTrue(garage.vehicles.isEmpty())
        assertNull(garage.activeId)
        assertNull(garage.active)
        assertEquals(before, source.stored.first())
        source.save("[]", null)
        assertTrue(repo.garage.first().readable)
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
    @Test fun `a corrupt store reads as unreadable and refuses writes`() = runTest {
        var writes = 0
        // VehicleDataSource stays final: a fake DataStore makes its stored flow fail on collection.
        val store = object : DataStore<Preferences> {
            override val data = flow<Preferences> { throw IOException("private vehicle data") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                writes++
                error("A corrupt store must never be updated")
            }
        }
        val source = VehicleDataSource(store)
        val warnings = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority == 5 && tag == "Garage") warnings += message
            }
        }
        Timber.plant(tree)
        try {
            repeat(2) { index ->
                val repo = GarageRepository(source)
                repeat(2) {
                    assertEquals(Garage(emptyList(), null, readable = false), repo.garage.first())
                }
                assertTrue(runCatching { repo.addVehicle("Truck", 100) }.exceptionOrNull() is VehiclesUnreadableException)
                assertTrue(runCatching { repo.selectVehicle("civic") }.exceptionOrNull() is VehiclesUnreadableException)
                assertEquals(index + 1, warnings.size)
            }
            assertEquals(listOf("IOException", "IOException"), warnings)
            assertEquals(0, writes)
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test fun `store cancellation is propagated on reads and writes`() = runTest {
        val cancelled = CancellationException("cancelled")
        val store = object : DataStore<Preferences> {
            override val data = flow<Preferences> { throw cancelled }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                error("A cancelled read must never write")
        }
        val repo = GarageRepository(VehicleDataSource(store))
        assertSame(cancelled, runCatching { repo.garage.first() }.exceptionOrNull())
        assertSame(cancelled, runCatching { repo.addVehicle("Truck", 100) }.exceptionOrNull())
        assertSame(cancelled, runCatching { repo.selectVehicle("civic") }.exceptionOrNull())
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
