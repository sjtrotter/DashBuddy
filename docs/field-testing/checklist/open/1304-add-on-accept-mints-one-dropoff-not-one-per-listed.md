status: active
section: §2 Active / 0/2 or 1/2 younger than 6 weeks — newest first
issues: #1304
confirmed: 0/2

- [active] **🆕 NEW — add-on Accept mints one dropoff, not one per listed row (#1304).**
  **On-dash:** accept a mid-route add-on; the HUD/job should show exactly one new drop (plus a new store
  only when the add-on is a different store). **Desk:** the job's DROPOFF task count grows by exactly one
  per add-on accept (PICKUP tasks grow by one only for a different-store add-on); `quotedItemCount` null
  on the add-on economics; the job proves complete after the last drop without an `endSession` force-stamp.
  - Issue: #1304. Confirmed: 0/2 (awaiting field validation)
