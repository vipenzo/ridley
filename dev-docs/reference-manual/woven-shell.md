---
name: woven-shell
category: generative-operations
since: ""
status: stable
---

# woven-shell

## Signature

`(woven-shell shape-or-fn & {:keys [thickness mode strands width warp weft warp-width weft-width lift threshold fn cap-top cap-bottom]})`

## Description

Shape-fn that produces a hollow extrusion with a true 3D over/under
woven pattern. Unlike `shell` (which only modulates wall thickness),
`woven-shell` also shifts the wall **radially** at each point so threads
can pass in front of / behind each other. At crossings, both threads are
combined into a single thicker wall that encompasses both. Used with
`loft`, `bloft`, or `revolve`. Does not modify turtle state.

Two built-in modes: `:diagonal` (default; threads cross at 45°) and
`:orthogonal` (basket/wicker; warp and weft are perpendicular). A custom
`:fn` overrides both.

## Parameters

| Parameter | Default | Description |
|---|---|---|
| `shape-or-fn` | — | Base profile (shape or shape-fn). |
| `:thickness` | `2` | Wall thickness at full-thread position. |
| `:mode` | `:diagonal` | `:diagonal` or `:orthogonal`. Ignored when `:fn` is supplied. |
| `:strands` | `8` | Threads per direction (diagonal mode). |
| `:width` | `0.12` | Thread width as fraction of cell (diagonal mode). |
| `:warp` | `8` | Warp count (orthogonal mode). |
| `:weft` | `30` | Weft count (orthogonal mode). |
| `:warp-width` | `0.2` | Warp thread width (orthogonal mode). |
| `:weft-width` | `0.1` | Weft thread width (orthogonal mode). |
| `:lift` | `thickness/2` | Radial offset amplitude at crossings. |
| `:fn` | — | Custom `(fn [a t] -> {:thickness 0..1 :offset number})` overriding the built-in patterns. |
| `:threshold` | `0.05` | Thickness values below this snap to 0. |
| `:cap-top` | — | Same syntax as `shell`. |
| `:cap-bottom` | — | Same syntax as `shell`. |

## Example

{{example: woven-shell-diagonal}}

<!-- example-source: woven-shell-diagonal -->
```clojure
(register diag-weave (loft (woven-shell (circle 20 128) :thickness 3 :strands 8) (f 60)))
```
<!-- /example-source -->

Default diagonal weave: 8 strands per direction, threads visibly crossing
in front of / behind each other.

## Variations

{{example: woven-shell-orthogonal}}

<!-- example-source: woven-shell-orthogonal -->
```clojure
(register basket
  (loft (woven-shell (circle 20 128) :thickness 3
                     :mode :orthogonal :warp 8 :weft 40
                     :warp-width 0.2 :weft-width 0.08)
        (f 60)))
```
<!-- /example-source -->

Orthogonal mode produces a basket / wicker look: warp threads run along
the path, weft threads run around the circumference.

{{example: woven-shell-custom}}

<!-- example-source: woven-shell-custom -->
```clojure
(register undulating
  (loft (woven-shell (circle 20 128) :thickness 3
                     :fn (fn [a t] {:thickness 0.8
                                    :offset (* 0.5 (Math/sin (* a 4)))}))
        (f 50)))
```
<!-- /example-source -->

Custom function returning a map per vertex: full control over thickness
and radial offset.

{{example: woven-shell-composed}}

<!-- example-source: woven-shell-composed -->
```clojure
(register tapered-weave
  (loft (-> (circle 20 128) (woven-shell :thickness 3 :strands 6) (tapered :to 0.5))
        (f 50)))
```
<!-- /example-source -->

Compose with other shape-fns via `->` threading.

## Notes

- Use a high segment count on the base shape (`(circle r 128)`) — threads
  need enough samples to resolve clearly.
- At crossings the wall thickens to span both threads; this is built into
  the union step.
- For thickness-only modulation without radial offset, use `shell` with a
  `:style :weave` or `:fn`.

## See also

- **Guide:** placeholder → cap. 6 (Da funzioni matematiche a forme)
- **Related:** `shell`, `woven`, `loft`, `shape-fn`
