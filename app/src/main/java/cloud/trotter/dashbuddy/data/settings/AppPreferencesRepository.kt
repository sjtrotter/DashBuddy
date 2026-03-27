package cloud.trotter.dashbuddy.data.settings

import cloud.trotter.dashbuddy.core.datastore.settings.AppPreferencesDataSource
import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppPreferencesRepository @Inject constructor(
    private val dataSource: AppPreferencesDataSource
) {
    // ============================================================================================
    // STREAMS
    // ============================================================================================
    val vehicleYear = dataSource.vehicleYear
    val vehicleMake = dataSource.vehicleMake
    val vehicleModel = dataSource.vehicleModel
    val vehicleTrim = dataSource.vehicleTrim
    val estimatedMpg = dataSource.estimatedMpg
    val isGasPriceAuto = dataSource.isGasPriceAuto
    val gasPrice = dataSource.gasPrice
    val isProMode = dataSource.isProMode
    val appTheme = dataSource.appTheme

    // Maps the String from the Datastore into your pure Domain Enum
    val fuelType: Flow<FuelType> = dataSource.fuelType.map { savedType ->
        try {
            FuelType.valueOf(savedType ?: FuelType.REGULAR.name)
        } catch (_: Exception) {
            FuelType.REGULAR
        }
    }

    // ============================================================================================
    // WRITE ACTIONS
    // ============================================================================================
    suspend fun updateGasPrice(price: Float) = dataSource.updateGasPrice(price)

    // Passes the Enum as a String to keep the DataSource clean
    suspend fun updateFuelType(type: FuelType) = dataSource.updateFuelType(type.name)

    suspend fun setProMode(enabled: Boolean) = dataSource.setProMode(enabled)

    suspend fun setTheme(theme: String) = dataSource.setTheme(theme)

    suspend fun updateEconomySettings(
        year: String,
        make: String,
        model: String,
        trim: String,
        mpg: Float,
        isGasAuto: Boolean,
        price: Float
    ) = dataSource.updateEconomySettings(year, make, model, trim, mpg, isGasAuto, price)

    suspend fun saveSimulationState(pay: Double, dist: Double) =
        dataSource.saveSimulationState(pay, dist)

    suspend fun clearPreferences() {
        Timber.w("Clearing App Preferences")
        dataSource.clear()
    }
}