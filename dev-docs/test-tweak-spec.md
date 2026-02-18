# `tweak` — Interactive Parameter Tweaking with Sliders

## Summary

The `tweak` macro evaluates an expression, displays the result in the viewport based on its type (mesh, shape, or path), and generates interactive sliders for selected numeric literals. Moving a slider re-evaluates only that expression and updates the viewport in real-time. When the user confirms, the final values replace the originals and the expression is printed for copy/paste.

## Status: Implemented

## Syntax

```clojure
;; No index → slider for first literal only (index 0)
(tweak (extrude (circle 15) (f 30)))
;; → slider only for 15

;; Positive index (0-based)
(tweak 2 (extrude (circle 15) (f 30) (th 90) (f 20)))
;; → slider only for literal at index 2 (value 90)

;; Negative index (from end, Python-style)
(tweak -1 (extrude (circle 15) (f 30) (th 90) (f 20)))
;; → slider for last literal (20)
(tweak -2 (extrude (circle 15) (f 30) (th 90) (f 20)))
;; → slider for second-to-last (90)

;; Select multiple by vector (supports negative indices)
(tweak [0 -1] (extrude (circle 15) (f 30) (th 90) (f 20)))
;; → sliders for first (15) and last (20)

;; All literals
(tweak :all (extrude (circle 15) (f 30) (th 90) (f 20)))
;; → sliders for all 4 literals
```

**Default behavior**: no index = first literal only. Use `:all` for all literals.

## Display Logic (type dispatch)

The result of evaluating the expression determines visualization:

1. **Mesh** (`(and (map? x) (:vertices x) (:faces x))`) → display as temporary mesh in viewport
2. **Shape** (`(shape? x)` i.e. `(and (map? x) (= :shape (:type x)))`) → display via `stamp` at current turtle pose (semi-transparent surface)
3. **Path** (`(path? x)` i.e. `(and (map? x) (= :path (:type x)))`) → display via `follow-path` from current turtle pose (draws lines)
4. **Vector of meshes** → display all as temporary meshes
5. **Other** → just print value to REPL output, no sliders

## Slider Generation

### Step 1: Parse numeric literals from the expression AST

`tweak` is a macro, so it receives the expression as code. Walk the AST to find all numeric literal positions. Index them 0, 1, 2, ... in depth-first left-to-right order.

Example:
```clojure
(extrude (circle 15) (f 30) (th 90) (f 20))
;;                    ^0         ^1       ^2       ^3
```

### Step 2: Generate labels from parent context

Each literal gets a label derived from its enclosing function call:
- `15` inside `(circle 15)` → label: `"circle: 15"`
- `30` inside `(f 30)` → label: `"f: 30"`
- `90` inside `(th 90)` → label: `"th: 90"`

If a function has multiple numeric args, disambiguate:
- `(box 40 20 10)` → `"box[0]: 40"`, `"box[1]: 20"`, `"box[2]: 10"`

If preceded by a keyword argument:
- `(noisy shape :amplitude 0.3)` → `"noisy amplitude: 0.3"`

### Step 3: Determine slider ranges

Default range: `[value * 0.1, value * 3]` (or `[-50, 50]` if value is 0).
Step: auto-detect from value (integers get integer step, floats get 0.1 step).

### Step 4: Filter by index

- **No filter** (bare expression): only literal at index 0 gets a slider
- **Single positive integer `n`**: only literal at index `n`
- **Single negative integer `-n`**: literal at position `count - n` (Python-style: `-1` = last, `-2` = second-to-last)
- **Vector `[n1 n2 ...]`**: selected indices (supports both positive and negative)
- **`:all`**: all literals get sliders

Negative indices are resolved after counting all literals. Out-of-range indices are silently ignored.

## UI

### Slider Panel

Display sliders in a panel inside `#repl-terminal`, inserted before `#repl-input-line`. Each slider row shows:
- Label (e.g. "circle: 15")
- `−` button (narrow range / more precise)
- Slider control
- `+` button (wider range)
- Current value display

### Zoom Buttons (Range Adjustment)

Each slider has `−` and `+` buttons to adjust the slider range:

- **`−` (narrower)**: Halves the range, re-centers on the current value, halves the step size (min step: 0.01). Useful for fine-tuning.
- **`+` (wider)**: Doubles the range, re-centers on the current value, doubles the step size. Useful for exploring larger values.

Both buttons re-center the range on the current slider value, so the slider stays centered after zooming. The step size adjusts proportionally.

### Index Map (on first run)

When `tweak` first evaluates, print the index map to REPL output:
```
tweak: 4 numeric literals found
  0: (circle 15)  → 15
  1: (f 30)       → 30
  2: (th 90)      → 90
  3: (f 20)       → 20
```

This helps the user know which indices to use for selective tweaking.

### Confirm/Cancel

