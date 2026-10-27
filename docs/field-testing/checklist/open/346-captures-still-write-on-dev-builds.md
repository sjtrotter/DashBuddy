status: review
issues: #346

- **Captures still write on dev builds (#346).** Capture persistence is now bound per build
  variant (debug → disk, release → none). Your field builds are debug, so nothing should change —
  sanity-check after a dash that the session's `captures/` folder is non-empty. If it's ever
  empty, check logcat for "Capture persistence disabled (release build)" — that means a release
  build got dashed by mistake.
  - Confirmed: 0/2.
  desk: desk-verifiable: confirm the fielded build variant and a nonempty captures folder; inspect the release-disabled log if empty.
  Triage source: first-line blame 2026-06-11 (`1ed6efbaa`, original README L3084).
