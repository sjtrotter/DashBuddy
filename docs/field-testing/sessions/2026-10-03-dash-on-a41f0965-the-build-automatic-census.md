## 2026-10-03 (dash on `a41f0965` — the #1185 build; automatic census uploads confirmed from the server side; v0.7.0 server + VPN + edge limits went live during the dash)

**Date:** 2026-10-03 · **Platform(s) tested:** DoorDash (app 8.99.20) · **Branch under test:** `master`
at `a41f0965` (build `0.230.0+a41f0965`, installed 09:34 local over the running install — the first run of
PR #1190 / #1185: persisted census status line, Copy install id, Reset census identity). · **Field
conditions:** a real dash, written up from the SERVER side while the dasher was driving (no pull yet —
captures/logs/db still on the phone; pull per CLAUDE.local.md § Field-data import when home). The
census server was upgraded under the dash: memory limits (#1179) at 14:05 UTC, the operator VPN (#1181)
at 14:43 UTC, server v0.7.0 with the session login at 15:18 UTC, the edge rate limits (#1178) at 15:42
UTC — four stack restarts of a few seconds each; the phone's uploader rode through all of them.

### Confirmations that cleared the "Next field test" checklist

1. **Census upload lands as clusters on the dashboard (#1182) — Confirmed 2/2, item retired.** Without
   any manual "Upload now", the phone (install `75978f7b`) delivered a batch during the dash: the server's
   ingest log shows `accepted=7 duplicate=0 rejected=0 bytes=39167 status=accepted` (547 ms) on v0.7.0,
   today's ledger for the install reads 67 accepted, and the cluster table grew from 60 (morning) to 66
   with `last_seen_day` today — i.e. the #1187 "upload within ~5 minutes of the first spooled item" path
   works unattended. (First confirmation 2026-10-02 22:53, manual Upload now.)

### Still open on the checklist

- **#1185 status line + Copy install id** stays at 0/2: the server-side evidence proves the uploads, but
  the status line itself (`uploaded <n>` · Queued: 0) and the clipboard copy need the dasher's eyes.

### Verification TODOs (post-dash pull)

1. Pull per the standard procedure and run the desk playbook; confirm the uploads happened without a
   WARN from the uploader (`Census` tag), and that the four server restarts show up only as `uploadFailures`
   retries, never as lost spool items (`spoolDropped` must stay 0).
2. Check the developer screen's status line matches the server ledger (67 accepted today).
