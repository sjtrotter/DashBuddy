status: active
issues: #598, #346, #623, #624, #620

- **🔒 FIX SHIPPED — recognized capture envelopes now redact customer PII at the device edge (#598, incl. audit F1/F2 coverage extension).**
  Pre-#598 the recognized-screen captures on disk carried raw `Deliver to ‹name›`, full street
  addresses, and gate codes — PII was hashed only in the parse output, raw on disk. Now rules
  DECLARE a `redact` block and the capture stage masks those node texts in the serialized envelope
  only (recognition/parse/state unchanged); a screen rule that hashes PII (`sha256`) fails compile
  without a `redact` block. The audit F1/F2 pass extended coverage from the first 7 rules to **all**
  recognized customer-PII surfaces: `pickup_pre_arrival` (customer name + store address),
  `pickup_verify_items` (`Verify items for ‹name›`), `dropoff_photo` (free-form instruction / gate
  codes / apt — all id-less text), `chat_conversation` (customer name + every chat message body),
  `camera_capture` (`…name: ‹name›` / `…Apt/Suite: ‹apt›` + a stale bin-scan bare name),
  `nav_arriving` (arrival street address), `dropoff_geofence_warning` (address/apt lines),
  `waiting_for_offer` (a stale prior-delivery customer-name bleed), plus a bare-unit-number entry on
  the id-less dropoff cards (`dropoff_pre_arrival` / `_completion` / `dropoff_handoff`) — on top of
  the original `dropoff_handoff/navigation/pre_arrival`, `pickup_arrival`, `pickup_resolution_options`.
  **Confirm on next pull: 0/2 —** pull the on-device captures after a dash with real deliveries and
  grep the recognized envelope JSON for the surfaces above: every customer name reads `[redacted]`
  (or `Deliver to [redacted]` / `Verify items for [redacted]` where a marker is kept), addresses,
  apt/unit numbers, gate codes, and chat bodies read `[redacted]`, and NO raw recipient name / street
  / apt / gate-code / chat text survives. Markers (`Deliver to `, `Order for`, `Delivery for`,
  `Hand it to customer`, `Your Customer`, `Arriving at`, step titles) must remain so recognition is
  unchanged — verify the same screens still classify + drive state exactly as before. Broken = any
  raw customer name/address/apt/gate-code/chat text in a recognized envelope, OR a screen that
  stopped recognizing. **Documented exceptions (NOT defects):** UNKNOWN frames + UNKNOWN clicks are
  debug-only (release `NoOpCaptureBus` #346 + the `SensitiveTextMarkers` backstop, not name
  redaction); the id-less building-NAME line on dropoff cards and the merchant-name-as-product rows
  are structural residuals (#623/#624); the notification-capture customer-name path is #620; a nav
  `roadNameView` road-name residual is accepted (F7).
  Triage source: first-line blame 2026-07-03 (`98c718b53`, original README L2189).
