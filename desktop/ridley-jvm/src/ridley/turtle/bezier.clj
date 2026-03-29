(ns ridley.turtle.bezier
  "Pure bezier and arc math functions.
   These are computational functions that don't depend on turtle state or movement commands.
   Higher-level bezier commands (bezier-to, bezier-as, arc-h, arc-v) remain in core.cljs
   as they depend on movement primitives (f, th, tv)."
  (:require [ridley.math :refer [normalize v- v+ v* magnitude cross dot rotate-around-axis]]))

;; ============================================================
;; Bezier point and tangent calculations
;; ============================================================

(defn cubic-bezier-point
  "Calculate point on cubic Bezier curve at parameter t.
   p0 = start, p1 = control1, p2 = control2, p3 = end"
  [p0 p1 p2 p3 t]
  (let [t2 (- 1 t)
        a (* t2 t2 t2)
        b (* 3 t2 t2 t)
        c (* 3 t2 t t)
        d (* t t t)]
    [(+ (* a (nth p0 0)) (* b (nth p1 0)) (* c (nth p2 0)) (* d (nth p3 0)))
     (+ (* a (nth p0 1)) (* b (nth p1 1)) (* c (nth p2 1)) (* d (nth p3 1)))
     (+ (* a (nth p0 2)) (* b (nth p1 2)) (* c (nth p2 2)) (* d (nth p3 2)))]))

(defn quadratic-bezier-point
  "Calculate point on quadratic Bezier curve at parameter t.
   p0 = start, p1 = control, p2 = end"
  [p0 p1 p2 t]
  (let [t2 (- 1 t)
        a (* t2 t2)
        b (* 2 t2 t)
        c (* t t)]
    [(+ (* a (nth p0 0)) (* b (nth p1 0)) (* c (nth p2 0)))
     (+ (* a (nth p0 1)) (* b (nth p1 1)) (* c (nth p2 1)))
     (+ (* a (nth p0 2)) (* b (nth p1 2)) (* c (nth p2 2)))]))

(defn cubic-bezier-tangent
  "Calculate tangent (derivative) on cubic Bezier curve at parameter t."
  [p0 p1 p2 p3 t]
  (let [t2 (- 1 t)
        a (* 3 t2 t2)
        b (* 6 t2 t)
        c (* 3 t t)]
    (normalize
     [(+ (* a (- (nth p1 0) (nth p0 0)))
         (* b (- (nth p2 0) (nth p1 0)))
         (* c (- (nth p3 0) (nth p2 0))))
      (+ (* a (- (nth p1 1) (nth p0 1)))
         (* b (- (nth p2 1) (nth p1 1)))
         (* c (- (nth p3 1) (nth p2 1))))
      (+ (* a (- (nth p1 2) (nth p0 2)))
         (* b (- (nth p2 2) (nth p1 2)))
         (* c (- (nth p3 2) (nth p2 2))))])))

(defn quadratic-bezier-tangent
  "Calculate tangent (derivative) on quadratic Bezier curve at parameter t."
  [p0 p1 p2 t]
  (let [t2 (- 1 t)
        a (* 2 t2)
        b (* 2 t)]
    (normalize
     [(+ (* a (- (nth p1 0) (nth p0 0))) (* b (- (nth p2 0) (nth p1 0))))
      (+ (* a (- (nth p1 1) (nth p0 1))) (* b (- (nth p2 1) (nth p1 1))))
      (+ (* a (- (nth p1 2) (nth p0 2))) (* b (- (nth p2 2) (nth p1 2))))])))

;; ============================================================
;; Control point generation
;; ============================================================

(defn auto-control-points
  "Generate control points for a smooth cubic bezier.
   The curve starts tangent to current heading and ends smoothly at target."
  [p0 heading p3]
  (let [dist (magnitude (v- p3 p0))
        ;; First control point: extend from start along heading
        c1 (v+ p0 (v* heading (* dist 0.33)))
        ;; Second control point: extend from end back toward start
        to-start (normalize (v- p0 p3))
        c2 (v+ p3 (v* to-start (* dist 0.33)))]
    [c1 c2]))

