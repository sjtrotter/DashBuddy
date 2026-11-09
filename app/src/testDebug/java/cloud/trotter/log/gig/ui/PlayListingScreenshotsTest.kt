package cloud.trotter.log.gig.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.domain.offer.story.OfferFixtures
import cloud.trotter.log.gig.domain.offer.story.OfferStories
import cloud.trotter.log.gig.feature.bubble.hud.OfferCard
import cloud.trotter.log.gig.feature.bubble.hud.OfferCardMapper
import cloud.trotter.log.gig.ui.main.analytics.ShiftPageActions
import cloud.trotter.log.gig.ui.main.analytics.ShiftPageContent
import cloud.trotter.log.gig.ui.main.analytics.ShiftPageFixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import java.util.TimeZone
import cloud.trotter.log.gig.feature.dashboard.R as DashboardR

/**
 * #1416 — the Play listing's pictures from the app, rendered into `play-listing/<name>-<theme>.png` with the PR CI
 * screenshots (`tools/play/stage_listing_images.sh` stages them). Runs only with `-PscreenShots=true`.
 *
 * Supported-platform labels (the platform name gig.log shows on a shift) are allowed; merchant, brand and vehicle
 * names are not, and none may read "debug".
 *
 * The phone screenshots are the real tab bodies in the app shell's frame and the real shift page over
 * [ScreenshotData] and [ShiftPageFixtures.sampleStackAndAddOn] (made-up store names only), at 412 x 732 dp and
 * 420 dpi (about 1081 x 1921 px, Play's 1080 x 1920 recommendation). The icon is the launcher icon's two layers drawn full-bleed into 512 x 512;
 * the feature graphic is 1024 x 500 in the dark brand theme. Those two are the same picture in both themes.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h732dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayListingScreenshotsTest(private val theme: String) {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var locale: Locale
    private lateinit var timeZone: TimeZone

    @Before fun fixLocaleAndTimeZone() {
        locale = Locale.getDefault()
        timeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After fun restoreLocaleAndTimeZone() {
        Locale.setDefault(locale)
        TimeZone.setDefault(timeZone)
    }

    // ── Phone screenshots ────────────────────────────────────────────────

    @Test fun home() = shot("home", {
        text(string(R.string.dashboard_screen_show_bubble_button)).assertExists()
        text(string(DashboardR.string.dashboard_weekly_plan_pointer_action)).assertExists()
    }) { HomeBody(ScreenshotData.dashboard(), hasPermissions = true) }

    @Test fun recapWeek() = shot("recap-week", {
        text(string(R.string.analytics_screen_title)).assertExists()
        text(Formats.money(ScreenshotData.week.grossEarnings)).assertExists()
    }) { RecapBody(ScreenshotData.recap()) }

    /** A stack, an add-on job and runs of passed offers, every store renamed to a made-up one. */
    @Test fun shiftPage() = shot("shift-page", {
        text(ShiftPageFixtures.SAMPLE_STORES.getValue("Five Guys & Chipotle")).assertExists()
    }) { ShiftPageContent(ShiftPageFixtures.sampleStackAndAddOn(), null, ShiftPageActions(onBack = {})) }

    @Test fun playbook() = shot("playbook", {
        text(string(R.string.playbook_screen_title)).assertExists()
        text("Sample Grocery").assertExists()
    }) { PlaybookBody(ScreenshotData.playbook(showMore = false)) }

    // ── Icon and feature graphic ─────────────────────────────────────────

    /** The adaptive icon's background, then its foreground, each over the whole 108 dp canvas scaled to 512 px. */
    @Test fun icon() {
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        layer(R.drawable.ic_launcher_background).draw(canvas)
        val background = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        layer(R.drawable.ic_launcher_foreground).draw(canvas)
        assertEquals("the background layer fills the corner", 255, Color.alpha(background.getPixel(0, 0)))
        assertFalse("the foreground layer drew nothing", bitmap.sameAs(background))
        assertTrue("the icon is a single colour", colours(bitmap) > 1)
        if (recording()) {
            val file = File(System.getProperty("giglog.screenshots.dir"), "play-listing/icon-$theme.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }

    /** The wordmark and tagline beside the real offer card; the text stays inside a 50 dp margin. */
    @Test @Config(qualifiers = "w1024dp-h500dp-mdpi")
    fun featureGraphic() {
        val tagline = string(R.string.play_feature_graphic_tagline)
        assertEquals(WORDMARK, string(R.string.play_wordmark))
        shot("featureGraphic", {
            listOf(WORDMARK, tagline).forEach { label ->
                val bounds = compose.onNodeWithText(label).assertIsDisplayed().getBoundsInRoot()
                assertTrue("'$label' is outside the margin: $bounds", bounds.left >= MARGIN && bounds.top >= MARGIN &&
                    bounds.right <= GRAPHIC_WIDTH - MARGIN && bounds.bottom <= GRAPHIC_HEIGHT - MARGIN)
            }
            compose.onNodeWithTag("offer.accept").assertIsDisplayed()
        }) { FeatureGraphic(tagline) }
    }

    @Composable
    private fun FeatureGraphic(tagline: String) = GigLogTheme(darkTheme = true) {
        val c = AppTheme.colors
        val now = ScreenshotData.clock.millis()
        val o = OfferFixtures.offer().copy(orders = listOf(OfferFixtures.order("Sample Grocery")))
        val pending = OfferFixtures.pending(o).copy(presentedAt = now - 3_000, countdownExpiresAt = now + 27_000)
        val card = OfferCardMapper.map(OfferStories.build(pending, null, OfferFixtures.config, pending.platform), pending, null)
        Row(Modifier.fillMaxSize().background(c.bg).padding(horizontal = MARGIN),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                AppIcon(72.dp)
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.play_wordmark), color = c.text, style = MaterialTheme.typography.displayLarge)
                Spacer(Modifier.height(12.dp))
                Text(tagline, color = c.text2, style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.width(40.dp))
            Box(Modifier.size(width = 360.dp, height = 440.dp)) { OfferCard(card, {}, {}) }
        }
    }

    /** The launcher icon's two layers, as the launcher draws them under a rounded mask. */
    @Composable
    private fun AppIcon(size: Dp) {
        Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.22f))) {
            Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
        }
    }

    // ── Builders ─────────────────────────────────────────────────────────

    private fun layer(@DrawableRes id: Int): Drawable =
        requireNotNull(context.getDrawable(id)).apply { setBounds(0, 0, ICON_PX, ICON_PX) }

    /** Distinct colours on an 8 px grid. */
    private fun colours(bitmap: Bitmap): Int =
        (0 until ICON_PX step 8).flatMap { x -> (0 until ICON_PX step 8).map { y -> bitmap.getPixel(x, y) } }.toSet().size

    private fun recording() = System.getProperty("roborazzi.test.record") == "true"

    /**
     * A listing picture: the shot's own [assertion]; no store or car name that is not made up; and no "debug" (these
     * tests run the debug build, whose app name is "gig.log debug") in any text or description.
     */
    private fun shot(name: String, assertion: () -> Unit, content: @Composable () -> Unit) =
        compose.renderShot(theme, "play-listing/$name", window = false, assertion = {
            NOT_SAMPLE_NAMES.forEach { compose.onAllNodesWithText(it, substring = true, ignoreCase = true).assertCountEquals(0) }
            compose.onAllNodesWithText("debug", substring = true, ignoreCase = true).assertCountEquals(0)
            compose.onAllNodes(hasContentDescription("debug", substring = true, ignoreCase = true)).assertCountEquals(0)
            assertion()
        }, content = content)

    private fun string(@StringRes id: Int): String = context.getString(id)

    /** The first node showing [text], case-insensitively: a label can be upper-cased or appear twice. */
    private fun text(text: String) = compose.onAllNodesWithText(text, substring = true, ignoreCase = true)[0]

    companion object {
        private const val ICON_PX = 512
        private const val WORDMARK = "gig.log"
        private val MARGIN = 50.dp
        private val GRAPHIC_WIDTH = 1024.dp
        private val GRAPHIC_HEIGHT = 500.dp

        /** The real merchant names the shift-page fixture carries, and the screenshot data's car. */
        private val NOT_SAMPLE_NAMES = ShiftPageFixtures.SAMPLE_STORES.keys + listOf("Honda", "Civic")

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes(): List<Array<Any>> = listOf(arrayOf<Any>("light"), arrayOf<Any>("dark"))
    }
}
