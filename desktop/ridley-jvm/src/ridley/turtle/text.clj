(ns ridley.turtle.text
  "Text to 2D shape conversion using java.awt font rendering.

   Converts text strings to 2D shapes that can be extruded with the turtle system.
   Uses java.awt.Font for glyph outline extraction via PathIterator.

   Usage:
   (text-shape \"Hello\")                     ; uses default font
   (text-shape \"Hello\" :size 20)            ; with size
   (text-shape \"Hello\" :font loaded-font)   ; with custom font
   (load-font! \"/path/to/font.ttf\")         ; load a TTF/OTF file"
  (:require [ridley.turtle.shape :as shape])
  (:import [java.awt Font]
           [java.awt.font FontRenderContext GlyphVector]
           [java.awt.geom PathIterator AffineTransform]))

;; ============================================================
;; Font management
;; ============================================================

(defonce ^:private font-cache (atom {}))
(defonce ^:private default-font (atom nil))

(def ^:private ^FontRenderContext frc
  (FontRenderContext. (AffineTransform.) (boolean true) (boolean true)))

(defn load-font!
  "Load a font from a TTF/OTF file path. Caches by path.
   Returns the java.awt.Font object."
  [path]
  (if-let [cached (get @font-cache path)]
    cached
    (let [f (Font/createFont Font/TRUETYPE_FONT (java.io.File. path))]
      (swap! font-cache assoc path f)
      f)))

(defn init-default-font!
  "Initialize the default font. Tries bundled Roboto, falls back to system sans-serif."
  []
  (let [candidates ["public/fonts/Roboto-Regular.ttf"
                     "fonts/Roboto-Regular.ttf"
                     "../public/fonts/Roboto-Regular.ttf"]
        font (or (some (fn [p]
                         (let [f (java.io.File. p)]
                           (when (.exists f)
                             (try (load-font! p) (catch Exception _ nil)))))
                       candidates)
                 ;; Fallback to system font
                 (Font. "SansSerif" Font/PLAIN 1))]
    (reset! default-font font)
    font))

(defn font-loaded?
  "Check if the default font is loaded."
  []
  (some? @default-font))

;; ============================================================
;; PathIterator → contours
;; ============================================================

(def ^:private ^:dynamic bezier-segments 8)

(defn- sample-quadratic
  "Sample quadratic bezier to line segments. Returns points (excluding start)."
  [[x0 y0] [x1 y1] [x2 y2] n]
  (for [i (range 1 (inc n))]
    (let [t (/ (double i) n)
          mt (- 1.0 t) mt2 (* mt mt) t2 (* t t)]
      [(+ (* mt2 x0) (* 2 mt t x1) (* t2 x2))
       (+ (* mt2 y0) (* 2 mt t y1) (* t2 y2))])))

(defn- sample-cubic
  "Sample cubic bezier to line segments. Returns points (excluding start)."
  [[x0 y0] [x1 y1] [x2 y2] [x3 y3] n]
  (for [i (range 1 (inc n))]
    (let [t (/ (double i) n)
          mt (- 1.0 t) mt2 (* mt mt) mt3 (* mt2 mt)
          t2 (* t t) t3 (* t2 t)]
      [(+ (* mt3 x0) (* 3 mt2 t x1) (* 3 mt t2 x2) (* t3 x3))
       (+ (* mt3 y0) (* 3 mt2 t y1) (* 3 mt t2 y2) (* t3 y3))])))

(defn- path-iterator->contours
  "Extract contours from a PathIterator.
   Each contour is a vector of [x y] points forming a closed loop."
  [^PathIterator pi]
  (let [coords (double-array 6)]
    (loop [contours []
           current []
           pos [0.0 0.0]]
      (if (.isDone pi)
        (if (seq current)
          (conj contours current)
          contours)
        (let [seg (.currentSegment pi coords)]
          (.next pi)
          (case seg
            ;; SEG_MOVETO = 0
            0 (let [p [(aget coords 0) (aget coords 1)]]
                (recur (if (seq current) (conj contours current) contours)
                       [p]
                       p))
            ;; SEG_LINETO = 1
            1 (let [p [(aget coords 0) (aget coords 1)]]
                (recur contours (conj current p) p))
            ;; SEG_QUADTO = 2
            2 (let [ctrl [(aget coords 0) (aget coords 1)]
                    end  [(aget coords 2) (aget coords 3)]
                    pts (sample-quadratic pos ctrl end bezier-segments)]
                (recur contours (into current pts) end))
            ;; SEG_CUBICTO = 3
            3 (let [c1  [(aget coords 0) (aget coords 1)]
                    c2  [(aget coords 2) (aget coords 3)]
                    end [(aget coords 4) (aget coords 5)]
                    pts (sample-cubic pos c1 c2 end bezier-segments)]
                (recur contours (into current pts) end))
            ;; SEG_CLOSE = 4
            4 (recur (if (seq current) (conj contours current) contours)
                     []
                     pos)
            ;; Unknown
            (recur contours current pos)))))))

;; ============================================================
;; Contour cleanup and classification
;; ============================================================

(defn- remove-near-duplicates
  "Remove consecutive near-duplicate points."
  [contour]
  (let [epsilon 1e-6
        near? (fn [[x1 y1] [x2 y2]]
                (and (< (Math/abs (- x1 x2)) epsilon)
                     (< (Math/abs (- y1 y2)) epsilon)))]
    (if (< (count contour) 2)
      contour
      (let [result (reduce (fn [acc pt]
                             (if (near? (peek acc) pt) acc (conj acc pt)))
                           [(first contour)]
                           (rest contour))]
        (if (and (> (count result) 1)
                 (near? (peek result) (first result)))
          (pop result)
          result)))))

(defn- contour-area
  "Signed area (shoelace). Positive = CCW, negative = CW."
  [contour]
  (let [n (count contour)]
    (/ (reduce + (for [i (range n)]
                   (let [[x1 y1] (nth contour i)
                         [x2 y2] (nth contour (mod (inc i) n))]
                     (- (* x1 y2) (* x2 y1)))))
       2.0)))


;; ============================================================
;; Public API
;; ============================================================

(defn text-shape
  "Create 2D shapes from a text string.

   Options:
   - :size            font size in units (default 10)
   - :font            java.awt.Font object (default: built-in)
   - :curve-segments   segments for Bezier curves (default 8)

   Returns a VECTOR of shapes, one per glyph.
   Shapes include holes for letters like O, A, B, etc.

   (text-shape \"Hello\")
   (text-shape \"Hi\" :size 20)
   (text-shape \"Hi\" :font (load-font! \"/path/to/font.ttf\"))"
  [text & {:keys [size font curve-segments]
           :or {size 10 curve-segments 8}}]
  (when-not @default-font (init-default-font!))
  (let [base-font (or font @default-font)
        ;; java.awt.Font sizes are in points; derive from base font at target size
        sized-font (.deriveFont ^Font base-font (float size))
        gv (.createGlyphVector sized-font frc text)]
    (binding [ridley.turtle.text/bezier-segments curve-segments]
      (loop [idx 0
             shapes []]
        (if (>= idx (.getNumGlyphs gv))
          shapes
          (let [outline (.getGlyphOutline gv idx)
                pi (.getPathIterator outline nil)
                contours (->> (path-iterator->contours pi)
                              (map remove-near-duplicates)
                              (filter #(> (count %) 2))
                              vec)
                ;; java.awt uses Y-down; flip to Y-up
                flipped (mapv (fn [c]
                                (mapv (fn [[x y]] [x (- y)]) c))
                              contours)
                ;; Classify by absolute area — largest is outer, rest are holes
                ;; (don't rely on winding direction after flip, just use size)
                areas (mapv (fn [c] {:contour c :abs-area (Math/abs (contour-area c))}) flipped)
                sorted (sort-by :abs-area > areas)
                largest-outer (when (seq sorted)
                                ;; Ensure outer is CCW
                                (let [c (:contour (first sorted))]
                                  (if (neg? (contour-area c)) (vec (reverse c)) c)))
                holes (when (> (count sorted) 1)
                        (mapv (fn [{:keys [contour]}]
                                ;; Ensure holes are CW
                                (if (pos? (contour-area contour))
                                  (vec (reverse contour))
                                  contour))
                              (rest sorted)))
                glyph-shape (when (and largest-outer (> (count largest-outer) 2))
                              (shape/make-shape largest-outer
                                                (cond-> {:centered? false
                                                         :preserve-position? true
                                                         :align-to-heading? true}
                                                  (seq holes) (assoc :holes holes))))]
            (recur (inc idx)
                   (if glyph-shape (conj shapes glyph-shape) shapes))))))))

(defn text-shapes
  "Create shapes one per character (for individual letter manipulation)."
  [text & {:keys [size font curve-segments]
           :or {size 10 curve-segments 8}}]
  (vec (keep (fn [ch]
               (let [shapes (text-shape (str ch) :size size :font font
                                        :curve-segments curve-segments)]
                 (first shapes)))
             (seq text))))

(defn char-shape
  "Create a shape from a single character."
  [ch & {:keys [size font curve-segments]
         :or {size 10 curve-segments 8}}]
  (first (text-shape (str ch) :size size :font font
                     :curve-segments curve-segments)))

(defn text-width
  "Calculate the width of text at given size."
  [text font size]
  (when-not @default-font (init-default-font!))
  (let [f (or font @default-font)
        sized (.deriveFont ^Font f (float size))
        gv (.createGlyphVector sized frc text)
        bounds (.getLogicalBounds gv)]
    (.getWidth bounds)))
