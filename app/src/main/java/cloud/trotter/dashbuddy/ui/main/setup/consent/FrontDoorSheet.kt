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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The ONE front-door modal (#1151 review LL3/MM6). It stays composed while the door has a prompt;
 * [FrontDoorHost] swaps its [content] (animated) when a DECISION advances the door to the next
 * prompt, so the dasher never sees one modal disposed and another composed over their tap. "Not
 * now" (and scrim/back) call [onDefer], which closes the whole door for this foreground. Stateless.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrontDoorSheet(
    notNowLabel: String,
    onDefer: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    ModalBottomSheet(
        onDismissRequest = onDefer,
        sheetState = sheetState,
        modifier = Modifier.padding(bottom = bottomPadding),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            content()
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onDefer,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(notNowLabel) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One prompt's page inside [FrontDoorSheet]: title, body, then the prompt's decision [content]. */
@Composable
fun FrontDoorPage(
    title: String,
    body: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
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
    }
}
