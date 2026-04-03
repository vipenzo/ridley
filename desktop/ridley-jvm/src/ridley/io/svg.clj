(ns ridley.io.svg
  "SVG import for Ridley — extracts 2D shapes from SVG path elements.
   Converts SVG path commands (M, L, H, V, C, S, Q, T, A, Z) to Ridley shape format.
   Supports both absolute and relative commands, repeated implicit parameters,
   and arc conversion."
  (:require [clojure.xml :as xml]
            [clojure.string :as str]
            [ridley.turtle.shape :as shape]))

;; ── SVG Path Tokenizer ─────────────────────────────────────────

(defn- tokenize-path
  "Split SVG path d attribute into tokens (commands and numbers)."
  [d]
  (re-seq #"[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?" d))

(defn- take-numbers
  "Take n number tokens from the front of tokens seq. Returns [nums rest-tokens]."
  [tokens n]
  (loop [i 0, ts tokens, acc []]
    (if (>= i n)
      [acc ts]
      (if (empty? ts)
        [acc ts]
        (let [t (first ts)]
          (if (re-matches #"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?" t)
            (recur (inc i) (rest ts) (conj acc (Double/parseDouble t)))
            [acc ts]))))))

(defn- number-token? [t]
  (and t (re-matches #"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?" t)))

;; ── Bezier sampling ────────────────────────────────────────────

(defn- sample-cubic
  "Sample cubic bezier (cx,cy)→(x,y) with controls (x1,y1),(x2,y2). Returns points (excluding start)."
  [cx cy x1 y1 x2 y2 x y n]
  (for [i (range 1 (inc n))]
    (let [t (/ (double i) n)
          mt (- 1.0 t) mt2 (* mt mt) mt3 (* mt2 mt)
          t2 (* t t) t3 (* t2 t)]
      [(+ (* mt3 cx) (* 3 mt2 t x1) (* 3 mt t2 x2) (* t3 x))
       (+ (* mt3 cy) (* 3 mt2 t y1) (* 3 mt t2 y2) (* t3 y))])))

(defn- sample-quadratic
  "Sample quadratic bezier (cx,cy)→(x,y) with control (x1,y1). Returns points (excluding start)."
  [cx cy x1 y1 x y n]
  (for [i (range 1 (inc n))]
    (let [t (/ (double i) n)
          mt (- 1.0 t) mt2 (* mt mt) t2 (* t t)]
      [(+ (* mt2 cx) (* 2 mt t x1) (* t2 x))
       (+ (* mt2 cy) (* 2 mt t y1) (* t2 y))])))

;; ── Arc conversion (SVG arc → sampled points) ─────────────────

(defn- arc-endpoint-to-center
  "Convert SVG arc endpoint parameterization to center parameterization.
   Returns {:cx :cy :theta1 :dtheta :rx :ry} or nil if degenerate."
  [x1 y1 rx ry x-rotation large-arc-flag sweep-flag x2 y2]
  (let [rx (Math/abs rx) ry (Math/abs ry)]
    (when (and (pos? rx) (pos? ry)
               (not (and (== x1 x2) (== y1 y2))))
      (let [phi (Math/toRadians x-rotation)
            cos-phi (Math/cos phi) sin-phi (Math/sin phi)
            ;; Step 1: compute (x1', y1')
            dx (/ (- x1 x2) 2.0)
            dy (/ (- y1 y2) 2.0)
            x1p (+ (* cos-phi dx) (* sin-phi dy))
            y1p (+ (- (* sin-phi dx)) (* cos-phi dy))
            ;; Step 2: compute (cx', cy')
            x1p2 (* x1p x1p) y1p2 (* y1p y1p)
            rx2 (* rx rx) ry2 (* ry ry)
            ;; Ensure radii are large enough
            lambda (+ (/ x1p2 rx2) (/ y1p2 ry2))
            [rx ry rx2 ry2] (if (> lambda 1.0)
                              (let [sl (Math/sqrt lambda)
                                    rx (* rx sl) ry (* ry sl)]
                                [rx ry (* rx rx) (* ry ry)])
                              [rx ry rx2 ry2])
            num (- (* rx2 ry2) (* rx2 y1p2) (* ry2 x1p2))
            den (+ (* rx2 y1p2) (* ry2 x1p2))
            sq (Math/sqrt (max 0.0 (/ num den)))
            sign (if (= large-arc-flag sweep-flag) -1.0 1.0)
            cxp (* sign sq (/ (* rx y1p) ry))
            cyp (* sign sq (- (/ (* ry x1p) rx)))
            ;; Step 3: compute (cx, cy)
            cx (+ (* cos-phi cxp) (- (* sin-phi cyp)) (/ (+ x1 x2) 2.0))
            cy (+ (* sin-phi cxp) (* cos-phi cyp) (/ (+ y1 y2) 2.0))
            ;; Step 4: compute theta1 and dtheta
            angle-of (fn [ux uy vx vy]
                       (let [n (+ (* ux vx) (* uy vy))
                             d (* (Math/sqrt (+ (* ux ux) (* uy uy)))
                                  (Math/sqrt (+ (* vx vx) (* vy vy))))
                             cos-a (max -1.0 (min 1.0 (/ n d)))
                             a (Math/acos cos-a)]
                         (if (neg? (- (* ux vy) (* uy vx))) (- a) a)))
            theta1 (angle-of 1.0 0.0
                             (/ (- x1p cxp) rx)
                             (/ (- y1p cyp) ry))
            dtheta (angle-of (/ (- x1p cxp) rx) (/ (- y1p cyp) ry)
                              (/ (- (- x1p) cxp) rx) (/ (- (- y1p) cyp) ry))
            ;; Adjust dtheta for sweep direction
            dtheta (cond
                     (and (zero? sweep-flag) (pos? dtheta))
                     (- dtheta (* 2.0 Math/PI))
                     (and (pos? sweep-flag) (neg? dtheta))
                     (+ dtheta (* 2.0 Math/PI))
                     :else dtheta)]
        {:cx cx :cy cy :rx rx :ry ry :phi phi
         :theta1 theta1 :dtheta dtheta}))))

(defn- sample-arc
  "Sample an SVG arc to line segments. Returns points (excluding start)."
  [x1 y1 rx ry x-rotation large-arc-flag sweep-flag x2 y2 n]
  (if-let [{:keys [cx cy rx ry phi theta1 dtheta]}
           (arc-endpoint-to-center x1 y1 rx ry x-rotation
                                   large-arc-flag sweep-flag x2 y2)]
    (let [cos-phi (Math/cos phi) sin-phi (Math/sin phi)]
      (for [i (range 1 (inc n))]
        (let [t (/ (double i) n)
              theta (+ theta1 (* t dtheta))
              cos-t (Math/cos theta) sin-t (Math/sin theta)
              ;; Point on unit circle, scaled, rotated, translated
              px (+ (* cos-phi rx cos-t) (- (* sin-phi ry sin-t)) cx)
              py (+ (* sin-phi rx cos-t) (* cos-phi ry sin-t) cy)]
          [px py])))
    ;; Degenerate arc — just line to endpoint
    [[x2 y2]]))

;; ── SVG Path Parser ────────────────────────────────────────────

(def ^:private bezier-segments 8)
(def ^:private arc-segments 16)

(defn- parse-path-commands
  "Parse SVG path d string into a sequence of absolute [x y] points.
   Supports all SVG path commands: M, L, H, V, C, S, Q, T, A, Z.
   Handles repeated implicit parameters."
  [d]
  (let [tokens (vec (tokenize-path d))]
    (loop [i 0
           points []
           cx 0.0 cy 0.0      ;; current point
           sx 0.0 sy 0.0      ;; start of subpath
           last-cmd nil        ;; for implicit repeat
           ;; For S/T smooth continuation:
           last-ctrl-x 0.0 last-ctrl-y 0.0]
      (if (>= i (count tokens))
        points
        (let [tok (nth tokens i)
              ;; If current token is a number, repeat the last command
              [cmd consume-idx]
              (if (number-token? tok)
                ;; Implicit repeat: M→L, m→l, otherwise same command
                [(case last-cmd
                   "M" "L"
                   "m" "l"
                   last-cmd)
                 i]
                [tok (inc i)])]
          (case cmd
            ;; ── MoveTo ──────────────────────────────────────
            "M" (let [[nums _] (take-numbers (subvec tokens consume-idx) 2)
                      x (nth nums 0) y (nth nums 1)]
                  (recur (+ consume-idx 2) (conj points [x y])
                         x y x y "M" x y))
            "m" (let [[nums _] (take-numbers (subvec tokens consume-idx) 2)
                      x (+ cx (nth nums 0)) y (+ cy (nth nums 1))]
                  (recur (+ consume-idx 2) (conj points [x y])
                         x y x y "m" x y))

            ;; ── LineTo ──────────────────────────────────────
            "L" (let [[nums _] (take-numbers (subvec tokens consume-idx) 2)
                      x (nth nums 0) y (nth nums 1)]
                  (recur (+ consume-idx 2) (conj points [x y])
                         x y sx sy "L" x y))
            "l" (let [[nums _] (take-numbers (subvec tokens consume-idx) 2)
                      x (+ cx (nth nums 0)) y (+ cy (nth nums 1))]
                  (recur (+ consume-idx 2) (conj points [x y])
                         x y sx sy "l" x y))

            ;; ── Horizontal ──────────────────────────────────
            "H" (let [[nums _] (take-numbers (subvec tokens consume-idx) 1)
                      x (nth nums 0)]
                  (recur (+ consume-idx 1) (conj points [x cy])
                         x cy sx sy "H" x cy))
            "h" (let [[nums _] (take-numbers (subvec tokens consume-idx) 1)
                      x (+ cx (nth nums 0))]
                  (recur (+ consume-idx 1) (conj points [x cy])
                         x cy sx sy "h" x cy))

            ;; ── Vertical ────────────────────────────────────
            "V" (let [[nums _] (take-numbers (subvec tokens consume-idx) 1)
                      y (nth nums 0)]
                  (recur (+ consume-idx 1) (conj points [cx y])
                         cx y sx sy "V" cx y))
            "v" (let [[nums _] (take-numbers (subvec tokens consume-idx) 1)
                      y (+ cy (nth nums 0))]
                  (recur (+ consume-idx 1) (conj points [cx y])
                         cx y sx sy "v" cx y))

            ;; ── Cubic Bezier ────────────────────────────────
            "C" (let [[nums _] (take-numbers (subvec tokens consume-idx) 6)
                      [x1 y1 x2 y2 x y] nums
                      pts (sample-cubic cx cy x1 y1 x2 y2 x y bezier-segments)]
                  (recur (+ consume-idx 6) (into points pts)
                         x y sx sy "C" x2 y2))
            "c" (let [[nums _] (take-numbers (subvec tokens consume-idx) 6)
                      [dx1 dy1 dx2 dy2 dx dy] nums
                      x1 (+ cx dx1) y1 (+ cy dy1)
                      x2 (+ cx dx2) y2 (+ cy dy2)
                      x (+ cx dx) y (+ cy dy)
                      pts (sample-cubic cx cy x1 y1 x2 y2 x y bezier-segments)]
                  (recur (+ consume-idx 6) (into points pts)
                         x y sx sy "c" x2 y2))

            ;; ── Smooth Cubic (S) ────────────────────────────
            ;; Control point 1 is reflection of previous control point 2
            "S" (let [[nums _] (take-numbers (subvec tokens consume-idx) 4)
                      [x2 y2 x y] nums
                      ;; Reflect last control point through current point
                      x1 (- (* 2 cx) last-ctrl-x)
                      y1 (- (* 2 cy) last-ctrl-y)
                      pts (sample-cubic cx cy x1 y1 x2 y2 x y bezier-segments)]
                  (recur (+ consume-idx 4) (into points pts)
                         x y sx sy "S" x2 y2))
            "s" (let [[nums _] (take-numbers (subvec tokens consume-idx) 4)
                      [dx2 dy2 dx dy] nums
                      x1 (- (* 2 cx) last-ctrl-x)
                      y1 (- (* 2 cy) last-ctrl-y)
                      x2 (+ cx dx2) y2 (+ cy dy2)
                      x (+ cx dx) y (+ cy dy)
                      pts (sample-cubic cx cy x1 y1 x2 y2 x y bezier-segments)]
                  (recur (+ consume-idx 4) (into points pts)
                         x y sx sy "s" x2 y2))

            ;; ── Quadratic Bezier ────────────────────────────
            "Q" (let [[nums _] (take-numbers (subvec tokens consume-idx) 4)
                      [x1 y1 x y] nums
                      pts (sample-quadratic cx cy x1 y1 x y bezier-segments)]
                  (recur (+ consume-idx 4) (into points pts)
                         x y sx sy "Q" x1 y1))
            "q" (let [[nums _] (take-numbers (subvec tokens consume-idx) 4)
                      [dx1 dy1 dx dy] nums
                      x1 (+ cx dx1) y1 (+ cy dy1)
                      x (+ cx dx) y (+ cy dy)
                      pts (sample-quadratic cx cy x1 y1 x y bezier-segments)]
                  (recur (+ consume-idx 4) (into points pts)
                         x y sx sy "q" x1 y1))

            ;; ── Smooth Quadratic (T) ────────────────────────
            ;; Control point is reflection of previous quadratic control
            "T" (let [[nums _] (take-numbers (subvec tokens consume-idx) 2)
                      [x y] nums
                      x1 (- (* 2 cx) last-ctrl-x)
                      y1 (- (* 2 cy) last-ctrl-y)
                      pts (sample-quadratic cx cy x1 y1 x y bezier-segments)]
                  (recur (+ consume-idx 2) (into points pts)
                         x y sx sy "T" x1 y1))
            "t" (let [[nums _] (take-numbers (subvec tokens consume-idx) 2)
                      [dx dy] nums
                      x (+ cx dx) y (+ cy dy)
                      x1 (- (* 2 cx) last-ctrl-x)
                      y1 (- (* 2 cy) last-ctrl-y)
                      pts (sample-quadratic cx cy x1 y1 x y bezier-segments)]
                  (recur (+ consume-idx 2) (into points pts)
                         x y sx sy "t" x1 y1))

            ;; ── Arc ─────────────────────────────────────────
            "A" (let [[nums _] (take-numbers (subvec tokens consume-idx) 7)
                      [rx ry x-rot large-arc sweep x y] nums
                      pts (sample-arc cx cy rx ry x-rot
                                      (int large-arc) (int sweep)
                                      x y arc-segments)]
                  (recur (+ consume-idx 7) (into points pts)
                         x y sx sy "A" x y))
            "a" (let [[nums _] (take-numbers (subvec tokens consume-idx) 7)
                      [rx ry x-rot large-arc sweep dx dy] nums
                      x (+ cx dx) y (+ cy dy)
                      pts (sample-arc cx cy rx ry x-rot
                                      (int large-arc) (int sweep)
                                      x y arc-segments)]
                  (recur (+ consume-idx 7) (into points pts)
                         x y sx sy "a" x y))

            ;; ── ClosePath ───────────────────────────────────
            ("Z" "z") (recur consume-idx points
                             sx sy sx sy nil sx sy)

            ;; ── Unknown — skip ──────────────────────────────
            (recur consume-idx points cx cy sx sy last-cmd
                   last-ctrl-x last-ctrl-y)))))))

;; ── SVG File Loading ────────────────────────────────────────────

(defn- extract-paths
  "Recursively extract all path 'd' attributes from an SVG XML tree."
  [node]
  (cond
    (nil? node) []
    (map? node)
    (let [paths (if (and (= :path (:tag node))
                         (get-in node [:attrs :d]))
                  [(get-in node [:attrs :d])]
                  [])
          children (mapcat extract-paths (:content node))]
      (into paths children))
    :else []))

(defn load-svg
  "Load an SVG file and extract all paths as Ridley 2D shapes.
   Returns a vector of shapes (each is {:type :shape :points [[x y] ...]}).
   Y axis is flipped (SVG Y points down, Ridley Y points up)."
  [path]
  (let [tree (xml/parse (java.io.File. path))
        d-strings (extract-paths tree)
        shapes (keep (fn [d]
                       (let [pts (parse-path-commands d)]
                         (when (>= (count pts) 2)
                           (let [flipped (mapv (fn [[x y]] [x (- y)]) pts)]
                             (shape/make-shape flipped {:centered? false})))))
                     d-strings)]
    (println (str "Loaded " path " (" (count shapes) " shape(s))"))
    (let [result (vec shapes)]
      (if (= 1 (count result))
        (first result)
        result))))

;; ── SVG String Parsing (no file I/O) ───────────────────────────

(defn svg-path
  "Parse an SVG path d-string into a Ridley shape. No file I/O.
   Y axis is flipped (SVG Y down → Ridley Y up)."
  [d]
  (let [pts (parse-path-commands d)]
    (when (>= (count pts) 2)
      (let [flipped (mapv (fn [[x y]] [x (- y)]) pts)]
        (shape/make-shape flipped {:centered? false})))))
