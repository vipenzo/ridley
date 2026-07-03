---
name: transform->
category: generative-operations
since: ""
status: stable
---

# transform->

## Signature

`(transform-> shape & steps)`
`(transform-> end-face & steps)`

## Description

Macro. Automates the chaining pattern of `extrude+` / `revolve+` / `loft+`:
takes an initial shape (or an end-face map from a previous chainable
operation) and a sequence of steps. Each step receives the shape and pose
from the previous step's end-face. All produced meshes are combined via
`mesh-union` into a single result. Does not modify turtle state.

Inside the macro, operations do **not** take a shape argument — it is
passed automatically from the previous end-face. `extrude+`, `revolve+`
and `loft+` are accepted as step forms. For a `loft+` step the injected
shape becomes the loft's **profile**, so the step is written with just its
transform — a partial combinator like `(tapered :to 0.6)`, a
`(fn [shape t] …)`, or a target shape — and the movements.

## Parameters

- `shape` — a 2D shape used as the initial profile, **or**
- `end-face` — an end-face map (`{:shape … :pose …}`) returned by a
  previous `extrude+` / `revolve+`.
- `steps` — one or more `(extrude+ …)` / `(revolve+ …)` / `(loft+ …)`
  forms. Each form omits the leading shape; the macro injects the previous
  end-face's shape automatically (for `loft+` it becomes the profile, so
  the step's first argument is the transform, not the shape).

## Example

{{example: transform-arrow-frame}}

<!-- example-source: transform-arrow-frame -->
```clojure
(register frame
  (transform-> (shape-difference (rect 40 40) (rect 30 30))
    (extrude+ (f 20))           ; straight segment
    (revolve+ 30 :pivot :left)  ; 30° corner bend
    (extrude+ (f 30))           ; straight again
    (revolve+ -30 :pivot :right)
    (extrude+ (f 20))))         ; final segment
```
<!-- /example-source -->

A rectangular tube that walks forward, bends 30°, walks more, bends back,
and stops. Each segment inherits the previous end-face automatically;
the result is a single mesh.

## Variations

{{example: transform-arrow-chain-from-end-face}}

<!-- example-source: transform-arrow-chain-from-end-face -->
```clojure
(def base (extrude+ frame-shape (f 10)))
(register result
  (mesh-union (:mesh base)
              (transform-> (:end-face base)
                (revolve+ 30 :pivot :left)
                (extrude+ (f 20)))))
```
<!-- /example-source -->

Continue from an existing end-face: pass `(:end-face base)` as the first
argument, then chain follow-up segments. Used to splice new geometry into
an in-progress build.

A `loft+` step morphs the profile along its segment before the next op
continues from the real end cross-section:

```clojure
(register spout
  (transform-> (circle 20)
    (loft+ (tapered :to 0.6) (f 30))   ; tapered run (partial form)
    (revolve+ 45 :pivot :left)         ; corner bend
    (extrude+ (f 20))))                ; straight tail
```

## Notes

- Only `(extrude+ …)`, `(revolve+ …)` and `(loft+ …)` are recognised as
  steps. Other forms are silently ignored by the macro.
- A `loft+` step takes a transform (partial combinator, `(fn [s t] …)`, or
  a target shape) where `extrude+` takes only movements — the profile is
  the injected shape. Its `:end-face` is the real last-ring section, so the
  next step continues without a seam crack.
- `:mark` is supported inside step forms (advanced feature): tag the
  end-face of that step with a name and optional `:cap` for later reuse.
- The base `extrude` / `revolve` / `loft` remain unchanged; they keep
  returning a bare mesh.

## See also

- **Related:** `extrude+`, `revolve+`, `loft+`, `mesh-union`
