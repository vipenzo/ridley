(ns ridley.manual.structure
  "Single source of truth for the v1 manual navigation tree.

   Holds the ordered guide chapters and the Reference taxonomy. Prose and
   example code live in the Markdown files on disk (one .md per chapter per
   language); this namespace only carries navigation metadata: id, slug,
   order, file, titles, language, cross-references.

   Content layout on disk (plan §2.3 / brief §4):

     docs/manual/guides/{it,en}/<file>.md       guides (IT source, EN translated)
     docs/manual/reference/{it,en}/<category>/   reference cards (EN source)

   At build/serve time the guides are copied under the public web root and
   fetched by ridley.manual.draft-renderer; the reference cards are compiled
   into ridley.manual.reference-index by scripts/build_reference_index.bb.")

;; ── Served location of the guide Markdown ─────────────────────
;;
;; Relative to the public web root. The renderer fetches
;;   (str guides-url-base \"/\" lang \"/\" file)
;; e.g. \"manual/guides/it/04-extrusion.md\".

(def ^:const guides-url-base "manual/guides")

(def ^:const source-lang
  "Authoring language for the guides. Used by the bidirectional fallback:
   a page missing in the requested language falls back here, and vice-versa."
  :it)

(def default-guide-langs
  "Languages in which the guides exist by default. At v1 only IT is written;
   EN requests fall back to IT. A chapter may override this with a :langs set
   once its translation lands. Driving the fallback from metadata (rather than
   from a fetch 404) is deliberate: SPA/Tauri hosts serve index.html with HTTP
   200 for missing files, so a status-based fallback would silently render the
   shell page instead of falling back."
  #{:it})

;; ── Guide chapters ────────────────────────────────────────────
;;
;; Ordered list. One entry per chapter = one .md page.
;;   :id    stable keyword for navigation/history
;;   :slug  published url slug (no NN- prefix)
;;   :order narrative/teaching order (chapter number)
;;   :file  Markdown filename (kept with NN- prefix for natural on-disk sort)
;;   :title {:it ... :en ...}  — :en optional, falls back to :it
;;
;; Only IT titles exist at v1; EN falls back. about-ridley stands in as the
;; chapter-1 intro (the dedicated \"Per iniziare\" tutorial is not yet written).

(def guide-chapters
  [{:id :ch-about :slug "about-ridley"             :order 1  :file "about-ridley.md"
    :title {:it "Ridley in breve"}}
   {:id :ch-02 :slug "modeling-with-primitives"    :order 2  :file "02-modeling-with-primitives.md"
    :title {:it "2. Modellare per primitive"}}
   {:id :ch-03 :slug "working-with-2d-shapes"       :order 3  :file "03-working-with-2d-shapes.md"
    :title {:it "3. Lavorare con le forme 2D"}}
   {:id :ch-04 :slug "extrusion"                    :order 4  :file "04-extrusion.md"
    :title {:it "4. Estrusione"}}
   {:id :ch-05 :slug "paths"                        :order 5  :file "05-paths.md"
    :title {:it "5. Path"}}
   {:id :ch-06 :slug "shape-fn"                     :order 6  :file "06-shape-fn.md"
    :title {:it "6. Da funzioni matematiche a forme"}}
   {:id :ch-07 :slug "mesh"                         :order 7  :file "07-mesh.md"
    :title {:it "7. Mesh"}}
   {:id :ch-08 :slug "assemblaggio"                 :order 8  :file "08-assemblaggio.md"
    :title {:it "8. Assemblaggio"}}
   {:id :ch-09 :slug "librerie"                     :order 9  :file "09-librerie.md"
    :title {:it "9. Librerie"}}
   {:id :ch-10 :slug "analizzare-e-misurare"        :order 10 :file "10-analizzare-e-misurare.md"
    :title {:it "10. Analizzare e misurare"}}
   {:id :ch-11 :slug "curve-avanzate"               :order 11 :file "11-curve-avanzate.md"
    :title {:it "11. Curve avanzate"}}
   {:id :ch-12 :slug "sdf"                           :order 12 :file "12-sdf.md"
    :title {:it "12. Lavorare con gli SDF"}}
   {:id :ch-13 :slug "testo"                        :order 13 :file "13-testo.md"
    :title {:it "13. Testo"}}
   {:id :ch-14 :slug "colore-e-materiali"           :order 14 :file "14-colore-e-materiali.md"
    :title {:it "14. Colore e materiali"}}
   {:id :ch-15 :slug "debug"                        :order 15 :file "15-debug.md"
    :title {:it "15. Mettere a fuoco e risolvere i problemi"}}
   {:id :ch-16 :slug "clojure-per-ridley"           :order 16 :file "16-clojure-per-ridley.md"
    :title {:it "16. Clojure per Ridley"}}
   {:id :ch-17 :slug "esportare-e-stampare"         :order 17 :file "17-esportare-e-stampare.md"
    :title {:it "17. Esportare e stampare"}}])

;; ── Reference taxonomy ────────────────────────────────────────
;;
;; Three sections (plan §2.2). Internals is hidden at v1: no internals/ cards
;; exist yet, so it is declared but not :visible? until they do.

(def reference-sections
  [{:id :functions    :label {:it "Funzioni"     :en "Functions"}    :order 1 :visible? true}
   {:id :clojure-core :label {:it "Clojure core" :en "Clojure core"} :order 2 :visible? true}
   {:id :internals    :label {:it "Internals"    :en "Internals"}    :order 3 :visible? false}])

;; Function categories, ordered by Spec.md (§2-17). The slug is the stable key
;; (matches `category` in the reference card frontmatter / reference-index);
;; the label is what the browser shows. Categories without cards simply render
;; empty / are skipped by the browser.

(def function-categories
  [{:slug "turtle-movement"          :order 2  :label {:en "Turtle"                       :it "Tartaruga"}}
   {:slug "path"                     :order 3  :label {:en "Paths"                        :it "Path"}}
   {:slug "2d-shapes"                :order 4  :label {:en "2D Shapes"                    :it "Forme 2D"}}
   {:slug "3d-primitives"            :order 5  :label {:en "3D Primitives"                :it "Primitive 3D"}}
   {:slug "generative-operations"    :order 6  :label {:en "Generative Operations"        :it "Operazioni generative"}}
   {:slug "mesh-operations"          :order 7  :label {:en "Mesh Operations"              :it "Operazioni su mesh"}}
   {:slug "faces"                    :order 8  :label {:en "Faces"                         :it "Facce"}}
   {:slug "positioning-assembly"     :order 9  :label {:en "Positioning & Assembly"        :it "Posizionamento e assemblaggio"}}
   {:slug "spatial-deformation"      :order 10 :label {:en "Spatial Deformation"           :it "Deformazione spaziale"}}
   {:slug "sdf-modeling"             :order 11 :label {:en "SDF Modeling"                  :it "Modellazione SDF"}}
   {:slug "text"                     :order 12 :label {:en "Text"                          :it "Testo"}}
   {:slug "registration-visibility"  :order 13 :label {:en "Scene"                         :it "Scena"}}
   {:slug "live-interactive"         :order 14 :label {:en "Live & Interactive"            :it "Live e interattivo"}}
   {:slug "ai-describe"              :order 15 :label {:en "AI Describe"                   :it "AI Describe"}}
   {:slug "export"                   :order 16 :label {:en "Export"                        :it "Esportazione"}}
   {:slug "math"                     :order 17 :label {:en "Variables, Functions & Math"   :it "Variabili, funzioni e matematica"}}])

;; ── Helpers ───────────────────────────────────────────────────

(defn ordered-chapters
  "Guide chapters in narrative order."
  []
  (sort-by :order guide-chapters))

(defn chapter-by-id [id]
  (some #(when (= (:id %) id) %) guide-chapters))

(defn chapter-by-slug [slug]
  (some #(when (= (:slug %) slug) %) guide-chapters))

(defn chapter-title
  "Title for a chapter in the requested language, falling back to the source
   language when the translation is missing."
  [chapter lang]
  (let [t (:title chapter)]
    (or (get t lang) (get t source-lang) (:slug chapter))))

(defn chapter-langs
  "Languages a chapter is available in (defaults to default-guide-langs)."
  [chapter]
  (or (:langs chapter) default-guide-langs))

(defn resolve-guide-lang
  "Resolve the language to actually load for a chapter: the requested one if
   available, else the source language, else any available one. Bidirectional
   fallback driven by metadata, not by a fetch 404 (see default-guide-langs)."
  [chapter lang]
  (let [avail (chapter-langs chapter)]
    (cond
      (contains? avail lang) lang
      (contains? avail source-lang) source-lang
      :else (first avail))))

(defn chapter-url
  "Served URL of a chapter's Markdown, resolving the language to one that
   actually exists (bidirectional fallback)."
  [chapter lang]
  (str guides-url-base "/" (name (resolve-guide-lang chapter lang)) "/" (:file chapter)))

(defn adjacent-chapter
  "Next/previous chapter (:next or :prev) relative to a chapter id, in order."
  [id direction]
  (let [chs (vec (ordered-chapters))
        idx (.indexOf (mapv :id chs) id)]
    (when (>= idx 0)
      (case direction
        :next (get chs (inc idx))
        :prev (when (pos? idx) (get chs (dec idx)))))))

(defn visible-reference-sections []
  (->> reference-sections (filter :visible?) (sort-by :order)))

(defn ordered-function-categories []
  (sort-by :order function-categories))

(defn category-label [slug lang]
  (when-let [c (some #(when (= (:slug %) slug) %) function-categories)]
    (let [l (:label c)]
      (or (get l lang) (get l :en) slug))))
