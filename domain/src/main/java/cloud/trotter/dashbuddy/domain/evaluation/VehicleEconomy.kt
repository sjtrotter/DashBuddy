package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleProfile

/** Applies a car's profile without changing shared gas, time, or phone inputs. */
object VehicleEconomy {
    fun apply(base: UserEconomy, profile: VehicleProfile): UserEconomy {
        val cls = profile.vehicleClass
        return base.copy(
            vehicleClass = cls,
            vehicleMpg = profile.mpg ?: cls.defaultMpg.coerceAtLeast(1.0),
            tireSetCost = profile.tireSetCost ?: cls.tireSetCost,
            tireLifetimeMi = profile.tireLifetimeMi ?: cls.tireLifetimeMi,
            oilCost = profile.oilCost ?: cls.oilCost,
            oilIntervalMi = profile.oilIntervalMi ?: cls.oilIntervalMi,
            brakesCost = profile.brakesCost ?: cls.brakesCost,
            brakesIntervalMi = profile.brakesIntervalMi ?: cls.brakesIntervalMi,
            fluidsCost = profile.fluidsCost ?: cls.fluidsCost,
            fluidsIntervalMi = profile.fluidsIntervalMi ?: cls.fluidsIntervalMi,
            miscYearly = profile.miscYearly ?: cls.miscYearly,
            miscYearlyMi = profile.miscYearlyMi ?: cls.miscYearlyMi,
            includeDepreciation = profile.includeDepreciation ?: true,
            purchasePrice = profile.purchasePrice ?: cls.purchasePrice,
            totalLifetimeMi = profile.totalLifetimeMi ?: cls.totalLifetimeMi,
            insuranceDeltaPerMonth = profile.insuranceDeltaPerMonth ?: 0.0,
            registrationDeltaPerYear = profile.registrationDeltaPerYear ?: 0.0,
            expectedAnnualMiles = profile.expectedAnnualMiles ?: UserEconomy.DEFAULT_ANNUAL_MI,
            userSetFields = base.userSetFields.filterTo(mutableSetOf()) { field ->
                field !in VEHICLE_FIELDS
            } + setOfNotNull(
                EconomyField.VEHICLE_CLASS.takeIf { cls != cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass.SEDAN },
                EconomyField.VEHICLE_MPG.takeIf { profile.mpg != null },
                EconomyField.TIRE_COST.takeIf { profile.tireSetCost != null },
                EconomyField.TIRE_LIFETIME.takeIf { profile.tireLifetimeMi != null },
                EconomyField.OIL_COST.takeIf { profile.oilCost != null },
                EconomyField.OIL_INTERVAL.takeIf { profile.oilIntervalMi != null },
                EconomyField.BRAKES_COST.takeIf { profile.brakesCost != null },
                EconomyField.BRAKES_INTERVAL.takeIf { profile.brakesIntervalMi != null },
                EconomyField.FLUIDS_COST.takeIf { profile.fluidsCost != null },
                EconomyField.FLUIDS_INTERVAL.takeIf { profile.fluidsIntervalMi != null },
                EconomyField.MISC_YEARLY.takeIf { profile.miscYearly != null },
                EconomyField.MISC_YEARLY_MI.takeIf { profile.miscYearlyMi != null },
                EconomyField.INCLUDE_DEPRECIATION.takeIf { profile.includeDepreciation != null },
                EconomyField.PURCHASE_PRICE.takeIf { profile.purchasePrice != null },
                EconomyField.TOTAL_LIFETIME_MI.takeIf { profile.totalLifetimeMi != null },
                EconomyField.INSURANCE_DELTA.takeIf { profile.insuranceDeltaPerMonth != null },
                EconomyField.REGISTRATION_DELTA.takeIf { profile.registrationDeltaPerYear != null },
                EconomyField.EXPECTED_ANNUAL_MI.takeIf { profile.expectedAnnualMiles != null },
            ),
        )
    }

    private val VEHICLE_FIELDS = setOf(
        EconomyField.VEHICLE_CLASS, EconomyField.VEHICLE_MPG,
        EconomyField.TIRE_COST, EconomyField.TIRE_LIFETIME, EconomyField.OIL_COST, EconomyField.OIL_INTERVAL,
        EconomyField.BRAKES_COST, EconomyField.BRAKES_INTERVAL, EconomyField.FLUIDS_COST,
        EconomyField.FLUIDS_INTERVAL, EconomyField.MISC_YEARLY, EconomyField.MISC_YEARLY_MI,
        EconomyField.INCLUDE_DEPRECIATION, EconomyField.PURCHASE_PRICE, EconomyField.TOTAL_LIFETIME_MI,
        EconomyField.INSURANCE_DELTA, EconomyField.REGISTRATION_DELTA, EconomyField.EXPECTED_ANNUAL_MI,
    )
}
