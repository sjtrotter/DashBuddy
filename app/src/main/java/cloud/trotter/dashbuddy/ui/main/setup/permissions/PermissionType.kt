package cloud.trotter.dashbuddy.ui.main.setup.permissions

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import cloud.trotter.dashbuddy.R

/**
 * Defines the standard "Trust Card" content for each permission DashBuddy requires.
 */
sealed class PermissionType(
    @param:DrawableRes val iconRes: Int,
    @param:StringRes val titleRes: Int,
    @param:StringRes val pitchRes: Int,
    @param:StringRes val hoodRes: Int,
    @param:StringRes val actionRes: Int
) {
    // 1. The Heavy Lifter (Special System Permission)
    data object Accessibility : PermissionType(
        iconRes = R.drawable.ic_accessibility,
        titleRes = R.string.perm_title_accessibility,
        pitchRes = R.string.perm_pitch_accessibility,
        hoodRes = R.string.perm_hood_accessibility,
        actionRes = R.string.perm_action_accessibility
    )

    // 2. The Radar (Special System Permission)
    data object NotificationListener : PermissionType(
        iconRes = R.drawable.ic_read_notifications,
        titleRes = R.string.perm_title_listener,
        pitchRes = R.string.perm_pitch_listener,
        hoodRes = R.string.perm_hood_listener,
        actionRes = R.string.perm_action_listener
    )

    // 3. The Standard Stuff (Native Android Popup)
    data object Location : PermissionType(
        iconRes = R.drawable.ic_location,
        titleRes = R.string.perm_title_location,
        pitchRes = R.string.perm_pitch_location,
        hoodRes = R.string.perm_hood_location,
        actionRes = R.string.perm_action_location
    )

    // 4. The Standard Stuff (Native Android Popup)
    data object PostNotifications : PermissionType(
        iconRes = R.drawable.ic_notifications,
        titleRes = R.string.perm_title_post_notif,
        pitchRes = R.string.perm_pitch_post_notif,
        hoodRes = R.string.perm_hood_post_notif,
        actionRes = R.string.perm_action_post_notif
    )

    // 5. Bubbles (special permission)
    object Bubbles : PermissionType(
        iconRes = R.drawable.bag_red_idle, // Or whichever drawable you prefer!
        titleRes = R.string.perm_title_bubbles,
        pitchRes = R.string.perm_pitch_bubbles,
        actionRes = R.string.perm_action_bubbles,
        hoodRes = R.string.perm_hood_bubbles
    )
}