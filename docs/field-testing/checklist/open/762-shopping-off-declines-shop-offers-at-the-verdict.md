status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / state machine
issues: #762, #778
confirmed: 0/2

- [review] **🆕 NEW — "Shopping off" declines shop offers at the verdict edge (#762 D12 / PR #778).**
  With `allowShopping` off, a shop-type offer now gets a structural `SHOP_DECLINED` verdict (label
  "Shopping off") from the evaluator itself — full economics still computed and shown, score 0,
  decline recommendation — instead of relying on downstream handling.
  **How to tell it's working (needs the strategy toggle off; watch any shop offer):** the offer
  card/bubble shows the "Shopping off" quality label with the decline recommendation while the pay/
  mileage numbers still render; non-shop offers are completely unaffected. Flip the toggle back on
  and a shop offer scores normally again (the gate reads live strategy prefs).
  - Confirmed: 0/2
  desk: desk-verifiable: replay a shop offer with allowShopping off and compare verdict, score and computed economics; visible advice still needs a device check.
  Triage source: first-line blame 2026-07-15 (`2115f4c59`, original README L1538).
