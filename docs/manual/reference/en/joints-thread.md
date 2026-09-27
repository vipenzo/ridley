---
name: joints-thread
category: positioning-assembly
since: ""
status: stable
---

# joints-thread

## Signature

`(joints/thread m r l t & {:keys [pitch h sense radial]})`

## Description

Part of the built-in **joints** library (activate it in the libraries
panel; functions are called with the `joints/` prefix). Every joint cuts
the mesh at the turtle's current pose — plane = position, normal = heading,
exactly like `mesh-split` — and builds the joint on the joint axis =
heading, centred on the position, straddling the cut plane. It returns
`{:a mesh :b mesh :extras [mesh …]}`: `:a` is the `:ahead` side and carries
the male part, `:b` the `:behind` side with the female part, `:extras` the
pieces to print separately.

`thread` is the square thread: a core of radius `r` with a helical tooth
of height `h` on `:a`, a threaded bore in `:b`. The helix is built with
the turtle's own constant-curvature, constant-torsion steps (`f`, `th`,
`tr`), so `extrude` follows it exactly.

The two clearances are separate because they are two different physical
facts: `t` opens the tooth **flanks** (what jams on a printer), `:radial`
opens the bore and the tooth crest (what lets the pieces drift sideways —
a square thread does not self-centre).

## Parameters

- `m` — the mesh to cut.
- `r` — core radius.
- `l` — total male length; half protrudes.
- `t` — flank clearance per side. **Printed 2026-09-28: 0.25 would not
  screw in, 0.5 did.**
- `:pitch` — thread pitch (default `2`).
- `:h` — tooth height (default `1`).
- `:sense` — `1` right-handed (verified), `-1` left-handed.
- `:radial` — radial clearance, bore and crest (default `0.25`, the
  value that kept the bayonet aligned).

## Example

```clojure
(def ab (joints/thread (box 30 30 30) 8 24 0.5 :pitch 2.5))
(register A (:a ab))
(register B (:b ab))
(mesh-board {:A A :B B}
            {:solid true
             :views [[:section {:position [0 0 0] :heading [0 0 1] :up [1 0 0]}]]})
```

## Notes

- Section it *along* the axis (a pose whose heading is perpendicular to
  the joint), not on the cut plane: the cut plane only shows circles.

## See also

- **Related:** `joints-bayonet`, `joints-tenon`, `extrude`, `path`
