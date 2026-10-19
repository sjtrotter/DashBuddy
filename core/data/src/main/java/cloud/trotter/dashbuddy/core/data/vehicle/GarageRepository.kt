package cloud.trotter.dashbuddy.core.data.vehicle

import cloud.trotter.dashbuddy.core.datastore.vehicle.VehicleDataSource
import cloud.trotter.dashbuddy.domain.model.vehicle.Vehicle
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleStorage
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
    val active: Vehicle? get() = vehicles.firstOrNull { it.id == activeId }
}

class VehiclesUnreadableException : IllegalStateException("vehicles unreadable")

@Singleton
class GarageRepository @Inject constructor(private val dataSource: VehicleDataSource) {
    private val mutex = Mutex()
    private val warnedUnreadable = AtomicBoolean(false)
    val garage = dataSource.stored.map { saved ->
        val vehicles = readVehicles(saved.json)
        if (vehicles == null) Garage(emptyList(), null, readable = false)
        else Garage(vehicles, saved.activeId, readable = true)
    }

    suspend fun addVehicle(name: String, now: Long): Vehicle = mutex.withLock {
        val vehicle = Vehicle(UUID.randomUUID().toString(), name.trim(), now)
        val list = (VehicleStorage.decode(dataSource.stored.first().json)
            ?: throw VehiclesUnreadableException()) + vehicle
        dataSource.save(VehicleStorage.encode(list), vehicle.id)
        vehicle
    }

    /** True when the active vehicle was written; false for an unknown id (a stale switcher row), which changes nothing. */
    suspend fun selectVehicle(id: String): Boolean = mutex.withLock {
        val list = VehicleStorage.decode(dataSource.stored.first().json)
            ?: throw VehiclesUnreadableException()
        if (list.none { it.id == id }) {
            Timber.tag("Garage").w("Ignored a select for an unknown vehicle id")
            return@withLock false
        }
        dataSource.save(VehicleStorage.encode(list), id)
        true
    }

    private fun readVehicles(raw: String?): List<Vehicle>? = VehicleStorage.decode(raw) { error ->
        if (warnedUnreadable.compareAndSet(false, true)) {
            Timber.tag("Garage").w("%s", error.javaClass.simpleName)
        }
    }

}
