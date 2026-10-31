package cloud.trotter.log.gig.ui.main.setup.permissions

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import cloud.trotter.log.gig.R

/**
 * One OS permission of the gate, with its fixed place among the six steps (#1457: "Step N of 6";
 * step 1 is the Screen events consent, [PermissionStep.ScreenEvents]) and the copy its card shows.
 *
 * [card] is null for [Accessibility], whose step is its own disclosure screen
 * ([AccessibilityDisclosureCard]) rather than the shared [PermissionCard].
 */
sealed class PermissionType(
    val stepNumber: Int,
    @param:StringRes val titleRes: Int,
    /** What stops working without the grant; the card's "Without it" line. */
    @param:StringRes val withoutRes: Int,
    val card: CardCopy?,
) {
    /** The shared card's copy: what the permission does (pitch), what it reads (body), the grant button. */
    data class CardCopy(
        @param:DrawableRes val iconRes: Int,
        @param:StringRes val pitchRes: Int,
        @param:StringRes val bodyRes: Int,
        @param:StringRes val actionRes: Int,
    )

    data object Accessibility : PermissionType(
        stepNumber = 2,
        titleRes = R.string.perm_title_accessibility,
        withoutRes = R.string.perm_without_accessibility,
        card = null,
    )

    data object NotificationListener : PermissionType(
        stepNumber = 3,
        titleRes = R.string.perm_title_listener,
        withoutRes = R.string.perm_without_listener,
        card = CardCopy(
            iconRes = R.drawable.ic_read_notifications,
            pitchRes = R.string.perm_pitch_listener,
            bodyRes = R.string.perm_body_listener,
            actionRes = R.string.perm_action_listener,
        ),
    )

    data object Location : PermissionType(
        stepNumber = 4,
        titleRes = R.string.perm_title_location,
        withoutRes = R.string.perm_without_location,
        card = CardCopy(
            iconRes = R.drawable.ic_location,
            pitchRes = R.string.perm_pitch_location,
            bodyRes = R.string.perm_body_location,
            actionRes = R.string.perm_action_location,
        ),
    )

    data object PostNotifications : PermissionType(
        stepNumber = 5,
        titleRes = R.string.perm_title_post_notif,
        withoutRes = R.string.perm_without_post_notif,
        card = CardCopy(
            iconRes = R.drawable.ic_notifications,
            pitchRes = R.string.perm_pitch_post_notif,
            bodyRes = R.string.perm_body_post_notif,
            actionRes = R.string.perm_action_post_notif,
        ),
    )

    data object Bubbles : PermissionType(
        stepNumber = 6,
        titleRes = R.string.perm_title_bubbles,
        withoutRes = R.string.perm_without_bubbles,
        card = CardCopy(
            iconRes = R.drawable.bag_red_idle,
            pitchRes = R.string.perm_pitch_bubbles,
            bodyRes = R.string.perm_body_bubbles,
            actionRes = R.string.perm_action_bubbles,
        ),
    )

    companion object {
        /** The gate's steps in all: Screen events plus the five OS permissions. */
        const val STEP_COUNT = 6
    }
}
