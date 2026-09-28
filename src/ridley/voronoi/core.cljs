(ns ridley.voronoi.core
  "Procedural Voronoi shell generation.

   Generates perforated 2D shapes with Voronoi cell patterns where
   cell borders are material and cell interiors are holes.
   Compatible with loft, extrude, revolve, and all shape-fns."
  (:require ["d3-delaunay" :refer [Delaunay]]
            [ridley.clipper.core :as clipper]
            [ridley.turtle.shape :as shape]))

;; ============================================================
;; Deterministic PRNG (mulberry32)
;; ============================================================

(defn- mulberry32
  "Create a deterministic PRNG from an integer seed.
   Returns a function that produces float in [0, 1) on each call."
  [seed]
  (let [state (atom (bit-or seed 0))]
    (fn []
      (swap! state #(bit-or (+ % 0x6D2B79F5) 0))
      (let [t (bit-or (js/Math.imul (bit-xor @state (unsigned-bit-shift-right @state 15))
                                     (bit-or @state 1))
                       0)
            t (bit-xor t (+ t (bit-or (js/Math.imul (bit-xor t (unsigned-bit-shift-right t 7))
                                                      (bit-or t 61))
                                       0)))]
        (/ (unsigned-bit-shift-right (bit-xor t (unsigned-bit-shift-right t 14)) 0)
           4294967296)))))

;; ============================================================
;; 2D geometry helpers
;; ============================================================

(defn- bounding-box
  "Compute [xmin ymin xmax ymax] of a set of 2D points."
  [points]
  (reduce (fn [[xn yn xx yx] [x y]]
            [(min xn x) (min yn y) (max xx x) (max yx y)])
          [js/Number.POSITIVE_INFINITY js/Number.POSITIVE_INFINITY
           js/Number.NEGATIVE_INFINITY js/Number.NEGATIVE_INFINITY]
          points))

(def ^:private signed-area-2d clipper/polygon-signed-area)

(defn- vertex-mean
  "Mean of a polygon's vertices — voronoi-shell's historical 'centroid', kept
   so a shell with a given seed renders exactly as it always has."
  [points]
  (let [n (count points)]
    (if (zero? n)
      [0 0]
      [(/ (reduce + (map first points)) n)
       (/ (reduce + (map second points)) n)])))

(defn- polygon-centroid
  "Area-weighted centroid of a 2D polygon (the point Lloyd relaxation moves a
   seed to). Falls back to the vertex mean when the area is degenerate."
  [points]
  (let [n (count points)
        a (signed-area-2d points)]
    (cond
      (zero? n) [0 0]
      (< (Math/abs a) 1e-9) (vertex-mean points)
      :else
      (let [[cx cy] (reduce (fn [[cx cy] i]
                              (let [[x1 y1] (nth points i)
                                    [x2 y2] (nth points (mod (inc i) n))
                                    w (- (* x1 y2) (* x2 y1))]
                                [(+ cx (* (+ x1 x2) w)) (+ cy (* (+ y1 y2) w))]))
                            [0 0] (range n))]
        [(/ cx (* 6 a)) (/ cy (* 6 a))]))))

