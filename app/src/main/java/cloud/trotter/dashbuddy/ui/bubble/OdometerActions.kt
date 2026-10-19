package cloud.trotter.dashbuddy.ui.bubble

import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.core.data.vehicle.VehiclesUnreadableException
import cloud.trotter.dashbuddy.core.data.vehicle.OdometerReadingRepository
import cloud.trotter.dashbuddy.feature.bubble.hud.odometer.OdometerNoticeModel
import cloud.trotter.dashbuddy.feature.bubble.hud.odometer.OdometerWriteFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Clock

/** Owned by BubbleViewModel. Only successful appends produce a notice or close the sheet. */
internal class OdometerActions(
    private val scope: CoroutineScope,
    private val garage: GarageRepository,
    private val readings: OdometerReadingRepository,
    private val clock: Clock,
) {
    private val busy = MutableStateFlow(false)
    val saving = busy.asStateFlow()
    private enum class WriteKind { READING, VEHICLE }
    private var failureKind: WriteKind? = null
    private val failure = MutableStateFlow(OdometerWriteFailure.NONE)
    val failed = failure.asStateFlow()
    private val saved = MutableStateFlow<OdometerNoticeModel?>(null)
    val notice = saved.asStateFlow()
    private val completed = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
    val saveCompleted: SharedFlow<Unit> = completed.asSharedFlow()
    private val changed = MutableSharedFlow<VehicleChange>(replay = 1, extraBufferCapacity = 1)
    val vehicleChanged: SharedFlow<VehicleChange> = changed.asSharedFlow()

    companion object {
        const val NOTICE_MS = 10_000L
    }

    fun reopen() {
        failure.value = OdometerWriteFailure.NONE
        failureKind = null
    }
    /** A late collector sees the one pending completion once. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun saveHandled() { completed.resetReplayCache() }
    /** A late collector sees the one pending vehicle change once. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun vehicleChangeHandled() { changed.resetReplayCache() }

    fun save(vehicleId: String, miles: Int) {
        write(WriteKind.READING) {
            readings.record(vehicleId, miles)
            completed.emit(Unit)
            val model = OdometerNoticeModel(miles, HudNoticeOrder.next())
            saved.value = model
            scope.launch {
                delay(NOTICE_MS)
                if (saved.value?.token == model.token) saved.value = null
            }
            true
        }
    }
    fun add(name: String) = write(WriteKind.VEHICLE) {
        garage.addVehicle(name, clock.millis())
        changed.emit(VehicleChange.ADDED)
        true
    }
    fun select(id: String) = write(WriteKind.VEHICLE) {
        // A refused select (a stale row) changes nothing: not a vehicle change, not a success.
        val written = garage.selectVehicle(id)
        if (written) changed.emit(VehicleChange.SWITCHED)
        written
    }
    fun retire(id: String) = write(WriteKind.VEHICLE) {
        val wasActive = garage.garage.first().active?.id == id
        val written = garage.retire(id, clock.millis())
        if (written) changed.emit(VehicleChange.RETIRED(id, wasActive))
        written
    }

    /**
     * The failure shown follows the last COMPLETED write of its kind: a confirmed success of the same
     * kind clears it, an attempt never does (a refused select writes nothing and leaves it standing).
     */
    private fun write(kind: WriteKind, action: suspend () -> Boolean) {
        if (busy.value) return
        busy.value = true
        scope.launch {
            try {
                // A confirmed tap must land; the write is a single bounded insert.
                val wrote = withContext(NonCancellable) { action() }
                if (wrote && failureKind == kind) {
                    failure.value = OdometerWriteFailure.NONE
                    failureKind = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failureKind = kind
                failure.value = if (error is VehiclesUnreadableException) OdometerWriteFailure.UNREADABLE else OdometerWriteFailure.RETRY
                // Exception messages can contain the user's vehicle name.
                Timber.tag("Odometer").w("Could not save odometer data (%s)", error.javaClass.simpleName)
            } finally {
                busy.value = false
            }
        }
    }
}
