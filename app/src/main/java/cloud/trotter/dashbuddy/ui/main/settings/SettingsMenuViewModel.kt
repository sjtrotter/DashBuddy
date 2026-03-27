package cloud.trotter.dashbuddy.ui.main.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.data.settings.DevSettingsRepository
import cloud.trotter.dashbuddy.data.settings.StrategyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsMenuViewModel @Inject constructor(
    private val appPreferencesRepository: AppPreferencesRepository,
    private val devSettingsRepository: DevSettingsRepository,
    private val strategyRepository: StrategyRepository
) : ViewModel() {

    // Pass-through flows for the UI
    val evidenceConfig = strategyRepository.evidenceConfig
    val appTheme = appPreferencesRepository.appTheme
    val isProMode = appPreferencesRepository.isProMode

    // Re-using the debug flag from repo as the "Unlock" state
    val isDevModeUnlocked = devSettingsRepository.isDevModeUnlocked

    private val _versionClickCount = MutableStateFlow(0)
    val versionClickCount = _versionClickCount.asStateFlow()

    fun onVersionClicked() {
        val current = _versionClickCount.value
        if (current < 7) {
            _versionClickCount.value = current + 1
        }
    }

    fun unlockDeveloperMode() = viewModelScope.launch {
        devSettingsRepository.setDevModeUnlocked(true)
    }

    // --- Actions ---

    fun setEvidenceMaster(enabled: Boolean) = viewModelScope.launch {
        strategyRepository.setEvidenceMaster(enabled)
    }

    fun updateEvidenceConfig(offers: Boolean, delivery: Boolean, dash: Boolean) =
        viewModelScope.launch {
            strategyRepository.updateEvidenceConfig(offers, delivery, dash)
        }

    fun setProMode(enabled: Boolean) = viewModelScope.launch {
        appPreferencesRepository.setProMode(enabled)
    }

    fun setTheme(theme: String) = viewModelScope.launch {
        appPreferencesRepository.setTheme(theme)
    }
}