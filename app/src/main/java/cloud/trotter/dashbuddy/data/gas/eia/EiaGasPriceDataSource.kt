package cloud.trotter.dashbuddy.data.gas.eia

import android.content.Context
import android.location.Address
import android.location.Geocoder
import cloud.trotter.dashbuddy.BuildConfig
import cloud.trotter.dashbuddy.data.gas.GasPriceDataSource
import cloud.trotter.dashbuddy.model.vehicle.FuelType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class EiaGasPriceDataSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: EiaApi
) : GasPriceDataSource {

    private val apiKey = BuildConfig.EIA_API_KEY

    override suspend fun getGasPrice(
        lat: Double?,
        lon: Double?,
        fuelType: FuelType
    ): Result<Float> {
        return try {
            if (fuelType == FuelType.ELECTRICITY) {
                return Result.failure(IllegalStateException("Electricity prices are handled manually."))
            }
            val regionCode = getRegionCode(lat, lon)
            Timber.i("Fetching Gas Price for ${fuelType.name} in region: $regionCode")

            val seriesId = buildSeriesId(fuelType, regionCode)

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

    /**
     * Constructs the exact EIA Series ID string based on Fuel Type and Geographic Region.
     */
    private fun buildSeriesId(fuelType: FuelType, regionCode: String): String {
        val productCode = when (fuelType) {
            FuelType.REGULAR -> "EMM_EPMRU_PTE"
            FuelType.MIDGRADE -> "EMM_EPMMU_PTE"
            FuelType.PREMIUM -> "EMM_EPMPU_PTE"
            FuelType.DIESEL -> "EMD_EPD2D_PTE"
            FuelType.ELECTRICITY ->
                throw IllegalArgumentException("Cannot build EIA petroleum series ID for Electricity")
        }
        return "${productCode}_${regionCode}_DPG"
    }

    /**
     * Reverse-geocodes coordinates to a US State, then maps it to an EIA PADD Region.
     * Falls back to "NUS" (National US) if anything fails.
     */
    private suspend fun getRegionCode(lat: Double?, lon: Double?): String {
        if (lat == null || lon == null) return "NUS" // Fallback to National

        return try {
            val geocoder = Geocoder(context, Locale.getDefault())

            // Handle API 33+ vs Older APIs
            val stateName =
                suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            continuation.resume(addresses.firstOrNull()?.adminArea ?: "")
                        }

                        override fun onError(errorMessage: String?) {
                            Timber.w("Geocoder listener error: $errorMessage")
                            continuation.resume("") // Resume with empty string on error
                        }
                    })
                }

            if (stateName.isNotEmpty()) {
                mapStateToPaddRegion(stateName)
            } else {
                "NUS"
            }
        } catch (e: Exception) {
            Timber.w(e, "Geocoder failed to identify state, falling back to National Average.")
            "NUS"
        }
    }

    /**
     * Maps standard US State names to EIA PADD Codes.
     * R10 = East Coast, R20 = Midwest, R30 = Gulf Coast, R40 = Rocky Mountain, R50 = West Coast
     */
    private fun mapStateToPaddRegion(stateName: String): String {
        val upperState = stateName.uppercase(Locale.getDefault())
        return when (upperState) {
            // PADD 1: East Coast
            "MAINE", "NEW HAMPSHIRE", "VERMONT", "MASSACHUSETTS", "RHODE ISLAND", "CONNECTICUT",
            "NEW YORK", "PENNSYLVANIA", "NEW JERSEY", "DELAWARE", "MARYLAND", "VIRGINIA",
            "WEST VIRGINIA", "NORTH CAROLINA", "SOUTH CAROLINA", "GEORGIA", "FLORIDA", "DISTRICT OF COLUMBIA" -> "R10"

            // PADD 2: Midwest
            "OHIO", "INDIANA", "ILLINOIS", "MICHIGAN", "WISCONSIN", "MINNESOTA", "IOWA",
            "MISSOURI", "NORTH DAKOTA", "SOUTH DAKOTA", "NEBRASKA", "KANSAS", "OKLAHOMA", "TENNESSEE", "KENTUCKY" -> "R20"

            // PADD 3: Gulf Coast
            "ALABAMA", "MISSISSIPPI", "ARKANSAS", "LOUISIANA", "TEXAS", "NEW MEXICO" -> "R30"

            // PADD 4: Rocky Mountain
            "MONTANA", "IDAHO", "WYOMING", "UTAH", "COLORADO" -> "R40"

            // PADD 5: West Coast
            "WASHINGTON", "OREGON", "CALIFORNIA", "NEVADA", "ARIZONA", "ALASKA", "HAWAII" -> "R50"

            else -> {
                Timber.w("Unrecognized state ($stateName), falling back to National Average.")
                "NUS"
            }
        }
    }
}