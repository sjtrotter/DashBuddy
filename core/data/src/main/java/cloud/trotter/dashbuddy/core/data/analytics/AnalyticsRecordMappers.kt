package cloud.trotter.dashbuddy.core.data.analytics

import cloud.trotter.dashbuddy.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.OfferRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.PickupRecordEntity
import cloud.trotter.dashbuddy.domain.analytics.DeliveryFold
import cloud.trotter.dashbuddy.domain.analytics.OfferFold
import cloud.trotter.dashbuddy.domain.analytics.PickupFold

internal fun DeliveryFold.toEntity() = DeliveryRecordEntity(
    eventSequenceId = eventSequenceId,
    sessionId = sessionId,
    platform = platform,
    jobId = jobId,
    taskId = taskId,
    storeName = storeName,
    customerHash = customerHash,
    addressHash = addressHash,
    phaseStartedAt = phaseStartedAt,
    arrivedAt = arrivedAt,
    completedAt = completedAt,
    deadlineMillis = deadlineMillis,
    realizedPay = realizedPay,
    payBasis = payBasis,
    tip = tip,
    basePay = basePay,
    odometerAtCompletion = odometerAtCompletion,
    realizedMiles = realizedMiles,
    realizedMinutes = realizedMinutes,
    frozenCostPerMile = frozenCostPerMile,
    frozenFuelPerMile = frozenFuelPerMile,
    frozenNonFuelPerMile = frozenNonFuelPerMile,
    netProfit = netProfit,
    costBasis = costBasis,
    cashTip = cashTip,
    // #703: stamp the first-fold basis ONCE, here at fold time. Every later correction apply
    // preserves it via `row.copy`, so a re-priced row keeps its original receipt-evidence basis
    // for the #691 hydration COALESCE.
    originalPayBasis = payBasis,
    // #159: storeKey is null at fold time (stamped later by resolution, in the same transaction);
    // the full receipt store-form set is persisted here (serialized) as the row-sourced evidence.
    storeKey = null,
    payoutStoreForms = StoreResolutionRunner.encodeForms(payoutStoreForms),
    storeKeyPinned = 0,
    // #688 phase B: the machine-computed per-leg mileage (provenance; a driver miles edit never
    // rewrites these — see applyDeliveryAdjustment).
    milesToStore = milesToStore,
    milesToDropoff = milesToDropoff,
    // #1033: null at fold time — later corrections stamp these in the projector transaction,
    // in event-sequence order.
    receiptRepricedAt = null,
    driverAdjustedAt = null,
    jobOfferCount = jobOfferCount,
    soleOfferHash = soleOfferHash,
    odometerAtArrival = odometerAtArrival,
)

internal fun PickupFold.toEntity() = PickupRecordEntity(
    eventSequenceId = eventSequenceId,
    sessionId = sessionId,
    platform = platform,
    jobId = jobId,
    taskId = taskId,
    storeName = storeName,
    storeKey = null, // stamped later by resolution
    phaseStartedAt = phaseStartedAt,
    arrivedAt = arrivedAt,
    confirmedAt = confirmedAt,
    deadlineMillis = deadlineMillis,
    activity = activity,
    storeAddress = storeAddress,
    odometerAtConfirmation = odometerAtConfirmation,
)

internal fun OfferFold.toEntity() = OfferRecordEntity(
    eventSequenceId = eventSequenceId,
    sessionId = sessionId,
    platform = platform,
    offerHash = offerHash,
    outcome = outcome,
    presentedAt = presentedAt,
    decidedAt = decidedAt,
    payAmount = payAmount,
    distanceMiles = distanceMiles,
    itemCount = itemCount,
    merchantName = merchantName,
    score = score,
    action = action,
    quality = quality,
    estNetPay = estNetPay,
    estDollarsPerHour = estDollarsPerHour,
    estDollarsPerMile = estDollarsPerMile,
    estTimeMinutes = estTimeMinutes,
    estOperatingCostPerMile = estOperatingCostPerMile,
    estFuelPerMile = estFuelPerMile,
    estNonFuelPerMile = estNonFuelPerMile,
    orderCount = orderCount,
    isShop = isShop,
)
