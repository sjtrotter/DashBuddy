package cloud.trotter.log.gig.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import cloud.trotter.log.gig.core.designsystem.motion.LocalReducedMotion
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File

/**
 * strength.log's procedure for the `*ScreenshotsTest` classes (#1463): a frozen frame clock, two seconds of frames,
 * then the assertion and the capture to `<path>-<theme>.png`. A sheet or dialog ([window]) runs a second round, so
 * the capture never lands mid-slide, and is captured as the whole screen because it is its own window.
 */
@OptIn(ExperimentalRoborazziApi::class)
internal fun ComposeContentTestRule.renderShot(
    theme: String,
    path: String,
    window: Boolean,
    assertion: () -> Unit,
    content: @Composable () -> Unit,
) {
    mainClock.autoAdvance = false
    setContent {
        GigLogTheme(darkTheme = theme == "dark") {
            CompositionLocalProvider(LocalNowProvider provides ScreenshotData.clock,
                LocalReducedMotion provides true, content = content)
        }
    }
    mainClock.advanceTimeBy(2_000)
    waitForIdle()
    if (window) {
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
    }
    assertion()
    if (System.getProperty("roborazzi.test.record") == "true") {
        val file = File(System.getProperty("giglog.screenshots.dir"), "$path-$theme.png")
        file.parentFile?.mkdirs()
        if (window) captureScreenRoboImage(file) else onRoot().captureRoboImage(file)
    }
}
