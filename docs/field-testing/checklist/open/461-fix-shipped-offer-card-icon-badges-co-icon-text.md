status: active
issues: #461, #531

- **🔧 FIX SHIPPED — offer card icon badges + co-icon-text shop badge [cart N] (#461, PR #531). CONFIRM ON DASH.**
  The offer card's badges are now **icons** (red card, alcohol, large order, priority, etc., tinted by
  brand color), and the **Shop & Deliver** badge is the shopping-chat **cart icon + the item count**
  (`[🛒 N]`) — the count moved off the $/hr hero onto the badge. **Confirm on dash: 0/2 —** on a
  **shop** offer (e.g. Sprouts/CVS) the card shows the cart badge with the **true item count** (a
  25-item shop reads **25**, not 26), it shows **while the offer is live** (not just after), and a
  pure-pickup (or pickup stack) shows **no** cart/count. Other badges should render as their icons. If
  a count is off by the number of pickups in a stack, or the shop badge doesn't show live, note the
  offer's order mix.
  Triage source: first-line blame 2026-06-18 (`d412eb3fd`, original README L2386).
