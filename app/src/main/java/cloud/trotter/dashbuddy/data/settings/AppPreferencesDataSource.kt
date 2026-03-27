package cloud.trotter.dashbuddy.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appSettingsDataStore by preferencesDataStore(name = "app_settings")

@Singleton
class AppPreferencesDataSource @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val ds = context.appSettingsDataStore

    object Keys {
        val IS_PRO_MODE = booleanPreferencesKey("is_pro_mode")
        val APP_THEME = stringPreferencesKey("app_theme")
        val VEHICLE_YEAR = stringPreferencesKey("vehicle_year")
        val VEHICLE_MAKE = stringPreferencesKey("vehicle_make")
        val VEHICLE_MODEL = stringPreferencesKey("vehicle_model")
        val VEHICLE_TRIM = stringPreferencesKey("vehicle_trim")
        val ESTIMATED_MPG = floatPreferencesKey("estimated_mpg")
        val FUEL_TYPE = stringPreferencesKey("vehicle_fuel_type")
        val IS_GAS_PRICE_AUTO = booleanPreferencesKey("is_gas_price_auto")
        val GAS_PRICE = floatPreferencesKey("gas_price")
        val SIM_PAY = doublePreferencesKey("sim_test_pay")
        val SIM_DIST = doublePreferencesKey("sim_test_dist")
    }

    val isProMode: Flow<Boolean> = ds.data.map { it[Keys.IS_PRO_MODE] ?: false }
    val appTheme: Flow<String> = ds.data.map { it[Keys.APP_THEME] ?: "system" }

    val vehicleYear: Flow<String> = ds.data.map { it[Keys.VEHICLE_YEAR] ?: "" }
    val vehicleMake: Flow<String> = ds.data.map { it[Keys.VEHICLE_MAKE] ?: "" }
    val vehicleModel: Flow<String> = ds.data.map { it[Keys.VEHICLE_MODEL] ?: "" }
    val vehicleTrim: Flow<String> = ds.data.map { it[Keys.VEHICLE_TRIM] ?: "" }
    val estimatedMpg: Flow<Float> = ds.data.map { it[Keys.ESTIMATED_MPG] ?: 25.0f }
    val fuelType: Flow<String?> = ds.data.map { it[Keys.FUEL_TYPE] }
    val isGasPriceAuto: Flow<Boolean> = ds.data.map { it[Keys.IS_GAS_PRICE_AUTO] ?: true }
    val gasPrice: Flow<Float> = ds.data.map { it[Keys.GAS_PRICE] ?: 3.50f }

    suspend fun update(transform: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        ds.edit { transform(it) }
    }
}