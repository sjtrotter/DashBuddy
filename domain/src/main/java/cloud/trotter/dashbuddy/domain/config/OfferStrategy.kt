package cloud.trotter.dashbuddy.domain.config

/**
 * Defines the core behavior of the DashBuddy automation pipeline.
 *
 * Platform-agnostic vocabulary (#762 D12): [PROTECT_STATUS] is the generic
 * "keep my acceptance-rate-based standing high" goal — not any one platform's
 * program name (DoorDash "Platinum", Uber "Pro", etc.). This enum is a
 * wizard-UI selection only; it is never persisted — the wizard maps it to the
 * Boolean `protectStatsMode` strategy pref on Finish and rebuilds it from that
 * pref on load, so renaming a constant carries no migration hazard.
 */
enum class OfferStrategy { CHERRY_PICKER, PROTECT_STATUS, MANUAL }