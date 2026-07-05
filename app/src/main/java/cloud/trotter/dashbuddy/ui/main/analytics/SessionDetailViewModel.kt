package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.domain.analytics.SessionDetail
import cloud.trotter.dashbuddy.ui.main.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * State holder for the read-only per-dash drill-down (#650 PR A) — one immutable
 * [SessionDetailUiState] assembled reactively from [AnalyticsRepository.sessionDetail]
 * (Principle 1 — UDF, state out; no intents yet, PR A only reads). The sessionId is read from
 * [SavedStateHandle] (the nav argument), so this VM needs no explicit initializer.
 *
 * Reactive by construction: the source is a Room-invalidation `Flow`, so the header + rows re-emit
 * as the projector folds each delivery — a **review** surface, not a per-second tick (no
 * `rememberNow()` clock; same reframe as the hub #657). Read-model only, no economy dependency, no
 * new PII surface (store names are driver-owned; no customer hashes are exposed).
 */
@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    analyticsRepository: AnalyticsRepository,
) : ViewModel() {

    private val sessionId: String =
        savedStateHandle[Screen.SessionDetail.ARG_SESSION_ID] ?: ""

    val uiState: StateFlow<SessionDetailUiState> =
        analyticsRepository.sessionDetail(sessionId)
            .map { SessionDetailUiState(detail = it, loading = false) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SessionDetailUiState(),
            )
}

/**
 * Immutable state for the per-dash drill-down (#650 PR A). [loading] is the pre-first-emission
 * state; once the read-model emits, a `null` [detail] means the session row is absent (dash not
 * found / projector rebuild) and a present one is the full [SessionDetail].
 */
data class SessionDetailUiState(
    val detail: SessionDetail? = null,
    val loading: Boolean = true,
)
