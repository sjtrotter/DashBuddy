package cloud.trotter.dashbuddy.statev2

import cloud.trotter.dashbuddy.data.event.AppEventEntity
import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.data.event.EventMetadata
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.state.StateContext
import com.google.gson.Gson
import java.util.UUID

object Reducer {

    private val gson = Gson()

    data class Transition(
        val newState: AppStateV2,
        val effects: List<AppEffect> = emptyList()
    )

    // CHANGED: Now takes full StateContext to access Odometer/DashID
    fun reduce(currentState: AppStateV2, context: StateContext): Transition {
        val input = context.screenInfo ?: return Transition(currentState)

        // Filter noise early
        if (input.screen == Screen.UNKNOWN) return Transition(currentState)

        // 1. ANCHORS (Global Interceptors)
        // These override the current state because they represent absolute truth.
        val anchorTransition = checkAnchors(currentState, input, context)
        if (anchorTransition != null) {
            return anchorTransition
        }

        // 2. STANDARD STATE MACHINE (Sequential Logic)
        return when (currentState) {
            is AppStateV2.Initializing -> reduceInitializing(currentState, input)
            is AppStateV2.IdleOffline -> reduceIdle(currentState, input, context)
            is AppStateV2.AwaitingOffer -> reduceAwaitingOffer(currentState, input, context)
            // ... other states ...
            else -> Transition(currentState)
        }
    }

    // --- The Router ---

    private fun checkAnchors(
        state: AppStateV2,
        input: ScreenInfo,
        context: StateContext
    ): Transition? {
        return when (input) {
            // Anchor 1: Delivery Completed (The "Payday" screen)
            is ScreenInfo.DeliveryCompleted -> reduceDeliveryCompleted(state, input, context)

            // Anchor 2: Dash Summary (The "Session End" screen)
            is ScreenInfo.DashSummary -> reduceDashSummary(state, input, context)

            else -> null
        }
    }

    // --- Anchor Logic ---

    private fun reduceDeliveryCompleted(
        state: AppStateV2,
        input: ScreenInfo.DeliveryCompleted,
        context: StateContext
    ): Transition {
        val total = input.parsedPay.total

        // Deduplication: If we are already in PostDelivery with the same pay, ignore
        if (state is AppStateV2.PostDelivery && state.totalPay == total) {
            return Transition(state)
        }

        val newState = AppStateV2.PostDelivery(
            dashId = state.dashId, // Keep existing ID
            totalPay = total,
            summaryText = "Paid: $$total"
        )

        val effects = mutableListOf<AppEffect>()

        // 1. Log Event (With breakdown payload)
        val event = createEvent(
            state.dashId,
            AppEventType.DELIVERY_COMPLETED,
            gson.toJson(input.parsedPay),
            context.odometerReading
        )
        effects.add(AppEffect.LogEvent(event))

        // 2. Screenshot
        val filename = "payout_${total}_${System.currentTimeMillis()}"
        effects.add(AppEffect.CaptureScreenshot(filename))

        // 3. UI
        effects.add(AppEffect.UpdateBubble("Saved! $$total"))

        return Transition(newState, effects)
    }

    private fun reduceDashSummary(
        state: AppStateV2,
        input: ScreenInfo.DashSummary,
        context: StateContext
    ): Transition {
        val newState = AppStateV2.PostDash(
            dashId = state.dashId,
            totalEarnings = input.totalEarnings ?: 0.0,
            durationMillis = input.onlineDurationMillis,
            acceptanceRateForSession = "${input.offersAccepted}/${input.offersTotal}"
        )

        val effects = mutableListOf<AppEffect>()

        val event = createEvent(
            state.dashId,
            AppEventType.DASH_STOP,
            gson.toJson(input),
            context.odometerReading
        )
        effects.add(AppEffect.LogEvent(event))

        val earningStr = input.totalEarnings?.let { "$$it" } ?: "Unknown"
        effects.add(AppEffect.UpdateBubble("Dash Ended. Total: $earningStr"))
        effects.add(AppEffect.CaptureScreenshot("dash_summary_${System.currentTimeMillis()}"))

        return Transition(newState, effects)
    }

