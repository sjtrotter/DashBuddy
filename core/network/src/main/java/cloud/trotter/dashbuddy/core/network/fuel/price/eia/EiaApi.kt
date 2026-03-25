package cloud.trotter.dashbuddy.core.network.fuel.price.eia

import cloud.trotter.dashbuddy.core.network.fuel.price.eia.dto.EiaResponseWrapper
import retrofit2.http.GET
import retrofit2.http.Query

interface EiaApi {
    @GET("v2/petroleum/pri/gnd/data/")
    suspend fun getNationalAverage(
        @Query("api_key") apiKey: String,
        @Query("frequency") frequency: String = "weekly",
        @Query("data[0]") dataMode: String = "value",
        @Query("facets[series][]") seriesId: String,
        @Query("sort[0][column]") sortColumn: String = "period",
        @Query("sort[0][direction]") sortDirection: String = "desc",
        @Query("length") limit: Int = 1
    ): EiaResponseWrapper
}