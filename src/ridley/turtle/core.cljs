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
   - (extrude shape movements...) - stamp shape and extrude via movements")

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
   :resolution {:mode :n :value 16}})  ; curve resolution (like OpenSCAD $fn)

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
                                     ;; CCW winding from outside (inverted for correct volume)
                                     [[b0 t1 b1] [b0 t0 t1]]))
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
                                   ;; CCW winding from outside (inverted for correct volume)
                                   [[b0 t1 b1] [b0 t0 t1]]))
                               (range n-verts)))
                            (range (dec n-rings))))
               ;; Bottom cap: facing -heading direction (CCW from below)
               bottom-cap (vec (for [i (range 1 (dec n-verts))]
                                 [0 i (inc i)]))
               last-base (* (dec n-rings) n-verts)
               ;; Top cap: facing +heading direction (CCW from above)
               top-cap (vec (for [i (range 1 (dec n-verts))]
                              [last-base (+ last-base i 1) (+ last-base i)]))]
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

(defn- stamp-shape
  "Stamp a shape onto the plane perpendicular to turtle's heading.
   Returns 3D vertices of the stamped shape."
  [state shape]
  (let [points (:points shape)
        centered? (:centered? shape)
        pos (:position state)
        heading (:heading state)
        up (:up state)
        ;; The plane is perpendicular to heading
        ;; x-axis of plane = right vector (heading × up)
        ;; y-axis of plane = up vector
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
        offset (if centered?
                 [0 0]
                 (let [[fx fy] (first points)]
                   [(- fx) (- fy)]))]
    ;; Transform each 2D point to 3D
    (mapv (fn [[px py]]
            (let [;; Apply offset for non-centered shapes
                  px' (+ px (first offset))
                  py' (+ py (second offset))
                  ;; Transform to 3D: origin + px*plane-x + py*plane-y
                  [ox oy oz] pos
                  [xx xy xz] plane-x
                  [yx yy yz] plane-y]
              [(+ ox (* px' xx) (* py' yx))
               (+ oy (* px' xy) (* py' yy))
               (+ oz (* px' xz) (* py' yz))]))
          points)))

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
      :n (max 2 (int (/ value 4)))  ; use fraction of :n value
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
   Returns nil if not enough rings."
  [rings]
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
                                [[b0 t1 b1] [b0 t0 t1]]))
                            (range n-verts)))
                         (range (dec n-rings))))]
        {:type :mesh
         :primitive :segment
         :vertices vertices
         :faces side-faces}))))

(defn- build-corner-mesh
  "Build a corner mesh connecting two rings (no caps).
   ring1 and ring2 must have the same number of vertices."
  [ring1 ring2]
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
                             [[b0 t1 b1] [b0 t0 t1]]))
                         (range n-verts)))]
        {:type :mesh
         :primitive :corner
         :vertices vertices
         :faces side-faces}))))

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

