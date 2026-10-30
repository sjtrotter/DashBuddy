package cloud.trotter.log.gig.ui.bubble

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.log.gig.core.designsystem.time.rememberNow
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.model.vehicle.DashVehicles
import cloud.trotter.log.gig.domain.model.vehicle.odometerShownFor
import cloud.trotter.log.gig.domain.model.vehicle.MileageReconciliation
import cloud.trotter.log.gig.domain.model.vehicle.OdometerCheckInput
import cloud.trotter.log.gig.domain.model.vehicle.OdometerReadingCheck
import cloud.trotter.log.gig.domain.model.vehicle.VehicleOdometerReading
import cloud.trotter.log.gig.feature.bubble.hud.odometer.*

@Stable
internal class OdometerHud(
    private val viewModel: BubbleViewModel,
    private val entry: OdometerEntryState,
    private val closeLog: () -> Unit,
    private val onAddVehicle: () -> Unit,
) {
    val open: Boolean get() = entry.open

    /** A tap while the garage or readings are loading is inert. */
    fun openSheet() {
        if (!viewModel.odometerLoaded.value) return
        closeLog()
        val garage = viewModel.garage.value
        if (garage?.unretired?.isEmpty() == true) {
            onAddVehicle()
            return
        }
        if (entry.show(anyVehicles = garage?.unretired?.isNotEmpty() == true)) {
            viewModel.reopenOdometer()
        }
    }

    fun dismiss() { entry.open = false }

    /** Back closes the reading sheet. */
    fun back() { entry.back() }

    @Composable
    fun ObserveEntry() {
        LaunchedEffect(viewModel, entry) {
            viewModel.odometerSaveCompleted.collect {
                entry.open = false
                viewModel.odometerSaveHandled()
            }
        }
        LaunchedEffect(viewModel, entry) {
            viewModel.odometerVehicleChanged.collect {
                entry.vehicleSwitched()
                viewModel.odometerVehicleChangeHandled()
            }
        }
        // A typed reading belongs to the vehicle it was typed for. The collapsed bubble keeps its sheet, and
        // the active vehicle can change elsewhere (the app's Garage), so any change of the active vehicle
        // clears the draft; otherwise Save would record it against the new vehicle (#1379 follow-up review).
        val garage by viewModel.garage.collectAsStateWithLifecycle()
        val loaded = garage != null
        val activeId = garage?.active?.id
        val seenActive = rememberSaveable { mutableStateOf<String?>(null) }
        val seen = rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(loaded, activeId) {
            if (!loaded) return@LaunchedEffect
            if (seen.value && seenActive.value != activeId) entry.vehicleSwitched()
            seenActive.value = activeId
            seen.value = true
        }
    }

    /** Reading validation ticks once a minute for day-granular thresholds and the open span bound. */
    @Composable
    fun Sheet(vehicleChip: @Composable () -> Unit = {}) {
        val garage by viewModel.garage.collectAsStateWithLifecycle()
        val vehicle = garage?.active
        val readings by viewModel.odometerReadings.collectAsStateWithLifecycle()
        val gpsTotal by viewModel.gpsTotalMiles.collectAsStateWithLifecycle()
        val saving by viewModel.odometerSaving.collectAsStateWithLifecycle()
        val writeFailed by viewModel.odometerWriteFailed.collectAsStateWithLifecycle()
        val last = vehicle?.let { VehicleOdometerReading.latestFor(readings.orEmpty(), it.id) }
        val now by rememberNow(60_000)
        val check = OdometerReadingCheck.check(OdometerCheckInput(entry.reading, vehicle, last, gpsTotal, now, readings.orEmpty()))
        Column(Modifier.fillMaxSize()) {
            OdometerSheet(OdometerSheetModel(vehicle, garage?.unretired.orEmpty(), entry.reading, last, check, saving,
                writeFailed = if (garage?.readable == false) OdometerWriteFailure.UNREADABLE else writeFailed,
                gpsLoaded = gpsTotal != null),
                onDigit = entry::digit, onBackspace = entry::backspace,
                onSave = { entry.reading?.let(viewModel::saveOdometerReading) },
                vehicleChip = vehicleChip,
                onShowAddVehicle = onAddVehicle, onBack = ::back)
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
    fun RestingFooter(onOpen: () -> Unit = ::openSheet, odometerShown: Boolean = true) {
        val loaded by viewModel.odometerLoaded.collectAsStateWithLifecycle()
        if (!loaded) return
        val garage by viewModel.garage.collectAsStateWithLifecycle()
        if (!odometerShown || !odometerShownFor(garage?.takeIf { it.readable }?.active)) return
        val readings by viewModel.odometerReadings.collectAsStateWithLifecycle()
        val vehicle = garage?.active
        val lastReading = vehicle?.let { VehicleOdometerReading.latestFor(readings.orEmpty(), it.id) }
        val stamps by viewModel.stamps.collectAsStateWithLifecycle()
        val reconciliation = remember(readings, vehicle, stamps) {
            readings?.let { list -> vehicle?.id?.let { MileageReconciliation.latest(list, it, DashVehicles.perDash(stamps)) } }
        }
        OdometerFooter(vehicle, lastReading, reconciliation, onOpen)
    }

    /** The start-window cue ticks once a minute while its window is open. */
    @Composable
    fun Cue(startedAt: Long?, endedAt: Long?, odometerShown: Boolean = true): Boolean {
        val loaded by viewModel.odometerLoaded.collectAsStateWithLifecycle()
        val garage by viewModel.garage.collectAsStateWithLifecycle()
        if (!odometerShown || !odometerShownFor(garage?.takeIf { it.readable }?.active)) return false
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
internal fun rememberOdometerHud(
    viewModel: BubbleViewModel,
    closeLog: () -> Unit,
    onAddVehicle: () -> Unit,
): OdometerHud {
    val entry = rememberOdometerEntryState()
    val hud = remember(viewModel, entry, closeLog, onAddVehicle) { OdometerHud(viewModel, entry, closeLog, onAddVehicle) }
    hud.ObserveEntry()
    return hud
}
