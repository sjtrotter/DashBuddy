package cloud.trotter.dashbuddy.data.settings

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
    suspend fun updateGasPrice(price: Float) =
        dataSource.update { it[AppPreferencesDataSource.Keys.GAS_PRICE] = price }

    suspend fun updateFuelType(type: FuelType) =
        dataSource.update { it[AppPreferencesDataSource.Keys.FUEL_TYPE] = type.name }

    suspend fun setProMode(enabled: Boolean) =
        dataSource.update { it[AppPreferencesDataSource.Keys.IS_PRO_MODE] = enabled }

    suspend fun setTheme(theme: String) =
        dataSource.update { it[AppPreferencesDataSource.Keys.APP_THEME] = theme }

    suspend fun updateEconomySettings(
        year: String,
        make: String,
        model: String,
        trim: String,
        mpg: Float,
        isGasAuto: Boolean,
        price: Float
    ) {
        dataSource.update { prefs ->
            prefs[AppPreferencesDataSource.Keys.VEHICLE_YEAR] = year
            prefs[AppPreferencesDataSource.Keys.VEHICLE_MAKE] = make
            prefs[AppPreferencesDataSource.Keys.VEHICLE_MODEL] = model
            prefs[AppPreferencesDataSource.Keys.VEHICLE_TRIM] = trim
            prefs[AppPreferencesDataSource.Keys.ESTIMATED_MPG] = mpg
            prefs[AppPreferencesDataSource.Keys.IS_GAS_PRICE_AUTO] = isGasAuto
            prefs[AppPreferencesDataSource.Keys.GAS_PRICE] = price
        }
    }

    suspend fun saveSimulationState(pay: Double, dist: Double) {
        dataSource.update {
            it[AppPreferencesDataSource.Keys.SIM_PAY] =
                pay; it[AppPreferencesDataSource.Keys.SIM_DIST] = dist
        }
    }

    suspend fun clearPreferences() {
        Timber.w("Clearing App Preferences")
        dataSource.update { it.clear() }
    }
}