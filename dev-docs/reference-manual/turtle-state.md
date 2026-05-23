---
name: get-turtle
category: math
since: ""
status: stable
---

# turtle-state

## Signature

See the table below. All accessors are nullary and read the current turtle state (or, for `last-mesh`, the most recently created mesh).

## Description

Introspection helpers that expose the turtle's current pose and a handful of related runtime values. Useful inside scripts that need to compute positions algorithmically — for example arranging copies, sampling poses along a path, or branching on whether the turtle is inside an `attach` scope.

| Symbol | Signature | Returns | Description |
|--------|-----------|---------|-------------|
| `get-turtle` | `(get-turtle)` | map | Full turtle state map (`:position`, `:heading`, `:up`, `:scale`, …) |
| `turtle-position` | `(turtle-position)` | `[x y z]` | Current position |
| `turtle-heading` | `(turtle-heading)` | `[x y z]` | Current heading unit vector |
| `turtle-up` | `(turtle-up)` | `[x y z]` | Current up unit vector |
| `attached?` | `(attached?)` | bool | `true` while inside an `attach` / `attach!` / `with-path` scope |
| `last-mesh` | `(last-mesh)` | mesh \| nil | Most recently created mesh (the value returned by the last primitive / op) |

## Parameters

None — all accessors are nullary.

## Example

{{example: turtle-state-hole-pattern}}

<!-- example-source: turtle-state-hole-pattern -->
```clojure
;; Sample turtle positions evenly around a circle, then place holes there
(defn hole-pattern [n radius]
  (for [i (range n)]
    (do
      (reset)
      (th (* i (/ 360 n)))
      (f radius)
      (turtle-position))))

(let [holes (hole-pattern 6 20)]
  (doseq [[i p] (map-indexed vector holes)]
    (reset)
    (register (keyword (str "hole-" i))
              (translate (cyl 2 5) p))))
```
<!-- /example-source -->

Each iteration resets the turtle, rotates by an evenly-spaced amount, walks `radius` units forward, and captures the resulting position with `turtle-position`. The collected `[x y z]` triples then drive a second pass that registers a small cylinder at each location.

## Notes

- `get-turtle` returns a snapshot — mutating the map does not affect the live turtle. Use the standard movement commands (`f`, `th`, `tv`, …) to change the turtle's actual state.
- `turtle-position`, `turtle-heading`, `turtle-up` are shortcuts for `(:position (get-turtle))`, `(:heading (get-turtle))`, `(:up (get-turtle))`.
- `attached?` is most often used by helper functions that want to refuse to run at the top level or, conversely, only when bound to a mesh.
- `last-mesh` is convenient for one-liners but fragile in larger scripts: the binding changes after every primitive call. Prefer `register` + a name when the value matters beyond the next expression.

## See also

- **Related:** `f`, `th`, `tv`, `attach`, `path`
