---
name: describe
category: ai-describe
since: ""
status: stable
---

# describe

## Signature

`(describe)`
`(describe :name)`

## Description

Start an interactive AI session that describes the current geometry. The zero-arity form describes every visible mesh in the scene; the keyword form targets a single registered object.

`describe` is the entry point for accessibility-focused workflows. The session is "interactive" in the sense that once it is open, `ai-ask` keeps the same conversation thread alive so the AI sees prior turns when answering follow-up questions.

Under the hood the pipeline renders 7 standard views of the target via `render-view` (6 orthographic plus one perspective), packages the images alongside the source program text, and sends both to whichever vision-capable provider is configured (Gemini, Claude, or GPT-4o). The first response is printed to the panel inside a `=== Description ===` block.

A vision-capable AI provider must be configured first. Use `ai-status` to inspect the current configuration, or open the Settings panel to enter an API key.

## Parameters

- `:name` — optional keyword naming a registered object. When omitted, all currently visible meshes are described together.

## Example

{{example: describe-gear}}

<!-- example-source: describe-gear -->
```clojure
(register gear
  (mesh-difference
    (mesh-union
      (cyl 25 10)
      (for [i (range 12)]
        (attach (box 4 6 10) (th (* i 30)) (f 22))))
    (cyl 6 12)))

(describe :gear)
;; -> Analyzing geometry... (generating views)
;; -> Sending to AI... (7 images + source code)
;; -> === Description of :gear ===
;; -> [AI-generated description appears here]
;; -> ===
```
<!-- /example-source -->

A spur gear is built from a hub and twelve radial teeth, then described. The AI receives the 7 rendered views plus the source code that produced the mesh, and replies with a structured natural-language description.

## Variations

{{example: describe-all-visible}}

<!-- example-source: describe-all-visible -->
```clojure
(register base (box 50 50 5))
(register pillar (attach (cyl 6 30) (u 5)))
(register cap (attach (sphere 10) (u 35)))

(describe)
;; Describes the whole visible scene (base + pillar + cap together)
```
<!-- /example-source -->

The zero-arity form is the right call when the question is "what does this assembly look like?" rather than "what is this single part?".

## Notes

- Requires a configured vision-capable provider (Gemini, Claude, GPT-4o). Run `(ai-status)` if you are unsure.
- The describe pipeline shares its view-rendering code with `render-view` — the same 7 views you would get from `render-all-views` are what the AI sees.
- The session remains open after the first response. Use `ai-ask` to ask follow-up questions; `end-describe` closes it; `cancel-ai` aborts an in-flight call without closing the session.
- Output goes to the panel. Make sure a panel is open (`panel`) to see the text.

## See also

- **Related:** `ai-ask`, `end-describe`, `cancel-ai`, `ai-status`, `render-view`
