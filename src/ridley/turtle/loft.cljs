(ns ridley.turtle.loft
  "Loft operations: extrusion with shape transformation.

   This module contains loft-specific logic extracted from turtle/core.cljs.
   Loft allows extruding a shape along a path while transforming it (tapering, etc.)."
  (:require [ridley.schema :as schema]
            [ridley.math :as math]
            [ridley.manifold.core :as manifold]
            [ridley.turtle.extrusion :as extrusion]))

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
(def generate-round-corner-rings extrusion/generate-round-corner-rings)
(def generate-tapered-corner-rings extrusion/generate-tapered-corner-rings)
(def calc-round-steps extrusion/calc-round-steps)

;; ============================================================
;; Loft - extrusion with shape transformation
;; ============================================================

(defn stamp-loft
  "Internal: stamp a shape for loft with transform function.
   Similar to stamp but also stores the base shape and transform function.
   steps: number of intermediate steps (default 16)"
  ([state shape transform-fn] (stamp-loft state shape transform-fn 16))
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
  "Find interpolated orientation at a given distance along the path."
  [orientations target-dist total-dist]
  (if (zero? total-dist)
    (first orientations)
    (let [;; Find the two waypoints that bracket this distance
          n (count orientations)]
      (loop [i 0]
        (if (>= i (dec n))
          ;; Past end - return last
          (last orientations)
          (let [o1 (nth orientations i)
                o2 (nth orientations (inc i))
                d1 (or (:dist o1) 0)
                d2 (:dist o2)]
            (if (and (<= d1 target-dist) (< target-dist d2))
              ;; Interpolate between o1 and o2
              (let [segment-len (- d2 d1)
                    local-t (if (pos? segment-len)
                              (/ (- target-dist d1) segment-len)
                              0)]
                (interpolate-orientation o1 o2 local-t))
              (recur (inc i)))))))))

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
    (build-sweep-mesh new-rings false creation-pose)))

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
                               (stamp-shape temp-state transformed-2d))))
                mesh (build-sweep-mesh new-rings false creation-pose)
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

(defn loft-from-path
  "Loft a shape along a path with a transform function.

   transform-fn: (fn [shape t]) where t goes from 0 to 1
   steps: number of rings to generate (default 16)

   At corners, generates SEPARATE meshes for each segment (no joint mesh).
   Use mesh-union to combine them if needed."
  ([state shape transform-fn path] (loft-from-path state shape transform-fn path 16))
  ([state shape transform-fn path steps]
   (if-not (and (shape? shape) (is-path? path))
     state
     (let [creation-pose {:position (:position state)
                          :heading (:heading state)
                          :up (:up state)}
           commands (:commands path)
           segments (analyze-loft-path commands)
           n-segments (count segments)
           initial-radius (shape-radius shape)

           ;; Total visible path distance (does NOT include hidden/shortening)
           total-visible-dist (reduce + 0 (map :dist segments))
           ;; Read joint-mode early so we can skip shortening for :flat
           joint-mode (or (:joint-mode state) :flat)]
       (letfn [(compute-corner-data []
                 ;; Use visible distances only for taper/radius; hidden distance is only positional
                 (loop [idx 0
                        s state
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
                          s state
                          taper-acc 0         ;; effective distance travelled so far (for t)
                          prev-rn 0           ;; carry start offset from previous corner
                          acc-rings []        ;; accumulated rings for current smooth section
                          finished-meshes []] ;; completed meshes (split at corners)
                     (if (>= seg-idx n-segments)
                       ;; Flush any remaining accumulated rings as a final mesh (no internal caps)
                       {:meshes (if (>= (count acc-rings) 2)
                                  (conj finished-meshes
                                        (build-sweep-mesh (vec acc-rings) false creation-pose false))
                                  finished-meshes)
                        :state s}
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
                                  (stamp-shape temp-state transformed-shape))))

                             ;; Merge into accumulated rings
                             new-acc-rings (into acc-rings seg-rings)

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
                                                (build-sweep-mesh (vec new-acc-rings) false creation-pose false))

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
                                                   (stamp-shape temp-state shape-next))
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
                                                      (generate-round-corner-rings
                                                       end-ring corner-base
                                                       old-heading new-heading
                                                       (or round-steps 4) corner-radius))
                                             :tapered (let [generated (generate-tapered-corner-rings
                                                                       end-ring corner-base
                                                                       old-heading new-heading)]
                                                        (when (seq generated) generated))
                                             ;; default: tapered
                                             (let [generated (generate-tapered-corner-rings
                                                              end-ring corner-base
                                                              old-heading new-heading)]
                                               (when (seq generated) generated)))
                                 ;; For :flat, connect directly without mid-rings
                                 ;; For other modes, fallback to a midpoint ring if no mid-rings
                                 fallback-mid (when (and (not= joint-mode :flat)
                                                         end-ring next-start-ring (nil? mid-rings))
                                                [(mapv (fn [p1 p2] (v+ p1 (v* (v- p2 p1) 0.5)))
                                                       end-ring next-start-ring)])
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
                                               (assoc (build-sweep-mesh (vec c-rings)
                                                                        false creation-pose false)
                                                      :creation-pose creation-pose))]

                             (recur (inc seg-idx)
                                    s-next
                                    new-taper-acc
                                    r-n
                                    [next-start-ring]
                                    (cond-> finished-meshes
                                      section-mesh (conj section-mesh)
                                      corner-mesh (conj corner-mesh))))

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
                                     (generate-round-corner-rings
                                      end-ring corner-base old-heading new-heading
                                      n-smooth current-radius))
                                   [])
                                 ;; Continue from last transition ring's centroid for continuity
                                 next-start-pos (if (seq smooth-rings)
                                                  (ring-centroid (last smooth-rings))
                                                  corner-base)
                                 s-next (assoc s-rotated :position next-start-pos)
                                 updated-acc (into new-acc-rings smooth-rings)]
                             (recur (inc seg-idx)
                                    s-next
                                    new-taper-acc
                                    0
                                    updated-acc
                                    finished-meshes))))))

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

