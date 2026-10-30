package cloud.trotter.log.gig.feature.setup.wizard.model

import cloud.trotter.log.gig.feature.setup.wizard.vehicle.VehicleDraftState
import cloud.trotter.log.gig.domain.config.OfferStrategy
import cloud.trotter.log.gig.domain.evaluation.EconomyField
import cloud.trotter.log.gig.domain.evaluation.UserEconomy
import cloud.trotter.log.gig.domain.model.vehicle.FuelType
import cloud.trotter.log.gig.domain.model.vehicle.*
import cloud.trotter.log.gig.domain.evaluation.*

/**
 * Represents the volatile UI state of the Setup Wizard before it is committed to DataStore.
 */
data class WizardState(
    // Gas Variables
    val fuelType: FuelType = FuelType.REGULAR,
    val isGasPriceAuto: Boolean = false,
    val gasPrice: Float = 3.50f,
    val isFetchingGasPrice: Boolean = false,
    val fetchedGasPrices: Map<FuelType, Float> = emptyMap(),

    // Personal Economy v2 — operating cost components (#145)
    val wear: WearProfile = WearProfile(
        tireSetCost = VehicleClass.SEDAN.tireSetCost,
        tireLifetimeMi = VehicleClass.SEDAN.tireLifetimeMi,
        oilCost = VehicleClass.SEDAN.oilCost,
        oilIntervalMi = VehicleClass.SEDAN.oilIntervalMi,
        brakesCost = VehicleClass.SEDAN.brakesCost,
        brakesIntervalMi = VehicleClass.SEDAN.brakesIntervalMi,
        fluidsCost = VehicleClass.SEDAN.fluidsCost,
        fluidsIntervalMi = VehicleClass.SEDAN.fluidsIntervalMi,
        miscYearly = VehicleClass.SEDAN.miscYearly,
        miscYearlyMi = VehicleClass.SEDAN.miscYearlyMi,
        chainCost = 0.0,
        chainLifeMi = 1.0,
        batteryCost = 0.0,
        batteryLifeMi = 1.0,
        beltCost = 0.0,
        beltLifeMi = 1.0,
    ),
    val fixed: FixedCosts = FixedCosts(
        includeDepreciation = true,
        purchasePrice = VehicleClass.SEDAN.purchasePrice,
        totalLifetimeMi = VehicleClass.SEDAN.totalLifetimeMi,
        insuranceDeltaPerMonth = 0.0,
        registrationDeltaPerYear = 0.0,
        expectedAnnualMiles = UserEconomy.DEFAULT_ANNUAL_MI,
    ),
    val phonePlanTotal: Double = UserEconomy.DEFAULT_PHONE_PLAN_TOTAL,
    val phonePlanLines: Int = UserEconomy.DEFAULT_PHONE_PLAN_LINES,
    val phoneBusinessPercent: Double = UserEconomy.DEFAULT_PHONE_BUSINESS_PERCENT,
    val avgMinutesPerMile: Double = UserEconomy.DEFAULT_MINUTES_PER_MILE,
    val basePickupMinutes: Double = UserEconomy.DEFAULT_BASE_PICKUP_MINUTES,
    /**
     * Host-owned fields, disjoint by construction from [VehicleDraftState.userSetFields]:
     * loading excludes [VehicleDraftState.fields], and vehicle actions only update the draft.
     */
    val userSetEconomyFields: Set<EconomyField> = emptySet(),

    // Strategy Variables
    val strategy: OfferStrategy = OfferStrategy.MANUAL,

    // Targets & Enforcement
    val enforceMinPayout: Boolean = false,
    val minPayout: Float = 5.0f,

    val enforceTargetHourly: Boolean = false,
    val targetHourly: Float = 20.0f,

    val enforceMaxDistance: Boolean = false,
    val maxDistance: Float = 10.0f,

    val maxItems: Float = 15.0f // Shopping
)

private fun WizardState.sharedDraft(vehicle: VehicleDraftState): SharedEconomy = SharedEconomy(
    gasPricePerGallon = gasPrice.toDouble(),
    electricityPricePerKwh = vehicle.electricityPricePerKwh,
    avgMinutesPerMile = avgMinutesPerMile,
    basePickupMinutes = basePickupMinutes,
    phonePlanTotal = phonePlanTotal,
    phonePlanLines = phonePlanLines,
    phoneBusinessPercent = phoneBusinessPercent,
    expectedAnnualMiles = fixed.expectedAnnualMiles ?: UserEconomy.DEFAULT_ANNUAL_MI,
    userSetFields = userSetEconomyFields + vehicle.userSetFields,
)

/**
 * Projects the wizard's in-memory economy edits onto an [EconomyDraft] — the single
 * owner of this conversion (#357). The ECONOMY_COSTS card uses it to drive live
 * $/mi summaries and "(default)" badges; [cloud.trotter.log.gig.feature.setup.wizard.WizardViewModel.saveAndFinish]
 * commits its vehicle overrides in one targeted profile write and mirrors the
 * user-set fields to legacy preferences. Carries
 * [userSetEconomyFields] together with the vehicle draft's fields so the repository
 * persists exactly the fields the user touched.
 */
private fun WizardState.draft(vehicle: VehicleDraftState): EconomyDraft = EconomyDraft(
    vehicleClass = vehicle.vehicleClass,
    mpg = vehicle.mpg.toDouble(),
    kwhPer100Mi = vehicle.kwhPer100Mi,
    whPerMile = vehicle.whPerMile,
    wear = wear,
    fixed = fixed,
    shared = sharedDraft(vehicle),
    userSetFields = userSetEconomyFields + vehicle.userSetFields,
)

fun WizardState.toVehicleProfile(vehicle: VehicleDraftState): VehicleProfile =
    EconomyFieldGroups.updateProfile(vehicle.profile(), draft(vehicle).copy(userSetFields = userSetEconomyFields))

fun WizardState.toUserEconomy(vehicle: VehicleDraftState): UserEconomy {
    val shared = sharedDraft(vehicle)
    return UserEconomy.from(VehicleEconomyInputs.from(toVehicleProfile(vehicle), shared), shared)
}

/**
 * The draft the legacy preference mirror saves: the resolved vehicle's values and user-set fields together, as
 * [toUserEconomy] resolves them. A cost edited before a type change that no longer applies is not mirrored, and an
 * override the saved profile already holds is mirrored with its stored value.
 */
fun WizardState.toEconomyDraft(vehicle: VehicleDraftState): EconomyDraft {
    val resolved = toUserEconomy(vehicle)
    val detail = resolved.vehicleInputs?.disclosure
    val draft = draft(vehicle)
    return draft.copy(
        vehicleClass = resolved.vehicleClass, mpg = resolved.vehicleMpg,
        kwhPer100Mi = resolved.kwhPer100Mi, whPerMile = resolved.whPerMile,
        wear = detail?.wear ?: draft.wear, fixed = detail?.fixed ?: draft.fixed,
        userSetFields = resolved.userSetFields,
    )
}

fun WizardState.withTypeDefaults(vehicle: VehicleDraftState): WizardState {
    val resolved = toUserEconomy(vehicle)
    val detail = resolved.vehicleInputs?.disclosure ?: return this
    return copy(
        wear = detail.wear,
        fixed = fixed.copy(purchasePrice = detail.fixed.purchasePrice, totalLifetimeMi = detail.fixed.totalLifetimeMi),
        avgMinutesPerMile = resolved.avgMinutesPerMile, basePickupMinutes = resolved.basePickupMinutes,
    )
}
