package cloud.trotter.dashbuddy.ui.settings.market

import android.os.Bundle
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.ui.settings.BaseSettingsFragment

class MarketSettingsFragment : BaseSettingsFragment() {

    override fun getTitle() = "Market Strategy"

    override fun getDescription() =
        "Define your 'Unicorn'. These settings determine how aggressive the evaluator is. " +
                "Higher baselines mean fewer accepted offers, but higher quality."

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.market_preferences, rootKey)
    }
}