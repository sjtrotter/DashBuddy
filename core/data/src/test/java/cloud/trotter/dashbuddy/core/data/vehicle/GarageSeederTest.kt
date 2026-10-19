package cloud.trotter.dashbuddy.core.data.vehicle

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.dashbuddy.core.datastore.appstate.AppStateDataSource
import cloud.trotter.dashbuddy.core.datastore.settings.AppPreferencesDataSource
import cloud.trotter.dashbuddy.core.datastore.vehicle.VehicleDataSource
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever

class GarageSeederTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `saved wizard car seeds once and keeps its costs`() = runTest {
        val prefs = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("prefs.preferences_pb") }))
        val state = AppStateDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("state.preferences_pb") }))
        val garage = GarageRepository(VehicleDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("garage.preferences_pb") })))
        prefs.updateEconomySettings("2021", "Honda", "Civic", "EX", 31f, false, 3.80f)
        prefs.updateVehicleClass("COMPACT")
        prefs.updateTireCost(650.0, 40_000.0)
        val seeder = GarageSeeder(garage, prefs, state)
        seeder.seed()
        assertTrue(state.garageSeeded.first())
        val car = garage.garage.first().active!!
        assertNull(car.name)
        assertEquals("2021 Honda Civic", car.displayName)
        assertEquals(VehicleClass.COMPACT, car.profile.vehicleClass)
        assertEquals(31.0, car.profile.mpg)
        assertEquals(650.0, car.profile.tireSetCost)
        prefs.updateEconomySettings("2025", "Toyota", "Prius", "LE", 55f, false, 3.80f)
        seeder.seed()
        assertEquals(listOf(car.id), garage.garage.first().vehicles.map { it.id })
        assertEquals("Honda", garage.garage.first().active!!.profile.make)
        assertEquals(31.0, garage.garage.first().active!!.profile.mpg)
    }

    @Test fun `existing named car receives saved identity without losing profile`() = runTest {
        val prefs = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("named-prefs.preferences_pb") }))
        val state = AppStateDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("named-state.preferences_pb") }))
        val garage = GarageRepository(VehicleDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("named-garage.preferences_pb") })))
        val car = garage.addVehicle("My Civic", 1,
            VehicleProfile(tireSetCost = 900.0))
        prefs.updateEconomySettings("2018", "Honda", "Civic", "LX", 29f, false, 3.50f)
        prefs.updateTireCost(600.0, 35_000.0)
        GarageSeeder(garage, prefs, state).seed()
        val result = garage.garage.first().active!!
        assertEquals(car.id, result.id)
        assertEquals("My Civic", result.name)
        assertEquals("Honda", result.profile.make)
        assertEquals(900.0, result.profile.tireSetCost)
        assertEquals(35_000.0, result.profile.tireLifetimeMi)
        assertEquals(1, garage.garage.first().vehicles.size)
    }

    @Test fun `no saved identity marks import done without creating a car`() = runTest {
        val prefs = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("empty-prefs.preferences_pb") }))
        val state = AppStateDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("empty-state.preferences_pb") }))
        val garage = GarageRepository(VehicleDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("empty-garage.preferences_pb") })))
        val named = garage.addVehicle("Existing", 1)
        GarageSeeder(garage, prefs, state).seed()
        assertTrue(state.garageSeeded.first())
        assertEquals(named, garage.garage.first().active)
        assertEquals(1, garage.garage.first().vehicles.size)
    }

    @Test fun `every seed outcome marks import done after one repository call`() = runTest {
        for (outcome in SeedOutcome.entries) {
            val prefs = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
                produceFile = { tmp.newFile("${outcome}-prefs.preferences_pb") }))
            val state = AppStateDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
                produceFile = { tmp.newFile("${outcome}-state.preferences_pb") }))
            prefs.updateEconomySettings("2021", "Honda", "Civic", "EX", 31f, false, 3.50f)
            val identity = prefs.legacyVehicleProfile.first()!!
            val garage: GarageRepository = mock()
            whenever(garage.seedFrom(any(), any())).thenReturn(outcome)

            val seeder = GarageSeeder(garage, prefs, state)
            seeder.seed()
            seeder.seed()

            assertTrue(state.garageSeeded.first())
            verify(garage).seedFrom(eq(identity), any())
            verifyNoMoreInteractions(garage)
        }
    }

    @Test fun `no active vehicle marks import done without attaching to a parked car`() = runTest {
        val prefs = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("parked-prefs.preferences_pb") }))
        val state = AppStateDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("parked-state.preferences_pb") }))
        val garage = GarageRepository(VehicleDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("parked-garage.preferences_pb") })))
        garage.addVehicle("Parked", 1L)
        val retired = garage.addVehicle("Retired", 2L)
        garage.retire(retired.id, 3L)
        val before = garage.garage.first()
        prefs.updateEconomySettings("2021", "Honda", "Civic", "EX", 31f, false, 3.50f)

        GarageSeeder(garage, prefs, state).seed()

        assertTrue(state.garageSeeded.first())
        assertEquals(before, garage.garage.first())
    }

    @Test fun `unreadable garage is kept and seed remains pending`() = runTest {
        val prefs = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("bad-prefs.preferences_pb") }))
        val state = AppStateDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("bad-state.preferences_pb") }))
        val source = VehicleDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("bad-garage.preferences_pb") }))
        val raw = "not json"
        source.save(raw, "old")
        prefs.updateEconomySettings("2020", "Honda", "Civic", "LX", 30f, false, 3.50f)
        GarageSeeder(GarageRepository(source), prefs, state).seed()
        assertFalse(state.garageSeeded.first())
        assertEquals(raw, source.stored.first().json)
        assertEquals("old", source.stored.first().activeId)
    }

    @Test fun `a zero legacy mpg seeds no mpg`() = runTest {
        val prefs = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("zero-prefs.preferences_pb") }))
        val state = AppStateDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("zero-state.preferences_pb") }))
        val garage = GarageRepository(VehicleDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("zero-garage.preferences_pb") })))
        prefs.updateEconomySettings("2021", "Honda", "Civic", "", 0f, false, 3.80f)
        GarageSeeder(garage, prefs, state).seed()
        assertNull(garage.garage.first().active!!.profile.mpg)
        assertTrue(state.garageSeeded.first())
        prefs.updateEconomySettings("", "", "", "", 0f, false, 3.80f)
        assertNull(prefs.legacyVehicleProfile.first())
    }
}
