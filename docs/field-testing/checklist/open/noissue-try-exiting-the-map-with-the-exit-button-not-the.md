status: review
issues:

- **Try EXITING the map with the Exit button (not the back gesture) — capture the
  click.** The dev currently exits nav with the system **back gesture**, so no
  `exit_button` tap is ever captured (0 in all June). The nav screen *does* have an
  explicit **Exit** button (`id=exit_button`). On a dash, deliberately tap that
  **Exit** button a few times (pickup and dropoff) so we capture the click — it may
  give a cleaner, explicit "left navigation" signal than inferring it from the
  window transition. What to watch/capture: that tapping Exit produces a click
  capture (and note whether back-gesture vs button changes what DoorDash shows next).
  - Confirmed: 0/2.
  desk: needs field: the explicit Exit-button tap has never been captured; a back gesture cannot exercise this target.
  Triage source: first-line blame 2026-06-08 (`d265b8c25`, original README L3023).
