status: active
issues: #1160, #1145

- **🆕 NEW — chat header and chat preview are masked in captures (PR #1160 / #1145).** Desk check on a
  debug pull; nothing on the dash looks different. **How to tell it works:**
  1. A capture of the live DoorDash chat list (the conversation row with the customer's name and their
     last message) shows the `tvTitle` value as `[redacted:<4hex>]` — the 4 hex equal the first 4 of that
     delivery's `customerNameHash` — and the `tvLastMessage` value as plain `[redacted]`. No customer
     name or message text anywhere in the envelope.
  2. An UNKNOWN capture of any other sheet whose title renders under `tvTitle` ("Pick up order" and the
     like) shows ONLY that title NODE masked — all of its fields (text, description, …) as `[redacted]`;
     the rest of the X-Ray triage text is intact. That single lost node is the accepted cost (ADR-0011
     residual 11, on any platform whose id ends in `tvTitle`); anything more masked is a finding.
  - Confirmed: 0/2
  Triage source: first-line blame 2026-09-30 (`6e0033c7f`, original README L240).
