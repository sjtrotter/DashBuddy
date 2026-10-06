package cloud.trotter.dashbuddy.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import cloud.trotter.dashbuddy.R

@Composable
internal fun MoreNumbersToggle(showMore: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) =
    Column(modifier) {
        DisclosureRow(
            text = stringResource(if (showMore) R.string.common_fewer_numbers else R.string.common_more_numbers),
            expanded = showMore,
            onToggle = onToggle,
        )
    }
