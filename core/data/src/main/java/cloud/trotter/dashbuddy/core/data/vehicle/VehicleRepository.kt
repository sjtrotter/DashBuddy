package cloud.trotter.dashbuddy.core.data.vehicle

import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleDetails
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleOption
import cloud.trotter.dashbuddy.domain.provider.VehicleEfficiencyDataSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VehicleRepository @Inject constructor(
    private val dataSource: VehicleEfficiencyDataSource
) {
    suspend fun getYears(): List<String> = dataSource.getYears()

    suspend fun getMakes(year: String): List<String> {
        val list = dataSource.getMakes(year).toMutableList()
        list.add(0, "Not Listed")
        return list
    }

    suspend fun getModels(year: String, make: String): List<String> {
        val list = dataSource.getModels(year, make).toMutableList()
        list.add(0, "Not Listed")
        return list
    }

    suspend fun getVehicleOptions(year: String, make: String, model: String): List<VehicleOption> {
        val list = dataSource.getVehicleOptions(year, make, model).toMutableList()
        list.add(0, VehicleOption(id = "NOT_LISTED", displayName = "Not Listed"))
        return list
    }

    suspend fun getVehicleDetails(vehicleId: String): VehicleDetails? =
        dataSource.getVehicleDetails(vehicleId)

    suspend fun getCombinedMpg(vehicleId: String): Float? =
        dataSource.getVehicleDetails(vehicleId)?.combinedMpg
}
