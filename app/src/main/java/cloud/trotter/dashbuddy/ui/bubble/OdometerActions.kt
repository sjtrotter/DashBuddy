package cloud.trotter.dashbuddy.ui.bubble

import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.core.data.vehicle.OdometerReadingRepository
import cloud.trotter.dashbuddy.feature.bubble.hud.odometer.OdometerNoticeModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val failure = MutableStateFlow(false)
    val failed = failure.asStateFlow()
    private val saved = MutableStateFlow<OdometerNoticeModel?>(null)
    val notice = saved.asStateFlow()

    companion object {
        const val NOTICE_MS = 10_000L
    }

    fun reopen() { failure.value = false }
    fun save(vehicleId: String, miles: Int) {
        val at = clock.millis()
        write {
            readings.record(vehicleId, miles, at)
            val model = OdometerNoticeModel(miles, clock.millis())
            saved.value = model
            scope.launch {
                delay(NOTICE_MS)
                if (saved.value === model) saved.value = null
            }
        }
    }
    fun add(name: String) = write { garage.addVehicle(name, clock.millis()) }
    fun select(id: String) = write { garage.selectVehicle(id) }

    private fun write(action: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        failure.value = false
        scope.launch {
            try {
                // A confirmed tap must land; the write is a single bounded insert.
                withContext(NonCancellable) { action() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure.value = true
                // Exception messages can contain the user's vehicle name.
                Timber.tag("Odometer").w("Could not save odometer data (%s)", error.javaClass.simpleName)
            } finally {
                busy.value = false
            }
        }
    }
}
