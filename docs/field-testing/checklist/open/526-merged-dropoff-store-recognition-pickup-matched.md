status: active
section: §2 Active / Counter unspecified — retained active, newest first
issues: #526, #553, #557
confirmed: 1/2

- [active] **🔧 MERGED — dropoff store recognition + pickup-matched resolution (#526/#553). CONFIRM ON DASH.**
  A dropoff shows the store **resolved from the job's pickups** (single → the pickup's store;
  multi-store stack → matched per drop), not inherited from the last active pickup.
  - Confirmed: **1/2** (2026-06-20 — singles + **same-store** double stacks resolved correctly
    [Panda Express, Parry's Pizzeria]; verified in the db + `ShadowProjector` log).
  - **Multi-store stack** (different stores): the 06-20 Peng's+Little Caesars stack left both drops
    `None` (safe, not wrong) — **now fixed by #557 (branch `feature/557-multistore-dropoff-store`,
    pending merge):** the dropoff running-key forms (`Little Caesars (0164-0045)`, place-name parens)
    now parse + resolve to their pickup. On a multi-store stack each drop should show its **own**
    store, **not both the same** and **not the other stop's**. If a drop shows a wrong store or `None`,
    capture the dropoff frames + note the stack's stores.
    - Confirmed: **1/2 for the #557 fix** (06-29 Sally Beauty + Panda Express stack — all four
      tasks correctly store-attributed, distinct customer hashes; the remaining fold-in-drop gap
      is the week entry's Bug #10b, not a #557 regression).
  Triage source: first-line blame 2026-06-21 (`77deaa149`, original README L2371).
