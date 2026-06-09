package cloud.trotter.dashbuddy.core.designsystem.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import cloud.trotter.dashbuddy.core.designsystem.R

/**
 * Brand typefaces, bundled as OFL variable fonts (offline — no downloadable provider).
 *
 * Both are variable fonts with a `wght` axis; on API 26+ the per-weight [Font] entries
 * apply the weight to the variation axis automatically via the default `variationSettings`.
 */

/** Hanken Grotesk — UI text (wght 400–800). */
val HankenGrotesk = FontFamily(
    Font(R.font.hanken_grotesk, FontWeight.Normal),
    Font(R.font.hanken_grotesk, FontWeight.Medium),
    Font(R.font.hanken_grotesk, FontWeight.SemiBold),
    Font(R.font.hanken_grotesk, FontWeight.Bold),
    Font(R.font.hanken_grotesk, FontWeight.ExtraBold),
)

/** Space Grotesk — numerals, hero metrics, timers (wght 400–700). */
val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Normal),
    Font(R.font.space_grotesk, FontWeight.Medium),
    Font(R.font.space_grotesk, FontWeight.SemiBold),
    Font(R.font.space_grotesk, FontWeight.Bold),
)
