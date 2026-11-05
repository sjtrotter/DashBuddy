package cloud.trotter.log.gig.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import cloud.trotter.log.gig.R
import kotlinx.coroutines.delay

/** How long a tab waits for its first data before it shows the spinner, so a fast load never flashes it. */
internal const val LOADING_INDICATOR_DELAY_MS = 400L

/**
 * A tab's body before its first data arrives (#1524): blank for [LOADING_INDICATOR_DELAY_MS], then a
 * centred spinner announced as "Loading". Fills the space it is given.
 */
@Composable
internal fun LoadingBody(modifier: Modifier = Modifier) {
    var showIndicator by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LOADING_INDICATOR_DELAY_MS)
        showIndicator = true
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (showIndicator) {
            val label = stringResource(R.string.common_loading)
            CircularProgressIndicator(Modifier.semantics { contentDescription = label })
        }
    }
}
