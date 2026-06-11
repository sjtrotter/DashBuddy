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
    // Result unwrapped at this boundary (#366) — the UI sees the same
    // empty-on-failure shape as before; failures were logged at the source.
    suspend fun getYears(): List<String> = dataSource.getYears().getOrElse { emptyList() }

    // The "Not Listed" sentinel is a UI concern — the wizard layer prepends
    // it (#364); data results stay pure EPA values.
    suspend fun getMakes(year: String): List<String> =
        dataSource.getMakes(year).getOrElse { emptyList() }

    suspend fun getModels(year: String, make: String): List<String> =
        dataSource.getModels(year, make).getOrElse { emptyList() }

    suspend fun getVehicleOptions(year: String, make: String, model: String): List<VehicleOption> =
        dataSource.getVehicleOptions(year, make, model).getOrElse { emptyList() }

    suspend fun getVehicleDetails(vehicleId: String): VehicleDetails? =
        dataSource.getVehicleDetails(vehicleId).getOrNull()

    suspend fun getCombinedMpg(vehicleId: String): Float? =
        dataSource.getVehicleDetails(vehicleId).getOrNull()?.combinedMpg
}