    // --- Standard Reducers ---

    private fun reduceInitializing(state: AppStateV2.Initializing, input: ScreenInfo): Transition {
        return if (input is ScreenInfo.IdleMap) {
            Transition(
                AppStateV2.IdleOffline(
                    lastKnownZone = input.zoneName,
                    dashType = input.dashType
                )
            )
        } else {
            Transition(state)
        }
    }

    private fun reduceIdle(
        state: AppStateV2.IdleOffline,
        input: ScreenInfo,
        context: StateContext
    ): Transition {
        return when (input) {
            is ScreenInfo.IdleMap -> {
                if (state.lastKnownZone != input.zoneName || state.dashType != input.dashType) {
                    Transition(
                        state.copy(
                            lastKnownZone = input.zoneName,
                            dashType = input.dashType
                        )
                    )
                } else {
                    Transition(state)
                }
            }

            is ScreenInfo.WaitingForOffer -> {
                val newDashId = UUID.randomUUID().toString()

                val newState = AppStateV2.AwaitingOffer(
                    dashId = newDashId,
                    currentSessionPay = input.currentDashPay,
                    waitTimeEstimate = input.waitTimeEstimate,
                    isHeadingBackToZone = input.isHeadingBackToZone
                )

                val startPayload = mapOf(
                    "zone" to state.lastKnownZone,
                    "type" to state.dashType,
                    "start_screen" to "WaitingForOffer"
                )

                val startEvent = createEvent(
                    newDashId,
                    AppEventType.DASH_START,
                    gson.toJson(startPayload),
                    context.odometerReading
                )

                Transition(
                    newState = newState,
                    effects = listOf(
                        AppEffect.LogEvent(startEvent),
                        AppEffect.UpdateBubble("Dash Started! Good luck.")
                    )
                )
            }

            else -> Transition(state)
        }
    }

    private fun reduceAwaitingOffer(
        state: AppStateV2.AwaitingOffer,
        input: ScreenInfo,
        context: StateContext
    ): Transition {
        return when (input) {
            is ScreenInfo.WaitingForOffer -> {
                // Implicit Hash Check: Only update if data changed
                if (state.currentSessionPay != input.currentDashPay ||
                    state.waitTimeEstimate != input.waitTimeEstimate ||
                    state.isHeadingBackToZone != input.isHeadingBackToZone
                ) {
                    Transition(
                        state.copy(
                            currentSessionPay = input.currentDashPay,
                            waitTimeEstimate = input.waitTimeEstimate,
                            isHeadingBackToZone = input.isHeadingBackToZone
                        )
                    )
                } else {
                    Transition(state)
                }
            }

            is ScreenInfo.IdleMap -> {
                val endEvent = createEvent(
                    state.dashId,
                    AppEventType.DASH_STOP,
                    "Return to Map",
                    context.odometerReading
                )
                Transition(
                    newState = AppStateV2.IdleOffline(lastKnownZone = input.zoneName),
                    effects = listOf(
                        AppEffect.LogEvent(endEvent),
                        AppEffect.UpdateBubble("Dash Ended")
                    )
                )
            }

            else -> Transition(state)
        }
    }

    // --- Helper ---

    private fun createEvent(
        dashId: String?,
        type: AppEventType,
        payload: String,
        odometer: Double?
    ): AppEventEntity {

        // Ensure you define EventMetadata data class somewhere!
        val metadata = EventMetadata(odometer = odometer)

        return AppEventEntity(
            aggregateId = dashId,
            eventType = type,
            eventPayload = payload,
            occurredAt = System.currentTimeMillis(),
            metadata = gson.toJson(metadata)
        )
    }
}