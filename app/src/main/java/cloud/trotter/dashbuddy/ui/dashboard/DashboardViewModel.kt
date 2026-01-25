package cloud.trotter.dashbuddy.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    application: Application,
    private val repository: SettingsRepository
) : AndroidViewModel(application) {

    // --- APP STATUS ---

    val isFirstRun: StateFlow<Boolean> = repository.isFirstRun
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // --- ACTIONS ---

    fun completeSetup() = viewModelScope.launch {
        repository.setFirstRunComplete()
    }
}