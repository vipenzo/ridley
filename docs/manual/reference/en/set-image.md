---
name: set-image
category: 2d-shapes
since: ""
status: stable
---

# set-image

## Signature

`(set-image shape path width offset-x offset-y)`

## Description

Attach a reference image to a 2D shape, to trace over it with paths and
beziers. The image becomes visible **only when the shape is `stamp`ed** — it
is UV-mapped onto the stamped polygon in the shape's own 2D frame, so it is
**clipped to the shape's outline**. Returns a new shape carrying the image
attribute; the input is untouched and turtle state is unchanged.

Because the image rides on the **shape attribute**, it survives the 2D
booleans (`shape-union` / `shape-difference` / `shape-intersection` /
`shape-xor`): the result keeps the first operand's image, and being clipped
to the outline, **only the fragment inside the resulting polygon is drawn**.

The image is a viewport aid only: it is **not exported**, not part of mesh
CSG, and (for now) does not survive `extrude` into the mesh.

**Desktop only.** The image bytes are read from disk through the Rust
server; on the web build nothing loads.

## Parameters

- `shape` — the 2D shape to annotate.
- `path` — absolute file path to an image (PNG / JPG / …).
- `width` — image width in the shape's **local 2D units**. This doubles as
  scale calibration: if a known feature should measure *N* units, pick
  `width` so it does (verify with the `ruler`). The height follows the
  image aspect ratio (no distortion).
- `offset-x`, `offset-y` — the image's **lower-left corner** in the shape's
  2D coordinates. These are raw shape coordinates: a centred
  `(rect 200 100)` spans `[-100,100]×[-50,50]`, so pass `(-100 -50)` to
  cover the whole rect.

## Example

<!-- example-source: set-image-basic :no-run :warning desktop-only -->
```clojure
;; Lay a photo flat over a centred rect (lower-left at the rect's corner),
;; then trace over the stamped image with paths/beziers.
(stamp (set-image (rect 200 100) "/Users/me/ref/photo.jpg" 200 -100 -50))
```
<!-- /example-source -->

## Variations

<!-- example-source: set-image-crop :no-run :warning desktop-only -->
```clojure
;; Clipped to the outline: intersect to keep only a fragment of the image.
(def board  (set-image (rect 200 100) "/Users/me/ref/photo.jpg" 200 -100 -50))
(def window (translate-shape (rect 50 20) 75 -40))   ; bottom-right region
(stamp (shape-intersection board window))            ; only that slice shows
```
<!-- /example-source -->

Intersecting the image-bearing board with a small window stamps just that
window's slice of the photo — handy for isolating one detail to trace.

## Notes

- Visible only via `stamp` (shapes are not otherwise rendered). Toggle with
  `show-stamps` / `hide-stamps` (see `stamp-visibility`).
- The texture loads asynchronously; the stamp appears blank for a moment,
  then fills in once the image is decoded.
- `offset-x` / `offset-y` are raw shape coordinates, not bounding-box
  fractions — mind the centring of built-in shapes like `rect` and
  `circle`.

## See also

- **Related:** `stamp`, `shape-intersection`, `shape-difference`,
  `translate-shape`, `ruler`
