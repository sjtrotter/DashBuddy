status: review
issues: #867, #873

- **🆕 NEW — #867 write-side / PR #873 — bubble chat messages carry their own session.**
  Offer outcomes, session lifecycle, task lines, and the heads-up summary now file into their
  ORIGINATING session's chat even when the other platform's frames flip the active platform
  mid-write. **Desk (multi-app pull):** `chat_messages.dashId` for offer-outcome lines matches the
  offer's own platform session — zero cross-platform mis-files. (Display-side — what the bubble
  SHOWS with two live sessions — is still open on #867, dev call.)
  - Confirmed: 0/2
  desk: desk-verifiable: join chat_messages.dashId to each originating offer's platform session on a multi-app pull.
  Triage source: first-line blame 2026-07-26 (`a71ad28d8`, original README L1474).
