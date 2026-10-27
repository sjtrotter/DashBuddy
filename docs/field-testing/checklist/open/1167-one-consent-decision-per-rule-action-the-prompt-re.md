status: active
issues: #1167

- **🆕 NEW — one consent decision per rule action; the prompt re-collects once (#1167).** The grant
  store's key shape changed (one key per (rule, action), pinned to every branch's binding
  definition), so the v2 migration clears every stored grant AND denial on the first launch of this
  build and the front door asks again. **How to tell it works:**
  1. First foreground after install: the consent prompt shows exactly FOUR rows — Accept offer,
     Decline offer, Confirm decline, Expand earnings — never a duplicate; Accept and Decline each
     carry the line "Covers 2 card layouts". Settings → Automation & Consent shows the same four.
  2. The log's rule-load line reads `reconciled 4 capabilit(ies) from rule load: granted=0 denied=0
     undecided=4 (stale grants=0)` on that first launch, then real counts after your answers; one
     `consent-schema migration ran: cleared stored grants and denials (key shape v2, #1167)` INFO line
     appears exactly once.
  3. Allow Decline once → on the next dash BOTH card layouts' Decline binds are covered: a decline
     automation on the Compose card (8.99.20) fires without a second prompt.
  - Issue: #1167. Confirmed: 1/2 (log half, desk 2026-10-05: 11 notification Decline taps each resolved `OFFER_DECLINED` on the matching hash; `confirm_decline` granted since 10-03 and skipped by the quick-decline setting; the prompt half needs dev eyes)
    - desk 10-26 (pull of 10-18 evening to 10-25, build `d807d3f3`): log half again: 26 heads-up Decline taps each resolved on their own hash; `confirm_decline` skipped by the quick-decline setting. The prompt half still needs dev eyes. Counter unchanged.
  Triage source: first-line blame 2026-09-30 (`ee5a9509b`, original README L225).
