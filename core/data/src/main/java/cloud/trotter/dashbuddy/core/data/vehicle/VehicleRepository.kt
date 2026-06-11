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

    // The "Not Listed" sentinel is a UI concern — the wizard layer prepends
    // it (#364); data results stay pure EPA values.
    suspend fun getMakes(year: String): List<String> = dataSource.getMakes(year)

    suspend fun getModels(year: String, make: String): List<String> =
        dataSource.getModels(year, make)

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
