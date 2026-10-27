status: active
section: §2 Active / Counter unspecified — retained active, newest first
issues: #548, #572
confirmed: unspecified

- [active] **🔧 FIX SHIPPED — a pickup no longer borrows a customer identity (#548, PR #572). CONFIRM ON DASH.**
  On a **multi-store stack**, a restaurant pickup screen could bleed the *other* drop's customer onto
  the pickup task (latent — no visible bubble effect, but a data-model defect). The pickup screen no
  longer parses a customer at all. **Confirm on dash: 0/2 —** on a stacked/multi-store order, the
  **pickup** bubble should read the **store/merchant** (never a 6-character customer fragment), and no
  customer should bleed onto the first dropoff. Hard to see directly; mainly verified in the db/log —
  but flag anything where a pickup card shows a customer-looking label.
  Triage source: first-line blame 2026-06-22 (`457e68d9a`, original README L2325).
