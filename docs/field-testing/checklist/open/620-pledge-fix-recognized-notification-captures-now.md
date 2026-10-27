status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / recognition
issues: #620
confirmed: 0/2

- [review] **🔒 PLEDGE FIX — recognized-notification captures now mask customer PII (#620).**
  Send yourself a customer chat ("Message from ‹name›" + a message body) and trigger an order-ready
  push ("‹name›'s order is ready for pickup at ‹store›"), then pull the capture envelopes off-device.
  **Working looks like:** the chat capture shows the sender masked (`Message from [redacted:‹4hex›]`)
  and NO message body; the order-ready capture shows the customer name masked (`[redacted:‹4hex›]`)
  but **keeps the store name** (merchants aren't PII). If any raw customer name/body survives in a
  notification envelope, it's broken.
  - Confirmed: 0/2
  desk: desk-verifiable: inspect chat and order-ready notification envelopes for masked sender/customer slots and absent message bodies.
  Triage source: first-line blame 2026-07-03 (`82cbb53d5`, original README L2019).
