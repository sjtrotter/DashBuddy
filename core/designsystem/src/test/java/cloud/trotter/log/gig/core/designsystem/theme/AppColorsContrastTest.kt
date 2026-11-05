package cloud.trotter.log.gig.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/** #1444: every text colour meets WCAG 4.5:1 on the surfaces it sits on, in both themes. */
class AppColorsContrastTest {

    private val themes = listOf("dark" to darkAppColors(), "light" to lightAppColors())

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun assertReadable(theme: String, pair: String, fg: Color, bg: Color) {
        val ratio = contrast(fg, bg)
        assertTrue("$theme: $pair is %.2f:1, needs 4.5:1".format(ratio), ratio >= MIN_TEXT_CONTRAST)
    }

    @Test fun textColoursOnEverySurface() = themes.forEach { (theme, c) ->
        val inks = listOf(
            "text" to c.text, "text2" to c.text2, "neutral" to c.neutral, "accent" to c.accent,
            "good" to c.good, "warn" to c.warn, "bad" to c.bad, "stOffer" to c.stOffer, "stPickup" to c.stPickup,
        )
        val surfaces = listOf("bg" to c.bg, "surface" to c.surface, "surface2" to c.surface2)
        inks.forEach { (ink, fg) ->
            surfaces.forEach { (surface, bg) -> assertReadable(theme, "$ink on $surface", fg, bg) }
        }
    }

    @Test fun textOnSurface3() = themes.forEach { (theme, c) ->
        assertReadable(theme, "text on surface3", c.text, c.surface3)
        assertReadable(theme, "text2 on surface3", c.text2, c.surface3)
    }

    @Test fun chipTextOnItsOwnTint() = themes.forEach { (theme, c) ->
        listOf(
            Triple("good", c.good, c.goodBg), Triple("warn", c.warn, c.warnBg), Triple("bad", c.bad, c.badBg),
            Triple("neutral", c.neutral, c.neutralBg), Triple("stOffer", c.stOffer, c.stOfferBg),
            Triple("stPickup", c.stPickup, c.stPickupBg),
        ).forEach { (ink, fg, tint) ->
            listOf("bg" to c.bg, "surface" to c.surface, "surface2" to c.surface2).forEach { (surface, under) ->
                assertReadable(theme, "$ink on ${ink}Bg over $surface", fg, tint.compositeOver(under))
            }
        }
    }

    @Test fun materialOnColours() = themes.forEach { (theme, c) ->
        assertReadable(theme, "accentText on accent", c.accentText, c.accent)
        assertReadable(theme, "accent on accentDim", c.accent, c.accentDim)
        assertReadable(theme, "textInv on stOffer", c.textInv, c.stOffer)
        assertReadable(theme, "textInv on stPickup", c.textInv, c.stPickup)
    }

    private companion object {
        const val MIN_TEXT_CONTRAST = 4.5
    }
}
