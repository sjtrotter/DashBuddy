package cloud.trotter.dashbuddy.ui.bubble

import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.ShortcutInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import cloud.trotter.dashbuddy.ui.activities.BubbleActivity
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.data.log.dash.DashLogRepo
import cloud.trotter.dashbuddy.log.Logger as Log

class BubbleService : Service() {
    private lateinit var notificationManager: NotificationManager
    private lateinit var dashBuddyPerson: Person
    private lateinit var bubbleShortcut: ShortcutInfoCompat
    private lateinit var dashBuddyNotificationIcon: IconCompat
    private lateinit var dashBuddyPersonIcon: IconCompat
    private lateinit var dashBuddyLocusId: LocusIdCompat

    // Flag to indicate if core components are initialized
    private var areComponentsInitialized = false


    companion object {
        const val BUBBLE_CHANNEL_ID = "bubble_channel"
        const val SERVICE_CHANNEL_ID = "service_channel"
        const val BUBBLE_NOTIFICATION_ID = 1
        const val SERVICE_NOTIFICATION_ID = 2
        private const val SHORTCUT_ID = "DashBuddy_Bubble_Shortcut"
        private const val TAG = "BubbleService"
        const val EXTRA_MESSAGE = "extra_message_to_show" // Key for intent extra
        private const val DASHBUDDY_PERSON_KEY = "dashbuddy_user_key" // Stable key for the Person

        // Publicly accessible static variable to check if the service is intended to be running.
        // This is more reliable than an instance variable if the service instance gets recreated.
        @Volatile
        var isServiceRunningIntentional = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: BubbleService creating...")
        notificationManager = DashBuddyApplication.notificationManager

        // 1. Create Notification Channel (essential)
        createNotificationChannel(
            BUBBLE_CHANNEL_ID,
            "Bubble Channel",
            NotificationManager.IMPORTANCE_HIGH,
            "Bubble Notifications",
            true
        )

        // 2. Define the "Person" representing DashBuddy
        dashBuddyNotificationIcon = IconCompat.createWithResource(
            DashBuddyApplication.context, R.drawable.bag_red_idle
        )

        dashBuddyPersonIcon =
            IconCompat.createWithContentUri("android.resource://${packageName}/${R.drawable.bag_red_idle}")

        dashBuddyPerson = Person.Builder()
            .setName("DashBuddy")
            .setIcon(dashBuddyNotificationIcon)
            .setKey(DASHBUDDY_PERSON_KEY) // Added a stable key for the Person
            .setImportant(true)
//            .setBot(true)
            .build()

        // Initialize LocusId here
        dashBuddyLocusId = LocusIdCompat("${SHORTCUT_ID}_Locus")
        Log.d(TAG, "Initialized LocusId: $dashBuddyLocusId")

        // 3. Create and push the dynamic shortcut for the bubble
        val shortcutIntent =
            Intent(DashBuddyApplication.context, BubbleActivity::class.java).apply {
                action = Intent.ACTION_VIEW
            }
        bubbleShortcut = ShortcutInfoCompat.Builder(DashBuddyApplication.context, SHORTCUT_ID)
            .setLongLived(true)
            .setIntent(shortcutIntent)
            .setShortLabel("DashBuddy")
            .setIcon(dashBuddyNotificationIcon)
            .setPerson(dashBuddyPerson)
            .setCategories(setOf(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION))
            .setIsConversation()
            .setLocusId(dashBuddyLocusId)
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(DashBuddyApplication.context, bubbleShortcut)

        areComponentsInitialized = true // Mark components as initialized

        // Make service instance accessible (if your design relies on this)
        // Be cautious with static references to services.
        DashBuddyApplication.bubbleService = this
        Log.d(TAG, "onCreate: BubbleService created successfully.")
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isServiceRunningIntentional = true // Mark that the service is now intentionally running
        Log.d(TAG, "onStartCommand: BubbleService started.")

        // Use message from intent if provided (e.g., from showMessageInBubble starting the service)
        // Otherwise, use a default initial message.
        val messageToShow = intent?.getStringExtra(EXTRA_MESSAGE) ?: "DashBuddy is active!"

        createNotificationChannel(
            SERVICE_CHANNEL_ID,
            "Messenger Service",
            NotificationManager.IMPORTANCE_LOW,
            "Messenger Service",
            false
        )

        val notification =
            NotificationCompat.Builder(DashBuddyApplication.context, SERVICE_CHANNEL_ID)
                .setSmallIcon(dashBuddyNotificationIcon)
                .setContentTitle("DashBuddy")
                .setContentText("Messenger Service active.")
                .build()

        postNotification(messageToShow, true)

        try {
            val serviceType =
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            // If serviceType is 0 for API < 34, just call startForeground(id, notification)
            startForeground(SERVICE_NOTIFICATION_ID, notification, serviceType)
            Log.d(TAG, "Service started in foreground with message: '$messageToShow'")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
            isServiceRunningIntentional = false // Reset flag on error
            stopSelf() // Stop the service if it can't start in foreground
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        areComponentsInitialized = false
        if (DashBuddyApplication.bubbleService == this) {
            DashBuddyApplication.bubbleService = null // Clear static reference
            isServiceRunningIntentional =
                false // Mark that the service is no longer intentionally running
        }
        Log.d(TAG, "onDestroy: BubbleService destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel(
        channelId: String,
        name: String,
        importance: Int,
        channelDescription: String,
        allowBubbles: Boolean
    ) {

        val channel = NotificationChannel(
            channelId,
            name,
            importance
        ).apply {
            description = channelDescription
            setAllowBubbles(allowBubbles)
        }
        notificationManager.createNotificationChannel(channel)

        if (allowBubbles) {
            channel.setConversationId(channelId, "dashbuddy")
        }
        Log.d(TAG, "Notification channel $name created/updated.")
    }

    /**
     * Public method to show a new message in the bubble.
     * If the service is not running, it will attempt to start it with this message.
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    fun showMessageInBubble(message: CharSequence, expand: Boolean = false) {
        Log.d(TAG, "showMessageInBubble called with message: '$message'")

        Log.d(
            TAG,
            "Service Running: $isServiceRunningIntentional, Components Initialized: $areComponentsInitialized"
        )
        if (!isServiceRunningIntentional || !areComponentsInitialized) {
            Log.w(
                TAG,
                "Service not running or not initialized. Attempting to start service with this message."
            )
            val startIntent = Intent(
                DashBuddyApplication.context,
                BubbleService::class.java
            ).apply {
                putExtra(EXTRA_MESSAGE, message) // Pass the message to onStartCommand
            }
            ContextCompat.startForegroundService(DashBuddyApplication.context, startIntent)
            // The message will be displayed when onStartCommand processes this intent.
            // We return here because the current instance might not be fully ready yet.
        }

        // If service is running and initialized, proceed to update the notification
        Log.d(TAG, "Service is running. Updating bubble notification with message: '$message'")

        postNotification(message, expand)
        // --- Communicate this message to the UI/ViewModel via the Repository ---
        DashLogRepo.addLogMessage(message) // Timestamp will be default (System.currentTimeMillis())
        Log.d(TAG, "Added message to DashLogRepository.")
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun postNotification(message: CharSequence, expand: Boolean) {
        val activityOptions = ActivityOptions.makeBasic()
        activityOptions.pendingIntentCreatorBackgroundActivityStartMode =
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS

        val bubbleContentPendingIntent = PendingIntent.getActivity(
            DashBuddyApplication.context,
            0,
            Intent(
                DashBuddyApplication.context,
                BubbleActivity::class.java
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            activityOptions.toBundle()
        )

        val newNotification = BubbleNotification.create(
            context = DashBuddyApplication.context,
            channelId = BUBBLE_CHANNEL_ID,
            senderPerson = dashBuddyPerson,
            shortcutId = bubbleShortcut.id,
            bubbleIcon = dashBuddyPersonIcon,
            notificationIcon = dashBuddyNotificationIcon,
            messageText = message,
            contentIntent = bubbleContentPendingIntent,
            locusId = dashBuddyLocusId,
            autoExpandAndSuppress = expand
        )

        notificationManager.notify(BUBBLE_NOTIFICATION_ID, newNotification)

        Log.d(TAG, "Updated bubble notification posted.")
    }
}