(defn- inset-attached-face
  "Inset the attached face, creating a smaller inner face.
   Returns updated state with attachment moved to inner face."
  [state dist]
  (let [attachment (:attached state)
        mesh (:mesh attachment)
        face-id (:face-id attachment)
        face-info (:face-info attachment)

        ;; Build inset mesh
        new-mesh (build-face-inset mesh face-id face-info dist)

        ;; The face center stays the same (we're not moving normal direction)
        ;; But we need to update face-info with new triangles and vertices
        new-triangles (get-in new-mesh [:face-groups face-id])
        new-face-info (-> face-info
                          (assoc :triangles new-triangles)
                          (assoc :vertices (vec (distinct (mapcat identity new-triangles)))))]
    (-> state
        (replace-mesh-in-state mesh new-mesh)
        (assoc-in [:attached :mesh] new-mesh)
        (assoc-in [:attached :face-info] new-face-info))))

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
                                  :to new-pos}))

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
      (let [current-shape (:stamped-shape state)
            offset (v* direction dist)
            new-shape (mapv #(v+ % offset) current-shape)
            new-total-dist (+ (:loft-total-dist state) (Math/abs dist))
            ;; Save turtle orientation for this waypoint
            orientation {:position new-pos
                         :heading (:heading state)
                         :up (:up state)
                         :dist new-total-dist}]
        (-> state
            (assoc :position new-pos)
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
  (cond
    ;; Attached to mesh pose: rotate the mesh
    (= :pose (get-in state [:attached :type]))
    (rotate-attached-mesh state (:up state) angle)

    ;; Attached to face: rotate the face around up axis (tilt sideways)
    (= :face (get-in state [:attached :type]))
    (rotate-attached-face state (:up state) angle)

    ;; Shape mode: store pending rotation
    (= :shape (:pen-mode state))
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
  (cond
    ;; Attached to mesh pose: rotate the mesh
    (= :pose (get-in state [:attached :type]))
    (let [right (right-vector state)]
      (rotate-attached-mesh state right angle))

    ;; Attached to face: rotate the face around right axis
    (= :face (get-in state [:attached :type]))
    (let [right (right-vector state)]
      (rotate-attached-face state right angle))

    ;; Shape mode: store pending rotation
    (= :shape (:pen-mode state))
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
  (cond
    ;; Attached to mesh pose: rotate the mesh
    (= :pose (get-in state [:attached :type]))
    (rotate-attached-mesh state (:heading state) angle)

    ;; Attached to face: rotate the face around its normal (heading = normal)
    (= :face (get-in state [:attached :type]))
    (rotate-attached-face state (:heading state) angle)

    ;; Shape mode: store pending rotation
    (= :shape (:pen-mode state))
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

(defn- bezier-walk
  "Walk along a bezier curve, moving directly to each sample point.
   Updates heading to follow the curve tangent."
  [state steps point-fn tangent-fn]
  (let [initial-up (:up state)]
    (reduce
     (fn [s i]
       (let [t (/ (inc i) steps)
             new-pos (point-fn t)
             new-heading (tangent-fn t)
             current-pos (:position s)
             move-dir (v- new-pos current-pos)
             dist (magnitude move-dir)]
         (if (> dist 0.001)
           (let [;; Set heading to actual movement direction for f to work correctly
                 actual-heading (normalize move-dir)
                 ;; Recompute up to stay perpendicular to heading
                 right (cross actual-heading initial-up)
                 right-mag (magnitude right)]
             (if (< right-mag 0.001)
               ;; Heading parallel to up - use current up
               (-> s
                   (f dist)
                   (assoc :heading new-heading))
               ;; Normal case: recompute up from heading and right
               (let [new-up (normalize (cross right actual-heading))]
                 (-> s
                     (assoc :heading actual-heading)
                     (assoc :up new-up)
                     (f dist)
                     ;; After movement, set heading to curve tangent for next iteration
                     (assoc :heading new-heading)))))
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

   Usage:
   (bezier-to-anchor state :name)
   (bezier-to-anchor state :name [c1] [c2])
   (bezier-to-anchor state :name :steps 24)"
  [state anchor-name & args]
  (if-let [anchor (get-in state [:anchors anchor-name])]
    (apply bezier-to state (:position anchor) args)
    state))

;; --- Joint mode (for future corner styles) ---

(defn joint-mode
  "Set the joint mode for corners during extrusion.
   Currently supported: :flat (default)
   Future: :round, :tapered"
  [state mode]
  (assoc state :joint-mode mode))

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
      (let [final-segment (build-segment-mesh rings)
            ;; Determine the actual first and last rings for caps
            actual-first-ring (or first-ring (first rings))
            actual-last-ring (last rings)
            ;; Add caps to final segment if it's the only mesh,
            ;; otherwise we need to add caps to the combined structure
            all-meshes (if final-segment
                         (conj existing-meshes final-segment)
                         existing-meshes)
            ;; Create separate cap meshes
            n-verts (count actual-first-ring)
            ;; Bottom cap mesh
            bottom-cap-mesh (when (>= n-verts 3)
                              {:type :mesh
                               :primitive :cap
                               :vertices (vec actual-first-ring)
                               :faces (vec (for [i (range 1 (dec n-verts))]
                                             [0 i (inc i)]))})
            ;; Top cap mesh
            top-cap-mesh (when (>= n-verts 3)
                           {:type :mesh
                            :primitive :cap
                            :vertices (vec actual-last-ring)
                            :faces (vec (for [i (range 1 (dec n-verts))]
                                          [0 (inc i) i]))})]
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

(defn- analyze-closed-path
  "Analyze a path for closed extrusion.
   Returns a vector of segments with their adjustments.

   For a closed path, each forward segment may need shortening:
   - If preceded by significant rotation (>10°): shorten start by radius
   - If followed by significant rotation (>10°): shorten end by radius
   - Small rotations (like arc steps) don't trigger shortening

   Returns: [{:cmd :f :dist 20 :shorten-start r :shorten-end r :rotations-after [...]}]"
  [commands radius]
  (let [cmds (vec commands)
        n (count cmds)
        ;; Find all forward commands and their indices
        forwards (keep-indexed (fn [i c] (when (= :f (:cmd c)) [i c])) cmds)]
    (vec
     (for [[idx cmd] forwards]
       (let [dist (first (:args cmd))
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
             ;; Check if rotation before is significant
             has-significant-rotation-before (>= (total-rotation-angle-closed rotations-before)
                                                 corner-threshold-deg-closed)
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
             ;; Check if rotation after is significant
             has-significant-rotation-after (>= (total-rotation-angle-closed rotations-after)
                                                corner-threshold-deg-closed)]
         {:cmd :f
          :dist dist
          :shorten-start (if has-significant-rotation-before radius 0)
          :shorten-end (if has-significant-rotation-after radius 0)
          :rotations-after rotations-after})))))

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
        ;; First pass: collect ALL rings in order
        (let [rings-result
              (loop [i 0
                     s state
                     rings []]
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

                        ;; Create start ring of this segment
                        start-ring (stamp-shape s1 shape)

                        ;; Move forward by effective distance
                        s2 (assoc s1 :position (v+ (:position s1) (v* (:heading s1) effective-dist)))

                        ;; Create end ring of this segment
                        end-ring (stamp-shape s2 shape)

                        ;; Corner position
                        corner-pos (v+ (:position s2) (v* (:heading s2) shorten-end))
                        s3 (assoc s2 :position corner-pos)

                        ;; Apply rotations to get new heading
                        s4 (reduce apply-rotation-to-state s3 rotations)
                        old-heading (:heading s3)
                        new-heading (:heading s4)

                        ;; Generate corner junction rings based on joint-mode
                        ;; All modes use same shortening. Difference is the junction geometry:
                        ;; :flat - NO extra rings; mesh connects end-ring directly to next start-ring
                        ;; :round - arc of rings rotating from old to new orientation
                        ;; :tapered - scaled intermediate ring at corner
                        corner-rings (if has-corner-rotation
                                       (case joint-mode
                                         :flat []  ;; Direct connection, no intermediate rings
                                         :round (generate-round-corner-rings
                                                 end-ring corner-pos old-heading new-heading
                                                 (calc-round-steps state 90) radius)
                                         :tapered (generate-tapered-corner-rings
                                                   end-ring corner-pos old-heading new-heading)
                                         [])
                                       [])

                        ;; Position for next segment's start: corner + radius along new heading
                        corner-start-pos (v+ (:position s4) (v* (:heading s4) next-shorten-start))

                        ;; Collect rings: start-ring, end-ring, and any corner junction rings
                        new-rings (into (conj rings start-ring end-ring) corner-rings)

                        s5 (assoc s4 :position corner-start-pos)]
                    (recur (inc i) s5 new-rings))))

              all-rings (:rings rings-result)
              final-state (:state rings-result)
              n-rings (count all-rings)
              n-verts (count (first all-rings))]

          (if (< n-rings 2)
            state
            ;; Build single manifold mesh from all rings
            (let [vertices (vec (apply concat all-rings))
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
                                            t1 (+ next-base next-vert)]
                                        ;; CCW winding from outside
                                        [[b0 t1 b1] [b0 t0 t1]]))
                                    (range n-verts))))
                               (range n-rings)))
                  mesh {:type :mesh
                        :primitive :torus
                        :vertices vertices
                        :faces side-faces
                        :creation-pose creation-pose}]
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

(defn finalize-loft
  "Internal: finalize loft by generating rings at N steps with interpolated orientations.
   Called at end of loft macro."
  [state]
  (let [total-dist (:loft-total-dist state)
        base-shape (:loft-base-shape state)
        transform-fn (:loft-transform-fn state)
        orientations (:loft-orientations state)
        steps (:loft-steps state)
        creation-pose {:position (:loft-start-pos state)
                       :heading (:loft-start-heading state)
                       :up (:loft-start-up state)}]
    (if (and (pos? total-dist) base-shape transform-fn (>= (count orientations) 2))
      ;; Generate rings at N+1 evenly spaced points (0 to 1)
      (let [new-rings (vec
                       (for [i (range (inc steps))]
                         (let [t (/ i steps)
                               target-dist (* t total-dist)
                               ;; Get interpolated orientation at this distance
                               orientation (find-orientation-at-dist orientations target-dist total-dist)
                               ;; Get transformed 2D shape at this t
                               transformed-2d (transform-fn base-shape t)
                               ;; Create temp state with interpolated orientation
                               temp-state (-> state
                                              (assoc :position (:position orientation))
                                              (assoc :heading (:heading orientation))
                                              (assoc :up (:up orientation)))]
                           (stamp-shape temp-state transformed-2d))))
            mesh (build-sweep-mesh new-rings false creation-pose)]
        (if mesh
          (-> state
              (update :meshes conj mesh)
              (assoc :sweep-rings [])
              (assoc :stamped-shape nil)
              (dissoc :loft-base-shape :loft-transform-fn :loft-steps
                      :loft-total-dist :loft-start-pos :loft-start-heading
                      :loft-start-up :loft-orientations))
          (-> state
              (assoc :sweep-rings [])
              (assoc :stamped-shape nil)
              (dissoc :loft-base-shape :loft-transform-fn :loft-steps
                      :loft-total-dist :loft-start-pos :loft-start-heading
                      :loft-start-up :loft-orientations))))
      ;; Not enough data - just clear
      (-> state
          (assoc :sweep-rings [])
          (assoc :stamped-shape nil)
          (dissoc :loft-base-shape :loft-transform-fn :loft-steps
                  :loft-total-dist :loft-start-pos :loft-start-heading
                  :loft-start-up :loft-orientations)))))

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

(defn stamp-shape-at
  "Stamp a 2D shape at current turtle position/orientation.
   Returns the 3D ring (vector of 3D points).
   Does not modify turtle state."
  [state shape]
  (when (shape? shape)
    (stamp-shape state shape)))

(defn sweep-two-shapes
  "Create a mesh connecting two 3D rings (stamped shapes).
   ring1 and ring2 must have the same number of vertices.
   Returns a mesh with side faces connecting the two rings."
  [ring1 ring2]
  (let [n-verts (count ring1)]
    (when (and (>= n-verts 3) (= n-verts (count ring2)))
      (let [vertices (vec (concat ring1 ring2))
            ;; Create faces connecting ring1 (indices 0 to n-1) to ring2 (indices n to 2n-1)
            side-faces (vec
                        (mapcat
                         (fn [i]
                           (let [next-i (mod (inc i) n-verts)
                                 b0 i
                                 b1 next-i
                                 t0 (+ n-verts i)
                                 t1 (+ n-verts next-i)]
                             ;; CCW winding from outside
                             [[b0 t1 b1] [b0 t0 t1]]))
                         (range n-verts)))
            ;; Bottom cap (ring1) - facing backward
            bottom-cap (vec (for [i (range 1 (dec n-verts))]
                              [0 i (inc i)]))
            ;; Top cap (ring2) - facing forward
            top-cap (vec (for [i (range 1 (dec n-verts))]
                           [n-verts (+ n-verts i 1) (+ n-verts i)]))]
        {:type :mesh
         :primitive :sweep-two
         :vertices vertices
         :faces (vec (concat side-faces bottom-cap top-cap))
         ;; Default creation pose at origin (matches turtle default orientation)
         :creation-pose {:position [0 0 0]
                         :heading [1 0 0]
                         :up [0 0 1]}}))))

(defn make-path
  "Create a path from a vector of recorded commands.
   Each command is {:cmd :f/:b/:th/:tv/:tr :args [...]}"
  [commands]
  {:type :path
   :commands (vec commands)})

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
                s))
            state
            (:commands path))
    state))

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
              ;; Check if there's a CORNER rotation before this forward
              ;; (excludes :set-heading used by smooth curves like bezier/arc)
              prev-idx (dec idx)
              has-corner-before (and (>= prev-idx 0)
                                     (is-corner-rotation? (:cmd (nth cmds prev-idx))))
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
              ;; Check if there's a CORNER rotation after (for shorten decision)
              has-corner-after (some #(is-corner-rotation? (:cmd %)) rotations-after)]
          {:cmd :f
           :dist dist
           ;; Open path: first segment doesn't shorten start
           :shorten-start (if (and has-corner-before (not is-first)) radius 0)
           ;; Open path: last segment doesn't shorten end
           :shorten-end (if (and has-corner-after (not is-last)) radius 0)
           :rotations-after rotations-after}))
      forwards))))

