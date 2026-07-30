package cloud.trotter.dashbuddy.core.designsystem.text

/**
 * The placeholder shown for a stat with no measurable value yet — the em-dash.
 *
 * ONE owner (#942, principle 5) for every surface that renders a nullable number: the analytics-hub
 * tabs (Money / Decisions / Time / Patterns / SessionDetail, `:app`) AND the home glance's
 * home blocks (`SoFarToday` & co., `:feature:dashboard`). The `:app` copy's own KDoc already forbade
 * per-file copies,
 * but a feature module can never depend on `:app`, so the copy was the only way to reach it — the
 * fix is to own it where both can: the design system, which is where display vocabulary belongs.
 *
 * Not a string resource: it is a typographic token, identical in every locale, and several call
 * sites are non-Composable `?:` fallbacks.
 */
const val EMPTY_VALUE = "—"
