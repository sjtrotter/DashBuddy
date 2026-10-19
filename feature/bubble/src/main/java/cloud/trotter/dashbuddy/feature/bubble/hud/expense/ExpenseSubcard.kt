package cloud.trotter.dashbuddy.feature.bubble.hud.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.ExpenseCategory

val expenseTiles = listOf(ExpensePick.Expense(ExpenseCategory.FOOD), ExpensePick.Fuel,
    ExpensePick.Expense(ExpenseCategory.PARKING), ExpensePick.Expense(ExpenseCategory.CAR_CARE),
    ExpensePick.Expense(ExpenseCategory.SUPPLIES), ExpensePick.Expense(ExpenseCategory.OTHER))

/** Back and outside taps are handled by the single HudSheetHost. */
@Composable
fun ExpenseSubcard(onPick: (ExpensePick) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("expense.subcard").padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        expenseTiles.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { pick ->
                    Column(Modifier.weight(1f).height(72.dp).testTag("expense.tile.${pick.tagName}")
                        .clip(RoundedCornerShape(12.dp)).background(AppTheme.colors.surface2)
                        .clickable(role = Role.Button) { onPick(pick) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(pick.icon, null, tint = AppTheme.colors.text2)
                        Text(stringResource(pick.labelResource), color = AppTheme.colors.text)
                    }
                }
            }
        }
    }
}
