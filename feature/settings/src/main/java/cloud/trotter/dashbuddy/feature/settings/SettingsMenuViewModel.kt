package cloud.trotter.dashbuddy.feature.settings

import cloud.trotter.dashbuddy.core.data.census.CensusCredentialStore
import cloud.trotter.dashbuddy.core.data.census.CensusIdentityResetter
import cloud.trotter.dashbuddy.core.data.census.CensusSpool
import cloud.trotter.dashbuddy.domain.census.CensusUploadScheduler
import kotlinx.coroutines.flow.map
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.settings.DevSettingsRepository
import cloud.trotter.dashbuddy.core.data.strategy.StrategyRepository
import cloud.trotter.dashbuddy.domain.model.bubble.BubbleSessionMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsMenuViewModel @Inject constructor(
    private val appPreferencesRepository: AppPreferencesRepository,
    private val devSettingsRepository: DevSettingsRepository,
    private val strategyRepository: StrategyRepository,
    credentials: CensusCredentialStore,
    spool: CensusSpool,
    private val censusResetter: CensusIdentityResetter,
    private val censusScheduler: CensusUploadScheduler,
) : ViewModel() {

    val censusAvailable = devSettingsRepository.censusAvailable
    val censusUploadEnabled = devSettingsRepository.censusUploadEnabled
    val censusHost = devSettingsRepository.censusBaseUrl.map { it.removePrefix("https://") }
    val censusInstallIdPrefix = credentials.installIdPrefix
    val censusInstallId = credentials.installId
    val censusLastRun = devSettingsRepository.censusLastRun
    val censusQueued = spool.queued

    fun setCensusUploadEnabled(enabled: Boolean) = viewModelScope.launch {
        devSettingsRepository.setCensusUploadEnabled(enabled)
    }

    fun uploadCensusNow() = censusScheduler.enqueueNow()

    /** #1185 — forget this phone's census identity; the next run enrols a fresh install id. */
    fun resetCensusIdentity() = viewModelScope.launch { censusResetter.reset() }

    // Pass-through flows for the UI
    val evidenceConfig = strategyRepository.evidenceConfig
    val appTheme = appPreferencesRepository.appTheme
    val isProMode = appPreferencesRepository.isProMode
    /** Driving / glance mode (#318) — surfaced on General settings. */
    val glanceMode = appPreferencesRepository.glanceMode
    /** Spoken-offer language override (#428 Half B) — surfaced on General settings; null ⇒ system. */
    val ttsLanguageTag = appPreferencesRepository.ttsLanguageTag
    /** Surfaced on Settings home so the Personal Economy nav row shows live $/mi. */
    val userEconomy = appPreferencesRepository.userEconomy

    // Re-using the debug flag from repo as the "Unlock" state
    val isDevModeUnlocked = devSettingsRepository.isDevModeUnlocked

    /** #867 — the bubble's session-presentation experiment switch (Developer Options). */
    val bubbleSessionMode = devSettingsRepository.bubbleSessionMode

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

    /** #867 — persist the bubble session-presentation mode; the HUD reads it reactively. */
    fun setBubbleSessionMode(mode: BubbleSessionMode) = viewModelScope.launch {
        devSettingsRepository.setBubbleSessionMode(mode)
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

    fun setGlanceMode(enabled: Boolean) = viewModelScope.launch {
        appPreferencesRepository.setGlanceMode(enabled)
    }

    /** #428 Half B — set (or clear, null ⇒ system) the spoken-offer language override. */
    fun setTtsLanguage(tag: String?) = viewModelScope.launch {
        appPreferencesRepository.setTtsLanguageTag(tag)
    }
}
