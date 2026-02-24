(ns ridley.library.svg
  "SVG import: parse SVG strings into Ridley 2D shapes.

   Two layers:
   1. Runtime functions (SCI bindings): svg, svg-shape, svg-shapes
      - Used in library source code to parse embedded SVG data at eval time
   2. Code generation: generate-library-source
      - Used at import time to produce library source from an SVG file"
  (:require [clojure.string :as str]
            [ridley.turtle.shape :as shape]))

;; ============================================================
;; DOM cleanup tracking
;; ============================================================

(defonce ^:private svg-dom-elements (atom []))

(defn cleanup!
  "Remove all hidden SVG DOM elements created by (svg ...).
   Call on SCI context reset."
  []
  (doseq [el @svg-dom-elements]
    (when (.-parentNode el)
      (.remove el)))
  (reset! svg-dom-elements []))

;; ============================================================
;; SVG geometry element types
;; ============================================================

(def ^:private geometry-selector
  "CSS selector for SVG elements that have geometry (support getPointAtLength)."
  "path, polygon, polyline, rect, circle, ellipse, line")

;; ============================================================
;; Transform handling
;; ============================================================

(defn- get-ctm
  "Get the cumulative transform matrix from an SVG element to the SVG root.
   Returns [a b c d e f] or nil if no transform."
  [el svg-root]
  (try
    ;; getScreenCTM gives transform to screen; we want element→SVG root.
    ;; getCTM() returns the transformation matrix from element to nearest viewport.
    (let [ctm (.getCTM el)]
      (when ctm
        [(.-a ctm) (.-b ctm) (.-c ctm) (.-d ctm) (.-e ctm) (.-f ctm)]))
    (catch :default _ nil)))

(defn- apply-ctm
  "Apply a CTM [a b c d e f] to a point [x y].
   | a c e |   | x |   | ax + cy + e |
   | b d f | × | y | = | bx + dy + f |
   | 0 0 1 |   | 1 |   |      1      |"
  [[a b c d e f] [x y]]
  [(+ (* a x) (* c y) e)
   (+ (* b x) (* d y) f)])

;; ============================================================
;; SVG parsing — runtime function (SCI binding)
;; ============================================================

