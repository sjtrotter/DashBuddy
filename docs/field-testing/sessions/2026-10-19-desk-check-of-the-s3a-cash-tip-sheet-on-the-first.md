## 2026-10-19 (desk check of the S3a cash tip sheet on the first install with it: the pad does not match panel 11c)

**Date:** 2026-10-19 · **Platform(s) tested:** none (the sheet was opened at the desk) · **Branch under test:** `master` at `b663280a` (post-#1365 merge: S3a cash tip, #1363 vehicles, S3b expenses) · **Field conditions:** parked; the dev opened the Cash tip sheet and compared it with the approved panel `v4-11c-sheet-cash-tip-sign`.

### Bugs
1. **The sign control is two text buttons, not one toggle.** The panel shows ONE button at the left of the pad with `+` stacked over `−` and the active glyph on an accent pill; the shipped sheet renders two separate `+` / `−` text buttons in a column. The dev: "the +/− button should be ONE button that changes state."
   - **Status:** Shipped in #1370 (2026-10-19).
2. **The dollar amounts do not look like buttons.** `$1 $5 $10 $20 Other` are text buttons with no fill; the panel's pad is a row of filled 56 dp buttons (`surface3`, 6 dp corners, the ends rounded 18 dp).
   - **Status:** Shipped in #1370 (2026-10-19).
3. **Too much empty space inside the sheet above the bottom.** The dev: "what was the reason again that there's so much space between the bottomsheet-looking interface and the bottom row?" Likely cause: the shipped compact page lays the pad directly under the delivery rows and leaves the sheet's slack between the pad and the commit button, while the panel anchors the pad at the bottom (`margin-top:auto`) above the button. One possibility to rule out at the desk: an extra inset between the sheet frame and the dock (the undo slot); the fix measures `hud.sheet` against `hud.body` and `hud.dock` in a test rather than assuming.
   - **Status:** Shipped in #1370 (2026-10-19).
4. **Other adds to the running amount instead of replacing it.** The spec (S3a §2) had Other's amount ADDED on Done; the dev wants it to replace: "if the user clicks Other after adding in some tip... it should replace." Decision taken: Other opens an empty keypad and its Done sets the total.
   - **Status:** Shipped in #1370 (2026-10-19).
