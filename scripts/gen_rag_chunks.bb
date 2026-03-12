#!/usr/bin/env bb
;; Generate src/ridley/ai/rag_chunks.cljs from dev-docs/Spec.md
;;
;; Usage: bb scripts/gen_rag_chunks.bb
;;
;; Splits Spec.md at ## headers, with large sections further split at ### headers.
;; Generates a CLJS namespace with a vector of chunk maps.

(require '[clojure.string :as str]
         '[babashka.fs :as fs])

(def spec-file "dev-docs/Spec.md")
(def output-file "src/ridley/ai/rag_chunks.cljs")

;; Sections that are large enough to split at ### level
(def split-at-subsections
  #{"Turtle Commands"         ;; 225 lines, 10+ subsections
    "Path Recording"          ;; 90 lines, 5 subsections
    "2D Shapes"               ;; 390 lines, 7 subsections
    "Generative Operations"   ;; 152 lines, 5 subsections
    "Scene Registry"          ;; 95 lines
    "Face Operations"         ;; 105 lines
    "Mesh Transformation Macros" ;; 76 lines
    "Animation System"})      ;; 160 lines

;; Italian/English keywords per section (manually curated for keyword fallback)
(def section-keywords
  {"Overview"                   ["overview" "evaluation" "SCI" "environment"]
   "Movement"                   ["forward" "f" "move" "avanti" "indietro" "backward"]
   "Rotation"                   ["th" "tv" "tr" "turn" "yaw" "pitch" "roll" "gira" "ruota"]
   "Arc Commands"               ["arc" "arc-h" "arc-v" "arco" "curve" "curva" "S-curve"]
   "Bezier Commands"            ["bezier" "bezier-to" "spline" "curve" "control-point"]
   "Bezier Along Path"          ["bezier-as" "bezier-along" "spline" "path"]
   "Pen Control"                ["pen" "on" "off" "drawing"]
   "Turtle Scope"               ["turtle" "scope" "branch" "isolation" "push" "pop"]
   "Preserve-Up Mode"           ["preserve-up" "up" "orientation" "banking"]
   "Reset"                      ["reset" "origin" "home"]
   "Resolution (Curve Quality)" ["resolution" "curve" "quality" "segments" "smooth"]
   "Anchors & Navigation"       ["anchor" "navigate" "mark" "goto" "anchor-at"]
   "Path Recording"             ["path" "record" "trajectory"]
   "Marks Inside Paths"         ["marks" "path" "mark-at" "named"]
   "Follow (Path Splicing)"     ["follow" "splice" "path" "compose"]
   "Quick Path"                 ["quick-path" "qp" "shorthand"]
   "Poly Path"                  ["poly-path" "polygon" "closed"]
   "Path Utilities"             ["path" "length" "reverse" "subdivide" "resample"]
   "2D Shapes"                  ["shape" "circle" "rect" "star" "polygon" "profile" "2D"]
   "Custom Shapes from Coordinates" ["make-shape" "coordinates" "points" "polygon"]
   "Custom Shapes from Turtle"  ["shape" "turtle" "custom" "profile" "2D"]
   "Shape Transformations"      ["scale" "rotate-shape" "offset" "transform" "translate"]
   "Shape Functions (shape-fn)" ["shape-fn" "tapered" "twisted" "noisy" "displaced" "heightmap" "woven" "fluted" "shell" "morphed" "rugged"]
   "Shape Preview (Stamp)"      ["stamp" "preview" "2D" "flat"]
   "2D Shape Booleans"          ["shape-union" "shape-difference" "shape-intersection" "offset-shape" "boolean" "2D"]
   "Voronoi Shell"              ["voronoi" "voronoi-shell" "organic" "pattern" "cells"]
   "3D Primitives"              ["box" "sphere" "cyl" "cone" "cylinder" "primitive" "cubo" "sfera" "cilindro"]
   "Generative Operations"      ["extrude" "loft" "revolve" "sweep" "generate"]
   "Extrude"                    ["extrude" "sweep" "estrusione" "profilo" "sezione"]
   "Extrude Closed"             ["extrude-closed" "torus" "ring" "anello" "closed"]
   "Loft"                       ["loft" "loft-n" "morph" "transform" "taper" "twist"]
   "Bloft (Bezier-safe Loft)"   ["bloft" "bezier" "loft" "smooth" "curve"]
   "Revolve"                    ["revolve" "lathe" "revolution" "vase" "spicchio" "tornio" "vaso"]
   "Boolean Operations"         ["mesh-union" "mesh-difference" "mesh-intersection" "boolean" "hole" "foro" "subtract" "sottrai"]
   "Cross-Section (Slice)"      ["slice" "cross-section" "sezione" "taglio" "plane"]
   "Convex Hull"                ["convex-hull" "hull" "involucro"]
   "Text Shapes"                ["text" "font" "text-shape" "lettering" "testo"]
   "Text on Path"               ["text-on-path" "text" "along" "path" "scritta"]
   "Scene Registry"             ["register" "get-mesh" "visible" "scene" "registry"]
   "Hierarchical Assemblies"    ["hierarchy" "assembly" "with-path" "nested" "group"]
   "Face Operations"            ["face" "list-faces" "highlight" "measure" "area"]
   "Face Highlighting"          ["face" "highlight" "flash" "select"]
   "Measurement"                ["measure" "distance" "angle" "bounds" "height" "width"]
   "Semantic Face Names (Primitives)" ["face" "name" "top" "bottom" "front" "back"]
   "Functional Face Operations" ["attach-face" "clone-face" "face"]
   "attach-face"                ["attach-face" "face" "position" "surface"]
   "clone-face"                 ["clone-face" "duplicate" "face" "copy"]
   "Spatial Deformation (warp)" ["warp" "bend" "twist" "taper" "deformation" "inflate" "squash"]
   "Preset Deformations"        ["preset" "warp" "bend" "twist" "taper"]
   "Viewport Control"           ["camera" "viewport" "show" "hide" "visibility"]
   "Camera"                     ["camera" "look-at" "orbit" "zoom"]
   "Visibility Toggles"         ["show" "hide" "visible" "toggle" "visibility"]
   "Color and Material"         ["color" "material" "emissive" "wireframe" "colore"]
   "Per-Mesh Color and Material" ["set-color" "set-material" "per-mesh" "material"]
   "Pure Color and Material on Mesh Values" ["with-color" "with-material" "pure" "functional"]
   "3D Panels (Text Billboards)" ["panel" "billboard" "text" "3D" "label"]
   "Mesh Transformation Macros" ["attach" "attach!" "move-to" "play-path" "transform"]
   "attach"                     ["attach" "functional" "place" "position" "transform"]
   "attach!"                    ["attach!" "modify" "in-place" "move" "position"]
   "play-path"                  ["play-path" "animate" "follow" "trajectory"]
   "move-to"                    ["move-to" "position" "go-to" "navigate"]
   "Lateral Movement"           ["lateral" "strafe" "sideways" "move-lateral"]
   "Animation System"           ["animation" "anim!" "animate" "keyframe" "motion"]
   "Defining Animations"        ["anim!" "define" "animation" "sequence"]
   "Span"                       ["span" "duration" "timing" "animation"]
   "Parallel Commands"          ["parallel" "simultaneous" "concurrent" "animation"]
   "Procedural Animations"      ["procedural" "animation" "code" "dynamic"]
   "Mesh Anchors"               ["anchor" "mesh" "attachment" "point"]
   "Target Linking"             ["target" "link" "follow" "tracking"]
   "Playback Control"           ["play" "pause" "stop" "loop" "playback"]
   "Easing"                     ["easing" "ease" "smooth" "acceleration" "deceleration"]
   "STL Export"                 ["STL" "export" "3D-print" "stampa" "file"]
   "Interactive Tweaking"       ["tweak" "interactive" "test" "adjust" "parametric"]
   "Registry-Aware Mode"        ["registry" "tweak" "interactive" "test-mode"]
   "Variables and Functions"    ["def" "defn" "let" "variable" "function" "math"]
   "Math Functions"             ["math" "sqrt" "sin" "cos" "PI" "pow" "abs"]
   "Complete Example"           ["example" "complete" "parametric" "torus"]
   "Parametric Torus with Anchors" ["torus" "parametric" "anchor" "example"]
   "Twisted Extrusion"          ["twist" "extrusion" "example" "twisted"]
   "Branching with Turtle Scopes" ["branch" "scope" "turtle" "tree" "example"]
   "View Capture and Export"    ["render" "capture" "export" "image" "screenshot"]
   "Rendering Views"            ["render" "view" "image" "screenshot"]
   "Cross-Section Rendering"    ["cross-section" "render" "slice" "view"]
   "Saving Images"              ["save" "image" "export" "file"]
   "AI Describe (Accessibility)" ["describe" "accessibility" "screen-reader" "AI"]
   "Session Commands"           ["describe" "session" "ai-ask" "end-describe"]
   "Workflow"                   ["describe" "workflow" "session"]
   "Requirements"               ["describe" "requirements" "provider" "multimodal"]
   "Not Yet Implemented"        ["future" "planned" "not-implemented"]
   "File Structure"             ["file" "structure" "project" "directory"]})

