package cloud.trotter.dashbuddy.notice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import cloud.trotter.dashbuddy.R

/**
 * The "Weekly plan" notification channel (#981 / brief §8's trigger) — the Sunday-evening nudge and
 * the grade of the week just finished.
 *
 * **Its own channel, deliberately not [AppNoticeChannel].** A channel name IS the user's mute control,
 * and these two families are not the same promise. `AppNoticeChannel` carries one-off, honest
 * disclosures about how the app is working on this device — things a driver would want even if they
 * want nothing else. This is a **recurring engagement** notification: welcome, useful, and something a
 * driver is entitled to silence *without* also silencing a locale-boundary or recognition-health
 * notice. Filing an engagement nudge under a disclosure channel would make muting the nudge cost them
 * the disclosures — so it gets its own switch.
 *
 * **Default importance, not high.** It is a Sunday-evening suggestion, not an offer alert: it should
 * appear in the shade without interrupting whatever the driver is doing.
 */
object WeeklyPlanChannel {

    /** Stable channel id. Do not rename — the user's mute state is keyed on it. */
    const val ID = "weekly_plan_channel"

    /**
     * The notification id, in the same small-constant space the bubble (1), offer fallback (2),
     * odometer (101) and the [AppNoticeChannel] notices (102, 103) occupy. Per-offer ids live in the
     * disjoint `[2^30, 2^31)` namespace, so fixed small ids cannot collide.
     */
    const val NOTIFICATION_ID = 104

    /** Create (or refresh) the channel — cheap and idempotent by platform contract, called at post time. */
    fun ensure(context: Context, notificationManager: NotificationManager) {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                ID,
                context.getString(R.string.weekly_plan_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.weekly_plan_channel_description)
            },
        )
    }
}
