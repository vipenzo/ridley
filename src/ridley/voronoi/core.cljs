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

(defn- polygon-centroid
  "Compute centroid of a 2D polygon."
  [points]
  (let [n (count points)]
    (if (zero? n)
      [0 0]
      [(/ (reduce + (map first points)) n)
       (/ (reduce + (map second points)) n)])))

(defn- signed-area-2d
  "Signed area of 2D polygon. Positive = CCW, Negative = CW."
  [points]
  (let [n (count points)]
    (if (< n 3)
      0
      (/ (reduce
          (fn [sum i]
            (let [[x1 y1] (nth points i)
                  [x2 y2] (nth points (mod (inc i) n))]
              (+ sum (- (* x1 y2) (* x2 y1)))))
          0
          (range n))
         2))))

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
  "Generate n deterministic seed points inside a shape via rejection sampling."
  [shape-points n seed]
  (let [rng (mulberry32 seed)
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
          (if (clipper/point-in-polygon? [x y] shape-points)
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
  "Relax seed points via Lloyd's algorithm for more uniform cells.
   Each iteration moves seeds to centroids of their clipped Voronoi cells."
  [seeds shape-points bounds iterations]
  (if (<= iterations 0)
    seeds
    (loop [current-seeds seeds
           i 0]
      (if (>= i iterations)
        current-seeds
        (let [cells (compute-voronoi-cells current-seeds bounds)
              ;; For each cell: clip to shape, compute centroid
              boundary-shape (shape/make-shape shape-points {:centered? true})
              new-seeds
              (vec (map-indexed
                    (fn [idx cell]
                      (if (nil? cell)
                        (nth current-seeds idx) ;; Keep original if no cell
                        (let [cell-shape (shape/make-shape cell {:centered? true})
                              clipped (clipper/shape-intersection cell-shape boundary-shape)]
                          (if clipped
                            (polygon-centroid (:points clipped))
                            (nth current-seeds idx)))))
                    cells))]
          (recur new-seeds (inc i)))))))

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
        ;; Bounding box with margin for Voronoi computation
        [xmin ymin xmax ymax] (bounding-box shape-points)
        margin (* 0.05 (max (- xmax xmin) (- ymax ymin)))
        bounds [(- xmin margin) (- ymin margin)
                (+ xmax margin) (+ ymax margin)]
        ;; Generate and relax seeds
        seeds (generate-seeds shape-points cells seed)
        seeds (lloyd-relax seeds shape-points bounds relax)
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
