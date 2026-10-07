package cloud.trotter.dashbuddy.ui.main.setup.permissions

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R

@Composable
fun PermissionCard(
    type: PermissionType,
    onGrantClicked: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally // Centered layout!
    ) {

        // 1. The Hero Icon in a Colored Circle
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = type.iconRes),
                contentDescription = null,
                modifier = Modifier.size(36.dp), // Smaller standard icon
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Centered Title
        Text(
            text = stringResource(id = type.titleRes),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 3. Centered Human Pitch
        Text(
            text = stringResource(id = type.pitchRes),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Data use is visible before the permission choice.
        Text(
            text = stringResource(R.string.perm_under_the_hood_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(id = type.hoodRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            textAlign = TextAlign.Start,
        )
        Spacer(modifier = Modifier.height(24.dp))

        // 4. Action Button
        Button(
            onClick = onGrantClicked,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(
                text = stringResource(id = type.actionRes),
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 5. Privacy Guarantee (Always Visible)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(AppTheme.colors.goodBg) // Soft Green
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Security,
                contentDescription = stringResource(R.string.permission_card_content_desc_privacy),
                tint = AppTheme.colors.good,
            )
            Text(
                text = stringResource(id = R.string.perm_privacy_guarantee),
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.good,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

    }
}
