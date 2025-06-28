package cloud.trotter.dashbuddy.services.bubble // You can choose your package structure

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.graphics.drawable.IconCompat
import java.util.Date
import cloud.trotter.dashbuddy.log.Logger as Log

/**
 * Helper object to create bubble-specific notifications.
 */
object Notification {

    /**
     * Creates a notification configured for display as a bubble.
     *
     * @param context The application context.
     * @param channelId The ID of the notification channel to use.
     * @param senderPerson The Person object representing the sender of the message (e.g., "DashBuddy").
     * @param shortcutId The ID of the shortcut this bubble is associated with.
     * @param bubbleIcon The icon to display for the bubble and notification.
     * @param messageText The text content of the message.
     * @param contentIntent The PendingIntent to launch when the bubble is tapped.
     * @param locusId Optional LocusIdCompat to link the notification to app state.
     * @param desiredHeight The desired height of the bubble's expanded view.
     * @param autoExpandAndSuppress True to have the bubble auto-expand when it first appears.
     *          (This also sets suppressNotification to true to disable notification and fly-out.)
     * @return A configured Notification object.
     */
    fun create(
        context: Context,
        channelId: String,
        senderPerson: Person,
        shortcutId: String,
        bubbleIcon: IconCompat,
        notificationIcon: IconCompat,
        messageText: CharSequence,
        contentIntent: PendingIntent,
        locusId: LocusIdCompat? = null,
        desiredHeight: Int = 600,
        autoExpandAndSuppress: Boolean = false
    ): Notification {
        Log.d(
            "BubbleNotificationHelper",
            "Creating messaging bubble notification with message: '$messageText', Locus ID: $locusId"
        )

        // 1. Create the BubbleMetadata
        val bubbleMetadataBuilder =
            NotificationCompat.BubbleMetadata.Builder(contentIntent, bubbleIcon)
                .setDesiredHeight(desiredHeight)
                .setIntent(contentIntent)
                .setIcon(bubbleIcon)
                .setSuppressNotification(autoExpandAndSuppress)
                .setAutoExpandBubble(autoExpandAndSuppress)

        val bubbleMetadata = bubbleMetadataBuilder.build()

        // 2. Create the MessagingStyle for the notification content
        val messagingStyle = NotificationCompat.MessagingStyle(senderPerson)
            .addMessage(
                NotificationCompat.MessagingStyle.Message(
                    messageText,
                    Date().time,
                    senderPerson
                )
            )

        // 3. Build the Notification
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(notificationIcon)
            .setContentTitle(senderPerson.name)
            .setContentText(messageText)
            .setShortcutId(shortcutId)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .addPerson(senderPerson)
            .setStyle(messagingStyle)
            .setBubbleMetadata(bubbleMetadata)
            .setContentIntent(contentIntent)
            .setLocusId(locusId)
            .setShowWhen(true)

        return builder.build()
    }
}
