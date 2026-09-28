---
name: joint-zone
category: positioning-assembly
since: ""
status: stable
---

# joint-zone

## Signature

`(joint-zone piece anchor & {:keys [inset]})`

## Description

The admissible zone for joints on a cut face: the face's outline(s)
shrunk by `:inset`, as a vector of 2D shapes in the anchor's frame
(X = right, Y = up), one per island. An island too narrow for the inset
disappears, so the vector can be empty. This is the region
`layout-anchors` spreads its anchors in; use it directly to inspect or
to place joints by hand.

`piece` and `anchor` are as in `layout-anchors`: a piece from
`mesh-split` and the name of a cut anchor on it. The outline is read a
hair inside the piece, because a slice coincident with the face itself
returns nothing.

## Parameters

- `:inset` — **required.** Pin radius plus wall; no default.

## Example

```clojure
(def halves (mesh-split (box 20 60 40)))
(joint-zone (:behind halves) :cut :inset 5)
;; => [<10×50 rectangle>]
```

## See also

- **Related:** `layout-anchors`, `shape-offset-all`, `slice-mesh`
