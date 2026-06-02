---
name: render-view
category: export
since: ""
status: stable
---

# render-view

## Signature

`(render-view direction)`
`(render-view direction & options)`

## Description

Render the current scene from a given direction and return a PNG data URL. The result is a string of the form `"data:image/png;base64,..."` that can be passed to `save-image`, embedded in HTML, or fed to any downstream consumer.

`direction` selects the camera placement. It can be one of the six axis-aligned orthographic keywords (`:front`, `:back`, `:left`, `:right`, `:top`, `:bottom`), the `:perspective` keyword for a 3/4 view, or a custom direction vector `[dx dy dz]` for arbitrary camera placement.

`render-view` is the building block for `render-all-views`, `save-views`, and the AI describe pipeline. Use it directly when you need a single image at a specific size or angle.

## Parameters

- `direction` — one of `:front`, `:back`, `:left`, `:right`, `:top`, `:bottom`, `:perspective`, or a custom direction vector `[dx dy dz]`.
- `:width` (option) — image width in pixels. Default `512`.
- `:height` (option) — image height in pixels. Default `512`.
- `:target` (option) — keyword name of a specific registered object to frame, or `nil` (default) for all visible meshes.

## Example

{{example: render-view-front}}

<!-- example-source: render-view-front -->
```clojure
(register part (mesh-difference (box 30) (cyl 6 35)))

(def img (render-view :front))
;; img is "data:image/png;base64,..."

(save-image img "part-front.png")
```
<!-- /example-source -->

The orthographic front view of a registered part is rendered and saved. The returned data URL is reusable; in this case it is forwarded to `save-image` for download.

## Variations

{{example: render-view-options}}

<!-- example-source: render-view-options -->
```clojure
(register obj (sphere 15))

(render-view :top
             :target :obj
             :width 1024
             :height 1024)
```
<!-- /example-source -->

Options override defaults: `:target` narrows the framing to a single object, `:width`/`:height` produce a higher-resolution PNG suitable for documentation or print.

{{example: render-view-custom-direction}}

<!-- example-source: render-view-custom-direction -->
```clojure
(register part (box 30))

(render-view [1 1 0.5])
;; Camera placed along the (1,1,0.5) direction, looking at the scene origin.
```
<!-- /example-source -->

A custom direction vector gives full control over camera placement. Useful for hero shots, oblique views, or matching an existing rendering pose.

## Notes

- Default image size is 512×512. Override per call with `:width` and `:height`.
- `:perspective` renders the same 3/4 view used in the viewport's default camera.
- `:target` accepts a registered name keyword. Without it, all currently visible meshes are framed together.
- The return value is a string. To turn it into a file, pass it to `save-image`; to ship many views at once, use `render-all-views` + `save-views`.

## See also

- **Related:** `render-all-views`, `render-slice`, `save-views`, `save-image`
