package cloud.trotter.dashbuddy.core.data.vehicle

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.dashbuddy.core.datastore.vehicle.VehicleDataSource
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import cloud.trotter.dashbuddy.domain.model.vehicle.*
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
    @Test fun `v1 migration is persisted only on a successful explicit write`() = runTest {
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            var fail = false
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (fail) throw IOException("write failed")
                return transform(data.value).also { data.value = it }
            }
        }
        val source = VehicleDataSource(store)
        val raw = """[{"id":"one","name":"Civic","createdAt":1,"profile":{"vehicleClass":"SEDAN","tireSetCost":0,"unknown":5}}]"""
        source.save(raw, "one")
        val repo = GarageRepository(source)
        val before = repo.garage.first()
        assertTrue(before.active!!.profile is GasCar)
        assertEquals(raw, source.stored.first().json)
        store.fail = true
        assertTrue(runCatching { repo.selectVehicle("one") }.exceptionOrNull() is VehiclesUnreadableException)
        assertEquals(raw, source.stored.first().json)
        store.fail = false
        assertTrue(repo.selectVehicle("one"))
        assertTrue(source.stored.first().json!!.startsWith("{\"version\":2,"))
        assertEquals(before, repo.garage.first())
    }

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
        val identity = LegacyProfile(year = "2021", make = "Honda", model = "Civic", mpg = 31.0).toTyped()
        val add = async { repo.addVehicle("My car", 1L, LegacyProfile(tireSetCost = 900.0).toTyped()) }
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
        assertEquals(identity.withCosts(wear = identity.wear.copy(tireSetCost = 900.0)), result.active!!.profile)
        assertEquals(1, result.vehicles.count { it.profile.make == "Honda" && it.profile.model == "Civic" })
    }

    @Test fun `seedFrom attaches to the active vehicle`() = runTest {
        val repo = garage(backgroundScope)
        val active = repo.addVehicle("Active", 1L, LegacyProfile(make = "Honda", tireSetCost = 900.0).toTyped())
        val other = repo.addVehicle("Other", 2L, LegacyProfile(make = "Toyota", mpg = 50.0).toTyped())
        repo.selectVehicle(active.id)

        assertEquals(SeedOutcome.ATTACHED, repo.seedFrom(
            LegacyProfile(year = "2021", make = "Ford", model = "Civic", mpg = 31.0,
                tireSetCost = 600.0, tireLifetimeMi = 40_000.0).toTyped(), 3L,
        ))

        val result = repo.garage.first()
        assertEquals(2, result.vehicles.size)
        assertEquals(active.id, result.activeId)
        assertEquals(active.copy(profile = LegacyProfile(year = "2021", make = "Honda", model = "Civic",
            mpg = 31.0, tireSetCost = 900.0, tireLifetimeMi = 40_000.0).toTyped()), result.active)
        assertEquals(other, result.vehicles.first { it.id == other.id })
    }

    @Test fun `seedFrom adds to an empty garage`() = runTest {
        val repo = garage(backgroundScope)
        val identity = LegacyProfile(year = "2021", make = "Honda", model = "Civic", mpg = 31.0).toTyped()
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
        val live = repo.addVehicle("Parked", 1L, activate = false)
        val retired = repo.addVehicle("Retired", 2L, activate = false)
        repo.retire(retired.id, 3L)
        val before = repo.garage.first()
        assertEquals(SeedOutcome.NO_ACTIVE_VEHICLE, repo.seedFrom(LegacyProfile(make = "Honda").toTyped(), 4L))
        assertEquals(before, repo.garage.first())
        repo.retire(live.id, 5L)
        val retiredOnly = repo.garage.first()
        assertEquals(SeedOutcome.NO_ACTIVE_VEHICLE, repo.seedFrom(LegacyProfile(make = "Honda").toTyped(), 6L))
        assertEquals(retiredOnly, repo.garage.first())
    }

    @Test fun `profile edits remain with each car across switching`() = runTest {
        val repo = garage(backgroundScope)
        val civic = repo.addVehicle("Civic", 100, LegacyProfile(vehicleClass = VehicleClass.COMPACT).toTyped())
        val truck = repo.addVehicle("Truck", 200, LegacyProfile(vehicleClass = VehicleClass.TRUCK).toTyped())
        assertTrue(repo.updateProfile(civic.id) { it.withGas(mpg = 31.0).withCosts(wear = it.wear.copy(tireSetCost = 650.0)) })
        assertEquals(VehicleClass.TRUCK, repo.garage.first().active?.profile?.legacyClass)
        assertTrue(repo.selectVehicle(civic.id))
        assertEquals(31.0, repo.garage.first().active?.profile?.gasMpg)
        assertEquals(650.0, repo.garage.first().active?.profile?.wear?.tireSetCost)
        assertFalse(repo.updateProfile("missing") { it.withGas(mpg = 1.0) })
        assertEquals(truck.profile, repo.garage.first().vehicles.first { it.id == truck.id }.profile)
    }

    @Test fun `settings edits create one active profile under concurrent writes`() = runTest {
        val repo = garage(backgroundScope)
        val writes = listOf(
            async { repo.updateOrCreateActiveProfile(1) { it.withGas(mpg = 29.0) } },
            async { repo.updateOrCreateActiveProfile(2) { it.withCosts(wear = it.wear.copy(tireSetCost = 710.0)) } },
            async { repo.updateOrCreateActiveProfile(3) { it.withCosts(fixed = it.fixed.copy(insuranceDeltaPerMonth = 15.0)) } },
        )
        assertTrue(writes.awaitAll().all { it })
        val garage = repo.garage.first()
        assertEquals(1, garage.vehicles.size)
        assertNull(garage.active!!.name)
        assertEquals(29.0, garage.active!!.profile.gasMpg)
        assertEquals(710.0, garage.active!!.profile.wear.tireSetCost)
        assertEquals(15.0, garage.active!!.profile.fixed.insuranceDeltaPerMonth)
    }

    @Test fun `retired-only garage creates one new active car for settings`() = runTest {
        val repo = garage(backgroundScope)
        val old = repo.addVehicle("Old", 1)
        assertTrue(repo.retire(old.id, 2))
        assertTrue(repo.updateOrCreateActiveProfile(3) { it.withGas(mpg = 30.0) })
        val result = repo.garage.first()
        assertEquals(2, result.vehicles.size)
        assertEquals(1, result.unretired.size)
        assertEquals(30.0, result.active!!.profile.gasMpg)
        assertEquals(old.id, result.vehicles.last().id)
    }

    @Test fun `retiring active promotes the survivor for subsequent settings edits`() = runTest {
        val repo = garage(backgroundScope)
        val first = repo.addVehicle("First", 1)
        val second = repo.addVehicle("Second", 2)
        assertTrue(repo.retire(second.id, 3))
        assertEquals(first, repo.garage.first().active)
        assertTrue(repo.updateOrCreateActiveProfile(4) { it.withGas(mpg = 99.0) })
        val result = repo.garage.first()
        assertEquals(2, result.vehicles.size)
        assertEquals(first.id, result.activeId)
        assertEquals(99.0, result.active!!.profile.gasMpg)
        assertEquals(3L, result.vehicles.first { it.id == second.id }.retiredAt)
    }

    @Test fun `retiring active promotes survivor and keeps record ordered last`() = runTest {
        val repo = garage(backgroundScope)
        val first = repo.addVehicle("First", 1)
        val second = repo.addVehicle("Second", 2)
        assertTrue(repo.retire(second.id, 3))
        assertEquals(first, repo.garage.first().active)
        assertEquals(first.id, repo.garage.first().activeId)
        assertEquals(listOf(first.id, second.id), repo.garage.first().vehicles.map { it.id })
        assertFalse(repo.selectVehicle(second.id))
        assertFalse(repo.retire(second.id, 4))
        assertTrue(repo.selectVehicle(first.id))
        assertTrue(repo.retire(first.id, 5))
        assertTrue(repo.garage.first().unretired.isEmpty())
        assertNull(repo.garage.first().active)
    }
    @Test fun `retiring active chooses newest live creation rather than list order`() = runTest {
        val repo = garage(backgroundScope)
        val newest = repo.addVehicle("Newest live", 30)
        val retired = repo.addVehicle("Retired", 40)
        val older = repo.addVehicle("Older", 20)
        val active = repo.addVehicle("Active", 10)
        assertTrue(repo.retire(retired.id, 50))
        assertEquals(active.id, repo.garage.first().activeId)
        assertTrue(repo.retire(active.id, 60))
        assertEquals(newest.id, repo.garage.first().activeId)
        assertTrue(repo.retire(newest.id, 70))
        assertEquals(older.id, repo.garage.first().activeId)
        assertTrue(repo.retire(older.id, 80))
        assertNull(repo.garage.first().activeId)
    }

    @Test fun `add without activation preserves active id`() = runTest {
        val repo = garage(backgroundScope)
        val active = repo.addVehicle("Active", 1)
        val parked = repo.addVehicle("Parked", 2, ElectricCar(), activate = false)
        assertEquals(listOf(active, parked), repo.garage.first().vehicles)
        assertEquals(active.id, repo.garage.first().activeId)
    }

    @Test fun `updateVehicle commits name and profile once and refuses invalid or stale edits`() = runTest {
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            var writes = 0
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                writes++
                return transform(data.value).also { data.value = it }
            }
        }
        val repo = GarageRepository(VehicleDataSource(store))
        val target = repo.addVehicle("Original", 1)
        val active = repo.addVehicle("Active", 2)
        val profile = ElectricCar(kwhPer100Mi = 28.0)
        assertTrue(repo.updateVehicle(target.id, "  Edited  ", profile))
        assertEquals(3, store.writes)
        val edited = repo.garage.first()
        assertEquals(target.copy(name = "Edited", profile = profile), edited.vehicles.first())
        assertEquals(active, edited.active)
        listOf(" ", "😀".repeat(31)).forEach { invalid ->
            assertTrue(runCatching { repo.updateVehicle(target.id, invalid, Bicycle()) }
                .exceptionOrNull() is IllegalArgumentException)
        }
        assertFalse(repo.updateVehicle("missing", "New", Bicycle()))
        assertEquals(3, store.writes)
        assertEquals(edited, repo.garage.first())
        assertTrue(repo.updateVehicle(target.id, "😀".repeat(30), profile))
        assertTrue(repo.updateVehicle(target.id, null, profile))
        assertNull(repo.garage.first().vehicles.first().name)
        repo.retire(target.id, 3)
        val retired = repo.garage.first()
        val writes = store.writes
        assertFalse(repo.updateVehicle(target.id, "New", Bicycle()))
        assertEquals(writes, store.writes)
        assertEquals(retired, repo.garage.first())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `updateVehicle holds the lock through its single commit`() = runTest {
        val saving = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            var writes = 0
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (++writes == 2) {
                    saving.complete(Unit)
                    resume.await()
                }
                return transform(data.value).also { data.value = it }
            }
        }
        val repo = GarageRepository(VehicleDataSource(store))
        val vehicle = repo.addVehicle("Original", 1)
        val edit = async { repo.updateVehicle(vehicle.id, "Edited", ElectricCar()) }
        saving.await()
        val retire = async { repo.retire(vehicle.id, 2) }
        runCurrent()
        assertFalse(retire.isCompleted)
        assertEquals(vehicle, repo.garage.first().active)
        assertEquals(2, store.writes)
        resume.complete(Unit)
        assertTrue(edit.await())
        assertTrue(retire.await())
        assertEquals(3, store.writes)
        assertEquals(vehicle.copy(name = "Edited", profile = ElectricCar(), retiredAt = 2),
            repo.garage.first().vehicles.single())
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
    @Test fun `rename trims validates code points and writes only a changed live name`() = runTest {
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            var writes = 0
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                writes++
                return transform(data.value).also { data.value = it }
            }
        }
        val repo = GarageRepository(VehicleDataSource(store))
        val profile = GasCar(mpg = 31.0, year = "2020", make = "Honda", model = "Civic")
        val target = repo.addVehicle("Civic", 1, profile)
        val active = repo.addVehicle("Active", 2)
        assertTrue(repo.rename(target.id, "  Commute  "))
        assertEquals(3, store.writes)
        val renamed = repo.garage.first()
        assertEquals(target.copy(name = "Commute"), renamed.vehicles.first())
        assertEquals(active, renamed.active)

        assertTrue(repo.rename(target.id, "  Commute  "))
        assertFalse(repo.rename("missing", "New"))
        assertEquals(3, store.writes)
        listOf("  ", "x".repeat(31), "😀".repeat(31)).forEach { invalid ->
            assertTrue(runCatching { repo.rename(target.id, invalid) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(3, store.writes)
        assertEquals(renamed, repo.garage.first())

        val unicodeName = "😀".repeat(Vehicle.MAX_NAME_CODE_POINTS)
        assertTrue(repo.rename(target.id, unicodeName))
        assertEquals(unicodeName, repo.garage.first().vehicles.first().name)
        assertTrue(repo.rename(target.id, null))
        assertNull(repo.garage.first().vehicles.first().name)
        val beforeUnchangedNull = store.writes
        assertTrue(repo.rename(target.id, null))
        assertEquals(beforeUnchangedNull, store.writes)
        repo.retire(target.id, 3)
        val beforeRetired = repo.garage.first()
        val beforeRefused = store.writes
        assertFalse(repo.rename(target.id, "Retired"))
        assertEquals(beforeRefused, store.writes)
        assertEquals(beforeRetired, repo.garage.first())
    }

    @Test fun `rename and updateVehicle refuse unreadable vehicles without a write`() = runTest {
        val source = VehicleDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("rename-corrupt.preferences_pb") }))
        source.save("invalid json", "one")
        val repo = GarageRepository(source)
        val before = source.stored.first()
        assertTrue(runCatching { repo.rename("one", "Name") }.exceptionOrNull() is VehiclesUnreadableException)
        assertTrue(runCatching { repo.updateVehicle("one", "Name", Bicycle()) }.exceptionOrNull() is VehiclesUnreadableException)
        assertEquals(before, source.stored.first())
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
                assertTrue(runCatching { repo.updateOrCreateActiveProfile(100) { it.withGas(mpg = 20.0) } }
                    .exceptionOrNull() is VehiclesUnreadableException)
                assertTrue(runCatching { repo.seedFrom(LegacyProfile().toTyped(), 100) }
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
        assertSame(cancelled, runCatching { repo.seedFrom(LegacyProfile().toTyped(), 100) }.exceptionOrNull())
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
