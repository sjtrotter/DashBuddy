package cloud.trotter.dashbuddy.bubble

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import cloud.trotter.dashbuddy.BubbleActivity
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.bubble.Notification as BubbleNotification

class Service : Service() {
    private lateinit var notificationManager: NotificationManager
    private lateinit var dashBuddyPerson: Person
    private lateinit var bubbleShortcut: ShortcutInfoCompat
    private lateinit var dashBuddyIcon: IconCompat
    private lateinit var dashBuddyLocusId: LocusIdCompat
    private lateinit var messagingStyle: NotificationCompat.MessagingStyle

    // Flag to indicate if core components are initialized
    private var areComponentsInitialized = false


    companion object {
        const val CHANNEL_ID = "bubble_channel"
        const val NOTIFICATION_ID = 1
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
        createNotificationChannel()

        // 2. Define the "Person" representing DashBuddy
        dashBuddyIcon = IconCompat.createWithResource(DashBuddyApplication.context, R.drawable.bag_red_idle)
        dashBuddyPerson = Person.Builder()
            .setName("DashBuddy")
            .setIcon(dashBuddyIcon)
            .setKey(DASHBUDDY_PERSON_KEY) // Added a stable key for the Person
            .setImportant(true)
            .build()

        messagingStyle = NotificationCompat.MessagingStyle(dashBuddyPerson)


        // Initialize LocusId here
        dashBuddyLocusId = LocusIdCompat("${SHORTCUT_ID}_Locus")
        Log.d(TAG, "Initialized LocusId: $dashBuddyLocusId")

        // 3. Create and push the dynamic shortcut for the bubble
        val shortcutIntent = Intent(DashBuddyApplication.context, BubbleActivity::class.java).apply {
            action = Intent.ACTION_VIEW
        }
        bubbleShortcut = ShortcutInfoCompat.Builder(DashBuddyApplication.context, SHORTCUT_ID)
            .setLongLived(true)
            .setIntent(shortcutIntent)
            .setShortLabel("DashBuddy")
            .setIcon(dashBuddyIcon)
            .setPerson(dashBuddyPerson)
            .setCategories(setOf(SHORTCUT_CATEGORY_CONVERSATION))
            .setLocusId(dashBuddyLocusId) // Associate LocusId with the shortcut
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(DashBuddyApplication.context, bubbleShortcut)

        areComponentsInitialized = true // Mark components as initialized

        // Make service instance accessible (if your design relies on this)
        // Be cautious with static references to services.
        DashBuddyApplication.bubbleService = this
        Log.d(TAG, "onCreate: BubbleService created successfully.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isServiceRunningIntentional = true // Mark that the service is now intentionally running
        Log.d(TAG, "onStartCommand: BubbleService started.")

        // Use message from intent if provided (e.g., from showMessageInBubble starting the service)
        // Otherwise, use a default initial message.
        val messageToShow = intent?.getStringExtra(EXTRA_MESSAGE) ?: "DashBuddy is active!"

        val bubbleContentPendingIntent = PendingIntent.getActivity(
            DashBuddyApplication.context,
            0,
            Intent(DashBuddyApplication.context, BubbleActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val notification = BubbleNotification.create(
            context = DashBuddyApplication.context,
            channelId = CHANNEL_ID,
            senderPerson = dashBuddyPerson,
            shortcutId = bubbleShortcut.id,
            bubbleIcon = dashBuddyIcon,
            messagingStyle = messagingStyle,
            messageText = messageToShow, // Use the determined message
            contentIntent = bubbleContentPendingIntent,
            locusId = dashBuddyLocusId, // Use the member variable
            autoExpandBubble = true, // Good for initial launch or if explicitly requested
            suppressNotification = true // Good for subsequent messages
        )

        try {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                // For API < 34, no specific foregroundServiceType needed for startForeground for bubbles
                // if the manifest has foregroundServiceType="specialUse"
                0 // No specific type, but the manifest declaration counts.
            }
            // If serviceType is 0 for API < 34, just call startForeground(id, notification)
            if (serviceType != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, serviceType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
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
        isServiceRunningIntentional = false // Mark that the service is no longer intentionally running
        areComponentsInitialized = false
        if (DashBuddyApplication.bubbleService == this) {
            DashBuddyApplication.bubbleService = null // Clear static reference
        }
        Log.d(TAG, "onDestroy: BubbleService destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "DashBuddy Bubbles",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Channel for DashBuddy bubble notifications."
//            setAllowBubbles(true)
            setAllowBubbles(true)
            setConversationId(CHANNEL_ID, "dashbuddy")
        }
        notificationManager.createNotificationChannel(channel)
        Log.d(TAG, "Notification channel created/updated.")
    }

    /**
     * Public method to show a new message in the bubble.
     * If the service is not running, it will attempt to start it with this message.
     */
    fun showMessageInBubble(message: String) {
        Log.d(TAG, "showMessageInBubble called with message: '$message'")

        if (!isServiceRunningIntentional || !areComponentsInitialized) {
            Log.w(TAG, "Service not running or not initialized. Attempting to start service with this message.")
            val startIntent = Intent(DashBuddyApplication.context,
                cloud.trotter.dashbuddy.bubble.Service::class.java).apply {
                putExtra(EXTRA_MESSAGE, message) // Pass the message to onStartCommand
            }
            ContextCompat.startForegroundService(DashBuddyApplication.context, startIntent)
            // The message will be displayed when onStartCommand processes this intent.
            // We return here because the current instance might not be fully ready yet.
            return
        }

        // If service is running and initialized, proceed to update the notification
        Log.d(TAG, "Service is running. Updating bubble notification with message: '$message'")

        val bubbleContentPendingIntent = PendingIntent.getActivity(
            DashBuddyApplication.context,
            0,
            Intent(DashBuddyApplication.context, BubbleActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val newNotification = BubbleNotification.create(
            context = DashBuddyApplication.context,
            channelId = CHANNEL_ID,
            senderPerson = dashBuddyPerson,
            shortcutId = bubbleShortcut.id,
            bubbleIcon = dashBuddyIcon,
            messageText = message,
            messagingStyle = messagingStyle,
            contentIntent = bubbleContentPendingIntent,
            locusId = dashBuddyLocusId, // Use the member variable
            suppressNotification = true,
            autoExpandBubble = true // Usually false for subsequent messages
        )
        notificationManager.notify(NOTIFICATION_ID, newNotification)

        Log.d(TAG, "Updated bubble notification posted.")
    }
}
