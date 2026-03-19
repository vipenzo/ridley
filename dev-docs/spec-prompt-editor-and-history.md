# Spec: Prompt Editor & Explicit AI History

## Overview

Two related features that make AI interaction in Ridley more transparent and controllable:

1. **Prompt Editor** — a panel for viewing and editing the system prompts used by AI features, with support for expandable macros.
2. **Explicit AI History** — code generated in previous steps is kept in the source file as comments, visible to both the user and the AI on the next step.

Both features share the same motivation: removing opacity from AI mechanisms and giving the user direct control over what gets sent to the models.

---

## Feature 1: Prompt Editor

### Motivation

The `(describe)` system prompt originally included the model's source code, which can bias the AI: if the code contains descriptive names (e.g., `easter-shade`), the model may be influenced by the name instead of objectively describing the geometry. It's impossible to anticipate all use cases: better to let the user experiment directly.

### Editable Prompts

Each prompt has an identifier, a human-readable name, and belongs to a category:

| ID | Name | Category |
|----|------|----------|
| `describe/system` | Describe — System Prompt | Accessibility |
| `describe/user` | Describe — User Prompt | Accessibility |
| `codegen/tier1` | Code Generation — Tier 1 | AI Assistant |
| `codegen/tier2` | Code Generation — Tier 2 | AI Assistant |
| `codegen/tier3` | Code Generation — Tier 3 | AI Assistant |

Prompts can have variants per provider and per specific model. The resolution order, from most specific to most generic, is:

1. `<id>:<provider>:<model>` — e.g., `describe/system:google:gemini-2.5-pro`
2. `<id>:<provider>` — e.g., `describe/system:google`
3. `<id>` — default variant, used when no more specific variant exists

This allows differentiating behavior between models from the same provider (e.g., Flash vs Pro in Gemini, which respond differently to highly detailed prompts).

### Macros

Prompt text can include macros in the form `{{macro-name}}`. Before sending the prompt to the AI, macros are expanded with actual data. Available macros depend on the context:

| Macro | Available in | Expansion |
|-------|-------------|-----------|
| `{{source-code}}` | describe, codegen | Current editor source code |
| `{{screenshots}}` | describe | Descriptive list of the 7 attached images (front, back, left, right, top, bottom, perspective) |
| `{{metadata}}` | describe | JSON with bounding box, dimensions, volumes, vertex/face counts, and per-object data |
| `{{object-bounds}}` | describe | Per-object bounding boxes as readable text (e.g., `bowl: X [-37.5 .. 37.5] Y [-276.0 .. 38.0] Z [0.0 .. 192.0] size 75.0 × 314.0 × 192.0`). Helps the AI choose slice positions that actually intersect each object. |
| `{{slices}}` | describe | Any additional slices requested by the AI or pre-configured |
| `{{history}}` | codegen | Code from previous steps (see Feature 2) |
| `{{query}}` | codegen | Current user query text |
| `{{object-name}}` | describe | Name of the registered object being described |

Unrecognized macros are left unchanged and a warning is shown in the REPL.

### UI: Prompt Editor Panel

The panel opens from the Settings menu (or a dedicated toolbar icon), following the same pattern as the Libraries panel.

**Layout:**

```
┌─────────────────────────────────────────────────────────┐
│  Prompt Editor                               [×]        │
│─────────────────────────────────────────────────────────│
│  [Accessibility ▼]  describe/system          [↩ Reset]  │
│  ● describe/system                                      │
│  ○ describe/user                                        │
│  ─────────────────                                      │
│  [AI Assistant ▼]                                       │
│  ○ codegen/tier1                                        │
│  ○ codegen/tier2                                        │
│  ○ codegen/tier3                                        │
│─────────────────────────────────────────────────────────│
│  [Provider: default ▼]          [● modified]            │
│                                                         │
│  ┌───────────────────────────────────────────────────┐  │
│  │ You are a 3D model analyst for blind users.       │  │
│  │ The user cannot see the model.                    │  │
│  │                                                   │  │
│  │ {{screenshots}}                                   │  │
│  │ {{metadata}}                                      │  │
│  │ {{object-bounds}}                                 │  │
│  │                                                   │  │
│  │ Describe the 3D model in detail...                │  │
│  └───────────────────────────────────────────────────┘  │
│                                                         │
│  Available macros: {{screenshots}} {{metadata}}         │
│  {{object-bounds}} {{object-name}} {{source-code}}      │
└─────────────────────────────────────────────────────────┘
```

