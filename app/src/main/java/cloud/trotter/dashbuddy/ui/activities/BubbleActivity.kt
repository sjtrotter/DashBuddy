package cloud.trotter.dashbuddy.ui.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import cloud.trotter.dashbuddy.R
import com.google.android.material.bottomnavigation.BottomNavigationView
import cloud.trotter.dashbuddy.ui.fragments.OfferListFragment
import cloud.trotter.dashbuddy.ui.fragments.SettingsFragment
import cloud.trotter.dashbuddy.ui.fragments.StatisticsFragment

class BubbleActivity : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavigationView
    private lateinit var fragmentContainer: FragmentContainerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.bubble_layout)

        bottomNav = findViewById(R.id.bubble_bottom_nav)
        fragmentContainer = findViewById(R.id.bubble_fragment_container)

        // Set up the BottomNavigationView
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_statistics -> {
                    loadFragment(StatisticsFragment())
                    true
                }
                R.id.nav_offers -> {
                    loadFragment(OfferListFragment())
                    true
                }
                R.id.nav_settings -> {
                    loadFragment(SettingsFragment())
                    true
                }
                else -> false
            }
        }

        // Set the default fragment
        if (savedInstanceState == null) {
            bottomNav.selectedItemId = R.id.nav_statistics
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.bubble_fragment_container, fragment)
            .commit()
    }
}