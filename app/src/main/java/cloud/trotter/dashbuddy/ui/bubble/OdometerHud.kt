package cloud.trotter.dashbuddy.ui.bubble

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.dashbuddy.core.designsystem.time.rememberNow
import cloud.trotter.dashbuddy.domain.model.vehicle.MileageReconciliation
import cloud.trotter.dashbuddy.domain.model.vehicle.OdometerCheckInput
import cloud.trotter.dashbuddy.domain.model.vehicle.OdometerReadingCheck
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleOdometerReading
import cloud.trotter.dashbuddy.feature.bubble.hud.odometer.*

@Stable
internal class OdometerHud(
    private val viewModel: BubbleViewModel,
    private val entry: OdometerEntryState,
    private val closeLog: () -> Unit,
) {
    val open: Boolean get() = entry.open

    /** A tap while the garage or readings are loading is inert. */
    fun openSheet() {
        if (!viewModel.odometerLoaded.value) return
        closeLog()
        if (entry.show(viewModel.garage.value?.active != null)) viewModel.reopenOdometer()
    }

    /** Back inside the sheet: one page up, or closed from the reading page. */
    fun back() { entry.back(viewModel.garage.value?.active != null) }
    fun dismiss() { entry.open = false }

    @Composable
    fun ObserveEntry() {
        // Keep the dock gate subscribed even when no footer or prompt is composed.
        viewModel.odometerLoaded.collectAsStateWithLifecycle()
        LaunchedEffect(viewModel, entry) {
            viewModel.odometerSaveCompleted.collect {
                if (entry.page == OdometerPage.READING) entry.open = false
                viewModel.odometerSaveHandled()
            }
        }
        LaunchedEffect(viewModel, entry) {
            viewModel.odometerVehicleChanged.collect { change ->
                when (change) {
                    VehicleChange.ADDED -> entry.vehicleAdded()
                    VehicleChange.SWITCHED -> entry.vehicleSwitched()
                }
                viewModel.odometerVehicleChangeHandled()
            }
        }
    }

    @Composable
    fun Sheet() {
        val garage by viewModel.garage.collectAsStateWithLifecycle()
        val vehicle = garage?.active
        val readings by viewModel.odometerReadings.collectAsStateWithLifecycle()
        val gpsTotal by viewModel.gpsTotalMiles.collectAsStateWithLifecycle()
        val saving by viewModel.odometerSaving.collectAsStateWithLifecycle()
        val writeFailed by viewModel.odometerWriteFailed.collectAsStateWithLifecycle()
        val now by rememberNow()
        val last = remember(readings, vehicle) {
            readings?.let { rows -> vehicle?.let { VehicleOdometerReading.latestFor(rows, it.id) } }
        }
        val otherVehicleReadSince = last != null && vehicle != null && readings?.let {
            !VehicleOdometerReading.singleVehicleSpan(it, vehicle.id, last.occurredAt, now)
        } == true
        val check = OdometerReadingCheck.check(OdometerCheckInput(entry.reading, vehicle, last, gpsTotal, now, otherVehicleReadSince))
        Column(Modifier.fillMaxSize()) {
            OdometerSheet(OdometerSheetModel(vehicle, garage?.vehicles.orEmpty(), entry.reading, last, check, entry.page, saving,
                writeFailed = if (garage?.readable == false) OdometerWriteFailure.UNREADABLE else writeFailed,
                vehicleName = entry.vehicleName, gpsLoaded = gpsTotal != null),
                onVehicleNameChange = { entry.vehicleName = it },
                onDigit = entry::digit, onBackspace = entry::backspace,
                onSave = { entry.reading?.let(viewModel::saveOdometerReading) },
                onAddVehicle = viewModel::addVehicle,
                onSwitchVehicle = { entry.page = OdometerPage.VEHICLES },
                onSelectVehicle = { id ->
                    if (id == vehicle?.id) entry.page = OdometerPage.READING else viewModel.selectVehicle(id)
                },
                onShowAddVehicle = { entry.showAddVehicle() }, onBack = ::back)
        }
    }

    // The slot must disappear at rest when no notice is present, even though its lambda is stable.
    @Composable
    fun UndoVisible(): Boolean {
        val notice by viewModel.odometerNotice.collectAsStateWithLifecycle()
        return notice != null
    }

    @Composable
    fun Undo() {
        val notice by viewModel.odometerNotice.collectAsStateWithLifecycle()
        notice?.let { OdometerNotice(it) }
    }

    @Composable
    fun RestingFooter() {
        val loaded by viewModel.odometerLoaded.collectAsStateWithLifecycle()
        if (!loaded) return
        val garage by viewModel.garage.collectAsStateWithLifecycle()
        val readings by viewModel.odometerReadings.collectAsStateWithLifecycle()
        val prompts by viewModel.odometerPrompts.collectAsStateWithLifecycle()
        val lastSession by viewModel.lastSession.collectAsStateWithLifecycle()
        val vehicle = garage?.active
        val lastReading = remember(readings, vehicle) {
            readings?.let { rows -> vehicle?.let { VehicleOdometerReading.latestFor(rows, it.id) } }
        }
        val reconciliation = remember(readings, vehicle) {
            readings?.let { list -> vehicle?.id?.let { MileageReconciliation.latest(list, it) } }
        }
        OdometerFooter(vehicle?.name, lastReading, reconciliation, ::openSheet)
        OdometerPrompt(prompts, lastSession?.endedAt?.let(PromptAnchor::DashEnd), lastReading, ::openSheet)
    }

    @Composable
    fun WaitingFoot() {
        val loaded by viewModel.odometerLoaded.collectAsStateWithLifecycle()
        if (!loaded) return
        val garage by viewModel.garage.collectAsStateWithLifecycle()
        val readings by viewModel.odometerReadings.collectAsStateWithLifecycle()
        val vehicle = garage?.active
        val lastReading = remember(readings, vehicle) {
            readings?.let { rows -> vehicle?.let { VehicleOdometerReading.latestFor(rows, it.id) } }
        }
        val prompts by viewModel.odometerPrompts.collectAsStateWithLifecycle()
        val region by viewModel.focusedRegion.collectAsStateWithLifecycle()
        OdometerPrompt(prompts, region?.session?.startedAt?.let(PromptAnchor::DashStart), lastReading, ::openSheet)
    }
}

@Composable
internal fun rememberOdometerHud(viewModel: BubbleViewModel, closeLog: () -> Unit): OdometerHud {
    val entry = rememberOdometerEntryState()
    val hud = remember(viewModel, entry) { OdometerHud(viewModel, entry, closeLog) }
    hud.ObserveEntry()
    return hud
}
