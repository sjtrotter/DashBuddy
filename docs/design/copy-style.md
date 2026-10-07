# App copy style (DashBuddy)

Adopted 2026-10-06 from tropes.fyi (The Writing Whip, Ossama Chaib, v0.1.0) and Oxide RFD 576 §LLMs as writers.
The full prose discipline is the dev's `prose-whip` skill; this file is the subset that governs user-facing copy:
`strings.xml` in `:app` and every `:feature:*`, hard-coded Compose text, notification text, and the bubble HUD.

## The rules

1. **A label, never a sentence, beside a number.** The number is the message. The label says what the number is and
   over what denominator or window, in four words or fewer. `Net/hr online`, `Kept this week`, `Typical gap`.
2. **Caveats live in the one disclosure per screen** (`HowNumbersWorkFooter`), never beside the figure. A figure
   whose meaning depends on a condition carries that condition as a short context line (`AppStatTile.sub`), not
   a paragraph. Thin-data reasons and plan states are exempt from the label rule because they are the content.
3. **Plain nouns, repeated.** A dash is a dash on every screen (not a session, a shift, a window). Kept is kept.
   Earned is gross and says so. Pick the word once and reuse it.
4. **No adverbs, no intensifiers, no marketing.** Nothing is quietly, deeply, remarkably or fundamentally anything.
   The app describes; it does not sell, congratulate, reassure or coach.
5. **No counting before listing, no "you" sentences that perform.** Not "3 things to review"; a list. Not
   "You kept more this week!"; the number and its comparison.
6. **No negative parallelism, no reveals, no analogies.** Not "It isn't pay, it's time." Not "Here's the thing."
   Not "Think of it as a scorecard."
7. **State the scope once, where the eye is.** Lifetime, this week, in your windows: said at the figure or in
   the screen's badge, not three times.
8. **Punctuation a keyboard types.** Hyphens and straight quotes; the one em-dash placeholder `EMPTY_VALUE` is a
   glyph for "no value", not prose.
9. **Delete before you shorten.** If a string's only job is to explain another string, remove it and fix the label.
10. **Errors and empty states say what happened and what would change it,** in one line each. No apology, no
    encouragement.

## How to apply it

- When a string is added or changed, read it against the ten rules. The reviewer does too.
- A number without a label is a defect; a label with a verb is a smell; a second sentence is a disclosure in the
  wrong place.
- Translations follow the English (en is authoritative; es/fr language-level only, see the locale allowlist).
