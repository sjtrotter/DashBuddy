package cloud.trotter.dashbuddy.core.network.fuel.price.eia

import cloud.trotter.dashbuddy.core.network.BuildConfig
import cloud.trotter.dashbuddy.domain.model.location.UserLocation
import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType
import cloud.trotter.dashbuddy.domain.provider.FuelPriceDataSource
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EiaFuelPrice @Inject constructor(
    private val api: EiaApi
) : FuelPriceDataSource {

    private val apiKey = BuildConfig.EIA_API_KEY

    override suspend fun getFuelPrice(
        userLocation: UserLocation?,
        fuelType: FuelType
    ): Result<Float> {
        return try {
            if (fuelType == FuelType.ELECTRICITY) {
                return Result.failure(IllegalStateException("Electricity prices are handled manually."))
            }
            val regionCode = getRegionCode(userLocation)

            Timber.tag("Network").i("Fetching Gas Price for ${fuelType.name} in region: $regionCode")

            val seriesId = buildSeriesId(fuelType, regionCode)

            val response = api.getNationalAverage(apiKey = apiKey, seriesId = seriesId)
            val latestPrice = response.response.data.firstOrNull()?.value

            if (latestPrice != null && latestPrice > 0f) {
                Timber.tag("Network").i("Successfully fetched EIA gas price: $$latestPrice")
                Result.success(latestPrice)
            } else {
                Result.failure(Exception("EIA API returned empty or invalid data"))
            }
        } catch (e: Exception) {
            // #692 P7: this used to be its own ERROR line, double-reporting with the WorkManager
            // caller's WARN ("Failed to fetch gas prices... will retry later") for the exact same
            // failure. A WorkManager-retried fetch is neither lost data nor a crashed subsystem, so
            // there is exactly ONE shareable line for this failure now — the caller's WARN, which
            // carries this exception's message. This site keeps the full exception + stack trace on
            // the DEBUG firehose only, for on-device diagnosis. (#348: no secret is logged here —
            // the api_key never appears in an exception message; HTTP client secret redaction is
            // separately enforced in NetworkClientFactory.)
            Timber.tag("Network").d(e, "Failed to fetch from EIA API")
            Result.failure(e)
        }
    }

    /**
     * Constructs the exact EIA Series ID string based on Fuel Type and Geographic Region.
     */
    internal fun buildSeriesId(fuelType: FuelType, regionCode: String): String {
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
     * Extracts the US State from the UserLocation and maps it to an EIA PADD Region.
     * Falls back to "NUS" (National US) if the state is missing or unrecognized.
     */
    internal fun getRegionCode(userLocation: UserLocation?): String {
        val stateName = userLocation?.stateName

        return if (!stateName.isNullOrEmpty()) {
            mapStateToPaddRegion(stateName)
        } else {
            Timber.tag("Network").w("State not specified in UserLocation, falling back to National Average.")
            "NUS"
        }
    }

    /**
     * Maps standard US State names to EIA PADD Codes.
     * R10 = East Coast, R20 = Midwest, R30 = Gulf Coast, R40 = Rocky Mountain, R50 = West Coast
     */
    internal fun mapStateToPaddRegion(stateName: String): String {
        val upperState = stateName.uppercase(Locale.ROOT)
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
                Timber.tag("Network").w("Unrecognized state ($stateName), falling back to National Average.")
                "NUS"
            }
        }
    }
}