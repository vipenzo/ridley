---
name: save-views
category: export
since: ""
status: stable
---

# save-views

## Signature

`(save-views)`
`(save-views & options)`

## Description

Render all seven standard views and download them as a single ZIP archive. The archive contains one PNG per view (`front`, `back`, `left`, `right`, `top`, `bottom`, `perspective`), named with the configured prefix.

This is the convenience function for "I want all views of this object as files, right now". For the same images as data in memory, use `render-all-views`; for a single image, use `render-view`.

## Parameters

- `:target` (option) — keyword name of a specific registered object to frame, or `nil` (default) for all visible meshes.
- `:prefix` (option) — string used as the base of each filename inside the ZIP (e.g. `"cup-views"` produces `cup-views-front.png`, `cup-views-perspective.png`, …).

## Example

{{example: save-views-zip}}

<!-- example-source: save-views-zip -->
```clojure
(register cup
  (revolve (shape (f 20) (th -90) (f 30) (th -90) (f 15))))

(save-views :target :cup :prefix "cup-views")
;; Downloads a ZIP containing:
;;   cup-views-front.png
;;   cup-views-back.png
;;   cup-views-left.png
;;   cup-views-right.png
;;   cup-views-top.png
;;   cup-views-bottom.png
;;   cup-views-perspective.png
```
<!-- /example-source -->

Seven PNGs in one download, framed on a single registered object, with a custom filename prefix.

## Notes

- The seven views are the same set produced by `render-all-views`.
- Image dimensions follow the same defaults as `render-view`/`render-all-views`.
- The ZIP is offered as a single browser download; no native picker.

## See also

- **Related:** `render-view`, `render-all-views`
