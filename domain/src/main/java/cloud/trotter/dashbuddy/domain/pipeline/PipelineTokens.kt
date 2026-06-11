package cloud.trotter.dashbuddy.domain.pipeline

/**
 * Named wire tokens shared between producer and consumer (#402). Each of
 * these used to exist only as matching string literals in separate files —
 * one mistyped copy and the contract silently breaks.
 */

/** Classification target for unrecognized screens/clicks/notifications. */
const val UNKNOWN_TARGET = "UNKNOWN"

/** Fallback shown for a node with no view id (produced by UiNode's printer,
 *  filtered by the classifier's click-target derivation). */
const val NO_ID_FALLBACK = "no_id"
