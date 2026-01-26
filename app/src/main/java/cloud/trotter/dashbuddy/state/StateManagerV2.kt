package cloud.trotter.dashbuddy.state

import android.os.Build
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.state.effects.EffectHandler
import cloud.trotter.dashbuddy.state.event.StateEvent
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import cloud.trotter.dashbuddy.log.Logger as Log

@Singleton
class StateManagerV2 @Inject constructor(
    private val effectHandler: EffectHandler
) {

    private val tag = "StateManagerV2"
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
                .registerSubtype(AppStateV2.PostDelivery::class.java)
                .registerSubtype(AppStateV2.DashPaused::class.java)
                .registerSubtype(AppStateV2.PausedOrInterrupted::class.java)
        )
        .create()

    private val inputChannel = Channel<StateEvent>(Channel.UNLIMITED)

    private val _state = MutableStateFlow<AppStateV2>(AppStateV2.Initializing)
    val state = _state.asStateFlow()

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    fun initialize() {
        Log.i(tag, "Initializing V2 State Machine...")
        restoreState()
        startProcessor()
    }

    private fun isActiveDash(state: AppStateV2): Boolean {
        return state !is AppStateV2.IdleOffline &&
                state !is AppStateV2.Initializing &&
                state !is AppStateV2.PostDash
    }

    fun dispatch(stateEvent: StateEvent) {
        inputChannel.trySend(stateEvent)
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun startProcessor() {
        scope.launch {
            for (stateEvent in inputChannel) {
                val currentState = _state.value

                // 1. Reduce
                val transition = Reducer.reduce(currentState, stateEvent)

                // 2. Update State
                if (transition.newState != currentState) {
                    val oldClass = currentState::class.simpleName
                    val newClass = transition.newState::class.simpleName

                    if (oldClass != newClass) {
                        Log.i(tag, ">>> TRANSITION: $oldClass -> $newClass")
                    } else {
                        Log.v(tag, "    Update within $newClass: ${transition.newState}")
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
            Log.e(tag, "Failed to save state", e)
        }
    }

    private fun restoreState() {
        val json = DashBuddyApplication.getCrashRecoveryState()
        if (json != null) {
            try {
                Log.i(tag, "Restoring state from storage...")
                _state.value = gson.fromJson(json, AppStateV2::class.java)
            } catch (e: Exception) {
                Log.w(tag, "Failed to restore state. Starting fresh.", e)
                _state.value = AppStateV2.Initializing
            }
        } else {
            _state.value = AppStateV2.Initializing
        }
    }
}