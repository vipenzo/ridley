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
   :meshes []})

;; --- Vector math utilities ---

(defn v+ [[x1 y1 z1] [x2 y2 z2]]
  [(+ x1 x2) (+ y1 y2) (+ z1 z2)])

(defn v- [[x1 y1 z1] [x2 y2 z2]]
  [(- x1 x2) (- y1 y2) (- z1 z2)])

(defn v* [[x y z] s]
  [(* x s) (* y s) (* z s)])

(defn dot [[x1 y1 z1] [x2 y2 z2]]
  (+ (* x1 x2) (* y1 y2) (* z1 z2)))

(defn cross [[x1 y1 z1] [x2 y2 z2]]
  [(- (* y1 z2) (* z1 y2))
   (- (* z1 x2) (* x1 z2))
   (- (* x1 y2) (* y1 x2))])

(defn magnitude [[x y z]]
  (Math/sqrt (+ (* x x) (* y y) (* z z))))

(defn normalize [v]
  (let [m (magnitude v)]
    (if (zero? m)
      v
      (v* v (/ 1 m)))))

(defn rotate-around-axis
  "Rotate vector v around axis by angle (radians) using Rodrigues' formula."
  [v axis angle]
  (let [k (normalize axis)
        cos-a (Math/cos angle)
        sin-a (Math/sin angle)
        ; v' = v*cos(a) + (k x v)*sin(a) + k*(k·v)*(1-cos(a))
        term1 (v* v cos-a)
        term2 (v* (cross k v) sin-a)
        term3 (v* k (* (dot k v) (- 1 cos-a)))]
    (normalize (v+ (v+ term1 term2) term3))))

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
   Otherwise creates bottom cap, side faces, and top cap."
  ([rings] (build-sweep-mesh rings false))
  ([rings closed?]
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
           {:type :mesh
            :primitive :sweep-closed
            :vertices vertices
            :faces side-faces})
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
           {:type :mesh
            :primitive :sweep
            :vertices vertices
            :faces (vec (concat side-faces bottom-cap top-cap))}))))))

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
  "Move forward by distance. Use negative values to move backward."
  [state dist]
  (move state (:heading state) dist))

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
   In shape mode, stores pending rotation for fillet creation on next (f)."
  [state angle]
  (if (= :shape (:pen-mode state))
    ;; In shape mode, store pending rotation (fillet created on next f)
    (store-pending-rotation state :th angle)
    ;; Normal mode: apply rotation immediately
    (let [rad (deg->rad angle)
          new-heading (rotate-around-axis (:heading state) (:up state) rad)]
      (assoc state :heading new-heading))))

