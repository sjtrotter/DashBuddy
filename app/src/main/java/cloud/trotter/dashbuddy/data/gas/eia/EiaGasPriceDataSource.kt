package cloud.trotter.dashbuddy.data.gas.eia

import cloud.trotter.dashbuddy.BuildConfig
import cloud.trotter.dashbuddy.data.gas.GasPriceDataSource
import cloud.trotter.dashbuddy.model.vehicle.FuelType
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EiaGasPriceDataSource @Inject constructor(
    private val api: EiaApi
) : GasPriceDataSource {

    private val apiKey = BuildConfig.EIA_API_KEY

    override suspend fun getGasPrice(
        lat: Double?,
        lon: Double?,
        fuelType: FuelType
    ): Result<Float> {
        return try {
            Timber.i("Fetching National Average Gas Price for ${fuelType.name} from U.S. EIA...")

            // Map the FuelType to the correct EIA Series ID for U.S. National Averages
            val seriesId = when (fuelType) {
                FuelType.REGULAR -> "EMM_EPMRU_PTE_NUS_DPG"
                FuelType.MIDGRADE -> "EMM_EPMMU_PTE_NUS_DPG"
                FuelType.PREMIUM -> "EMM_EPMPU_PTE_NUS_DPG"
                FuelType.DIESEL -> "EMD_EPD2D_PTE_NUS_DPG"
            }

            val response = api.getNationalAverage(apiKey = apiKey, seriesId = seriesId)
            val latestPrice = response.response.data.firstOrNull()?.value

            if (latestPrice != null && latestPrice > 0f) {
                Timber.i("Successfully fetched EIA gas price: $$latestPrice")
                Result.success(latestPrice)
            } else {
                Result.failure(Exception("EIA API returned empty or invalid data"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch from EIA API")
            Result.failure(e)
        }
    }
}