**Behavior:**

- The editor is a CodeMirror instance in plain text mode (no syntax highlighting, or optionally a minimal mode that highlights `{{macro}}`).
- The selector shows `default`, then the providers configured in Settings, then — if the selected provider has multiple configured models — model-specific variants (e.g., `google / gemini-2.5-flash`, `google / gemini-2.5-pro`).
- The `● modified` badge appears when the current prompt diverges from the system default.
- The `↩ Reset` button restores the original system prompt for that prompt ID and provider, after confirmation.
- Changes are auto-saved to localStorage on editor blur.

### Persistence

localStorage keys, in order from most specific to most generic:

```
ridley:prompt:<id>                        → default variant
ridley:prompt:<id>:<provider>             → provider variant
ridley:prompt:<id>:<provider>:<model>     → model-specific variant
```

Examples:
```
ridley:prompt:describe/system
ridley:prompt:describe/system:google
ridley:prompt:describe/system:google:gemini-2.5-pro
```

When sending to the AI, the system looks up keys in order `provider:model` → `provider` → default, using the first one found in localStorage; if none exists, it uses the hard-coded value.

### Export / Import

An icon in the panel exports all customized prompts as JSON:

```json
{
  "ridley-prompts": "1.0",
  "exported": "2025-03-12T10:00:00Z",
  "prompts": {
    "describe/system": "...",
    "describe/system:google": "...",
    "describe/system:google:gemini-2.5-pro": "..."
  }
}
```

Import accepts the same format, with a confirmation dialog before overwriting.

### Change to `describe` Behavior

The original behavior sent both images and source code. With the Prompt Editor, source code is only sent if the `{{source-code}}` macro is present in the prompt. The default prompt has been updated to remove source code, which can introduce bias when the code contains semantically loaded names.

The current default for `describe/system` is:

> *"I am assisting a blind user who cannot see the 3D model. Please describe the model in as much detail as possible: its shape, proportions, features, spatial relationships, symmetry, and likely function. Do not describe anything other than the 3D model itself. Describe only what is visible in the images."*

Source code is not included in the default. Users who want to provide it to the AI can add `{{source-code}}` manually via the Prompt Editor.

---

## Feature 2: Explicit AI History

### Motivation

The `/ai` system previously tracked previous steps opaquely (in an internal atom), passing them to the AI in the prompt without the user knowing what was being sent or being able to intervene. Making this process explicit in the source code has three advantages:

1. The user sees exactly what the AI generated previously.
2. The user can delete steps they don't need, go back to a previous step, or edit an old step before it influences the next one.
3. The AI receives richer, more coherent context about how the current state was reached.

### Format in Code

Every time the AI generates code via `/ai`, the system:

1. Takes the current editor content that was produced by the AI in the previous step.
2. Comments it out and inserts it above the new code as a history block.
3. Inserts the newly generated code below.

The result in the source file looks like:

```clojure
;; ── AI step 2 ──────────────────────────────────────────
;; (register vase
;;   (extrude (circle 20) (f 80)))
;; ────────────────────────────────────────────────────────

;; ── AI step 3 (current) ─────────────────────────────────
(register vase
  (loft-n 32
    (circle 20)
    #(scale-shape %1 (+ 1 (* 0.5 %2)))
    (f 80)))
```

With multiple steps, they accumulate upward:

