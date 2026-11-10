package cloud.trotter.log.gig.ui.main.analytics.search

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme

/** The search field's test tag, on the shift page and the Search screen. */
internal const val SEARCH_FIELD_TAG = "store-search-field"

/** The title bar's search button: [name] is its content description and its long-press tooltip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchButton(name: String, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(name) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick) { Icon(Icons.Filled.Search, contentDescription = name) }
    }
}

/**
 * The single-line search field that takes the title's place: [placeholder] until the dasher types, a clear button once
 * there is text. Focused with the keyboard up the first time it is composed, and not again: the flag is saved, so a
 * return from a result (or a rotation) leaves the keyboard down.
 */
@Composable
internal fun SearchField(query: String, onQueryChange: (String) -> Unit, placeholder: String) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by rememberSaveable { mutableStateOf(false) }
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(SEARCH_FIELD_TAG),
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = if (query.isEmpty()) null else {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear))
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        // The field sits in the title bar: no container or underline of its own.
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
    LaunchedEffect(Unit) {
        if (focused) return@LaunchedEffect
        focused = true
        focus.requestFocus()
        keyboard?.show()
    }
}

/** "All shifts" or "This shift" above the results: the scope stays in view once the placeholder is gone. */
@Composable
internal fun SearchScopeLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(top = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = AppTheme.colors.text2,
    )
}

/** The one line when no row matched: "No offers or deliveries from "Wingstop"". */
@Composable
internal fun SearchNoMatch(query: String, modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.search_no_match_format, query.trim()),
        modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = AppTheme.colors.text2,
    )
}
