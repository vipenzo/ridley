---
name: cancel-ai
category: ai-describe
since: ""
status: stable
---

# cancel-ai

## Signature

`(cancel-ai)`

## Description

Cancel the currently in-flight AI call. The describe session itself stays open, so the next `ai-ask` will work normally — only the pending request is aborted.

Useful when a question turns out to be wrong, when the wait is taking too long, or when you realise the AI is going down an unhelpful path and you want to retry with a sharper prompt.

## Example

{{example: cancel-ai-mid-call}}

<!-- example-source: cancel-ai-mid-call -->
```clojure
(register part (cyl 20 30))
(describe :part)

(ai-ask "Describe every microscopic surface feature in extreme detail")
;; ... it's taking forever, and you realise you do not actually want that.

(cancel-ai)
;; The in-flight call is aborted.

;; The session is still open — retry with a tighter question.
(ai-ask "What is the wall thickness?")
```
<!-- /example-source -->

A long-running question is cancelled mid-flight; the describe session survives, so the next `ai-ask` continues the same conversation as if the cancelled question had never been sent.

## Notes

- `cancel-ai` does not close the session. Use `end-describe` to close it.
- Calling `cancel-ai` when nothing is in flight is a no-op.
- The cancelled response is dropped — it is not retried automatically.

## See also

- **Related:** `describe`, `ai-ask`
