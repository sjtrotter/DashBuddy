status: active
issues: #991, #428

- **🆕 NEW — #991 (P0) — the spoken offer verdict went silent and stayed silent.** From 08-06
  16:28 onward every single `speak()` call returned `-1` (15 of 15 across three dashes; 11 of 11
  had succeeded on 08-01/08-02), each one logging `WARN/Tts: speak returned -1 — abandoning audio
  focus`. Offers were still evaluated, carded and notified the whole time — **only the voice
  died**, with no user-visible signal that it had. The app process had been alive for 8 days at
  that point and the TTS engine was only ever initialized once, at install.
  **FIXED — PR for #991 (needs field validation).** The handler now rebuilds the engine instead of
  latching dead: a failed `speak()` tears the `TextToSpeech` down and constructs a new one
  immediately (the same #428-B language wiring is re-applied), further failures are gated by a
  linear 30 s → 60 s → … → 5 min backoff, and after 3 consecutive lost utterances a notification
  appears on the shared **App notices** channel saying the voice stopped and that reopening the app
  restores it. A successful utterance resets the whole ladder; the notice fires at most once per
  process.
  **What to watch:** if the offer read goes quiet mid-dash, it should come back **by itself within
  roughly a minute** — the next offer or two after the silent one should speak again. If the engine
  is genuinely dead (e.g. the TTS package is mid-update), you should instead get the "gig.log has
  stopped reading offers aloud" notification rather than open-ended silence; force-stopping and
  reopening should then restore it. Note whether the notice appeared, and whether the voice
  self-recovered without any intervention. **Desk:** `grep 'Tts' app.log` — the fix working looks
  like `WARN … speak returned -1` immediately followed by `WARN … re-initializing the engine` and
  then `INFO Tts: engine re-initialized after failure`; an unrecoverable engine looks like
  `WARN … speech still failing after 3 consecutive losses — notifying the dasher`. A `speak
  returned -1` run with NO re-init line after it means the fix did not engage.
  - Confirmed: 1/2
    - (desk 09-21: no-regression — 22/22 `speak()` succeeded on the 09-20 dash, zero `-1`, zero
      re-init, zero notice; the ladder was untouched again.)
    - (desk 09-09: no-regression only — 17/17 `speak()` succeeded, zero `-1`, zero re-init, zero notice;
      the ladder was untouched.)
    - (desk 09-05: the recovery seam fired live 08-30 15:44:52 — one `speak()` loss →
      rebuild → engine re-initialized in 417 ms; 34/35 utterances spoke.)
    - (desk 08-24: voice healthy — 28/28 `speak()` succeeded across four dashes — but the
      engine never failed, so the ladder was unexercised; no-regression, not a confirmation.)
  Triage source: first-line blame 2026-08-09 (`2f57a0842`, original README L841). Recorded first confirmation: 2026-08-30 recovery, recorded at desk 2026-09-05; the counter line itself is undated (existing evidence only).
