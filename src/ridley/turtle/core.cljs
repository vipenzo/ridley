(ns ridley.turtle.core
  "Immutable turtle state and movement commands.

   State structure:
   {:position [x y z]     - current position
    :heading [x y z]      - forward direction (unit vector)
    :up [x y z]           - up direction (unit vector)
    :pen-mode             - nil/:off, :2d, :3d, or face-id
    :pen-plane            - for :3d mode: {:at [x y z] :normal [x y z]}
    :current-face         - selected face info when pen is on a face
    :pending-profile []   - 2D points accumulated on current face/plane
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

;; --- Movement commands ---

(defn- move
  "Move turtle by distance along a direction vector.
   Behavior depends on pen-mode:
   - :off or nil - just move, no drawing
   - :2d - add line segment to geometry
   - :3d or face-id - accumulate profile point (TODO: implement extrusion)"
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

      ;; For :3d and face modes, just move for now
      ;; Profile accumulation will be handled separately
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
