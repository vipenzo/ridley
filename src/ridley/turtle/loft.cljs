(ns ridley.turtle.loft
  "Loft operations: extrusion with shape transformation.

   This module contains loft-specific logic extracted from turtle/core.cljs.
   Loft allows extruding a shape along a path while transforming it (tapering, etc.)."
  (:require [ridley.schema :as schema]
            [ridley.math :as math]
            [ridley.turtle.extrusion :as extrusion]
            [ridley.clipper.core :as clipper]
            [ridley.voronoi.core :as voronoi]
            [ridley.turtle.shape :as shape]))

;; --- Re-export math utilities used throughout ---
(def v+ math/v+)
(def v- math/v-)
(def v* math/v*)
(def dot math/dot)
(def cross math/cross)
(def magnitude math/magnitude)
(def normalize math/normalize)

;; --- Re-export extrusion utilities we depend on ---
(def shape? extrusion/shape?)
(def is-path? extrusion/is-path?)
(def stamp-shape extrusion/stamp-shape)
(def shape-radius extrusion/shape-radius)
(def build-sweep-mesh extrusion/build-sweep-mesh)
(def apply-rotation-to-state extrusion/apply-rotation-to-state)
(def is-rotation? extrusion/is-rotation?)
(def is-corner-rotation? extrusion/is-corner-rotation?)
(def calc-shorten-for-angle extrusion/calc-shorten-for-angle)
(def ring-centroid extrusion/ring-centroid)
(def triangulate-cap extrusion/triangulate-cap)
(def triangulate-cap-with-holes extrusion/triangulate-cap-with-holes)
(def generate-round-corner-rings extrusion/generate-round-corner-rings)
(def generate-tapered-corner-rings extrusion/generate-tapered-corner-rings)
(def generate-round-corner-ring-data extrusion/generate-round-corner-ring-data)
(def generate-tapered-corner-ring-data extrusion/generate-tapered-corner-ring-data)
(def stamp-shape-with-holes extrusion/stamp-shape-with-holes)
(def build-sweep-mesh-with-holes extrusion/build-sweep-mesh-with-holes)
(def calc-round-steps extrusion/calc-round-steps)
(def get-resolution extrusion/get-resolution)

;; ============================================================
;; Loft - extrusion with shape transformation
;; ============================================================

(defn stamp-loft
  "Internal: stamp a shape for loft with transform function.
   Similar to stamp but also stores the base shape and transform function.
   steps: number of intermediate steps (defaults to (default-segments state 1))"
  ([state shape transform-fn]
   (stamp-loft state shape transform-fn (extrusion/default-segments state 1)))
  ([state shape transform-fn steps]
   (if (shape? shape)
     (let [;; Apply transform at t=0 to get initial shape
           initial-shape (transform-fn shape 0)
           stamped (stamp-shape state initial-shape)
           ;; Save initial orientation
           initial-orientation {:position (:position state)
                                :heading (:heading state)
                                :up (:up state)}]
       (-> state
           (assoc :pen-mode :loft)
           (assoc :stamped-shape stamped)
           (assoc :sweep-rings [])           ; Will be built at finalize
           (assoc :loft-base-shape shape)
           (assoc :loft-transform-fn transform-fn)
           (assoc :loft-steps steps)
           (assoc :loft-total-dist 0)
           (assoc :loft-start-pos (:position state))
           (assoc :loft-start-heading (:heading state))
           (assoc :loft-start-up (:up state))
           (assoc :loft-orientations [initial-orientation])))
     state)))

(defn- interpolate-orientation
  "Interpolate between two orientations at parameter t (0-1)."
  [o1 o2 t]
  (let [;; Linear interpolation of position
        p1 (:position o1)
        p2 (:position o2)
        pos (v+ p1 (v* (v- p2 p1) t))
        ;; For heading/up, use linear interpolation then normalize
        ;; (proper slerp would be better but this works for small angles)
        h1 (:heading o1)
        h2 (:heading o2)
        heading (normalize (v+ h1 (v* (v- h2 h1) t)))
        u1 (:up o1)
        u2 (:up o2)
        up (normalize (v+ u1 (v* (v- u2 u1) t)))]
    {:position pos :heading heading :up up}))

(defn- find-orientation-at-dist
  "Find interpolated orientation at a given distance along the path.
   Uses binary search (orientations are sorted by :dist)."
  [orientations target-dist total-dist]
  (if (zero? total-dist)
    (first orientations)
    (let [n (count orientations)
          last-idx (dec n)
          ;; Binary search for the segment containing target-dist
          i (loop [lo 0, hi last-idx]
              (if (>= lo hi)
                (min lo (dec last-idx))
                (let [mid (unsigned-bit-shift-right (+ lo hi) 1)
                      d (or (:dist (nth orientations mid)) 0)]
                  (if (<= d target-dist)
                    (recur (inc mid) hi)
                    (recur lo mid)))))
          ;; i is the last index whose dist <= target-dist; clamp to valid segment
          i (max 0 (min i (dec last-idx)))
          o1 (nth orientations i)
          o2 (nth orientations (inc i))
          d1 (or (:dist o1) 0)
          d2 (:dist o2)]
      (if (>= target-dist d2)
        (last orientations)
        (let [segment-len (- d2 d1)
              local-t (if (pos? segment-len)
                        (/ (- target-dist d1) segment-len)
                        0)]
          (interpolate-orientation o1 o2 local-t))))))

