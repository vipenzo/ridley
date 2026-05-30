(ns ridley.turtle.text
  "Text to 2D shape conversion using opentype.js.

   Converts text strings to 2D shapes that can be extruded with the turtle system.
   Uses opentype.js to parse font files and extract glyph outlines.

   Usage:
   (text-shape \"Hello\")                    ; uses default font
   (text-shape \"Hello\" :size 20)           ; with size
   (text-shape \"Hello\" :font loaded-font)  ; with custom font

   The resulting shape can be extruded like any other 2D shape:
   (extrude (text-shape \"Hi\" :size 30) (f 10))"
  (:require ["opentype.js" :as opentype]
            [ridley.turtle.shape :as shape]))

;; ============================================================
;; Font registry
;; ============================================================
;;
;; Fonts are looked up by keyword id from a synchronous registry. Built-in
;; ids (`:roboto`, `:roboto-mono`) are pre-loaded at startup. Custom fonts
;; are added by the Settings → Fonts panel (desktop only) and persist
;; across sessions via the filesystem.
;;
;; User-facing API: shape-producing functions accept `:font :some-id`.
;; Resolution is synchronous; an unregistered id throws a deterministic
;; error pointing the user to Settings → Fonts.

;; Registry: {id-keyword -> opentype-font-object}
(defonce ^:private font-registry (atom {}))

;; Display metadata: {id-keyword -> {:label "..." :builtin? bool :filename "..."}}
;; Used by the Settings panel for listing.
(defonce ^:private font-meta (atom {}))

(def ^:private builtin-font-specs
  [{:id :roboto      :label "Roboto Regular" :url "fonts/Roboto-Regular.ttf"}
   {:id :roboto-mono :label "Roboto Mono"    :url "fonts/RobotoMono-Regular.ttf"}])

(defn register-font!
  "Register `font` (an opentype.js Font object) under `id` (a keyword).
   `meta` is optional display metadata: `{:label \"...\" :builtin? bool :filename \"...\"}`."
  ([id font] (register-font! id font {}))
  ([id font meta]
   (swap! font-registry assoc id font)
   (swap! font-meta assoc id meta)
   font))

(defn parse-font-bytes
  "Parse an ArrayBuffer into an opentype Font object synchronously."
  [array-buffer]
  (opentype/parse array-buffer))

(defn unregister-font!
  "Remove a font id from the registry. No-op if id is not registered."
  [id]
  (swap! font-registry dissoc id)
  (swap! font-meta dissoc id))

(defn font-info
  "Return the metadata map for a registered font id, or nil."
  [id]
  (get @font-meta id))

(defn registered?
  "True if `id` (a keyword) is in the registry."
  [id]
  (contains? @font-registry id))

(defn list-registered-fonts
  "Return a vector of `{:id :label :builtin? :filename}` maps for all
   registered fonts, with built-ins first."
  []
  (let [entries (for [[id meta] @font-meta]
                  (assoc meta :id id))
        builtins (filter :builtin? entries)
        customs (->> entries
                     (remove :builtin?)
                     (sort-by :id))]
    (vec (concat (sort-by :id builtins) customs))))

(defn resolve-font
  "Resolve `font-arg` to an opentype font object.

   Accepts:
   - `nil`        → resolves to `:roboto` (the default).
   - a keyword id → synchronous lookup in the registry; throws if missing.
   - a font object (anything non-keyword) → passthrough (for internal callers
     that already hold the resolved object).

   Errors are deterministic: an unregistered id throws immediately. There
   is no \"loading, please retry\" path — fonts are loaded once at startup
   or via the Settings panel."
  [font-arg]
  (cond
    (nil? font-arg)
    (or (get @font-registry :roboto)
        (throw (js/Error. "Default font :roboto is not yet loaded. Wait for app startup to finish.")))

    (keyword? font-arg)
    (or (get @font-registry font-arg)
        (throw (js/Error. (str "Font id " (pr-str font-arg) " is not registered. "
                               "Add it in Settings → Fonts."))))

    :else font-arg))

(defn- fetch-font-url
  "Fetch and parse a font from a URL. Returns a Promise resolving to an
   opentype Font object."
  [url]
  (js/Promise.
   (fn [resolve reject]
     (opentype/load
      url
      (fn [err font]
        (if err (reject err) (resolve font)))))))

(defn init-builtin-fonts!
  "Fetch and register the bundled built-in fonts. Returns a Promise that
   resolves once all built-ins are in the registry."
  []
  (-> (js/Promise.all
       (clj->js
        (for [{:keys [id label url]} builtin-font-specs]
          (-> (fetch-font-url url)
              (.then (fn [font]
                       (register-font! id font
                                       {:label label
                                        :builtin? true
                                        :filename url})
                       font))))))
      (.catch (fn [err]
                (js/console.error "Failed to load built-in fonts:" err)
                nil))))

;; Backwards-compatible alias for callers still using the old name during
;; the refactor. New code should use `init-builtin-fonts!`.
(def init-default-font! init-builtin-fonts!)

;; ============================================================
;; Bezier curve flattening
;; ============================================================

(defn- lerp
  "Linear interpolation between two 2D points."
  [[x1 y1] [x2 y2] t]
  [(+ x1 (* t (- x2 x1)))
   (+ y1 (* t (- y2 y1)))])

(defn- quadratic-bezier-point
  "Calculate point on quadratic Bezier curve at parameter t.
   p0 = start, p1 = control, p2 = end"
  [p0 p1 p2 t]
  (let [t2 (- 1 t)
        a (* t2 t2)
        b (* 2 t2 t)
        c (* t t)]
    [(+ (* a (first p0)) (* b (first p1)) (* c (first p2)))
     (+ (* a (second p0)) (* b (second p1)) (* c (second p2)))]))

(defn- cubic-bezier-point
  "Calculate point on cubic Bezier curve at parameter t.
   p0 = start, p1 = control1, p2 = control2, p3 = end"
  [p0 p1 p2 p3 t]
  (let [t2 (- 1 t)
        a (* t2 t2 t2)
        b (* 3 t2 t2 t)
        c (* 3 t2 t t)
        d (* t t t)]
    [(+ (* a (first p0)) (* b (first p1)) (* c (first p2)) (* d (first p3)))
     (+ (* a (second p0)) (* b (second p1)) (* c (second p2)) (* d (second p3)))]))

(defn- flatten-quadratic
  "Flatten a quadratic Bezier curve to line segments."
  [p0 p1 p2 segments]
  (for [i (range 1 (inc segments))]
    (quadratic-bezier-point p0 p1 p2 (/ i segments))))

(defn- flatten-cubic
  "Flatten a cubic Bezier curve to line segments."
  [p0 p1 p2 p3 segments]
  (for [i (range 1 (inc segments))]
    (cubic-bezier-point p0 p1 p2 p3 (/ i segments))))

;; ============================================================
;; Glyph path to points conversion
;; ============================================================

(defn- remove-consecutive-duplicates
  "Remove consecutive duplicate points from a contour.
   Also removes near-duplicate points (within epsilon distance)."
  [contour]
  (let [epsilon 1e-6
        near-equal? (fn [[x1 y1] [x2 y2]]
                      (and (< (Math/abs (- x1 x2)) epsilon)
                           (< (Math/abs (- y1 y2)) epsilon)))]
    (if (< (count contour) 2)
      contour
      (loop [result [(first contour)]
             remaining (rest contour)]
        (if (empty? remaining)
          ;; Also check if last point equals first point
          (if (and (> (count result) 1)
                   (near-equal? (last result) (first result)))
            (vec (butlast result))
            (vec result))
          (let [prev (last result)
                curr (first remaining)]
            (if (near-equal? prev curr)
              (recur result (rest remaining))
              (recur (conj result curr) (rest remaining)))))))))

(defn- path-commands->contours
  "Convert opentype.js path commands to contours (list of point lists).
   Each contour is a closed loop of points."
  [commands curve-segments]
  (loop [cmds (seq commands)
         current-contour []
         current-pos nil
         contours []]
    (if-not cmds
      ;; End of commands - finalize last contour
      (if (seq current-contour)
        (conj contours current-contour)
        contours)
      (let [cmd (first cmds)
            cmd-type (.-type cmd)]
        (case cmd-type
          ;; Move: start new contour
          "M" (let [new-pos [(.-x cmd) (.-y cmd)]]
                (recur (next cmds)
                       [new-pos]
                       new-pos
                       (if (seq current-contour)
                         (conj contours current-contour)
                         contours)))

          ;; Line: add endpoint
          "L" (let [new-pos [(.-x cmd) (.-y cmd)]]
                (recur (next cmds)
                       (conj current-contour new-pos)
                       new-pos
                       contours))

          ;; Quadratic Bezier: flatten and add points
          "Q" (let [p1 [(.-x1 cmd) (.-y1 cmd)]
                    p2 [(.-x cmd) (.-y cmd)]
                    points (flatten-quadratic current-pos p1 p2 curve-segments)]
                (recur (next cmds)
                       (into current-contour points)
                       p2
                       contours))

          ;; Cubic Bezier: flatten and add points
          "C" (let [p1 [(.-x1 cmd) (.-y1 cmd)]
                    p2 [(.-x2 cmd) (.-y2 cmd)]
                    p3 [(.-x cmd) (.-y cmd)]
                    points (flatten-cubic current-pos p1 p2 p3 curve-segments)]
                (recur (next cmds)
                       (into current-contour points)
                       p3
                       contours))

          ;; Close path: finalize contour
          "Z" (recur (next cmds)
                     []
                     nil
                     (if (seq current-contour)
                       (conj contours current-contour)
                       contours))

          ;; Unknown command: skip
          (recur (next cmds) current-contour current-pos contours))))))

(defn- normalize-contour
  "Normalize a contour to the specified size.
   - Scale from font units to target size
   - Keep Y as-is (font Y up matches turtle up)"
  [contour font-units-per-em target-size]
  (let [scale (/ target-size font-units-per-em)]
    (mapv (fn [[x y]]
            [(* x scale)
             (* y scale)])
          contour)))

;; ============================================================
;; Contour classification (for holes)
;; ============================================================

(defn- contour-area
  "Calculate signed area of a 2D contour using shoelace formula.
   Positive = counter-clockwise (outer), negative = clockwise (hole)."
  [contour]
  (let [n (count contour)]
    (/ (reduce + (for [i (range n)]
                   (let [[x1 y1] (nth contour i)
                         [x2 y2] (nth contour (mod (inc i) n))]
                     (- (* x1 y2) (* x2 y1)))))
       2.0)))

(defn- classify-contours
  "Classify contours as outer (positive area) or holes (negative area).
   Returns {:outer [...] :holes [...]}."
  [contours]
  (let [classified (map (fn [c] {:contour c :area (contour-area c)}) contours)]
    {:outer (vec (map :contour (filter #(pos? (:area %)) classified)))
     :holes (vec (map :contour (filter #(neg? (:area %)) classified)))}))

(defn- point-in-contour?
  "Ray-casting point-in-polygon test. Returns true if [x y] is inside contour."
  [[x y] contour]
  (let [n (count contour)]
    (loop [i 0
           j (dec n)
           inside false]
      (if (>= i n)
        inside
        (let [[xi yi] (nth contour i)
              [xj yj] (nth contour j)
              crosses? (and (not= (> yi y) (> yj y))
                            (< x (+ xi (/ (* (- xj xi) (- y yi))
                                          (- yj yi)))))]
          (recur (inc i) i (if crosses? (not inside) inside)))))))

(defn- assign-holes-to-outers
  "For each outer contour, find which holes belong inside it.
   A hole belongs to the SMALLEST outer (by absolute area) that contains it,
   so nested glyphs (rare but possible) attribute correctly.
   Returns vector of {:outer contour :holes [...]}."
  [outers holes]
  (let [outer-areas (mapv (fn [o] {:contour o :area (Math/abs (contour-area o))}) outers)
        holes-with-target
        (mapv (fn [hole]
                (let [test-pt (first hole)
                      candidates (filter (fn [{:keys [contour]}]
                                           (point-in-contour? test-pt contour))
                                         outer-areas)]
                  ;; Pick smallest containing outer (innermost in case of nesting)
                  (when (seq candidates)
                    {:hole hole
                     :target (:contour (apply min-key :area candidates))})))
              holes)]
    (mapv (fn [outer]
            {:outer outer
             :holes (vec (keep (fn [{:keys [hole target]}]
                                 (when (identical? target outer) hole))
                               (remove nil? holes-with-target)))})
          outers)))

;; ============================================================
;; Public API
;; ============================================================

(defn glyph->contours
  "Extract contours from a single glyph.
   Returns vector of contours, each contour is a vector of [x y] points.
   Y is flipped (SVG Y-down → our Y-up) and points reversed to preserve winding."
  [glyph font size & {:keys [curve-segments] :or {curve-segments 8}}]
  (let [path (.getPath glyph 0 0 size)
        commands (.-commands path)
        units-per-em (.-unitsPerEm font)]
    (->> (path-commands->contours commands curve-segments)
         (map #(normalize-contour % units-per-em size))
         ;; Flip Y and reverse to preserve winding
         (map (fn [contour]
                (vec (reverse (mapv (fn [[x y]] [x (- y)]) contour)))))
         (map remove-consecutive-duplicates)
         (filter #(> (count %) 2))
         vec)))

(defn char-shape
  "Create a shape from a single character.
   `font` may be a registered keyword id (e.g. `:roboto-mono`), nil for
   the default `:roboto`, or an already-resolved opentype Font object.
   Returns the outer contour as a shape (ignores holes for now)."
  [char font size & {:keys [curve-segments] :or {curve-segments 8}}]
  (let [font (resolve-font font)]
    (when font
      (let [glyph (.charToGlyph font char)
            contours (glyph->contours glyph font size :curve-segments curve-segments)]
        (when (seq contours)
          ;; Take the largest contour (outer boundary)
          (let [largest (apply max-key count contours)]
            (shape/make-shape largest {:centered? false})))))))

(defn text-shape
  "Create 2D shapes from a text string.

   Options:
   - :size - font size in units (default 10)
   - :font - font object (default: built-in Roboto)
   - :curve-segments - segments for Bezier curves (default 8)

   Returns a VECTOR of shapes. Composite glyphs (lowercase i/j, accented
   letters, umlauts, etc.) produce multiple shapes — one per outer contour,
   with holes attributed to the containing outer.

   Use with extrude which combines multiple shapes into a single mesh:
   (extrude (text-shape \"Hi\" :size 30) (f 5))"
  [text & {:keys [size font curve-segments]
           :or {size 10 curve-segments 8}}]
  (let [font (resolve-font font)]
    (when font
      (let [glyphs (.stringToGlyphs font text)
            units-per-em (.-unitsPerEm font)
            scale (/ size units-per-em)]
        ;; Process each glyph with proper positioning
        (loop [idx 0
               x-offset 0
               shapes []]
          (if (>= idx (.-length glyphs))
            shapes
            (let [glyph (aget glyphs idx)
                  path (.getPath glyph x-offset 0 size)
                  commands (.-commands path)
                  contours (path-commands->contours commands curve-segments)
                  advance-width (* (.-advanceWidth glyph) scale)
                  ;; Flip Y and reverse points to preserve winding.
                  ;; opentype.js getPath uses SVG coords (Y-down), we need Y-up.
                  ;; Y flip inverts winding, reverse restores it.
                  normalized-contours
                  (->> contours
                       (map (fn [contour]
                              (vec (reverse (mapv (fn [[x y]] [x (- y)]) contour)))))
                       (map remove-consecutive-duplicates)
                       (filter #(> (count %) 2))
                       vec)
                  {:keys [outer holes]} (classify-contours normalized-contours)
                  ;; Composite glyphs (i, j, à, ä, ñ, ...) have multiple outers.
                  ;; Emit one shape per outer, attributing each hole to its container.
                  outer+holes (assign-holes-to-outers outer holes)
                  glyph-shapes (keep (fn [{:keys [outer holes]}]
                                       (when (> (count outer) 2)
                                         (shape/make-shape
                                          outer
                                          (cond-> {:centered? false
                                                   :preserve-position? true
                                                   :align-to-heading? true}
                                            (seq holes) (assoc :holes holes)))))
                                     outer+holes)]
              (recur (inc idx)
                     (+ x-offset advance-width)
                     (into shapes glyph-shapes)))))))))

(defn text-shapes
  "Create multiple shapes, one per character.
   Useful for individual letter manipulation.

   Returns a vector of shapes, one for each character."
  [text & {:keys [size font curve-segments]
           :or {size 10 curve-segments 8}}]
  (let [font (resolve-font font)]
    (when font
      (let [chars (seq text)]
        (vec (keep #(char-shape (str %) font size :curve-segments curve-segments) chars))))))

(defn text-width
  "Calculate the width of text at given size.
   `font` may be a registered keyword id (e.g. `:roboto-mono`) or nil for
   the default `:roboto`."
  [text font size]
  (let [font (resolve-font font)]
    (when font
      (let [glyphs (.stringToGlyphs font text)
            units-per-em (.-unitsPerEm font)
            scale (/ size units-per-em)]
        (reduce + (map #(* (.-advanceWidth %) scale) glyphs))))))

(defn text-metrics
  "Get metrics for text rendering: advance widths for each character.
   Returns vector of {:char c :advance-width w} maps."
  [text & {:keys [size font] :or {size 10}}]
  (let [font (resolve-font font)]
    (when font
      (let [glyphs (.stringToGlyphs font text)
            units-per-em (.-unitsPerEm font)
            scale (/ size units-per-em)]
        (vec (for [i (range (.-length glyphs))]
               (let [glyph (aget glyphs i)]
                 {:char (.charAt text i)
                  :advance-width (* (.-advanceWidth glyph) scale)})))))))

(defn ^:export text-glyph-data
  "Get raw glyph data for text extrusion.
   Returns vector of {:contours [...] :x-offset n :advance-width n} for each character.
   Contours are in 2D local coordinates (not yet transformed to 3D).

   This is used by extrude-text to create properly oriented 3D text."
  [text & {:keys [size font curve-segments]
           :or {size 10 curve-segments 8}}]
  (let [font (resolve-font font)]
    (when font
      (let [glyphs (.stringToGlyphs font text)
            units-per-em (.-unitsPerEm font)
            scale (/ size units-per-em)]
        (loop [idx 0
               x-offset 0
               result []]
          (if (>= idx (.-length glyphs))
            result
            (let [glyph (aget glyphs idx)
                  ;; Get path at origin (no offset) so contours are local
                  path (.getPath glyph 0 0 size)
                  commands (.-commands path)
                  contours (path-commands->contours commands curve-segments)
                  advance-width (* (.-advanceWidth glyph) scale)
                  ;; Flip Y and reverse to preserve winding (same as text-shape)
                  ;; opentype.js getPath uses SVG coords (Y-down), we need Y-up
                  normalized-contours
                  (->> contours
                       (map (fn [contour]
                              (vec (reverse (mapv (fn [[x y]] [x (- y)]) contour)))))
                       (map remove-consecutive-duplicates)
                       (filter #(> (count %) 2))
                       vec)]
              (recur (inc idx)
                     (+ x-offset advance-width)
                     (conj result {:char (.charAt text idx)
                                   :contours normalized-contours
                                   :x-offset x-offset
                                   :advance-width advance-width})))))))))
