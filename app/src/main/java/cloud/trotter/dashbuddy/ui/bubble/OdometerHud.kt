package cloud.trotter.dashbuddy.ui.bubble

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.dashbuddy.core.designsystem.time.rememberNow
import cloud.trotter.dashbuddy.core.designsystem.time.LocalNowProvider
import cloud.trotter.dashbuddy.domain.model.vehicle.MileageReconciliation
import cloud.trotter.dashbuddy.domain.model.vehicle.OdometerCheck
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
        val garage = viewModel.garage.value
        if (entry.show(activeVehicle = garage?.active != null, anyVehicles = garage?.unretired?.isNotEmpty() == true)) {
            viewModel.reopenOdometer()
        }
    }

    fun openVehicles() {
        if (!viewModel.odometerLoaded.value) return
        closeLog()
        if (entry.showVehicles()) {
            viewModel.reopenOdometer()
        }
    }

    fun dismiss() { entry.open = false }

    /** Back inside the sheet: one page up, or closed from the reading page. */
    fun back() { entry.back(viewModel.garage.value?.active != null) }

    @Composable
    fun ObserveEntry() {
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
                    is VehicleChange.RETIRED -> {
                        val garage = viewModel.garageSnapshot()
                        entry.vehicleRetired(change.wasActive, garage.active != null, garage.unretired.isNotEmpty())
                    }
                }
                viewModel.odometerVehicleChangeHandled()
            }
        }
    }

    /** Reading validation ticks once a minute for day-granular thresholds and the open span bound. */
    @Composable
    fun Sheet() {
        val garage by viewModel.garage.collectAsStateWithLifecycle()
        val vehicle = garage?.active
        val readings by viewModel.odometerReadings.collectAsStateWithLifecycle()
        val gpsTotal by viewModel.gpsTotalMiles.collectAsStateWithLifecycle()
        val saving by viewModel.odometerSaving.collectAsStateWithLifecycle()
        val writeFailed by viewModel.odometerWriteFailed.collectAsStateWithLifecycle()
        val last = vehicle?.let { VehicleOdometerReading.latestFor(readings.orEmpty(), it.id) }
        val check = if (entry.page == OdometerPage.READING) {
            val now by rememberNow(60_000)
            OdometerReadingCheck.check(OdometerCheckInput(entry.reading, vehicle, last, gpsTotal, now, readings.orEmpty()))
        } else OdometerCheck.Empty
        Column(Modifier.fillMaxSize()) {
            OdometerSheet(OdometerSheetModel(vehicle, garage?.unretired.orEmpty(), entry.reading, last, check, entry.page, saving,
                writeFailed = if (garage?.readable == false) OdometerWriteFailure.UNREADABLE else writeFailed,
                vehicleName = entry.vehicleName, gpsLoaded = gpsTotal != null),
                onVehicleNameChange = { entry.vehicleName = it },
                onDigit = entry::digit, onBackspace = entry::backspace,
                onSave = { entry.reading?.let(viewModel::saveOdometerReading) },
                onAddVehicle = viewModel::addVehicle,
                onSwitchVehicle = { entry.page = OdometerPage.VEHICLES },
                onSelectVehicle = { id ->
                    if (id == vehicle?.id) {
                        if (entry.switcherFromRow) entry.vehicleSwitched() else entry.page = OdometerPage.READING
                    } else viewModel.selectVehicle(id)
                },
                onShowAddVehicle = { entry.showAddVehicle() }, onBack = ::back,
                onRetireVehicle = viewModel::retireVehicle)
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
    fun RestingFooter(onOpen: () -> Unit = ::openSheet) {
        val loaded by viewModel.odometerLoaded.collectAsStateWithLifecycle()
        if (!loaded) return
        val garage by viewModel.garage.collectAsStateWithLifecycle()
        val readings by viewModel.odometerReadings.collectAsStateWithLifecycle()
        val vehicle = garage?.active
        val lastReading = vehicle?.let { VehicleOdometerReading.latestFor(readings.orEmpty(), it.id) }
        val reconciliation = remember(readings, vehicle) {
            readings?.let { list -> vehicle?.id?.let { MileageReconciliation.latest(list, it) } }
        }
        OdometerFooter(vehicle, lastReading, reconciliation, onOpen)
    }

    /** The start-window cue ticks once a minute while its window is open. */
    @Composable
    fun Cue(startedAt: Long?, endedAt: Long?): Boolean {
        val loaded by viewModel.odometerLoaded.collectAsStateWithLifecycle()
        val garage by viewModel.garage.collectAsStateWithLifecycle()
        val readings by viewModel.odometerReadings.collectAsStateWithLifecycle()
        val lastReading = garage?.active?.let { VehicleOdometerReading.latestFor(readings.orEmpty(), it.id) }
        val prompts by viewModel.odometerPrompts.collectAsStateWithLifecycle()
        // Resting needs no clock. Drop the live ticker as soon as the start window closes.
        val now = if (loaded && prompts && startedAt != null) {
            val snapshot = LocalNowProvider.current.millis()
            if (snapshot >= startedAt && snapshot - startedAt < OdometerCue.WINDOW_MS) {
                val tick by rememberNow(60_000)
                tick
            } else snapshot
        } else 0L
        return OdometerCue.shows(prompts, startedAt, endedAt,
            lastReading.takeIf { loaded }, loaded, now)
    }
}

@Composable
internal fun rememberOdometerHud(viewModel: BubbleViewModel, closeLog: () -> Unit): OdometerHud {
    val entry = rememberOdometerEntryState()
    val hud = remember(viewModel, entry) { OdometerHud(viewModel, entry, closeLog) }
    hud.ObserveEntry()
    return hud
}
