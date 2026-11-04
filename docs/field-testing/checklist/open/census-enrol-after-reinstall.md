status: active
issues: #1522

- **The census identity enrols after a reinstall (#1522):** after any reinstall of the debug build (or a
  **Reset census identity**) with census upload on, open Developer options and read the census status line after
  the next run. It should reach `enrolled` (or a later outcome such as `uploaded`), never stay at `enrol_rejected`.
  If it shows `enrol_rejected`, the token now names the reason, e.g. `enrol_rejected 422 unsupported_schema`, or
  `enrol_rejected unsupported_schema` with no status when the server accepts no schema this build can send. Working
  = a fresh identity enrols while the server accepts screens only (notifications off).
  - Confirmed: 0/2
