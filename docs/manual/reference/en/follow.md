---
name: follow
category: path
since: ""
status: stable
---

# follow

## Signature

`(follow path)` — available inside `path` / `extrude` / `loft` /
`attach` bodies.

## Description

Splice another path's recorded commands into the current recording.
`follow` is the recording-time counterpart of `follow-path`: where
`follow-path` walks a recorded path on the live turtle, `follow` copies
its commands into the path currently being built.

Use it to factor reusable trajectory fragments out of a larger path:
build a helper that returns a small path, then `follow` it from
wherever it should appear.

## Parameters

- `path` — a previously recorded path (a map with `:type :path`). If
  the value is not a path, the call is a no-op.

## Example

{{example: follow-basic}}

<!-- example-source: follow-basic -->
```clojure
(def segment (path (f 10) (mark :joint)))
(def full (path (f 20) (follow segment) (th 90) (f 10)))

(register chain (extrude (circle 1.2) full))
```
<!-- /example-source -->

The commands of `segment` (including its `mark`) are inlined into
`full` exactly where `(follow segment)` appears.

## Variations

{{example: follow-helper}}

<!-- example-source: follow-helper -->
```clojure
(defn arm [side depth mname]
  (path
    (side-trip
      (th (if (pos? side) 90 -90))
      (f (abs side))
      (tv -90) (f depth) (tv 90)
      (mark mname))))

(def skel
  (path
    (mark :pin-axis)
    (f 50)
    (follow (arm  27 37 :left))
    (follow (arm -27 37 :right))))

(register body (extrude (circle 1) skel))
```
<!-- /example-source -->

`follow` is the natural primitive for composing paths from reusable
helper functions: build the fragment, then splice it wherever needed.

## Notes

- `follow` splices the raw command list of the input path. Marks,
  side-trips, and nested commands are preserved exactly. The current
  path's resolution and joint-mode are NOT replaced.
- For executing a path on the live turtle (rather than splicing it into
  another recording), use `follow-path`.

## See also

- **Guide:** placeholder → cap. 5 (Paths and anchors)
- **Related:** `path`, `follow-path`, `side-trip`, `mark`
