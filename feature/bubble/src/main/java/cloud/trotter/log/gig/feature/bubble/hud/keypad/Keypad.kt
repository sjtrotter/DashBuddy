package cloud.trotter.log.gig.feature.bubble.hud.keypad

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
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.feature.bubble.R

/** Fixed columns match 12j. A null point handler leaves the slot visible and disabled. */
@Composable
fun Keypad(onDigit: (Int) -> Unit, onPoint: (() -> Unit)?, onBackspace: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(188.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            listOf(Key.Digit(1), Key.Digit(2), Key.Digit(3)),
            listOf(Key.Digit(4), Key.Digit(5), Key.Digit(6)),
            listOf(Key.Digit(7), Key.Digit(8), Key.Digit(9)),
            listOf(Key.Point, Key.Digit(0), Key.Backspace),
        ).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { key ->
                    val enabled = key != Key.Point || onPoint != null
                    Box(Modifier.widthIn(min = 60.dp).heightIn(min = 38.dp)
                        .testTag(when (key) {
                            Key.Point -> "keypad.point"
                            Key.Backspace -> "keypad.backspace"
                            is Key.Digit -> "keypad.${key.n}"
                        })
                        .background(AppTheme.colors.surface3, RoundedCornerShape(6.dp))
                        .clickable(enabled = enabled, role = Role.Button) {
                            when (key) {
                                Key.Point -> onPoint?.invoke()
                                Key.Backspace -> onBackspace()
                                is Key.Digit -> onDigit(key.n)
                            }
                        }, contentAlignment = Alignment.Center) {
                        when (key) {
                            Key.Backspace -> Icon(Icons.AutoMirrored.Outlined.Backspace,
                                stringResource(R.string.hud_keypad_backspace), tint = AppTheme.colors.text2,
                                modifier = Modifier.size(22.dp))
                            Key.Point, is Key.Digit -> Text(if (key is Key.Digit) key.n.toString() else ".",
                                style = AppTheme.num.mdNum,
                                color = if (enabled) AppTheme.colors.text else AppTheme.colors.text3)
                        }
                    }
                }
            }
        }
    }
}
