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
   - 1 = very wide curve

   With six args, tension-start and tension-end are applied independently to
   the two control points (asymmetric handles); with one tension the curve is
   symmetric. Both handle directions stay locked to the headings either way."
  ([p0 start-heading p3 target-heading]
   (auto-control-points-with-target-heading p0 start-heading p3 target-heading 0.33))
  ([p0 start-heading p3 target-heading tension]
   (auto-control-points-with-target-heading p0 start-heading p3 target-heading tension tension))
  ([p0 start-heading p3 target-heading tension-start tension-end]
   (let [dist (magnitude (v- p3 p0))
         f0 (or tension-start 0.33)
         f1 (or tension-end 0.33)
         ;; First control point: extend from start along start heading
         c1 (v+ p0 (v* start-heading (* dist f0)))
         ;; Second control point: extend from end opposite to target heading
         ;; (the curve arrives in the direction of target-heading)
         c2 (v+ p3 (v* (v* target-heading -1) (* dist f1)))]
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

;; ============================================================
;; Canonical bezier frame — resolution-independent RMF
;; ============================================================
;; dev-docs/brief-bezier-canonical-frame.md: parallel-transport-up (above) is
;; correct minimal-rotation transport PER STEP, but re-seeded chord-to-chord,
;; so its accumulated result depends on how many chords the caller happens to
;; use. canonical-bezier-frame instead builds a frame that is a property of
;; the curve alone (its 4 control points + entry up) via the double-reflection
;; RMF (Wang, Jüttler, Zheng, Liu — "Computation of Rotation Minimizing
;; Frames", 2008): two Householder reflections per step, which cancel the
;; spurious roll a single reflection would introduce. Evaluated once on a
;; fixed fine grid, then read off at any t with one more reflection — so the
;; result never depends on which/how-many t's a caller happens to sample.

(def ^:private canonical-frame-grid-n 64)

(defn- bezier-tangent-safe
  "cubic-bezier-tangent, but falls back to the secant p3-p0 when the exact
   derivative is ~0 (e.g. c2≈p3 at t=1), then to [1 0 0] if even that is
   degenerate — mirrors the end-tangent fallback already used elsewhere
   (bezier-frame-3d) for the same coincident-control-point case."
  [p0 c1 c2 p3 t]
  (let [tan (cubic-bezier-tangent p0 c1 c2 p3 t)]
    (if (< (magnitude tan) 1e-9)
      (let [secant (v- p3 p0)]
        (if (> (magnitude secant) 1e-9) (normalize secant) [1 0 0]))
      tan)))

(defn- rmf-reflect-step
  "One double-reflection step transporting frame vector `up0` (with tangent
   `tangent0`) at `point0` to `point1`, where the curve's ANALYTIC tangent at
   point1 is `tangent1`. Degenerate sub-steps (coincident points, or a
   reflected tangent that already coincides with tangent1) skip the
   corresponding reflection and carry the frame through unchanged, mirroring
   parallel-transport-up's near-parallel-heading fallback."
  [point0 point1 tangent0 up0 tangent1]
  (let [v1 (v- point1 point0)]
    (if (< (magnitude v1) 1e-9)
      up0
      (let [c1 (dot v1 v1)
            r-up (v- up0 (v* v1 (/ (* 2 (dot v1 up0)) c1)))
            r-t  (v- tangent0 (v* v1 (/ (* 2 (dot v1 tangent0)) c1)))
            v2   (v- tangent1 r-t)]
        (if (< (magnitude v2) 1e-9)
          (normalize r-up)
          (let [c2 (dot v2 v2)]
            (normalize (v- r-up (v* v2 (/ (* 2 (dot v2 r-up)) c2))))))))))

(defn canonical-bezier-frame
  "THE canonical frame field for cubic bezier [p0 c1 c2 p3], anchored at
   `entry-up` (t=0) — independent of tessellation. Returns a vector of
   {:t :heading :up} parallel to `ts`: `:heading` is the exact analytic
   tangent at t; `:up` is the canonical RMF up (always ⊥ heading).

   `entry-up` is re-orthogonalized once via Gram-Schmidt against the entry
   tangent (if entry-up is ~parallel to the tangent, there is no perpendicular
   direction to recover, so it is used unchanged — same fallback as
   parallel-transport-up)."
  [p0 c1 c2 p3 entry-up ts]
  (let [n canonical-frame-grid-n
        h0 (bezier-tangent-safe p0 c1 c2 p3 0)
        entry-proj (v- entry-up (v* h0 (dot entry-up h0)))
        up0 (if (> (magnitude entry-proj) 1e-9) (normalize entry-proj) entry-up)
        grid-pts (mapv #(cubic-bezier-point p0 c1 c2 p3 (/ % n)) (range (inc n)))
        grid-tans (mapv #(bezier-tangent-safe p0 c1 c2 p3 (/ % n)) (range (inc n)))
        grid-ups (reduce (fn [ups i]
                           (conj ups (rmf-reflect-step (nth grid-pts i) (nth grid-pts (inc i))
                                                       (nth grid-tans i) (peek ups) (nth grid-tans (inc i)))))
                         [up0] (range n))]
    (mapv (fn [t]
            (let [t' (max 0.0 (min 1.0 t))
                  i (min (dec n) (int (Math/floor (* t' n))))
                  pt (cubic-bezier-point p0 c1 c2 p3 t')
                  tan (bezier-tangent-safe p0 c1 c2 p3 t')]
              {:t t :heading tan
               :up (rmf-reflect-step (nth grid-pts i) pt (nth grid-tans i) (nth grid-ups i) tan)}))
          ts)))

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

   :final-up is read off the canonical bezier frame (see
   canonical-bezier-frame above) — a property of the curve's control points
   and start-up alone, so it does not depend on `steps`."
  [p0 c1 c2 p3 steps start-heading start-up]
  (let [last-i (dec steps)
        ts (mapv #(/ (inc %) steps) (range steps))
        frames (canonical-bezier-frame p0 c1 c2 p3 start-up ts)
        end-heading (:heading (peek frames))]
    (loop [i 0
           current-pos p0
           results []]
      (if (>= i steps)
        results
        (let [t (nth ts i)
              new-pos (cubic-bezier-point p0 c1 c2 p3 t)
              move-dir (v- new-pos current-pos)
              dist (magnitude move-dir)]
          (if (> dist 0.001)
            (let [chord-heading (normalize move-dir)
                  ;; Final heading for tangent continuity
                  final-heading (cond (zero? i) start-heading
                                      (= i last-i) end-heading
                                      :else chord-heading)
                  final-up (:up (nth frames i))]
              (recur (inc i)
                     new-pos
                     (conj results {:from current-pos
                                    :to new-pos
                                    :dist dist
                                    :chord-heading chord-heading
                                    :final-heading final-heading
                                    :final-up final-up})))
            ;; Skip degenerate step
            (recur (inc i) current-pos results)))))))
