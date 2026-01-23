package cloud.trotter.dashbuddy.data.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.log.Logger
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LocationService : Service() {

    private val tag = "LocationService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // --- Dependencies ---
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // --- State ---
    private var lastLocation: Location? = null
    private var currentOdometer: Double = 0.0

    // --- Constants ---
    private val prefsName = "dashbuddy_odometer_prefs"
    private val keyOdometer = "odometer_reading_miles"
    private val keyLastLat = "last_latitude"
    private val keyLastLon = "last_longitude"

    private val cooldownLimitMS = 10 * 60 * 1000L // 10 Minutes
    private var lastKeepAliveTime: Long = 0L
    private var cooldownJob: Job? = null
    private var isTracking = false // Add this state flag

    companion object {
        const val ACTION_KEEP_ALIVE = "cloud.trotter.dashbuddy.services.KEEP_ALIVE"
        const val NOTIFICATION_CHANNEL_ID = "LocationServiceChannel"
        const val NOTIFICATION_ID = 101

        // Static helper for StateContext
        fun getCurrentOdometer(context: Context): Double {
            val prefs =
                context.getSharedPreferences("dashbuddy_odometer_prefs", MODE_PRIVATE)
            return prefs.getFloat("odometer_reading_miles", 0.0f).toDouble()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Logger.d(tag, "LocationService Created")
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        restoreOdometerState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isKeepAlive = intent?.action == ACTION_KEEP_ALIVE

        // If it's a KeepAlive but we aren't tracking, treat it like a full start
        if (!isKeepAlive || !isTracking) {
            Logger.i(tag, "Starting Odometer Service (Triggered by: ${intent?.action})")
            createNotificationChannel()
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
            startLocationUpdates()
            isTracking = true
        }

        // Always handle the heartbeat logic
        handleKeepAlive()

        return START_STICKY
    }

    private fun handleKeepAlive() {
        lastKeepAliveTime = System.currentTimeMillis()

        // Reset/Restart the cooldown monitor
        cooldownJob?.cancel()
        cooldownJob = serviceScope.launch {
            while (isActive) {
                delay(60_000L) // Check every minute
                checkCooldown()
            }
        }
    }

    private fun checkCooldown() {
        val timeSinceActivity = System.currentTimeMillis() - lastKeepAliveTime
        if (timeSinceActivity > cooldownLimitMS) {
            Logger.i(
                tag,
                "Cooldown expired ($timeSinceActivity ms) and no active dash. Stopping Odometer."
            )
            stopSelf()
            serviceScope.cancel()
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Logger.e(tag, "Location permission missing. Stopping service.")
            stopSelf()
            return
        }

        val locationRequest =
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L).apply {
                setMinUpdateIntervalMillis(2000L)
            }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    serviceScope.launch { processNewLocation(location) }
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun processNewLocation(location: Location) {
        // 1. Calculate Delta
        var distanceMiles = 0.0

        if (lastLocation != null) {
            val distanceMeters = location.distanceTo(lastLocation!!)

            // Noise Filter (ignore tiny GPS jitter < 5m)
            if (distanceMeters > 5) {
                distanceMiles = distanceMeters * 0.000621371
            }
        } else {
            Logger.i(tag, "Odometer anchor set: ${location.latitude}, ${location.longitude}")
        }

        // 2. Update Odometer (Always)
        if (distanceMiles > 0 || lastLocation == null) {
            if (distanceMiles > 0) currentOdometer += distanceMiles
            saveOdometerState(location)
        }

        lastLocation = location
    }

    private fun restoreOdometerState() {
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        currentOdometer = prefs.getFloat(keyOdometer, 0.0f).toDouble()

        if (prefs.contains(keyLastLat) && prefs.contains(keyLastLon)) {
            val lat = prefs.getFloat(keyLastLat, 0.0f).toDouble()
            val lon = prefs.getFloat(keyLastLon, 0.0f).toDouble()
            lastLocation = Location("gps_restored").apply {
                latitude = lat
                longitude = lon
                time = System.currentTimeMillis()
            }
            Logger.i(tag, "Restored Odometer: $currentOdometer miles.")
        }
    }

    private fun saveOdometerState(location: Location) {
        getSharedPreferences(prefsName, MODE_PRIVATE).edit {
            putFloat(keyOdometer, currentOdometer.toFloat())
            putFloat(keyLastLat, location.latitude.toFloat())
            putFloat(keyLastLon, location.longitude.toFloat())
        }
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "DashBuddy Location Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Odometer Active")
            .setContentText("")
            .setSmallIcon(R.drawable.ic_location)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.i(tag, "Odometer Service Destroyed")
        serviceScope.cancel()
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (_: Exception) {
            // Ignore
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}