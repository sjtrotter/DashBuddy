package cloud.trotter.dashbuddy.state

import android.os.Build
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.data.state.StateRecoveryRepository
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
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StateManagerV2 @Inject constructor(
    private val effectHandler: EffectHandler,
    private val stateRecoveryRepository: StateRecoveryRepository,
    private val reducer: Reducer,
) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // GSON configuration setup
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
        Timber.i("Initializing V2 State Machine...")
        restoreState()
        startProcessor()
    }

    /**
     * Entry point for all events in the system.
     * This function is passed to the EffectHandler to allow feedback loops.
     */
    fun dispatch(stateEvent: StateEvent) {
        inputChannel.trySend(stateEvent)
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun startProcessor() {
        scope.launch {
            for (stateEvent in inputChannel) {
                val currentState = _state.value

                // 1. Reduce
                val transition = reducer.reduce(currentState, stateEvent)

                // 2. Update State
                if (transition.newState != currentState) {
                    val oldClass = currentState::class.simpleName
                    val newClass = transition.newState::class.simpleName

                    if (oldClass != newClass) {
                        Timber.i(">>> TRANSITION: $oldClass -> $newClass")
                    } else {
                        Timber.v("    Update within $newClass: ${transition.newState}")
                    }

                    _state.value = transition.newState
                    saveState(transition.newState)
                }

                // 3. Execute Effects
                // We pass `::dispatch` so the handler can feed events (like Offer Evaluated) back to us
                transition.effects.forEach { effect ->
                    effectHandler.handle(effect, scope, ::dispatch)
                }
            }
        }
    }

    // --- Persistence (Crash Recovery) ---

    private fun saveState(state: AppStateV2) {
        // Run IO on background thread
        scope.launch(Dispatchers.IO) {
            try {
                val json = gson.toJson(state)
                stateRecoveryRepository.saveState(json)
            } catch (e: Exception) {
                Timber.e(e, "Failed to save state")
            }
        }
    }

    private fun restoreState() {
        scope.launch(Dispatchers.IO) {
            val json = stateRecoveryRepository.getFreshState()

            if (json != null) {
                try {
                    Timber.i("Restoring state from storage...")
                    val restored = gson.fromJson(json, AppStateV2::class.java)
                    _state.value = restored
                } catch (e: Exception) {
                    Timber.w(e, "Failed to restore state. Starting fresh.")
                    _state.value = AppStateV2.Initializing
                }
            } else {
                Timber.i("No valid previous state found. Starting fresh.")
                _state.value = AppStateV2.Initializing
            }
        }
    }
}