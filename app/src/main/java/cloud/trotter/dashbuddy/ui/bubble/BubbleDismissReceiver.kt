package cloud.trotter.dashbuddy.ui.bubble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/** Observes explicit user dismissal; never re-posts the bubble. */
class BubbleDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Timber.tag("Bubble").i("bubble dismissed by user id=%d", BubbleManager.BUBBLE_NOTIFICATION_ID)
    }
}
