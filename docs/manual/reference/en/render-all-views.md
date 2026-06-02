---
name: render-all-views
category: export
since: ""
status: stable
---

# render-all-views

## Signature

`(render-all-views)`
`(render-all-views & options)`

## Description

Render the seven standard views in one call and return a map keyed by direction. The keys are `:front`, `:back`, `:left`, `:right`, `:top`, `:bottom`, and `:perspective`; each value is a PNG data URL of the form `"data:image/png;base64,..."`.

This is the same set of images consumed internally by the AI describe pipeline. Call it directly when you want all seven views as data — for example to build a documentation gallery, to compare construction variants side by side, or to feed a custom downstream pipeline.

For a one-shot "download all seven as a ZIP" workflow, use `save-views` instead.

## Parameters

- `:width` (option) — image width in pixels. Default `512`.
- `:height` (option) — image height in pixels. Default `512`.
- `:target` (option) — keyword name of a specific registered object to frame, or `nil` (default) for all visible meshes.

## Example

{{example: render-all-views-map}}

<!-- example-source: render-all-views-map -->
```clojure
(register part (mesh-difference (box 30) (cyl 6 35)))

(def views (render-all-views :target :part))
;; views is a map:
;; {:front       "data:image/png;base64,..."
;;  :back        "data:image/png;base64,..."
;;  :left        "data:image/png;base64,..."
;;  :right       "data:image/png;base64,..."
;;  :top         "data:image/png;base64,..."
;;  :bottom      "data:image/png;base64,..."
;;  :perspective "data:image/png;base64,..."}

(save-image (:perspective views) "part-perspective.png")
(save-image (:front views)       "part-front.png")
```
<!-- /example-source -->

All seven views are rendered in one shot, then individual entries are saved.

## Notes

- Same options as `render-view`. They apply uniformly to every rendered view.
- Each call re-renders all seven views. If you only need one, prefer `render-view`.
- Output is a Clojure map of keyword → data URL. Iterate it with `doseq`, `map`, or destructure individual keys.

## See also

- **Related:** `render-view`, `save-views`
