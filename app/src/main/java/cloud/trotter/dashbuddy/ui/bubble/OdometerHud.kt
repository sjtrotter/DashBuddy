package cloud.trotter.dashbuddy.ui.bubble

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.dashbuddy.core.designsystem.time.rememberNow
import cloud.trotter.dashbuddy.domain.model.vehicle.MileageReconciliation
import cloud.trotter.dashbuddy.domain.model.vehicle.OdometerCheckInput
import cloud.trotter.dashbuddy.domain.model.vehicle.OdometerReadingCheck
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.feature.bubble.hud.odometer.*

@Stable
internal class OdometerHud(
    val open: Boolean,
    val dismiss: () -> Unit,
    /** Back inside the sheet: one page up, or closed from the reading page. */
    val back: () -> Unit,
    val openSheet: () -> Unit,
    val sheet: @Composable () -> Unit,
    val undo: (@Composable () -> Unit)?,
    val restingFooter: @Composable () -> Unit,
    val waitingFoot: @Composable () -> Unit,
)

@Composable
internal fun rememberOdometerHud(viewModel: BubbleViewModel, region: PlatformRegion?, closeLog: () -> Unit): OdometerHud {
    val vehicle by viewModel.activeVehicle.collectAsStateWithLifecycle()
    val vehicles by viewModel.vehicles.collectAsStateWithLifecycle()
    val lastReading by viewModel.lastReading.collectAsStateWithLifecycle()
    val readings by viewModel.odometerReadings.collectAsStateWithLifecycle()
    val prompts by viewModel.odometerPrompts.collectAsStateWithLifecycle()
    val gpsTotal by viewModel.gpsTotalMiles.collectAsStateWithLifecycle()
    val saving by viewModel.odometerSaving.collectAsStateWithLifecycle()
    val writeFailed by viewModel.odometerWriteFailed.collectAsStateWithLifecycle()
    val savedNotice by viewModel.odometerNotice.collectAsStateWithLifecycle()
    val lastSession by viewModel.lastSession.collectAsStateWithLifecycle()
    val entry = rememberOdometerEntryState()
    var seenVehicleId by rememberSaveable { mutableStateOf(vehicle?.id) }
    LaunchedEffect(viewModel) {
        viewModel.odometerSaveCompleted.collect {
            if (entry.page == OdometerPage.READING) entry.open = false
        }
    }
    LaunchedEffect(vehicle?.id) {
        val id = vehicle?.id
        if (id != null && id != seenVehicleId) {
            if (seenVehicleId != null) entry.vehicleChanged()
            seenVehicleId = id
        }
    }
    val reconciliation = remember(readings, vehicle) {
        vehicle?.id?.let { MileageReconciliation.latest(readings, it) }
    }
    val openOdometer = {
        closeLog()
        viewModel.reopenOdometer()
        entry.show(vehicle != null)
    }
    val sheetBack = { entry.back(vehicle != null) }
    val noticeContent: (@Composable () -> Unit)? = savedNotice?.let { { OdometerNotice(it) } }
    return OdometerHud(
        open = entry.open,
        dismiss = { entry.open = false },
        back = sheetBack,
        openSheet = openOdometer,
        sheet = {
            val now by rememberNow()
            val check = OdometerReadingCheck.check(OdometerCheckInput(entry.reading, vehicle, lastReading, gpsTotal, now))
            Column(Modifier.fillMaxSize()) {
                OdometerSheet(OdometerSheetModel(vehicle, vehicles, entry.reading, lastReading, check, entry.page, saving, writeFailed),
                    onDigit = entry::digit, onBackspace = entry::backspace,
                    onSave = { entry.reading?.let(viewModel::saveOdometerReading) },
                    onAddVehicle = viewModel::addVehicle,
                    onSwitchVehicle = { entry.page = OdometerPage.VEHICLES },
                    onSelectVehicle = { id ->
                        if (id == vehicle?.id) entry.page = OdometerPage.READING else viewModel.selectVehicle(id)
                    },
                    onShowAddVehicle = { entry.showAddVehicle() }, onBack = sheetBack)
            }
        },
        undo = noticeContent,
        restingFooter = {
            OdometerFooter(vehicle?.name, lastReading, reconciliation, openOdometer)
            OdometerPrompt(prompts, lastSession?.endedAt?.let(PromptAnchor::DashEnd), lastReading, openOdometer)
        },
        waitingFoot = {
            OdometerPrompt(prompts, region?.session?.startedAt?.let(PromptAnchor::DashStart), lastReading, openOdometer)
        },
    )
}
