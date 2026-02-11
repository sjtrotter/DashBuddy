package cloud.trotter.dashbuddy.state

import android.os.Build
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.data.state.StateRecoveryRepository
import cloud.trotter.dashbuddy.pipeline.PipelineV2
import cloud.trotter.dashbuddy.state.effects.SideEffectEngine
import cloud.trotter.dashbuddy.state.event.StateEvent
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StateManagerV2 @Inject constructor(
    private val pipeline: PipelineV2,
    private val engine: SideEffectEngine, // <--- Renamed & Updated
    private val stateRecoveryRepository: StateRecoveryRepository,
    private val reducer: Reducer,
) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // GSON setup...
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

    // 3. UI INPUT STREAM (Clicks, Debug Buttons)
    private val uiInputChannel = Channel<StateEvent>(Channel.UNLIMITED)

    private val _state = MutableStateFlow<AppStateV2>(AppStateV2.Initializing)
    val state = _state.asStateFlow()

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    fun initialize() {
        Timber.i("Initializing V2 State Machine...")
        restoreState()
        startProcessor()
    }

    fun dispatch(stateEvent: StateEvent) {
        uiInputChannel.trySend(stateEvent)
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun startProcessor() {
        scope.launch {
            Timber.d("🔌 Connecting All Event Streams...")

            // --- THE TRIFECTA MERGE ---
            // 1. Pipeline (System Events: Screen, Notifications)
            // 2. Engine (Logic Events: Timeouts, Calculations)
            // 3. UI (User Events: Manual Clicks)
            merge(
                pipeline.events,
                engine.events,
                uiInputChannel.receiveAsFlow()
            )
                .collect { stateEvent ->
                    // The Single Source of Truth
                    Timber.i("📥 PROCESSING: ${stateEvent::class.simpleName}")
                    processEvent(stateEvent)
                }
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun processEvent(stateEvent: StateEvent) {
        val currentState = _state.value

        // 1. REDUCE
        val transition = reducer.reduce(currentState, stateEvent)

        // 2. UPDATE STATE
        if (transition.newState != currentState) {
            val oldClass = currentState::class.simpleName
            val newClass = transition.newState::class.simpleName
            if (oldClass != newClass) {
                Timber.i(">>> TRANSITION: $oldClass -> $newClass")
            }
            _state.value = transition.newState
            saveState(transition.newState)
        }

        // 3. EMIT EFFECTS
        // We push effects into the Engine. It decides if/when to loop back.
        transition.effects.forEach { effect ->
            engine.process(effect, scope)
        }
    }

    // --- Persistence code (Same as before) ---
    private fun saveState(state: AppStateV2) {
        scope.launch(Dispatchers.IO) {
            try {
                stateRecoveryRepository.saveState(gson.toJson(state))
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
                    _state.value = gson.fromJson(json, AppStateV2::class.java)
                } catch (_: Exception) {
                    _state.value = AppStateV2.Initializing
                }
            }
        }
    }
}