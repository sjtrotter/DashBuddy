package cloud.trotter.dashbuddy.core.location

import cloud.trotter.dashbuddy.domain.model.location.Coordinates
import cloud.trotter.dashbuddy.domain.model.location.UserLocation
import kotlinx.coroutines.flow.Flow

/**
 * Interface for any raw location provider.
 * This abstracts away the Google Play Services library so we can mock it in tests.
 */
interface LocationDataSource {
    /**
     * A hot stream of location updates.
     * The GPS hardware is only active while this Flow is being collected.
     */
    val locationUpdates: Flow<Coordinates>
    suspend fun getLastKnownLocation(): Coordinates?
    suspend fun getUserLocation(): UserLocation?
}