- **OK button**: replaces numeric literals in the expression with final slider values, prints the final expression to REPL output for copy/paste. Clears temporary visualization.
- **Cancel button** (or Escape): discards changes, clears temporary visualization.

## Re-evaluation on Slider Change

When any slider value changes:

1. Build a new expression by substituting the changed numeric literal(s) into the original AST
2. Evaluate the modified expression using `sci/eval-string` in the existing SCI context
3. Clear previous temporary visualization
4. Display new result based on type (mesh/shape/path)

This is fast because:
- The SCI context already has all definitions loaded
- Only one expression is evaluated
- No turtle state mutation (the expression is pure in typical usage)

**Important**: The turtle state is saved before the first evaluation and restored before each re-evaluation, so the visualization always starts from the same turtle pose.

Slider changes are debounced (~100ms) to avoid excessive re-evaluation.

## Temporary Visualization (Preview System)

Preview objects are managed outside the scene registry to avoid polluting registered meshes:

- **`viewport/show-preview!`**: Creates temporary Three.js objects (Mesh, stamp mesh, or LineSegments) and adds them directly to `world-group`. Stores references in a `preview-objects` atom. Clears previous preview first.
- **`viewport/clear-preview!`**: Removes all preview objects from `world-group`, disposes their geometry and materials, resets the atom.

This ensures clean removal on OK, Cancel, or auto-cancel (new REPL command while tweak is active).

For each result type:
- **Mesh** → `create-three-mesh` → add to `world-group`
- **Shape** → `turtle/stamp-debug` on saved turtle state → extract stamp data → `create-stamp-mesh` → add to `world-group`
- **Path** → `turtle/run-path` on saved turtle → extract `:geometry` lines → `create-line-segments` → add to `world-group`

## Implementation

### Files

| File | What |
|------|------|
| `src/ridley/editor/test_mode.cljs` | Core logic: AST walk, labels, filter resolution, substitution, slider UI with zoom buttons, re-eval, confirm/cancel |
| `src/ridley/viewport/core.cljs` | `show-preview!`, `clear-preview!` — preview system with `preview-objects` atom |
| `src/ridley/editor/macros.cljs` | `tweak` macro definition |
| `src/ridley/editor/bindings.cljs` | `'tweak-start!` → `test-mode/start!` binding |
| `src/ridley/editor/state.cljs` | `sci-ctx-ref` atom — shared SCI context reference (avoids circular dependency) |
| `src/ridley/core.cljs` | Auto-cancel tweak mode on new REPL eval |
| `public/css/style.css` | Slider panel styles |

### Circular Dependency Resolution

`test_mode.cljs` needs access to the SCI context for re-evaluation, but `repl.cljs → bindings.cljs → test_mode.cljs` would create a circular dependency if `test_mode` required `repl`. Instead:
- `state.cljs` holds `sci-ctx-ref` atom
- `repl.cljs` stores the context there on creation/reset
- `test_mode.cljs` reads from `@state/sci-ctx-ref`

### Turtle State Management

```clojure
;; On tweak-start!:
(def saved-turtle @turtle-atom)

;; Before each re-evaluation:
(reset! turtle-atom saved-turtle)

;; On confirm/cancel:
(reset! turtle-atom saved-turtle)
```

## Testing

### Manual test cases

```clojure
;; 1. Mesh test — default: slider only for first literal (15)
(tweak (extrude (circle 15) (f 30)))

;; 2. Mesh test — all sliders
(tweak :all (extrude (circle 15) (f 30)))

;; 3. Shape test — slider for 20
(tweak (circle 20))

;; 4. Path test — default: slider only for first literal (30)
(tweak (path (f 30) (th 45) (f 20)))

;; 5. Specific index
(tweak 2 (extrude (circle 15) (f 30) (th 90) (f 20)))
;; Only slider for 90

;; 6. Negative index — last literal
(tweak -1 (extrude (circle 15) (f 30) (th 90) (f 20)))
;; Only slider for 20

;; 7. Multiple indices with negatives
(tweak [0 -1] (extrude (circle 15) (f 30) (th 90) (f 20)))
;; Sliders for 15 and 20

;; 8. Complex expression
(register m (box 50 50 10))
(tweak :all (warp m (attach (sphere 15) (f 10)) (inflate 3)))
;; Sliders for 15, 10, 3
```

## Notes

- `tweak` only works in REPL (not in definitions panel). It enters an interactive mode that must be resolved (OK/Cancel) before the next REPL command.
- Only one `tweak` session can be active at a time. Starting a new one cancels the previous.
- A new REPL command while tweak is active auto-cancels it.
- The slider panel is dismissible and does not block the viewport.
- Debounce slider changes (~100ms) to avoid excessive re-evaluation.
- If evaluation throws an error, the error is logged but sliders remain active.
