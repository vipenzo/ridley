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
(def corner-rotation? extrusion/corner-rotation?)
(def calc-shorten-for-angle extrusion/calc-shorten-for-angle)
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
              ;; Check if there's a corner rotation after. A bezier's tessellated
              ;; steps are tagged :smooth (corner-rotation? excludes them) so the
              ;; curve isn't shortened into a self-intersecting fold — the reason
              ;; pure-extrude-path routes bezier rails here instead of extrude.
              has-corner-after (and (not is-last)
                                    (some corner-rotation? rotations-after))]
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
   Use mesh-union to combine them if needed. The miter joining two segments
   shares one cross-section, so the profile is held constant at the corner's
   t value across the joint (the taper/shape-fn pauses through a sharp bend;
   the effect scales with the turn angle). Smooth curves aren't corners."
  ([state shape transform-fn path]
   (loft-from-path state shape transform-fn path (extrusion/default-segments state 1)))
  ([state shape transform-fn path steps]
   (if-not (and (shape? shape) (is-path? path))
     state
     (let [creation-pose {:position (:position state)
                          :heading (:heading state)
                          :up (:up state)}
           commands (@extrusion/path-micro-commands-ref path)
           ;; Apply initial rotations before the first forward command
           ;; (bezier paths often start with th/tv to orient toward the first chord)
           initial-rotations (take-while #(not= :f (:cmd %)) commands)
           state-with-initial-heading (reduce apply-rotation-to-state state initial-rotations)
           ;; Sweep invariant (frame-whole): the rail must begin in the turtle's frame.
           _ (extrusion/validate-rail-start-frame! state initial-rotations)
           ;; Realizability: reject a corner whose miter folds the section back
           ;; through the tube. Fed by the SAME directional projection extrude uses
           ;; (analyze-open-path-dir: corner-inner-extent in the stamp frame, wall-
           ;; aware for shell via :shell-thickness on `shape`) so both operators
           ;; reject identically. See extrusion/validate-corner-realizability!.
           _ (extrusion/validate-corner-realizability!
              (extrusion/analyze-open-path-dir commands shape state))
           ;; Cap carve-out (mirrors extrude-from-path / extrude-with-holes-from-path,
           ;; extrusion.cljs split-leading-cap): when the leading rotations end in an
           ;; arc's :lead half-step or a bezier's :bez-cap :lead th/tv, stamp the
           ;; FIRST ring with the pre-cap frame so the start cap stays perpendicular
           ;; to the INCOMING heading. That leading rotation is a tessellation
           ;; artifact (midpoint integration / analytic-vs-chord veer), not a real
           ;; cusp, so the cap must not tilt by it; the spine still advances along
           ;; the full initial heading. Rails with no leading cap: start-cap-state ==
           ;; state-with-initial-heading (no-op).
           start-cap-state (:cap-state (extrusion/split-leading-cap state initial-rotations))
           segments (analyze-loft-path commands)
           n-segments (count segments)

           ;; Detect shell mode from the first transformed shape
           ;; (shell shape-fn attaches :shell-mode to the shape)
           probe-shape (transform-fn shape 0)
           shell-mode? (boolean (:shell-mode probe-shape))
           shell-smooth? (boolean (:shell-smooth probe-shape))
           shell-level (or (:shell-level probe-shape) 0.5)
           shell-cap-top (:shell-cap-top probe-shape)
           shell-cap-bottom (:shell-cap-bottom probe-shape)

           ;; Embroid mode: perforate an already-thin swept wall.
           ;; (embroid shape-fn attaches :embroid-mode to the shape)
           embroid-mode? (boolean (:embroid-mode probe-shape))
           embroid-level (or (:embroid-level probe-shape) 0.5)
           ;; embroid shares shell's dual-ring ({:outer :inner :values})
           ;; machinery for accumulation, midpoints and corner handling
           dual-ring? (or shell-mode? embroid-mode?)

           ;; Profile marks → mesh anchors (resolved on the base section, stamped
           ;; through the first-ring frame). embroid carries the wall :offset so
           ;; centerline marks track the shifted wall, and :embroid-half-width so
           ;; `move-to … :face :outer/:inner` can step to either wall face.
           section-2d (extrusion/resolve-section-anchors probe-shape)
           section-3d (let [anchors (extrusion/section-anchors->3d
                                     section-2d
                                     (extrusion/compute-stamp-transform
                                      state-with-initial-heading probe-shape)
                                     (or (:embroid-offset probe-shape) [0 0]))
                            hw (:embroid-half-width probe-shape)]
                        (if hw
                          (into {} (map (fn [[k a]] [k (assoc a :half-width hw)])) anchors)
                          anchors))

           ;; Polymorphic helpers (shell vs embroid vs holes vs plain)
           has-holes? (and (not dual-ring?) (boolean (:holes shape)))
           do-stamp (cond
                      embroid-mode?
                      (fn [state s]
                        ;; Stamp the path-derived 2D faces into 3D at this pose.
                        (let [params (extrusion/compute-stamp-transform state s)
                              outer (extrusion/transform-2d-to-3d (:embroid-outer s) params)
                              inner (extrusion/transform-2d-to-3d (:embroid-inner s) params)]
                          {:outer outer :inner inner
                           :values (:embroid-values s)}))
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
           do-build (cond
                      embroid-mode?
                      (fn [rings cp caps?]
                        (extrusion/build-embroid-mesh rings cp caps? embroid-level))
                      shell-mode?
                      (fn [rings cp caps?]
                        (if shell-smooth?
                          (extrusion/build-shell-isocontour-mesh rings cp caps? shell-level)
                          (extrusion/build-shell-sweep-mesh rings cp caps?)))
                      has-holes?
                      (fn [rings cp caps?]
                        (build-sweep-mesh-with-holes rings cp caps?))
                      :else
                      (fn [rings cp caps?]
                        (build-sweep-mesh rings false cp caps?)))
           do-round-corners (cond
                              dual-ring? (fn [& _] nil)
                              has-holes? generate-round-corner-ring-data
                              :else generate-round-corner-rings)
           do-tapered-corners (cond
                                dual-ring? (fn [& _] nil)
                                has-holes? generate-tapered-corner-ring-data
                                :else generate-tapered-corner-rings)
           midpoint-ring (cond
                           dual-ring?
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
           ;; (make-cap-mesh removed: it built the redundant separate end caps —
           ;; the double-capping that produced non-manifold geometry. do-build now
           ;; caps each true end exactly once, inline.)

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

                           ;; R_p/R_n: directional miter — shorten = extent·tan(angle/2),
                           ;; where extent is the profile's reach toward THIS corner's
                           ;; inner normal in the stamp frame (corner-inner-extent),
                           ;; wall-aware for shell. Same magnitude extrude uses, so the
                           ;; two reject/build identically. (Was initial-radius =
                           ;; shape-radius, the centroid-max proxy.)
                           {:keys [r-p r-n]}
                           (if (and has-corner (> turn-angle 0.01))
                             (let [turn-angle-deg (* turn-angle (/ 180 Math/PI))
                                   extent (extrusion/corner-inner-extent
                                           shape old-heading new-heading (:up s))
                                   shorten (calc-shorten-for-angle turn-angle-deg extent)]
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
                          loft-second-ring nil ;; second ring (for start cap normal)
                          last-shape nil]     ;; 2D shape stamped on the last ring (for loft+ end-face)
                     (if (>= seg-idx n-segments)
                       ;; Flush remaining accumulated rings as final mesh.
                       ;; do-build caps the TRUE ends inline so cap faces share
                       ;; vertex indices with the side walls (manifold by
                       ;; construction, like extrude): the first section caps its
                       ;; :start, this final flush caps its :end. Intermediate
                       ;; sections sit between corner seams and stay open there.
                       ;; (The ends used to ALSO be capped by separate
                       ;; make-cap-mesh sub-meshes when corners existed — that
                       ;; double-capping produced coincident duplicate faces that
                       ;; the seam-weld stacked into non-manifold edges. Removed.)
                       (let [no-corners (empty? finished-meshes)
                             ;; A single smooth section caps both ends; otherwise
                             ;; this is the last section, so cap only its :end.
                             final-caps (if no-corners true :end)
                             final-meshes (if (>= (count acc-rings) 2)
                                            (conj finished-meshes
                                                  (do-build (vec acc-rings) creation-pose final-caps))
                                            finished-meshes)
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
                                          solid-cap-top (conj solid-cap-top)
                                          solid-cap-bottom (conj solid-cap-bottom))]
                         {:meshes all-meshes
                          :state s
                          :end-shape last-shape})
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
                                      ;; The VERY FIRST ring of the loft uses the
                                      ;; arc carve-out frame (start-cap-state); all
                                      ;; others use the running frame s. For non-arc
                                      ;; rails the two are identical.
                                      temp-state (if (and (zero? seg-idx) (zero? i))
                                                   (assoc start-cap-state :position pos)
                                                   (assoc s :position pos))]
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
                             new-taper-acc (+ taper-acc effective-seg-dist)

                             ;; 2D shape stamped on this segment's LAST ring. Its t is the
                             ;; ACTUAL clamped-t of that ring (i=seg-steps → local-t=1 →
                             ;; taper-at=new-taper-acc), NOT the nominal t=1: on corner+short
                             ;; segments the clamp diverges below 1 (see brief-loft-plus).
                             ;; transform-fn is pure, so recomputing at the same t reproduces
                             ;; the stamped shape exactly. This value also equals shape-next
                             ;; (the corner branch's next-start-ring shape, whose t-end shares
                             ;; new-taper-acc), so it is the last-stamped shape in every branch.
                             seg-last-shape (transform-fn shape
                                                          (if (pos? total-effective-dist)
                                                            (min 1 (/ new-taper-acc total-effective-dist))
                                                            0))]

                         (if has-corner
                           ;; Corner. For the plain loft (and its tapered/twisted/
                           ;; two-shape variants) we SPLICE the bridge rings into the
                           ;; single accumulated ring sequence so the final
                           ;; build-sweep-mesh emits ONE continuous mesh — like
                           ;; extrude: adjacent bands share ring vertex indices
                           ;; (manifold, no T-junction) and only the two TRUE ends are
                           ;; capped (no interior seam caps). Shell/embroid (dual-ring)
                           ;; and holed loft keep the per-segment build (their
                           ;; seam-aware builders already handle the corner).
                           (let [;; Next segment starts at corner + R_n along new heading
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
                                 ;; Bridge rings to place BETWEEN end-ring and
                                 ;; next-start-ring (the joint geometry).
                                 bridge-rings (cond mid-rings mid-rings
                                                    (= joint-mode :flat) []
                                                    fallback-mid fallback-mid
                                                    :else [])
                                 ;; Continuous build for the plain loft (closes (b));
                                 ;; per-segment flush for dual-ring / holed loft.
                                 continuous? (and (not dual-ring?) (not has-holes?))]

                             (if continuous?
                               ;; Splice the bridge into the single ring sequence and
                               ;; keep accumulating (no flush): one continuous mesh.
                               (recur (inc seg-idx)
                                      s-next
                                      new-taper-acc
                                      r-n
                                      (-> (vec new-acc-rings)
                                          (into bridge-rings)
                                          (conj next-start-ring))
                                      finished-meshes
                                      new-first-ring
                                      new-second-ring
                                      seg-last-shape)
                               ;; Per-segment: flush section + corner bridge as
                               ;; separate sub-meshes (seam-aware builders).
                               (let [section-caps (if (empty? finished-meshes) :start false)
                                     section-mesh (when (>= (count new-acc-rings) 2)
                                                    (do-build (vec new-acc-rings) creation-pose section-caps))
                                     c-rings (concat [end-ring] bridge-rings [next-start-ring])
                                     corner-mesh (when (>= (count c-rings) 2)
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
                                        new-second-ring
                                        seg-last-shape))))

                           ;; No corner: smooth junction. The per-step sections of
                           ;; a bezier rail are each stamped perpendicular to the
                           ;; interpolated heading, so they ALREADY form the smooth
                           ;; sweep of the curve — just continue the spine from the
                           ;; rail point (corner-base) along the new heading and keep
                           ;; accumulating into the single ring sequence.
                           ;;
                           ;; (Removed: the inner-pivot transition rings. They
                           ;; advanced the spine to the centroid of a ring rotated
                           ;; about a pivot shape-radius off the rail, so the spine
                           ;; drifted 2R·sin(α/2) per step — linear in the profile
                           ;; radius, accumulated along the curve: the sole source
                           ;; of the smooth-branch drift. They were added to guard
                           ;; inner-side ring overlap on tight curves but never did
                           ;; — the spine folded anyway — and this branch is already
                           ;; a continuous build, so removing them just lets the
                           ;; spine follow the rail like extrude. See accertamento
                           ;; 2026-06-23 + loft_smooth_spine_drift_net_test.cljs.
                           ;; NB: on a dormant rail (every step below the heading
                           ;; threshold) the old code already used corner-base with
                           ;; no rings, so that path stays byte-identical.)
                           (let [s-next (assoc s-rotated :position corner-base)]
                             (recur (inc seg-idx)
                                    s-next
                                    new-taper-acc
                                    0
                                    new-acc-rings
                                    finished-meshes
                                    new-first-ring
                                    new-second-ring
                                    seg-last-shape))))))

                   segment-meshes (:meshes result)
                   final-state (:state result)
                   end-shape (:end-shape result)]

               (if (empty? segment-meshes)
                 state
                 ;; Add all segment meshes with material to state. Profile anchors
                 ;; belong to the base section = the first segment mesh (corner
                 ;; and cap meshes get none).
                 (let [meshes-with-material (if (:material state)
                                              (mapv #(schema/assert-mesh!
                                                      (assoc % :material (:material state)))
                                                    segment-meshes)
                                              (mapv schema/assert-mesh! segment-meshes))
                       ;; Keep the sweep rail (always) so `:on` can locate a
                       ;; cross-section by mark or fraction; profile anchors and
                       ;; the profile path ride on the base-section mesh (first).
                       meshes-with-anchors (cond-> (update meshes-with-material 0
                                                           assoc :rail-path path)
                                             (seq section-3d)
                                             (update 0 assoc :anchors section-3d
                                                     :section-anchors section-2d
                                                     :profile-shape probe-shape
                                                     ;; the loft's per-t cross-section,
                                                     ;; so (slice-mesh m :on t) returns the
                                                     ;; MORPHED profile (taper/twist) — its
                                                     ;; :mark-refs ride the scaled points.
                                                     :profile-shape-fn (fn [t] (transform-fn shape t))))]
                   ;; :loft-end-shape is an ephemeral key consumed by pure-loft-path*
                   ;; (for loft+'s end-face). Invisible to plain-loft callers, which
                   ;; only read :meshes — mesh geometry is byte-identical.
                   (-> final-state
                       (update :meshes into meshes-with-anchors)
                       (assoc :loft-end-shape end-shape))))))))))))
