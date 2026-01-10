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
import cloud.trotter.dashbuddy.statev2.effects.DefaultEffectHandler
import cloud.trotter.dashbuddy.statev2.effects.EffectHandler
import kotlinx.coroutines.delay

object StateManagerV2 {

    private const val TAG = "StateManagerV2"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // --- DEPENDENCY INJECTION POINT ---
    // By default, use the real handler. Tests can overwrite this with a Mock.
    var effectHandler: EffectHandler = DefaultEffectHandler()

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

    private val inputChannel = Channel<StateContext>(Channel.UNLIMITED)

    private val _state = MutableStateFlow<AppStateV2>(AppStateV2.Initializing)
    val state = _state.asStateFlow()

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    fun initialize() {
        Log.i(TAG, "Initializing V2 State Machine...")
        restoreState()
        startProcessor()
        startHeartbeat()
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun startHeartbeat() {
        scope.launch {
            while (true) {
                if (isActiveDash(_state.value)) {
                    // We delegate this to the handler now
                    effectHandler.handle(AppEffect.SendKeepAlive, scope)
                }
                delay(5 * 60 * 1000L)
            }
        }
    }

    private fun isActiveDash(state: AppStateV2): Boolean {
        return state !is AppStateV2.IdleOffline &&
                state !is AppStateV2.Initializing &&
                state !is AppStateV2.PostDash
    }

    fun dispatch(context: StateContext) {
        if (context.screenInfo != null || context.notification != null) {
            inputChannel.trySend(context)
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun startProcessor() {
        scope.launch {
            for (context in inputChannel) {
                val currentState = _state.value

                // 1. Reduce
                val transition = Reducer.reduce(currentState, context)

                // 2. Update State
                if (transition.newState != currentState) {
                    val oldClass = currentState::class.simpleName
                    val newClass = transition.newState::class.simpleName

                    if (oldClass != newClass) {
                        Log.i(TAG, ">>> TRANSITION: $oldClass -> $newClass")
                    } else {
                        Log.v(TAG, "    Update within $newClass: ${transition.newState}")
                    }

                    _state.value = transition.newState
                    saveState(transition.newState)
                }

                // 3. Execute Effects (DELEGATED)
                transition.effects.forEach { effect ->
                    effectHandler.handle(effect, scope)
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