(defn title->id [title]
  (-> title
      str/lower-case
      (str/replace "!" "-bang")
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-|-$" "")))

(defn parse-sections
  "Parse Spec.md into sections. Returns [{:title :content :level}]"
  [text]
  (let [lines (str/split-lines text)]
    (loop [i 0
           sections []
           current-title nil
           current-level nil
           current-lines []]
      (if (>= i (count lines))
        ;; Flush last section
        (if current-title
          (conj sections {:title current-title
                          :level current-level
                          :content (str/trim (str/join "\n" current-lines))})
          sections)
        (let [line (nth lines i)
              h2-match (re-matches #"^## (.+)" line)
              h3-match (re-matches #"^### (.+)" line)]
          (cond
            ;; New ## section
            h2-match
            (let [title (second h2-match)
                  new-sections (if current-title
                                 (conj sections {:title current-title
                                                 :level current-level
                                                 :content (str/trim (str/join "\n" current-lines))})
                                 sections)]
              (recur (inc i) new-sections title 2 [line]))

            ;; New ### section (only if parent is in split-at-subsections)
            (and h3-match
                 current-title
                 (or (= current-level 3)
                     (split-at-subsections current-title)))
            (let [title (second h3-match)
                  new-sections (if current-title
                                 (conj sections {:title current-title
                                                 :level current-level
                                                 :content (str/trim (str/join "\n" current-lines))})
                                 sections)]
              (recur (inc i) new-sections title 3 [line]))

            ;; Regular line
            :else
            (recur (inc i) sections current-title current-level (conj current-lines line))))))))

(defn escape-cljs-string [s]
  (-> s
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")))

(defn generate-cljs [sections]
  (let [version (str (java.time.LocalDate/now))
        chunks (for [{:keys [title content]} sections
                     :let [id (title->id title)
                           kws (get section-keywords title [""])]]
                 (str "   {:id \"" id "\"\n"
                      "    :title \"" (escape-cljs-string title) "\"\n"
                      "    :keywords " (pr-str (vec kws)) "\n"
                      "    :content \"" (escape-cljs-string content) "\"}"))]
    (str "(ns ridley.ai.rag-chunks\n"
         "  \"Pre-compiled Spec.md chunks for RAG retrieval.\n"
         "   Generated by scripts/gen_rag_chunks.bb — do not edit manually.\")\n"
         "\n"
         "(def chunks-version \"" version "\")\n"
         "\n"
         "(def chunks\n"
         "  [" (str/join "\n" chunks) "])\n")))

;; Main
(let [text (slurp spec-file)
      sections (parse-sections text)
      ;; Filter out empty/trivial sections (< 5 lines of content)
      sections (filterv #(>= (count (str/split-lines (:content %))) 5) sections)
      cljs-code (generate-cljs sections)]
  (spit output-file cljs-code)
  (println (str "Generated " output-file " with " (count sections) " chunks"))
  (doseq [{:keys [title content level]} sections]
    (println (str "  " (if (= level 3) "  " "") title
                  " (" (count (str/split-lines content)) " lines)"))))