(defn ^:export svg
  "Parse an SVG string and return an opaque SVG data handle.
   Creates a hidden DOM element for geometry sampling."
  [svg-string]
  (let [container (.createElement js/document "div")]
    ;; Hide the container
    (set! (.-style.position container) "absolute")
    (set! (.-style.left container) "-9999px")
    (set! (.-style.top container) "-9999px")
    (set! (.-style.width container) "0")
    (set! (.-style.height container) "0")
    (set! (.-style.overflow container) "hidden")
    (set! (.-innerHTML container) svg-string)
    (.appendChild js/document.body container)
    ;; Track for cleanup
    (swap! svg-dom-elements conj container)
    ;; Find the SVG element
    (let [svg-el (.querySelector container "svg")
          ;; Parse viewBox
          vb (when svg-el
               (let [vb-str (.getAttribute svg-el "viewBox")]
                 (when (and vb-str (seq vb-str))
                   (mapv js/parseFloat (str/split (str/trim vb-str) #"[\s,]+")))))
          ;; Find all geometry elements
          elements (when svg-el
                     (let [nodes (.querySelectorAll svg-el geometry-selector)]
                       (vec (for [i (range (.-length nodes))]
                              (let [el (.item nodes i)]
                                {:index i
                                 :tag (str/lower-case (.-tagName el))
                                 :id (.getAttribute el "id")
                                 :element el})))))]
      {:type :svg-data
       :elements (or elements [])
       :viewBox vb
       :svg-root svg-el
       :dom-root container})))

(defn- find-element
  "Find an element in svg-data by :id or :index."
  [svg-data {:keys [id index]}]
  (cond
    id    (first (filter #(= id (:id %)) (:elements svg-data)))
    index (get (:elements svg-data) index)
    :else (first (:elements svg-data))))

(defn- sample-geometry
  "Sample points from an SVG geometry element using getPointAtLength.
   Returns vector of [x y] points."
  [el segments]
  (let [total (.getTotalLength el)
        n (max 4 segments)
        step (/ total n)]
    (vec (for [i (range n)]
           (let [pt (.getPointAtLength el (* i step))]
             [(.-x pt) (.-y pt)])))))

(defn- centroid
  "Calculate the centroid of a set of 2D points."
  [points]
  (let [n (count points)]
    (if (zero? n)
      [0 0]
      [(/ (reduce + (map first points)) n)
       (/ (reduce + (map second points)) n)])))

(defn- signed-area-2x
  "Compute 2× the signed area of a polygon. Positive = CCW, negative = CW."
  [points]
  (let [n (count points)]
    (reduce + (for [i (range n)]
                (let [[x1 y1] (nth points i)
                      [x2 y2] (nth points (mod (inc i) n))]
                  (- (* x1 y2) (* x2 y1)))))))

(defn ^:export svg-shape
  "Extract a 2D shape from parsed SVG data.

   Options:
   - :id \"path-id\"  - select element by SVG id attribute
   - :index N         - select element by position (0-based)
   - :segments 64     - number of sample points (default 64)
   - :scale 1.0       - scale factor
   - :center true     - center shape at origin (default true)
   - :flip-y true     - flip Y axis, SVG Y-down → Ridley Y-up (default true)"
  [svg-data & {:keys [id index segments scale center flip-y]
               :or {segments 64 scale 1.0 center true flip-y true}}]
  (when-not (and (map? svg-data) (= :svg-data (:type svg-data)))
    (throw (js/Error. "svg-shape expects svg-data from (svg \"...\"), got something else")))
  (let [entry (find-element svg-data {:id id :index index})]
    (when-not entry
      (throw (js/Error. (str "SVG element not found"
                             (when id (str " with id \"" id "\""))
                             (when index (str " at index " index))))))
    (let [el (:element entry)
          ;; Sample points in element's local coordinate space
          raw-points (sample-geometry el segments)
          ;; Apply cumulative transform (handles nested <g transform="...">)
          ctm (get-ctm el (:svg-root svg-data))
          transformed (if (and ctm (not= ctm [1 0 0 1 0 0]))
                        (mapv #(apply-ctm ctm %) raw-points)
                        raw-points)
          ;; Flip Y if requested (SVG Y-down → Ridley Y-up)
          flipped (if flip-y
                    (mapv (fn [[x y]] [x (- y)]) transformed)
                    transformed)
          ;; Scale
          scaled (if (not= scale 1.0)
                   (mapv (fn [[x y]] [(* x scale) (* y scale)]) flipped)
                   flipped)
          ;; Center at origin
          centered (if center
                     (let [[cx cy] (centroid scaled)]
                       (mapv (fn [[x y]] [(- x cx) (- y cy)]) scaled))
                     scaled)
          ;; Ensure CCW winding (outer contour convention in Ridley)
          final (if (neg? (signed-area-2x centered))
                  (vec (reverse centered))
                  centered)]
      (shape/make-shape final {:centered? center}))))

(defn ^:export svg-shapes
  "Extract all shapes from parsed SVG data. Returns a vector of shapes.

   Options are passed through to svg-shape for each element:
   - :segments 64, :scale 1.0, :center true, :flip-y true"
  [svg-data & {:keys [segments scale center flip-y]
               :or {segments 64 scale 1.0 center true flip-y true}}]
  (vec (for [entry (:elements svg-data)]
         (svg-shape svg-data
                    :index (:index entry)
                    :segments segments
                    :scale scale
                    :center center
                    :flip-y flip-y))))

;; ============================================================
;; SVG element discovery (for code generation at import time)
;; ============================================================

(defn- discover-elements
  "Parse an SVG string and return info about its geometry elements.
   Does NOT retain DOM — used only for code generation."
  [svg-string]
  (let [container (.createElement js/document "div")]
    (set! (.-style.display container) "none")
    (set! (.-innerHTML container) svg-string)
    (.appendChild js/document.body container)
    (let [svg-el (.querySelector container "svg")
          nodes (when svg-el (.querySelectorAll svg-el geometry-selector))
          elements (when nodes
                     (vec (for [i (range (.-length nodes))]
                            (let [el (.item nodes i)]
                              {:index i
                               :tag (str/lower-case (.-tagName el))
                               :id (.getAttribute el "id")}))))]
      (.remove container)
      (or elements []))))

;; ============================================================
;; Code generation
;; ============================================================

(defn- sanitize-symbol-name
  "Convert an SVG id to a valid Clojure symbol name."
  [id]
  (-> id
      (str/replace #"[^a-zA-Z0-9\-_]" "-")
      (str/replace #"-+" "-")
      (str/replace #"^-|-$" "")
      (str/lower-case)))

(defn- escape-svg-string
  "Escape an SVG string for embedding in ClojureScript source."
  [s]
  (-> s
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")
      (str/replace "\r" "")))

(defn- deduplicate-names
  "Given a seq of {:index :id :tag}, assign unique def names.
   Elements with IDs get sanitized ID names, others get path-N names.
   Duplicates get -2, -3, etc. suffixes."
  [elements]
  (loop [remaining elements
         used-names #{}
         result []]
    (if (empty? remaining)
      result
      (let [entry (first remaining)
            base-name (if (:id entry)
                        (sanitize-symbol-name (:id entry))
                        (str "path-" (:index entry)))
            ;; Ensure non-empty
            base-name (if (empty? base-name) (str "path-" (:index entry)) base-name)
            ;; Deduplicate
            final-name (if (contains? used-names base-name)
                          (loop [n 2]
                            (let [candidate (str base-name "-" n)]
                              (if (contains? used-names candidate)
                                (recur (inc n))
                                candidate)))
                          base-name)]
        (recur (rest remaining)
               (conj used-names final-name)
               (conj result (assoc entry :def-name final-name)))))))

(defn generate-library-source
  "Generate ClojureScript library source from an SVG string.
   Returns a source code string suitable for saving as a library."
  [svg-string]
  (let [elements (discover-elements svg-string)
        named-elements (deduplicate-names elements)
        escaped (escape-svg-string svg-string)]
    (str ";; Imported from SVG\n\n"
         "(def svg-data (svg \"" escaped "\"))\n"
         (if (empty? named-elements)
           "\n;; No geometry elements found in SVG\n"
           (str "\n"
                (str/join "\n"
                  (for [{:keys [def-name id index]} named-elements]
                    (if id
                      (str "(def " def-name " (svg-shape svg-data :id \"" id "\"))")
                      (str "(def " def-name " (svg-shape svg-data :index " index "))")))))))))

(defn filename->lib-name
  "Derive a library name from an SVG filename."
  [filename]
  (-> filename
      (str/replace #"\.svg$" "")
      (str/replace #"[^a-zA-Z0-9\-_]" "-")
      (str/replace #"-+" "-")
      (str/replace #"^-|-$" "")
      (str/lower-case)))
