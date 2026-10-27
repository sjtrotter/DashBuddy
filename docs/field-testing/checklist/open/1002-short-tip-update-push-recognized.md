status: active
section: §2 Active / 0/2 or 1/2 younger than 6 weeks — newest first
issues: #1002, #1400
confirmed: 0/2

- [active] **#1002 short "Tip Update" push recognized:** when DoorDash sends "A customer added $X tip on a past <store> order." (no "delivered at"), check the pull. Working = it lands under `captures/doordash/notification/additional_tip/`, with the amount and store in the `ADDITIONAL_TIP` event; a customer chat that happens to quote those words still lands under `customer_message`. Broken = the push in `notification/UNKNOWN/`, or a chat under `additional_tip`. — Confirmed: 0/2
  - desk 10-26 (pull of 10-18 evening to 10-25, build `d807d3f3`): only the long form arrived (10-23 17:49:49, recognized `additional_tip`). Its handler ran twice and the amount was stored nowhere: #1400. Counter unchanged.
