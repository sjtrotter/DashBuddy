status: must-watch
issues: #919

- **Chat compose box never persists raw (#919):** after a dash in which you typed in the DoorDash chat,
  no file under `captures/doordash/accessibility.click/UNKNOWN/` or `accessibility.window/UNKNOWN/`
  carries a `message_input` node or an `android.widget.EditText` node with readable `text` — the field
  reads `[redacted]` — and `app.log` shows `Capture backstop: UNKNOWN click node carried customer PII (… input=edittext-class)` (or `input=editable`);
  a recognized `chat_conversation` envelope masks the box through its rule entry.
  - Confirmed: 1/2 (desk 10-18, build `09c70dba`, DoorDash 8.102.6: 25 recognized `chat_conversation` envelopes carry `message_input` as `[redacted]`; the two UNKNOWN click envelopes on the box (10-14 15:11, 10-17 17:11) read `[redacted]`; `app.log` 10-17 17:16:12 shows `Capture backstop: UNKNOWN click node carried customer PII (... input=editable ...)`. No readable EditText text anywhere in the pull.)
    - desk 10-26 (pull of 10-18 evening to 10-25, build `d807d3f3`): all 13 text-bearing `message_input` nodes read `[redacted]` (12 recognized `chat_conversation`, 1 UNKNOWN click on 10-25); no readable EditText text. The dev confirmed typing (2026-10-27) and the log agrees: 258 `TYPE_VIEW_TEXT_CHANGED` events on the DoorDash `EditText` from 15:48 to 16:15, matching the six `chat_conversation` envelopes 15:54 to 16:15, all with the box `[redacted]`. The backstop half was NOT exercised: the one tap on the box (15:55:03) carried `screenTarget=chat_conversation`, so the chat rule masked it and no `Capture backstop: UNKNOWN click node…` line was logged (the 12:47 `input=editable` backstop lines are a store-search `AutoCompleteTextView`, not the chat). Counter unchanged: the second confirmation needs a chat-box tap that reaches the UNKNOWN backstop.
  Why proposed: Pledge: typed chat text must never persist raw in recognized or UNKNOWN captures.
  Triage source: first-line blame 2026-10-05 (`8d8855c9e`, original README L82).