;; ============================================================
;; Bezier Loft (bloft) - Self-intersection safe loft for bezier paths
;; ============================================================

(defn- rings-intersect?
  "Check if two consecutive rings would intersect.
   Returns true if any vertex in ring2 moved 'backward' significantly
   relative to the direction from ring1's centroid to ring2's centroid.

   threshold-factor: how much backward movement (as fraction of shape radius)
                     triggers intersection detection. Default 0.1 = 10%.
                     Higher = less sensitive = fewer intersections detected.
                     Lower = more sensitive = more intersections detected.
   shape-radius: the radius of the shape being lofted."
  [ring1 ring2 threshold-factor shape-radius]
  (let [c1 (ring-centroid ring1)
        c2 (ring-centroid ring2)
        travel-dir (v- c2 c1)
        travel-len (Math/sqrt (dot travel-dir travel-dir))]
    (if (< travel-len 0.0001)
      true  ;; rings overlap, treat as intersection
      (let [travel-unit (v* travel-dir (/ 1.0 travel-len))
            threshold (* (- threshold-factor) shape-radius)
            result (some (fn [[v1 v2]]
                           (let [movement (v- v2 v1)
                                 forward (dot movement travel-unit)]
                             (< forward threshold)))
                         (map vector ring1 ring2))]
        result))))


(defn- walk-path-poses
  "Walk a path and sample turtle poses at regular distance intervals.
   Returns a vector of (n-samples + 1) poses: [{:position :heading :up :t} ...]
   where t ranges from 0.0 to 1.0.

   By actually walking the path with apply-rotation-to-state, the heading/up
   frame stays coherent through all rotations — avoiding the degenerate frame
   that can occur when interpolating within segments."
  [initial-state path n-samples]
  (let [commands (vec (:commands path))
        n-cmds (count commands)
        ;; Total forward distance
        total-dist (reduce (fn [acc cmd]
                             (if (= :f (:cmd cmd))
                               (+ acc (Math/abs (first (:args cmd))))
                               acc))
                           0 commands)
        sample-interval (if (pos? n-samples)
                          (/ total-dist n-samples)
                          total-dist)]
    (if (<= total-dist 0)
      [{:position (:position initial-state)
        :heading (:heading initial-state)
        :up (:up initial-state)
        :t 0.0}]
      ;; Walk the path. For each :f command, check if any sample points fall
      ;; within it. Re-enter the loop for the same :f until all its samples
      ;; are emitted, then advance to the next command.
      (loop [cmd-idx 0
             s initial-state
             dist-walked 0.0
             next-sample-at 0.0
             poses []]
        (cond
          ;; Collected all samples
          (> (count poses) n-samples)
          poses

          ;; Ran out of commands — pad to n-samples+1 with final pose
          (>= cmd-idx n-cmds)
          (let [final-pose {:position (:position s)
                            :heading (:heading s)
                            :up (:up s)
                            :t 1.0}]
            (loop [p poses]
              (if (> (count p) n-samples)
                p
                (recur (conj p final-pose)))))

          :else
          (let [cmd (nth commands cmd-idx)]
            (if (= :f (:cmd cmd))
              ;; Forward command — may contain sample points
              (let [d (first (:args cmd))
                    abs-d (Math/abs d)
                    end-dist (+ dist-walked abs-d)]
                (if (and (<= next-sample-at end-dist)
                         (<= (count poses) n-samples))
                  ;; Emit sample point within this segment
                  (let [frac (if (pos? abs-d)
                               (/ (- next-sample-at dist-walked) abs-d)
                               0)
                        frac (max 0.0 (min 1.0 frac))
                        sample-pos (v+ (:position s) (v* (:heading s) (* d frac)))
                        t (/ next-sample-at total-dist)]
                    ;; Don't advance cmd-idx — there might be more samples in this segment
                    (recur cmd-idx s dist-walked
                           (+ next-sample-at sample-interval)
                           (conj poses {:position sample-pos
                                        :heading (:heading s)
                                        :up (:up s)
                                        :t (min 1.0 t)})))
                  ;; No more samples in this segment — advance state
                  (let [new-pos (v+ (:position s) (v* (:heading s) d))]
                    (recur (inc cmd-idx) (assoc s :position new-pos)
                           end-dist next-sample-at poses))))
              ;; Rotation command — apply to state
              (recur (inc cmd-idx) (apply-rotation-to-state s cmd)
                     dist-walked next-sample-at poses))))))))

