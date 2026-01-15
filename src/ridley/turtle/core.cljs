(ns ridley.turtle.core
  "Immutable turtle state and movement commands.

   State structure:
   {:position [x y z]     - current position
    :heading [x y z]      - forward direction (unit vector)
    :up [x y z]           - up direction (unit vector)
    :pen-mode             - nil/:off, :2d, :3d, or face-id
    :pen-plane            - for :3d mode: {:at [x y z] :normal [x y z] :heading [x y z]}
    :current-face         - selected face info when pen is on a face
    :pending-profile []   - 2D points [x y] in local plane coords
    :geometry []          - accumulated line segments
    :meshes []}           - accumulated 3D meshes")

(defn make-turtle
  "Create initial turtle state at origin, facing +X, up +Z.
   This makes 2D drawing happen in the XY plane (Z=0),
   ideal for extruding along Z."
  []
  {:position [0 0 0]
   :heading [1 0 0]
   :up [0 0 1]
   :pen-mode :2d            ; :off, :2d, :3d, or face-id
   :pen-plane nil           ; for :3d mode: {:at [x y z] :normal [x y z]}
   :current-face nil        ; selected face when pen is on a face
   :pending-profile []      ; accumulated 2D profile points
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

;; --- Plane coordinate transforms ---

(defn- get-plane-frame
  "Get the coordinate frame for the current plane.
   Returns {:origin [x y z] :x-axis [x y z] :y-axis [x y z] :z-axis [x y z]}
   or nil if not in plane mode."
  [state]
  (when-let [plane (:pen-plane state)]
    (let [origin (:at plane)
          z-axis (normalize (:normal plane))
          x-axis (normalize (:heading plane))
          ;; Y axis = Z cross X (right-hand rule)
          y-axis (cross z-axis x-axis)]
      {:origin origin
       :x-axis x-axis
       :y-axis y-axis
       :z-axis z-axis})))

(defn- world-to-plane
  "Convert a 3D world position to 2D plane coordinates [x y].
   Returns nil if no plane is set."
  [state world-pos]
  (when-let [frame (get-plane-frame state)]
    (let [rel (v- world-pos (:origin frame))
          x (dot rel (:x-axis frame))
          y (dot rel (:y-axis frame))]
      [x y])))

(defn- plane-to-world
  "Convert 2D plane coordinates [x y] to 3D world position.
   Returns nil if no plane is set."
  [state [px py]]
  (when-let [frame (get-plane-frame state)]
    (v+ (:origin frame)
        (v+ (v* (:x-axis frame) px)
            (v* (:y-axis frame) py)))))

(defn- extrude-profile
  "Extrude a 2D profile along the plane's normal to create a 3D mesh.
   Returns the mesh or nil if no valid profile/plane."
  [state dist]
  (when-let [frame (get-plane-frame state)]
    (let [profile (:pending-profile state)
          n (count profile)]
      (when (>= n 3)
        (let [;; Convert 2D profile points to 3D
              bottom-verts (mapv (fn [[px py]]
                                   (v+ (:origin frame)
                                       (v+ (v* (:x-axis frame) px)
                                           (v* (:y-axis frame) py))))
                                 profile)
              ;; Extrude along normal
              offset (v* (:z-axis frame) dist)
              top-verts (mapv #(v+ % offset) bottom-verts)
              ;; Combine vertices: bottom ring, then top ring
              vertices (into (vec bottom-verts) top-verts)
              ;; Side faces (quads as 2 triangles each)
              side-faces (vec
                          (mapcat (fn [i]
                                    (let [next-i (mod (inc i) n)
                                          b0 i
                                          b1 next-i
                                          t0 (+ i n)
                                          t1 (+ next-i n)]
                                      ;; Winding order depends on extrusion direction
                                      (if (pos? dist)
                                        [[b0 b1 t1] [b0 t1 t0]]
                                        [[b0 t0 t1] [b0 t1 b1]])))
                                  (range n)))
              ;; Cap faces (simple fan triangulation)
              bottom-cap (vec (for [i (range 1 (dec n))]
                                (if (pos? dist)
                                  [0 (inc i) i]
                                  [0 i (inc i)])))
              top-cap (vec (for [i (range 1 (dec n))]
                             (if (pos? dist)
                               [n (+ n i) (+ n i 1)]
                               [n (+ n i 1) (+ n i)])))]
          {:type :mesh
           :primitive :profile-extrude
           :vertices vertices
           :faces (vec (concat side-faces bottom-cap top-cap))})))))

;; --- Movement commands ---

(defn- move
  "Move turtle by distance along a direction vector.
   Behavior depends on pen-mode:
   - :off or nil - just move, no drawing
   - :2d - add line segment to geometry
   - :3d - if profile exists, extrude it; otherwise accumulate profile points"
  [state direction dist]
  (let [pos (:position state)
        new-pos (v+ pos (v* direction dist))
        mode (:pen-mode state)]
    (case mode
      (:off nil)
      (assoc state :position new-pos)

      :2d
      (-> state
          (assoc :position new-pos)
          (update :geometry conj {:type :line
                                  :from pos
                                  :to new-pos}))

      :3d
      ;; In plane mode: check if we have a complete profile to extrude
      (let [profile (:pending-profile state)]
        (if (>= (count profile) 3)
          ;; Profile exists - extrude it along plane normal
          (if-let [mesh (extrude-profile state dist)]
            (-> state
                (assoc :position new-pos)
                (update :meshes conj mesh)
                (assoc :pending-profile []))  ; Clear profile after extrusion
            ;; Fallback: just move if extrusion failed
            (assoc state :position new-pos))
          ;; No complete profile - accumulate points
          (let [;; Add start point if this is the first move
                profile' (if (empty? profile)
                           (if-let [start-2d (world-to-plane state pos)]
                             [start-2d]
                             profile)
                           profile)
                ;; Add end point
                profile'' (if-let [end-2d (world-to-plane state new-pos)]
                            (conj profile' end-2d)
                            profile')]
            (-> state
                (assoc :position new-pos)
                (assoc :pending-profile profile'')))))

      ;; Face mode - similar to :3d but needs face info lookup
      ;; For now, just move without accumulating
      (assoc state :position new-pos))))

(defn f
  "Move forward by distance."
  [state dist]
  (move state (:heading state) dist))

(defn b
  "Move backward by distance."
  [state dist]
  (move state (v* (:heading state) -1) dist))

(defn u
  "Move up (along turtle's up vector) by distance."
  [state dist]
  (move state (:up state) dist))

(defn d
  "Move down (opposite of up) by distance."
  [state dist]
  (move state (v* (:up state) -1) dist))

;; --- Rotation commands ---

(defn- deg->rad [deg]
  (* deg (/ Math/PI 180)))

(defn- right-vector
  "Calculate the right vector (heading x up)."
  [state]
  (cross (:heading state) (:up state)))

(defn th
  "Turn horizontal (yaw) - rotate heading around up axis.
   Positive angle turns left."
  [state angle]
  (let [rad (deg->rad angle)
        new-heading (rotate-around-axis (:heading state) (:up state) rad)]
    (assoc state :heading new-heading)))

(defn tv
  "Turn vertical (pitch) - rotate heading and up around right axis.
   Positive angle pitches up."
  [state angle]
  (let [rad (deg->rad angle)
        right (right-vector state)
        new-heading (rotate-around-axis (:heading state) right rad)
        new-up (rotate-around-axis (:up state) right rad)]
    (-> state
        (assoc :heading new-heading)
        (assoc :up new-up))))

(defn tr
  "Turn roll - rotate up around heading axis.
   Positive angle rolls clockwise (when viewed from behind)."
  [state angle]
  (let [rad (deg->rad angle)
        new-up (rotate-around-axis (:up state) (:heading state) rad)]
    (assoc state :up new-up)))

;; --- Pen commands ---

(defn pen-up
  "Stop drawing when moving. (Legacy - use (pen :off) instead)"
  [state]
  (assoc state :pen-mode :off))

(defn pen-down
  "Start drawing when moving. (Legacy - use (pen :2d) instead)"
  [state]
  (assoc state :pen-mode :2d))

(defn pen
  "Set pen mode for drawing.

   Modes:
   - (pen :off) - stop drawing
   - (pen :2d) - draw lines (default turtle behavior)
   - (pen :3d :at [p] :normal [n] :heading [h]) - draw on arbitrary plane
   - (pen face-id) - draw on mesh face (:top, :bottom, :front, etc.)
   - (pen face-id :at [u v]) - draw on face with UV offset from center

   The plane requires a full frame: :at (position), :normal (Z axis),
   and :heading (X axis). The Y axis is derived from normal × heading.

   When pen is on a face or plane, movement accumulates profile points
   that can be extruded with (f dist)."
  [state mode & {:keys [at normal heading]}]
  (cond
    ;; Simple modes
    (= mode :off)
    (-> state
        (assoc :pen-mode :off)
        (assoc :pen-plane nil)
        (assoc :current-face nil)
        (assoc :pending-profile []))

    (= mode :2d)
    (-> state
        (assoc :pen-mode :2d)
        (assoc :pen-plane nil)
        (assoc :current-face nil)
        (assoc :pending-profile []))

    ;; 3D plane mode - requires full frame
    (= mode :3d)
    (-> state
        (assoc :pen-mode :3d)
        (assoc :pen-plane {:at (or at [0 0 0])
                           :normal (or normal [0 0 1])
                           :heading (or heading [1 0 0])})
        (assoc :current-face nil)
        (assoc :pending-profile []))

    ;; Face selection mode (keyword like :top, :bottom, or numeric ID)
    :else
    (-> state
        (assoc :pen-mode mode)
        (assoc :pen-plane nil)
        (assoc :current-face {:id mode :offset (or at [0 0])})
        (assoc :pending-profile []))))

;; --- 2D Primitives ---
;; These generate closed profile points centered at current turtle position.
;; They work in any pen mode but are most useful in :3d mode for extrusion.

(defn circle
  "Generate a circular profile centered at turtle position.
   Returns state with pending-profile set to circle points."
  ([state radius] (circle state radius 32))
  ([state radius segments]
   (let [pos (:position state)
         ;; Get 2D center position on plane
         center-2d (if (= :3d (:pen-mode state))
                     (world-to-plane state pos)
                     [0 0])
         [cx cy] (or center-2d [0 0])
         ;; Generate circle points
         step (/ (* 2 Math/PI) segments)
         points (vec (for [i (range segments)]
                       (let [angle (* i step)]
                         [(+ cx (* radius (Math/cos angle)))
                          (+ cy (* radius (Math/sin angle)))])))]
     (assoc state :pending-profile points))))

(defn rect
  "Generate a rectangular profile centered at turtle position.
   Returns state with pending-profile set to rectangle corners."
  [state width height]
  (let [pos (:position state)
        ;; Get 2D center position on plane
        center-2d (if (= :3d (:pen-mode state))
                    (world-to-plane state pos)
                    [0 0])
        [cx cy] (or center-2d [0 0])
        hw (/ width 2)
        hh (/ height 2)
        ;; Rectangle corners (CCW from bottom-left)
        points [[(- cx hw) (- cy hh)]
                [(+ cx hw) (- cy hh)]
                [(+ cx hw) (+ cy hh)]
                [(- cx hw) (+ cy hh)]]]
    (assoc state :pending-profile points)))

(defn polygon
  "Generate a polygonal profile from a sequence of 2D points.
   Points are relative to turtle position if in :3d mode."
  [state points]
  (let [pos (:position state)
        ;; Get 2D offset for turtle position
        offset (if (= :3d (:pen-mode state))
                 (world-to-plane state pos)
                 [0 0])
        [ox oy] (or offset [0 0])
        ;; Apply offset to all points
        adjusted (mapv (fn [[x y]] [(+ ox x) (+ oy y)]) points)]
    (assoc state :pending-profile adjusted)))
