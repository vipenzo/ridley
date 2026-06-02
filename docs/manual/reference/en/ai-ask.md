---
name: ai-ask
category: ai-describe
since: ""
status: stable
---

# ai-ask

## Signature

`(ai-ask "question")`

## Description

Send a follow-up question into the currently open describe session. The AI replies in the same conversation thread, so prior turns (including the original images sent by `describe`) stay in context.

`ai-ask` only works while a session is active. Call `describe` first to open one; if no session is open `ai-ask` reports the error and does nothing else.

The reply is printed to the panel. Multiple `ai-ask` calls accumulate in the same thread, which is what makes the session "interactive": each question is interpreted in light of what came before.

## Parameters

- `question` — a string. Free-form natural language; no special syntax.

## Example

{{example: ai-ask-followups}}

<!-- example-source: ai-ask-followups -->
```clojure
(register gear
  (mesh-difference
    (mesh-union (cyl 25 10)
                (for [i (range 12)]
                  (attach (box 4 6 10) (th (* i 30)) (f 22))))
    (cyl 6 12)))

(describe :gear)
;; First reply with the structured description

(ai-ask "How thick are the gear teeth?")
(ai-ask "Would this print well without supports?")
(ai-ask "What changes would make the hub stronger?")
```
<!-- /example-source -->

After describing a gear, three follow-up questions probe different aspects (geometry, manufacturability, design improvements). The AI keeps the gear images and earlier replies in context, so questions can be short and conversational.

## Notes

- Requires an open session. Start one with `describe` (or `(describe :name)`).
- The session persists across `ai-ask` calls until you call `end-describe` (or it is reset by a new `describe`).
- To abort a single in-flight call without closing the session, use `cancel-ai`.
- Output goes to the panel; open one with `panel` if you do not see replies.

## See also

- **Related:** `describe`, `end-describe`, `cancel-ai`
