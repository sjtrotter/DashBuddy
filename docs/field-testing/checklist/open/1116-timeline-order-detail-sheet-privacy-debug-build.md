status: active
issues: #1116

- **#1116 timeline order-detail sheet (privacy, debug build):** on a dash, open the Timeline and tap a delivery task row so its detail sheet opens (address and `Close sheet`). The sheet still lands in `UNKNOWN` (recognition is unchanged). Working = in that `UNKNOWN` capture the street line, the `City, ST ZIP` line, the quoted note and any short code read `[redacted]`, while `Close sheet` and the dropoff option (`Leave it at my door`) are readable. Broken = a raw street or ZIP in a capture that contains `Close sheet` (grep the pull's `UNKNOWN` captures for `Close sheet`, then for `, TX `). — Confirmed: 0/2