(defn- calculate-loft-corner-shortening
  "Calculate R_p and R_n for a loft corner based on inner edge intersection.

   For a tapered loft (linear taper from radius R to 0 over distance D),
   when there's a turn at distance L_A, we need to find where the inner edges
   of the two cone segments intersect (point P).

   R_p = distance to shorten the previous segment (before corner)
   R_n = distance to offset the next segment start (after corner)
   hidden-dist = R_p + R_n (virtual distance traveled through corner)

   Parameters:
   - initial-radius: the shape radius at t=0
   - total-dist: total loft distance D
   - dist-at-corner: distance traveled when corner occurs (L_A)
   - turn-angle: angle between old and new heading (radians)"
  [initial-radius total-dist dist-at-corner turn-angle]
  (let [R initial-radius
        D total-dist
        L_A dist-at-corner
        theta turn-angle
        L (- D L_A)  ;; remaining distance
        ;; Radius at corner (linear taper)
        r-corner (* R (/ L D))  ;; = R * (1 - L_A/D) = R * L / D

        ;; For very small angles, no corner shortening needed
        _ (when (< (Math/abs theta) 0.01)
            (throw (ex-info "skip" {:r-p 0 :r-n 0 :hidden-dist 0})))

        ;; Set up line intersection in 2D local coordinates at corner
        ;; X axis = old heading direction, Y axis = toward inside of turn
        ;; (For theta > 0, turn is to the left, inside is +Y)
        cos-t (Math/cos theta)
        sin-t (Math/sin theta)

        ;; Line A (inner edge of cone A):
        ;; From (-L_A, R) to (D-L_A, 0) = (L, 0)
        ;; Point p1, direction d1
        p1-x (- L_A)
        p1-y R
        d1-x D      ;; = L + L_A
        d1-y (- R)

        ;; Line B (inner edge of cone B, rotated):
        ;; Starts at inner point of corner cross-section
        ;; Inner direction for B is perpendicular to B's heading, toward inside
        ;; B's heading is (cos θ, sin θ), inner perpendicular is (-sin θ, cos θ)
        ;; Start point: r-corner * (-sin θ, cos θ)
        ;; End point (tip): L * (cos θ, sin θ)
        p2-x (* r-corner (- sin-t))
        p2-y (* r-corner cos-t)
        ;; Direction from start to end
        d2-x (- (* L cos-t) p2-x)
        d2-y (- (* L sin-t) p2-y)

        ;; Solve line intersection: p1 + t*d1 = p2 + s*d2
        ;; Using Cramer's rule for 2x2 system
        det (- (* d1-x (- d2-y)) (* (- d2-x) d1-y))
        ;; det = -d1-x*d2-y + d2-x*d1-y
        ]
    (if (< (Math/abs det) 0.0001)
      ;; Lines are parallel (shouldn't happen for reasonable angles)
      {:r-p 0 :r-n 0 :hidden-dist 0}
      (let [;; t parameter for point P on line A
            dx (- p2-x p1-x)
            dy (- p2-y p1-y)
            t-param (/ (- (* (- d2-y) dx) (* (- d2-x) dy)) det)

            ;; Point P in local coordinates
            p-x (+ p1-x (* t-param d1-x))
            p-y (+ p1-y (* t-param d1-y))

            ;; R_p: distance from corner (origin) back along A's axis (negative X)
            ;; P is at x = p-x. If p-x < 0, P is behind corner, R_p = -p-x
            r-p (if (neg? p-x) (- p-x) 0)

            ;; R_n: projection of P onto B's heading direction
            ;; B's heading is (cos θ, sin θ)
            r-n (+ (* p-x cos-t) (* p-y sin-t))
            r-n (if (pos? r-n) r-n 0)]
        {:r-p r-p
         :r-n r-n
         :hidden-dist (+ r-p r-n)
         :intersection-point [p-x p-y]}))))

(defn- process-loft-corners
  "Process all recorded corners to adjust orientations and distances.

   For each corner:
   1. Calculate R_p and R_n based on taper geometry
   2. Adjust the position of orientations around the corner
   3. Add hidden distance to subsequent orientation dist values

   Returns updated orientations and new total distance."
  [orientations corners initial-radius original-total-dist]
  (if (empty? corners)
    {:orientations orientations :total-dist original-total-dist}
    ;; Process corners in order of distance
    (let [sorted-corners (sort-by :dist-at-corner corners)]
      (loop [orients orientations
             remaining-corners sorted-corners
             accumulated-hidden 0
             total-dist original-total-dist]
        (if (empty? remaining-corners)
          {:orientations orients :total-dist total-dist}
          (let [corner (first remaining-corners)
                {:keys [old-heading new-heading dist-at-corner]} corner

                ;; Calculate turn angle from dot product
                cos-angle (dot old-heading new-heading)
                turn-angle (Math/acos (min 1 (max -1 cos-angle)))

                ;; Calculate R_p and R_n
                {:keys [r-p r-n hidden-dist]}
                (try
                  (calculate-loft-corner-shortening
                   initial-radius total-dist
                   (+ dist-at-corner accumulated-hidden)
                   turn-angle)
                  (catch :default _
                    {:r-p 0 :r-n 0 :hidden-dist 0}))

                ;; Find and adjust orientations around this corner
                ;; The corner is at dist-at-corner (plus any previously accumulated hidden)
                adjusted-dist (+ dist-at-corner accumulated-hidden)

                ;; Adjust positions: shorten end of previous segment, offset start of next
                new-orients
                (vec
                 (map-indexed
                  (fn [idx o]
                    (let [o-dist (or (:dist o) 0)]
                      (cond
                        ;; Before corner: no change to dist, but check if this is the corner waypoint
                        (< o-dist adjusted-dist)
                        o

                        ;; At or near corner: adjust position backward by R_p along old heading
                        (and (>= o-dist adjusted-dist)
                             (< o-dist (+ adjusted-dist 0.001)))
                        (-> o
                            (update :position #(v+ % (v* old-heading (- r-p))))
                            (assoc :dist o-dist))

                        ;; After corner: shift dist by hidden amount, adjust first one's position
                        :else
                        (let [is-first-after (and (> idx 0)
                                                  (< (or (:dist (nth orients (dec idx))) 0)
                                                     adjusted-dist))]
                          (cond-> o
                            true (update :dist #(+ % hidden-dist))
                            is-first-after (update :position
                                                   #(v+ (v+ % (v* old-heading (- r-p)))
                                                        (v* new-heading r-n))))))))
                  orients))]
            (recur new-orients
                   (rest remaining-corners)
                   (+ accumulated-hidden hidden-dist)
                   (+ total-dist hidden-dist))))))))

(defn- generate-loft-segment-mesh
  "Generate a mesh for a single loft segment.
   t-start and t-end are the t values (0-1) for this segment.
   steps-per-segment is the number of interpolation steps."
  [state base-shape transform-fn orientations total-dist t-start t-end steps-per-segment creation-pose]
  (let [has-holes? (boolean (:holes base-shape))]
    (if has-holes?
      (let [ring-data-vec (vec
                           (for [i (range (inc steps-per-segment))]
                             (let [local-t (/ i steps-per-segment)
                                   t (+ t-start (* local-t (- t-end t-start)))
                                   target-dist (* t total-dist)
                                   orientation (find-orientation-at-dist orientations target-dist total-dist)
                                   transformed-2d (transform-fn base-shape t)
                                   temp-state (-> state
                                                  (assoc :position (:position orientation))
                                                  (assoc :heading (:heading orientation))
                                                  (assoc :up (:up orientation)))]
                               (stamp-shape-with-holes temp-state transformed-2d))))]
        (build-sweep-mesh-with-holes ring-data-vec creation-pose))
      (let [new-rings (vec
                       (for [i (range (inc steps-per-segment))]
                         (let [local-t (/ i steps-per-segment)
                               t (+ t-start (* local-t (- t-end t-start)))
                               target-dist (* t total-dist)
                               orientation (find-orientation-at-dist orientations target-dist total-dist)
                               transformed-2d (transform-fn base-shape t)
                               temp-state (-> state
                                              (assoc :position (:position orientation))
                                              (assoc :heading (:heading orientation))
                                              (assoc :up (:up orientation)))]
                           (stamp-shape temp-state transformed-2d))))]
        (build-sweep-mesh new-rings false creation-pose)))))

(defn finalize-loft
  "Internal: finalize loft by generating rings at N steps with interpolated orientations.
   When corners exist, generates separate meshes for each segment (no joint mesh).
   Called at end of loft macro."
  [state]
  (let [original-total-dist (:loft-total-dist state)
        base-shape (:loft-base-shape state)
        transform-fn (:loft-transform-fn state)
        original-orientations (:loft-orientations state)
        corners (:loft-corners state)
        steps (:loft-steps state)
        creation-pose {:position (:loft-start-pos state)
                       :heading (:loft-start-heading state)
                       :up (:loft-start-up state)}
        initial-radius (shape-radius base-shape)]
    (if (and (pos? original-total-dist) base-shape transform-fn (>= (count original-orientations) 2))
      (let [{:keys [orientations total-dist]}
            (process-loft-corners original-orientations corners initial-radius original-total-dist)]
        (if (empty? corners)
          ;; No corners: generate single mesh as before
          (let [has-holes? (boolean (:holes base-shape))
                mesh (if has-holes?
                       (let [ring-data-vec (vec
                                            (for [i (range (inc steps))]
                                              (let [t (/ i steps)
                                                    target-dist (* t total-dist)
                                                    orientation (find-orientation-at-dist orientations target-dist total-dist)
                                                    transformed-2d (transform-fn base-shape t)
                                                    temp-state (-> state
                                                                   (assoc :position (:position orientation))
                                                                   (assoc :heading (:heading orientation))
                                                                   (assoc :up (:up orientation)))]
                                                (stamp-shape-with-holes temp-state transformed-2d))))]
                         (build-sweep-mesh-with-holes ring-data-vec creation-pose))
                       (let [new-rings (vec
                                        (for [i (range (inc steps))]
                                          (let [t (/ i steps)
                                                target-dist (* t total-dist)
                                                orientation (find-orientation-at-dist orientations target-dist total-dist)
                                                transformed-2d (transform-fn base-shape t)
                                                temp-state (-> state
                                                               (assoc :position (:position orientation))
                                                               (assoc :heading (:heading orientation))
                                                               (assoc :up (:up orientation)))]
                                            (stamp-shape temp-state transformed-2d))))]
                         (build-sweep-mesh new-rings false creation-pose)))
                mesh-with-material (when mesh
                                     (cond-> mesh
                                       (:material state) (assoc :material (:material state))))]
            (if mesh-with-material
              (-> state
                  (update :meshes conj mesh-with-material)
                  (assoc :sweep-rings [])
                  (assoc :stamped-shape nil)
                  (dissoc :loft-base-shape :loft-transform-fn :loft-steps
                          :loft-total-dist :loft-start-pos :loft-start-heading
                          :loft-start-up :loft-orientations :loft-corners))
              (-> state
                  (assoc :sweep-rings [])
                  (assoc :stamped-shape nil)
                  (dissoc :loft-base-shape :loft-transform-fn :loft-steps
                          :loft-total-dist :loft-start-pos :loft-start-heading
                          :loft-start-up :loft-orientations :loft-corners))))
          ;; Has corners: generate separate mesh for each segment
          (let [;; Calculate t values at each corner (based on adjusted distances)
                sorted-corners (sort-by :dist-at-corner corners)
                corner-t-values (mapv (fn [c]
                                        (/ (:dist-at-corner c) total-dist))
                                      sorted-corners)
                ;; Segment boundaries: [0, corner1-t, corner2-t, ..., 1]
                segment-bounds (vec (concat [0] corner-t-values [1]))
                num-segments (dec (count segment-bounds))
                steps-per-segment (max 4 (quot steps num-segments))
                ;; Generate mesh for each segment
                segment-meshes (vec
                                (for [seg-idx (range num-segments)]
                                  (let [t-start (nth segment-bounds seg-idx)
                                        t-end (nth segment-bounds (inc seg-idx))]
                                    (generate-loft-segment-mesh
                                     state base-shape transform-fn orientations
                                     total-dist t-start t-end steps-per-segment creation-pose))))
                valid-meshes (filterv some? segment-meshes)
                ;; Add material to each mesh
                meshes-with-material (if (:material state)
                                       (mapv #(assoc % :material (:material state)) valid-meshes)
                                       valid-meshes)]
            (-> state
                (update :meshes into meshes-with-material)
                (assoc :sweep-rings [])
                (assoc :stamped-shape nil)
                (dissoc :loft-base-shape :loft-transform-fn :loft-steps
                        :loft-total-dist :loft-start-pos :loft-start-heading
                        :loft-start-up :loft-orientations :loft-corners)))))
      ;; Not enough data - just clear
      (-> state
          (assoc :sweep-rings [])
          (assoc :stamped-shape nil)
          (dissoc :loft-base-shape :loft-transform-fn :loft-steps
                  :loft-total-dist :loft-start-pos :loft-start-heading
                  :loft-start-up :loft-orientations :loft-corners)))))

;; ============================================================
;; Loft from path (unified extrusion with transform)
;; ============================================================

(defn analyze-loft-path
  "Analyze a path for loft operation.
   Similar to analyze-open-path but tracks where corners are without
   pre-computing shorten values (since radius changes along the path).

   Returns: [{:cmd :f :dist d :has-corner-after bool :rotations-after [...]}]"
  [commands]
  (let [cmds (vec commands)
        n (count cmds)
        forwards (keep-indexed (fn [i c] (when (= :f (:cmd c)) [i c])) cmds)
        n-forwards (count forwards)]
    (vec
     (map-indexed
      (fn [fwd-idx [idx cmd]]
        (let [dist (first (:args cmd))
              is-last (= fwd-idx (dec n-forwards))
              ;; Collect all rotations after this forward until next forward or end
              rotations-after (loop [i (inc idx)
                                     rots []]
                                (if (>= i n)
                                  rots
                                  (let [c (nth cmds i)]
                                    (if (is-rotation? (:cmd c))
                                      (recur (inc i) (conj rots c))
                                      rots))))
              ;; Check if there's a corner rotation after
              has-corner-after (and (not is-last)
                                    (some #(is-corner-rotation? (:cmd %)) rotations-after))]
          {:cmd :f
           :dist dist
           :has-corner-after has-corner-after
           :rotations-after rotations-after}))
      forwards))))

(defn- calc-t-at-dist
  "Calculate the parameter t (0-1) at a given distance along total path length."
  [dist total-dist]
  (if (pos? total-dist)
    (/ dist total-dist)
    0))

(defn- strip-shell
  "Remove shell metadata from a shape, returning a plain shape for solid loft."
  [s]
  (dissoc s :shell-mode :shell-thickness :shell-values :shell-offsets
          :shell-cap-top :shell-cap-bottom))

(defn- resolve-cap-shape
  "Resolve a cap-spec into a decorated shape at a given base shape.
   cap-spec can be:
   - a number → nil (solid cap, no decoration)
   - a map with :shape → use provided shape directly
   - a map with :style → generate pattern on base-shape automatically
   base-shape is the shell shape at the cap's t position, already stripped.
   shell-thickness is the shell wall thickness (for expanding to outer wall)."
  [cap-spec base-shape shell-thickness]
  (when (map? cap-spec)
    (if (:shape cap-spec)
      ;; Legacy: pre-built decorated shape
      (:shape cap-spec)
      ;; Style-based: generate pattern on the actual shape at this t
      (let [style (:style cap-spec)
            half-t (* 0.5 shell-thickness)
            ;; Expand shape to match outer wall radius
            expanded (clipper/shape-offset base-shape half-t)]
        (when expanded
          (case style
            :voronoi (voronoi/voronoi-shell expanded
                                            :cells (or (:cells cap-spec) 20)
                                            :wall (or (:wall cap-spec) 1.5)
                                            :seed (or (:seed cap-spec) 0)
                                            :relax (or (:relax cap-spec) 2)
                                            :resolution (or (:resolution cap-spec)
                                                            (extrusion/default-segments 0.5)))
            :grid    (clipper/pattern-tile expanded
                                           (shape/circle-shape
                                            (or (:hole cap-spec) 1.5)
                                            (or (:hole-segments cap-spec) 16))
                                           :spacing (or (:spacing cap-spec) [5 5])
                                           :inset (or (:inset cap-spec) 0))
            :solid   nil
            (throw (js/Error. (str "Unknown cap :style " style)))))))))

(defn- generate-cap-mesh
  "Generate a cap sweep mesh for a shell cap section.
   cap-end is :top or :bottom.
   cap-spec is either:
   - a number (thickness → solid cap)
   - a map with :style (auto-generated pattern on actual shape)
   - a map with :shape (pre-built decorated shape, legacy)
   Returns a mesh or nil."
  [state shape transform-fn total-eff-dist cap-spec cap-end creation-pose steps]
  (let [decorated? (map? cap-spec)
        cap-thickness (if decorated? (:thickness cap-spec) cap-spec)
        cap-ratio (/ cap-thickness total-eff-dist)
        [t-start t-end] (if (= cap-end :top)
                          [(max 0 (- 1 cap-ratio)) 1]
                          [0 (min 1 cap-ratio)])
        cap-steps (max 2 (Math/round (* steps cap-ratio)))
        heading (:heading state)
        end-pos (:position state)
        start-pos (v- end-pos (v* heading cap-thickness))
        [pos0 pos1] (if (= cap-end :top)
                      [start-pos end-pos]
                      [end-pos (v+ end-pos (v* heading cap-thickness))])
        ;; Get shell thickness for shape expansion
        probe (transform-fn shape (if (= cap-end :top) 1 0))
        shell-thickness (or (:shell-thickness probe) 0)
        ;; Resolve cap shape at the reference t
        ref-t (if (= cap-end :top) t-start t-end)
        ref-base (strip-shell (transform-fn shape ref-t))
        cap-shape (when decorated?
                    (resolve-cap-shape cap-spec ref-base shell-thickness))
        rings (vec
               (for [i (range (inc cap-steps))]
                 (let [local-t (/ i cap-steps)
                       t (+ t-start (* local-t (- t-end t-start)))
                       pos (v+ pos0 (v* (v- pos1 pos0) local-t))
                       raw-shape (strip-shell (transform-fn shape t))
                       final-shape (if cap-shape
                                     (assoc cap-shape
                                            :centered? (:centered? raw-shape))
                                     raw-shape)
                       temp-state (assoc state :position pos)]
                   (if (:holes final-shape)
                     (stamp-shape-with-holes temp-state final-shape)
                     (stamp-shape temp-state final-shape)))))
        has-holes? (boolean (:holes (or cap-shape ref-base)))]
    (when (>= (count rings) 2)
      (if has-holes?
        (build-sweep-mesh-with-holes rings creation-pose true)
        (build-sweep-mesh rings false creation-pose true)))))

(defn loft-from-path
  "Loft a shape along a path with a transform function.

   transform-fn: (fn [shape t]) where t goes from 0 to 1
   steps: number of rings to generate (defaults to (default-segments state 1))

   At corners, generates SEPARATE meshes for each segment (no joint mesh).
   Use mesh-union to combine them if needed."
  ([state shape transform-fn path]
   (loft-from-path state shape transform-fn path (extrusion/default-segments state 1)))
  ([state shape transform-fn path steps]
   (if-not (and (shape? shape) (is-path? path))
     state
     (let [creation-pose {:position (:position state)
                          :heading (:heading state)
                          :up (:up state)}
           commands (:commands path)
           ;; Apply initial rotations before the first forward command
           ;; (bezier paths often start with th/tv to orient toward the first chord)
           initial-rotations (take-while #(not= :f (:cmd %)) commands)
           state-with-initial-heading (reduce apply-rotation-to-state state initial-rotations)
           segments (analyze-loft-path commands)
           n-segments (count segments)
           initial-radius (shape-radius shape)

           ;; Detect shell mode from the first transformed shape
           ;; (shell shape-fn attaches :shell-mode to the shape)
           probe-shape (transform-fn shape 0)
           shell-mode? (boolean (:shell-mode probe-shape))
           shell-cap-top (:shell-cap-top probe-shape)
           shell-cap-bottom (:shell-cap-bottom probe-shape)

           ;; Polymorphic helpers (shell vs holes vs plain)
           has-holes? (and (not shell-mode?) (boolean (:holes shape)))
           do-stamp (cond
                      shell-mode?
                      (fn [state s]
                        (let [base-ring (stamp-shape state s)
                              half-t (* 0.5 (:shell-thickness s))
                              vals (:shell-values s)
                              offs (:shell-offsets s)
                              outer (extrusion/generate-shell-ring
                                     base-ring half-t vals false :offsets offs)
                              inner (extrusion/generate-shell-ring
                                     base-ring half-t vals true :offsets offs)]
                          {:outer outer :inner inner :values vals}))
                      has-holes? stamp-shape-with-holes
                      :else stamp-shape)
           get-outer (if (or shell-mode? has-holes?) :outer identity)
           do-build (cond
                      shell-mode?
                      (fn [rings cp caps?]
                        (extrusion/build-shell-sweep-mesh rings cp caps?))
                      has-holes?
                      (fn [rings cp caps?]
                        (build-sweep-mesh-with-holes rings cp caps?))
                      :else
                      (fn [rings cp caps?]
                        (build-sweep-mesh rings false cp caps?)))
           do-round-corners (cond
                              shell-mode? (fn [& _] nil)
                              has-holes? generate-round-corner-ring-data
                              :else generate-round-corner-rings)
           do-tapered-corners (cond
                                shell-mode? (fn [& _] nil)
                                has-holes? generate-tapered-corner-ring-data
                                :else generate-tapered-corner-rings)
           midpoint-ring (cond
                           shell-mode?
                           (fn [r1 r2]
                             {:outer (mapv (fn [p1 p2] (v+ p1 (v* (v- p2 p1) 0.5)))
                                           (:outer r1) (:outer r2))
                              :inner (mapv (fn [p1 p2] (v+ p1 (v* (v- p2 p1) 0.5)))
                                           (:inner r1) (:inner r2))
                              :values (mapv (fn [v1 v2] (* 0.5 (+ v1 v2)))
                                            (:values r1) (:values r2))})
                           has-holes?
                           (fn [r1 r2]
                             {:outer (mapv (fn [p1 p2] (v+ p1 (v* (v- p2 p1) 0.5)))
                                           (:outer r1) (:outer r2))
                              :holes (when (:holes r1)
                                       (mapv (fn [h1 h2]
                                               (mapv (fn [p1 p2] (v+ p1 (v* (v- p2 p1) 0.5))) h1 h2))
                                             (:holes r1) (:holes r2)))})
                           :else
                           (fn [r1 r2]
                             (mapv (fn [p1 p2] (v+ p1 (v* (v- p2 p1) 0.5))) r1 r2)))
           make-cap-mesh (cond
                           shell-mode?
                           (fn [& _] nil) ;; shell caps handled inside build-shell-sweep-mesh
                           has-holes?
                           (fn [ring-or-data normal]
                             (let [outer (:outer ring-or-data)
                                   holes (or (:holes ring-or-data) [])]
                               (when (>= (count outer) 3)
                                 {:type :mesh :primitive :cap
                                  :vertices (vec (concat outer (apply concat holes)))
                                  :faces (triangulate-cap-with-holes outer holes 0 normal false)})))
                           :else
                           (fn [ring-or-data normal]
                             (when (>= (count ring-or-data) 3)
                               {:type :mesh :primitive :cap
                                :vertices (vec ring-or-data)
                                :faces (triangulate-cap ring-or-data 0 normal false)})))

           ;; Total visible path distance (does NOT include hidden/shortening)
           total-visible-dist (reduce + 0 (map :dist segments))
           ;; Read joint-mode early so we can skip shortening for :flat
           joint-mode (or (:joint-mode state) :flat)]
       (letfn [(compute-corner-data []
                 ;; Use visible distances only for taper/radius; hidden distance is only positional
                 (loop [idx 0
                        s state-with-initial-heading
                        acc-visible 0
                        results []]
                   (if (>= idx n-segments)
                     results
                     (let [seg (nth segments idx)
                           seg-dist (:dist seg)
                           has-corner (:has-corner-after seg)
                           rotations (:rotations-after seg)
                           dist-at-corner (+ acc-visible seg-dist)

                           ;; Get rotation angle
                           s-temp (reduce apply-rotation-to-state s rotations)
                           old-heading (:heading s)
                           new-heading (:heading s-temp)
                           cos-angle (dot old-heading new-heading)
                           turn-angle (if (< cos-angle 0.9999)
                                        (Math/acos (min 1 (max -1 cos-angle)))
                                        0)

                           ;; R_p/R_n: use simple miter formula for all joint modes
                           ;; shorten = radius * tan(angle/2) - same as extrude
                           {:keys [r-p r-n]}
                           (if (and has-corner (> turn-angle 0.01))
                             (let [turn-angle-deg (* turn-angle (/ 180 Math/PI))
                                   shorten (calc-shorten-for-angle turn-angle-deg initial-radius)]
                               {:r-p shorten :r-n shorten})
                             {:r-p 0 :r-n 0})

                           corner-pos (v+ (:position s) (v* (:heading s) seg-dist))
                           s-at-corner (assoc s :position corner-pos)
                           s-rotated (reduce apply-rotation-to-state s-at-corner rotations)]

                       (recur (inc idx)
                              s-rotated
                              (+ acc-visible seg-dist)
                              (conj results {:r-p r-p :r-n r-n :turn-angle turn-angle}))))))]

         (let [corner-data (compute-corner-data)
               ;; Total effective distance used for taper (subtract start offset and end pullback)
               total-effective-dist
               (loop [seg-idx 0
                      prev-rn 0
                      acc 0]
                 (if (>= seg-idx n-segments)
                   acc
                   (let [seg (nth segments seg-idx)
                         seg-dist (:dist seg)
                         has-corner (:has-corner-after seg)
                         {:keys [r-p]} (nth corner-data seg-idx)
                         eff (-> seg-dist
                                 (- prev-rn)
                                 (- (if has-corner r-p 0))
                                 (max 0.001))]
                     (recur (inc seg-idx)
                            (if has-corner (:r-n (nth corner-data seg-idx)) 0)
                            (+ acc eff)))))]
           (if (or (< n-segments 1) (<= total-visible-dist 0))
             state
             ;; Generate rings, accumulating across smooth junctions.
             ;; Only split into separate meshes at real corners (th/tv/tr).
             (let [joint-mode (or (:joint-mode state) :flat)
                   result
                   (loop [seg-idx 0
                          s state-with-initial-heading
                          taper-acc 0         ;; effective distance travelled so far (for t)
                          prev-rn 0           ;; carry start offset from previous corner
                          acc-rings []        ;; accumulated rings for current smooth section
                          finished-meshes []  ;; completed meshes (split at corners)
                          loft-first-ring nil  ;; first ring of entire loft (for start cap)
                          loft-second-ring nil] ;; second ring (for start cap normal)
                     (if (>= seg-idx n-segments)
                       ;; Flush remaining accumulated rings as final mesh.
                       ;; When no corner segments exist, build WITH caps so cap faces
                       ;; share vertex indices with side faces (required for manifold mesh).
                       ;; When corners exist, caps must be separate meshes since intermediate
                       ;; segments shouldn't have caps at their boundaries.
                       (let [no-corners (empty? finished-meshes)
                             final-meshes (if (>= (count acc-rings) 2)
                                            (conj finished-meshes
                                                  (do-build (vec acc-rings) creation-pose no-corners))
                                            finished-meshes)
                             ;; Generate separate cap meshes only when corners exist
                             last-ring (when-not no-corners (last acc-rings))
                             second-to-last (when (and (not no-corners) (>= (count acc-rings) 2))
                                              (nth acc-rings (- (count acc-rings) 2)))
                             bottom-normal (when (and (not no-corners) loft-first-ring loft-second-ring)
                                             (v* (normalize (v- (ring-centroid (get-outer loft-second-ring))
                                                                (ring-centroid (get-outer loft-first-ring))))
                                                 -1))
                             top-normal (when (and last-ring second-to-last)
                                          (normalize (v- (ring-centroid (get-outer last-ring))
                                                         (ring-centroid (get-outer second-to-last)))))
                             bottom-cap (when (and loft-first-ring bottom-normal)
                                          (make-cap-mesh loft-first-ring bottom-normal))
                             top-cap (when (and last-ring top-normal)
                                       (make-cap-mesh last-ring top-normal))
                             ;; Generate thick caps for shell mode
                             solid-cap-top
                             (when (and shell-mode? shell-cap-top (pos? total-effective-dist))
                               (let [m (generate-cap-mesh
                                        s shape transform-fn total-effective-dist
                                        shell-cap-top :top creation-pose steps)]
                                 (js/console.log "shell cap-top:" (if m
                                                                    (str (count (:vertices m)) " verts, "
                                                                         (count (:faces m)) " faces")
                                                                    "nil"))
                                 m))
                             solid-cap-bottom
                             (when (and shell-mode? shell-cap-bottom (pos? total-effective-dist))
                               (let [m (generate-cap-mesh
                                        state-with-initial-heading shape transform-fn total-effective-dist
                                        shell-cap-bottom :bottom creation-pose steps)]
                                 (js/console.log "shell cap-bottom:" (if m
                                                                       (str (count (:vertices m)) " verts, "
                                                                            (count (:faces m)) " faces")
                                                                       "nil"))
                                 m))
                             ;; Combine shell + caps (just concatenate — no boolean union)
                             all-meshes (cond-> final-meshes
                                          bottom-cap (conj bottom-cap)
                                          top-cap (conj top-cap)
                                          solid-cap-top (conj solid-cap-top)
                                          solid-cap-bottom (conj solid-cap-bottom))]
                         {:meshes all-meshes
                          :state s})
                       (let [seg (nth segments seg-idx)
                             seg-dist (:dist seg)
                             has-corner (:has-corner-after seg)
                             rotations (:rotations-after seg)

                             ;; Get pre-calculated corner data
                             {:keys [r-p r-n]} (nth corner-data seg-idx)

                             ;; Calculate seg-steps FIRST (it's used in min-step calculation)
                             ;; For smooth junctions (bezier micro-segments), use max 1 since
                             ;; the path already provides fine-grained sampling. Forcing max 4
                             ;; creates 64+ overlapping rings on tight curves.
                             min-seg-steps (if has-corner 4 1)
                             seg-steps (max min-seg-steps (Math/round (* steps (/ seg-dist total-visible-dist))))

                             ;; Remaining distance to the original corner after start offset
                             remaining-to-corner (max 0.0 (- seg-dist prev-rn))
                             ;; Effective length: pull back by r_p and also leave space for next start (r_n)
                             ;; Clamp to at least one step length so the last ring doesn't reach the corner
                             min-step (/ remaining-to-corner (max min-seg-steps seg-steps))
                             ;; Pull back by r-p before the corner (r-n is for the next segment's start)
                             effective-seg-dist (max min-step
                                                     (- remaining-to-corner
                                                        (if has-corner r-p 0)))

                             ;; Original corner position (before pullback)
                             corner-base (v+ (:position s) (v* (:heading s) remaining-to-corner))

                             ;; Generate rings for this segment.
                             ;; Skip i=0 if we already have accumulated rings (smooth continuation)
                             ;; to avoid duplicate rings at the junction.
                             start-i (if (seq acc-rings) 1 0)
                             seg-rings
                             (vec
                              (for [i (range start-i (inc seg-steps))]
                                (let [local-t (/ i seg-steps)
                                      dist-in-seg (* local-t effective-seg-dist)
                                      taper-at (+ taper-acc dist-in-seg)
                                      clamped-t (if (pos? total-effective-dist)
                                                  (min 1 (/ taper-at total-effective-dist))
                                                  0)
                                      pos (v+ (:position s) (v* (:heading s) dist-in-seg))
                                      transformed-shape (transform-fn shape clamped-t)
                                      temp-state (assoc s :position pos)]
                                  (do-stamp temp-state transformed-shape))))

                             ;; Merge into accumulated rings
                             new-acc-rings (into acc-rings seg-rings)

                             ;; Track first/second ring for caps
                             new-first-ring (or loft-first-ring (first new-acc-rings))
                             new-second-ring (or loft-second-ring
                                                 (when (>= (count new-acc-rings) 2)
                                                   (second new-acc-rings)))

                             ;; Apply rotations to get new heading (rotate at corner position)
                             s-at-corner (assoc s :position corner-base)
                             s-rotated (reduce apply-rotation-to-state s-at-corner rotations)

                             ;; Heading change detection (for smooth transition rings)
                             old-heading (:heading s)
                             new-heading (:heading s-rotated)
                             cos-a (dot old-heading new-heading)
                             heading-angle (when (< cos-a 0.9998)
                                             (Math/acos (min 1 (max -1 cos-a))))

                             ;; Taper distance advances by effective length
                             new-taper-acc (+ taper-acc effective-seg-dist)]

                         (if has-corner
                           ;; Corner: flush accumulated rings as a mesh, generate corner bridge
                           (let [;; Build mesh from accumulated rings (no caps - will be combined)
                                 section-mesh (when (>= (count new-acc-rings) 2)
                                                (do-build (vec new-acc-rings) creation-pose false))

                                 ;; Next segment starts at corner + R_n along new heading
                                 next-start-pos (v+ corner-base (v* (:heading s-rotated) r-n))
                                 s-next (assoc s-rotated :position next-start-pos)

                                 ;; Corner mesh (bridge end ring to next start ring)
                                 t-end (if (pos? total-effective-dist)
                                         (min 1 (/ new-taper-acc total-effective-dist))
                                         0)
                                 end-ring (last new-acc-rings)
                                 next-start-ring (let [shape-next (transform-fn shape t-end)
                                                       temp-state (assoc s-rotated :position next-start-pos)]
                                                   (do-stamp temp-state shape-next))
                                 ;; Calculate corner radius from transformed shape
                                 corner-shape (transform-fn shape t-end)
                                 corner-radius (shape-radius corner-shape)
                                 ;; Calculate round steps based on resolution settings
                                 corner-angle-deg (when (and (= joint-mode :round) heading-angle)
                                                    (* heading-angle (/ 180 Math/PI)))
                                 round-steps (when corner-angle-deg
                                               (calc-round-steps state corner-angle-deg))
                                 ;; Generate corner rings based on joint-mode
                                 mid-rings (case joint-mode
                                             :flat nil
                                             :round (when heading-angle
                                                      (do-round-corners
                                                       end-ring corner-base
                                                       old-heading new-heading
                                                       (or round-steps 4) corner-radius))
                                             :tapered (let [generated (do-tapered-corners
                                                                       end-ring corner-base
                                                                       old-heading new-heading)]
                                                        (when (seq generated) generated))
                                             ;; default: tapered
                                             (let [generated (do-tapered-corners
                                                              end-ring corner-base
                                                              old-heading new-heading)]
                                               (when (seq generated) generated)))
                                 ;; For :flat, connect directly without mid-rings
                                 ;; For other modes, fallback to a midpoint ring if no mid-rings
                                 fallback-mid (when (and (not= joint-mode :flat)
                                                         end-ring next-start-ring (nil? mid-rings))
                                                [(midpoint-ring end-ring next-start-ring)])
                                 c-rings (cond
                                           ;; Has mid-rings (round/tapered with rings)
                                           mid-rings (concat [end-ring] mid-rings [next-start-ring])
                                           ;; Flat mode: direct connection (no mid-rings)
                                           (= joint-mode :flat) (when (and end-ring next-start-ring)
                                                                  [end-ring next-start-ring])
                                           ;; Other modes with fallback
                                           fallback-mid (concat [end-ring] fallback-mid [next-start-ring])
                                           :else nil)
                                 ;; Corner mesh without caps (caps would create internal surfaces)
                                 corner-mesh (when c-rings
                                               (assoc (do-build (vec c-rings) creation-pose false)
                                                      :creation-pose creation-pose))]

                             (recur (inc seg-idx)
                                    s-next
                                    new-taper-acc
                                    r-n
                                    [next-start-ring]
                                    (cond-> finished-meshes
                                      section-mesh (conj section-mesh)
                                      corner-mesh (conj corner-mesh))
                                    new-first-ring
                                    new-second-ring))

                           ;; No corner: smooth junction — use inner-pivot transition rings
                           ;; to prevent ring overlap on the inner side of tight curves
                           (let [end-ring (last new-acc-rings)
                                 ;; Current radius at this taper position
                                 current-t (if (pos? total-effective-dist)
                                             (min 1 (/ new-taper-acc total-effective-dist))
                                             0)
                                 current-shape (transform-fn shape current-t)
                                 current-radius (shape-radius current-shape)
                                 ;; Generate inner-pivot transition rings if heading changed
                                 smooth-rings
                                 (if (and heading-angle end-ring (seq rotations))
                                   (let [n-smooth (max 1 (int (Math/ceil (/ heading-angle (/ Math/PI 12)))))]
                                     (do-round-corners
                                      end-ring corner-base old-heading new-heading
                                      n-smooth current-radius))
                                   [])
                                 ;; Continue from last transition ring's centroid for continuity
                                 next-start-pos (if (seq smooth-rings)
                                                  (ring-centroid (get-outer (last smooth-rings)))
                                                  corner-base)
                                 s-next (assoc s-rotated :position next-start-pos)
                                 updated-acc (into new-acc-rings smooth-rings)]
                             (recur (inc seg-idx)
                                    s-next
                                    new-taper-acc
                                    0
                                    updated-acc
                                    finished-meshes
                                    new-first-ring
                                    new-second-ring))))))

                   segment-meshes (:meshes result)
                   final-state (:state result)]

               (if (empty? segment-meshes)
                 state
                 ;; Add all segment meshes with material to state
                 (let [meshes-with-material (if (:material state)
                                              (mapv #(schema/assert-mesh!
                                                      (assoc % :material (:material state)))
                                                    segment-meshes)
                                              (mapv schema/assert-mesh! segment-meshes))]
                   (update final-state :meshes into meshes-with-material)))))))))))
