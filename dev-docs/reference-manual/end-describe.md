---
name: end-describe
category: ai-describe
since: ""
status: stable
---

# end-describe

## Signature

`(end-describe)`

## Description

Close the active describe session. Subsequent `ai-ask` calls will report that no session is open until a new `describe` is started.

Use this when you are done with a conversation thread and want a fresh start, or when the next question is unrelated enough that mixing it with the prior context would confuse the AI.

## Example

{{example: end-describe-cycle}}

<!-- example-source: end-describe-cycle -->
```clojure
(register part (cyl 20 30))

(describe :part)
(ai-ask "Is the wall thickness uniform?")

;; Done with this part — close the thread.
(end-describe)

;; A new describe opens a brand-new session.
(register other (sphere 15))
(describe :other)
```
<!-- /example-source -->

After two turns of questions, the session is explicitly closed before describing a different object so the AI does not carry over context from the cylinder.

## Notes

- Calling `end-describe` with no session open is a no-op.
- This does not cancel an in-flight AI call; for that, use `cancel-ai`.
- Starting a new `describe` while an old session is still open will replace the session, so it is fine to skip `end-describe` if you immediately move to a new subject.

## See also

- **Related:** `describe`
