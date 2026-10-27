status: active
issues: #428, #845

- **🆕 NEW — #428-B / PR #845 — multi-language TTS (system locale + settings override).**
  **What to watch:** Settings → Voice → Spoken offer language set to Español → the next offer reads
  in Spanish (voice AND words together); System default on an English phone stays English; if the
  es voice pack is missing the read falls back to English (one WARN in the log, never silence).
  **Desk:** grep for the `Tts` tag language-apply lines; no per-utterance WARN spam.
  - Confirmed: 0/2 (desk 07-26: INCONCLUSIVE — 64 post-install utterances all English, zero WARN
    spam, but the Spanish path and the missing-voice-pack fallback were never exercised. CAVEAT:
    the desk grep above has NO corresponding log site in the current code — a no-hit is not
    evidence; needs the Settings→Español toggle actually flipped on a dash. Desk 07-30: same
    null — 14 utterances, all English, zero WARN; the toggle was never flipped.)
    - desk 09-13: seventh mechanism corroboration — the 09-08 expand grant persisted across the 09-11 22:34 restart and fired 11×, while `confirm_decline` was denied fail-closed 31× (13 confirm episodes × 2 + 5 single-denial episodes, exactly accounted). Note: the reconcile line's "(none granted — awaiting consent)" phrase is a CONSTANT in `RuleCapabilityRepository.reconcile`, not a store read.
  Triage source: first-line blame 2026-07-23 (`3745bc073`, original README L1288).
