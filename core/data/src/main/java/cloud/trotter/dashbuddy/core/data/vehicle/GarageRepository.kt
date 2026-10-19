package cloud.trotter.dashbuddy.core.data.vehicle

import cloud.trotter.dashbuddy.core.datastore.vehicle.VehicleDataSource
import cloud.trotter.dashbuddy.domain.model.vehicle.Vehicle
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleStorage
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class Garage(val vehicles: List<Vehicle>, val activeId: String?, val readable: Boolean) {
    val active: Vehicle? get() = vehicles.firstOrNull { it.id == activeId && it.retiredAt == null }
    val unretired: List<Vehicle> get() = vehicles.filter { it.retiredAt == null }
}

enum class SeedOutcome { ADDED, ATTACHED, NO_ACTIVE_VEHICLE }

class VehiclesUnreadableException : IllegalStateException("vehicles unreadable")

@Singleton
class GarageRepository @Inject constructor(private val dataSource: VehicleDataSource) {
    private val mutex = Mutex()
    private val warnedUnreadable = AtomicBoolean(false)
    val garage = dataSource.stored.map { saved ->
        val vehicles = readVehicles(saved.json)
        if (vehicles == null) Garage(emptyList(), null, readable = false)
        else Garage(vehicles.sortedBy { it.retiredAt != null }, saved.activeId, readable = true)
    }.catch { error ->
        if (error !is Exception || error is CancellationException) throw error
        warnOnce(error)
        emit(Garage(emptyList(), null, readable = false))
    }

    suspend fun addVehicle(name: String?, now: Long, profile: VehicleProfile = VehicleProfile()): Vehicle = mutex.withLock {
        val vehicle = Vehicle(UUID.randomUUID().toString(), name?.trim(), now, profile)
        val list = vehiclesForWrite() + vehicle
        saveForWrite(list, vehicle.id)
        vehicle
    }

    /** The seed decision shares the write lock with HUD adds and selection changes. */
    suspend fun seedFrom(identity: VehicleProfile, now: Long): SeedOutcome = mutex.withLock {
        val saved = savedForWrite()
        val list = readVehicles(saved.json) ?: throw VehiclesUnreadableException()
        if (list.isEmpty()) {
            val vehicle = Vehicle(UUID.randomUUID().toString(), null, now, identity)
            saveForWrite(listOf(vehicle), vehicle.id)
            return@withLock SeedOutcome.ADDED
        }
        val index = list.indexOfFirst { it.id == saved.activeId && it.retiredAt == null }
        if (index < 0) return@withLock SeedOutcome.NO_ACTIVE_VEHICLE
        val updated = list.toMutableList()
        updated[index] = updated[index].copy(profile = mergeSeed(updated[index].profile, identity))
        saveForWrite(updated, saved.activeId)
        SeedOutcome.ATTACHED
    }

    /** True when the active vehicle was written; false for an unknown id (a stale switcher row), which changes nothing. */
    suspend fun selectVehicle(id: String): Boolean = mutex.withLock {
        val list = vehiclesForWrite()
        if (list.none { it.id == id && it.retiredAt == null }) {
            Timber.tag("Garage").w("Ignored a select for an unknown vehicle id")
            return@withLock false
        }
        saveForWrite(list, id)
        true
    }

    suspend fun updateProfile(id: String, transform: (VehicleProfile) -> VehicleProfile): Boolean = mutex.withLock {
        val saved = savedForWrite()
        val list = readVehicles(saved.json) ?: throw VehiclesUnreadableException()
        val index = list.indexOfFirst { it.id == id && it.retiredAt == null }
        if (index < 0) return@withLock false
        val updated = list.toMutableList()
        updated[index] = updated[index].copy(profile = transform(updated[index].profile))
        saveForWrite(updated, saved.activeId)
        true
    }

    suspend fun updateProfile(id: String, profile: VehicleProfile): Boolean =
        updateProfile(id) { profile }

    /** Settings edits own one active profile even before the first named garage entry exists. */
    suspend fun updateOrCreateActiveProfile(
        now: Long,
        transform: (VehicleProfile) -> VehicleProfile,
    ): Boolean = mutex.withLock {
        val saved = savedForWrite()
        val list = readVehicles(saved.json) ?: throw VehiclesUnreadableException()
        val activeIndex = list.indexOfFirst { it.id == saved.activeId && it.retiredAt == null }
        if (activeIndex >= 0) {
            val updated = list.toMutableList()
            updated[activeIndex] = updated[activeIndex].copy(profile = transform(updated[activeIndex].profile))
            saveForWrite(updated, saved.activeId)
            return@withLock true
        }
        if (list.any { it.retiredAt == null }) return@withLock false
        val vehicle = Vehicle(UUID.randomUUID().toString(), null, now, transform(VehicleProfile()))
        saveForWrite(list + vehicle, vehicle.id)
        true
    }

