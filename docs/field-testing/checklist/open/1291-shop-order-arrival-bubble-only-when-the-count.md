status: active
issues: #1291

- **#1291 shop-order arrival bubble only when the count changed:** on a single-store shop order, at the store's item list. Working = when the store lists the same number of items as the offer, no "Store lists N items (offer said N)" bubble appears; when the count differs, one bubble shows both numbers and the new $/hr. Broken = the bubble shows with equal numbers. (A differing count with no bubble is expected when the offer had no pay to price, or when it isn't the job's first estimate; note that the offer may quote *units* where the store counts *items*.) — Confirmed: 0/2
  - desk 11-12 (pull of 11-07 and 11-11, builds `9064004e.dirty` and `96fa4a89.dirty`, DoorDash 8.106.9): no advisory. Every single-order shop's store count equalled the offer's (7 jobs). The two #1528 cards (offer read 0 items, store 20) raised no advisory either. Counter unchanged.
