package cloud.trotter.dashbuddy.ui.main.setup.consent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The shared front-door modal (#1151 review LL3): title, body, the caller's decision [content], and —
 * when [onDefer] is non-null — a "Not now" button that scrim/back also trigger. Stateless: whether it
 * is shown, and the deferral itself, belong to the host ([FrontDoorHost] + [FrontDoorViewModel]).
 * A non-deferrable sheet refuses to hide on scrim/back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrontDoorSheet(
    title: String,
    body: String,
    notNowLabel: String,
    onDefer: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { onDefer != null || it != SheetValue.Hidden },
    )
    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    ModalBottomSheet(
        onDismissRequest = { onDefer?.invoke() },
        sheetState = sheetState,
        modifier = Modifier.padding(bottom = bottomPadding),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            content()
            if (onDefer != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onDefer,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(notNowLabel) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