    /** Applies a wizard edit only while its selected car is still active. */
    suspend fun updateActiveProfile(
        expectedActiveId: String,
        transform: (VehicleProfile) -> VehicleProfile,
    ): Boolean = mutex.withLock {
        val saved = savedForWrite()
        if (saved.activeId != expectedActiveId) return@withLock false
        val list = readVehicles(saved.json) ?: throw VehiclesUnreadableException()
        val index = list.indexOfFirst { it.id == expectedActiveId && it.retiredAt == null }
        if (index < 0) return@withLock false
        val updated = list.toMutableList()
        updated[index] = updated[index].copy(profile = transform(updated[index].profile))
        saveForWrite(updated, expectedActiveId)
        true
    }

    suspend fun rename(id: String, name: String?): Boolean = mutex.withLock {
        val saved = savedForWrite()
        val list = readVehicles(saved.json) ?: throw VehiclesUnreadableException()
        val index = list.indexOfFirst { it.id == id && it.retiredAt == null }
        if (index < 0) return@withLock false
        val updated = list.toMutableList()
        updated[index] = updated[index].copy(name = name?.trim())
        saveForWrite(updated, saved.activeId)
        true
    }

    suspend fun retire(id: String, now: Long): Boolean = mutex.withLock {
        val saved = savedForWrite()
        val list = readVehicles(saved.json) ?: throw VehiclesUnreadableException()
        val index = list.indexOfFirst { it.id == id && it.retiredAt == null }
        if (index < 0) return@withLock false
        val updated = list.toMutableList()
        updated[index] = updated[index].copy(retiredAt = now)
        val nextActive = if (saved.activeId == id) null
            else saved.activeId
        saveForWrite(updated, nextActive)
        true
    }

    private suspend fun vehiclesForWrite(): List<Vehicle> {
        val saved = savedForWrite()
        return readVehicles(saved.json) ?: throw VehiclesUnreadableException()
    }

    private suspend fun savedForWrite(): VehicleDataSource.StoredVehicles {
        val saved = try {
            dataSource.stored.first()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            warnOnce(error)
            throw VehiclesUnreadableException()
        }
        return saved
    }

    private suspend fun saveForWrite(vehicles: List<Vehicle>, activeId: String?) {
        val json = VehicleStorage.encode(vehicles)
        try {
            dataSource.save(json, activeId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            warnOnce(error)
            throw VehiclesUnreadableException()
        }
    }

    private fun readVehicles(raw: String?): List<Vehicle>? = VehicleStorage.decode(raw, ::warnOnce)

    private fun warnOnce(error: Exception) {
        if (warnedUnreadable.compareAndSet(false, true)) {
            Timber.tag("Garage").w("%s", error.javaClass.simpleName)
        }
    }

    private fun mergeSeed(current: VehicleProfile, saved: VehicleProfile): VehicleProfile = current.copy(
        year = current.year ?: saved.year,
        make = current.make ?: saved.make,
        model = current.model ?: saved.model,
        mpg = current.mpg ?: saved.mpg,
        vehicleClass = if (current.vehicleClass == cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass.SEDAN)
            saved.vehicleClass else current.vehicleClass,
        fuelType = if (current.fuelType == cloud.trotter.dashbuddy.domain.model.vehicle.FuelType.REGULAR)
            saved.fuelType else current.fuelType,
        tireSetCost = current.tireSetCost ?: saved.tireSetCost,
        tireLifetimeMi = current.tireLifetimeMi ?: saved.tireLifetimeMi,
        oilCost = current.oilCost ?: saved.oilCost,
        oilIntervalMi = current.oilIntervalMi ?: saved.oilIntervalMi,
        brakesCost = current.brakesCost ?: saved.brakesCost,
        brakesIntervalMi = current.brakesIntervalMi ?: saved.brakesIntervalMi,
        fluidsCost = current.fluidsCost ?: saved.fluidsCost,
        fluidsIntervalMi = current.fluidsIntervalMi ?: saved.fluidsIntervalMi,
        miscYearly = current.miscYearly ?: saved.miscYearly,
        miscYearlyMi = current.miscYearlyMi ?: saved.miscYearlyMi,
        includeDepreciation = current.includeDepreciation ?: saved.includeDepreciation,
        purchasePrice = current.purchasePrice ?: saved.purchasePrice,
        totalLifetimeMi = current.totalLifetimeMi ?: saved.totalLifetimeMi,
        insuranceDeltaPerMonth = current.insuranceDeltaPerMonth ?: saved.insuranceDeltaPerMonth,
        registrationDeltaPerYear = current.registrationDeltaPerYear ?: saved.registrationDeltaPerYear,
        expectedAnnualMiles = current.expectedAnnualMiles ?: saved.expectedAnnualMiles,
    )
}
