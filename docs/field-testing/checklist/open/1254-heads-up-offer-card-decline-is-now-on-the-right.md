status: active
issues: #1254

- **#1254 heads-up offer card: Decline is now on the RIGHT, Accept on the LEFT (24dp gap).**
  **On-dash:** when a DoorDash offer arrives with our heads-up floating over DoorDash's top-right Decline,
  reach for Decline by habit. Working = your finger lands on OUR muted Decline (the offer declines / the
  confirm sheet appears), never Accept; expanding the banner keeps the same order. Broken = any near-miss
  onto Accept, or the order flips between collapsed and expanded. Two clean dashes with at least one
  reflex decline each.
  On Pixel 7 (Android 16), check the collapsed shade, floating heads-up and expanded view, including
  enlarged text: both actions must remain visible and tappable. Open the in-bubble offer card and verify
  the same physical order (its gap remains 8dp); forced RTL must keep Accept left and Decline right on
  every surface. With TalkBack, verify Accept is the first action in traversal order on every surface.
  - Confirmed: 0/2