(defn tv
  "Turn vertical (pitch) - rotate heading and up around right axis.
   Positive angle pitches up.
   In shape mode, stores pending rotation for fillet creation on next (f)."
  [state angle]
  (if (= :shape (:pen-mode state))
    ;; In shape mode, store pending rotation (fillet created on next f)
    (store-pending-rotation state :tv angle)
    ;; Normal mode: apply rotation immediately
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
   In shape mode, stores pending rotation for fillet creation on next (f)."
  [state angle]
  (if (= :shape (:pen-mode state))
    ;; In shape mode, store pending rotation (fillet created on next f)
    (store-pending-rotation state :tr angle)
    ;; Normal mode: apply rotation immediately
    (let [rad (deg->rad angle)
          new-up (rotate-around-axis (:up state) (:heading state) rad)]
      (assoc state :up new-up))))

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
  "Check if command is a rotation."
  [cmd]
  (#{:th :tv :tr} cmd))

(defn- is-path?
  "Check if x is a path (internal version to avoid forward reference)."
  [x]
  (and (map? x) (= :path (:type x))))

(defn- analyze-closed-path
  "Analyze a path for closed extrusion.
   Returns a vector of segments with their adjustments.

   For a closed path, each forward segment may need shortening:
   - If preceded by rotation: shorten start by radius
   - If followed by rotation: shorten end by radius

   Returns: [{:cmd :f :dist 20 :shorten-start r :shorten-end r :rotations-after [...]}]"
  [commands radius]
  (let [cmds (vec commands)
        n (count cmds)
        ;; Find all forward commands and their indices
        forwards (keep-indexed (fn [i c] (when (= :f (:cmd c)) [i c])) cmds)]
    (vec
     (for [[idx cmd] forwards]
       (let [dist (first (:args cmd))
             ;; Check if there's a rotation before this forward
             ;; For closed path, rotation before first forward = rotation after last forward
             prev-idx (mod (dec idx) n)
             prev-cmd (:cmd (nth cmds prev-idx))
             has-rotation-before (is-rotation? prev-cmd)
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
             has-rotation-after (seq rotations-after)]
         {:cmd :f
          :dist dist
          :shorten-start (if has-rotation-before radius 0)
          :shorten-end (if has-rotation-after radius 0)
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
    state))

(defn extrude-closed-from-path
  "Extrude a shape along a closed path, creating a torus-like mesh.

   Pre-processes the path to calculate correct segment lengths.
   Creates a SINGLE manifold mesh with all rings connected.

   Returns the turtle state with the mesh added."
  [state shape path]
  (if-not (and (shape? shape) (is-path? path))
    state
    (let [radius (shape-radius shape)
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

                        ;; Move to corner position
                        s3 (assoc s2 :position (v+ (:position s2) (v* (:heading s2) shorten-end)))

                        ;; Apply rotations to get new heading
                        s4 (reduce apply-rotation-to-state s3 rotations)

                        ;; Position for next segment's start
                        corner-start-pos (v+ (:position s4) (v* (:heading s4) next-shorten-start))

                        ;; Collect rings: only add distinct position rings
                        ;; - start-ring: beginning of segment (after shorten-start)
                        ;; - end-ring: end of segment (before shorten-end)
                        ;; DON'T add corner-end-ring - it's the same position as next start-ring
                        new-rings (conj rings start-ring end-ring)

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
                        :faces side-faces}]
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
        steps (:loft-steps state)]
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
            mesh (build-sweep-mesh new-rings)]
        (if mesh
          (-> state
              (update :meshes conj mesh)
              (assoc :sweep-rings [])
              (assoc :stamped-shape nil)
              (dissoc :loft-base-shape :loft-transform-fn :loft-steps
                      :loft-total-dist :loft-start-pos :loft-orientations))
          (-> state
              (assoc :sweep-rings [])
              (assoc :stamped-shape nil)
              (dissoc :loft-base-shape :loft-transform-fn :loft-steps
                      :loft-total-dist :loft-start-pos :loft-orientations))))
      ;; Not enough data - just clear
      (-> state
          (assoc :sweep-rings [])
          (assoc :stamped-shape nil)
          (dissoc :loft-base-shape :loft-transform-fn :loft-steps
                  :loft-total-dist :loft-start-pos :loft-orientations)))))

;; ============================================================
;; Path - recorded turtle movements
;; ============================================================

(defn path?
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
         :faces (vec (concat side-faces bottom-cap top-cap))}))))

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
                s))
            state
            (:commands path))
    state))

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
              ;; Check if there's a rotation before this forward
              prev-idx (dec idx)
              has-rotation-before (and (>= prev-idx 0)
                                       (is-rotation? (:cmd (nth cmds prev-idx))))
              ;; Collect all rotations after this forward until next forward or end
              rotations-after (loop [i (inc idx)
                                     rots []]
                                (if (>= i n)
                                  rots
                                  (let [c (nth cmds i)]
                                    (if (is-rotation? (:cmd c))
                                      (recur (inc i) (conj rots c))
                                      rots))))
              has-rotation-after (seq rotations-after)]
          {:cmd :f
           :dist dist
           ;; Open path: first segment doesn't shorten start
           :shorten-start (if (and has-rotation-before (not is-first)) radius 0)
           ;; Open path: last segment doesn't shorten end
           :shorten-end (if (and has-rotation-after (not is-last)) radius 0)
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
    (let [radius (shape-radius shape)
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

                        ;; For first segment, start at current position
                        ;; For subsequent segments, position is already correct from previous iteration
                        start-pos (:position s)
                        s1 (assoc s :position start-pos)

                        ;; Create start ring
                        start-ring (stamp-shape s1 shape)

                        ;; Position at end of segment
                        end-pos (v+ start-pos (v* (:heading s1) effective-dist))
                        s2 (assoc s1 :position end-pos)
                        end-ring (stamp-shape s2 shape)

                        ;; Move to corner position
                        corner-pos (v+ end-pos (v* (:heading s2) shorten-end))
                        s3 (assoc s2 :position corner-pos)

                        ;; Apply rotations and get new heading
                        s4 (reduce apply-rotation-to-state s3 rotations)
                        old-heading (:heading s3)
                        new-heading (:heading s4)

                        ;; Generate corner rings based on joint-mode
                        joint-mode (or (:joint-mode state) :flat)
                        corner-rings (if (and (seq rotations) (not is-last))
                                       (case joint-mode
                                         :round (generate-round-corner-rings
                                                 end-ring corner-pos old-heading new-heading 4 radius)
                                         :tapered (generate-tapered-corner-rings
                                                   end-ring corner-pos old-heading new-heading)
                                         ;; :flat - no intermediate rings
                                         [])
                                       [])

                        ;; Position for next segment (if any)
                        ;; Same calculation for all joint modes
                        next-shorten-start (if (and (not is-last) (seq rotations))
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
                        :faces all-faces}]
              (update final-state :meshes conj mesh))))))))

