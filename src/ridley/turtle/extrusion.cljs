(ns ridley.turtle.extrusion
  "Extrusion engine: mesh building, path analysis, and sweep operations.

   This module contains the core extrusion logic extracted from turtle/core.cljs.
   All functions are pure - they operate on data and return data."
  (:require ["earcut" :default earcut]
            [ridley.schema :as schema]
            [ridley.math :as math]))

;; --- Re-export math utilities used throughout ---
(def v+ math/v+)
(def v- math/v-)
(def v* math/v*)
(def dot math/dot)
(def cross math/cross)
(def magnitude math/magnitude)
(def normalize math/normalize)
(def rotate-around-axis math/rotate-around-axis)

;; --- Numeric validation ---

(defn check-num
  "Validate that a value is a finite number. Throws with a clear message if not.
   Catches NaN and non-numeric values early, before they corrupt geometry."
  [value command-name]
  (when-not (and (number? value) (js/isFinite value))
    (throw (js/Error. (str "(" command-name " " (pr-str value) "): expected a number, got "
                           (cond
                             (js/Number.isNaN value) "NaN (bad arithmetic?)"
                             (not (number? value)) (str (type value))
                             :else "Infinity"))))))

;; --- Basic helpers ---

(defn deg->rad
  "Convert degrees to radians."
  [deg]
  (* deg (/ Math/PI 180)))

(defn right-vector
  "Calculate the right vector (heading x up)."
  [state]
  (cross (:heading state) (:up state)))

(defn shape?
  "Check if x is a shape (has :type :shape)."
  [x]
  (and (map? x) (= :shape (:type x))))

(defn is-path?
  "Check if x is a path (has :type :path)."
  [x]
  (and (map? x) (= :path (:type x))))

;; --- Resolution settings ---

(defn get-resolution
  "Get current resolution settings, defaulting to {:mode :n :value 16}."
  [state]
  (or (:resolution state) {:mode :n :value 16}))

(defn calc-round-steps
  "Calculate steps for a round joint based on resolution and angle.
   angle-deg: the bend angle in degrees"
  [state angle-deg]
  (let [{:keys [mode value]} (get-resolution state)]
    (case mode
      ;; :n mode: scale proportionally with angle (90° = 1/4 of full circle)
      :n (max 2 (int (* value (/ (Math/abs angle-deg) 360))))
      :a (max 2 (int (Math/ceil (/ (Math/abs angle-deg) value))))
      :s value  ; :s mode doesn't apply well to corners, use value as steps
      ;; default
      4)))

;; --- Pure rotation application (no side effects) ---

(defn apply-rotations
  "Apply a list of rotations to heading/up vectors.
   Returns {:heading new-heading :up new-up}"
  [state rotations]
  (reduce
   (fn [{:keys [heading up] :as acc} {:keys [type angle]}]
     (let [rad (deg->rad angle)]
       (case type
         :th (assoc acc :heading (rotate-around-axis heading up rad))
         :tv (let [right (normalize (cross heading up))
                   new-heading (rotate-around-axis heading right rad)
                   new-up (rotate-around-axis up right rad)]
               (assoc acc :heading new-heading :up new-up))
         :tr (assoc acc :up (rotate-around-axis up heading rad))
         acc)))
   {:heading (:heading state) :up (:up state)}
   rotations))

(defn apply-rotation-to-state
  "Apply a single rotation command to turtle state (pure version).
   Only handles rotation math, no attachment/shape mode logic."
  [state rotation]
  (case (:cmd rotation)
    :th (let [angle (first (:args rotation))
              rad (deg->rad angle)
              new-heading (rotate-around-axis (:heading state) (:up state) rad)]
          (assoc state :heading new-heading))
    :tv (let [angle (first (:args rotation))
              rad (deg->rad angle)
              right (normalize (cross (:heading state) (:up state)))
              new-heading (rotate-around-axis (:heading state) right rad)
              new-up (rotate-around-axis (:up state) right rad)]
          (-> state
              (assoc :heading new-heading)
              (assoc :up new-up)))
    :tr (let [angle (first (:args rotation))
              rad (deg->rad angle)
              new-up (rotate-around-axis (:up state) (:heading state) rad)]
          (assoc state :up new-up))
    :set-heading (let [[heading up] (:args rotation)]
                   (-> state
                       (assoc :heading (normalize heading))
                       (assoc :up (normalize up))))
    state))

;; --- Triangulation (earcut) ---

(defn earcut-triangulate
  "Triangulate a 2D polygon with holes using earcut.js.
   - outer: vector of [x y] points for outer boundary (CCW)
   - holes: vector of hole contours, each is vector of [x y] points (CW)
   Returns vector of [i j k] triangles (indices into combined vertex list).
   The combined vertex list is: outer ++ hole1 ++ hole2 ++ ..."
  [outer holes]
  (let [;; Flatten outer contour to [x y x y ...]
        outer-flat (into-array (mapcat identity outer))
        ;; Track hole start indices and flatten holes
        outer-len (count outer)
        [hole-indices holes-flat]
        (reduce (fn [[indices flat-data] hole]
                  [(conj indices (+ outer-len (/ (count flat-data) 2)))
                   (into flat-data (mapcat identity hole))])
                [[] []]
                holes)
        ;; Combine outer + holes into single flat array
        all-coords (js/Float64Array. (into-array (concat outer-flat holes-flat)))
        ;; Create hole indices array (empty if no holes)
        hole-idx-arr (when (seq hole-indices) (into-array hole-indices))
        ;; Call earcut: returns flat array of triangle indices
        result (if hole-idx-arr
                 (earcut all-coords hole-idx-arr 2)
                 (earcut all-coords nil 2))
        ;; Convert flat [i j k i j k ...] to [[i j k] [i j k] ...]
        n-indices (.-length result)]
    (vec (for [i (range 0 n-indices 3)]
           [(aget result i) (aget result (+ i 1)) (aget result (+ i 2))]))))

(defn project-to-2d
  "Project 3D points to 2D by dropping the axis most aligned with normal.
   Returns [pts-2d winding-preserved?] where winding-preserved? indicates
   whether the 2D winding matches the 3D winding (false if mirrored)."
  [pts normal]
  (let [[nx ny nz] normal
        ax (Math/abs nx) ay (Math/abs ny) az (Math/abs nz)]
    (cond
      ;; Drop Z, project to XY plane
      (and (>= az ax) (>= az ay))
      [(mapv (fn [[x y _]] [x y]) pts) (>= nz 0)]

      ;; Drop Y, project to XZ plane
      (and (>= ay ax) (>= ay az))
      [(mapv (fn [[x _ z]] [x z]) pts) (< ny 0)]

      ;; Drop X, project to YZ plane
      :else
      [(mapv (fn [[_ y z]] [y z]) pts) (>= nx 0)])))

