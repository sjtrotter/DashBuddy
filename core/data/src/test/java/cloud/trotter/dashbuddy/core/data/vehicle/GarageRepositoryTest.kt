package cloud.trotter.dashbuddy.core.data.vehicle

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.dashbuddy.core.datastore.vehicle.VehicleDataSource
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

class GarageRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun garage(scope: CoroutineScope) = GarageRepository(VehicleDataSource(PreferenceDataStoreFactory.create(
        scope = scope, produceFile = { tmp.newFile("vehicles.preferences_pb") })))
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `seeding under a pending add never duplicates`() = runTest {
        val saving = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            var saves = 0
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (saves++ == 0) {
                    saving.complete(Unit)
                    resume.await()
                }
                return transform(data.value).also { data.value = it }
            }
        }
        val repo = GarageRepository(VehicleDataSource(store))
        val identity = VehicleProfile(year = "2021", make = "Honda", model = "Civic", mpg = 31.0)
        val add = async { repo.addVehicle("My car", 1L, VehicleProfile(tireSetCost = 900.0)) }
        saving.await()
        val seed = async { repo.seedFrom(identity, 2L) }
        runCurrent()
        assertFalse(seed.isCompleted)
        assertEquals(1, store.saves)
        resume.complete(Unit)

        val added = add.await()
        assertEquals(SeedOutcome.ATTACHED, seed.await())
        val result = repo.garage.first()
        assertEquals(1, result.vehicles.size)
        assertEquals(added.id, result.activeId)
        assertEquals("My car", result.active!!.name)
        assertEquals(identity.copy(tireSetCost = 900.0), result.active!!.profile)
        assertEquals(1, result.vehicles.count { it.profile.make == "Honda" && it.profile.model == "Civic" })
    }

    @Test fun `seedFrom attaches to the active vehicle`() = runTest {
        val repo = garage(backgroundScope)
        val active = repo.addVehicle("Active", 1L, VehicleProfile(make = "Honda", tireSetCost = 900.0))
        val other = repo.addVehicle("Other", 2L, VehicleProfile(make = "Toyota", mpg = 50.0))
        repo.selectVehicle(active.id)

        assertEquals(SeedOutcome.ATTACHED, repo.seedFrom(
            VehicleProfile(year = "2021", make = "Ford", model = "Civic", mpg = 31.0,
                tireSetCost = 600.0, tireLifetimeMi = 40_000.0), 3L,
        ))

        val result = repo.garage.first()
        assertEquals(2, result.vehicles.size)
        assertEquals(active.id, result.activeId)
        assertEquals(active.copy(profile = VehicleProfile(year = "2021", make = "Honda", model = "Civic",
            mpg = 31.0, tireSetCost = 900.0, tireLifetimeMi = 40_000.0)), result.active)
        assertEquals(other, result.vehicles.first { it.id == other.id })
    }

    @Test fun `seedFrom adds to an empty garage`() = runTest {
        val repo = garage(backgroundScope)
        val identity = VehicleProfile(year = "2021", make = "Honda", model = "Civic", mpg = 31.0)
        assertEquals(SeedOutcome.ADDED, repo.seedFrom(identity, 123L))
        val result = repo.garage.first()
        assertEquals(1, result.vehicles.size)
        assertEquals(result.vehicles.single(), result.active)
        assertEquals(identity, result.active!!.profile)
        assertEquals(123L, result.active!!.createdAt)
        assertNull(result.active!!.name)
    }

    @Test fun `seedFrom leaves vehicles alone when none is active`() = runTest {
        val repo = garage(backgroundScope)
        val live = repo.addVehicle("Parked", 1L)
        val retired = repo.addVehicle("Retired", 2L)
        repo.retire(retired.id, 3L)
        val before = repo.garage.first()
        assertEquals(SeedOutcome.NO_ACTIVE_VEHICLE, repo.seedFrom(VehicleProfile(make = "Honda"), 4L))
        assertEquals(before, repo.garage.first())
        repo.retire(live.id, 5L)
        val retiredOnly = repo.garage.first()
        assertEquals(SeedOutcome.NO_ACTIVE_VEHICLE, repo.seedFrom(VehicleProfile(make = "Honda"), 6L))
        assertEquals(retiredOnly, repo.garage.first())
    }

    @Test fun `profile edits remain with each car across switching`() = runTest {
        val repo = garage(backgroundScope)
        val civic = repo.addVehicle("Civic", 100, VehicleProfile(vehicleClass = VehicleClass.COMPACT))
        val truck = repo.addVehicle("Truck", 200, VehicleProfile(vehicleClass = VehicleClass.TRUCK))
        assertTrue(repo.updateProfile(civic.id) { it.copy(mpg = 31.0, tireSetCost = 650.0) })
        assertEquals(VehicleClass.TRUCK, repo.garage.first().active?.profile?.vehicleClass)
        assertTrue(repo.selectVehicle(civic.id))
        assertEquals(31.0, repo.garage.first().active?.profile?.mpg)
        assertEquals(650.0, repo.garage.first().active?.profile?.tireSetCost)
        assertFalse(repo.updateProfile("missing") { it.copy(mpg = 1.0) })
        assertEquals(truck.profile, repo.garage.first().vehicles.first { it.id == truck.id }.profile)
    }

    @Test fun `wizard edit refuses a car that is no longer active`() = runTest {
        val repo = garage(backgroundScope)
        val first = repo.addVehicle("First", 1)
        val second = repo.addVehicle("Second", 2)
        assertFalse(repo.updateActiveProfile(first.id) { it.copy(mpg = 99.0) })
        assertTrue(repo.updateActiveProfile(second.id) { it.copy(mpg = 25.0) })
        assertNull(repo.garage.first().vehicles.first { it.id == first.id }.profile.mpg)
        assertEquals(25.0, repo.garage.first().active!!.profile.mpg)
    }

    @Test fun `settings edits create one active profile under concurrent writes`() = runTest {
        val repo = garage(backgroundScope)
        val writes = listOf(
            async { repo.updateOrCreateActiveProfile(1) { it.copy(mpg = 29.0) } },
            async { repo.updateOrCreateActiveProfile(2) { it.copy(tireSetCost = 710.0) } },
            async { repo.updateOrCreateActiveProfile(3) { it.copy(insuranceDeltaPerMonth = 15.0) } },
        )
        assertTrue(writes.awaitAll().all { it })
        val garage = repo.garage.first()
        assertEquals(1, garage.vehicles.size)
        assertNull(garage.active!!.name)
        assertEquals(29.0, garage.active!!.profile.mpg)
        assertEquals(710.0, garage.active!!.profile.tireSetCost)
        assertEquals(15.0, garage.active!!.profile.insuranceDeltaPerMonth)
    }

    @Test fun `retired-only garage creates one new active car for settings`() = runTest {
        val repo = garage(backgroundScope)
        val old = repo.addVehicle("Old", 1)
        assertTrue(repo.retire(old.id, 2))
        assertTrue(repo.updateOrCreateActiveProfile(3) { it.copy(mpg = 30.0) })
        val result = repo.garage.first()
        assertEquals(2, result.vehicles.size)
        assertEquals(1, result.unretired.size)
        assertEquals(30.0, result.active!!.profile.mpg)
        assertEquals(old.id, result.vehicles.last().id)
    }

    @Test fun `unselected live garage is preserved by settings edit`() = runTest {
        val repo = garage(backgroundScope)
        val first = repo.addVehicle("First", 1)
        val second = repo.addVehicle("Second", 2)
        assertTrue(repo.retire(second.id, 3))
        assertNull(repo.garage.first().active)
        assertFalse(repo.updateOrCreateActiveProfile(4) { it.copy(mpg = 99.0) })
        assertEquals(listOf(first.id, second.id), repo.garage.first().vehicles.map { it.id })
        assertNull(repo.garage.first().vehicles.first().profile.mpg)
    }

    @Test fun `retiring active clears selection and keeps record ordered last`() = runTest {
        val repo = garage(backgroundScope)
        val first = repo.addVehicle("First", 1)
        val second = repo.addVehicle("Second", 2)
        assertTrue(repo.retire(second.id, 3))
        assertNull(repo.garage.first().active)
        assertNull(repo.garage.first().activeId)
        assertEquals(listOf(first.id, second.id), repo.garage.first().vehicles.map { it.id })
        assertFalse(repo.selectVehicle(second.id))
        assertFalse(repo.retire(second.id, 4))
        assertTrue(repo.selectVehicle(first.id))
        assertTrue(repo.retire(first.id, 5))
        assertTrue(repo.garage.first().unretired.isEmpty())
        assertNull(repo.garage.first().active)
    }
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
        assertEquals(30, repo.addVehicle("x".repeat(30), 100).name!!.length)
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
                assertTrue(runCatching { repo.updateOrCreateActiveProfile(100) { it.copy(mpg = 20.0) } }
                    .exceptionOrNull() is VehiclesUnreadableException)
                assertTrue(runCatching { repo.seedFrom(VehicleProfile(), 100) }
                    .exceptionOrNull() is VehiclesUnreadableException)
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
        assertSame(cancelled, runCatching { repo.seedFrom(VehicleProfile(), 100) }.exceptionOrNull())
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
