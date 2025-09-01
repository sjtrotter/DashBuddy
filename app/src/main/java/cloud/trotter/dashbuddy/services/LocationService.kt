package cloud.trotter.dashbuddy.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.data.current.CurrentRepo
import cloud.trotter.dashbuddy.data.dash.DashRepo
import cloud.trotter.dashbuddy.data.order.OrderRepo
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import cloud.trotter.dashbuddy.log.Logger as Log

class LocationService : Service() {

    private val tag = "LocationService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var locationTrackingJob: Job? = null

    private lateinit var currentRepo: CurrentRepo
    private lateinit var dashRepo: DashRepo
    private lateinit var orderRepo: OrderRepo

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "LocationServiceChannel"
        const val NOTIFICATION_ID = 101
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "onCreate called")
        currentRepo = DashBuddyApplication.currentRepo
        dashRepo = DashBuddyApplication.dashRepo
        orderRepo = DashBuddyApplication.orderRepo
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(tag, "onStartCommand called")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Tracking location for active dash..."))

        locationTrackingJob?.cancel()
        locationTrackingJob = serviceScope.launch {
            observeDashState()
        }

        return START_STICKY
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeDashState() {
        Log.i(tag, "Starting to observe dash state.")
        currentRepo.currentDashStateFlow
            // --- THIS IS THE FIX ---
            // 1. Map the flow to only the boolean `isActive` state.
            .map { it?.isActive == true && it.dashId != null }
            // 2. Only proceed if this boolean value has actually changed (e.g., from false to true).
            //    This will ignore updates to lastLatitude/lastLongitude.
            .distinctUntilChanged()
            .flatMapLatest { isActive ->
                if (isActive) {
                    Log.i(tag, "Dash is now active. Starting location updates.")
                    locationFlow()
                } else {
                    Log.i(tag, "Dash is no longer active. Stopping location updates.")
                    emptyFlow()
                }
            }
            // --- END OF FIX ---
            .catch { e -> Log.e(tag, "Error in location flow: ${e.message}") }
            .onEach { location ->
                processNewLocation(location)
            }
            .launchIn(serviceScope)
    }

    private suspend fun processNewLocation(newLocation: Location) {
        val currentDash = currentRepo.getCurrentDashState() ?: return
        val lastLat = currentDash.lastLatitude
        val lastLon = currentDash.lastLongitude

        if (lastLat != null && lastLon != null) {
            val lastLocation = Location("").apply {
                latitude = lastLat
                longitude = lastLon
            }
            val distanceInMiles = newLocation.distanceTo(lastLocation) / 1609.34

            if (distanceInMiles > 0.001) {
                Log.d(tag, "Distance calculated: $distanceInMiles miles")
                currentDash.dashId?.let { dashRepo.incrementDashMileage(it, distanceInMiles) }
                currentDash.activeOrderId?.let {
                    orderRepo.incrementOrderMileage(
                        it,
                        distanceInMiles
                    )
                }
            }
        }

        currentRepo.updateLastLocation(newLocation.latitude, newLocation.longitude)
    }

    private fun locationFlow(): Flow<Location> = callbackFlow {
        if (ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(tag, "Location permission not granted. Cannot start updates.")
            close(SecurityException("Location permission not granted."))
            return@callbackFlow
        }

        val locationRequest =
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000L).apply {
                setMinUpdateIntervalMillis(5000L)
            }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let {
                    Log.v(tag, "New location received: $it")
                    trySend(it)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )

        awaitClose {
            Log.i(tag, "Location flow cancelled. Removing updates.")
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "DashBuddy Location Service",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Dash In Progress")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_location)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(tag, "onDestroy called")
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
