---
name: export-manual
category: export
since: ""
status: stable
---

# export-manual

## Signature

`(export-manual)`
`(export-manual-en)`
`(export-manual-it)`

## Description

Generate downloadable Markdown manuals from the online manual content. `export-manual-en` downloads the English manual (`Manual_en.md`); `export-manual-it` downloads the Italian one (`Manuale_it.md`); `export-manual` downloads both.

Output is text-only — no screenshots. For manuals with screenshots, use the Python script `scripts/export-manual.py` (see Notes), which renders the same content with embedded images.

## Example

{{example: export-manual-basic}}

<!-- example-source: export-manual-basic -->
```clojure
(export-manual-en)               ; downloads Manual_en.md
(export-manual-it)               ; downloads Manuale_it.md
(export-manual)                  ; downloads both
```
<!-- /example-source -->

Three forms of the same action: one per language, or both at once.

## Notes

- **Text only.** Browser-side export skips screenshots to keep the download fast and self-contained.
- **Screenshots via Python.** For the full illustrated manual, run the project script:
  ```bash
  python3 scripts/export-manual.py --lang en       # with screenshots
  python3 scripts/export-manual.py --no-images     # text only
  python3 scripts/export-manual.py --check         # non-regression test
  ```
- Files are offered as a browser download with the canonical filenames `Manual_en.md` and `Manuale_it.md`.
