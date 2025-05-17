//package cloud.trotter.dashbuddy.ui
//
//import android.app.ActivityOptions
//import android.app.Notification
//import android.app.NotificationChannel
//import android.app.NotificationManager
//import android.app.PendingIntent
//import android.app.Service
//import android.content.Context
//import android.content.Intent
//import android.content.pm.ServiceInfo
//import android.os.Build
//import android.os.Bundle
//import android.os.IBinder
//import android.util.Log
//import androidx.annotation.RequiresApi
//import androidx.core.app.NotificationCompat
//import androidx.core.app.Person
//import androidx.core.content.pm.ShortcutInfoCompat
//import androidx.core.content.pm.ShortcutManagerCompat
//import androidx.core.graphics.drawable.IconCompat
//import cloud.trotter.dashbuddy.BubbleActivity
//import cloud.trotter.dashbuddy.DashBuddyApplication
//import cloud.trotter.dashbuddy.R
//import java.util.Date
//
//class Bubble : Service() {
//
//    private lateinit var icon: IconCompat
//    private lateinit var person: Person
//    private lateinit var shortcut: ShortcutInfoCompat
//
//    companion object {
//        const val CHANNEL_ID = "bubble_channel"
//        const val NOTIFICATION_ID = 1
//        private const val TAG = "BubbleService"
//    }
//
//    override fun onCreate() {
//        super.onCreate()
//        Log.d(TAG, "onCreate: BubbleService created")
//        icon = IconCompat.createWithResource(
//            DashBuddyApplication.context, R.drawable.bag_red_idle)
//        person = Person.Builder()
//            .setName("DashBuddy")
//            .setIcon(icon)
//            .setImportant(true)
//            .build()
//        shortcut = ShortcutInfoCompat.Builder(DashBuddyApplication.context, "DashBuddy_Shortcut")
//            .setLongLived(true)
//            .setIntent(Intent(DashBuddyApplication.context, BubbleActivity::class.java).apply {
//                action = Intent.ACTION_VIEW
//            })
//            .setShortLabel("DashBuddy")
//            .setIcon(icon)
//            .setPerson(person)
//            .build()
//        ShortcutManagerCompat.pushDynamicShortcut(DashBuddyApplication.context, shortcut)
//        DashBuddyApplication.bubbleService = this
//        createNotificationChannel()
//    }
//
//    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
//    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
//        Log.d(TAG, "onStartCommand: BubbleService started")
//        val notification = create("Started!")
//        post(notification)
//        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
//        return START_STICKY
//    }
//
//    override fun onBind(intent: Intent?): IBinder? {
//        return null
//    }
//
//    private fun createNotificationChannel() {
//        val channel = NotificationChannel(
//            CHANNEL_ID,
//            "Bubble Channel",
//            NotificationManager.IMPORTANCE_HIGH
//        ).apply {
//            description = "Channel for Bubble Notifications"
//        }
//        val notificationManager =
//            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
//        notificationManager.createNotificationChannel(channel)
//    }
//
//    fun post(notification: Notification) {
//        DashBuddyApplication.notificationManager.notify(NOTIFICATION_ID, notification)
//    }
//
//    private fun getActivityOptionsBundle(): Bundle {
//        val activityOptions = ActivityOptions.makeBasic()
//
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
//            activityOptions.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
//        }
//        return activityOptions.toBundle()
//    }
//
//    fun create(message: String): Notification {
//        Log.d(TAG, "createNotification: Creating notification")
//
//        val builder = NotificationCompat.Builder(DashBuddyApplication.context, CHANNEL_ID)
//
//        val target = Intent(DashBuddyApplication.context, BubbleActivity::class.java)
////        target.putExtra("ActivityOptions",getActivityOptionsBundle())
//        val bubbleIntent = PendingIntent.getActivity(
//            DashBuddyApplication.context, 0, target,
//            PendingIntent.FLAG_UPDATE_CURRENT
//                    or PendingIntent.FLAG_MUTABLE
////                    or getActivityOptionsBundle()
////                    or PendingIntent.FLAG_ALLOW_BACKGROUND_ACTIVITY_STARTS
//        )
//
//        val bubbleMetadata = NotificationCompat.BubbleMetadata.Builder(bubbleIntent, icon)
//            .setDesiredHeight(400)
//            .setSuppressNotification(true)
//            .setAutoExpandBubble(true)
//            .build()
//
//        with(builder) {
//            setBubbleMetadata(bubbleMetadata)
//            setStyle(
//                NotificationCompat.MessagingStyle(person).addMessage(
//                    NotificationCompat.MessagingStyle.Message(
//                        message,
//                        Date().time,
//                        person
//                    )
//                )
//            )
//            setShortcutId(shortcut.id)
//            addPerson(person)
//        }
//
//        with(builder) {
//            setContentTitle("DashBuddy")
//            setSmallIcon(
//                icon
////                IconCompat.createWithResource(DashBuddyApplication.context, R.drawable.dashly)
//            )
//            setCategory(NotificationCompat.CATEGORY_MESSAGE)
//            setContentIntent(bubbleIntent)
//            setShowWhen(true)
//        }
//
//        return builder.build()
//    }
//}