package cloud.trotter.dashbuddy.ui.main.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.settings.PlatformPreferencesRepository
import cloud.trotter.dashbuddy.domain.state.Platform
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlatformUiState(
    val platform: Platform,
    val displayName: String,
    val packageName: String,
    val isEnabled: Boolean,
    val isInstalled: Boolean,
)

@HiltViewModel
class PlatformSettingsViewModel @Inject constructor(
    private val repository: PlatformPreferencesRepository,
    @ApplicationContext context: Context,
) : ViewModel() {

    private val packageManager: PackageManager = context.packageManager

    private val monitorablePlatforms = Platform.entries.filter { it.packageName != null }

    val platforms: StateFlow<List<PlatformUiState>> = repository.enabledPlatforms
        .map { enabled ->
            monitorablePlatforms.map { platform ->
                PlatformUiState(
                    platform = platform,
                    displayName = platform.displayName(),
                    packageName = platform.packageName!!,
                    isEnabled = platform in enabled,
                    isInstalled = isInstalled(platform),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggle(platform: Platform, enabled: Boolean) {
        viewModelScope.launch {
            repository.setEnabled(platform, enabled)
        }
    }

    private fun isInstalled(platform: Platform): Boolean = try {
        packageManager.getPackageInfo(platform.packageName!!, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun Platform.displayName(): String = when (this) {
        Platform.DoorDash -> "DoorDash"
        Platform.Uber -> "Uber Driver"
        Platform.Instacart -> "Instacart Shopper"
        Platform.WalmartSpark -> "Walmart Spark"
        Platform.Unknown -> "Unknown"
    }
}
