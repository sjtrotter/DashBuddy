status: active
issues: #1568

- **The bundled rules load as one generation at startup (#1568):** after installing a build with #1568, open
  DoorDash and confirm screens are recognized as before (the bubble follows the dash; `PipelineStats` shows
  recognized frames, not all UNKNOWN). In the log, look for one INFO `Rules generation live:` line per app start and
  no ERROR `Rules` line rejecting the bundled generation. Working = recognition unchanged from the previous build; a
  rejected bundled generation would leave every frame dropped (fail closed), which shows as no recognition at all.
  - Confirmed: 0/2
