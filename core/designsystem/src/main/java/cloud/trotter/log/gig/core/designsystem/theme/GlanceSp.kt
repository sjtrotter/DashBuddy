package cloud.trotter.log.gig.core.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** Glance-scaled one-off text sizes; system font scale still applies through sp. */
@Composable
fun glanceSp(sp: Int): TextUnit = sp.sp * LocalGlance.current
