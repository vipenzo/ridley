---
name: ai-status
category: ai-describe
since: ""
status: stable
---

# ai-status

## Signature

`(ai-status)`

## Description

Report the current AI provider configuration to the panel. Lists which provider is selected, whether an API key is present, and the model name in use.

Use `ai-status` to confirm that `describe` will actually work before you call it. If no provider is configured, the report explains what is missing and points at the Settings panel.

## Example

{{example: ai-status-check}}

<!-- example-source: ai-status-check -->
```clojure
(ai-status)
;; -> AI provider: gemini
;; -> Model:       gemini-2.5-flash
;; -> API key:     configured
;; -> Ready to use (describe) and (ai-ask).
```
<!-- /example-source -->

A typical "ready" report. If the provider is unset or the API key is missing, the output instead explains the gap so you know what to fix.

## Notes

- Provider, model and API key are configured via the Settings panel; this function only reads the current state, it does not change it.
- Vision-capable providers supported today: Gemini, Claude, GPT-4o.
- `ai-status` is safe to call at any time, including with no session open.

## See also

- **Related:** `describe`
