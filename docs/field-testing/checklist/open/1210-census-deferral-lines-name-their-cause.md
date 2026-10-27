status: active
section: §2 Active / Counter unspecified — retained active, newest first
issues: #1210
confirmed: unspecified

- [active] **Census deferral lines name their cause (#1210):** after any dash, `grep -h 'census deferred cause=' shareable.log`
  shows `cause=budget|upload_rate_limit|enrol_rate_limit|health_rate_limit|health_budget seconds=<n>` or
  `cause=stored_deadline remaining=<n>` — never the old bare `census deferred runs=1`. Working = every `deferred`
  status (skeleton, health or enrolment stage) has a matching line naming its cause.
  Triage source: first-line blame 2026-10-05 (`5ff90c7d0`, original README L116).
