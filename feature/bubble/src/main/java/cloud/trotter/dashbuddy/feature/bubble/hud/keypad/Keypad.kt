package cloud.trotter.dashbuddy.feature.bubble.hud.keypad

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.feature.bubble.R

/** Fixed columns match 12j. A null point handler leaves the slot visible and disabled. */
@Composable
fun Keypad(onDigit: (Int) -> Unit, onPoint: (() -> Unit)?, onBackspace: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(188.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), listOf(-1, 0, -2)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { key ->
                    val enabled = key != -1 || onPoint != null
                    Box(Modifier.size(width = 60.dp, height = 38.dp)
                        .testTag(when (key) { -1 -> "keypad.point"; -2 -> "keypad.backspace"; else -> "keypad.$key" })
                        .background(AppTheme.colors.surface3, RoundedCornerShape(6.dp))
                        .clickable(enabled = enabled, role = Role.Button) {
                            when (key) { -1 -> onPoint?.invoke(); -2 -> onBackspace(); else -> onDigit(key) }
                        }, contentAlignment = Alignment.Center) {
                        if (key == -2) Icon(Icons.AutoMirrored.Outlined.Backspace,
                            stringResource(R.string.hud_keypad_backspace), tint = AppTheme.colors.text2,
                            modifier = Modifier.size(22.dp))
                        else Text(if (key == -1) "." else key.toString(), style = AppTheme.num.mdNum,
                            color = if (enabled) AppTheme.colors.text else AppTheme.colors.text3)
                    }
                }
            }
        }
    }
}
