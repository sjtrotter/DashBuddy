package cloud.trotter.dashbuddy.core.network.vehicle.efficiency.epa

import cloud.trotter.dashbuddy.core.network.vehicle.efficiency.epa.dto.MenuItemsResponse
import cloud.trotter.dashbuddy.core.network.vehicle.efficiency.epa.dto.VehicleDetailsResponse
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query

interface EpaApi {

    @Headers("Accept: application/json")
    @GET("menu/year")
    suspend fun getYears(): MenuItemsResponse

    @Headers("Accept: application/json")
    @GET("menu/make")
    suspend fun getMakes(
        @Query("year") year: String
    ): MenuItemsResponse

    @Headers("Accept: application/json")
    @GET("menu/model")
    suspend fun getModels(
        @Query("year") year: String,
        @Query("make") make: String
    ): MenuItemsResponse

    @Headers("Accept: application/json")
    @GET("menu/options")
    suspend fun getVehicleOptions(
        @Query("year") year: String,
        @Query("make") make: String,
        @Query("model") model: String
    ): MenuItemsResponse

    @Headers("Accept: application/json")
    @GET("{id}")
    suspend fun getVehicleDetails(
        @Path("id") vehicleId: String
    ): VehicleDetailsResponse
}