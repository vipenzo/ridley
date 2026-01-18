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
;; Font management
;; ============================================================

;; Cache for loaded fonts: {url-or-key -> font-object}
(defonce ^:private font-cache (atom {}))

;; Default font (loaded at startup)
(defonce ^:private default-font (atom nil))

;; Built-in font paths
(def ^:private builtin-fonts
  {:roboto "/fonts/Roboto-Regular.ttf"
   :roboto-mono "/fonts/RobotoMono-Regular.ttf"})

(defn load-font!
  "Load a font file asynchronously. Returns a promise that resolves to the font.

   Usage:
   (load-font! \"/fonts/custom.ttf\")      ; load by URL
   (load-font! :roboto)                    ; load built-in font
   (load-font! :roboto-mono)               ; load monospace"
  [url-or-key]
  (let [url (if (keyword? url-or-key)
              (get builtin-fonts url-or-key)
              url-or-key)]
    (if-let [cached (get @font-cache url)]
      (js/Promise.resolve cached)
      (js/Promise.
       (fn [resolve reject]
         (opentype/load
          url
          (fn [err font]
            (if err
              (reject err)
              (do
                (swap! font-cache assoc url font)
                (resolve font))))))))))

(defn init-default-font!
  "Initialize the default bundled font. Returns a promise.
   Called at app startup."
  []
  (-> (load-font! :roboto)
      (.then (fn [font]
               (reset! default-font font)
               font))
      (.catch (fn [err]
                (js/console.error "Failed to load default font:" err)
                nil))))

(defn get-default-font
  "Get the default font if loaded, nil otherwise."
  []
  @default-font)

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
   - Flip Y axis (font Y is up, we want Y down for consistency)"
  [contour font-units-per-em target-size]
  (let [scale (/ target-size font-units-per-em)]
    (mapv (fn [[x y]]
            [(* x scale)
             (* (- y) scale)])  ; Flip Y
          contour)))

;; ============================================================
;; Public API
;; ============================================================

(defn glyph->contours
  "Extract contours from a single glyph.
   Returns vector of contours, each contour is a vector of [x y] points."
  [glyph font size & {:keys [curve-segments] :or {curve-segments 8}}]
  (let [path (.getPath glyph 0 0 size)
        commands (.-commands path)
        units-per-em (.-unitsPerEm font)]
    (->> (path-commands->contours commands curve-segments)
         (mapv #(normalize-contour % units-per-em size)))))

(defn char-shape
  "Create a shape from a single character.
   Returns the outer contour as a shape (ignores holes for now)."
  [char font size & {:keys [curve-segments] :or {curve-segments 8}}]
  (when font
    (let [glyph (.charToGlyph font char)
          contours (glyph->contours glyph font size :curve-segments curve-segments)]
      (when (seq contours)
        ;; Take the largest contour (outer boundary)
        (let [largest (apply max-key count contours)]
          (shape/make-shape largest {:centered? false}))))))

(defn text-shape
  "Create a 2D shape from a text string.

   Options:
   - :size - font size in units (default 10)
   - :font - font object (default: built-in Roboto)
   - :curve-segments - segments for Bezier curves (default 8)

   Returns a shape with all character outlines combined.
   Note: Currently returns only outer contours (no holes/counter-shapes)."
  [text & {:keys [size font curve-segments]
           :or {size 10 curve-segments 8}}]
  (let [font (or font @default-font)]
    (when font
      (let [;; Get all glyphs
            glyphs (.stringToGlyphs font text)
            units-per-em (.-unitsPerEm font)
            scale (/ size units-per-em)

            ;; Process each glyph with proper positioning
            all-points
            (loop [idx 0
                   x-offset 0
                   points []]
              (if (>= idx (.-length glyphs))
                points
                (let [glyph (aget glyphs idx)
                      path (.getPath glyph x-offset 0 size)
                      commands (.-commands path)
                      contours (path-commands->contours commands curve-segments)
                      ;; Get advance width for next character positioning
                      advance-width (* (.-advanceWidth glyph) scale)
                      ;; Normalize and flip Y
                      normalized-contours
                      (mapv (fn [contour]
                              (mapv (fn [[x y]]
                                      [x (- y)])  ; Flip Y
                                    contour))
                            contours)
                      ;; Add largest contour from this glyph
                      largest (when (seq normalized-contours)
                                (apply max-key count normalized-contours))]
                  (recur (inc idx)
                         (+ x-offset advance-width)
                         (if largest
                           (into points largest)
                           points)))))]
        (when (seq all-points)
          (shape/make-shape all-points {:centered? false}))))))

(defn text-shapes
  "Create multiple shapes, one per character.
   Useful for individual letter manipulation.

   Returns a vector of shapes, one for each character."
  [text & {:keys [size font curve-segments]
           :or {size 10 curve-segments 8}}]
  (let [font (or font @default-font)]
    (when font
      (let [chars (seq text)]
        (vec (keep #(char-shape (str %) font size :curve-segments curve-segments) chars))))))

(defn text-width
  "Calculate the width of text at given size."
  [text font size]
  (when font
    (let [glyphs (.stringToGlyphs font text)
          units-per-em (.-unitsPerEm font)
          scale (/ size units-per-em)]
      (reduce + (map #(* (.-advanceWidth %) scale) glyphs)))))

(defn font-loaded?
  "Check if the default font is loaded and ready."
  []
  (some? @default-font))
