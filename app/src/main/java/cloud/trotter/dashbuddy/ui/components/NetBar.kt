package cloud.trotter.dashbuddy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme

/**
 * The proportional net bar — a track plus a fill, scaled to the list's best row.
 *
 * ONE owner for a render two lists had copied byte-for-byte (#1024 part 2 review F7): the Playbook's
 * store leaderboard (#979) and the Money tab's by-platform rows (#1024 B4). [PatternsModel.netBarFraction]
 * is the matching scale rule, and the pairing is the point — a caller that computes its own fraction
 * and a caller that uses the shared one would drift exactly the way two copies of this Box did.
 *
 * It lives in `:app`'s `ui/components/` rather than `:core:designsystem` because it is not generic:
 * it encodes a specific editorial decision (a zero-or-negative row draws NO fill rather than a
 * minimum-width stub, so "you kept nothing here" never looks like a small positive). The design
 * system's own `AppStackBar` is the generic proportion bar.
 *
 * [fraction] is expected in `0f..1f`; anything outside is coerced, and `<= 0f` renders the bare track.
 */
@Composable
internal fun NetBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
) {
    val shape = RoundedCornerShape(height / 2)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(AppTheme.colors.surface3),
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(height)
                    .clip(shape)
                    .background(color),
            )
        }
    }
}
