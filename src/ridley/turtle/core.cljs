(ns ridley.turtle.core
  "Immutable turtle state and movement commands.

   State structure:
   {:position [x y z]     - current position
    :heading [x y z]      - forward direction (unit vector)
    :up [x y z]           - up direction (unit vector)
    :pen-mode             - :off, :on, or :shape (internal)
    :stamped-shape        - for :shape mode: current 3D vertices of shape
    :sweep-rings          - for :shape mode: accumulated rings for unified mesh
    :pending-rotation     - for :shape mode: deferred rotation to create fillet
    :geometry []          - accumulated line segments
    :meshes []}           - accumulated 3D meshes

   API:
   - (pen :off) - stop drawing lines
   - (pen :on) - draw lines (default)
   - (extrude shape movements...) - stamp shape and extrude via movements"
  (:require ["earcut" :default earcut]
            [ridley.manifold.core :as manifold]))

(defn make-turtle
  "Create initial turtle state at origin, facing +X, up +Z.
   This makes 2D drawing happen in the XY plane (Z=0),
   ideal for extruding along Z."
  []
  {:position [0 0 0]
   :heading [1 0 0]
   :up [0 0 1]
   :pen-mode :on            ; :off, :on, or :shape (internal for extrude)
   :stamped-shape nil       ; for :shape mode: current 3D vertices of shape
   :sweep-rings []          ; for :shape mode: accumulated rings for unified mesh
   :geometry []
   :meshes []
   :state-stack []          ; stack for push-state/pop-state
   :anchors {}              ; named poses for mark/goto
   :attached nil            ; attachment state for face/mesh operations
   :resolution {:mode :n :value 16}  ; curve resolution (like OpenSCAD $fn)
   :material {:color 0x00aaff        ; hex color
              :metalness 0.3         ; 0-1, PBR metalness
              :roughness 0.7         ; 0-1, PBR roughness
              :opacity 1.0           ; 0-1, transparency
              :flat-shading true}})  ; flat vs smooth shading

;; --- Numeric validation ---

(defn- check-num
  "Validate that a value is a finite number. Throws with a clear message if not.
   Catches NaN and non-numeric values early, before they corrupt geometry."
  [value command-name]
  (when-not (and (number? value) (js/isFinite value))
    (throw (js/Error. (str "(" command-name " " (pr-str value) "): expected a number, got "
                           (cond
                             (js/Number.isNaN value) "NaN (bad arithmetic?)"
                             (not (number? value)) (str (type value))
                             :else "Infinity"))))))

;; --- State stack ---

(defn push-state
  "Push current turtle pose (position, heading, up, pen-mode) onto the stack.
   Use pop-state to restore. Useful for branching or temporary movements.
   Meshes and geometry created between push and pop are kept."
  [state]
  (let [pose {:position (:position state)
              :heading (:heading state)
              :up (:up state)
              :pen-mode (:pen-mode state)}]
    (update state :state-stack conj pose)))

(defn pop-state
  "Pop and restore the most recently pushed turtle pose from the stack.
   Returns state unchanged if stack is empty."
  [state]
  (let [stack (:state-stack state)]
    (if (empty? stack)
      state
      (let [pose (peek stack)]
        (-> state
            (assoc :position (:position pose))
            (assoc :heading (:heading pose))
            (assoc :up (:up pose))
            (assoc :pen-mode (:pen-mode pose))
            (assoc :state-stack (pop stack)))))))

(defn clear-stack
  "Clear the state stack without restoring any pose.
   Useful to reset after complex branching operations."
  [state]
  (assoc state :state-stack []))

;; --- Vector math utilities ---

(defn ^:export v+ [[x1 y1 z1] [x2 y2 z2]]
  [(+ x1 x2) (+ y1 y2) (+ z1 z2)])

(defn ^:export v- [[x1 y1 z1] [x2 y2 z2]]
  [(- x1 x2) (- y1 y2) (- z1 z2)])

(defn ^:export v* [[x y z] s]
  [(* x s) (* y s) (* z s)])

(defn ^:export dot [[x1 y1 z1] [x2 y2 z2]]
  (+ (* x1 x2) (* y1 y2) (* z1 z2)))

(defn ^:export cross [[x1 y1 z1] [x2 y2 z2]]
  [(- (* y1 z2) (* z1 y2))
   (- (* z1 x2) (* x1 z2))
   (- (* x1 y2) (* y1 x2))])

(defn ^:export magnitude [[x y z]]
  (Math/sqrt (+ (* x x) (* y y) (* z z))))

(defn ^:export normalize [v]
  (let [m (magnitude v)]
    (if (zero? m)
      v
      (v* v (/ 1 m)))))

(defn ^:export rotate-point-around-axis
  "Rotate point v around axis by angle (radians) using Rodrigues' formula.
   Preserves vector magnitude - use for position vectors."
  [v axis angle]
  (let [k (normalize axis)
        cos-a (Math/cos angle)
        sin-a (Math/sin angle)
        ; v' = v*cos(a) + (k x v)*sin(a) + k*(k·v)*(1-cos(a))
        term1 (v* v cos-a)
        term2 (v* (cross k v) sin-a)
        term3 (v* k (* (dot k v) (- 1 cos-a)))]
    (v+ (v+ term1 term2) term3)))

(defn ^:export rotate-around-axis
  "Rotate direction vector v around axis by angle (radians) using Rodrigues' formula.
   Result is normalized - use for direction vectors (heading, up)."
  [v axis angle]
  (normalize (rotate-point-around-axis v axis angle)))

(defn- earcut-triangulate
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

(defn- project-to-2d
  "Project 3D points to 2D by dropping the axis most aligned with normal.
   Returns [pts-2d winding-preserved?] where winding-preserved? indicates
   whether the 2D winding matches the 3D winding (false if mirrored).

   The key insight: when we drop an axis, we're projecting onto a plane.
   If the normal points in the positive direction of the dropped axis,
   the winding is preserved. If negative, it's reversed.
   But also, dropping Y uses XZ which swaps axis order, so we need to
   account for that too."
  [pts normal]
  (let [[nx ny nz] normal
        ax (Math/abs nx) ay (Math/abs ny) az (Math/abs nz)]
    (cond
      ;; Drop Z, project to XY plane
      ;; Winding preserved if normal points +Z
      (and (>= az ax) (>= az ay))
      [(mapv (fn [[x y _]] [x y]) pts) (>= nz 0)]

      ;; Drop Y, project to XZ plane
      ;; Using [x z] means we're looking from +Y direction
      ;; Winding preserved if normal points -Y (looking from +Y at -Y surface)
      (and (>= ay ax) (>= ay az))
      [(mapv (fn [[x _ z]] [x z]) pts) (< ny 0)]

      ;; Drop X, project to YZ plane
      ;; Winding preserved if normal points +X
      :else
      [(mapv (fn [[_ y z]] [y z]) pts) (>= nx 0)])))

(defn- triangulate-cap
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

(defn- triangulate-cap-with-holes
  "Triangulate a polygon cap with holes using earcut.
   - outer-ring: vector of 3D vertex positions for outer boundary
   - hole-rings: vector of 3D vertex vectors for each hole
   - base-idx: starting index in the mesh vertex array
   - normal: cap normal (for 2D projection)
   - flip?: whether to flip winding order for final triangles
   Returns vector of [i j k] face triangles with mesh indices.

   The combined vertex order is: outer-ring ++ hole1 ++ hole2 ++ ..."
  [outer-ring hole-rings base-idx normal flip?]
  (if (empty? hole-rings)
    ;; No holes - use standard triangulation
    (triangulate-cap outer-ring base-idx normal flip?)
    ;; With holes - use earcut
    (let [[outer-2d _] (project-to-2d outer-ring normal)
          holes-2d (mapv #(first (project-to-2d % normal)) hole-rings)
          local-tris (earcut-triangulate outer-2d holes-2d)]
      (mapv (fn [[i j k]]
              (if flip?
                [(+ base-idx i) (+ base-idx k) (+ base-idx j)]
                [(+ base-idx i) (+ base-idx j) (+ base-idx k)]))
            local-tris))))

;; --- Pose reset ---

(defn reset-pose
  "Reset turtle position, orientation, and pen mode to defaults.
   Keeps accumulated geometry and meshes.
   Options:
   - (reset-pose state) - reset to origin, facing +X, up +Z, pen :on
   - (reset-pose state [x y z]) - reset to position, default orientation
   - (reset-pose state [x y z] :heading [hx hy hz]) - position + heading
   - (reset-pose state [x y z] :heading [hx hy hz] :up [ux uy uz]) - full control"
  ([state]
   (reset-pose state [0 0 0]))
  ([state position]
   (assoc state
          :position (vec position)
          :heading [1 0 0]
          :up [0 0 1]
          :pen-mode :on
          :stamped-shape nil
          :sweep-rings []
          :pending-rotation nil))
  ([state position & {:keys [heading up]}]
   (let [h (if heading (normalize (vec heading)) [1 0 0])
         u (if up (normalize (vec up)) [0 0 1])]
     (assoc state
            :position (vec position)
            :heading h
            :up u
            :pen-mode :on
            :stamped-shape nil
            :sweep-rings []
            :pending-rotation nil))))

;; --- Sweep mesh building ---

(defn- build-sweep-mesh
  "Build a unified mesh from accumulated sweep rings.
   Each ring is a vector of 3D vertices.
   If closed? is true, connects last ring back to first (torus-like, no caps).
   Otherwise creates bottom cap, side faces, and top cap.
   Optional creation-pose records where the extrusion started."
  ([rings] (build-sweep-mesh rings false nil))
  ([rings closed?] (build-sweep-mesh rings closed? nil))
  ([rings closed? creation-pose]
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
               ring-centroid (fn [ring]
                               (let [n (count ring)]
                                 (v* (reduce v+ ring) (/ 1.0 n))))
               extrusion-dir (normalize (v- (ring-centroid (second rings))
                                            (ring-centroid first-ring)))

               ;; Use extrusion direction as cap normal (more robust than ring-normal)
               ;; Bottom cap: normal points opposite to extrusion (no flip needed)
               ;; Top cap: normal points same as extrusion (no flip needed)
               bottom-normal (v* extrusion-dir -1)  ; points backward
               top-normal extrusion-dir             ; points forward

               bottom-cap (triangulate-cap first-ring 0 bottom-normal false)
               top-cap (triangulate-cap last-ring last-base top-normal false)]
           (cond-> {:type :mesh
                    :primitive :sweep
                    :vertices vertices
                    :faces (vec (concat side-faces bottom-cap top-cap))}
             creation-pose (assoc :creation-pose creation-pose))))))))

;; --- Shape stamping ---

(defn- shape?
  "Check if x is a shape (has :type :shape)."
  [x]
  (and (map? x) (= :shape (:type x))))

