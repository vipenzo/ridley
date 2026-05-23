---
name: save-image
category: export
since: ""
status: stable
---

# save-image

## Signature

`(save-image data-url "name.png")`

## Description

Download any PNG data URL to a file. Intended as the partner of `render-view` and `render-slice`, both of which return data URLs that need a separate step to land on disk.

`save-image` is format-agnostic about the content beyond the data URL convention: anything that is `"data:image/png;base64,..."` (or any other `data:image/*` MIME type) can be passed through, including images obtained outside the rendering pipeline.

## Parameters

- `data-url` — a string of the form `"data:image/png;base64,..."`. Typically the return value of `render-view`, `render-slice`, or a value from the map returned by `render-all-views`.
- `"name.png"` — the filename for the downloaded file.

## Example

{{example: save-image-from-render-view}}

<!-- example-source: save-image-from-render-view -->
```clojure
(register part (cyl 20 30))

(save-image (render-view :perspective) "part-perspective.png")
(save-image (render-slice :part :z 15) "part-slice-z15.png")
```
<!-- /example-source -->

The data URL from a single render call is piped directly into `save-image`. Two files land in the download folder: a perspective render and a cross-section.

## Notes

- The filename determines the download name only; format follows the data URL's MIME type. A `.png` extension matches the PNG content produced by `render-view` and `render-slice`.
- To download all seven standard views as one ZIP without scripting, use `save-views`.

## See also

- **Related:** `render-view`, `render-slice`
