package cloud.trotter.dashbuddy.notice

import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import android.content.Context
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.domain.analytics.WeeklyPlanGrade
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.ui.main.MainActivity
import cloud.trotter.dashbuddy.ui.main.navigation.Screen
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the Sunday-evening Weekly Plan notification (#981 / brief §8), deep-linking into the screen.
 *
 * **The grade is the point.** Brief §8 item 6: "next Sunday's notification grades the previous plan
 * (planned vs actual hours and dollars). That loop is what makes the notification worth keeping
 * enabled." So when there is a finished plan to grade, the grade IS the headline — four of the
 * driver's own numbers — and the invitation to plan the week ahead is the second line. With nothing to
 * grade it is just the invitation.
 *
 * **PII posture (principle 7):** every value in this copy is a count or a dollar figure derived from
 * the driver's own aggregates. No store names, no customer data, no screen text — nothing that could
 * be PII is reachable from here, by construction.
 *
 * **Fail-open.** A notification the OS refuses (POST_NOTIFICATIONS not granted, channel muted) must
 * not take the worker down with it: the whole post is wrapped, and a failure logs and returns.
 */
@Singleton
class WeeklyPlanNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val notificationManager: NotificationManager,
) {

    /** [grade] is last week's plan measured against the record, or null when there is nothing to grade. */
    fun postWeeklyNudge(grade: WeeklyPlanGrade?) {
        try {
            post(grade)
        } catch (t: Throwable) {
            // Deliberately broad and not rethrown (#909): an engagement notification is never worth a
            // crashed background job.
            Timber.tag(TAG).e(t, "Weekly plan notification could not be posted (#981)")
        }
    }

    private fun post(grade: WeeklyPlanGrade?) {
        WeeklyPlanChannel.ensure(context, notificationManager)

        val title = context.getString(R.string.weekly_plan_notice_title)
        val text = grade?.let { gradeLine(it) } ?: context.getString(R.string.weekly_plan_notice_text_plain)

        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            MainActivity.routeIntent(context, Screen.WeeklyPlan.route),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, WeeklyPlanChannel.ID)
            .setSmallIcon(R.drawable.bag_red_idle)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(WeeklyPlanChannel.NOTIFICATION_ID, notification)
    }

    /**
     * The grade headline: hours worked against hours planned, dollars kept against dollars projected —
     * stated, never scored. A week the driver did not work any of their windows says exactly that
     * rather than reporting "0 of 12 hours" as if it were a near miss.
     */
    private fun gradeLine(grade: WeeklyPlanGrade): String = if (grade.unworked) {
        context.getString(R.string.weekly_plan_notice_grade_unworked_format, grade.plannedHours)
    } else {
        context.getString(
            R.string.weekly_plan_notice_grade_format,
            Formats.decimal(grade.actualHours),
            grade.plannedHours,
            Formats.money(grade.actualKept),
            Formats.money(grade.projectedKept),
        )
    }

    private companion object {
        const val TAG = "WeeklyPlan"
        const val REQUEST_CODE = 981
    }
}
