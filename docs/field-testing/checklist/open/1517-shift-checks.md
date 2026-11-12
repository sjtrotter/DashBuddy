status: active
issues: #1517

- **An accepted offer with no delivery is checked on its shift page (#1517):** open a finished shift that had an accepted offer you never delivered. Its card reads "No delivery recorded" (while the shift runs it reads "Accepted") and under the money check a line reads "1 accepted offer has no delivery recorded". An offer the app saw you unassign reads "Unassigned" with its time and raises no line. A job with two accepted offers and one delivery shows one line, "1 job has 2 accepted offers and 1 delivery", and no sheet. Tap the "No delivery recorded" card: "What happened to this offer?" opens. "It was unassigned" turns the card into "You marked it unassigned" and the line goes. "Add a missed delivery" opens the add dialog with the offer's store and pay filled in; after Add the card shows the delivery ("You entered") and the line goes, also after a restart. Close leaves the card as it was. The Money tab no longer lists unassigned offers.
  - Confirmed: 0/2
    - desk 11-12 (pull of 11-07 and 11-11, builds `9064004e.dirty` and `96fa4a89.dirty`, DoorDash 8.106.9): not exercised. Counter unchanged.
