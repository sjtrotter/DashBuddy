package cloud.trotter.dashbuddy.ui.main.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Free-tier CSV data export (#319). One folder pick → three CSVs (deliveries, sessions, summary)
 * written into it. All-time export (v1); a date-range picker is a follow-up. Driver-owned data out,
 * no network, no storage permission (Storage Access Framework grants the chosen folder only).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataExportScreen(
    onBack: () -> Unit,
    viewModel: DataExportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val logState by viewModel.logState.collectAsStateWithLifecycle()

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.export(uri)
    }

    val pickLogFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.exportLog(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export Data") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Export your mileage & earnings to CSV",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Pick a folder and DashBuddy writes three spreadsheet files into it:\n" +
                    "• deliveries.csv — one row per delivery\n" +
                    "• sessions.csv — one row per session\n" +
                    "• summary.csv — totals + estimated IRS mileage deduction\n\n" +
                    "This is your own data, on-device — nothing is uploaded. Exports all history " +
                    "(a date-range picker is coming later).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { viewModel.reset(); pickFolder.launch(null) },
                enabled = state !is DataExportViewModel.ExportState.InProgress,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.FileDownload, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text("  Choose folder & export")
            }

            Spacer(Modifier.height(16.dp))

            when (val s = state) {
                is DataExportViewModel.ExportState.InProgress -> {
                    CircularProgressIndicator()
                }
                is DataExportViewModel.ExportState.Success -> {
                    Text(
                        "Exported ${s.filesWritten} files to the chosen folder.",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is DataExportViewModel.ExportState.Error -> {
                    Text(
                        "Export failed: ${s.message}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                DataExportViewModel.ExportState.Idle -> Unit
            }

            Spacer(Modifier.height(32.dp))

            // --- Bug-report (shareable log) export (#551) ---
            Text(
                "Export a bug report",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Pick a folder and DashBuddy writes dashbuddy-log.txt into it — the log the " +
                    "developer needs to diagnose an issue.\n\n" +
                    "INFO+ milestones only — no raw store, customer, or address text. Every line is " +
                    "auto-scrubbed at the sink before it's written, so a leaked name can't reach the " +
                    "file. On-device, nothing is uploaded.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { viewModel.resetLog(); pickLogFolder.launch(null) },
                enabled = logState !is DataExportViewModel.LogExportState.InProgress,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.FileDownload, contentDescription = null)
                Text("  Choose folder & export log")
            }

            Spacer(Modifier.height(16.dp))

            when (val s = logState) {
                is DataExportViewModel.LogExportState.InProgress -> {
                    CircularProgressIndicator()
                }
                is DataExportViewModel.LogExportState.Success -> {
                    Text(
                        "Exported dashbuddy-log.txt — ${s.scrubbedLines} line(s) were auto-scrubbed.",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is DataExportViewModel.LogExportState.Error -> {
                    Text(
                        "Log export failed: ${s.message}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                DataExportViewModel.LogExportState.Idle -> Unit
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