(defn bloft
  "Bezier-safe loft: loft a shape along a bezier path with self-intersection handling.

   When consecutive rings would intersect, creates micro-mesh hulls to bridge them.

   Parameters:
   - state: turtle state
   - shape: starting shape
   - transform-fn: (fn [shape t]) for tapering, t goes 0→1
   - bezier-path: a path created with bezier-as
   - steps: number of steps (default 32)
   - threshold: intersection sensitivity, 0.0-1.0 (default 0.1)
                Higher = less sensitive = faster but may miss intersections
                Lower = more sensitive = slower but catches more intersections

   Returns updated state with the resulting mesh."
  ([state shape transform-fn bezier-path]
   (bloft state shape transform-fn bezier-path 32 0.1))
  ([state shape transform-fn bezier-path steps]
   (bloft state shape transform-fn bezier-path steps 0.1))
  ([state shape transform-fn bezier-path steps threshold]
   (if-not (and (shape? shape) (is-path? bezier-path))
     state
     (let [creation-pose {:position (:position state)
                          :heading (:heading state)
                          :up (:up state)}
           initial-radius (shape-radius shape)
           ;; Walk the path properly to get coherent poses at each sample point.
           ;; This avoids the degenerate heading/up frame that can occur when
           ;; interpolating positions within segments after tr (roll).
           poses (walk-path-poses state bezier-path steps)
           n-poses (count poses)]

       (if (< n-poses 2)
         state
         (let [result
               (loop [i 0
                      acc-rings []
                      tmp-meshes []
                      last-ring nil]

                 (if (>= i n-poses)
                   ;; Flush remaining rings
                   (if (>= (count acc-rings) 2)
                     (conj tmp-meshes (build-sweep-mesh acc-rings false creation-pose))
                     tmp-meshes)

                   (let [{:keys [position heading up t]} (nth poses i)
                         current-shape (transform-fn shape t)
                         temp-state {:position position :heading heading :up up}
                         current-ring (stamp-shape temp-state current-shape)]

                     (if (nil? last-ring)
                       ;; First ring
                       (recur (inc i) (conj acc-rings current-ring)
                              tmp-meshes current-ring)

                       (if (rings-intersect? last-ring current-ring threshold initial-radius)
                         ;; INTERSECTION: create hull bridge directly from ring vertices
                         (let [hull-mesh (manifold/hull-from-points (vec (concat last-ring current-ring)))
                               valid-hull? (and hull-mesh (pos? (count (:vertices hull-mesh))))
                               section-mesh (when (>= (count acc-rings) 2)
                                              (build-sweep-mesh acc-rings false creation-pose))
                               new-tmp-meshes (cond-> tmp-meshes
                                                section-mesh (conj section-mesh)
                                                valid-hull? (conj hull-mesh))]
                           (recur (inc i) [current-ring]
                                  new-tmp-meshes current-ring))

                         ;; No intersection — accumulate ring
                         (recur (inc i) (conj acc-rings current-ring)
                                tmp-meshes current-ring))))))

               final-meshes result
               ;; Walk the path to get final turtle state
               final-state (reduce
                            (fn [s cmd]
                              (case (:cmd cmd)
                                :f (update s :position v+ (v* (:heading s) (first (:args cmd))))
                                (:th :tv :tr :set-heading) (apply-rotation-to-state s cmd)
                                s))
                            state (:commands bezier-path))
               _ (js/console.log "bloft: threshold=" threshold
                                 "shape-radius=" (.toFixed initial-radius 2)
                                 "meshes:" (count final-meshes))]

           (if (empty? final-meshes)
             state
             ;; Use manifold/union to properly merge meshes and remove internal faces
             (let [unified-mesh (if (= 1 (count final-meshes))
                                  (first final-meshes)
                                  (manifold/union final-meshes))
                   mesh-with-material (if (:material state)
                                        (assoc unified-mesh :material (:material state))
                                        unified-mesh)]
               (update final-state :meshes conj mesh-with-material)))))))))
