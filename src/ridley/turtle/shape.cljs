(ns ridley.turtle.shape
  "Shape definitions: 2D profiles that can be stamped and extruded.

   A shape is a closed 2D profile defined as a vector of [x y] points.
   Shapes can be:
   - Built-in: (circle r), (rect w h) - centered at origin
   - Custom: (shape ...) - recorded from turtle movements

   The new pen API:
   (pen shape & movements)
   - Stamps the shape on the plane perpendicular to turtle's heading
   - Movements after stamping extrude the shape
   - Turtle moves (extrusion is a side-effect)")

;; ============================================================
;; Shape data structure
;; ============================================================

(defn make-shape
  "Create a shape from a vector of 2D points.
   Points should form a closed polygon (CCW winding).

   Options:
   - :centered? true  - shape is centered at turtle position (default for circle/rect)
   - :centered? false - shape starts at turtle position (default for custom shapes)"
  ([points] (make-shape points {:centered? false}))
  ([points opts]
   {:type :shape
    :points (vec points)
    :centered? (:centered? opts false)}))

(defn shape?
  "Check if x is a shape."
  [x]
  (and (map? x) (= :shape (:type x))))

;; ============================================================
;; Built-in shapes (centered at origin)
;; ============================================================

(defn circle-shape
  "Create a circular shape centered at origin."
  ([radius] (circle-shape radius 32))
  ([radius segments]
   (let [step (/ (* 2 Math/PI) segments)
         points (vec (for [i (range segments)]
                       (let [angle (* i step)]
                         [(* radius (Math/cos angle))
                          (* radius (Math/sin angle))])))]
     (make-shape points {:centered? true}))))

(defn rect-shape
  "Create a rectangular shape centered at origin."
  [width height]
  (let [hw (/ width 2)
        hh (/ height 2)
        points [[(- hw) (- hh)]
                [hw (- hh)]
                [hw hh]
                [(- hw) hh]]]
    (make-shape points {:centered? true})))

(defn polygon-shape
  "Create a polygonal shape from points.
   Points are relative to origin (not centered)."
  [points]
  (make-shape points {:centered? false}))

(defn ngon-shape
  "Create a regular n-sided polygon centered at origin.
   n: number of sides (e.g., 6 for hexagon)
   radius: distance from center to vertices"
  [n radius]
  (let [step (/ (* 2 Math/PI) n)
        points (vec (for [i (range n)]
                      (let [angle (- (* i step) (/ Math/PI 2))]  ; Start at top
                        [(* radius (Math/cos angle))
                         (* radius (Math/sin angle))])))]
    (make-shape points {:centered? true})))

(defn star-shape
  "Create a star shape centered at origin.
   n-points: number of points (tips)
   outer-r: radius to outer points (tips)
   inner-r: radius to inner points (valleys)"
  ([n-points outer-r inner-r]
   (let [total-verts (* 2 n-points)
         step (/ (* 2 Math/PI) total-verts)
         points (vec (for [i (range total-verts)]
                       (let [angle (* i step)
                             r (if (even? i) outer-r inner-r)]
                         [(* r (Math/cos angle))
                          (* r (Math/sin angle))])))]
     (make-shape points {:centered? true}))))

;; ============================================================
;; Shape from turtle recording
;; ============================================================

(defn recording-turtle
  "Create a turtle for recording movements.
   Starts at origin, facing +X, records path."
  []
  {:position [0 0]
   :heading [1 0]
   :path []})

(defn- rotate-2d
  "Rotate a 2D vector by angle (radians)."
  [[x y] angle]
  (let [cos-a (Math/cos angle)
        sin-a (Math/sin angle)]
    [(- (* x cos-a) (* y sin-a))
     (+ (* x sin-a) (* y cos-a))]))

(defn- v2+ [[x1 y1] [x2 y2]]
  [(+ x1 x2) (+ y1 y2)])

(defn- v2* [[x y] s]
  [(* x s) (* y s)])

(defn- deg->rad [deg]
  (* deg (/ Math/PI 180)))

;; Recording turtle commands (pure, for shape macro)
(defn rec-f
  "Record forward movement."
  [state dist]
  (let [pos (:position state)
        heading (:heading state)
        new-pos (v2+ pos (v2* heading dist))
        path' (if (empty? (:path state))
                [pos new-pos]
                (conj (:path state) new-pos))]
    (-> state
        (assoc :position new-pos)
        (assoc :path path'))))

(defn rec-b
  "Record backward movement."
  [state dist]
  (rec-f state (- dist)))

(defn rec-th
  "Record turn (2D only has horizontal turn)."
  [state angle]
  (let [rad (deg->rad angle)
        new-heading (rotate-2d (:heading state) rad)]
    (assoc state :heading new-heading)))

(defn shape-from-recording
  "Extract shape from recorded turtle state.
   The path forms the shape outline."
  [rec-state]
  (let [path (:path rec-state)]
    (when (>= (count path) 3)
      (make-shape path {:centered? false}))))

;; ============================================================
;; Shape interpolation
;; ============================================================

(defn lerp-shape
  "Linearly interpolate between two shapes.
   t=0 returns shape1, t=1 returns shape2.
   Both shapes must have the same number of points."
  [shape1 shape2 t]
  (let [pts1 (:points shape1)
        pts2 (:points shape2)
        n1 (count pts1)
        n2 (count pts2)]
    (when (= n1 n2)
      (let [lerp-pt (fn [[x1 y1] [x2 y2]]
                      [(+ x1 (* t (- x2 x1)))
                       (+ y1 (* t (- y2 y1)))])
            new-pts (mapv lerp-pt pts1 pts2)]
        (make-shape new-pts {:centered? (:centered? shape1)})))))

(defn make-lerp-fn
  "Create a transform function that interpolates from shape1 to shape2.
   Returns (fn [_ t]) that ignores the first arg and returns lerp-shape."
  [shape1 shape2]
  (fn [_ t]
    (lerp-shape shape1 shape2 t)))

;; ============================================================
;; Shape transformation for stamping
;; ============================================================

(defn transform-shape-to-plane
  "Transform a 2D shape to 3D points on a plane.

   The plane is defined by:
   - origin: turtle position
   - x-axis: perpendicular to turtle heading, in the 'right' direction
   - y-axis: perpendicular to both (derived)
   - The shape is placed on the plane perpendicular to turtle heading

   For centered shapes, the center is at turtle position.
   For non-centered shapes, the first point is at turtle position."
  [shape turtle-pos turtle-heading turtle-up]
  (let [points (:points shape)
        centered? (:centered? shape)
        ;; The plane is perpendicular to heading
        ;; x-axis of plane = right vector (heading × up)
        ;; y-axis of plane = up vector
        [hx hy hz] turtle-heading
        [ux uy uz] turtle-up
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
        plane-y turtle-up
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
                  [ox oy oz] turtle-pos
                  [xx xy xz] plane-x
                  [yx yy yz] plane-y]
              [(+ ox (* px' xx) (* py' yx))
               (+ oy (* px' xy) (* py' yy))
               (+ oz (* px' xz) (* py' yz))]))
          points)))
