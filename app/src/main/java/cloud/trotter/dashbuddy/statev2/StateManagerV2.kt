package cloud.trotter.dashbuddy.statev2

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.state.StateContext
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import cloud.trotter.dashbuddy.log.Logger as Log
import android.os.Build
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.data.offer.OfferEvaluator
import cloud.trotter.dashbuddy.services.LocationService
import cloud.trotter.dashbuddy.statev2.effects.OdometerEffect
import cloud.trotter.dashbuddy.statev2.effects.ScreenShot
import cloud.trotter.dashbuddy.statev2.effects.TipEffectHandler
import kotlinx.coroutines.delay

object StateManagerV2 {

    private const val TAG = "StateManagerV2"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val gson = Gson().newBuilder()
        .registerTypeAdapterFactory(
            cloud.trotter.dashbuddy.util.RuntimeTypeAdapterFactory.of(
                AppStateV2::class.java,
                "type"
            )
                .registerSubtype(AppStateV2.Initializing::class.java)
                .registerSubtype(AppStateV2.IdleOffline::class.java)
                .registerSubtype(AppStateV2.PostDash::class.java)
                .registerSubtype(AppStateV2.AwaitingOffer::class.java)
                .registerSubtype(AppStateV2.OfferPresented::class.java)
                .registerSubtype(AppStateV2.OnPickup::class.java)
                .registerSubtype(AppStateV2.OnDelivery::class.java)
                .registerSubtype(AppStateV2.ExpandingDeliverySummary::class.java)
                .registerSubtype(AppStateV2.PostDelivery::class.java)
                .registerSubtype(AppStateV2.DashPaused::class.java)
                .registerSubtype(AppStateV2.PausedOrInterrupted::class.java)
        )
        .create()

    // CHANGED: Input Queue now holds the full Context (for Odometer, etc.)
    private val inputChannel = Channel<StateContext>(Channel.UNLIMITED)

    // Output State (UI observes this)
    private val _state = MutableStateFlow<AppStateV2>(AppStateV2.Initializing)
    val state = _state.asStateFlow()

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    fun initialize() {
        Log.i(TAG, "Initializing V2 State Machine...")
        restoreState()
        startProcessor()
        startHeartbeat() // <--- NEW: Start the heartbeat
    }

    // NEW: The Heartbeat Loop
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun startHeartbeat() {
        scope.launch {
            while (true) {
                if (isActiveDash(_state.value)) {
                    // Send Keep Alive every 5 minutes
                    executeEffect(AppEffect.SendKeepAlive)
                }
                delay(5 * 60 * 1000L)
            }
        }
    }

    // Helper to determine if we should be tracking miles
    private fun isActiveDash(state: AppStateV2): Boolean {
        return state !is AppStateV2.IdleOffline &&
                state !is AppStateV2.Initializing &&
                state !is AppStateV2.PostDash
    }

    fun dispatch(context: StateContext) {
        // We still only care if there is valid screen info, but we send the WHOLE context.
        if (context.screenInfo != null || context.notification != null) {
            inputChannel.trySend(context)
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun startProcessor() {
        scope.launch {
            for (context in inputChannel) { // Now receiving StateContext
                val currentState = _state.value

                // CHANGED: Pass full context to Reducer
                val transition = Reducer.reduce(currentState, context)

                // 2. Update State (if changed)
                if (transition.newState != currentState) {
                    val oldClass = currentState::class.simpleName
                    val newClass = transition.newState::class.simpleName

                    if (oldClass != newClass) {
                        Log.i(TAG, ">>> TRANSITION: $oldClass -> $newClass")
                    } else {
                        // Verbose logging for data updates
                        Log.v(TAG, "    Update within $newClass: ${transition.newState}")
                    }

                    _state.value = transition.newState
                    saveState(transition.newState)
                }

                // 3. Execute Effects (Side Effects)
                transition.effects.forEach { effect ->
                    executeEffect(effect)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun executeEffect(effect: AppEffect) {
        when (effect) {
            is AppEffect.LogEvent -> {
                scope.launch(Dispatchers.IO) {
                    Log.v(TAG, "Logging Event: ${effect.event.eventType}")
                    DashBuddyApplication.appEventRepo.insert(effect.event)
                }
            }

            is AppEffect.UpdateBubble -> {
                Log.i(TAG, "Bubble Update: ${effect.text}")
                DashBuddyApplication.sendBubbleMessage(effect.text)
            }

            is AppEffect.CaptureScreenshot -> {
                ScreenShot.capture(scope, effect)
            }

            is AppEffect.PlayNotificationSound -> {
                // Play sound logic
            }

            is AppEffect.ProcessTipNotification -> {
                TipEffectHandler.process(scope, effect)
            }

            is AppEffect.ScheduleTimeout -> {
                TimeoutHandler.schedule(scope, effect.timestamp)
            }

            is AppEffect.CancelTimeout -> {
                TimeoutHandler.cancel()
            }

            is AppEffect.StartOdometer -> {
                OdometerEffect.startUp()
            }

            is AppEffect.StopOdometer -> {
                OdometerEffect.shutDown()
            }

            is AppEffect.EvaluateOffer -> {
                DashBuddyApplication.sendBubbleMessage(
                    OfferEvaluator.evaluateOffer(effect.parsedOffer)
                )
            }

            is AppEffect.ClickNode -> {
                Log.i(TAG, "Executing Effect: Clicking Node (${effect.description})")
                // Use your existing robust utility
                cloud.trotter.dashbuddy.util.AccNodeUtils.clickNode(effect.node.originalNode)
            }

            is AppEffect.Delayed -> {
                scope.launch {
                    delay(effect.delayMs)
                    executeEffect(effect.effect) // Recursive call after delay
                }
            }

            is AppEffect.SendKeepAlive -> {
                try {
                    val intent = android.content.Intent(
                        DashBuddyApplication.context,
                        LocationService::class.java
                    ).apply {
                        action = LocationService.ACTION_KEEP_ALIVE
                    }
                    DashBuddyApplication.context.startForegroundService(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send KeepAlive", e)
                }
            }
        }
    }

    // --- Persistence (Crash Recovery) ---

    private fun saveState(state: AppStateV2) {
        try {
            val json = gson.toJson(state)
            DashBuddyApplication.saveCrashRecoveryState(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save state", e)
        }
    }

    private fun restoreState() {
        val json = DashBuddyApplication.getCrashRecoveryState()
        if (json != null) {
            try {
                // Note regarding Gson & Sealed Classes:
                // Standard Gson doesn't handle polymorphic deserialization (Sealed Classes) automatically.
                // It will likely deserialize to the Base Type (AppStateV2) and lose the data fields
                // unless you register a RuntimeTypeAdapterFactory.

                // For this Draft/Test phase, if it fails, we just reset.
                Log.i(TAG, "Restoring state from storage...")
                _state.value = gson.fromJson(json, AppStateV2::class.java)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore state. Starting fresh.", e)
                _state.value = AppStateV2.Initializing
            }
        } else {
            _state.value = AppStateV2.Initializing
        }
    }
}