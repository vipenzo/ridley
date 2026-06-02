;; Hamiltonian Cycle on a Grid — by u/Eternally_Monika (Reddit contribution)
;; A random Hamiltonian cycle on a grid, rendered as a 3D path. The cycle is
;; generated with the backbite algorithm: start from a snake path, then perturb
;; it randomly thousands of times until a valid cycle emerges. The result is
;; converted to turtle moves and extruded with a flat rectangular section.
;; Demonstrates why a real programming language as a modeling substrate matters:
;; graph algorithms, loop/recur, assert, RNG, all available natively.
;; Try changing: cols / rows (product must be even), cell-size, path-width /
;; path-thickness, mixing-steps.

;; Config
(def cols 10)
(def rows 10)
(def cell-size 10)
(def path-width 4)
(def path-thickness 1)
(def mixing-steps 1000)
(def max-search-steps (* rows cols rows cols))

(assert (even? (* cols rows))
  "Hamiltonian cycles are impossible on odd-sized grids.")

;; Logic
(defn make-snake [c r]
  (vec (for [y (range r)
             x (if (even? y) (range c) (reverse (range c)))]
         [x y])))

(defn in-bounds? [[x y]]
  (and (>= x 0) (< x cols) (>= y 0) (< y rows)))

(defn on-boundary? [[x y]]
  (or (= x 0) (= x (dec cols)) (= y 0) (= y (dec rows))))

(defn abs [n] (max n (- n)))

(defn manhattan-dist [p1 p2]
  (+ (abs (- (first p1) (first p2)))
     (abs (- (second p1) (second p2)))))

(defn reverse-prefix [path idx]
  (vec (concat (reverse (take idx path)) (drop idx path))))

(defn find-index [coll target]
  (first (keep-indexed #(when (= %2 target) %1) coll)))

(defn backbite-step [path]
  (let [work-path (if (> (rand) 0.5) (vec (reverse path)) path)
        head (first work-path)
        neck (second work-path)
        [hx hy] head
        candidates [[(inc hx) hy] [(dec hx) hy]
                    [hx (inc hy)] [hx (dec hy)]]
        valid-neighbors (filter #(and (in-bounds? %)
                                      (not= % neck))
                          candidates)]
    (if (empty? valid-neighbors)
      work-path
      (let [chosen (rand-nth valid-neighbors)
            cut-idx (find-index work-path chosen)]
        (reverse-prefix work-path cut-idx)))))

(defn find-cycle [path step limit]
  (let [head (first path)
        tail (last path)
        is-cycle (and (= 1 (manhattan-dist head tail))
                      (on-boundary? head)
                      (on-boundary? tail))]
    (if (or is-cycle (>= step limit))
      path
      (recur (backbite-step path) (inc step) limit))))

(defn roll-to-zero [path]
  (let [idx (find-index path [0 0])]
    (vec (concat (drop idx path) (take idx path)))))

;; Execution
(def initial-snake (make-snake cols rows))

(def mixed-path
  (loop [p initial-snake i 0]
    (if (< i mixing-steps)
      (recur (backbite-step p) (inc i))
      p)))

(def raw-cycle (find-cycle mixed-path 0 max-search-steps))
(def path-points (roll-to-zero raw-cycle))

;; Rendering
(def cycle-path
  (path
    (loop [pts (conj (vec path-points) (first path-points))
           curr-heading 0]
      (when (> (count pts) 1)
        (let [[[x1 y1] [x2 y2]] pts
              dx (- x2 x1)
              dy (- y2 y1)
              target-heading (cond
                               (> dx 0) 0   (> dy 0) 90
                               (< dx 0) 180 (< dy 0) -90
                               :else curr-heading)
              diff (mod (- target-heading curr-heading) 360)
              turn (if (> diff 180) (- diff 360) diff)]
          (when-not (zero? turn) (th turn))
          (f cell-size)
          (recur (rest pts) target-heading))))))

(register cycle-mesh
  (extrude (rect path-width path-thickness) cycle-path))