(defn- compute-stamp-transform
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

(defn- transform-2d-to-3d
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

(defn- stamp-shape
  "Stamp a shape onto the plane perpendicular to turtle's heading.
   Returns 3D vertices of the stamped shape."
  [state shape]
  (let [params (compute-stamp-transform state shape)]
    (transform-2d-to-3d (:points shape) params)))

(defn- stamp-shape-with-holes
  "Stamp a shape with holes onto the plane perpendicular to turtle's heading.
   Returns {:outer <3D-vertices> :holes [<3D-vertices> ...]}."
  [state shape]
  (let [params (compute-stamp-transform state shape)
        outer-3d (transform-2d-to-3d (:points shape) params)
        holes-3d (when-let [holes (:holes shape)]
                   (mapv #(transform-2d-to-3d % params) holes))]
    {:outer outer-3d
     :holes holes-3d}))

;; --- Rotation utilities (needed by fillet) ---

(defn- deg->rad [deg]
  (* deg (/ Math/PI 180)))

(defn- right-vector
  "Calculate the right vector (heading x up)."
  [state]
  (cross (:heading state) (:up state)))

(defn- apply-rotations
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

;; --- Resolution settings (like OpenSCAD $fn/$fa/$fs) ---

(defn resolution
  "Set the default curve resolution for arcs, beziers, and primitives.

   Modes:
   - (resolution state :n 32)   ; fixed number of segments
   - (resolution state :a 5)    ; minimum angle per segment (degrees)
   - (resolution state :s 0.5)  ; minimum segment length (units)

   This affects: arc-h, arc-v, bezier-to, circle, sphere, cyl, cone, round joints."
  [state mode value]
  (assoc state :resolution {:mode mode :value value}))

(defn- get-resolution
  "Get current resolution settings, defaulting to {:mode :n :value 16}."
  [state]
  (or (:resolution state) {:mode :n :value 16}))

(defn calc-arc-steps
  "Calculate steps for an arc based on resolution settings.
   arc-length: approximate arc length in units
   angle-deg: total angle in degrees"
  [state arc-length angle-deg]
  (let [{:keys [mode value]} (get-resolution state)]
    (case mode
      :n value
      :a (max 1 (int (Math/ceil (/ (Math/abs angle-deg) value))))
      :s (max 1 (int (Math/ceil (/ arc-length value))))
      ;; default
      value)))

(defn calc-bezier-steps
  "Calculate steps for a bezier based on resolution settings.
   approx-length: approximate curve length in units"
  [state approx-length]
  (let [{:keys [mode value]} (get-resolution state)]
    (case mode
      :n value
      :a value  ; for bezier, :a mode uses value as steps (angle doesn't apply)
      :s (max 1 (int (Math/ceil (/ approx-length value))))
      ;; default
      value)))

(defn calc-circle-segments
  "Calculate segments for a full circle based on resolution.
   circumference: circle circumference in units"
  [state circumference]
  (let [{:keys [mode value]} (get-resolution state)]
    (case mode
      :n value
      :a (max 8 (int (Math/ceil (/ 360 value))))
      :s (max 8 (int (Math/ceil (/ circumference value))))
      ;; default
      value)))

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

;; --- Corner/Bend calculation ---

(defn- shape-radius
  "Calculate the radius of a shape (max distance from origin to any point)."
  [shape]
  (if-let [points (:points shape)]
    (reduce max 0 (map (fn [[x y]] (Math/sqrt (+ (* x x) (* y y)))) points))
    0))

(defn- build-segment-mesh
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

(defn- build-corner-mesh
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

;; --- Joint mode helper functions ---

(defn- ring-centroid
  "Calculate the centroid of a ring (vector of 3D points)."
  [ring]
  (let [n (count ring)
        sum (reduce (fn [[sx sy sz] [x y z]]
                      [(+ sx x) (+ sy y) (+ sz z)])
                    [0 0 0] ring)]
    [(/ (first sum) n) (/ (second sum) n) (/ (nth sum 2) n)]))

(defn- rotate-ring-around-axis
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

(defn- scale-ring-from-centroid
  "Scale a ring uniformly from its centroid."
  [ring scale-factor]
  (let [centroid (ring-centroid ring)]
    (mapv (fn [pt]
            (let [rel (v- pt centroid)
                  scaled (v* rel scale-factor)]
              (v+ centroid scaled)))
          ring)))

(defn- generate-round-corner-rings
  "Generate intermediate rings for a rounded corner.

   Parameters:
   - end-ring: the last ring before the corner
   - corner-pos: position at the corner vertex (unused, kept for API compatibility)
   - old-heading: heading before rotation
   - new-heading: heading after rotation
   - n-steps: number of intermediate rings (more = smoother)
   - radius: the shape radius (needed to find pivot point)

   Returns a vector of intermediate rings.
   These connect end-ring to the start of the next segment."
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
            ;; This is perpendicular to old-heading, in the plane of the turn
            ;; cross(axis, old-heading) points toward the inside of the curve
            center-dir (normalize (cross axis-norm old-heading))
            ;; The pivot is at the ring's centroid + radius in the center direction
            ;; This is the point on the cylinder surface where the two cylinders touch
            end-centroid (ring-centroid end-ring)
            pivot (v+ end-centroid (v* center-dir radius))]
        ;; Generate intermediate rings by rotating around the pivot point
        ;; The end-ring stays where it is (at end-pos), we rotate it around the pivot
        (vec
         (for [i (range 1 (inc n-steps))]
           (let [angle (* i step-angle)]
             (rotate-ring-around-axis end-ring pivot axis-norm angle))))))))

(defn- scale-ring-along-direction
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

(defn- generate-tapered-corner-rings
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
        ;; At 90°: scale = 1/cos(45°) ≈ 1.414
        ;; At 60°: scale = 1/cos(30°) ≈ 1.155
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
            ;; Scale only along the bisector direction (perpendicular to the axis)
            ;; The stretch direction is perpendicular to both axis and the rotated heading
            stretch-dir (normalize (cross axis-norm (normalize (v+ old-heading new-heading))))
            scaled-ring (scale-ring-along-direction rotated-ring stretch-dir scale-factor)]
        [scaled-ring]))))

(defn- process-pending-rotation
  "Process a pending rotation when moving forward.
   Creates separate meshes for the pre-rotation segment and corner.

   Strategy:
   1. Finalize current segment (rings accumulated so far) - shortened by radius
   2. Create corner mesh connecting end of old segment to start of new segment
   3. Start fresh rings for next segment - starting at radius offset

   This ensures no internal faces at corners."
  [state _dist]
  (let [pending (:pending-rotation state)
        {:keys [heading up]} (apply-rotations state pending)
        base-shape (:sweep-base-shape state)
        radius (shape-radius base-shape)
        old-heading (:heading state)
        pos (:position state)
        rings (:sweep-rings state)

        ;; Corner position: pull back from current pos by radius along old heading
        corner-pos (v+ pos (v* old-heading (- radius)))

        ;; New turtle position: advance from corner by radius along NEW heading
        new-pos (v+ corner-pos (v* heading radius))

        ;; Create the end ring of the pre-rotation segment (at corner-pos, old orientation)
        end-ring (stamp-shape (assoc state :position corner-pos) base-shape)

        ;; Replace last ring with shortened version and build segment mesh
        segment-rings (if (seq rings)
                        (conj (pop rings) end-ring)
                        [end-ring])
        segment-mesh (build-segment-mesh segment-rings)

        ;; Create first ring of new segment (at new-pos, new orientation)
        first-new-ring (stamp-shape (-> state
                                        (assoc :position new-pos)
                                        (assoc :heading heading)
                                        (assoc :up up))
                                    base-shape)

        ;; Create corner mesh: from end-ring to first-new-ring
        ;; This connects the end of old segment to start of new segment
        corner-mesh (build-corner-mesh end-ring first-new-ring)]
    (-> state
        (assoc :heading heading)
        (assoc :up up)
        (assoc :position new-pos)
        ;; Add segment mesh and corner mesh to accumulated meshes
        (update :meshes (fn [meshes]
                          (cond-> meshes
                            segment-mesh (conj segment-mesh)
                            corner-mesh (conj corner-mesh))))
        ;; Start fresh rings for next segment
        (assoc :sweep-rings [first-new-ring])
        (dissoc :pending-rotation))))

;; --- Mesh movement when attached ---

(defn- mesh-centroid
  "Calculate the centroid of a mesh from its vertices."
  [mesh]
  (let [verts (:vertices mesh)
        n (count verts)]
    (if (pos? n)
      (v* (reduce v+ [0 0 0] verts) (/ 1 n))
      [0 0 0])))

(defn- translate-mesh
  "Translate all vertices of a mesh by an offset vector."
  [mesh offset]
  (-> mesh
      (update :vertices (fn [verts] (mapv #(v+ % offset) verts)))
      (update :creation-pose
              (fn [pose]
                (when pose
                  (update pose :position #(v+ % offset)))))))

(defn- rotate-mesh
  "Rotate all vertices of a mesh around its centroid by angle (radians) around axis.
   Also rotates the creation-pose heading and up vectors."
  [mesh axis angle]
  (let [centroid (mesh-centroid mesh)
        rotate-vertex (fn [pt]
                        (let [rel (v- pt centroid)
                              rotated (rotate-point-around-axis rel axis angle)]
                          (v+ centroid rotated)))]
    (-> mesh
        (update :vertices (fn [verts] (mapv rotate-vertex verts)))
        (update :creation-pose
                (fn [pose]
                  (when pose
                    (-> pose
                        (update :position rotate-vertex)
                        (update :heading #(rotate-around-axis % axis angle))
                        (update :up #(rotate-around-axis % axis angle)))))))))

(defn- scale-mesh
  "Scale all vertices of a mesh uniformly from its centroid."
  [mesh factor]
  (let [centroid (mesh-centroid mesh)]
    (-> mesh
        (update :vertices
                (fn [verts]
                  (mapv (fn [v]
                          (let [rel (v- v centroid)
                                scaled (v* rel factor)]
                            (v+ centroid scaled)))
                        verts))))))

(defn- replace-mesh-in-state
  "Replace a mesh in the state's meshes vector.
   If the old mesh is not found, adds the new mesh to the end."
  [state old-mesh new-mesh]
  (let [meshes (:meshes state)
        found? (some #(identical? % old-mesh) meshes)]
    (if found?
      (update state :meshes
              (fn [ms] (mapv #(if (identical? % old-mesh) new-mesh %) ms)))
      ;; Not found - add the new mesh
      (update state :meshes conj new-mesh))))

(defn- rotate-attached-mesh
  "Rotate the attached mesh around its centroid using the given axis.
   Also rotates the turtle's heading and up vectors so subsequent movements
   follow the new orientation."
  [state axis angle-deg]
  (let [attachment (:attached state)
        mesh (:mesh attachment)
        rad (deg->rad angle-deg)
        new-mesh (rotate-mesh mesh axis rad)
        ;; Also rotate the turtle's heading and up
        new-heading (rotate-around-axis (:heading state) axis rad)
        new-up (rotate-around-axis (:up state) axis rad)]
    (-> state
        (replace-mesh-in-state mesh new-mesh)
        (assoc :heading new-heading)
        (assoc :up new-up)
        (assoc-in [:attached :mesh] new-mesh)
        (assoc-in [:attached :original-pose] (:creation-pose new-mesh)))))

(defn- scale-attached-mesh
  "Scale the attached mesh uniformly from its centroid."
  [state factor]
  (let [attachment (:attached state)
        mesh (:mesh attachment)
        new-mesh (scale-mesh mesh factor)]
    (-> state
        (replace-mesh-in-state mesh new-mesh)
        (assoc-in [:attached :mesh] new-mesh))))

(defn- move-attached-mesh
  "Move the attached mesh along the turtle's heading direction."
  [state dist]
  (let [attachment (:attached state)
        mesh (:mesh attachment)
        heading (:heading state)
        offset (v* heading dist)
        new-mesh (translate-mesh mesh offset)
        new-pos (v+ (:position state) offset)]
    (-> state
        (replace-mesh-in-state mesh new-mesh)
        (assoc :position new-pos)
        (assoc-in [:attached :mesh] new-mesh)
        (assoc-in [:attached :original-pose :position] new-pos))))

;; --- Face extrusion when attached to face ---

(defn- extract-face-perimeter
  "Extract ordered perimeter vertices from face triangles.
   Returns vector of vertex indices in order around the face boundary."
  [triangles]
  (let [;; Collect all edges from triangles
        edges (mapcat (fn [[a b c]] [[a b] [b c] [c a]]) triangles)
        ;; Count edge occurrences (boundary edges appear once, internal edges twice)
        edge-counts (frequencies (map (fn [[a b]] #{a b}) edges))
        ;; Keep only boundary edges (appear once)
        boundary-edges (filter (fn [[a b]] (= 1 (get edge-counts #{a b}))) edges)
        ;; Build adjacency map
        adj (reduce (fn [m [a b]] (update m a (fnil conj []) b))
                    {} boundary-edges)]
    ;; Walk the boundary starting from first edge
    (when (seq boundary-edges)
      (let [start (ffirst boundary-edges)]
        (loop [current start
               visited #{}
               result []]
          (if (contains? visited current)
            result
            (let [next-v (first (filter #(not (contains? visited %))
                                        (get adj current)))]
              (if next-v
                (recur next-v (conj visited current) (conj result current))
                (conj result current)))))))))

(defn- build-face-extrusion
  "Extrude a face by creating new vertices and side faces.
   Returns updated mesh with extruded face."
  [mesh face-id face-info dist]
  (let [vertices (:vertices mesh)
        faces (:faces mesh)
        face-groups (:face-groups mesh)
        normal (:normal face-info)
        offset (v* normal dist)

        ;; Get face triangles and extract ordered perimeter
        face-triangles (:triangles face-info)
        perimeter (extract-face-perimeter face-triangles)
        n-old-verts (count vertices)
        n-perimeter (count perimeter)

        ;; Create new vertices at offset positions (for perimeter vertices only)
        new-verts (mapv (fn [idx]
                          (v+ (nth vertices idx) offset))
                        perimeter)

        ;; Map old perimeter vertex indices to new vertex indices
        index-mapping (zipmap perimeter
                              (range n-old-verts (+ n-old-verts n-perimeter)))

        ;; Build side faces (quads as two triangles each)
        ;; For each edge of the perimeter, create a quad connecting old to new vertices
        side-faces (vec
                    (mapcat
                     (fn [i]
                       (let [next-i (mod (inc i) n-perimeter)
                             ;; Old edge vertices (perimeter order)
                             b0 (nth perimeter i)
                             b1 (nth perimeter next-i)
                             ;; New edge vertices
                             t0 (get index-mapping b0)
                             t1 (get index-mapping b1)]
                         ;; Two triangles forming the quad
                         ;; Side faces need CCW winding when viewed from outside
                         ;; For extrusion outward: b0-b1 is bottom edge, t0-t1 is top edge
                         [[b0 b1 t1] [b0 t1 t0]]))
                     (range n-perimeter)))

        ;; Create new top face triangles (same winding, new indices)
        new-top-triangles (mapv (fn [[i j k]]
                                  [(get index-mapping i)
                                   (get index-mapping j)
                                   (get index-mapping k)])
                                face-triangles)

        ;; Remove old face triangles from faces list
        old-face-set (set face-triangles)
        remaining-faces (vec (remove old-face-set faces))

        ;; Combine all faces
        all-faces (vec (concat remaining-faces side-faces new-top-triangles))

        ;; Update face-groups
        side-face-id (keyword (str (name face-id) "-sides-" (count vertices)))
        new-face-groups (-> face-groups
                           (assoc face-id new-top-triangles)
                           (assoc side-face-id side-faces))]

    (assoc mesh
           :vertices (vec (concat vertices new-verts))
           :faces all-faces
           :face-groups new-face-groups)))

(defn- extrude-attached-face
  "Extrude the attached face along its normal."
  [state dist]
  (let [attachment (:attached state)
        mesh (:mesh attachment)
        face-id (:face-id attachment)
        face-info (:face-info attachment)
        normal (:normal face-info)
        center (:center face-info)

        ;; Build extruded mesh
        new-mesh (build-face-extrusion mesh face-id face-info dist)

        ;; Calculate new face center
        new-center (v+ center (v* normal dist))

        ;; Update face info with new center and triangles
        new-triangles (get-in new-mesh [:face-groups face-id])
        new-face-info (-> face-info
                          (assoc :center new-center)
                          (assoc :triangles new-triangles)
                          ;; Update vertex indices to the new vertices
                          (assoc :vertices (vec (distinct (mapcat identity new-triangles)))))]
    (-> state
        (replace-mesh-in-state mesh new-mesh)
        (assoc :position new-center)
        (assoc-in [:attached :mesh] new-mesh)
        (assoc-in [:attached :face-info] new-face-info))))

(defn- move-attached-face
  "Move the attached face along its normal WITHOUT creating side faces.
   Updates the vertex positions of the face directly."
  [state dist]
  (let [attachment (:attached state)
        mesh (:mesh attachment)
        face-info (:face-info attachment)
        normal (:normal face-info)
        offset (v* normal dist)

        ;; Get face vertices (from perimeter)
        face-triangles (:triangles face-info)
        perimeter (extract-face-perimeter face-triangles)

        ;; Move each perimeter vertex by offset
        vertices (:vertices mesh)
        new-vertices (reduce
                      (fn [verts idx]
                        (assoc verts idx (v+ (nth verts idx) offset)))
                      (vec vertices)
                      perimeter)

        new-mesh (assoc mesh :vertices new-vertices)
        new-center (v+ (:center face-info) offset)
        new-face-info (assoc face-info :center new-center)]

    (-> state
        (replace-mesh-in-state mesh new-mesh)
        (assoc :position new-center)
        (assoc-in [:attached :mesh] new-mesh)
        (assoc-in [:attached :face-info] new-face-info))))

(defn- rotate-attached-face
  "Rotate the attached face around an axis passing through its center.
   Updates vertex positions of the face directly."
  [state axis angle-deg]
  (if-let [attachment (:attached state)]
    (let [mesh (:mesh attachment)
          face-info (:face-info attachment)
          center (:center face-info)
          rad (deg->rad angle-deg)

          ;; Get face vertices (from perimeter)
          face-triangles (:triangles face-info)
          perimeter (extract-face-perimeter face-triangles)]

      (if (seq perimeter)
        ;; Rotate each perimeter vertex around center
        (let [vertices (:vertices mesh)
              new-vertices (reduce
                            (fn [verts idx]
                              (let [v (nth verts idx)
                                    ;; Translate to origin, rotate, translate back
                                    v-centered (v- v center)
                                    v-rotated (rotate-point-around-axis v-centered axis rad)
                                    v-final (v+ v-rotated center)]
                                (assoc verts idx v-final)))
                            (vec vertices)
                            perimeter)

              new-mesh (assoc mesh :vertices new-vertices)

              ;; Also rotate the face normal and heading
              old-normal (:normal face-info)
              old-heading (:heading face-info)
              new-normal (rotate-around-axis old-normal axis rad)
              new-heading (rotate-around-axis old-heading axis rad)
              new-face-info (-> face-info
                                (assoc :normal new-normal)
                                (assoc :heading new-heading))

              ;; Update turtle orientation to match new face orientation
              new-up (normalize (cross new-normal new-heading))]

          (-> state
              (replace-mesh-in-state mesh new-mesh)
              (assoc :heading new-normal)
              (assoc :up new-up)
              (assoc-in [:attached :mesh] new-mesh)
              (assoc-in [:attached :face-info] new-face-info)))
        ;; No perimeter found, return state unchanged
        state))
    ;; No attachment, return state unchanged
    state))

;; --- Face inset ---

(defn- build-face-inset
  "Inset a face by creating new smaller face and connecting trapezoid sides.
   Positive dist = inset (smaller), negative = outset (larger).
   Returns updated mesh."
  [mesh face-id face-info dist]
  (let [vertices (:vertices mesh)
        faces (:faces mesh)
        face-groups (:face-groups mesh)
        center (:center face-info)

        ;; Get face triangles and extract ordered perimeter
        face-triangles (:triangles face-info)
        perimeter (extract-face-perimeter face-triangles)
        n-old-verts (count vertices)
        n-perimeter (count perimeter)

        ;; Calculate inset direction for each vertex (toward center)
        ;; We move each vertex toward the centroid by dist units
        inset-verts (mapv (fn [idx]
                           (let [v (nth vertices idx)
                                 to-center (normalize (v- center v))
                                 ;; Move toward center by dist
                                 new-v (v+ v (v* to-center dist))]
                             new-v))
                         perimeter)

        ;; Map old perimeter vertex indices to new vertex indices
        index-mapping (zipmap perimeter
                              (range n-old-verts (+ n-old-verts n-perimeter)))

        ;; Build side faces (trapezoids as two triangles each)
        ;; Connect outer edge (old vertices) to inner edge (new vertices)
        side-faces (vec
                    (mapcat
                     (fn [i]
                       (let [next-i (mod (inc i) n-perimeter)
                             ;; Outer edge vertices (original)
                             o0 (nth perimeter i)
                             o1 (nth perimeter next-i)
                             ;; Inner edge vertices (inset)
                             i0 (get index-mapping o0)
                             i1 (get index-mapping o1)]
                         ;; Two triangles forming the trapezoid
                         ;; Winding: CCW from outside (same as face normal)
                         [[o0 o1 i1] [o0 i1 i0]]))
                     (range n-perimeter)))

        ;; Create new inner face triangles (same winding, new indices)
        new-inner-triangles (mapv (fn [[i j k]]
                                    [(get index-mapping i)
                                     (get index-mapping j)
                                     (get index-mapping k)])
                                  face-triangles)

        ;; Remove old face triangles from faces list
        old-face-set (set face-triangles)
        remaining-faces (vec (remove old-face-set faces))

        ;; Combine all faces
        all-faces (vec (concat remaining-faces side-faces new-inner-triangles))

        ;; Update face-groups
        ;; The inner face keeps the original face-id
        ;; Side faces get a new id
        side-face-id (keyword (str (name face-id) "-inset-sides-" (count vertices)))
        new-face-groups (-> face-groups
                           (assoc face-id new-inner-triangles)
                           (assoc side-face-id side-faces))]

    (assoc mesh
           :vertices (vec (concat vertices inset-verts))
           :faces all-faces
           :face-groups new-face-groups)))

(defn- move-face-vertices-toward-center
  "Move face vertices toward center by dist units (in place).
   Positive dist = smaller face, negative = larger.
   Does NOT create new vertices - modifies existing ones.
   Returns updated mesh."
  [mesh face-info dist]
  (let [vertices (:vertices mesh)
        center (:center face-info)
        face-triangles (:triangles face-info)
        perimeter (extract-face-perimeter face-triangles)
        ;; Move each perimeter vertex toward center by dist
        new-vertices (reduce
                      (fn [verts idx]
                        (let [v (nth verts idx)
                              to-center (normalize (v- center v))
                              new-v (v+ v (v* to-center dist))]
                          (assoc verts idx new-v)))
                      (vec vertices)
                      perimeter)]
    (assoc mesh :vertices new-vertices)))

(defn- inset-attached-face
  "Inset the attached face.
   In clone-face context (after attach-face-extrude): creates new inner face
   with side trapezoids, and enables extrude-mode so next f creates a spike.
   In attach-face context: moves existing vertices toward center (frustum base).
   Returns updated state."
  [state dist]
  (let [attachment (:attached state)
        mesh (:mesh attachment)
        face-id (:face-id attachment)
        face-info (:face-info attachment)
        clone-context? (:clone-context attachment)]

    (if clone-context?
      ;; Clone mode: create new inner face with connecting side faces
      ;; After this, enable extrude-mode so f will create spike side faces
      (let [new-mesh (build-face-inset mesh face-id face-info dist)
            new-triangles (get-in new-mesh [:face-groups face-id])
            new-face-info (-> face-info
                              (assoc :triangles new-triangles)
                              (assoc :vertices (vec (distinct (mapcat identity new-triangles)))))]
        (-> state
            (replace-mesh-in-state mesh new-mesh)
            (assoc-in [:attached :mesh] new-mesh)
            (assoc-in [:attached :face-info] new-face-info)
            ;; Enable extrude-mode so next f creates side faces for spike
            (assoc-in [:attached :extrude-mode] true)))

      ;; Attach mode: move existing face vertices toward center
      (let [new-mesh (move-face-vertices-toward-center mesh face-info dist)]
        (-> state
            (replace-mesh-in-state mesh new-mesh)
            (assoc-in [:attached :mesh] new-mesh))))))

(defn inset
  "Inset the attached face by dist units.
   Positive = smaller face (toward center).
   Negative = larger face (away from center).
   Only works when attached to a face."
  [state dist]
  (if-let [attachment (:attached state)]
    (if (= :face (:type attachment))
      (inset-attached-face state dist)
      state)
    state))

(defn- build-face-scale
  "Scale a face uniformly from its center.
   factor > 1 = larger, factor < 1 = smaller.
   Modifies vertices in place (doesn't create new vertices).
   Returns updated mesh."
  [mesh face-info factor]
  (let [vertices (:vertices mesh)
        center (:center face-info)
        ;; Get face triangles and extract ordered perimeter
        face-triangles (:triangles face-info)
        perimeter (extract-face-perimeter face-triangles)
        ;; Scale each perimeter vertex from the center
        new-vertices (reduce
                      (fn [verts idx]
                        (let [v (nth verts idx)
                              rel (v- v center)
                              scaled (v* rel factor)
                              new-v (v+ center scaled)]
                          (assoc verts idx new-v)))
                      (vec vertices)
                      perimeter)]
    (assoc mesh :vertices new-vertices)))

(defn- scale-attached-face
  "Scale the attached face uniformly from its center.
   Returns updated state with scaled face."
  [state factor]
  (let [attachment (:attached state)
        mesh (:mesh attachment)
        face-id (:face-id attachment)
        face-info (:face-info attachment)
        ;; Build scaled mesh
        new-mesh (build-face-scale mesh face-info factor)
        ;; Update face-info: center stays the same after uniform scaling from center
        ;; but recalculate to get updated vertex positions
        new-face-info (assoc face-info
                             :center (:center face-info))]  ; center unchanged
    (-> state
        (replace-mesh-in-state mesh new-mesh)
        (assoc-in [:attached :mesh] new-mesh)
        (assoc-in [:attached :face-info] (assoc new-face-info :id face-id)))))

(defn scale
  "Scale the attached geometry uniformly from its centroid.
   factor > 1 = larger, factor < 1 = smaller.
   Works with both mesh attachment (:pose) and face attachment (:face)."
  [state factor]
  (if-let [attachment (:attached state)]
    (case (:type attachment)
      :pose (scale-attached-mesh state factor)
      :face (scale-attached-face state factor)
      state)
    state))

;; --- Movement commands ---

(defn- move
  "Move turtle by distance along a direction vector.
   Behavior depends on pen-mode:
   - :off or nil - just move, no drawing
   - :on - add line segment to geometry
   - :shape - accumulate ring for sweep mesh"
  [state direction dist]
  (let [pos (:position state)
        new-pos (v+ pos (v* direction dist))
        mode (:pen-mode state)]
    (case mode
      (:off nil)
      (assoc state :position new-pos)

      :on
      (-> state
          (assoc :position new-pos)
          (update :geometry conj {:type :line
                                  :from pos
                                  :to new-pos
                                  :color (get-in state [:material :color])}))

      :shape
      ;; Shape mode: check for pending rotation first
      (let [state' (if (:pending-rotation state)
                     (process-pending-rotation state dist)
                     state)
            base-shape (:sweep-base-shape state')
            rings (:sweep-rings state')
            closed? (:sweep-closed? state')
            radius (shape-radius base-shape)
            ;; For closed extrusion with empty rings: add offset first ring
            state'' (if (and closed? (empty? rings))
                      ;; First movement in closed extrusion: start at offset position
                      (let [offset-pos (v+ (:position state') (v* (:heading state') radius))
                            first-ring (stamp-shape (assoc state' :position offset-pos) base-shape)]
                        (-> state'
                            (assoc :position offset-pos)
                            (assoc :sweep-rings [first-ring])
                            ;; Save this offset first ring for closing
                            (assoc :sweep-closed-first-ring first-ring)))
                      state')
            ;; Move forward from current position
            new-pos' (v+ (:position state'') (v* (:heading state'') dist))
            new-state (assoc state'' :position new-pos')
            ;; Re-stamp the 2D shape at the new position/orientation
            new-shape (stamp-shape new-state base-shape)]
        (-> new-state
            (assoc :stamped-shape new-shape)
            ;; Add new ring to sweep-rings (mesh built at finalize)
            (update :sweep-rings conj new-shape)))

      :loft
      ;; Loft mode: track distance and orientations (rings built at finalize)
      ;; Check for pending rotation first - record corner info
      (let [pending (:pending-rotation state)
            ;; If there's a pending rotation, apply it and record corner
            state' (if pending
                     (let [{:keys [heading up]} (apply-rotations state pending)
                           old-heading (:heading state)
                           ;; Record corner info for processing at finalize
                           corner {:position pos
                                   :old-heading old-heading
                                   :new-heading heading
                                   :new-up up
                                   :dist-at-corner (:loft-total-dist state)}]
                       (-> state
                           (assoc :heading heading)
                           (assoc :up up)
                           (update :loft-corners (fnil conj []) corner)
                           (dissoc :pending-rotation)))
                     state)
            ;; Now do the forward movement with updated heading
            current-shape (:stamped-shape state')
            actual-heading (:heading state')
            offset (v* actual-heading dist)
            new-shape (mapv #(v+ % offset) current-shape)
            new-pos' (v+ (:position state') offset)
            new-total-dist (+ (:loft-total-dist state') (Math/abs dist))
            ;; Save turtle orientation for this waypoint
            orientation {:position new-pos'
                         :heading actual-heading
                         :up (:up state')
                         :dist new-total-dist}]
        (-> state'
            (assoc :position new-pos')
            (assoc :stamped-shape new-shape)
            (assoc :loft-total-dist new-total-dist)
            (update :loft-orientations conj orientation)))

      ;; Other modes - just move
      (assoc state :position new-pos))))

(defn f
  "Move forward by distance. Use negative values to move backward.
   When attached to a mesh, moves the entire mesh.
   When attached to a face, extrudes the face along its normal."
  [state dist]
  (check-num dist "f")
  (if-let [attachment (:attached state)]
    (case (:type attachment)
      :pose (move-attached-mesh state dist)
      :face (if (:extrude-mode attachment)
              (extrude-attached-face state dist)
              (move-attached-face state dist))
      (move state (:heading state) dist))
    (move state (:heading state) dist)))

;; --- Rotation commands ---

(defn- store-pending-rotation
  "Store a pending rotation to be applied on next forward movement.
   Multiple rotations accumulate (compose)."
  [state rotation-type angle]
  (let [current-pending (:pending-rotation state)
        new-rotation {:type rotation-type :angle angle}]
    (if current-pending
      ;; Accumulate rotations
      (update state :pending-rotation conj new-rotation)
      ;; Start new pending rotation list
      (assoc state :pending-rotation [new-rotation]))))

(defn th
  "Turn horizontal (yaw) - rotate heading around up axis.
   Positive angle turns left.
   When attached to mesh, rotates the entire mesh around up axis.
   When attached to face, rotates the face around up axis (tilt sideways).
   In shape mode, stores pending rotation for fillet creation on next (f)."
  [state angle]
  (check-num angle "th")
  (cond
    ;; Attached to mesh pose: rotate the mesh
    (= :pose (get-in state [:attached :type]))
    (rotate-attached-mesh state (:up state) angle)

    ;; Attached to face: rotate the face around up axis (tilt sideways)
    (= :face (get-in state [:attached :type]))
    (rotate-attached-face state (:up state) angle)

    ;; Shape or loft mode: store pending rotation
    (#{:shape :loft} (:pen-mode state))
    (store-pending-rotation state :th angle)

    ;; Normal mode: apply rotation immediately
    :else
    (let [rad (deg->rad angle)
          new-heading (rotate-around-axis (:heading state) (:up state) rad)]
      (assoc state :heading new-heading))))

(defn tv
  "Turn vertical (pitch) - rotate heading and up around right axis.
   Positive angle pitches up.
   When attached to mesh, rotates the entire mesh around right axis.
   When attached to face, rotates the face around right axis (tilts the face).
   In shape mode, stores pending rotation for fillet creation on next (f)."
  [state angle]
  (check-num angle "tv")
  (cond
    ;; Attached to mesh pose: rotate the mesh
    (= :pose (get-in state [:attached :type]))
    (let [right (right-vector state)]
      (rotate-attached-mesh state right angle))

    ;; Attached to face: rotate the face around right axis
    (= :face (get-in state [:attached :type]))
    (let [right (right-vector state)]
      (rotate-attached-face state right angle))

    ;; Shape or loft mode: store pending rotation
    (#{:shape :loft} (:pen-mode state))
    (store-pending-rotation state :tv angle)

    ;; Normal mode: apply rotation immediately
    :else
    (let [rad (deg->rad angle)
          right (right-vector state)
          new-heading (rotate-around-axis (:heading state) right rad)
          new-up (rotate-around-axis (:up state) right rad)]
      (-> state
          (assoc :heading new-heading)
          (assoc :up new-up)))))

(defn tr
  "Turn roll - rotate up around heading axis.
   Positive angle rolls clockwise (when viewed from behind).
   When attached to mesh, rotates the entire mesh around heading axis.
   When attached to face, rotates the face around its normal (spins in place).
   In shape mode, stores pending rotation for fillet creation on next (f)."
  [state angle]
  (check-num angle "tr")
  (cond
    ;; Attached to mesh pose: rotate the mesh
    (= :pose (get-in state [:attached :type]))
    (rotate-attached-mesh state (:heading state) angle)

    ;; Attached to face: rotate the face around its normal (heading = normal)
    (= :face (get-in state [:attached :type]))
    (rotate-attached-face state (:heading state) angle)

    ;; Shape or loft mode: store pending rotation
    (#{:shape :loft} (:pen-mode state))
    (store-pending-rotation state :tr angle)

    ;; Normal mode: apply rotation immediately
    :else
    (let [rad (deg->rad angle)
          new-up (rotate-around-axis (:up state) (:heading state) rad)]
      (assoc state :up new-up))))

;; --- Arc commands ---

(defn arc-h
  "Draw a horizontal arc (turning around up axis while moving).

   Parameters:
   - radius: arc radius in units
   - angle: total turn angle in degrees (positive = left, negative = right)
   - :steps n (optional): override resolution

   The turtle moves along an arc, ending at radius * angle_rad distance
   with heading rotated by angle degrees.

   Example: (arc-h 10 90) draws a quarter circle turning left"
  [state radius angle & {:keys [steps]}]
  (if (or (zero? radius) (zero? angle))
    state
    (let [angle-rad (* (Math/abs angle) (/ Math/PI 180))
          arc-length (* radius angle-rad)
          actual-steps (or steps (calc-arc-steps state arc-length (Math/abs angle)))
          step-angle-deg (/ angle actual-steps)
          step-angle-rad (/ angle-rad actual-steps)
          ;; Chord length for each step: 2 * r * sin(θ/2)
          step-dist (* 2 radius (Math/sin (/ step-angle-rad 2)))
          ;; First step: rotate half, then move
          ;; Middle steps: rotate full, then move
          ;; This ensures proper extrusion integration (pending rotation consumed by f)
          half-angle (/ step-angle-deg 2)]
      (-> (reduce (fn [s _]
                    (-> s
                        (th step-angle-deg)
                        (f step-dist)))
                  ;; First: rotate half and move
                  (-> state
                      (th half-angle)
                      (f step-dist))
                  ;; Remaining steps (n-1)
                  (range (dec actual-steps)))
          ;; Final half rotation to complete the arc
          (th half-angle)))))

(defn arc-v
  "Draw a vertical arc (pitching around right axis while moving).

   Parameters:
   - radius: arc radius in units
   - angle: total pitch angle in degrees (positive = up, negative = down)
   - :steps n (optional): override resolution

   The turtle moves along an arc in the vertical plane.

   Example: (arc-v 10 45) draws an arc pitching upward"
  [state radius angle & {:keys [steps]}]
  (if (or (zero? radius) (zero? angle))
    state
    (let [angle-rad (* (Math/abs angle) (/ Math/PI 180))
          arc-length (* radius angle-rad)
          actual-steps (or steps (calc-arc-steps state arc-length (Math/abs angle)))
          step-angle-deg (/ angle actual-steps)
          step-angle-rad (/ angle-rad actual-steps)
          ;; Chord length for each step: 2 * r * sin(θ/2)
          step-dist (* 2 radius (Math/sin (/ step-angle-rad 2)))
          ;; First step: rotate half, then move
          ;; Middle steps: rotate full, then move
          half-angle (/ step-angle-deg 2)]
      (-> (reduce (fn [s _]
                    (-> s
                        (tv step-angle-deg)
                        (f step-dist)))
                  ;; First: rotate half and move
                  (-> state
                      (tv half-angle)
                      (f step-dist))
                  ;; Remaining steps (n-1)
                  (range (dec actual-steps)))
          ;; Final half rotation to complete the arc
          (tv half-angle)))))

;; --- Bezier commands ---

(defn- cubic-bezier-point
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

(defn- quadratic-bezier-point
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

(defn- cubic-bezier-tangent
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

(defn- quadratic-bezier-tangent
  "Calculate tangent (derivative) on quadratic Bezier curve at parameter t."
  [p0 p1 p2 t]
  (let [t2 (- 1 t)
        a (* 2 t2)
        b (* 2 t)]
    (normalize
     [(+ (* a (- (nth p1 0) (nth p0 0))) (* b (- (nth p2 0) (nth p1 0))))
      (+ (* a (- (nth p1 1) (nth p0 1))) (* b (- (nth p2 1) (nth p1 1))))
      (+ (* a (- (nth p1 2) (nth p0 2))) (* b (- (nth p2 2) (nth p1 2))))])))

(defn- auto-control-points
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

(defn- auto-control-points-with-target-heading
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

(defn- bezier-walk
  "Walk along a bezier curve, moving directly to each sample point.
   Uses chord directions for accurate positions. First step preserves
   the existing heading, last step uses exact end tangent."
  [state steps point-fn tangent-fn]
  (let [initial-up (:up state)
        last-i (dec steps)
        end-heading (tangent-fn 1)]
    (reduce
     (fn [s i]
       (let [t (/ (inc i) steps)
             new-pos (point-fn t)
             current-pos (:position s)
             move-dir (v- new-pos current-pos)
             dist (magnitude move-dir)]
         (if (> dist 0.001)
           (let [chord-heading (normalize move-dir)
                 ;; First step: keep existing heading (exact tangent at t=0)
                 ;; Last step: use exact end tangent
                 ;; Middle steps: use chord
                 heading-dir (cond (zero? i) (:heading s)
                                   (= i last-i) end-heading
                                   :else chord-heading)
                 right (cross heading-dir initial-up)
                 right-mag (magnitude right)]
             (if (< right-mag 0.001)
               (-> s
                   (assoc :heading heading-dir)
                   (f dist)
                   (assoc :position new-pos))
               (let [new-up (normalize (cross right heading-dir))]
                 (-> s
                     (assoc :heading heading-dir)
                     (assoc :up new-up)
                     (f dist)
                     (assoc :position new-pos)))))
           s)))
     state
     (range steps))))

(defn bezier-to
  "Draw a bezier curve to target position.

   Usage:
   (bezier-to state target)                    ; auto control points
   (bezier-to state target [cx cy cz])         ; quadratic with 1 control point
   (bezier-to state target [c1...] [c2...])    ; cubic with 2 control points
   (bezier-to state target :steps 24)          ; auto with more steps
   (bezier-to state target [c1] [c2] :steps 24); explicit with more steps

   With 0 control points: generates smooth curve starting along current heading
   With 1 control point: quadratic bezier
   With 2 control points: cubic bezier"
  [state target & args]
  (let [;; Separate vector args (control points) from keyword args
        {control-points true options false} (group-by vector? args)
        {:keys [steps]} (apply hash-map (flatten options))
        p0 (:position state)
        p3 (vec target)
        approx-length (magnitude (v- p3 p0))
        actual-steps (or steps (calc-bezier-steps state approx-length))
        n-controls (count control-points)]
    (if (< approx-length 0.001)
      state  ; target same as current position
      (cond
        ;; 2 control points: cubic bezier
        (= n-controls 2)
        (let [[c1 c2] control-points]
          (bezier-walk state actual-steps
                       #(cubic-bezier-point p0 c1 c2 p3 %)
                       #(cubic-bezier-tangent p0 c1 c2 p3 %)))

        ;; 1 control point: quadratic bezier
        (= n-controls 1)
        (let [c1 (first control-points)]
          (bezier-walk state actual-steps
                       #(quadratic-bezier-point p0 c1 p3 %)
                       #(quadratic-bezier-tangent p0 c1 p3 %)))

        ;; 0 control points: auto-generate cubic
        :else
        (let [[c1 c2] (auto-control-points p0 (:heading state) p3)]
          (bezier-walk state actual-steps
                       #(cubic-bezier-point p0 c1 c2 p3 %)
                       #(cubic-bezier-tangent p0 c1 c2 p3 %)))))))

(defn bezier-to-anchor
  "Draw a bezier curve to a named anchor position.
   When auto-generating control points (no explicit [c1] [c2] provided),
   the curve respects both the current heading AND the anchor's saved heading,
   creating a smooth connection that honors both directions.

   Usage:
   (bezier-to-anchor state :name)              ; auto control points (respects both headings)
   (bezier-to-anchor state :name [c1] [c2])    ; explicit control points
   (bezier-to-anchor state :name :steps 24)    ; auto with more steps
   (bezier-to-anchor state :name :tension 0.5) ; control curve width (0=tight, 1=wide)"
  [state anchor-name & args]
  (if-let [anchor (get-in state [:anchors anchor-name])]
    (let [{control-points true options false} (group-by vector? args)
          {:keys [steps tension]} (apply hash-map (flatten options))
          n-controls (count control-points)]
      (if (zero? n-controls)
        ;; Auto-generate control points using BOTH headings
        (let [p0 (:position state)
              p3 (:position anchor)
              approx-length (magnitude (v- p3 p0))
              actual-steps (or steps (calc-bezier-steps state approx-length))
              [c1 c2] (auto-control-points-with-target-heading
                        p0 (:heading state) p3 (:heading anchor) (or tension 0.33))]
          (if (< approx-length 0.001)
            state
            (bezier-walk state actual-steps
                         #(cubic-bezier-point p0 c1 c2 p3 %)
                         #(cubic-bezier-tangent p0 c1 c2 p3 %))))
        ;; Explicit control points - delegate to bezier-to
        (apply bezier-to state (:position anchor) args)))
    state))

;; --- Joint mode (for future corner styles) ---

(defn joint-mode
  "Set the joint mode for corners during extrusion.
   Currently supported: :flat (default)
   Future: :round, :tapered"
  [state mode]
  (assoc state :joint-mode mode))

;; --- Material settings ---

(defn set-color
  "Set the color for subsequent meshes.
   Accepts hex integer (0xff0000 for red) or RGB values (255 0 0)."
  ([state hex-color]
   (assoc-in state [:material :color] hex-color))
  ([state r g b]
   (let [hex (+ (bit-shift-left (int r) 16)
                (bit-shift-left (int g) 8)
                (int b))]
     (assoc-in state [:material :color] hex))))

(def ^:private default-material
  "Default material settings."
  {:color 0x00aaff
   :metalness 0.3
   :roughness 0.7
   :opacity 1.0
   :flat-shading true})

(defn set-material
  "Set material properties for subsequent meshes.
   Unspecified options reset to defaults.
   Options:
     :color - hex color (e.g., 0xff0000)
     :metalness - 0-1, how metallic the surface appears
     :roughness - 0-1, how rough/smooth the surface is
     :opacity - 0-1, transparency (1 = opaque)
     :flat-shading - true/false for flat vs smooth shading"
  [state & {:keys [color metalness roughness opacity flat-shading]}]
  (assoc state :material
         {:color (or color (:color default-material))
          :metalness (or metalness (:metalness default-material))
          :roughness (or roughness (:roughness default-material))
          :opacity (or opacity (:opacity default-material))
          :flat-shading (if (some? flat-shading) flat-shading (:flat-shading default-material))}))

(defn get-material
  "Get the current material settings."
  [state]
  (:material state))

(defn reset-material
  "Reset material to default values."
  [state]
  (assoc state :material default-material))

;; --- Pen commands ---

(defn pen-up
  "Stop drawing when moving. (Legacy - use (pen :off) instead)"
  [state]
  (assoc state :pen-mode :off))

(defn pen-down
  "Start drawing when moving. (Legacy - use (pen :on) instead)"
  [state]
  (assoc state :pen-mode :on))

(defn pen
  "Set pen mode for drawing.

   Modes:
   - (pen :off) - stop drawing
   - (pen :on) - draw lines (default)

   For shape extrusion, use (extrude shape movements...) instead."
  [state mode]
  (cond
    (= mode :off)
    (-> state
        (assoc :pen-mode :off)
        (assoc :stamped-shape nil))

    (= mode :on)
    (-> state
        (assoc :pen-mode :on)
        (assoc :stamped-shape nil))

    ;; Unknown mode - no change
    :else
    state))

(defn stamp
  "Internal: stamp a shape for extrusion. Used by extrude macro.
   Initializes sweep-rings with the first ring (the stamped shape).
   Also stores the 2D base shape for re-stamping after rotations.
   For closed extrusions, also saves initial orientation to compute
   the correct closing connection."
  [state shape]
  (if (shape? shape)
    (let [stamped (stamp-shape state shape)]
      (-> state
          (assoc :pen-mode :shape)
          (assoc :stamped-shape stamped)
          (assoc :sweep-base-shape shape)  ; Store 2D shape for re-stamping
          (assoc :sweep-rings [stamped])   ; First ring is the stamped shape
          (assoc :sweep-first-ring stamped) ; Save for caps and closing
          (assoc :sweep-initial-pos (:position state))
          (assoc :sweep-initial-heading (:heading state))
          (assoc :sweep-initial-up (:up state))))
    state))

(defn stamp-closed
  "Internal: stamp a shape for closed extrusion. Used by extrude-closed macro.
   Unlike stamp, does NOT add the first ring to sweep-rings because for closed
   extrusions, all segments will be shortened on both ends.
   The first ring will be added during the first forward movement."
  [state shape]
  (if (shape? shape)
    (let [stamped (stamp-shape state shape)]
      (-> state
          (assoc :pen-mode :shape)
          (assoc :stamped-shape stamped)
          (assoc :sweep-base-shape shape)
          (assoc :sweep-rings [])          ; Empty! First ring added on first (f)
          (assoc :sweep-first-ring stamped) ; Save for reference
          (assoc :sweep-initial-pos (:position state))
          (assoc :sweep-initial-heading (:heading state))
          (assoc :sweep-initial-up (:up state))
          (assoc :sweep-closed? true)))    ; Mark as closed extrusion
    state))

(defn finalize-sweep
  "Internal: finalize sweep by building final segment mesh with caps.
   Called at end of extrude macro.

   The meshes list may already contain segment and corner meshes from
   rotations. This adds the final segment and puts caps on the whole thing."
  [state]
  (let [rings (:sweep-rings state)
        existing-meshes (:meshes state)
        first-ring (:sweep-first-ring state)]
    (if (>= (count rings) 2)
      ;; Build final segment from remaining rings
      ;; Detect if extrusion went backward by comparing first and last ring centroids
      ;; with the sweep-initial-heading
      (let [ring-centroid-fn (fn [ring]
                               (let [n (count ring)]
                                 (v* (reduce v+ ring) (/ 1.0 n))))
            first-centroid (ring-centroid-fn (first rings))
            last-centroid (ring-centroid-fn (last rings))
            extrusion-dir (v- last-centroid first-centroid)
            initial-heading (or (:sweep-initial-heading state) (:heading state))
            ;; If dot product is negative, extrusion went backward
            backward? (neg? (dot extrusion-dir initial-heading))
            ;; Flip winding for backward extrusion to correct normals
            flip-winding? backward?
            final-segment (build-segment-mesh rings flip-winding?)
            ;; Determine the actual first and last rings for caps
            actual-first-ring (or first-ring (first rings))
            actual-last-ring (last rings)
            ;; Add caps to final segment if it's the only mesh,
            ;; otherwise we need to add caps to the combined structure
            all-meshes (if final-segment
                         (conj existing-meshes final-segment)
                         existing-meshes)
            ;; Create separate cap meshes using ear clipping for concave shapes
            n-verts (count actual-first-ring)
            ;; Compute normals from extrusion direction (centroid-based)
            ;; More robust than cross-product of ring vertices (which fails with duplicate points)
            ring-centroid (fn [ring]
                            (let [n (count ring)]
                              (v* (reduce v+ ring) (/ 1.0 n))))
            ;; For bottom cap: direction is from actual-first-ring toward first ring in rings
            ;; (or second ring if actual-first-ring IS the first ring)
            bottom-reference-ring (if (= actual-first-ring (first rings))
                                    (second rings)
                                    (first rings))
            bottom-normal (when (and (>= n-verts 3) bottom-reference-ring)
                            (v* (normalize (v- (ring-centroid bottom-reference-ring)
                                               (ring-centroid actual-first-ring)))
                                -1))  ; points backward (away from interior)
            ;; For top cap: direction is from second-to-last ring toward last ring
            second-to-last-ring (nth rings (- (count rings) 2))
            top-normal (when (>= n-verts 3)
                         (normalize (v- (ring-centroid actual-last-ring)
                                        (ring-centroid second-to-last-ring))))
            ;; triangulate-cap handles winding via project-to-2d, no flip needed
            bottom-cap-flip false
            top-cap-flip false
            ;; Bottom cap mesh
            bottom-cap-mesh (when (>= n-verts 3)
                              {:type :mesh
                               :primitive :cap
                               :vertices (vec actual-first-ring)
                               :faces (triangulate-cap actual-first-ring 0 bottom-normal bottom-cap-flip)})
            ;; Top cap mesh
            top-cap-mesh (when (>= n-verts 3)
                           {:type :mesh
                            :primitive :cap
                            :vertices (vec actual-last-ring)
                            :faces (triangulate-cap actual-last-ring 0 top-normal top-cap-flip)})]
        (-> state
            (assoc :meshes (cond-> all-meshes
                             bottom-cap-mesh (conj bottom-cap-mesh)
                             top-cap-mesh (conj top-cap-mesh)))
            (assoc :sweep-rings [])
            (assoc :stamped-shape nil)
            (dissoc :sweep-base-shape :sweep-first-ring :sweep-closed?
                    :sweep-closed-first-ring
                    :sweep-initial-pos :sweep-initial-heading :sweep-initial-up)))
      ;; Not enough rings - just clear state
      (-> state
          (assoc :sweep-rings [])
          (assoc :stamped-shape nil)
          (dissoc :sweep-base-shape :sweep-first-ring :sweep-closed?
                  :sweep-closed-first-ring
                  :sweep-initial-pos :sweep-initial-heading :sweep-initial-up)))))

(defn finalize-sweep-closed
  "Internal: finalize sweep as a closed torus-like mesh.
   Last ring connects back to first ring, no end caps.
   Called at end of extrude-closed macro.

   Forces (f 0) first to process any pending rotation, ensuring
   the final corner fillet is created before closing the loop.

   With stamp-closed, the first ring is already offset by radius
   and saved in :sweep-closed-first-ring.
   The closing corner connects:
   - FROM: last ring (after final corner was processed)
   - TO: the saved first ring (offset, created during first movement)"
  [state]
  ;; First, process any pending rotation by doing (f 0)
  ;; This creates the fillet for the last corner
  (let [state' (if (:pending-rotation state)
                 (f state 0)
                 state)
        rings (:sweep-rings state')
        existing-meshes (:meshes state')
        ;; Use the saved first ring (from first movement with offset)
        closed-first-ring (:sweep-closed-first-ring state')]
    (if (and (>= (count rings) 1) closed-first-ring)
      ;; Build final segment (if any) and closing corner
      (let [;; The last ring is at post-corner position
            last-ring (last rings)

            ;; If there are 2+ rings, we have a final segment to build
            ;; Otherwise, just the closing corner
            final-segment (when (>= (count rings) 2)
                            (build-segment-mesh rings))

            ;; Create closing corner: from last ring to the saved first ring
            closing-corner (build-corner-mesh last-ring closed-first-ring)

            ;; Combine all meshes
            all-meshes (cond-> existing-meshes
                         final-segment (conj final-segment)
                         closing-corner (conj closing-corner))]
        (-> state'
            (assoc :meshes all-meshes)
            (assoc :sweep-rings [])
            (assoc :stamped-shape nil)
            (dissoc :sweep-base-shape :sweep-first-ring :sweep-closed?
                    :sweep-closed-first-ring
                    :sweep-initial-pos :sweep-initial-heading :sweep-initial-up)))
      ;; Not enough rings or missing initial data - just clear state
      (-> state'
          (assoc :sweep-rings [])
          (assoc :stamped-shape nil)
          (dissoc :sweep-base-shape :sweep-first-ring :sweep-closed?
                  :sweep-closed-first-ring
                  :sweep-initial-pos :sweep-initial-heading :sweep-initial-up)))))

;; ============================================================
;; Extrude-closed with pre-processed path (clean implementation)
;; ============================================================

(defn- is-rotation?
  "Check if command is a rotation (or direct heading set)."
  [cmd]
  (#{:th :tv :tr :set-heading} cmd))

(defn- is-corner-rotation?
  "Check if command is a corner rotation that requires segment shortening.
   Excludes :set-heading which is used for smooth curves (bezier/arc)
   that don't need corner treatment."
  [cmd]
  (#{:th :tv :tr} cmd))

(defn- is-path?
  "Check if x is a path (internal version to avoid forward reference)."
  [x]
  (and (map? x) (= :path (:type x))))

(defn- total-rotation-angle-closed
  "Calculate the total absolute rotation angle from a sequence of rotation commands.
   For :set-heading commands, we return 0 (no shortening needed for bezier curves)."
  [rotations]
  (reduce + 0 (map (fn [r]
                     (if (= :set-heading (:cmd r))
                       0
                       (Math/abs (first (:args r)))))
                   rotations)))

(def ^:private ^:const corner-threshold-deg-closed
  "Minimum rotation angle (degrees) to be considered a corner requiring segment shortening.
   Smaller rotations (like arc steps) don't need shortening."
  10.0)

(defn- calc-shorten-for-angle
  "Calculate the segment shortening needed for a given rotation angle.

   For a corner rotation of θ degrees, the shortening is:
   shorten = radius * tan(θ/2)

   This ensures segments meet properly at any angle:
   - 90° → shorten = radius * tan(45°) = radius * 1 = radius
   - 45° → shorten = radius * tan(22.5°) ≈ radius * 0.414
   - 135° → shorten = radius * tan(67.5°) ≈ radius * 2.414

   For angles >= 180°, we cap at a reasonable maximum to avoid infinite shortening."
  [angle-deg radius]
  (if (< (Math/abs angle-deg) corner-threshold-deg-closed)
    0
    (let [half-angle-rad (* (Math/abs angle-deg) (/ Math/PI 360))  ; angle/2 in radians
          ;; Cap at 175° to avoid tan approaching infinity
          capped-half-angle (min half-angle-rad (* 87.5 (/ Math/PI 180)))
          tan-half (Math/tan capped-half-angle)]
      (* radius tan-half))))

(defn- analyze-closed-path
  "Analyze a path for closed extrusion.
   Returns a vector of segments with their adjustments.

   For a closed path, each forward segment may need shortening:
   - If preceded by significant rotation (>10°): shorten start by radius
   - If followed by significant rotation (>10°): shorten end by radius
   - Small rotations (like arc steps) don't trigger shortening
   - The LAST segment also accounts for the implicit closing rotation

   Returns: [{:cmd :f :dist 20 :shorten-start r :shorten-end r :rotations-after [...] :is-last bool}]"
  [commands radius]
  (let [cmds (vec commands)
        n (count cmds)
        ;; Find all forward commands and their indices
        forwards (vec (keep-indexed (fn [i c] (when (= :f (:cmd c)) [i c])) cmds))
        n-forwards (count forwards)
        ;; Calculate total explicit rotation in the path to determine closing angle
        ;; Sum all rotation angles from th/tv/tr commands
        total-explicit-rotation (reduce
                                 (fn [sum cmd]
                                   (if (is-corner-rotation? (:cmd cmd))
                                     (+ sum (Math/abs (first (:args cmd))))
                                     sum))
                                 0
                                 cmds)
        ;; For a closed square path, total should be 360°
        ;; The closing angle is what's needed to complete 360°
        closing-angle (let [remainder (mod total-explicit-rotation 360)]
                        (if (< remainder 1) 0 (- 360 remainder)))]
    (vec
     (map-indexed
      (fn [seg-idx [idx cmd]]
        (let [dist (first (:args cmd))
              is-first (= seg-idx 0)
              is-last (= seg-idx (dec n-forwards))
              ;; Collect rotations before this forward (back to previous forward)
              ;; For closed path, wrap around negative indices
              rotations-before (loop [i (dec idx)
                                      rots []
                                      steps 0]
                                 (if (> steps n)
                                   rots  ; Safety limit to prevent infinite loop
                                   (let [ci (mod (+ i n) n)  ; Proper modulo for negative numbers
                                         c (nth cmds ci)]
                                     (if (is-rotation? (:cmd c))
                                       (recur (dec i) (conj rots c) (inc steps))
                                       rots))))
              ;; Calculate total rotation angle before this forward
              ;; For the FIRST segment, add the closing angle (rotation coming from closure)
              explicit-angle-before (total-rotation-angle-closed rotations-before)
              angle-before (if is-first
                             (+ explicit-angle-before closing-angle)
                             explicit-angle-before)
              ;; Collect all rotations after this forward until next forward
              rotations-after (loop [i (inc idx)
                                     rots []]
                                (if (>= i n)
                                  ;; Wrap around for closed path
                                  (let [wrapped-i (mod i n)]
                                    (if (= wrapped-i idx)
                                      rots  ; Back to start
                                      (let [c (nth cmds wrapped-i)]
                                        (if (is-rotation? (:cmd c))
                                          (recur (inc i) (conj rots c))
                                          rots))))
                                  (let [c (nth cmds i)]
                                    (if (is-rotation? (:cmd c))
                                      (recur (inc i) (conj rots c))
                                      rots))))
              ;; Calculate total rotation angle after this forward
              ;; For the LAST segment, add the closing angle
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

(defn- apply-rotation-to-state
  "Apply a single rotation command to turtle state."
  [state rotation]
  (case (:cmd rotation)
    :th (let [angle (first (:args rotation))]
          (th (assoc state :pen-mode :off) angle))
    :tv (let [angle (first (:args rotation))]
          (tv (assoc state :pen-mode :off) angle))
    :tr (let [angle (first (:args rotation))]
          (tr (assoc state :pen-mode :off) angle))
    :set-heading (let [[heading up] (:args rotation)]
                   (-> state
                       (assoc :heading (normalize heading))
                       (assoc :up (normalize up))))
    state))

(defn extrude-closed-from-path
  "Extrude a shape along a closed path, creating a torus-like mesh.

   Pre-processes the path to calculate correct segment lengths.
   Creates a SINGLE manifold mesh with all rings connected.

   Returns the turtle state with the mesh added."
  [state shape path]
  (if-not (and (shape? shape) (is-path? path))
    state
    (let [;; Save creation pose before any modifications
          creation-pose {:position (:position state)
                         :heading (:heading state)
                         :up (:up state)}
          radius (shape-radius shape)
          commands (:commands path)
          segments (analyze-closed-path commands radius)
          n-segments (count segments)]
      (if (< n-segments 1)
        state
        ;; Apply any rotations/set-heading that appear BEFORE the first :f command
        ;; This is critical for bezier paths where the first direction differs from initial heading
        (let [initial-rotations (take-while #(not= :f (:cmd %)) commands)
              state-with-initial-heading (reduce apply-rotation-to-state state initial-rotations)
              ;; First pass: collect ALL rings in order
              rings-result
              (loop [i 0
                     s state-with-initial-heading
                     rings []
                     prev-had-corner true] ;; treat first segment as if preceded by corner (emit start-ring)
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

                        ;; Move forward by shorten-start ONLY on first iteration
                        s1 (if (and (zero? i) (pos? shorten-start))
                             (assoc s :position (v+ (:position s) (v* (:heading s) shorten-start)))
                             s)

                        start-pos (:position s1)

                        ;; Create start ring only if first segment or preceded by a corner.
                        ;; For smooth curves (bezier/arc), consecutive segments share the same
                        ;; junction position via :set-heading. Emitting both previous end-ring
                        ;; and this start-ring creates duplicate rings with degenerate faces.
                        emit-start-ring? (or (zero? i) prev-had-corner)
                        start-ring (when emit-start-ring? (stamp-shape s1 shape))

                        ;; Move forward by effective distance
                        s2 (assoc s1 :position (v+ start-pos (v* (:heading s1) effective-dist)))
                        end-pos (:position s2)

                        end-ring (stamp-shape s2 shape)

                        ;; Corner position
                        corner-pos (v+ end-pos (v* (:heading s2) shorten-end))
                        s3 (assoc s2 :position corner-pos)

                        ;; Apply rotations to get new heading
                        s4 (reduce apply-rotation-to-state s3 rotations)
                        old-heading (:heading s3)
                        new-heading (:heading s4)

                        ;; Calculate angle between headings
                        cos-a (dot old-heading new-heading)
                        heading-angle (when (< cos-a 0.9998) ;; > ~1 degree
                                        (Math/acos (min 1 (max -1 cos-a))))

                        ;; For corner rotations, generate junction rings based on joint-mode
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

                        ;; For smooth (non-corner) junctions with heading change,
                        ;; generate transition rings that pivot on the inner edge.
                        ;; This prevents ring overlap on the inner side of tight curves.
                        smooth-transition-rings
                        (if (and heading-angle
                                 (not has-corner-rotation)
                                 (seq rotations))
                          (let [n-smooth (max 1 (int (Math/ceil (/ heading-angle (/ Math/PI 12)))))]
                            (generate-round-corner-rings
                             end-ring end-pos old-heading new-heading
                             n-smooth radius))
                          [])

                        ;; Position for next segment's start: when smooth transition rings
                        ;; were generated, use centroid of last ring for continuity
                        corner-start-pos (if (seq smooth-transition-rings)
                                           (ring-centroid (last smooth-transition-rings))
                                           (v+ (:position s4) (v* (:heading s4) next-shorten-start)))

                        ;; Collect rings for this segment
                        new-rings (cond-> rings
                                    emit-start-ring? (conj start-ring)
                                    true (conj end-ring)
                                    (seq smooth-transition-rings) (into smooth-transition-rings)
                                    (seq corner-rings) (into corner-rings))

                        s5 (assoc s4 :position corner-start-pos)]
                    (recur (inc i) s5 new-rings (boolean has-corner-rotation)))))

              all-rings (:rings rings-result)
              final-state (:state rings-result)

              ;; Generate closing corner rings (from last segment back to first)
              ;; This handles the implicit rotation to close the path
              initial-heading (:heading creation-pose)
              final-heading (:heading final-state)
              cos-angle (dot final-heading initial-heading)
              closing-angle (Math/acos (min 1 (max -1 cos-angle)))
              needs-closing-corner (> closing-angle 0.1)  ;; More than ~6 degrees
              joint-mode (or (:joint-mode state) :flat)

              ;; Get last end-ring for corner generation
              ;; In ring order: [start0, end0, corner0?, start1, end1, corner1?, ...]
              ;; The last segment has start and end but no corner (since no rotations-after)
              ;; So last ring is end-ring of last segment
              last-end-ring (last all-rings)

              ;; The closing corner position: undo the offset that was added for next segment
              ;; final-state.position = corner-pos + heading * first-segment-shorten-start
              ;; So corner-pos = final-state.position - heading * first-segment-shorten-start
              first-shorten-start (:shorten-start (first segments))
              closing-corner-pos (v- (:position final-state)
                                     (v* final-heading first-shorten-start))

              ;; Calculate closing corner angle in degrees for round resolution
              closing-angle-deg (* closing-angle (/ 180 Math/PI))
              closing-round-steps (when (= joint-mode :round)
                                    (calc-round-steps state closing-angle-deg))

              closing-corner-rings (if needs-closing-corner
                                     (case joint-mode
                                       :flat []
                                       :round (generate-round-corner-rings
                                               last-end-ring closing-corner-pos
                                               final-heading initial-heading
                                               closing-round-steps radius)
                                       :tapered (generate-tapered-corner-rings
                                                 last-end-ring closing-corner-pos
                                                 final-heading initial-heading)
                                       [])
                                     [])

              ;; Add closing corner rings to all rings
              all-rings-with-closing (into all-rings closing-corner-rings)
              n-rings (count all-rings-with-closing)
              n-verts (count (first all-rings-with-closing))]

          (if (< n-rings 2)
            state
            ;; Build single manifold mesh from all rings
            (let [vertices (vec (apply concat all-rings-with-closing))
                  ;; Create faces connecting consecutive rings (closed torus)
                  side-faces (vec
                              (mapcat
                               (fn [ring-idx]
                                 (let [next-ring-idx (mod (inc ring-idx) n-rings)]
                                   (mapcat
                                    (fn [vert-idx]
                                      (let [next-vert (mod (inc vert-idx) n-verts)
                                            base (* ring-idx n-verts)
                                            next-base (* next-ring-idx n-verts)
                                            b0 (+ base vert-idx)
                                            b1 (+ base next-vert)
                                            t0 (+ next-base vert-idx)
                                            t1 (+ next-base next-vert)
                                            ;; Compare diagonal lengths
                                            db0t1 (v- (nth vertices b0) (nth vertices t1))
                                            db1t0 (v- (nth vertices b1) (nth vertices t0))]
                                        (if (<= (dot db0t1 db0t1) (dot db1t0 db1t0))
                                          [[b0 t0 t1] [b0 t1 b1]]
                                          [[b0 t0 b1] [t0 t1 b1]])))
                                    (range n-verts))))
                               (range n-rings)))
                  mesh (cond-> {:type :mesh
                               :primitive :torus
                               :vertices vertices
                               :faces side-faces
                               :creation-pose creation-pose}
                         (:material state) (assoc :material (:material state)))]
              (update final-state :meshes conj mesh))))))))

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
;; Path - recorded turtle movements
;; ============================================================

(defn ^:export path?
  "Check if x is a path."
  [x]
  (and (map? x) (= :path (:type x))))

;; ============================================================
;; Sweep between two shapes
;; ============================================================


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
            ;; For caps, we need: outer + all holes (flattened per ring)
            ;; Layout per ring: outer ++ hole1 ++ hole2 ++ ...
            hole-lengths (mapv count holes1)

            ;; Build combined vertex list
            ;; Ring1: outer1 ++ holes1[0] ++ holes1[1] ++ ...
            ;; Ring2: outer2 ++ holes2[0] ++ holes2[1] ++ ...
            ring1-vertices (vec (concat outer1 (apply concat holes1)))
            ring2-vertices (vec (concat outer2 (apply concat holes2)))
            vertices (vec (concat ring1-vertices ring2-vertices))

            ring1-len (count ring1-vertices)

            ;; Side faces for outer contour (indices 0 to n-outer-1 for ring1)
            outer-side-faces
            (vec (mapcat (fn [i]
                           (let [next-i (mod (inc i) n-outer)
                                 b0 i b1 next-i
                                 t0 (+ ring1-len i) t1 (+ ring1-len next-i)]
                             ;; CCW from outside
                             [[b0 t1 b1] [b0 t0 t1]]))
                         (range n-outer)))

            ;; Side faces for each hole (same winding as outer - holes have opposite point order)
            hole-side-faces
            (vec (apply concat
                        (map-indexed
                         (fn [hole-idx hole-len]
                           (let [;; Start index for this hole in ring1
                                 base1 (+ n-outer (reduce + (take hole-idx hole-lengths)))
                                 base2 (+ ring1-len base1)]
                             (mapcat (fn [i]
                                       (let [next-i (mod (inc i) hole-len)
                                             b0 (+ base1 i) b1 (+ base1 next-i)
                                             t0 (+ base2 i) t1 (+ base2 next-i)]
                                         ;; Same winding as outer (CCW from outside)
                                         [[b0 t1 b1] [b0 t0 t1]]))
                                     (range hole-len))))
                         hole-lengths)))

            all-side-faces (vec (concat outer-side-faces hole-side-faces))

            ;; Compute normals for caps
            ring-centroid (fn [ring] (v* (reduce v+ ring) (/ 1.0 (count ring))))
            extrusion-dir (normalize (v- (ring-centroid outer2) (ring-centroid outer1)))
            bottom-normal (v* extrusion-dir -1)
            top-normal extrusion-dir

            ;; Caps with holes
            ;; Bottom cap: vertices 0 to ring1-len-1
            bottom-cap (triangulate-cap-with-holes outer1 holes1 0 bottom-normal true)
            ;; Top cap: vertices ring1-len to end
            top-cap (triangulate-cap-with-holes outer2 holes2 ring1-len top-normal false)]

        {:type :mesh
         :primitive :sweep-two-holes
         :vertices vertices
         :faces (vec (concat all-side-faces bottom-cap top-cap))
         :creation-pose {:position [0 0 0]
                         :heading [1 0 0]
                         :up [0 0 1]}}))))

(defn make-path
  "Create a path from a vector of recorded commands.
   Each command is {:cmd :f/:b/:th/:tv/:tr :args [...]}"
  [commands]
  {:type :path
   :commands (vec commands)})

(defn quick-path
  "Create a path from compact numeric notation.
   Numbers alternate between forward distance and turn angle.
   Accepts: (quick-path 30 90 30), (quick-path [30 90 30]),
   or (def v [30 90 30]) (quick-path v)"
  ([x] (quick-path x nil))
  ([x y & more]
   (let [nums (if (and (nil? y) (sequential? x))
                x
                (cons x (cons y more)))]
     {:type :path
      :commands
      (->> nums
           (partition-all 2)
           (mapcat (fn [[a b]]
                     (if (some? b)
                       [{:cmd :f :args [a]}
                        {:cmd :th :args [b]}]
                       [{:cmd :f :args [a]}])))
           vec)})))

(defn make-recorder
  "Create a recorder turtle that captures commands.
   This is a regular turtle with an extra :recording vector."
  []
  (assoc (make-turtle) :recording []))

(defn- record-cmd
  "Record a command and execute it on the turtle."
  [state cmd args execute-fn]
  (-> state
      (update :recording conj {:cmd cmd :args (vec args)})
      (execute-fn)))

;; Recorder versions of movement commands
(defn rec-f [state dist]
  (record-cmd state :f [dist] #(f % dist)))

(defn rec-th [state angle]
  (record-cmd state :th [angle] #(th % angle)))

(defn rec-tv [state angle]
  (record-cmd state :tv [angle] #(tv % angle)))

(defn rec-tr [state angle]
  (record-cmd state :tr [angle] #(tr % angle)))

(defn rec-set-heading
  "Record a set-heading command that directly sets heading and up vectors."
  [state heading up]
  (record-cmd state :set-heading [heading up]
              #(-> %
                   (assoc :heading (normalize heading))
                   (assoc :up (normalize up)))))

(defn path-from-recorder
  "Extract a path from a recorder turtle."
  [recorder]
  (make-path (:recording recorder)))

(defn run-path
  "Execute a path's commands on a turtle state.
   Handles :mark commands by saving the pose as an anchor.
   Returns the updated state."
  [state path]
  (if (path? path)
    (reduce (fn [s {:keys [cmd args]}]
              (case cmd
                :f  (f s (first args))
                :th (th s (first args))
                :tv (tv s (first args))
                :tr (tr s (first args))
                :set-heading (-> s
                                 (assoc :heading (normalize (first args)))
                                 (assoc :up (normalize (second args))))
                :mark (assoc-in s [:anchors (first args)]
                                {:position (:position s)
                                 :heading (:heading s)
                                 :up (:up s)})
                s))
            state
            (:commands path))
    state))

(defn resolve-marks
  "Execute a path virtually from the given state (no geometry generated),
   collecting mark poses. Returns a map of {mark-name pose}."
  [state path]
  (if (path? path)
    (let [;; Run path on a bare turtle (no geometry) starting from given pose
          virtual-turtle (-> (make-turtle)
                             (assoc :position (:position state))
                             (assoc :heading (:heading state))
                             (assoc :up (:up state))
                             (assoc :pen-mode :off)
                             (assoc :anchors {}))
          result (run-path virtual-turtle path)]
      (:anchors result))
    {}))

(defn path-segments
  "Split a path's commands into segments, one per :f command.
   Each segment is a group of rotation commands followed by one :f.
   Returns a vector of {:rotations [...] :distance d}."
  [path]
  (loop [cmds (:commands path)
         current-rotations []
         segments []]
    (if (empty? cmds)
      segments
      (let [{:keys [cmd args] :as c} (first cmds)]
        (if (= :f cmd)
          (recur (rest cmds)
                 []
                 (conj segments {:rotations current-rotations
                                 :distance (first args)}))
          (recur (rest cmds)
                 (conj current-rotations c)
                 segments))))))

(defn subdivide-segment
  "Subdivide a segment into smaller sub-segments if it exceeds max-length.
   Returns a vector of segments. Only the first sub-segment keeps rotations."
  [segment max-length]
  (let [dist (:distance segment)]
    (if (or (<= dist max-length) (<= max-length 0))
      [segment]
      (let [n (int (Math/ceil (/ dist max-length)))
            sub-dist (/ dist n)]
        (into [{:rotations (:rotations segment) :distance sub-dist}]
              (repeat (dec n) {:rotations [] :distance sub-dist}))))))

(defn- segment->state
  "Run a segment's rotations and forward on a turtle state. Returns new state."
  [state segment]
  (let [rotated (reduce (fn [s {:keys [cmd args]}]
                          (case cmd
                            :th (th s (first args))
                            :tv (tv s (first args))
                            :tr (tr s (first args))
                            :set-heading (-> s
                                             (assoc :heading (normalize (first args)))
                                             (assoc :up (normalize (second args))))
                            s))
                        state
                        (:rotations segment))]
    (f rotated (:distance segment))))

(defn- catmull-rom-directions
  "Compute smoothing directions at each waypoint for cubic spline mode.
   At endpoints: turtle heading (unit vector).
   At interior points: normalized (P_{i+1} - P_{i-1}) (Catmull-Rom direction)."
  [waypoints]
  (let [n (count waypoints)]
    (mapv (fn [i]
            (if (or (zero? i) (= i (dec n)))
              ;; Endpoints: use turtle heading
              (:heading (nth waypoints i))
              ;; Interior: Catmull-Rom direction
              (let [prev (:position (nth waypoints (dec i)))
                    nxt  (:position (nth waypoints (inc i)))
                    diff (v- nxt prev)
                    len  (magnitude diff)]
                (if (> len 0.001)
                  (v* diff (/ 1.0 len))
                  (:heading (nth waypoints i))))))
          (range n))))

(defn bezier-as
  "Draw a bezier curve that smoothly approximates a turtle path.
   Produces one cubic bezier per segment in the path, with C1 continuity
   at junction points.

   Usage:
   (bezier-as state my-path)                          ; one bezier per segment
   (bezier-as state my-path :tension 0.5)             ; wider curves
   (bezier-as state my-path :max-segment-length 20)   ; subdivide long segments
   (bezier-as state my-path :cubic true)              ; Catmull-Rom spline
   (bezier-as state my-path :steps 32)                ; resolution per bezier

   :tension            - control point distance factor (default 0.33)
   :max-segment-length - subdivide segments longer than this
   :cubic              - use Catmull-Rom tangents for smoother global curves
   :steps              - bezier resolution (default from resolution settings)"
  [state p & args]
  (let [{:keys [tension steps max-segment-length cubic]} (apply hash-map args)
        factor (or tension 0.33)
        segments (path-segments p)
        ;; Optionally subdivide long segments
        segments (if max-segment-length
                   (vec (mapcat #(subdivide-segment % max-segment-length) segments))
                   segments)]
    (if (empty? segments)
      state
      ;; Collect waypoints: run each segment on a virtual turtle
      ;; to find positions and headings at each junction
      (let [waypoints (loop [s state
                             segs segments
                             wps [{:position (:position state)
                                   :heading  (:heading state)}]]
                        (if (empty? segs)
                          wps
                          (let [next-s (segment->state s (first segs))]
                            (recur next-s
                                   (rest segs)
                                   (conj wps {:position (:position next-s)
                                              :heading  (:heading next-s)})))))
            ;; Precompute directions for cubic (Catmull-Rom) mode
            directions (when cubic (catmull-rom-directions waypoints))]
        ;; Walk each bezier segment
        (reduce
         (fn [current-state i]
           (let [wp0 (nth waypoints i)
                 wp1 (nth waypoints (inc i))
                 p0 (:position wp0)
                 p3 (:position wp1)
                 seg-length (magnitude (v- p3 p0))]
             (if (< seg-length 0.001)
               ;; Degenerate segment: just apply rotations and advance
               (segment->state current-state (nth segments i))
               (let [actual-steps (or steps (calc-bezier-steps current-state seg-length))
                     [c1 c2] (if cubic
                               ;; Cubic: Catmull-Rom directions, same distance formula
                               (let [d0 (nth directions i)
                                     d1 (nth directions (inc i))]
                                 [(v+ p0 (v* d0 (* seg-length factor)))
                                  (v- p3 (v* d1 (* seg-length factor)))])
                               (auto-control-points-with-target-heading
                                 p0 (:heading wp0) p3 (:heading wp1) factor))]
                 (bezier-walk current-state actual-steps
                              #(cubic-bezier-point p0 c1 c2 p3 %)
                              #(cubic-bezier-tangent p0 c1 c2 p3 %))))))
         state
         (range (count segments)))))))

;; ============================================================
;; Path sampling for text-on-path
;; ============================================================

(defn ^:export path-total-length
  "Calculate total arc length of a path by summing positive :f distances."
  [path]
  (if (path? path)
    (->> (:commands path)
         (filter #(= :f (:cmd %)))
         (map #(first (:args %)))
         (filter pos?)
         (reduce + 0))
    0))

(defn ^:export sample-path-at-distance
  "Sample a path at a specific arc-length distance.
   Returns {:position [x y z] :heading [x y z] :up [x y z]} or nil if past end.

   Options:
   - :wrap? - if true, wrap distance around for closed paths (default false)
   - :start-pos - starting position (default [0 0 0])
   - :start-heading - starting heading (default [1 0 0])
   - :start-up - starting up vector (default [0 0 1])"
  [path distance & {:keys [wrap? start-pos start-heading start-up]
                    :or {wrap? false
                         start-pos [0 0 0]
                         start-heading [1 0 0]
                         start-up [0 0 1]}}]
  (when (path? path)
    (let [total (path-total-length path)
          dist (if (and wrap? (> distance total) (pos? total))
                 (mod distance total)
                 distance)]
      (when (and (>= dist 0) (<= dist total))
        (loop [cmds (:commands path)
               pos start-pos
               heading start-heading
               up start-up
               cumulative 0.0]
          (if-let [cmd (first cmds)]
            (case (:cmd cmd)
              :f (let [d (first (:args cmd))]
                   (if (and (pos? d) (>= (+ cumulative d) dist))
                     ;; Target is in this segment - interpolate position
                     (let [remaining (- dist cumulative)
                           final-pos (v+ pos (v* heading remaining))]
                       {:position final-pos :heading heading :up up})
                     ;; Continue to next segment
                     (recur (rest cmds)
                            (if (pos? d)
                              (v+ pos (v* heading d))
                              pos)
                            heading
                            up
                            (if (pos? d) (+ cumulative d) cumulative))))
              :th (let [angle (first (:args cmd))
                        rad (deg->rad angle)
                        new-heading (rotate-around-axis heading up rad)]
                    (recur (rest cmds) pos new-heading up cumulative))
              :tv (let [angle (first (:args cmd))
                        rad (deg->rad angle)
                        right (cross heading up)
                        new-heading (rotate-around-axis heading right rad)
                        new-up (rotate-around-axis up right rad)]
                    (recur (rest cmds) pos new-heading new-up cumulative))
              :tr (let [angle (first (:args cmd))
                        rad (deg->rad angle)
                        new-up (rotate-around-axis up heading rad)]
                    (recur (rest cmds) pos heading new-up cumulative))
              ;; Unknown command - skip
              (recur (rest cmds) pos heading up cumulative))
            ;; End of commands - return final position if we reached the target
            (when (>= cumulative dist)
              {:position pos :heading heading :up up})))))))

;; ============================================================
;; Extrude with pre-processed path (open path, unified mesh)
;; ============================================================

(defn- analyze-open-path
  "Analyze a path for open extrusion.
   Returns a vector of segments with their adjustments.

   For an open path:
   - First segment: no shorten-start (unless preceded by rotation in commands)
   - Last segment: no shorten-end
   - Middle segments: shorten both ends at corners

   Returns: [{:cmd :f :dist 20 :shorten-start r :shorten-end r :rotations-after [...]}]"
  [commands radius]
  (let [cmds (vec commands)
        n (count cmds)
        ;; Find all forward commands and their indices
        forwards (keep-indexed (fn [i c] (when (= :f (:cmd c)) [i c])) cmds)
        n-forwards (count forwards)]
    (vec
     (map-indexed
      (fn [fwd-idx [idx cmd]]
        (let [dist (first (:args cmd))
              is-first (zero? fwd-idx)
              is-last (= fwd-idx (dec n-forwards))
              ;; Collect rotations before this forward (back to previous forward or start)
              rotations-before (loop [i (dec idx)
                                      rots []]
                                 (if (< i 0)
                                   rots
                                   (let [c (nth cmds i)]
                                     (if (is-rotation? (:cmd c))
                                       (recur (dec i) (conj rots c))
                                       rots))))
              ;; Calculate angle before (only corner rotations count)
              angle-before (reduce + 0 (map (fn [r]
                                              (if (is-corner-rotation? (:cmd r))
                                                (Math/abs (first (:args r)))
                                                0))
                                            rotations-before))
              ;; Collect all rotations after this forward until next forward or end
              ;; (includes :set-heading for applying during extrude)
              rotations-after (loop [i (inc idx)
                                     rots []]
                                (if (>= i n)
                                  rots
                                  (let [c (nth cmds i)]
                                    (if (is-rotation? (:cmd c))
                                      (recur (inc i) (conj rots c))
                                      rots))))
              ;; Calculate angle after (only corner rotations count)
              angle-after (reduce + 0 (map (fn [r]
                                             (if (is-corner-rotation? (:cmd r))
                                               (Math/abs (first (:args r)))
                                               0))
                                           rotations-after))]
          {:cmd :f
           :dist dist
           ;; Open path: first segment doesn't shorten start
           :shorten-start (if is-first 0 (calc-shorten-for-angle angle-before radius))
           ;; Open path: last segment doesn't shorten end
           :shorten-end (if is-last 0 (calc-shorten-for-angle angle-after radius))
           :rotations-after rotations-after}))
      forwards))))

(defn- is-simple-forward-path?
  "Check if path is a simple straight extrusion (single forward command, no corners)."
  [path]
  (let [commands (:commands path)]
    (and (= 1 (count commands))
         (= :f (:cmd (first commands))))))

(defn- extrude-simple-with-holes
  "Extrude a shape with holes along a simple straight path.
   Only works for single-segment forward paths.
   Returns turtle state with mesh added."
  [state shape path]
  (let [creation-pose {:position (:position state)
                       :heading (:heading state)
                       :up (:up state)}
        dist (-> path :commands first :args first)
        ;; Stamp start ring with holes
        start-data (stamp-shape-with-holes state shape)
        ;; Move to end position
        end-pos (v+ (:position state) (v* (:heading state) dist))
        end-state (assoc state :position end-pos)
        ;; Stamp end ring with holes
        end-data (stamp-shape-with-holes end-state shape)
        ;; Build mesh with holes
        mesh (sweep-two-shapes-with-holes start-data end-data)]
    (if mesh
      (let [mesh-with-pose (cond-> (assoc mesh :creation-pose creation-pose)
                            (:material state) (assoc :material (:material state)))]
        (-> state
            (assoc :position end-pos)
            (update :meshes conj mesh-with-pose)))
      state)))

(defn extrude-from-path
  "Extrude a shape along an open path, creating a SINGLE unified mesh.

   Pre-processes the path to calculate correct segment lengths.
   Collects all rings in order and builds one mesh with side faces and end caps.

   Returns the turtle state with the mesh added."
  [state shape path]
  (if-not (and (shape? shape) (is-path? path))
    state
    ;; Check if shape has holes and path is simple
    (if (and (:holes shape) (is-simple-forward-path? path))
      ;; Use specialized hole-aware extrusion for simple paths
      (extrude-simple-with-holes state shape path)
      ;; Standard extrusion (no holes or complex path)
      (let [;; Save creation pose before any modifications
          creation-pose {:position (:position state)
                         :heading (:heading state)
                         :up (:up state)}
          radius (shape-radius shape)
          commands (:commands path)
          segments (analyze-open-path commands radius)
          n-segments (count segments)]
      (if (< n-segments 1)
        state
        ;; Apply any rotations/set-heading that appear BEFORE the first :f command
        ;; This is critical for bezier paths where the first direction differs from initial heading
        (let [initial-rotations (take-while #(not= :f (:cmd %)) commands)
              state-with-initial-heading (reduce apply-rotation-to-state state initial-rotations)
              ;; First pass: collect ALL rings in order
              rings-result
              (loop [i 0
                     s state-with-initial-heading
                     rings []
                     prev-had-corner true] ;; treat first segment as if preceded by corner (emit start-ring)
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
                        has-corner-rotation (some #(is-corner-rotation? (:cmd %)) rotations)

                        ;; For first segment, start at current position
                        ;; For subsequent segments, position is already correct from previous iteration
                        start-pos (:position s)
                        s1 (assoc s :position start-pos)

                        ;; Create start ring only if this is the first segment or preceded by a corner.
                        ;; For smooth curves (bezier/arc), consecutive segments share the same junction
                        ;; position via :set-heading (not a corner rotation). Emitting both the previous
                        ;; end-ring and this start-ring would create duplicate rings at the same position,
                        ;; producing degenerate zero-area faces that break boolean operations.
                        emit-start-ring? (or (zero? i) prev-had-corner)
                        start-ring (when emit-start-ring? (stamp-shape s1 shape))

                        ;; Position at end of segment (shortened by shorten-end)
                        end-pos (v+ start-pos (v* (:heading s1) effective-dist))
                        s2 (assoc s1 :position end-pos)
                        end-ring (stamp-shape s2 shape)

                        ;; Corner position
                        corner-pos (v+ end-pos (v* (:heading s2) shorten-end))
                        s3 (assoc s2 :position corner-pos)

                        ;; Apply rotations and get new heading
                        s4 (reduce apply-rotation-to-state s3 rotations)
                        old-heading (:heading s3)
                        new-heading (:heading s4)

                        ;; Calculate angle between headings
                        cos-a (dot old-heading new-heading)
                        heading-angle (when (< cos-a 0.9998) ;; > ~1 degree
                                        (Math/acos (min 1 (max -1 cos-a))))

                        ;; For corner rotations, generate junction rings based on joint-mode
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

                        ;; For smooth (non-corner) junctions with heading change,
                        ;; generate transition rings that pivot on the inner edge.
                        ;; This prevents ring overlap on the inner side of tight curves.
                        ;; Uses the same inner-pivot approach as round corner rings.
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

                        ;; Position for next segment: corner + radius along new heading
                        next-shorten-start (if (and (not is-last) has-corner-rotation)
                                             (:shorten-start (nth segments (inc i)))
                                             0)
                        ;; When smooth transition rings were generated, start next segment
                        ;; from the centroid of the last transition ring to maintain continuity
                        next-start-pos (if (seq smooth-transition-rings)
                                         (ring-centroid (last smooth-transition-rings))
                                         (v+ corner-pos (v* (:heading s4) next-shorten-start)))
                        s-next (assoc s4 :position next-start-pos)

                        ;; Collect rings for this segment
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
            ;; Build single unified mesh from all rings using ear clipping for caps
            (let [first-ring (first all-rings)
                  last-ring (last all-rings)

                  ;; Vertices: all rings (no centroids needed with ear clipping)
                  ;; Layout: [ring0-v0, ring0-v1, ..., ring0-vN, ring1-v0, ...]
                  vertices (vec (apply concat all-rings))

                  ;; Compute cap normals from extrusion direction (ring centroids)
                  ;; More robust than cross-product of ring vertices (which fails with duplicate points)
                  ring-centroid (fn [ring]
                                  (let [n (count ring)]
                                    (v* (reduce v+ ring) (/ 1.0 n))))
                  second-ring (nth all-rings 1)
                  second-to-last-ring (nth all-rings (- n-rings 2))

                  ;; Detect backward extrusion - only for simple straight paths
                  ;; For curved paths (arcs, multiple segments), don't flip
                  is-simple-straight? (= n-segments 1)
                  overall-extrusion-dir (v- (ring-centroid last-ring) (ring-centroid first-ring))
                  initial-heading (:heading state)
                  backward? (and is-simple-straight?
                                 (neg? (dot overall-extrusion-dir initial-heading)))

                  ;; Bottom cap: normal points opposite to extrusion direction
                  bottom-extrusion-dir (normalize (v- (ring-centroid second-ring)
                                                      (ring-centroid first-ring)))
                  bottom-normal (v* bottom-extrusion-dir -1)

                  ;; Top cap: normal points same as extrusion direction
                  top-extrusion-dir (normalize (v- (ring-centroid last-ring)
                                                   (ring-centroid second-to-last-ring)))
                  top-normal top-extrusion-dir

                  ;; triangulate-cap handles winding via project-to-2d, no flip needed
                  bottom-cap-flip false
                  top-cap-flip false
                  bottom-cap-faces (triangulate-cap first-ring 0 bottom-normal bottom-cap-flip)

                  ;; Side faces connecting consecutive rings
                  ;; Ring i vertices: i*n-verts to (i+1)*n-verts - 1
                  ;; Use shorter diagonal to split each quad, preventing inverted
                  ;; triangles at tight curve bends where quads become non-planar.
                  ;; Flip winding for backward extrusion to correct normals.
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
                                          ;; Compare diagonal lengths
                                          db0t1 (v- (nth vertices b0) (nth vertices t1))
                                          db1t0 (v- (nth vertices b1) (nth vertices t0))]
                                      (if (<= (dot db0t1 db0t1) (dot db1t0 db1t0))
                                        ;; Diagonal b0-t1 (shorter)
                                        (if backward?
                                          [[b0 t1 t0] [b0 b1 t1]]  ;; flipped
                                          [[b0 t0 t1] [b0 t1 b1]]) ;; normal
                                        ;; Diagonal b1-t0 (shorter)
                                        (if backward?
                                          [[b0 b1 t0] [t0 b1 t1]]  ;; flipped
                                          [[b0 t0 b1] [t0 t1 b1]]))))  ;; normal
                                  (range n-verts)))
                               (range (dec n-rings))))

                  ;; Top cap: flip based on backward?
                  last-ring-base (* (dec n-rings) n-verts)
                  top-cap-faces (triangulate-cap last-ring last-ring-base top-normal top-cap-flip)

                  all-faces (vec (concat bottom-cap-faces side-faces top-cap-faces))

                  mesh (cond-> {:type :mesh
                               :primitive :extrusion
                               :vertices vertices
                               :faces all-faces
                               :creation-pose creation-pose}
                         (:material state) (assoc :material (:material state)))]
              (update final-state :meshes conj mesh)))))))))


;; ============================================================
;; Revolve (lathe operation)
;; ============================================================

(defn ^:export revolve-shape
  "Revolve a 2D profile shape around the turtle's heading axis.
   Creates a solid of revolution (like a lathe operation).

   The profile is interpreted as:
   - 2D X = radial distance from axis (swept around up axis)
   - 2D Y = position along axis (in up direction)

   At θ=0 the stamp matches extrude: shape-X → right, shape-Y → up.
   Revolution axis = turtle's up vector. Use (tv) to change the axis.

   The axis of revolution passes through the turtle's current position.

   Parameters:
   - state: turtle state
   - shape: a 2D shape (profile to revolve)
   - angle: rotation angle in degrees (default 360 for full revolution)

   Uses the global resolution setting for number of segments.

   Examples:
   (revolve (shape (f 8) (th 90) (f 10) (th 90) (f 8)) 360)  ; solid cylinder
   (revolve (circle 5) 360)  ; torus (circle revolved around axis)
   (revolve cup-profile 360) ; cup/vase shape"
  ([state shape]
   (revolve-shape state shape 360))
  ([state shape angle]
   (if-not (shape? shape)
     state
     (let [;; Save creation pose
           creation-pose {:position (:position state)
                          :heading (:heading state)
                          :up (:up state)}
           ;; Get profile points
           profile-points (:points shape)
           n-profile (count profile-points)
           ;; Calculate shape winding using signed area
           ;; Positive = CCW, Negative = CW
           shape-signed-area (let [pts profile-points
                                   n (count pts)]
                               (/ (reduce + (for [i (range n)]
                                              (let [[x1 y1] (nth pts i)
                                                    [x2 y2] (nth pts (mod (inc i) n))]
                                                (- (* x1 y2) (* x2 y1)))))
                                  2))
           ;; Determine if we need to flip face winding
           ;; Flip when: (CCW shape AND positive angle) OR (CW shape AND negative angle)
           shape-is-ccw? (pos? shape-signed-area)
           flip-winding? (if shape-is-ccw? (pos? angle) (neg? angle))
           ;; Calculate number of segments based on resolution
           ;; Use same logic as arc: resolution based on angle
           steps (calc-arc-steps state (* 2 Math/PI) (Math/abs angle))
           ;; For full 360, we don't need the last ring (it overlaps with first)
           is-closed (>= (Math/abs angle) 360)
           n-rings (if is-closed steps (inc steps))
           angle-rad (* angle (/ Math/PI 180))
           angle-step (/ angle-rad steps)
           ;; Get turtle orientation
           pos (:position state)
           heading (:heading state)
           up (:up state)
           ;; Right vector = heading × up (initial radial direction at θ=0)
           right (normalize (cross heading up))
           ;; Transform profile point [px py] at angle θ to 3D:
           ;; pos + py * up + px * (cos(θ) * right + sin(θ) * heading)
           ;; At θ=0 this matches extrude's stamp: px*right + py*up
           ;; Revolution axis = up; radial sweeps from right toward heading
           ;; shape-X = radial distance (swept around up axis)
           ;; shape-Y = axial position (along up / revolution axis)
           transform-point (fn [[px py] theta]
                             (let [cos-t (Math/cos theta)
                                   sin-t (Math/sin theta)
                                   ;; Radial direction at this angle (sweeps in right-heading plane)
                                   radial-x (+ (* cos-t (nth right 0)) (* sin-t (nth heading 0)))
                                   radial-y (+ (* cos-t (nth right 1)) (* sin-t (nth heading 1)))
                                   radial-z (+ (* cos-t (nth right 2)) (* sin-t (nth heading 2)))]
                               [(+ (nth pos 0) (* py (nth up 0)) (* px radial-x))
                                (+ (nth pos 1) (* py (nth up 1)) (* px radial-y))
                                (+ (nth pos 2) (* py (nth up 2)) (* px radial-z))]))
           ;; Generate all rings
           rings (vec (for [i (range n-rings)]
                        (let [theta (* i angle-step)]
                          (vec (for [pt profile-points]
                                 (transform-point pt theta))))))
           ;; Flatten vertices
           vertices (vec (apply concat rings))
           ;; Side faces connecting consecutive rings
           ;; For closed revolve, connect last ring to first
           side-faces
           (vec
            (mapcat
             (fn [ring-idx]
               (let [next-ring-idx (if (and is-closed (= ring-idx (dec n-rings)))
                                     0
                                     (inc ring-idx))]
                 (when (< next-ring-idx n-rings)
                   (mapcat
                    (fn [vert-idx]
                      (let [next-vert (mod (inc vert-idx) n-profile)
                            base (* ring-idx n-profile)
                            next-base (* next-ring-idx n-profile)
                            b0 (+ base vert-idx)
                            b1 (+ base next-vert)
                            t0 (+ next-base vert-idx)
                            t1 (+ next-base next-vert)]
                        ;; CCW winding for outward-facing normals
                        ;; Flip based on shape winding and angle sign
                        (if flip-winding?
                          [[b0 t0 t1] [b0 t1 b1]]
                          [[b0 t1 t0] [b0 b1 t1]])))
                    (range n-profile)))))
             (range (if is-closed n-rings (dec n-rings)))))
           ;; Caps for open revolve (angle < 360)
           cap-faces
           (when-not is-closed
             (let [first-ring (first rings)
                   last-ring (last rings)
                   last-ring-base (* (dec n-rings) n-profile)
                   ;; For triangulation, we need the normal to the ring PLANE
                   ;; (not the cap face normal). Ring plane is spanned by up and right,
                   ;; so plane normal = cross(up, right) = -heading (or heading depending on order)
                   ;; At theta=0, ring plane normal is along heading direction
                   start-proj-normal heading
                   ;; At theta=end, ring plane normal is still along heading
                   ;; (revolution around up doesn't change the ring plane normal direction)
                   end-proj-normal heading
                   ;; Cap flip determines which way the triangles face
                   ;; Try inverted flip logic
                   start-cap-flip (not flip-winding?)
                   end-cap-flip flip-winding?
                   ;; Triangulate caps using ring plane normal for projection
                   start-cap (triangulate-cap first-ring 0 start-proj-normal start-cap-flip)
                   end-cap (triangulate-cap last-ring last-ring-base end-proj-normal end-cap-flip)]
               (vec (concat start-cap end-cap))))
           all-faces (if cap-faces
                       (vec (concat side-faces cap-faces))
                       side-faces)
           mesh (cond-> {:type :mesh
                         :primitive :revolve
                         :vertices vertices
                         :faces all-faces
                         :creation-pose creation-pose}
                  (:material state) (assoc :material (:material state)))]
       (update state :meshes conj mesh)))))


;; ============================================================
;; Loft from path (unified extrusion with transform)
;; ============================================================

(defn- analyze-loft-path
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
           total-visible-dist (reduce + 0 (map :dist segments))]
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

                             ;; R_p/R_n based on visible taper distance
                             {:keys [r-p r-n]}
                             (if (and has-corner (> turn-angle 0.01))
                               (try
                                 (calculate-loft-corner-shortening
                                  initial-radius total-visible-dist dist-at-corner turn-angle)
                                 (catch :default _
                                   {:r-p 0 :r-n 0}))
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
             (let [result
                   (loop [seg-idx 0
                          s state
                          taper-acc 0         ;; effective distance travelled so far (for t)
                          prev-rn 0           ;; carry start offset from previous corner
                          acc-rings []        ;; accumulated rings for current smooth section
                          finished-meshes []] ;; completed meshes (split at corners)
                     (if (>= seg-idx n-segments)
                       ;; Flush any remaining accumulated rings as a final mesh
                       {:meshes (if (>= (count acc-rings) 2)
                                  (conj finished-meshes
                                        (build-sweep-mesh (vec acc-rings) false creation-pose))
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
                             ;; Leave room for next start, but only half of r_n to reduce gaps
                             effective-seg-dist (max min-step
                                                     (- remaining-to-corner
                                                        (if has-corner (+ r-p (* 0.5 r-n)) 0)))

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
                           (let [;; Build mesh from accumulated rings
                                 section-mesh (when (>= (count new-acc-rings) 2)
                                                (build-sweep-mesh (vec new-acc-rings) false creation-pose))

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
                                 mid-rings (let [generated (generate-tapered-corner-rings
                                                            end-ring corner-base
                                                            old-heading new-heading)]
                                             (when (seq generated) generated))
                                 fallback-mid (when (and end-ring next-start-ring (nil? mid-rings))
                                                [(mapv (fn [p1 p2] (v+ p1 (v* (v- p2 p1) 0.5)))
                                                       end-ring next-start-ring)])
                                 c-rings (cond
                                           mid-rings (concat [end-ring] mid-rings [next-start-ring])
                                           fallback-mid (concat [end-ring] fallback-mid [next-start-ring])
                                           :else nil)
                                 corner-mesh (when c-rings
                                               (assoc (build-sweep-mesh (vec c-rings)
                                                                        false creation-pose)
                                                      :creation-pose creation-pose))]

                             (recur (inc seg-idx)
                                    s-next
                                    new-taper-acc
                                    r-n
                                    []  ;; reset accumulated rings for next section
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
                                              (mapv #(assoc % :material (:material state)) segment-meshes)
                                              segment-meshes)]
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

(defn- compute-segment-angles
  "Pre-compute the angle change at each segment by simulating the turtle walk.
   Returns a vector of angles (in radians) for each segment."
  [segments initial-state]
  (loop [seg-idx 0
         s initial-state
         angles []]
    (if (>= seg-idx (count segments))
      angles
      (let [seg (nth segments seg-idx)
            seg-dist (:dist seg)
            rotations (:rotations-after seg)
            corner-pos (v+ (:position s) (v* (:heading s) seg-dist))
            s-at-corner (assoc s :position corner-pos)
            old-heading (:heading s-at-corner)
            s-rotated (reduce apply-rotation-to-state s-at-corner rotations)
            new-heading (:heading s-rotated)
            angle (if (seq rotations)
                    (Math/acos (max -1 (min 1 (dot old-heading new-heading))))
                    0)]
        (recur (inc seg-idx)
               s-rotated
               (conj angles angle))))))

(defn- compute-weighted-seg-steps
  "Distribute steps among segments weighted by angle (sharper curves get more steps).
   weight-factor controls how much angle affects distribution (1.0 = linear, 2.0 = quadratic)."
  [segments angles total-steps weight-factor]
  (let [n (count segments)
        total-dist (reduce + 0 (map :dist segments))
        ;; Combine distance and angle into weights
        weights (mapv (fn [seg angle]
                        (let [dist-weight (if (pos? total-dist)
                                            (/ (:dist seg) total-dist)
                                            (/ 1.0 n))
                              angle-weight (Math/pow (+ 1.0 (/ angle Math/PI)) weight-factor)]
                          (* dist-weight angle-weight)))
                      segments angles)
        total-weight (reduce + 0 weights)
        ;; Distribute steps proportionally
        raw-steps (mapv (fn [w]
                          (if (pos? total-weight)
                            (* total-steps (/ w total-weight))
                            (/ total-steps n)))
                        weights)]
    ;; Ensure at least 1 step per segment, convert to integers
    (mapv #(max 1 (Math/round %)) raw-steps)))

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
           commands (:commands bezier-path)
           segments (analyze-loft-path commands)
           n-segments (count segments)
           total-dist (reduce + 0 (map :dist segments))
           initial-radius (shape-radius shape)
           angles (compute-segment-angles segments state)
           weighted-steps (compute-weighted-seg-steps segments angles steps 2.0)]

       (if (or (< n-segments 1) (<= total-dist 0))
         state
         (let [result
               (loop [seg-idx 0
                      s state
                      dist-acc 0
                      acc-rings []
                      tmp-meshes []
                      last-ring nil
                      last-heading nil]

                 (if (>= seg-idx n-segments)
                   {:meshes (if (>= (count acc-rings) 2)
                              (conj tmp-meshes (build-sweep-mesh acc-rings false creation-pose))
                              tmp-meshes)
                    :state s}

                   (let [seg (nth segments seg-idx)
                         seg-dist (:dist seg)
                         rotations (:rotations-after seg)
                         t-start (if (pos? total-dist) (/ dist-acc total-dist) 0)
                         t-end (if (pos? total-dist) (/ (+ dist-acc seg-dist) total-dist) 1)
                         seg-steps (nth weighted-steps seg-idx 1)

                         seg-result
                         (loop [step-i 0
                                s-inner s
                                inner-acc-rings acc-rings
                                inner-tmp-meshes tmp-meshes
                                inner-last-ring last-ring
                                inner-last-heading last-heading]

                           (if (> step-i seg-steps)
                             {:acc-rings inner-acc-rings
                              :tmp-meshes inner-tmp-meshes
                              :state s-inner
                              :last-ring inner-last-ring
                              :last-heading inner-last-heading}

                             (let [local-t (/ step-i seg-steps)
                                   global-t (+ t-start (* local-t (- t-end t-start)))
                                   pos (v+ (:position s) (v* (:heading s) (* local-t seg-dist)))
                                   current-shape (transform-fn shape global-t)
                                   temp-state (assoc s :position pos)
                                   current-ring (stamp-shape temp-state current-shape)
                                   current-heading (:heading s)

                                   local-heading
                                   (if inner-last-ring
                                     (let [c1 (ring-centroid inner-last-ring)
                                           c2 (ring-centroid current-ring)
                                           d (v- c2 c1)
                                           len (Math/sqrt (dot d d))]
                                       (if (> len 0.0001)
                                         (v* d (/ 1.0 len))
                                         current-heading))
                                     current-heading)]

                               (if (nil? inner-last-ring)
                                 (recur (inc step-i) s-inner
                                        (conj inner-acc-rings current-ring)
                                        inner-tmp-meshes current-ring local-heading)

                                 (if (rings-intersect? inner-last-ring current-ring threshold initial-radius)
                                   ;; INTERSECTION: create hull bridge directly from ring vertices
                                   (let [hull-mesh (manifold/hull-from-points (vec (concat inner-last-ring current-ring)))
                                         valid-hull? (and hull-mesh (pos? (count (:vertices hull-mesh))))
                                         section-mesh (when (>= (count inner-acc-rings) 2)
                                                        (build-sweep-mesh inner-acc-rings false creation-pose))
                                         new-tmp-meshes (cond-> inner-tmp-meshes
                                                          section-mesh (conj section-mesh)
                                                          valid-hull? (conj hull-mesh))]
                                     (recur (inc step-i) s-inner [current-ring]
                                            new-tmp-meshes current-ring local-heading))

                                   ;; No intersection - accumulate ring
                                   (recur (inc step-i) s-inner
                                          (conj inner-acc-rings current-ring)
                                          inner-tmp-meshes current-ring local-heading))))))

                         corner-pos (v+ (:position s) (v* (:heading s) seg-dist))
                         s-at-corner (assoc s :position corner-pos)
                         s-rotated (reduce apply-rotation-to-state s-at-corner rotations)]

                     (recur (inc seg-idx) s-rotated (+ dist-acc seg-dist)
                            (:acc-rings seg-result) (:tmp-meshes seg-result)
                            (:last-ring seg-result) (:last-heading seg-result)))))

               final-meshes (:meshes result)
               final-state (:state result)]

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

;; ============================================================
;; Anchors and Navigation
;; ============================================================

;; mark removed — marks now exist only inside path recordings.
;; Use (mark :name) inside (path ...) to embed a mark,
;; then (with-path p ...) to resolve marks as anchors.

(defn goto
  "Move to a named anchor position and adopt its heading/up.
   Draws a line if pen-mode is :on.
   Returns state unchanged if anchor doesn't exist."
  [state name]
  (if-let [anchor (get-in state [:anchors name])]
    (let [from-pos (:position state)
          to-pos (:position anchor)
          mode (:pen-mode state)
          ;; Draw line if pen is on
          state' (if (= mode :on)
                   (update state :geometry conj {:type :line
                                                 :from from-pos
                                                 :to to-pos
                                                 :color (get-in state [:material :color])})
                   state)]
      (-> state'
          (assoc :position to-pos)
          (assoc :heading (:heading anchor))
          (assoc :up (:up anchor))))
    state))

(defn look-at
  "Rotate heading to point toward a named anchor.
   Does not move the turtle.
   Adjusts up to maintain orthogonality.
   Returns state unchanged if anchor doesn't exist or if at same position."
  [state name]
  (if-let [anchor (get-in state [:anchors name])]
    (let [from-pos (:position state)
          to-pos (:position anchor)
          dir (v- to-pos from-pos)
          dist (magnitude dir)]
      (if (< dist 0.0001)
        ;; Already at target position, can't determine direction
        state
        (let [new-heading (normalize dir)
              ;; Compute new up: try to keep it as close to original up as possible
              ;; but orthogonal to new heading
              old-up (:up state)
              ;; Project old-up onto plane perpendicular to new-heading
              ;; up' = up - (up · heading) * heading
              dot-prod (dot old-up new-heading)
              projected-up (v- old-up (v* new-heading dot-prod))
              proj-mag (magnitude projected-up)
              new-up (if (< proj-mag 0.0001)
                       ;; old-up is parallel to new-heading, pick arbitrary perpendicular
                       (let [arbitrary (if (< (Math/abs (first new-heading)) 0.9)
                                         [1 0 0]
                                         [0 1 0])]
                         (normalize (cross new-heading arbitrary)))
                       (normalize projected-up))]
          (-> state
              (assoc :heading new-heading)
              (assoc :up new-up)))))
    state))

(defn path-to
  "Create a path from current position to a named anchor.
   Returns a path with a single (f distance) command.

   Note: In implicit mode (REPL), this also orients the turtle toward
   the anchor first (via look-at), so extrusions go in the correct direction.

   Useful for: (extrude (circle 5) (path-to :target))
   Returns nil if anchor doesn't exist."
  [state name]
  (when-let [anchor (get-in state [:anchors name])]
    (let [from-pos (:position state)
          to-pos (:position anchor)
          dir (v- to-pos from-pos)
          dist (magnitude dir)]
      (make-path [{:cmd :f :args [dist]}]))))

;; ============================================================
;; Attachment system - attach to meshes and faces
;; ============================================================

(defn- compute-triangle-normal
  "Compute normal vector for a triangle given three vertices."
  [v0 v1 v2]
  (let [edge1 (v- v1 v0)
        edge2 (v- v2 v0)]
    (normalize (cross edge1 edge2))))

(defn- compute-face-info-internal
  "Compute normal, heading, and center for a face group.
   Returns {:normal :heading :center :vertices :triangles}."
  [vertices face-triangles]
  (when (seq face-triangles)
    (let [all-indices (distinct (mapcat identity face-triangles))
          face-verts (mapv #(nth vertices % [0 0 0]) all-indices)
          center (v* (reduce v+ [0 0 0] face-verts) (/ 1 (count face-verts)))
          [i0 i1 i2] (first face-triangles)
          v0 (nth vertices i0 [0 0 0])
          v1 (nth vertices i1 [0 0 0])
          v2 (nth vertices i2 [0 0 0])
          normal (compute-triangle-normal v0 v1 v2)
          edge1 (v- v1 v0)
          heading (normalize edge1)]
      {:normal normal
       :heading heading
       :center center
       :vertices (vec all-indices)
       :triangles face-triangles})))

;; --- Clone mesh helper ---

(defn- clone-mesh
  "Create a deep copy of a mesh with fresh collections.
   Removes :registry-id so it can be registered separately."
  [mesh]
  (-> mesh
      (update :vertices vec)
      (update :faces vec)
      (update :face-groups #(when % (into {} (map (fn [[k v]] [k (vec v)]) %))))
      (dissoc :registry-id)))

(defn attached?
  "Check if turtle is currently attached to a mesh or face."
  [state]
  (some? (:attached state)))

(defn attach
  "Attach to a mesh's creation pose.
   With :clone true, creates a copy of the mesh first (original unchanged).
   Pushes current state, moves turtle to the mesh's creation position,
   adopts its heading and up vectors.
   Returns state unchanged if mesh has no creation-pose."
  [state mesh & {:keys [clone]}]
  (let [target-mesh (if clone (clone-mesh mesh) mesh)
        state (if clone
                (update state :meshes conj target-mesh)
                state)]
    (if-let [pose (:creation-pose target-mesh)]
      (-> state
          (push-state)
          (assoc :position (:position pose))
          (assoc :heading (:heading pose))
          (assoc :up (:up pose))
          (assoc :attached {:type :pose
                            :mesh target-mesh
                            :original-pose pose}))
      state)))

(defn attach-face
  "Attach to a specific face of a mesh.
   With :clone true, enables extrusion mode (f creates side faces).
   Without :clone, face movement mode (f moves vertices directly).
   Pushes current state, moves turtle to face center,
   sets heading to face normal (outward), up perpendicular.
   Returns state unchanged if face not found."
  [state mesh face-id & {:keys [clone]}]
  (if-let [face-groups (:face-groups mesh)]
    (if-let [triangles (get face-groups face-id)]
      (let [info (compute-face-info-internal (:vertices mesh) triangles)
            normal (:normal info)
            center (:center info)
            ;; Derive up vector perpendicular to normal
            face-heading (:heading info)
            ;; up = normal × face-heading (perpendicular to both)
            up (normalize (cross normal face-heading))]
        (-> state
            (push-state)
            (assoc :position center)
            (assoc :heading normal)
            (assoc :up up)
            (assoc :attached {:type :face
                              :mesh mesh
                              :face-id face-id
                              :face-info info
                              :extrude-mode clone})))  ; flag: if true, f() extrudes
      state)
    state))

(defn ^:export attach-face-extrude
  "Attach to a face with extrusion mode enabled.
   Immediately creates cloned vertices at distance 0 (coincident with original).
   This way scale/inset can operate on the new vertices, and f moves them.
   After cloning, extrude-mode is turned off so f just moves vertices.
   Sets clone-context so inset knows to create new vertices and re-enable extrude.
   SCI-compatible (no keyword args)."
  [state mesh face-id]
  (-> state
      (attach-face mesh face-id :clone true)
      ;; Immediately extrude 0 to create cloned vertices and side faces
      ;; The cloned vertices start coincident with the original face
      (extrude-attached-face 0)
      ;; Turn off extrude-mode: vertices are now cloned, future f just moves them
      (assoc-in [:attached :extrude-mode] false)
      ;; Mark clone context so inset knows to create new vertices
      (assoc-in [:attached :clone-context] true)))

(defn ^:export attach-move
  "Attach to a mesh's creation pose (modifies original mesh).
   SCI-compatible wrapper for (attach state mesh) without :clone."
  [state mesh]
  (attach state mesh))

(defn detach
  "Detach from current attachment and restore previous position.
   Equivalent to pop-state but also clears :attached.
   Returns state unchanged if not attached."
  [state]
  (if (:attached state)
    (-> state
        (pop-state)
        (assoc :attached nil))
    state))
