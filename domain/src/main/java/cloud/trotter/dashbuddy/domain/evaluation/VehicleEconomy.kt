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

    fun hasCostOverrides(economy: UserEconomy): Boolean = economy.userSetFields.any {
        it in VEHICLE_FIELDS && it != EconomyField.VEHICLE_CLASS && it != EconomyField.VEHICLE_MPG
    }

    /** Applies only explicit draft overrides, preserving nullable preset-following fields. */
    fun updateProfile(profile: VehicleProfile, economy: UserEconomy): VehicleProfile {
        val userSet = economy.userSetFields
        var updated = profile
        if (EconomyField.TIRE_COST in userSet || EconomyField.TIRE_LIFETIME in userSet) {
            updated = updated.copy(tireSetCost = economy.tireSetCost, tireLifetimeMi = economy.tireLifetimeMi)
        }
        if (EconomyField.OIL_COST in userSet || EconomyField.OIL_INTERVAL in userSet) {
            updated = updated.copy(oilCost = economy.oilCost, oilIntervalMi = economy.oilIntervalMi)
        }
        if (EconomyField.BRAKES_COST in userSet || EconomyField.BRAKES_INTERVAL in userSet) {
            updated = updated.copy(brakesCost = economy.brakesCost, brakesIntervalMi = economy.brakesIntervalMi)
        }
        if (EconomyField.FLUIDS_COST in userSet || EconomyField.FLUIDS_INTERVAL in userSet) {
            updated = updated.copy(fluidsCost = economy.fluidsCost, fluidsIntervalMi = economy.fluidsIntervalMi)
        }
        if (EconomyField.MISC_YEARLY in userSet || EconomyField.MISC_YEARLY_MI in userSet) {
            updated = updated.copy(miscYearly = economy.miscYearly, miscYearlyMi = economy.miscYearlyMi)
        }
        if (EconomyField.INCLUDE_DEPRECIATION in userSet ||
            EconomyField.PURCHASE_PRICE in userSet || EconomyField.TOTAL_LIFETIME_MI in userSet
        ) {
            updated = updated.copy(
                includeDepreciation = economy.includeDepreciation,
                purchasePrice = economy.purchasePrice,
                totalLifetimeMi = economy.totalLifetimeMi,
            )
        }
        if (EconomyField.INSURANCE_DELTA in userSet) {
            updated = updated.copy(insuranceDeltaPerMonth = economy.insuranceDeltaPerMonth)
        }
        if (EconomyField.REGISTRATION_DELTA in userSet) {
            updated = updated.copy(registrationDeltaPerYear = economy.registrationDeltaPerYear)
        }
        if (EconomyField.EXPECTED_ANNUAL_MI in userSet) {
            updated = updated.copy(expectedAnnualMiles = economy.expectedAnnualMiles)
        }
        return updated
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
