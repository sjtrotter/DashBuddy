status: active
issues: #1443, #1458, #1459, #1460

- **The bubble headline is the shift's earnings (#1443):** the big unlabelled figure matches DoorDash's own
  running total, or runs ahead of it right after a delivery while DoorDash's total lags, then DoorDash's total
  catches up and takes over. A dash-along-the-way delivery counts once its quote-priced row lands (at job close
  today; delivery-time landing is #1458). A cash tip shows as `+$X cash` under the figure and never changes the
  figure. The top row is Online and Miles only; the waiting card and resting card read Earned/hr. After the shift
  the figure is DoorDash's summary total. Uber (no running total) shows its recorded delivery pay. The goal pill
  counts app earnings across both apps today (no cash, no costs), reads "Set goal" once on the first launch of this
  build, and switching the bubble between apps does not change it. With two apps, focusing the one that ended
  shows that shift's figure, chip, stats and cash target, not the other app's. Reopening the bubble after a long collapse never flashes the previous shift's figure; right after a shift ends the resting card shows em dashes until the shift's row lands, never "No shift yet". Known residuals: a receipt
  re-entered after an offer pop-up can be counted twice on the headline (#1460); at an early end the resting
  figure no longer drops below recorded pay since #1459 (its own item). — Confirmed: 0/2
