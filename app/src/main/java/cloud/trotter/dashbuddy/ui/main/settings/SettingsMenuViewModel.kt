package cloud.trotter.dashbuddy.ui.main.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsMenuViewModel @Inject constructor(
    private val repository: SettingsRepository
) : ViewModel() {

    // Pass-through flows for the UI
    val evidenceConfig = repository.evidenceConfig
    val appTheme = repository.appTheme
    val isProMode = repository.isProMode

    // Re-using the debug flag from repo as the "Unlock" state
    val isDevModeUnlocked = repository.isDevModeUnlocked

    private val _versionClickCount = MutableStateFlow(0)
    val versionClickCount = _versionClickCount.asStateFlow()

    fun onVersionClicked() {
        val current = _versionClickCount.value
        if (current < 7) {
            _versionClickCount.value = current + 1
        }
    }

    fun unlockDeveloperMode() = viewModelScope.launch {
        repository.setDevModeUnlocked(true)
    }

    // --- Actions ---

    fun setEvidenceMaster(enabled: Boolean) = viewModelScope.launch {
        repository.setEvidenceMaster(enabled)
    }

    fun updateEvidenceConfig(offers: Boolean, delivery: Boolean, dash: Boolean) =
        viewModelScope.launch {
            repository.updateEvidenceConfig(offers, delivery, dash)
        }

    fun setProMode(enabled: Boolean) = viewModelScope.launch {
        repository.setProMode(enabled)
    }

    fun setTheme(theme: String) = viewModelScope.launch {
        repository.setTheme(theme)
    }
}