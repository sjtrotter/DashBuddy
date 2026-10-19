package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.feature.bubble.hud.odometer.OdometerNotice
import cloud.trotter.dashbuddy.feature.bubble.hud.odometer.OdometerNoticeModel
import java.util.Locale
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OdometerNoticeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var originalLocale: Locale
    @Before fun setup() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }
    @After fun cleanup() { Locale.setDefault(originalLocale) }

    @Test fun `save notice renders the reading without Undo`() {
        compose.setContent {
            DashBuddyTheme { OdometerNotice(OdometerNoticeModel(52_388, 100)) }
        }
        compose.onNodeWithText("Odometer saved, 52,388 mi").assertIsDisplayed()
        compose.onNodeWithText("Undo").assertDoesNotExist()
    }
}
