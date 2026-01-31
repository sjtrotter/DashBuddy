package cloud.trotter.dashbuddy.data.location

import android.location.Location
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
    val locationUpdates: Flow<Location>
}