(defn auto-control-points-with-target-heading
  "Generate control points for a smooth cubic bezier respecting both headings.
   The curve starts tangent to start-heading and ends tangent to target-heading.

   tension controls how far control points extend from endpoints:
   - 0 = very tight curve (almost angular)
   - 0.33 = default, balanced curve
   - 0.5-0.7 = wider, smoother curves
   - 1 = very wide curve"
  ([p0 start-heading p3 target-heading]
   (auto-control-points-with-target-heading p0 start-heading p3 target-heading 0.33))
  ([p0 start-heading p3 target-heading tension]
   (let [dist (magnitude (v- p3 p0))
         factor (or tension 0.33)
         ;; First control point: extend from start along start heading
         c1 (v+ p0 (v* start-heading (* dist factor)))
         ;; Second control point: extend from end opposite to target heading
         ;; (the curve arrives in the direction of target-heading)
         c2 (v+ p3 (v* (v* target-heading -1) (* dist factor)))]
     [c1 c2])))

;; ============================================================
;; Pure computation functions for bezier-as
;; ============================================================

(defn compute-bezier-control-points
  "Pure function: compute control points for a cubic bezier segment.

   Arguments:
   - p0, h0: start position and heading
   - p3, h1: end position and heading
   - tension: control point distance factor (0.33 default)
   - cubic-dirs: optional [d0 d1] Catmull-Rom directions (nil for heading-based)

   Returns [c1 c2] control points."
  [p0 h0 p3 h1 tension cubic-dirs]
  (let [seg-length (magnitude (v- p3 p0))
        factor (or tension 0.33)]
    (if cubic-dirs
      ;; Catmull-Rom mode: use provided directions
      (let [[d0 d1] cubic-dirs]
        [(v+ p0 (v* d0 (* seg-length factor)))
         (v- p3 (v* d1 (* seg-length factor)))])
      ;; Heading-based: use auto-control-points
      (auto-control-points-with-target-heading p0 h0 p3 h1 factor))))

(defn parallel-transport-up
  "Transport up vector from old-heading to new-heading via minimal rotation.
   Keeps the frame twist-free by rotating up with the same rotation
   that takes old-heading to new-heading."
  [up old-heading new-heading]
  (let [axis (cross old-heading new-heading)
        axis-mag (magnitude axis)]
    (if (< axis-mag 0.0001)
      ;; Headings nearly parallel, keep up as-is
      up
      (let [axis-norm (v* axis (/ 1.0 axis-mag))
            cos-a (max -1 (min 1 (dot old-heading new-heading)))
            angle (Math/acos cos-a)]
        (rotate-around-axis up axis-norm angle)))))

(defn sample-bezier-segment
  "Pure function: sample a cubic bezier segment into walk steps.

   Arguments:
   - p0, c1, c2, p3: bezier control points
   - steps: number of steps to sample
   - start-heading: heading at t=0 (for first step)
   - start-up: up vector at t=0

   Returns vector of {:from :to :chord-heading :final-heading :final-up}
   where chord-heading is the direction to move (for drawing)
   and final-heading is the tangent (for continuity).

   Uses parallel transport to evolve the up vector smoothly,
   preventing twist discontinuities at bends."
  [p0 c1 c2 p3 steps start-heading start-up]
  (let [end-heading (normalize (cubic-bezier-tangent p0 c1 c2 p3 1))
        last-i (dec steps)]
    (loop [i 0
           current-pos p0
           current-up start-up
           prev-heading start-heading
           results []]
      (if (>= i steps)
        results
        (let [t (/ (inc i) steps)
              new-pos (cubic-bezier-point p0 c1 c2 p3 t)
              move-dir (v- new-pos current-pos)
              dist (magnitude move-dir)]
          (if (> dist 0.001)
            (let [chord-heading (normalize move-dir)
                  ;; Final heading for tangent continuity
                  final-heading (cond (zero? i) start-heading
                                      (= i last-i) end-heading
                                      :else chord-heading)
                  ;; Parallel transport: rotate up by the same rotation
                  ;; that takes prev-heading to final-heading
                  final-up (parallel-transport-up current-up prev-heading final-heading)]
              (recur (inc i)
                     new-pos
                     final-up
                     final-heading
                     (conj results {:from current-pos
                                    :to new-pos
                                    :dist dist
                                    :chord-heading chord-heading
                                    :final-heading final-heading
                                    :final-up final-up})))
            ;; Skip degenerate step
            (recur (inc i) current-pos current-up prev-heading results)))))))
