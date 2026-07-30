package cloud.trotter.dashbuddy.notice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import cloud.trotter.dashbuddy.R

/**
 * The one "App notices" notification channel (#938, extended by #937) — one-off, honest
 * disclosures about how DashBuddy itself is working on this device.
 *
 * A dedicated channel rather than a reused one: a channel name IS the user's mute control, so
 * filing these under "Offer Alerts" or "Odometer Active" would both mislabel them and let an
 * unrelated mute silence a disclosure. And ONE channel for the family rather than one per
 * notice — a settings screen with a channel per diagnostic is noise, and every member has the
 * same "you'd want to know this" character, so they share a mute.
 *
 * The id + copy live here (principle 5) because two notifiers now post on this channel and a
 * second hand-maintained copy of the id would be a divergence bug waiting to fire.
 */
object AppNoticeChannel {

    /** Stable channel id. Do not rename — the user's mute state is keyed on it. */
    const val ID = "app_notice_channel"

    /**
     * Create (or refresh) the channel. Cheap and idempotent by platform contract, and called
     * lazily at post time: a channel the dasher never needs shouldn't clutter their settings.
     */
    fun ensure(context: Context, notificationManager: NotificationManager) {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                ID,
                context.getString(R.string.app_notice_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.app_notice_channel_description)
            },
        )
    }

    /**
     * Notification ids for this channel, in the small-constant range the bubble (1), offer
     * fallback (2) and odometer (101) already occupy; `BubbleManager.offerNotificationId`
     * deliberately folds per-offer ids into the disjoint `[2^30, 2^31)` namespace, so fixed
     * small ids cannot collide.
     */
    object Ids {
        /** #938 — the once-per-install non-English locale notice. */
        const val LOCALE_BOUNDARY = 102

        /** #937 — recognition has stopped understanding a platform's screens. */
        const val RECOGNITION_HEALTH = 103
    }
}