```clojure
;; ── AI step 1 ──────────────────────────────────────────
;; (register vase (cyl 20 80))
;; ────────────────────────────────────────────────────────

;; ── AI step 2 ──────────────────────────────────────────
;; (register vase
;;   (extrude (circle 20) (f 80)))
;; ────────────────────────────────────────────────────────

;; ── AI step 3 (current) ─────────────────────────────────
(register vase
  (loft-n 32 ...))
```

### Management Rules

**The AI does not touch history comments.** The expected AI output is only the new code, without history comments. The system (not the AI) handles building the history block before inserting new code into the editor.

**The user can freely modify the comments.** They can delete steps they don't need, uncomment a previous step, or edit an old step. The system simply appends to the top on the next AI invocation.

**Non-AI steps are not tracked.** If the user manually edits code between one `/ai` call and the next, the manual code becomes part of the "current" that will be commented out on the next step. There is no distinction between AI code and manual code in the history.

**The `(current)` marker moves.** When generating a new step, the `(current)` marker is removed from the previous block and added to the new one.

### What Gets Sent to the AI

In the `{{history}}` macro (used by the `codegen/tier2` and `codegen/tier3` prompts), the content of history blocks present in the editor at the time of invocation is sent. Blocks are extracted and uncommented before inclusion in the prompt.

Format sent to the AI:

```
Previous steps:

[step 1]
(register vase (cyl 20 80))

[step 2]
(register vase
  (extrude (circle 20) (f 80)))

Current code:
(register vase
  (loft-n 32 ...))
```

### History Block Parsing

The system must identify history blocks in the source for two operations:

1. **Extraction** for the `{{history}}` macro: finds all `AI step N` blocks, uncomments the content.
2. **Addition** of a new block: finds the `(current)` block, relabels it with the step number, then inserts the new `(current)` block with the new code.

Recognition pattern (regex on the source):

```
;; ── AI step \d+ .*\n(;; .*\n)*;; ─+\n
```

Parsing is best-effort: if comments have been modified by the user in a way that doesn't match the pattern, the system ignores that part of the history without errors.

### Integration with the Internal Atom

The previous `ai-history` atom (which maintained steps for the current session) is deprecated. History state now lives in the source. This means history is persistent across sessions (if the user saves the file) and directly visible/editable.

Tier 1 (small models, code-only output) does not use `{{history}}` in its default prompt — behavior unchanged.

---

## Implementation: Files Involved

### Prompt Editor

| File | Change |
|------|--------|
| `ai/prompt-store.cljs` | Prompts become default values; added `resolve-template` function that reads from localStorage and expands macros |
| `ai/describe.cljs` | Uses `resolve-template` instead of hard-coded prompts; source code removed from default |
| `ai/core.cljs` | Uses `resolve-template` for codegen prompts |
| `ui/prompt_panel.cljs` | New component: panel with prompt list + CodeMirror + reset + export/import |
| Toolbar / Settings | Adds entry point to the panel |

### Explicit AI History

| File | Change |
|------|--------|
| `ai/core.cljs` | After receiving new code from the AI, calls `insert-history-step!` before updating the editor |
| `editor/macros.cljs` | History block functions: `parse-history-blocks`, `insert-history-step!`, `extract-history-for-prompt` |
| `ai/prompt-store.cljs` | The `{{history}}` macro uses `extract-history-for-prompt` |

---

## Open Questions

- **Prompt Editor accessibility**: the panel must be keyboard-navigable for screen reader users. CodeMirror is already accessible; ensure that controls (provider selector, reset button, export/import) are reachable via Tab.
- **History length in prompt**: if the session is very long, `{{history}}` could become very large. There is no limit for now; if it proves problematic in practice, a `{{history:3}}` parameter could be added to limit to the last N steps.
- **Tier 1 and history**: Tier 1 models receive minimal context to keep costs low. `{{history}}` is not in their default prompt, but an advanced user can add it manually via the Prompt Editor.
