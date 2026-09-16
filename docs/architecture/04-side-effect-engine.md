> **Detailed architecture reference — moved out of `CLAUDE.md` on 2026-09-07 (context-budget trim v3).**
> This file is the full, receipt-level narrative for this layer: every rule, its issue/PR receipt, and the
> reasoning that produced it. `CLAUDE.md` keeps the short summary an agent needs every session; this file is
> where a change's history and invariants are recorded in depth. **The 'every PR ships with context updates'
> rule applies here exactly as it does to `CLAUDE.md`:** when a PR changes an invariant described below, update
> this file in the same PR (and the `CLAUDE.md` summary if it goes stale).
> A `§N` reference below means the sibling numbered file here (or its `CLAUDE.md` summary).

# Side Effect Engine (`app/.../state/effects/`)

`SideEffectEngine` executes `AppEffect`s with `effects_fired` idempotency dedup (recovery-aware),
runs the evaluation loopback (offer eval → `OfferEvaluationEvent` back into the machine), and owns
the fail-closed action gates (#417): live `PermissionTierChecker` + the capability consent gate on
automation-triggered `RuleAction`s (grant store: `RuleCapabilityRepository` over the
`rule_capability_grants` DataStore; **no auto-grant (#843)** — `reconcile` only publishes the
enumeration, every capability lands *undecided* until the user opts in via the consent prompt).
Handlers: `OdometerEffectHandler`, `ScreenShotHandler`, `TipEffectHandler`, `TtsEffectHandler`,
`UiInteractionHandler` (package-scoped, label-verified `RuleAction` taps — the only path that ever
clicks a third-party app, #425), `OfferActionReceiver` (notification Accept/Decline actions).
