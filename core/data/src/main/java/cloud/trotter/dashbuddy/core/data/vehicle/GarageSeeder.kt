package cloud.trotter.dashbuddy.core.data.vehicle

import cloud.trotter.dashbuddy.core.datastore.appstate.AppStateDataSource
import cloud.trotter.dashbuddy.core.datastore.settings.AppPreferencesDataSource
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleProfile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/** Imports the saved wizard car once. The completion flag is written only after the garage write. */
@Singleton
class GarageSeeder @Inject constructor(
    private val garage: GarageRepository,
    private val preferences: AppPreferencesDataSource,
    private val appState: AppStateDataSource,
) {
    private val mutex = Mutex()

    suspend fun seed() = mutex.withLock {
        try {
            if (appState.garageSeeded.first()) return@withLock
            val profile = preferences.legacyVehicleProfile.first()
            if (profile != null) {
                val current = garage.garage.first()
                if (!current.readable) throw VehiclesUnreadableException()
                if (current.vehicles.isEmpty()) {
                    garage.addVehicle(null, System.currentTimeMillis(), profile)
                } else {
                    val active = current.active
                    if (active != null) {
                        garage.updateProfile(active.id) { current -> merge(current, profile) }
                    }
                }
            }
            appState.setGarageSeeded()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Keep garage details, preference values, and exception messages out of logs.
            Timber.tag("Garage").w("Garage seed failed: 1")
        }
    }

    private fun merge(current: VehicleProfile, saved: VehicleProfile): VehicleProfile = current.copy(
        year = current.year ?: saved.year,
        make = current.make ?: saved.make,
        model = current.model ?: saved.model,
        mpg = current.mpg ?: saved.mpg,
        vehicleClass = if (current.vehicleClass == cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass.SEDAN)
            saved.vehicleClass else current.vehicleClass,
        fuelType = if (current.fuelType == cloud.trotter.dashbuddy.domain.model.vehicle.FuelType.REGULAR)
            saved.fuelType else current.fuelType,
        tireSetCost = current.tireSetCost ?: saved.tireSetCost,
        tireLifetimeMi = current.tireLifetimeMi ?: saved.tireLifetimeMi,
        oilCost = current.oilCost ?: saved.oilCost,
        oilIntervalMi = current.oilIntervalMi ?: saved.oilIntervalMi,
        brakesCost = current.brakesCost ?: saved.brakesCost,
        brakesIntervalMi = current.brakesIntervalMi ?: saved.brakesIntervalMi,
        fluidsCost = current.fluidsCost ?: saved.fluidsCost,
        fluidsIntervalMi = current.fluidsIntervalMi ?: saved.fluidsIntervalMi,
        miscYearly = current.miscYearly ?: saved.miscYearly,
        miscYearlyMi = current.miscYearlyMi ?: saved.miscYearlyMi,
        includeDepreciation = current.includeDepreciation ?: saved.includeDepreciation,
        purchasePrice = current.purchasePrice ?: saved.purchasePrice,
        totalLifetimeMi = current.totalLifetimeMi ?: saved.totalLifetimeMi,
        insuranceDeltaPerMonth = current.insuranceDeltaPerMonth ?: saved.insuranceDeltaPerMonth,
        registrationDeltaPerYear = current.registrationDeltaPerYear ?: saved.registrationDeltaPerYear,
        expectedAnnualMiles = current.expectedAnnualMiles ?: saved.expectedAnnualMiles,
    )
}
