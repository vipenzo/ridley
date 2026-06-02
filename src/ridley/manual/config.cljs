(ns ridley.manual.config
  "Build-time configuration for the manual.

   `new-manual?` is the cutover switch (brief §8). When true, the panel shows
   only the v1 manual — the guide chapters and the Reference browser — and the
   legacy content.cljs pages are hidden (they remain reachable by direct id as
   a transitional fallback until content.cljs is deleted).

   Defaults to true so dev already previews the clean v1 manual. The :release
   build pins it true via :closure-defines, so the cutover happens on release
   regardless of the dev default. To inspect the legacy manual in dev, build
   with :closure-defines {ridley.manual.config/new-manual? false}.")

(goog-define new-manual? true)
