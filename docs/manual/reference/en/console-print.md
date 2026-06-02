---
name: println
category: math
since: ""
status: stable
---

# console-print

## Signature

See the table below. All printers are variadic.

## Description

Debug-printing utilities. `println` / `print` / `prn` / `T` write to the REPL output panel inside Ridley itself — the text shows up next to the script in the editor. `log` writes to the browser devtools console and is intended for cases where the REPL is not in the loop (Tauri webview probing, side-effect tracing, etc.).

| Symbol | Signature | Description |
|--------|-----------|-------------|
| `println` | `(println & args)` | Print to the REPL output panel, space-separated, with a trailing newline |
| `print` | `(print & args)` | Like `println` but without the trailing newline |
| `prn` | `(prn & args)` | Print using `pr-str` (strings are quoted, data is re-readable) |
| `log` | `(log & args)` | Browser devtools `console.log` — does not appear in the REPL output |
| `T` | `(T label value)` | "Tap": prints `"label: value"` and returns `value` unchanged |

## Parameters

- `args` — any number of values; printed in order, space-separated for `println` / `print`.
- `label` (for `T`) — any value, usually a string or keyword; printed before the value.
- `value` (for `T`) — the expression's value; returned unchanged so `T` can be inserted mid-pipeline.

## Example

{{example: console-print-tap}}

<!-- example-source: console-print-tap -->
```clojure
;; T is a tap: it prints the label + value and returns the value unchanged,
;; so it can be inserted into any expression without changing the structure.
(register tower
  (-> (rect 20 20)
      (T "profile-after-rect")           ;; prints the shape, returns it
      (extrude (f 30))
      (T "mesh-after-extrude")))         ;; prints the mesh, returns it
```
<!-- /example-source -->

`T` shows the intermediate values of a threading pipeline without rewriting the expression. Each `T` line in the REPL output reports the labelled value at that point in the chain.

## Notes

- **`log` vs `math-log` — the common trap.** `log` here is the *console printer* (`js/console.log`), not a mathematical logarithm. The natural log lives in [math.md](math.md) under the name `math-log`. If a formula prints `NaN` where `ln` was expected, the binding got mixed up.
- `println` / `print` / `prn` route through Ridley's REPL capture, so their output appears next to the script. `log` does not — it goes to the host browser's devtools console only.
- `T` evaluates its `value` argument exactly once, so it is safe to wrap expensive expressions without recomputing them.
- For long-lived or structured output, prefer a `panel` + `out` / `append` instead of a flood of `println` calls.

## See also

- **Related:** `math` (specifically `math-log` — same name, different role), `panel`, `out`
