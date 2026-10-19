package cloud.trotter.dashbuddy.core.data.vehicle

import cloud.trotter.dashbuddy.core.datastore.vehicle.VehicleDataSource
import cloud.trotter.dashbuddy.domain.model.vehicle.Vehicle
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GarageRepository @Inject constructor(private val dataSource: VehicleDataSource) {
    private val mutex = Mutex()
    val vehicles = dataSource.stored.map { VehicleStorage.decode(it.json) }
    val activeVehicle = dataSource.stored.map { saved ->
        VehicleStorage.decode(saved.json).firstOrNull { it.id == saved.activeId }
    }

    suspend fun addVehicle(name: String, now: Long): Vehicle = mutex.withLock {
        val vehicle = Vehicle(UUID.randomUUID().toString(), name.trim(), now)
        val list = VehicleStorage.decode(dataSource.stored.first().json) + vehicle
        dataSource.save(VehicleStorage.encode(list), vehicle.id)
        vehicle
    }

    suspend fun selectVehicle(id: String) = mutex.withLock {
        val list = VehicleStorage.decode(dataSource.stored.first().json)
        require(list.any { it.id == id })
        dataSource.save(VehicleStorage.encode(list), id)
    }

    suspend fun clear() = mutex.withLock { dataSource.clear() }
}
