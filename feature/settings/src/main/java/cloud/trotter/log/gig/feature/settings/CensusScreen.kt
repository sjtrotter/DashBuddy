package cloud.trotter.log.gig.feature.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.log.gig.domain.census.CensusConsent
import cloud.trotter.log.gig.domain.state.Platform

/**
 * #1621: the census opt-in, Settings > Data & privacy > Census, in every build. The switch turns on only through
 * [CensusDisclosure]'s Accept; turning it off, or Not now, records a decline. The choices save as they change.
 * #1622: "What gig.log sent" ([onOpenSent]) and "Delete my census data" sit below the choices.
 */
@Composable
fun CensusScreen(onBack: () -> Unit, onOpenSent: () -> Unit, viewModel: CensusViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CensusContent(
        state = state,
        onBack = onBack,
        onAccept = viewModel::accept,
        onDecline = viewModel::decline,
        onWifiOnly = viewModel::setWifiOnly,
        onPlatform = viewModel::setPlatform,
        onOpenSent = onOpenSent,
        onDelete = viewModel::deleteServerData,
    )
}

/** The census screen, stateless but for whether the disclosure is open (Compose tests and screenshots render it). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CensusContent(
    state: CensusUiState?,
    onBack: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onPlatform: (Platform, Boolean) -> Unit,
    onOpenSent: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDisclosure by rememberSaveable { mutableStateOf(false) }
    if (showDisclosure && state != null) {
        BackHandler { showDisclosure = false }
        CensusDisclosure(
            serverHost = state.serverHost,
            testBuild = state.testBuild,
            accepted = state.consent == CensusConsent.ON,
            onAccept = { onAccept(); showDisclosure = false },
            onNotNow = { onDecline(); showDisclosure = false },
            onClose = { showDisclosure = false },
        )
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.census_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (state == null) return@Scaffold
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            val on = state.consent == CensusConsent.ON
            ListItem(
                headlineContent = { Text(stringResource(R.string.census_switch_label)) },
                supportingContent = if (state.consent == CensusConsent.ENDPOINT_CHANGED) {
                    { Text(stringResource(R.string.census_endpoint_changed)) }
                } else null,
                trailingContent = { Switch(checked = on, onCheckedChange = null) },
                modifier = Modifier.toggleable(value = on, role = Role.Switch) { turnOn ->
                    if (turnOn) showDisclosure = true else onDecline()
                },
            )
            Text(
                stringResource(buildNote(state.testBuild)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                stringResource(R.string.census_platforms_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp)
                    .semantics { heading() },
            )
            state.platforms.forEach { row ->
                ListItem(
                    headlineContent = { Text(row.platform.displayName) },
                    trailingContent = { Checkbox(checked = row.checked, onCheckedChange = null) },
                    modifier = Modifier.toggleable(value = row.checked, role = Role.Checkbox) {
                        onPlatform(row.platform, it)
                    },
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.census_wifi_only_label)) },
                supportingContent = { Text(stringResource(R.string.census_wifi_only_supporting)) },
                trailingContent = { Switch(checked = state.wifiOnly, onCheckedChange = null) },
                modifier = Modifier.toggleable(value = state.wifiOnly, role = Role.Switch, onValueChange = onWifiOnly),
            )
            TextButton(onClick = { showDisclosure = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.census_read_disclosure))
            }
            CensusDataSection(
                deletion = state.deletion,
                deleting = state.deleting,
                onOpenSent = onOpenSent,
                onDelete = onDelete,
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** Where this build's census differs from the disclosure: what is not built yet, or the test build's extras. */
@StringRes
private fun buildNote(testBuild: Boolean): Int =
    if (testBuild) R.string.census_note_test_build else R.string.census_note_release

/** The disclosure's sections in order: heading, body. */
private val DISCLOSURE_SECTIONS: List<Pair<Int, Int>> = listOf(
    R.string.census_disclosure_what_title to R.string.census_disclosure_what_body,
    R.string.census_disclosure_why_title to R.string.census_disclosure_why_body,
    R.string.census_disclosure_who_title to R.string.census_disclosure_who_body,
    R.string.census_disclosure_when_title to R.string.census_disclosure_when_body,
    R.string.census_disclosure_identity_title to R.string.census_disclosure_identity_body,
    R.string.census_disclosure_stop_title to R.string.census_disclosure_stop_body,
)

/**
 * #1621: the prominent disclosure Google Play requires before consent: what the census sends, why, who receives it,
 * when, that it is not anonymous, and how to stop and delete. It fills the screen and ends with Accept and Not now;
 * once [accepted], it is for reading again and ends with Close.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CensusDisclosure(
    serverHost: String,
    testBuild: Boolean,
    accepted: Boolean,
    onAccept: () -> Unit,
    onNotNow: () -> Unit,
    onClose: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.census_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    if (accepted) {
                        Button(onClick = onClose) { Text(stringResource(R.string.census_disclosure_close)) }
                    } else {
                        TextButton(onClick = onNotNow) { Text(stringResource(R.string.consent_front_door_not_now)) }
                        Button(onClick = onAccept) { Text(stringResource(R.string.census_disclosure_accept)) }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.census_disclosure_heading),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.census_disclosure_intro), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(buildNote(testBuild)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            DISCLOSURE_SECTIONS.forEach { (title, body) ->
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (body == R.string.census_disclosure_who_body) stringResource(body, serverHost) else stringResource(body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.census_disclosure_agreement), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))
        }
    }
}
