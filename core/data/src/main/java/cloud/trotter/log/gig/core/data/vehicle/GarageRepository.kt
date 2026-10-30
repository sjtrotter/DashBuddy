package cloud.trotter.log.gig.core.data.vehicle

import cloud.trotter.log.gig.core.datastore.vehicle.VehicleDataSource
import cloud.trotter.log.gig.domain.model.vehicle.Vehicle
import cloud.trotter.log.gig.domain.model.vehicle.VehicleStorage
import cloud.trotter.log.gig.domain.model.vehicle.*
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

/** Evaluated under the garage lock: a caller never decides activation from a stale read. */
enum class ActivateOn { ALWAYS, IF_NONE_ACTIVE, NEVER }

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

    suspend fun addVehicle(
        name: String?, now: Long, profile: VehicleProfile = GasCar(), activateOn: ActivateOn = ActivateOn.ALWAYS,
    ): Vehicle = mutex.withLock {
        val vehicle = Vehicle(UUID.randomUUID().toString(), name?.trim(), now, profile)
        val saved = savedForWrite()
        val list = readVehicles(saved.json) ?: throw VehiclesUnreadableException()
        val activate = when (activateOn) {
            ActivateOn.ALWAYS -> true
            ActivateOn.IF_NONE_ACTIVE -> list.none { it.id == saved.activeId && it.retiredAt == null }
            ActivateOn.NEVER -> false
        }
        saveForWrite(list + vehicle, if (activate) vehicle.id else saved.activeId)
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

    /** Identity and energy edits commit together; Vehicle validates the name before any write. */
    suspend fun updateVehicle(id: String, name: String?, profile: VehicleProfile): Boolean = mutex.withLock {
        val saved = savedForWrite()
        val list = readVehicles(saved.json) ?: throw VehiclesUnreadableException()
        val index = list.indexOfFirst { it.id == id && it.retiredAt == null }
        if (index < 0) return@withLock false
        val edited = list[index].copy(name = name?.trim(), profile = profile)
        val updated = list.toMutableList()
        updated[index] = edited
        saveForWrite(updated, saved.activeId)
        true
    }

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
        val vehicle = Vehicle(UUID.randomUUID().toString(), null, now, transform(GasCar()))
        saveForWrite(list + vehicle, vehicle.id)
        true
    }

    /** Like addVehicle, Vehicle validates the trimmed name's code-point limit before any write. */
    suspend fun rename(id: String, name: String?): Boolean = updateName(id) { name?.trim() }

    /** The name transform sees the value actually replaced under the same lock as profile writes. */
    suspend fun updateName(id: String, transform: (String?) -> String?): Boolean = mutex.withLock {
        val saved = savedForWrite()
        val list = readVehicles(saved.json) ?: throw VehiclesUnreadableException()
        val index = list.indexOfFirst { it.id == id && it.retiredAt == null }
        if (index < 0) return@withLock false
        val renamed = list[index].copy(name = transform(list[index].name))
        if (renamed == list[index]) return@withLock true
        val updated = list.toMutableList()
        updated[index] = renamed
        saveForWrite(updated, saved.activeId)
        true
    }

    suspend fun setOdometerShown(id: String, shown: Boolean): Boolean = updateOdometerShown(id) { shown }

    suspend fun updateOdometerShown(id: String, transform: (Boolean?) -> Boolean?): Boolean = mutex.withLock {
        val saved = savedForWrite()
        val list = readVehicles(saved.json) ?: throw VehiclesUnreadableException()
        val index = list.indexOfFirst { it.id == id && it.retiredAt == null }
        if (index < 0) return@withLock false
        val edited = list[index].copy(odometerShown = transform(list[index].odometerShown))
        if (edited == list[index]) return@withLock true
        val updated = list.toMutableList()
        updated[index] = edited
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
        val nextActive = if (saved.activeId == id) {
            updated.filter { it.retiredAt == null }.maxByOrNull { it.createdAt }?.id
        } else saved.activeId
        saveForWrite(updated, nextActive)
        true
    }

    /** Restore the existing record without changing the garage selection. */
    suspend fun restore(id: String): Boolean = mutex.withLock {
        val saved = savedForWrite()
        val list = readVehicles(saved.json) ?: throw VehiclesUnreadableException()
        val index = list.indexOfFirst { it.id == id && it.retiredAt != null }
        if (index < 0) return@withLock false
        val updated = list.toMutableList()
        updated[index] = updated[index].copy(retiredAt = null)
        saveForWrite(updated, saved.activeId)
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

    private fun mergeSeed(current: VehicleProfile, saved: VehicleProfile): VehicleProfile {
        val target = if (current is GasCar && current.vehicleClass == GasCarClass.SEDAN) {
            if (saved is GasCar) current.copy(vehicleClass = saved.vehicleClass)
            else saved.withIdentity(current.year, current.make, current.model)
        } else current
        return target.withIdentity(current.year ?: saved.year, current.make ?: saved.make, current.model ?: saved.model)
            .withGas(mpg = current.gasMpg ?: saved.gasMpg,
                fuelType = current.gasFuel?.takeUnless { it == FuelType.REGULAR } ?: saved.gasFuel ?: FuelType.REGULAR)
            .withCosts(mergeWear(current.wear, saved.wear), mergeFixed(current.fixed, saved.fixed))
    }

    private fun mergeWear(current: WearProfile, saved: WearProfile) = WearProfile(
        tireSetCost = current.tireSetCost ?: saved.tireSetCost,
        tireLifetimeMi = current.tireLifetimeMi ?: saved.tireLifetimeMi,
        oilCost = current.oilCost ?: saved.oilCost,
        oilIntervalMi = current.oilIntervalMi ?: saved.oilIntervalMi,
        brakesCost = current.brakesCost ?: saved.brakesCost,
        brakesIntervalMi = current.brakesIntervalMi ?: saved.brakesIntervalMi,
        fluidsCost = current.fluidsCost ?: saved.fluidsCost,
        fluidsIntervalMi = current.fluidsIntervalMi ?: saved.fluidsIntervalMi,
        chainCost = current.chainCost ?: saved.chainCost,
        chainLifeMi = current.chainLifeMi ?: saved.chainLifeMi,
        batteryCost = current.batteryCost ?: saved.batteryCost,
        batteryLifeMi = current.batteryLifeMi ?: saved.batteryLifeMi,
        beltCost = current.beltCost ?: saved.beltCost,
        beltLifeMi = current.beltLifeMi ?: saved.beltLifeMi,
        miscYearly = current.miscYearly ?: saved.miscYearly,
        miscYearlyMi = current.miscYearlyMi ?: saved.miscYearlyMi,
    )

    private fun mergeFixed(current: FixedCosts, saved: FixedCosts) = FixedCosts(
        includeDepreciation = current.includeDepreciation ?: saved.includeDepreciation,
        purchasePrice = current.purchasePrice ?: saved.purchasePrice,
        totalLifetimeMi = current.totalLifetimeMi ?: saved.totalLifetimeMi,
        insuranceDeltaPerMonth = current.insuranceDeltaPerMonth ?: saved.insuranceDeltaPerMonth,
        registrationDeltaPerYear = current.registrationDeltaPerYear ?: saved.registrationDeltaPerYear,
        expectedAnnualMiles = current.expectedAnnualMiles ?: saved.expectedAnnualMiles,
    )
}
