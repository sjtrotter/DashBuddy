status: active
issues: #456, #3

- **"Saved: $X" bubble shows the dollar sign now (#456).**
  The post-delivery earnings bubble rendered `Saved: 5.50` (no `$`) because the state layer had
  its own money formatter that omitted it. Both local formatters are gone — money now formats
  through one `:domain` `Formats.money` SSOT. On a dash, after a delivery: working = the "Saved"
  bubble reads `Saved: $5.50` (with the `$`); the dash-summary "Session Ended. Total: $X" and
  the offer notification's `$/hr`/`$/mi` should all still read correctly. Broken = a missing or
  doubled `$`, or a wrong decimal.
  - Confirmed: 1/2. **2026-06-14 (DoorDash):** the "Saved" bubble shows `$X.XX` (with the `$`, 2 decimals)
    on **all** of them now — confirmed. (The separate "tip added" bubble is still a raw float — 2026-06-13
    #3.) One more to validate.
  Triage source: first-line blame 2026-06-13 (`c057ba1fa`, original README L2638). Recorded first confirmation: 2026-06-14 (existing evidence only).
