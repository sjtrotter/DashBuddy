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
    val devModeEnabled = repository.devSnapshotsEnabled

    // Internal click counter for the "Secret" unlock
    private val _versionClickCount = MutableStateFlow(0)
    val versionClickCount = _versionClickCount.asStateFlow()

    fun onVersionClicked() {
        val current = _versionClickCount.value
        if (current < 7) {
            _versionClickCount.value = current + 1
        }

        // Unlock at 7 clicks
        if (_versionClickCount.value == 7 && !devModeEnabled.value) {
            viewModelScope.launch {
                // We toggle the repo's master dev switch to "True" to persist the unlock
                // (Assuming your toggleSnapshotScreen logic handles the boolean flip,
                // or we add a specific method to repo for 'setDevMode(true)')
                // For now, let's assume we just treat the 'devSnapshotsEnabled' as the gate
                // If you don't have a specific setter for the boolean flow, you might need to add one to Repo.
                // For this example, I'll assume we simply track it locally or add a Repo method:
                // repository.setDevModeEnabled(true) -> You might need to add this to Repo
            }
        }
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