(defn- inside-shape?
  "Point strictly inside a shape: in its outer contour and in none of its holes."
  [pt shape]
  (and (clipper/point-in-polygon? pt (:points shape))
       (not-any? #(clipper/point-in-polygon? pt %) (:holes shape))))

(defn- point-segment-distance [[px py] [[x1 y1] [x2 y2]]]
  (let [dx (- x2 x1) dy (- y2 y1)
        l2 (+ (* dx dx) (* dy dy))
        t (if (< l2 1e-12) 0 (max 0 (min 1 (/ (+ (* (- px x1) dx) (* (- py y1) dy)) l2))))
        cx (+ x1 (* t dx)) cy (+ y1 (* t dy))]
    (Math/sqrt (+ (* (- px cx) (- px cx)) (* (- py cy) (- py cy))))))

(defn- contour-segments [pts]
  (map vector pts (concat (rest pts) [(first pts)])))

(defn- distance-to-boundary
  "Distance from a point to the nearest edge of the shape (outer or hole)."
  [pt shape]
  (reduce min js/Number.POSITIVE_INFINITY
          (map #(point-segment-distance pt %)
               (mapcat contour-segments (cons (:points shape) (:holes shape))))))

(defn ^:export deepest-point
  "The interior point of a shape farthest from its boundary (an approximate
   pole of inaccessibility, on a 48×48 grid over the bbox). The place for ONE
   pin on a C or a U, whose centroid falls outside the material."
  [shape]
  (let [pts (:points shape)
        [xmin ymin xmax ymax] (bounding-box pts)
        steps 48
        candidates (for [i (range 1 steps) j (range 1 steps)
                         :let [p [(+ xmin (* (- xmax xmin) (/ i steps)))
                                  (+ ymin (* (- ymax ymin) (/ j steps)))]]
                         :when (inside-shape? p shape)]
                     p)]
    (if (seq candidates)
      ;; ties (a rectangle's whole mid-line) break toward the centroid
      (let [c (polygon-centroid pts)
            scored (map (fn [p] [p (distance-to-boundary p shape)]) candidates)
            best (reduce max (map second scored))
            d2 (fn [[x y]] (+ (* (- x (c 0)) (- x (c 0))) (* (- y (c 1)) (- y (c 1)))))]
        (apply min-key d2 (map first (filter #(>= (second %) (- best 1e-9)) scored))))
      (polygon-centroid pts))))

(defn- ensure-cw
  "Ensure points are CW (negative signed area) for hole winding."
  [points]
  (if (pos? (signed-area-2d points))
    (vec (reverse points))
    points))

(defn- segment-length [[x1 y1] [x2 y2]]
  (let [dx (- x2 x1) dy (- y2 y1)]
    (Math/sqrt (+ (* dx dx) (* dy dy)))))

(defn- polygon-perimeter
  "Compute total perimeter of a closed polygon."
  [points]
  (let [n (count points)]
    (reduce + (for [i (range n)]
                (segment-length (nth points i)
                                (nth points (mod (inc i) n)))))))

;; ============================================================
;; Resampling (uniform point distribution along perimeter)
;; ============================================================

(defn- resample-polygon
  "Resample a closed polygon to exactly n evenly-spaced points."
  [points n]
  (let [cnt (count points)
        segments (mapv vector points (concat (rest points) [(first points)]))
        lengths (mapv (fn [[p1 p2]] (segment-length p1 p2)) segments)
        total (reduce + lengths)
        step (/ total n)]
    (loop [result []
           seg-idx 0
           accumulated 0.0
           target 0.0]
      (if (>= (count result) n)
        (vec result)
        (let [[p1 p2] (nth segments seg-idx)
              seg-len (nth lengths seg-idx)
              remaining (- seg-len (- target accumulated))]
          (if (and (> seg-len 1e-10) (<= (- target accumulated) seg-len))
            ;; Point falls in this segment
            (let [t (/ (- target accumulated) seg-len)
                  t (max 0.0 (min 1.0 t))
                  [x1 y1] p1
                  [x2 y2] p2
                  new-pt [(+ x1 (* t (- x2 x1)))
                          (+ y1 (* t (- y2 y1)))]]
              (recur (conj result new-pt)
                     seg-idx
                     accumulated
                     (+ target step)))
            ;; Move to next segment
            (recur result
                   (mod (inc seg-idx) cnt)
                   (+ accumulated seg-len)
                   target)))))))

;; ============================================================
;; Seed generation
;; ============================================================

(defn- generate-seeds
  "Generate n deterministic seed points inside a shape via rejection sampling.
   Accepts a shape map (holes respected) or bare outer points."
  [shape-or-points n seed]
  (let [shape (if (map? shape-or-points) shape-or-points {:points shape-or-points})
        shape-points (:points shape)
        rng (mulberry32 seed)
        [xmin ymin xmax ymax] (bounding-box shape-points)
        xspan (- xmax xmin)
        yspan (- ymax ymin)]
    (loop [seeds [] attempts 0]
      (cond
        (>= (count seeds) n) seeds
        ;; Safety: give up after too many attempts (very thin/weird shapes)
        (> attempts (* n 100)) seeds
        :else
        (let [x (+ xmin (* (rng) xspan))
              y (+ ymin (* (rng) yspan))]
          (if (inside-shape? [x y] shape)
            (recur (conj seeds [x y]) (inc attempts))
            (recur seeds (inc attempts))))))))

;; ============================================================
;; Voronoi computation via d3-delaunay
;; ============================================================

(defn- compute-voronoi-cells
  "Compute Voronoi cells from seed points within bounds.
   Returns vector of cell polygons (each is vector of [x y])."
  [seeds bounds]
  (let [[xmin ymin xmax ymax] bounds
        ;; d3-delaunay .from expects array of [x,y] pairs
        delaunay (.from Delaunay (clj->js seeds))
        voronoi (.voronoi delaunay (clj->js [xmin ymin xmax ymax]))
        n (count seeds)]
    (vec (for [i (range n)]
           (let [cell (.cellPolygon voronoi i)]
             (when cell
               ;; cellPolygon returns [[x,y], ...] with first == last (closed)
               ;; Drop the last (duplicate) point
               (let [len (.-length cell)]
                 (vec (for [j (range (dec len))]
                        (let [pt (aget cell j)]
                          [(aget pt 0) (aget pt 1)]))))))))))

;; ============================================================
;; Lloyd relaxation
;; ============================================================

(defn- lloyd-relax
  "Relax seed points via Lloyd's algorithm for more uniform cells: each
   iteration moves every seed to the area centroid of its Voronoi cell clipped
   to the boundary shape. On a concave boundary a clipped cell can fall into
   several disconnected pieces: the seed follows the piece that CONTAINS it
   (dev-docs/brief-joint-layout.md Parte 0) — never the largest piece, never
   the merged centroid, either of which can land outside the material. A seed
   whose cell vanishes stays put. `boundary` is a shape map (holes respected).
   `centroid-fn` (default polygon-centroid, area-weighted) is the point a seed
   moves to; voronoi-shell passes vertex-mean to keep its output unchanged."
  [seeds boundary bounds iterations & [centroid-fn]]
  (if (<= iterations 0)
    seeds
    (loop [current-seeds seeds
           i 0]
      (if (>= i iterations)
        current-seeds
        (let [cells (compute-voronoi-cells current-seeds bounds)
              new-seeds
              (vec (map-indexed
                    (fn [idx cell]
                      (let [seed (nth current-seeds idx)]
                        (if (nil? cell)
                          seed
                          (let [cell-shape (shape/make-shape cell {:centered? true})
                                pieces (clipper/shape-intersection-all cell-shape boundary)
                                mine (or (some #(when (inside-shape? seed %) %) pieces)
                                         (when (= 1 (count pieces)) (first pieces)))]
                            (if mine
                              ((or centroid-fn polygon-centroid) (:points mine))
                              seed)))))
                    cells))]
          (recur new-seeds (inc i)))))))

(defn- voronoi-bounds
  "Bounding box of a shape's outer contour, padded 5% — the box d3 clips the
   unbounded Voronoi cells to."
  [shape-points]
  (let [[xmin ymin xmax ymax] (bounding-box shape-points)
        margin (* 0.05 (max (- xmax xmin) (- ymax ymin)))]
    [(- xmin margin) (- ymin margin) (+ xmax margin) (+ ymax margin)]))

(defn ^:export spread-points
  "N points spread evenly inside a 2D shape (holes respected): deterministic
   seeds (`:seed`, default 0) relaxed by Lloyd's algorithm (`:iterations`,
   default 10) into a centroidal Voronoi layout — every point as far as it can
   be from its neighbours and from the boundary. A point that still ends up
   outside the material (N = 1 on a C: the centroid is in the void) is replaced
   by the shape's deepest interior point. Same seed → same points, so a script
   re-run leaves its pins where they were.

   (spread-points (rect 60 20) 3)
   (spread-points zone 5 :seed 7 :iterations 20)"
  [shape n & {:keys [seed iterations] :or {seed 0 iterations 10}}]
  (if (or (nil? shape) (< n 1) (< (count (:points shape)) 3))
    []
    (let [boundary (shape/make-shape (:points shape) (cond-> {:centered? true}
                                                       (:holes shape) (assoc :holes (:holes shape))))
          bounds (voronoi-bounds (:points shape))
          seeds (generate-seeds boundary n seed)
          relaxed (lloyd-relax seeds boundary bounds iterations)]
      (mapv #(if (inside-shape? % boundary) % (deepest-point boundary)) relaxed))))

;; ============================================================
;; Cell → hole conversion
;; ============================================================

(defn- cell-to-hole
  "Convert a Voronoi cell polygon to an inset hole.
   Returns vector of [x y] points (CW winding) or nil if too small."
  [cell-polygon boundary-shape wall min-area]
  (when cell-polygon
    (let [cell-shape (shape/make-shape cell-polygon {:centered? true})
          clipped (clipper/shape-intersection cell-shape boundary-shape)]
      (when clipped
        (let [inset (clipper/shape-offset clipped (- (/ wall 2)) :join-type :round)]
          (when (and inset
                     (> (Math/abs (signed-area-2d (:points inset))) min-area))
            (:points inset)))))))

;; ============================================================
;; Main API
;; ============================================================

(defn ^:export voronoi-shell
  "Generate a perforated shape with Voronoi cell pattern.
   Returns a shape with holes — one hole per Voronoi cell.
   Compatible with loft, extrude, revolve, and all shape-fns.

   (voronoi-shell (circle 20) :cells 40 :wall 1.5)
   (voronoi-shell (circle 20 64) :cells 20 :wall 2 :seed 42 :relax 3)

   Options:
     :cells      - number of Voronoi cells (default 20)
     :wall       - wall thickness between cells (default 1.5)
     :seed       - random seed for reproducibility (default 0)
     :relax      - Lloyd relaxation iterations for uniformity (default 2)
     :resolution - points per hole for loft compatibility (default 16)"
  [input-shape & {:keys [cells wall seed relax resolution]
                  :or {cells 20 wall 1.5 seed 0 relax 2 resolution 16}}]
  (let [shape-points (:points input-shape)
        bounds (voronoi-bounds shape-points)
        ;; Generate and relax seeds on the OUTER contour only, as before this
        ;; module grew spread-points: the shell's holes are its own output.
        outer-only (shape/make-shape shape-points {:centered? true})
        seeds (generate-seeds outer-only cells seed)
        seeds (lloyd-relax seeds outer-only bounds relax vertex-mean)
        ;; Compute final Voronoi cells
        voronoi-cells (compute-voronoi-cells seeds bounds)
        ;; Convert cells to holes
        boundary-shape (shape/make-shape shape-points {:centered? true
                                                        :holes (:holes input-shape)})
        ;; Minimum area: cells smaller than this after inset are dropped
        min-area (* wall wall 0.5)
        ;; Process each cell
        raw-holes (vec (keep #(cell-to-hole % boundary-shape wall min-area)
                             voronoi-cells))
        ;; Resample each hole to consistent point count and ensure CW winding
        holes (mapv (fn [hole-pts]
                      (let [resampled (resample-polygon hole-pts resolution)]
                        (ensure-cw resampled)))
                    raw-holes)]
    (assoc input-shape :holes holes)))
