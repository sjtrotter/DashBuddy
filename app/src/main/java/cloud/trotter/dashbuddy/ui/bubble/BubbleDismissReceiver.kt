package cloud.trotter.dashbuddy.ui.bubble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Observes the bubble NOTIFICATION's delete intent; never re-posts anything. The delete intent fires
 * when the notification is swiped from the shade, cleared with "Clear all", or its channel/package is
 * banned — NOT when the dasher drags the chathead to the dismiss target (Android keeps the
 * notification in the shade for that gesture). So this line means "the notification was removed
 * out of band", not "the bubble was dismissed"; the reason-coded signal
 * (`NotificationListenerService.onNotificationRemoved(..., reason)`) is #1226.
 */
class BubbleDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Timber.tag("Bubble").i("bubble notification removed (deleteIntent) id=%d", BubbleManager.BUBBLE_NOTIFICATION_ID)
    }
}