(defn triangulate-cap
  "Triangulate a polygon cap using earcut (handles concave polygons).
   - ring: vector of 3D vertex positions
   - base-idx: starting index in the mesh vertex array
   - normal: cap normal (for 2D projection)
   - flip?: whether to flip winding order for final triangles
   Returns vector of [i j k] face triangles with mesh indices."
  [ring base-idx normal flip?]
  (let [[pts-2d winding-preserved?] (project-to-2d ring normal)
        ;; earcut preserves input winding — if projection mirrored it, compensate
        effective-flip? (if winding-preserved? flip? (not flip?))
        local-tris (earcut-triangulate pts-2d [])]
    (mapv (fn [[i j k]]
            (if effective-flip?
              [(+ base-idx i) (+ base-idx k) (+ base-idx j)]
              [(+ base-idx i) (+ base-idx j) (+ base-idx k)]))
          local-tris)))

(defn triangulate-cap-with-holes
  "Triangulate a polygon cap with holes using earcut.
   - outer-ring: vector of 3D vertex positions for outer boundary
   - hole-rings: vector of 3D vertex vectors for each hole
   - base-idx: starting index in the mesh vertex array
   - normal: cap normal (for 2D projection)
   - flip?: whether to flip winding order for final triangles
   Returns vector of [i j k] face triangles with mesh indices."
  [outer-ring hole-rings base-idx normal flip?]
  (if (empty? hole-rings)
    ;; No holes - use standard triangulation
    (triangulate-cap outer-ring base-idx normal flip?)
    ;; With holes - use earcut
    (let [[outer-2d winding-preserved?] (project-to-2d outer-ring normal)
          holes-2d (mapv #(first (project-to-2d % normal)) hole-rings)
          ;; Compensate for projection mirror, same as triangulate-cap
          effective-flip? (if winding-preserved? flip? (not flip?))
          local-tris (earcut-triangulate outer-2d holes-2d)]
      (mapv (fn [[i j k]]
              (if effective-flip?
                [(+ base-idx i) (+ base-idx k) (+ base-idx j)]
                [(+ base-idx i) (+ base-idx j) (+ base-idx k)]))
            local-tris))))

;; --- Shape stamping ---

(defn compute-stamp-transform
  "Compute transformation parameters for stamping a shape.
   Returns {:plane-x :plane-y :offset :origin}."
  [state shape]
  (let [points (:points shape)
        centered? (:centered? shape)
        preserve-position? (:preserve-position? shape)
        pos (:position state)
        heading (:heading state)
        up (:up state)
        [hx hy hz] heading
        [ux uy uz] up
        ;; Right vector = heading × up
        rx (- (* hy uz) (* hz uy))
        ry (- (* hz ux) (* hx uz))
        rz (- (* hx uy) (* hy ux))
        ;; Normalize right vector
        r-mag (Math/sqrt (+ (* rx rx) (* ry ry) (* rz rz)))
        [rx ry rz] (if (pos? r-mag)
                     [(/ rx r-mag) (/ ry r-mag) (/ rz r-mag)]
                     [1 0 0])
        ;; Plane axes: X = right, Y = up
        plane-x [rx ry rz]
        plane-y up
        ;; Calculate offset for non-centered shapes
        offset (cond
                 preserve-position? [0 0]
                 centered? [0 0]
                 :else (let [[fx fy] (first points)]
                         [(- fx) (- fy)]))]
    {:plane-x plane-x :plane-y plane-y :offset offset :origin pos}))

(defn transform-2d-to-3d
  "Transform 2D points to 3D using stamp parameters."
  [points {:keys [plane-x plane-y offset origin]}]
  (let [[ox oy oz] origin
        [xx xy xz] plane-x
        [yx yy yz] plane-y
        [off-x off-y] offset]
    (mapv (fn [[px py]]
            (let [px' (+ px off-x)
                  py' (+ py off-y)]
              [(+ ox (* px' xx) (* py' yx))
               (+ oy (* px' xy) (* py' yy))
               (+ oz (* px' xz) (* py' yz))]))
          points)))

(defn stamp-shape
  "Stamp a shape onto the plane perpendicular to turtle's heading.
   Returns 3D vertices of the stamped shape."
  [state shape]
  (let [params (compute-stamp-transform state shape)]
    (transform-2d-to-3d (:points shape) params)))

(defn stamp-shape-with-holes
  "Stamp a shape with holes onto the plane perpendicular to turtle's heading.
   Returns {:outer <3D-vertices> :holes [<3D-vertices> ...]}."
  [state shape]
  (let [params (compute-stamp-transform state shape)
        outer-3d (transform-2d-to-3d (:points shape) params)
        holes-3d (when-let [holes (:holes shape)]
                   (mapv #(transform-2d-to-3d % params) holes))]
    {:outer outer-3d
     :holes holes-3d}))

;; --- Shape/Corner utilities ---

(defn shape-radius
  "Calculate the radius of a shape (max distance from origin to any point)."
  [shape]
  (if-let [points (:points shape)]
    (reduce max 0 (map (fn [[x y]] (Math/sqrt (+ (* x x) (* y y)))) points))
    0))

(defn ring-centroid
  "Calculate the centroid of a ring (vector of 3D points)."
  [ring]
  (let [n (count ring)
        sum (reduce (fn [[sx sy sz] [x y z]]
                      [(+ sx x) (+ sy y) (+ sz z)])
                    [0 0 0] ring)]
    [(/ (first sum) n) (/ (second sum) n) (/ (nth sum 2) n)]))

(defn rotate-ring-around-axis
  "Rotate all points of a ring around an axis passing through a pivot point."
  [ring pivot axis angle]
  (mapv (fn [pt]
          (let [;; Translate to origin (relative to pivot)
                rel (v- pt pivot)
                ;; Rotate around axis
                rotated (let [k (normalize axis)
                              cos-a (Math/cos angle)
                              sin-a (Math/sin angle)
                              term1 (v* rel cos-a)
                              term2 (v* (cross k rel) sin-a)
                              term3 (v* k (* (dot k rel) (- 1 cos-a)))]
                          (v+ (v+ term1 term2) term3))]
            ;; Translate back
            (v+ rotated pivot)))
        ring))

(defn scale-ring-from-centroid
  "Scale a ring uniformly from its centroid."
  [ring scale-factor]
  (let [centroid (ring-centroid ring)]
    (mapv (fn [pt]
            (let [rel (v- pt centroid)
                  scaled (v* rel scale-factor)]
              (v+ centroid scaled)))
          ring)))

(defn scale-ring-along-direction
  "Scale a ring along a specific direction from its centroid.
   Points are stretched along 'direction' by scale-factor, unchanged perpendicular to it."
  [ring direction scale-factor]
  (let [centroid (ring-centroid ring)
        dir-norm (normalize direction)]
    (mapv (fn [pt]
            (let [rel (v- pt centroid)
                  ;; Project rel onto direction
                  proj-len (dot rel dir-norm)
                  proj (v* dir-norm proj-len)
                  ;; Perpendicular component stays the same
                  perp (v- rel proj)
                  ;; Scale only the projection component
                  scaled-proj (v* proj scale-factor)
                  ;; Recombine
                  new-rel (v+ perp scaled-proj)]
              (v+ centroid new-rel)))
          ring)))

;; --- Corner generation ---

(defn generate-round-corner-rings
  "Generate intermediate rings for a rounded corner.

   Parameters:
   - end-ring: the last ring before the corner
   - corner-pos: position at the corner vertex (unused, kept for API compatibility)
   - old-heading: heading before rotation
   - new-heading: heading after rotation
   - n-steps: number of intermediate rings (more = smoother)
   - radius: the shape radius (needed to find pivot point)

   Returns a vector of intermediate rings."
  [end-ring _corner-pos old-heading new-heading n-steps radius]
  (let [;; Calculate rotation axis (perpendicular to both headings)
        axis (cross old-heading new-heading)
        axis-mag (magnitude axis)]
    ;; If headings are parallel, no corner needed
    (if (< axis-mag 0.001)
      []
      (let [axis-norm (normalize axis)
            ;; Total angle to rotate = angle between headings
            cos-angle (dot old-heading new-heading)
            total-angle (Math/acos (min 1 (max -1 cos-angle)))
            ;; Step angle - divide angle into n-steps segments
            step-angle (/ total-angle n-steps)
            ;; Direction toward the center of the turn
            center-dir (normalize (cross axis-norm old-heading))
            ;; The pivot is at the ring's centroid + radius in the center direction
            end-centroid (ring-centroid end-ring)
            pivot (v+ end-centroid (v* center-dir radius))]
        ;; Generate intermediate rings by rotating around the pivot point
        (vec
         (for [i (range 1 (inc n-steps))]
           (let [angle (* i step-angle)]
             (rotate-ring-around-axis end-ring pivot axis-norm angle))))))))

(defn generate-tapered-corner-rings
  "Generate intermediate ring for a tapered/beveled corner.

   Creates a single intermediate ring at the corner, scaled along the
   bisector direction to maintain continuous cross-section.

   Parameters:
   - end-ring: the last ring before the corner
   - corner-pos: position at the corner vertex
   - old-heading: heading before rotation
   - new-heading: heading after rotation

   Returns a vector with one intermediate ring."
  [end-ring corner-pos old-heading new-heading]
  (let [;; Calculate the angle between headings
        cos-angle (dot old-heading new-heading)
        total-angle (Math/acos (min 1 (max -1 cos-angle)))
        half-angle (/ total-angle 2)
        ;; Scale factor to prevent pinching: 1/cos(half_angle)
        scale-factor (if (> (Math/cos half-angle) 0.1)
                       (/ 1 (Math/cos half-angle))
                       2.0)  ; Cap at 2x for very sharp angles
        ;; Calculate rotation axis (perpendicular to both headings)
        axis (cross old-heading new-heading)
        axis-mag (magnitude axis)]
    (if (< axis-mag 0.001)
      ;; Headings are parallel, no corner needed
      []
      (let [axis-norm (normalize axis)
            ;; First translate end-ring to corner position
            end-centroid (ring-centroid end-ring)
            offset (v- corner-pos end-centroid)
            translated-ring (mapv #(v+ % offset) end-ring)
            ;; Rotate by half the angle to align with bisector
            rotated-ring (rotate-ring-around-axis translated-ring corner-pos axis-norm half-angle)
            ;; Scale only along the bisector direction
            stretch-dir (normalize (cross axis-norm (normalize (v+ old-heading new-heading))))
            scaled-ring (scale-ring-along-direction rotated-ring stretch-dir scale-factor)]
        [scaled-ring]))))

;; --- Mesh building ---

(defn build-sweep-mesh
  "Build a unified mesh from accumulated sweep rings.
   Each ring is a vector of 3D vertices.
   If closed? is true, connects last ring back to first (torus-like, no caps).
   Otherwise creates side faces, and optionally bottom/top caps.
   caps? controls whether to generate end caps (default true).
   Optional creation-pose records where the extrusion started."
  ([rings] (build-sweep-mesh rings false nil true))
  ([rings closed?] (build-sweep-mesh rings closed? nil true))
  ([rings closed? creation-pose] (build-sweep-mesh rings closed? creation-pose true))
  ([rings closed? creation-pose caps?]
   (let [n-rings (count rings)
         n-verts (count (first rings))]
     (when (and (>= n-rings 2) (>= n-verts 3))
       (if closed?
         ;; Closed loop: skip last ring (overlaps with first), connect last to first
         (let [effective-rings (butlast rings)
               effective-n-rings (count effective-rings)
               vertices (vec (apply concat effective-rings))
               side-faces (vec
                           (mapcat
                            (fn [ring-idx]
                              (let [next-ring-idx (mod (inc ring-idx) effective-n-rings)]
                                (mapcat
                                 (fn [vert-idx]
                                   (let [next-vert (mod (inc vert-idx) n-verts)
                                         base (* ring-idx n-verts)
                                         next-base (* next-ring-idx n-verts)
                                         b0 (+ base vert-idx)
                                         b1 (+ base next-vert)
                                         t0 (+ next-base vert-idx)
                                         t1 (+ next-base next-vert)]
                                     ;; Choose shorter diagonal for curves
                                     (let [db0t1 (v- (nth vertices b0) (nth vertices t1))
                                           db1t0 (v- (nth vertices b1) (nth vertices t0))]
                                       (if (<= (dot db0t1 db0t1) (dot db1t0 db1t0))
                                         [[b0 t0 t1] [b0 t1 b1]]
                                         [[b0 t0 b1] [t0 t1 b1]]))))
                                 (range n-verts))))
                            (range effective-n-rings)))]
           (cond-> {:type :mesh
                    :primitive :sweep-closed
                    :vertices vertices
                    :faces side-faces}
             creation-pose (assoc :creation-pose creation-pose)))
         ;; Open: caps at both ends
         (let [vertices (vec (apply concat rings))
               side-faces (vec
                           (mapcat
                            (fn [ring-idx]
                              (mapcat
                               (fn [vert-idx]
                                 (let [next-vert (mod (inc vert-idx) n-verts)
                                       base (* ring-idx n-verts)
                                       next-base (* (inc ring-idx) n-verts)
                                       b0 (+ base vert-idx)
                                       b1 (+ base next-vert)
                                       t0 (+ next-base vert-idx)
                                       t1 (+ next-base next-vert)]
                                   ;; Choose shorter diagonal for curves
                                   (let [db0t1 (v- (nth vertices b0) (nth vertices t1))
                                         db1t0 (v- (nth vertices b1) (nth vertices t0))]
                                     (if (<= (dot db0t1 db0t1) (dot db1t0 db1t0))
                                       [[b0 t0 t1] [b0 t1 b1]]
                                       [[b0 t0 b1] [t0 t1 b1]]))))
                               (range n-verts)))
                            (range (dec n-rings))))
               ;; Compute cap normals from ring geometry
               first-ring (first rings)
               last-ring (last rings)
               last-base (* (dec n-rings) n-verts)

               ;; Calculate extrusion direction from ring centroids
               ;; Use local direction at each end for correct cap projection
               bottom-dir (normalize (v- (ring-centroid (second rings))
                                         (ring-centroid first-ring)))
               top-dir (normalize (v- (ring-centroid last-ring)
                                      (ring-centroid (nth rings (- n-rings 2)))))

               ;; Generate caps only if caps? is true
               cap-faces (when caps?
                           (let [bottom-normal (v* bottom-dir -1)
                                 top-normal top-dir
                                 bottom-cap (triangulate-cap first-ring 0 bottom-normal false)
                                 top-cap (triangulate-cap last-ring last-base top-normal false)]
                             (concat bottom-cap top-cap)))]
           (cond-> {:type :mesh
                    :primitive :sweep
                    :vertices vertices
                    :faces (vec (concat side-faces cap-faces))}
             creation-pose (assoc :creation-pose creation-pose))))))))

(defn build-segment-mesh
  "Build a mesh from sweep rings (no caps - for segments that will be joined).
   Returns nil if not enough rings.
   flip-winding? reverses face winding for backward extrusions."
  ([rings] (build-segment-mesh rings false))
  ([rings flip-winding?]
   (let [n-rings (count rings)
         n-verts (count (first rings))]
     (when (and (>= n-rings 2) (>= n-verts 3))
       (let [vertices (vec (apply concat rings))
             side-faces (vec
                         (mapcat
                          (fn [ring-idx]
                            (mapcat
                             (fn [vert-idx]
                               (let [next-vert (mod (inc vert-idx) n-verts)
                                     base (* ring-idx n-verts)
                                     next-base (* (inc ring-idx) n-verts)
                                     b0 (+ base vert-idx)
                                     b1 (+ base next-vert)
                                     t0 (+ next-base vert-idx)
                                     t1 (+ next-base next-vert)]
                                 ;; CCW winding from outside
                                 ;; Flip when extrusion goes backward
                                 (if flip-winding?
                                   [[b0 b1 t1] [b0 t1 t0]]
                                   [[b0 t1 b1] [b0 t0 t1]])))
                             (range n-verts)))
                          (range (dec n-rings))))]
         {:type :mesh
          :primitive :segment
          :vertices vertices
          :faces side-faces})))))

(defn build-corner-mesh
  "Build a corner mesh connecting two rings (no caps).
   ring1 and ring2 must have the same number of vertices.
   flip-winding? reverses face winding for backward extrusions."
  ([ring1 ring2] (build-corner-mesh ring1 ring2 false))
  ([ring1 ring2 flip-winding?]
   (let [n-verts (count ring1)]
     (when (and (>= n-verts 3) (= n-verts (count ring2)))
       (let [vertices (vec (concat ring1 ring2))
             side-faces (vec
                         (mapcat
                          (fn [i]
                            (let [next-i (mod (inc i) n-verts)
                                  b0 i
                                  b1 next-i
                                  t0 (+ n-verts i)
                                  t1 (+ n-verts next-i)]
                              ;; CCW winding from outside
                              ;; Flip when extrusion goes backward
                              (if flip-winding?
                                [[b0 b1 t1] [b0 t1 t0]]
                                [[b0 t1 b1] [b0 t0 t1]])))
                          (range n-verts)))]
         {:type :mesh
          :primitive :corner
          :vertices vertices
          :faces side-faces})))))

;; --- Sweep with holes ---

(defn sweep-two-shapes-with-holes
  "Create a mesh connecting two 3D rings with holes.
   data1 and data2 are {:outer <ring> :holes [<ring> ...]}
   Returns a mesh with:
   - Side faces connecting outer rings
   - Side faces connecting each hole (reversed winding)
   - Caps triangulated with holes"
  [data1 data2]
  (let [outer1 (:outer data1)
        outer2 (:outer data2)
        holes1 (or (:holes data1) [])
        holes2 (or (:holes data2) [])
        n-outer (count outer1)
        n-holes (count holes1)]
    (when (and (>= n-outer 3)
               (= n-outer (count outer2))
               (= n-holes (count holes2)))
      (let [;; Vertices: outer1, outer2, hole1-ring1, hole1-ring2, hole2-ring1, hole2-ring2, ...
            hole-lengths (mapv count holes1)

            ;; Build combined vertex list
            ring1-vertices (vec (concat outer1 (apply concat holes1)))
            ring2-vertices (vec (concat outer2 (apply concat holes2)))
            vertices (vec (concat ring1-vertices ring2-vertices))

            ring1-len (count ring1-vertices)

            ;; Side faces for outer contour
            outer-side-faces
            (vec (mapcat (fn [i]
                           (let [next-i (mod (inc i) n-outer)
                                 b0 i b1 next-i
                                 t0 (+ ring1-len i) t1 (+ ring1-len next-i)]
                             ;; CCW from outside
                             [[b0 t1 b1] [b0 t0 t1]]))
                         (range n-outer)))

            ;; Side faces for each hole
            hole-side-faces
            (vec (apply concat
                        (map-indexed
                         (fn [hole-idx hole-len]
                           (let [base1 (+ n-outer (reduce + (take hole-idx hole-lengths)))
                                 base2 (+ ring1-len base1)]
                             (mapcat (fn [i]
                                       (let [next-i (mod (inc i) hole-len)
                                             b0 (+ base1 i) b1 (+ base1 next-i)
                                             t0 (+ base2 i) t1 (+ base2 next-i)]
                                         [[b0 t1 b1] [b0 t0 t1]]))
                                     (range hole-len))))
                         hole-lengths)))

            all-side-faces (vec (concat outer-side-faces hole-side-faces))

            ;; Compute normals for caps
            extrusion-dir (normalize (v- (ring-centroid outer2) (ring-centroid outer1)))
            bottom-normal (v* extrusion-dir -1)
            top-normal extrusion-dir

            ;; Caps with holes
            bottom-cap (triangulate-cap-with-holes outer1 holes1 0 bottom-normal false)
            top-cap (triangulate-cap-with-holes outer2 holes2 ring1-len top-normal false)]

        (schema/assert-mesh!
         {:type :mesh
          :primitive :sweep-two-holes
          :vertices vertices
          :faces (vec (concat all-side-faces bottom-cap top-cap))
          :creation-pose {:position [0 0 0]
                          :heading [1 0 0]
                          :up [0 0 1]}})))))

(defn sweep-two-shapes
  "Wrapper for sweep-two-shapes-with-holes when shapes have no holes."
  [ring1 ring2]
  (sweep-two-shapes-with-holes {:outer ring1 :holes []}
                               {:outer ring2 :holes []}))

;; --- Multi-ring sweep with holes ---

(defn build-sweep-mesh-with-holes
  "Build a unified mesh from accumulated ring-data entries.
   Each entry is {:outer <3D-ring> :holes [<3D-ring> ...]}.
   All entries must have the same number of outer vertices and same hole structure.
   Generates side faces for outer + each hole, and caps at both ends."
  [ring-data-vec creation-pose]
  (let [n-rings (count ring-data-vec)
        first-data (first ring-data-vec)
        n-outer (count (:outer first-data))
        holes-structure (mapv count (or (:holes first-data) []))
        ;; Combined ring length: outer + all holes
        ring-len (+ n-outer (reduce + 0 holes-structure))]
    (when (and (>= n-rings 2) (>= n-outer 3))
      (let [;; Flatten all ring-data into a single vertex array
            ;; Each "combined ring" = [outer-pts... hole0-pts... hole1-pts...]
            vertices
            (vec (mapcat (fn [rd]
                           (concat (:outer rd) (apply concat (or (:holes rd) []))))
                         ring-data-vec))

            ;; Side faces for outer contour
            outer-side-faces
            (vec (mapcat
                  (fn [ring-idx]
                    (let [base (* ring-idx ring-len)
                          next-base (* (inc ring-idx) ring-len)]
                      (mapcat
                       (fn [i]
                         (let [next-i (mod (inc i) n-outer)
                               b0 (+ base i) b1 (+ base next-i)
                               t0 (+ next-base i) t1 (+ next-base next-i)
                               db0t1 (v- (nth vertices b0) (nth vertices t1))
                               db1t0 (v- (nth vertices b1) (nth vertices t0))]
                           (if (<= (dot db0t1 db0t1) (dot db1t0 db1t0))
                             [[b0 t0 t1] [b0 t1 b1]]
                             [[b0 t0 b1] [t0 t1 b1]])))
                       (range n-outer))))
                  (range (dec n-rings))))

            ;; Side faces for each hole (same winding as outer — holes are CW
            ;; so the normals point into the tunnel, which is correct)
            hole-side-faces
            (vec (apply concat
                        (map-indexed
                         (fn [hole-idx hole-len]
                           (let [hole-offset (+ n-outer (reduce + 0 (take hole-idx holes-structure)))]
                             (mapcat
                              (fn [ring-idx]
                                (let [base (+ (* ring-idx ring-len) hole-offset)
                                      next-base (+ (* (inc ring-idx) ring-len) hole-offset)]
                                  (mapcat
                                   (fn [i]
                                     (let [next-i (mod (inc i) hole-len)
                                           b0 (+ base i) b1 (+ base next-i)
                                           t0 (+ next-base i) t1 (+ next-base next-i)]
                                       ;; Same face winding as outer
                                       [[b0 t0 t1] [b0 t1 b1]]))
                                   (range hole-len))))
                              (range (dec n-rings)))))
                         holes-structure)))

            ;; Caps
            first-outer (:outer first-data)
            first-holes (or (:holes first-data) [])
            last-data (last ring-data-vec)
            last-outer (:outer last-data)
            last-holes (or (:holes last-data) [])
            last-ring-base (* (dec n-rings) ring-len)

            ;; Cap normals from ring centroids
            second-data (nth ring-data-vec 1)
            second-to-last-data (nth ring-data-vec (- n-rings 2))
            bottom-dir (normalize (v- (ring-centroid (:outer second-data))
                                      (ring-centroid first-outer)))
            top-dir (normalize (v- (ring-centroid last-outer)
                                   (ring-centroid (:outer second-to-last-data))))
            bottom-normal (v* bottom-dir -1)
            top-normal top-dir

            ;; Bottom cap with holes
            bottom-cap (triangulate-cap-with-holes first-outer first-holes
                                                    0 bottom-normal false)
            ;; Top cap with holes
            top-cap (triangulate-cap-with-holes last-outer last-holes
                                                last-ring-base top-normal false)

            all-faces (vec (concat outer-side-faces hole-side-faces
                                   bottom-cap top-cap))]
        (schema/assert-mesh!
         (cond-> {:type :mesh
                  :primitive :extrusion
                  :vertices vertices
                  :faces all-faces}
           creation-pose (assoc :creation-pose creation-pose)))))))

;; --- Corner generation with holes ---

(defn generate-round-corner-ring-data
  "Generate intermediate ring-data for a rounded corner (outer + holes).
   end-data: {:outer ring :holes [ring ...]}
   Returns a vector of ring-data entries for the corner."
  [end-data corner-pos old-heading new-heading n-steps radius]
  (let [outer-corners (generate-round-corner-rings
                       (:outer end-data) corner-pos old-heading new-heading
                       n-steps radius)
        ;; Apply the same rotation to each hole ring
        hole-corners (when (seq (:holes end-data))
                       (mapv (fn [hole-ring]
                               (generate-round-corner-rings
                                hole-ring corner-pos old-heading new-heading
                                n-steps radius))
                             (:holes end-data)))]
    ;; Zip: for each step i, create {:outer outer-corners[i] :holes [hole0-corners[i] hole1-corners[i] ...]}
    (vec (for [i (range (count outer-corners))]
           {:outer (nth outer-corners i)
            :holes (when hole-corners
                     (mapv #(nth % i) hole-corners))}))))

(defn generate-tapered-corner-ring-data
  "Generate intermediate ring-data for a tapered corner (outer + holes).
   Returns a vector of ring-data entries (usually 1)."
  [end-data corner-pos old-heading new-heading]
  (let [outer-corners (generate-tapered-corner-rings
                       (:outer end-data) corner-pos old-heading new-heading)
        hole-corners (when (seq (:holes end-data))
                       (mapv (fn [hole-ring]
                               (generate-tapered-corner-rings
                                hole-ring corner-pos old-heading new-heading))
                             (:holes end-data)))]
    (vec (for [i (range (count outer-corners))]
           {:outer (nth outer-corners i)
            :holes (when hole-corners
                     (mapv #(nth % i) hole-corners))}))))

;; --- Path analysis ---

(defn is-rotation?
  "Check if command is a rotation (or direct heading set)."
  [cmd]
  (#{:th :tv :tr :set-heading} cmd))

(defn is-corner-rotation?
  "Check if command is a corner rotation that requires segment shortening.
   Excludes :set-heading which is used for smooth curves (bezier/arc)
   that don't need corner treatment."
  [cmd]
  (#{:th :tv :tr} cmd))

(defn- total-rotation-angle-closed
  "Calculate the total absolute rotation angle from a sequence of rotation commands."
  [rotations]
  (reduce + 0 (map (fn [r]
                     (if (= :set-heading (:cmd r))
                       0
                       (Math/abs (first (:args r)))))
                   rotations)))

(def ^:private ^:const corner-threshold-deg-closed
  "Minimum rotation angle (degrees) to be considered a corner requiring segment shortening."
  10.0)

(defn calc-shorten-for-angle
  "Calculate the segment shortening needed for a given rotation angle.
   For a corner rotation of θ degrees, the shortening is: radius * tan(θ/2)"
  [angle-deg radius]
  (if (< (Math/abs angle-deg) corner-threshold-deg-closed)
    0
    (let [half-angle-rad (* (Math/abs angle-deg) (/ Math/PI 360))
          capped-half-angle (min half-angle-rad (* 87.5 (/ Math/PI 180)))
          tan-half (Math/tan capped-half-angle)]
      (* radius tan-half))))

(defn analyze-closed-path
  "Analyze a path for closed extrusion.
   Returns a vector of segments with their adjustments."
  [commands radius]
  (let [cmds (vec commands)
        n (count cmds)
        forwards (vec (keep-indexed (fn [i c] (when (= :f (:cmd c)) [i c])) cmds))
        n-forwards (count forwards)
        total-explicit-rotation (reduce
                                 (fn [sum cmd]
                                   (if (is-corner-rotation? (:cmd cmd))
                                     (+ sum (Math/abs (first (:args cmd))))
                                     sum))
                                 0
                                 cmds)
        closing-angle (let [remainder (mod total-explicit-rotation 360)]
                        (if (< remainder 1) 0 (- 360 remainder)))]
    (vec
     (map-indexed
      (fn [seg-idx [idx cmd]]
        (let [dist (first (:args cmd))
              is-first (= seg-idx 0)
              is-last (= seg-idx (dec n-forwards))
              rotations-before (loop [i (dec idx)
                                      rots []
                                      steps 0]
                                 (if (> steps n)
                                   rots
                                   (let [ci (mod (+ i n) n)
                                         c (nth cmds ci)]
                                     (if (is-rotation? (:cmd c))
                                       (recur (dec i) (conj rots c) (inc steps))
                                       rots))))
              explicit-angle-before (total-rotation-angle-closed rotations-before)
              angle-before (if is-first
                             (+ explicit-angle-before closing-angle)
                             explicit-angle-before)
              rotations-after (loop [i (inc idx)
                                     rots []]
                                (if (>= i n)
                                  (let [wrapped-i (mod i n)]
                                    (if (= wrapped-i idx)
                                      rots
                                      (let [c (nth cmds wrapped-i)]
                                        (if (is-rotation? (:cmd c))
                                          (recur (inc i) (conj rots c))
                                          rots))))
                                  (let [c (nth cmds i)]
                                    (if (is-rotation? (:cmd c))
                                      (recur (inc i) (conj rots c))
                                      rots))))
              explicit-angle-after (total-rotation-angle-closed rotations-after)
              angle-after (if is-last
                            (+ explicit-angle-after closing-angle)
                            explicit-angle-after)]
          {:cmd :f
           :dist dist
           :shorten-start (calc-shorten-for-angle angle-before radius)
           :shorten-end (calc-shorten-for-angle angle-after radius)
           :rotations-after rotations-after
           :is-first is-first
           :is-last is-last
           :closing-angle closing-angle}))
      forwards))))

(defn analyze-open-path
  "Analyze a path for open extrusion.
   Returns a vector of segments with their adjustments."
  [commands radius]
  (let [cmds (vec commands)
        n (count cmds)
        forwards (keep-indexed (fn [i c] (when (= :f (:cmd c)) [i c])) cmds)
        n-forwards (count forwards)]
    (vec
     (map-indexed
      (fn [fwd-idx [idx cmd]]
        (let [dist (first (:args cmd))
              is-first (zero? fwd-idx)
              is-last (= fwd-idx (dec n-forwards))
              rotations-before (loop [i (dec idx)
                                      rots []]
                                 (if (< i 0)
                                   rots
                                   (let [c (nth cmds i)]
                                     (if (is-rotation? (:cmd c))
                                       (recur (dec i) (conj rots c))
                                       rots))))
              angle-before (reduce + 0 (map (fn [r]
                                              (if (is-corner-rotation? (:cmd r))
                                                (Math/abs (first (:args r)))
                                                0))
                                            rotations-before))
              rotations-after (loop [i (inc idx)
                                     rots []]
                                (if (>= i n)
                                  rots
                                  (let [c (nth cmds i)]
                                    (if (is-rotation? (:cmd c))
                                      (recur (inc i) (conj rots c))
                                      rots))))
              angle-after (reduce + 0 (map (fn [r]
                                             (if (is-corner-rotation? (:cmd r))
                                               (Math/abs (first (:args r)))
                                               0))
                                           rotations-after))]
          {:cmd :f
           :dist dist
           :shorten-start (if is-first 0 (calc-shorten-for-angle angle-before radius))
           :shorten-end (if is-last 0 (calc-shorten-for-angle angle-after radius))
           :rotations-after rotations-after}))
      forwards))))

(defn is-simple-forward-path?
  "Check if path is a simple straight extrusion (single forward command, no corners)."
  [path]
  (let [commands (:commands path)]
    (and (= 1 (count commands))
         (= :f (:cmd (first commands))))))

;; --- Extrusion functions ---

(defn extrude-simple-with-holes
  "Extrude a shape with holes along a simple straight path.
   Only works for single-segment forward paths.
   Returns turtle state with mesh added."
  [state shape path]
  (let [creation-pose {:position (:position state)
                       :heading (:heading state)
                       :up (:up state)}
        dist (-> path :commands first :args first)
        start-data (stamp-shape-with-holes state shape)
        end-pos (v+ (:position state) (v* (:heading state) dist))
        end-state (assoc state :position end-pos)
        end-data (stamp-shape-with-holes end-state shape)
        mesh (sweep-two-shapes-with-holes start-data end-data)]
    (if mesh
      (let [mesh-with-pose (cond-> (assoc mesh :creation-pose creation-pose)
                            (:material state) (assoc :material (:material state)))]
        (-> state
            (assoc :position end-pos)
            (update :meshes conj mesh-with-pose)))
      state)))

(defn- extrude-with-holes-from-path
  "Extrude a shape with holes along a complex open path.
   Uses ring-data accumulation to track both outer and hole rings."
  [state shape path]
  (let [creation-pose {:position (:position state)
                       :heading (:heading state)
                       :up (:up state)}
        radius (shape-radius shape)
        commands (:commands path)
        segments (analyze-open-path commands radius)
        n-segments (count segments)]
    (if (< n-segments 1)
      state
      (let [initial-rotations (take-while #(not= :f (:cmd %)) commands)
            state-with-initial-heading (reduce apply-rotation-to-state state initial-rotations)
            rings-result
            (loop [i 0
                   s state-with-initial-heading
                   ring-data-vec []
                   prev-had-corner true]
              (if (>= i n-segments)
                {:ring-data ring-data-vec :state s}
                (let [seg (nth segments i)
                      dist (:dist seg)
                      shorten-start (:shorten-start seg)
                      shorten-end (:shorten-end seg)
                      rotations (:rotations-after seg)
                      effective-dist (- dist shorten-start shorten-end)
                      is-last (= i (dec n-segments))
                      joint-mode (or (:joint-mode state) :flat)
                      has-corner-rotation (some #(is-corner-rotation? (:cmd %)) rotations)

                      start-pos (:position s)
                      s1 (assoc s :position start-pos)

                      emit-start? (or (zero? i) prev-had-corner)
                      start-data (when emit-start? (stamp-shape-with-holes s1 shape))

                      end-pos (v+ start-pos (v* (:heading s1) effective-dist))
                      s2 (assoc s1 :position end-pos)
                      end-data (stamp-shape-with-holes s2 shape)

                      corner-pos (v+ end-pos (v* (:heading s2) shorten-end))
                      s3 (assoc s2 :position corner-pos)

                      s4 (reduce apply-rotation-to-state s3 rotations)
                      old-heading (:heading s3)
                      new-heading (:heading s4)

                      cos-a (dot old-heading new-heading)
                      heading-angle (when (< cos-a 0.9998)
                                      (Math/acos (min 1 (max -1 cos-a))))

                      corner-ring-data
                      (if (and has-corner-rotation (not is-last))
                        (let [corner-angle-deg (when (= joint-mode :round)
                                                 (when heading-angle
                                                   (* heading-angle (/ 180 Math/PI))))
                              round-steps (when corner-angle-deg
                                            (calc-round-steps state corner-angle-deg))]
                          (case joint-mode
                            :flat []
                            :round (generate-round-corner-ring-data
                                    end-data corner-pos old-heading new-heading
                                    (or round-steps 4) radius)
                            :tapered (generate-tapered-corner-ring-data
                                      end-data corner-pos old-heading new-heading)
                            []))
                        [])

                      smooth-ring-data
                      (if (and heading-angle
                               (not has-corner-rotation)
                               (not is-last)
                               (seq rotations))
                        (let [n-smooth (max 1 (int (Math/ceil (/ heading-angle (/ Math/PI 12)))))]
                          (generate-round-corner-ring-data
                           end-data end-pos old-heading new-heading
                           n-smooth radius))
                        [])

                      next-shorten-start (if (and (not is-last) has-corner-rotation)
                                           (:shorten-start (nth segments (inc i)))
                                           0)
                      next-start-pos (if (seq smooth-ring-data)
                                       (ring-centroid (:outer (last smooth-ring-data)))
                                       (v+ corner-pos (v* (:heading s4) next-shorten-start)))
                      s-next (assoc s4 :position next-start-pos)

                      new-ring-data (cond-> ring-data-vec
                                      emit-start? (conj start-data)
                                      true (conj end-data)
                                      (seq smooth-ring-data) (into smooth-ring-data)
                                      (seq corner-ring-data) (into corner-ring-data))]
                  (recur (inc i) s-next new-ring-data (boolean has-corner-rotation)))))

            all-ring-data (:ring-data rings-result)
            final-state (:state rings-result)]
        (if (< (count all-ring-data) 2)
          state
          (let [mesh (build-sweep-mesh-with-holes all-ring-data creation-pose)
                mesh-with-material (when mesh
                                     (cond-> mesh
                                       (:material state) (assoc :material (:material state))))]
            (if mesh-with-material
              (update final-state :meshes conj mesh-with-material)
              state)))))))

(defn extrude-from-path
  "Extrude a shape along an open path, creating a SINGLE unified mesh.
   Returns the turtle state with the mesh added."
  [state shape path]
  (if-not (and (shape? shape) (is-path? path))
    state
    (if (:holes shape)
      ;; Shape with holes — use holes-aware extrusion
      (if (is-simple-forward-path? path)
        (extrude-simple-with-holes state shape path)
        (extrude-with-holes-from-path state shape path))
      (let [creation-pose {:position (:position state)
                           :heading (:heading state)
                           :up (:up state)}
            radius (shape-radius shape)
            commands (:commands path)
            segments (analyze-open-path commands radius)
            n-segments (count segments)]
        (if (< n-segments 1)
          state
          (let [initial-rotations (take-while #(not= :f (:cmd %)) commands)
                state-with-initial-heading (reduce apply-rotation-to-state state initial-rotations)
                rings-result
                (loop [i 0
                       s state-with-initial-heading
                       rings []
                       prev-had-corner true]
                  (if (>= i n-segments)
                    {:rings rings :state s}
                    (let [seg (nth segments i)
                          dist (:dist seg)
                          shorten-start (:shorten-start seg)
                          shorten-end (:shorten-end seg)
                          rotations (:rotations-after seg)
                          effective-dist (- dist shorten-start shorten-end)
                          is-last (= i (dec n-segments))
                          joint-mode (or (:joint-mode state) :flat)
                          any-corner-cmds (some #(is-corner-rotation? (:cmd %)) rotations)

                          start-pos (:position s)
                          s1 (assoc s :position start-pos)

                          emit-start-ring? (or (zero? i) prev-had-corner)
                          start-ring (when emit-start-ring? (stamp-shape s1 shape))

                          end-pos (v+ start-pos (v* (:heading s1) effective-dist))
                          s2 (assoc s1 :position end-pos)
                          end-ring (stamp-shape s2 shape)

                          corner-pos (v+ end-pos (v* (:heading s2) shorten-end))
                          s3 (assoc s2 :position corner-pos)

                          s4 (reduce apply-rotation-to-state s3 rotations)
                          old-heading (:heading s3)
                          new-heading (:heading s4)

                          cos-a (dot old-heading new-heading)
                          heading-angle (when (< cos-a 0.9998)
                                          (Math/acos (min 1 (max -1 cos-a))))

                          ;; Only treat as corner if heading actually changes significantly.
                          ;; Small rotations from bezier-as walk steps are smooth transitions,
                          ;; not corners requiring shortening/fillets/duplicate rings.
                          has-corner-rotation (and any-corner-cmds
                                                  heading-angle
                                                  (> heading-angle (* corner-threshold-deg-closed
                                                                      (/ Math/PI 180))))

                          corner-rings (if (and has-corner-rotation (not is-last))
                                         (let [corner-angle-deg (when (= joint-mode :round)
                                                                  (when heading-angle
                                                                    (* heading-angle (/ 180 Math/PI))))
                                               round-steps (when corner-angle-deg
                                                             (calc-round-steps state corner-angle-deg))]
                                           (case joint-mode
                                             :flat []
                                             :round (generate-round-corner-rings
                                                     end-ring corner-pos old-heading new-heading
                                                     (or round-steps 4) radius)
                                             :tapered (generate-tapered-corner-rings
                                                       end-ring corner-pos old-heading new-heading)
                                             []))
                                         [])

                          smooth-transition-rings
                          (if (and heading-angle
                                   (not has-corner-rotation)
                                   (not is-last)
                                   (seq rotations))
                            (let [n-smooth (max 1 (int (Math/ceil (/ heading-angle (/ Math/PI 12)))))]
                              (generate-round-corner-rings
                               end-ring end-pos old-heading new-heading
                               n-smooth radius))
                            [])

                          next-shorten-start (if (and (not is-last) has-corner-rotation)
                                               (:shorten-start (nth segments (inc i)))
                                               0)
                          next-start-pos (if (seq smooth-transition-rings)
                                           (ring-centroid (last smooth-transition-rings))
                                           (v+ corner-pos (v* (:heading s4) next-shorten-start)))
                          s-next (assoc s4 :position next-start-pos)

                          new-rings (cond-> rings
                                      emit-start-ring? (conj start-ring)
                                      true (conj end-ring)
                                      (seq smooth-transition-rings) (into smooth-transition-rings)
                                      (seq corner-rings) (into corner-rings))]
                      (recur (inc i) s-next new-rings (boolean has-corner-rotation)))))

                all-rings (:rings rings-result)
                final-state (:state rings-result)
                n-rings (count all-rings)
                n-verts (count (first all-rings))]
            (if (< n-rings 2)
              state
              (let [first-ring (first all-rings)
                    last-ring (last all-rings)

                    vertices (vec (apply concat all-rings))

                    second-ring (nth all-rings 1)
                    second-to-last-ring (nth all-rings (- n-rings 2))

                    is-simple-straight? (= n-segments 1)
                    overall-extrusion-dir (v- (ring-centroid last-ring) (ring-centroid first-ring))
                    initial-heading (:heading state)
                    backward? (and is-simple-straight?
                                   (neg? (dot overall-extrusion-dir initial-heading)))

                    bottom-extrusion-dir (normalize (v- (ring-centroid second-ring)
                                                        (ring-centroid first-ring)))
                    bottom-normal (v* bottom-extrusion-dir -1)

                    top-extrusion-dir (normalize (v- (ring-centroid last-ring)
                                                     (ring-centroid second-to-last-ring)))
                    top-normal top-extrusion-dir

                    bottom-cap-flip false
                    top-cap-flip false
                    bottom-cap-faces (triangulate-cap first-ring 0 bottom-normal bottom-cap-flip)

                    side-faces (vec
                                (mapcat
                                 (fn [ring-idx]
                                   (mapcat
                                    (fn [vert-idx]
                                      (let [next-vert (mod (inc vert-idx) n-verts)
                                            base (* ring-idx n-verts)
                                            next-base (* (inc ring-idx) n-verts)
                                            b0 (+ base vert-idx)
                                            b1 (+ base next-vert)
                                            t0 (+ next-base vert-idx)
                                            t1 (+ next-base next-vert)
                                            db0t1 (v- (nth vertices b0) (nth vertices t1))
                                            db1t0 (v- (nth vertices b1) (nth vertices t0))]
                                        (if (<= (dot db0t1 db0t1) (dot db1t0 db1t0))
                                          (if backward?
                                            [[b0 t1 t0] [b0 b1 t1]]
                                            [[b0 t0 t1] [b0 t1 b1]])
                                          (if backward?
                                            [[b0 b1 t0] [t0 b1 t1]]
                                            [[b0 t0 b1] [t0 t1 b1]]))))
                                    (range n-verts)))
                                 (range (dec n-rings))))

                    last-ring-base (* (dec n-rings) n-verts)
                    top-cap-faces (triangulate-cap last-ring last-ring-base top-normal top-cap-flip)

                    all-faces (vec (concat bottom-cap-faces side-faces top-cap-faces))

                    mesh (schema/assert-mesh!
                          (cond-> {:type :mesh
                                   :primitive :extrusion
                                   :vertices vertices
                                   :faces all-faces
                                   :creation-pose creation-pose}
                            (:material state) (assoc :material (:material state))))]
                (update final-state :meshes conj mesh)))))))))

(defn extrude-closed-from-path
  "Extrude a shape along a closed path, creating a torus-like mesh.
   Returns the turtle state with the mesh added."
  [state shape path]
  (if-not (and (shape? shape) (is-path? path))
    state
    (let [creation-pose {:position (:position state)
                         :heading (:heading state)
                         :up (:up state)}
          radius (shape-radius shape)
          commands (:commands path)
          segments (analyze-closed-path commands radius)
          n-segments (count segments)]
      (if (< n-segments 1)
        state
        (let [initial-rotations (take-while #(not= :f (:cmd %)) commands)
              state-with-initial-heading (reduce apply-rotation-to-state state initial-rotations)
              rings-result
              (loop [i 0
                     s state-with-initial-heading
                     rings []
                     prev-had-corner true]
                (if (>= i n-segments)
                  {:rings rings :state s}
                  (let [seg (nth segments i)
                        dist (:dist seg)
                        shorten-start (:shorten-start seg)
                        shorten-end (:shorten-end seg)
                        rotations (:rotations-after seg)
                        effective-dist (- dist shorten-start shorten-end)
                        joint-mode (or (:joint-mode state) :flat)
                        has-corner-rotation (some #(is-corner-rotation? (:cmd %)) rotations)

                        next-seg (nth segments (mod (inc i) n-segments))
                        next-shorten-start (:shorten-start next-seg)

                        s1 (if (and (zero? i) (pos? shorten-start))
                             (assoc s :position (v+ (:position s) (v* (:heading s) shorten-start)))
                             s)

                        start-pos (:position s1)

                        emit-start-ring? (or (zero? i) prev-had-corner)
                        start-ring (when emit-start-ring? (stamp-shape s1 shape))

                        s2 (assoc s1 :position (v+ start-pos (v* (:heading s1) effective-dist)))
                        end-pos (:position s2)

                        end-ring (stamp-shape s2 shape)

                        corner-pos (v+ end-pos (v* (:heading s2) shorten-end))
                        s3 (assoc s2 :position corner-pos)

                        s4 (reduce apply-rotation-to-state s3 rotations)
                        old-heading (:heading s3)
                        new-heading (:heading s4)

                        cos-a (dot old-heading new-heading)
                        heading-angle (when (< cos-a 0.9998)
                                        (Math/acos (min 1 (max -1 cos-a))))

                        corner-rings (if has-corner-rotation
                                       (let [corner-angle-deg (when (= joint-mode :round)
                                                                (when heading-angle
                                                                  (* heading-angle (/ 180 Math/PI))))
                                             round-steps (when corner-angle-deg
                                                           (calc-round-steps state corner-angle-deg))]
                                         (case joint-mode
                                           :flat []
                                           :round (generate-round-corner-rings
                                                   end-ring corner-pos old-heading new-heading
                                                   (or round-steps 4) radius)
                                           :tapered (generate-tapered-corner-rings
                                                     end-ring corner-pos old-heading new-heading)
                                           []))
                                       [])

                        smooth-transition-rings
                        (if (and heading-angle
                                 (not has-corner-rotation)
                                 (seq rotations))
                          (let [n-smooth (max 1 (int (Math/ceil (/ heading-angle (/ Math/PI 12)))))]
                            (generate-round-corner-rings
                             end-ring end-pos old-heading new-heading
                             n-smooth radius))
                          [])

                        corner-start-pos (if (seq smooth-transition-rings)
                                           (ring-centroid (last smooth-transition-rings))
                                           (v+ (:position s4) (v* (:heading s4) next-shorten-start)))

                        new-rings (cond-> rings
                                    emit-start-ring? (conj start-ring)
                                    true (conj end-ring)
                                    (seq smooth-transition-rings) (into smooth-transition-rings)
                                    (seq corner-rings) (into corner-rings))

                        s5 (assoc s4 :position corner-start-pos)]
                    (recur (inc i) s5 new-rings (boolean has-corner-rotation)))))

              all-rings (:rings rings-result)
              final-state (:state rings-result)

              initial-heading (:heading creation-pose)
              final-heading (:heading final-state)
              cos-angle (dot final-heading initial-heading)
              closing-angle (Math/acos (min 1 (max -1 cos-angle)))
              needs-closing-corner (> closing-angle 0.1)
              joint-mode (or (:joint-mode state) :flat)

              closing-corner-rings
              (when needs-closing-corner
                (let [last-ring (last all-rings)
                      first-ring (first all-rings)
                      last-centroid (ring-centroid last-ring)
                      first-centroid (ring-centroid first-ring)
                      corner-angle-deg (* closing-angle (/ 180 Math/PI))
                      round-steps (calc-round-steps state corner-angle-deg)]
                  (case joint-mode
                    :flat []
                    :round (generate-round-corner-rings
                            last-ring last-centroid final-heading initial-heading
                            round-steps radius)
                    :tapered (generate-tapered-corner-rings
                              last-ring last-centroid final-heading initial-heading)
                    [])))

              final-rings (cond-> all-rings
                            (seq closing-corner-rings) (into closing-corner-rings))

              final-rings-with-closure (conj final-rings (first final-rings))

              mesh (build-sweep-mesh final-rings-with-closure true creation-pose)]
          (if mesh
            (let [mesh-with-material (cond-> mesh
                                       (:material state) (assoc :material (:material state)))]
              (update final-state :meshes conj mesh-with-material))
            final-state))))))
