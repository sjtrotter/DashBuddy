package cloud.trotter.log.gig.ui.main.setup.permissions

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.feature.settings.R as SettingsR

/**
 * One OS permission's card (#1457): step label, title, what it does, what it reads, what stops
 * working without it, then the grant button and "Not now". When Android will no longer show the
 * permission's dialog ([settingsRoute] non-null), the card says where in Android settings to turn it
 * on and its button opens that page. Stateless.
 */
@Composable
fun PermissionCard(
    type: PermissionType,
    card: PermissionType.CardCopy,
    settingsRoute: SettingsRoute?,
    onGrantClicked: () -> Unit,
    onNotNow: () -> Unit,
) {
    PermissionStepColumn {
        PermissionStepHeader(stepNumber = type.stepNumber, iconRes = card.iconRes, titleRes = type.titleRes)
        Text(
            text = stringResource(card.pitchRes),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        PermissionBodyText(stringResource(card.bodyRes))
        PermissionWithoutIt(stringResource(type.withoutRes))
        settingsRoute?.let {
            Spacer(Modifier.height(12.dp))
            PermissionBodyText(stringResource(it.textRes), emphasis = true)
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onGrantClicked,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
        ) {
            Text(
                text = stringResource(settingsRoute?.actionRes ?: card.actionRes),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(SettingsR.string.consent_front_door_not_now))
        }
    }
}

/**
 * #1457 — where a runtime permission is turned on once its dialog no longer shows. The copy names no
 * cause: a refused-for-good dialog and one the dasher dismissed both land here.
 */
enum class SettingsRoute(
    @param:StringRes val textRes: Int,
    @param:StringRes val actionRes: Int,
) {
    LOCATION(R.string.perm_settings_route_location, R.string.perm_action_open_app_settings),
    POST_NOTIFICATIONS(R.string.perm_settings_route_post_notif, R.string.perm_action_open_notification_settings),
    ;

    companion object {
        fun of(type: PermissionType): SettingsRoute? = when (type) {
            PermissionType.Location -> LOCATION
            PermissionType.PostNotifications -> POST_NOTIFICATIONS
            else -> null
        }
    }
}

/** The scrolling column every step card sits in. */
@Composable
internal fun PermissionStepColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** "Step N of 6", the hero icon (when the step has one) and the title. */
@Composable
internal fun PermissionStepHeader(stepNumber: Int, @DrawableRes iconRes: Int?, @StringRes titleRes: Int) {
    Text(
        text = stringResource(R.string.perm_step_format, stepNumber, PermissionType.STEP_COUNT),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    if (iconRes != null) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(8.dp))
}

/** A paragraph of a step's disclosure, start-aligned. */
@Composable
internal fun PermissionBodyText(text: String, emphasis: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (emphasis) FontWeight.SemiBold else null,
        color = if (emphasis) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The "Without it" line: what stops working if the step is not allowed. */
@Composable
internal fun PermissionWithoutIt(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.perm_without_label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
    )
}
