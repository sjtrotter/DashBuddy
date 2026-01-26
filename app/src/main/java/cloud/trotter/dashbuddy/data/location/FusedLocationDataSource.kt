package cloud.trotter.dashbuddy.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton
import cloud.trotter.dashbuddy.log.Logger as Log

/**
 * The specific implementation that talks to Google's FusedLocationProviderClient.
 * It handles the "Hardware Kill Switch" via the awaitClose block.
 */
@Singleton
class FusedLocationDataSource @Inject constructor(
    @param:ApplicationContext private val context: Context
) : LocationDataSource {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission") // Permission is checked by the UI/Service before starting
    override val locationUpdates: Flow<Location> = callbackFlow {
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { trySend(it) }
            }
        }

        // High accuracy, updates every 2-5 seconds
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L).apply {
            setMinUpdateIntervalMillis(2000L)
            // Optional: Don't wake up for micro-movements (saves battery)
            setMinUpdateDistanceMeters(5f)
        }.build()

        Log.i("FusedLocation", "⚡ Starting GPS updates...")
        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { e ->
                Log.e("FusedLocation", "Failed to start GPS", e)
                close(e)
            }

        // --- THE KILL SWITCH ---
        // This runs automatically when the collector (OdometerRepository) stops listening.
        awaitClose {
            Log.i("FusedLocation", "🛑 Stopping GPS updates (Hardware Off)")
            fusedClient.removeLocationUpdates(callback)
        }
    }
}