(defn extrude-from-path
  "Extrude a shape along an open path, creating a SINGLE unified mesh.

   Pre-processes the path to calculate correct segment lengths.
   Collects all rings in order and builds one mesh with side faces and end caps.

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
          segments (analyze-open-path commands radius)
          n-segments (count segments)]
      (if (< n-segments 1)
        state
        ;; First pass: collect ALL rings in order
        (let [rings-result
              (loop [i 0
                     s state
                     rings []]
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

                        ;; Create start ring
                        start-ring (stamp-shape s1 shape)

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

                        ;; Calculate angle between headings for round corner resolution
                        corner-angle-deg (when (and has-corner-rotation (= joint-mode :round))
                                           (let [cos-a (dot old-heading new-heading)
                                                 angle-rad (Math/acos (min 1 (max -1 cos-a)))]
                                             (* angle-rad (/ 180 Math/PI))))
                        round-steps (when corner-angle-deg
                                      (calc-round-steps state corner-angle-deg))

                        ;; Generate corner junction rings based on joint-mode
                        ;; All modes use same shortening. Difference is the junction geometry:
                        ;; :flat - NO extra rings; mesh connects end-ring directly to next start-ring
                        ;; :round - arc of rings rotating from old to new orientation
                        ;; :tapered - scaled intermediate ring at corner
                        corner-rings (if (and has-corner-rotation (not is-last))
                                       (case joint-mode
                                         :flat []  ;; Direct connection, no intermediate rings
                                         :round (generate-round-corner-rings
                                                 end-ring corner-pos old-heading new-heading
                                                 (or round-steps 4) radius)
                                         :tapered (generate-tapered-corner-rings
                                                   end-ring corner-pos old-heading new-heading)
                                         [])
                                       [])

                        ;; Position for next segment: corner + radius along new heading
                        next-shorten-start (if (and (not is-last) has-corner-rotation)
                                             (:shorten-start (nth segments (inc i)))
                                             0)
                        next-start-pos (v+ corner-pos (v* (:heading s4) next-shorten-start))
                        s-next (assoc s4 :position next-start-pos)

                        ;; Collect rings for this segment
                        ;; start-ring + end-ring + any corner rings
                        new-rings (into (conj rings start-ring end-ring) corner-rings)]
                    (recur (inc i) s-next new-rings))))

              all-rings (:rings rings-result)
              final-state (:state rings-result)
              n-rings (count all-rings)
              n-verts (count (first all-rings))]

          (if (< n-rings 2)
            state
            ;; Build single unified mesh from all rings
            (let [;; Calculate centroid for cap
                  calc-centroid (fn [ring]
                                  (let [n (count ring)
                                        sum (reduce (fn [[sx sy sz] [x y z]]
                                                      [(+ sx x) (+ sy y) (+ sz z)])
                                                    [0 0 0] ring)]
                                    [(/ (first sum) n) (/ (second sum) n) (/ (nth sum 2) n)]))

                  first-ring (first all-rings)
                  last-ring (last all-rings)
                  bottom-centroid (calc-centroid first-ring)
                  top-centroid (calc-centroid last-ring)

                  ;; Vertices: bottom-centroid + all rings + top-centroid
                  ;; Layout: [bottom-centroid, ring0-v0, ring0-v1, ..., ring0-vN, ring1-v0, ..., top-centroid]
                  vertices (vec (concat [bottom-centroid]
                                        (apply concat all-rings)
                                        [top-centroid]))

                  ;; Bottom cap faces (fan from centroid at index 0)
                  ;; Ring vertices start at index 1
                  bottom-cap-faces (vec (for [j (range n-verts)]
                                          (let [v0 (+ 1 j)
                                                v1 (+ 1 (mod (inc j) n-verts))]
                                            ;; CCW from outside (facing backward along extrusion)
                                            [0 v0 v1])))

                  ;; Side faces connecting consecutive rings
                  ;; Ring i vertices: 1 + i*n-verts to 1 + (i+1)*n-verts - 1
                  side-faces (vec
                              (mapcat
                               (fn [ring-idx]
                                 (mapcat
                                  (fn [vert-idx]
                                    (let [next-vert (mod (inc vert-idx) n-verts)
                                          base (+ 1 (* ring-idx n-verts))
                                          next-base (+ 1 (* (inc ring-idx) n-verts))
                                          b0 (+ base vert-idx)
                                          b1 (+ base next-vert)
                                          t0 (+ next-base vert-idx)
                                          t1 (+ next-base next-vert)]
                                      ;; CCW winding from outside
                                      [[b0 t0 t1] [b0 t1 b1]]))
                                  (range n-verts)))
                               (range (dec n-rings))))

                  ;; Top cap faces (fan from centroid at last index)
                  top-centroid-idx (dec (count vertices))
                  last-ring-base (+ 1 (* (dec n-rings) n-verts))
                  top-cap-faces (vec (for [j (range n-verts)]
                                       (let [v0 (+ last-ring-base j)
                                             v1 (+ last-ring-base (mod (inc j) n-verts))]
                                         ;; CCW from outside (facing forward along extrusion)
                                         [top-centroid-idx v1 v0])))

                  all-faces (vec (concat bottom-cap-faces side-faces top-cap-faces))

                  mesh {:type :mesh
                        :primitive :extrusion
                        :vertices vertices
                        :faces all-faces
                        :creation-pose creation-pose}]
              (update final-state :meshes conj mesh))))))))

;; ============================================================
;; Anchors and Navigation
;; ============================================================

(defn mark
  "Save current turtle pose (position, heading, up) with a name.
   Overwrites if name already exists.
   Use goto to return to this position later."
  [state name]
  (let [pose {:position (:position state)
              :heading (:heading state)
              :up (:up state)}]
    (assoc-in state [:anchors name] pose)))

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
                                                 :to to-pos})
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
   SCI-compatible (no keyword args)."
  [state mesh face-id]
  (-> state
      (attach-face mesh face-id :clone true)
      ;; Immediately extrude 0 to create cloned vertices and side faces
      ;; The cloned vertices start coincident with the original face
      (extrude-attached-face 0)
      ;; Turn off extrude-mode: vertices are now cloned, future f just moves them
      (assoc-in [:attached :extrude-mode] false)))

(defn ^:export attach-move
  "Attach to a mesh's creation pose (modifies original mesh).
   SCI-compatible wrapper for (attach state mesh) without :clone."
  [state mesh]
  (attach state mesh))

(defn ^:export attach-clone
  "Attach to a cloned copy of a mesh (original unchanged).
   SCI-compatible wrapper for (attach state mesh :clone true)."
  [state mesh]
  (attach state mesh :clone true))

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

