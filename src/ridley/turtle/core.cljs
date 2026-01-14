(ns ridley.turtle.core
  "Immutable turtle state and movement commands.

   State structure:
   {:position [x y z]     - current position
    :heading [x y z]      - forward direction (unit vector)
    :up [x y z]           - up direction (unit vector)
    :pen-down? boolean    - whether movement draws
    :geometry []}         - accumulated line segments")

(defn make-turtle
  "Create initial turtle state at origin, facing +Z, up +Y."
  []
  {:position [0 0 0]
   :heading [0 0 1]
   :up [0 1 0]
   :pen-down? true
   :geometry []})

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
   If pen is down, add segment to geometry."
  [state direction dist]
  (let [pos (:position state)
        new-pos (v+ pos (v* direction dist))]
    (if (:pen-down? state)
      (-> state
          (assoc :position new-pos)
          (update :geometry conj {:type :line
                                  :from pos
                                  :to new-pos}))
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
  "Stop drawing when moving."
  [state]
  (assoc state :pen-down? false))

(defn pen-down
  "Start drawing when moving."
  [state]
  (assoc state :pen-down? true))
