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

@Singleton
class GarageRepository @Inject constructor(private val dataSource: VehicleDataSource) {
    private val mutex = Mutex()
    private data class Garage(val vehicles: List<Vehicle>, val activeId: String?)
    private val garage = dataSource.stored.map { saved -> Garage(readVehicles(saved.json), saved.activeId) }
    val vehicles = garage.map { it.vehicles }
    val activeVehicle = garage.map { g ->
        g.vehicles.firstOrNull { it.id == g.activeId }
    }

    suspend fun addVehicle(name: String, now: Long): Vehicle = mutex.withLock {
        val vehicle = Vehicle(UUID.randomUUID().toString(), name.trim(), now)
        val list = (VehicleStorage.decode(dataSource.stored.first().json)
            ?: throw IllegalStateException("vehicles unreadable")) + vehicle
        dataSource.save(VehicleStorage.encode(list), vehicle.id)
        vehicle
    }

    suspend fun selectVehicle(id: String) = mutex.withLock {
        val list = VehicleStorage.decode(dataSource.stored.first().json)
            ?: throw IllegalStateException("vehicles unreadable")
        require(list.any { it.id == id })
        dataSource.save(VehicleStorage.encode(list), id)
    }

    private fun readVehicles(raw: String?): List<Vehicle> = VehicleStorage.decode(raw) { error ->
        if (warnedUnreadable.compareAndSet(false, true)) {
            Timber.tag("Garage").w("%s", error.javaClass.simpleName)
        }
    }.orEmpty()

    private companion object {
        val warnedUnreadable = AtomicBoolean(false)
    }
}
