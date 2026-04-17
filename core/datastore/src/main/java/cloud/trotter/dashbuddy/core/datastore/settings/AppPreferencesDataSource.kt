package cloud.trotter.dashbuddy.core.datastore.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.dashbuddy.core.datastore.di.AppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppPreferencesDataSource @Inject constructor(
    @param:AppPreferences private val ds: DataStore<Preferences>
) {
    private object Keys {
        val VEHICLE_YEAR = stringPreferencesKey("vehicle_year")
        val VEHICLE_MAKE = stringPreferencesKey("vehicle_make")
        val VEHICLE_MODEL = stringPreferencesKey("vehicle_model")
        val VEHICLE_TRIM = stringPreferencesKey("vehicle_trim")
        val ESTIMATED_MPG = floatPreferencesKey("estimated_mpg")
        val IS_GAS_PRICE_AUTO = booleanPreferencesKey("is_gas_price_auto")
        val GAS_PRICE = floatPreferencesKey("gas_price")
        val IS_PRO_MODE = booleanPreferencesKey("is_pro_mode")
        val APP_THEME = stringPreferencesKey("app_theme")
        val FUEL_TYPE = stringPreferencesKey("fuel_type")
        val SIM_PAY = doublePreferencesKey("sim_pay")
        val SIM_DIST = doublePreferencesKey("sim_dist")
    }

    // ============================================================================================
    // STREAMS
    // ============================================================================================
    val vehicleYear: Flow<String?> = ds.data.map { it[Keys.VEHICLE_YEAR] }
    val vehicleMake: Flow<String?> = ds.data.map { it[Keys.VEHICLE_MAKE] }
    val vehicleModel: Flow<String?> = ds.data.map { it[Keys.VEHICLE_MODEL] }
    val vehicleTrim: Flow<String?> = ds.data.map { it[Keys.VEHICLE_TRIM] }
    val estimatedMpg: Flow<Float?> = ds.data.map { it[Keys.ESTIMATED_MPG] }
    val isGasPriceAuto: Flow<Boolean> = ds.data.map { it[Keys.IS_GAS_PRICE_AUTO] ?: true }
    val gasPrice: Flow<Float?> = ds.data.map { it[Keys.GAS_PRICE] }
    val isProMode: Flow<Boolean> = ds.data.map { it[Keys.IS_PRO_MODE] ?: false }
    val appTheme: Flow<String?> = ds.data.map { it[Keys.APP_THEME] }
    val fuelType: Flow<String?> = ds.data.map { it[Keys.FUEL_TYPE] }

    // ============================================================================================
    // ENCAPSULATED WRITE ACTIONS
    // ============================================================================================
    suspend fun updateGasPrice(price: Float) {
        ds.edit { it[Keys.GAS_PRICE] = price }
    }

    suspend fun updateFuelType(type: String) {
        ds.edit { it[Keys.FUEL_TYPE] = type }
    }

    suspend fun setProMode(enabled: Boolean) {
        ds.edit { it[Keys.IS_PRO_MODE] = enabled }
    }

    suspend fun setTheme(theme: String) {
        ds.edit { it[Keys.APP_THEME] = theme }
    }

    suspend fun updateEconomySettings(
        year: String, make: String, model: String, trim: String,
        mpg: Float, isGasAuto: Boolean, price: Float
    ) {
        ds.edit { prefs ->
            prefs[Keys.VEHICLE_YEAR] = year
            prefs[Keys.VEHICLE_MAKE] = make
            prefs[Keys.VEHICLE_MODEL] = model
            prefs[Keys.VEHICLE_TRIM] = trim
            prefs[Keys.ESTIMATED_MPG] = mpg
            prefs[Keys.IS_GAS_PRICE_AUTO] = isGasAuto
            prefs[Keys.GAS_PRICE] = price
        }
    }

    suspend fun saveSimulationState(pay: Double, dist: Double) {
        ds.edit {
            it[Keys.SIM_PAY] = pay
            it[Keys.SIM_DIST] = dist
        }
    }

    suspend fun clear() {
        ds.edit { it.clear() }
    }
}