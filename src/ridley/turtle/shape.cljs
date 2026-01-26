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
   Points should form a closed polygon (CCW winding for outer, CW for holes).

   Options:
   - :centered? true  - shape is centered at turtle position (default for circle/rect)
   - :centered? false - shape starts at turtle position (default for custom shapes)
   - :holes [...]     - vector of hole contours (each is a vector of [x y] points, CW winding)
   - :preserve-position? true - use raw 2D coords without offset (for text with built-in spacing)
   - :align-to-heading? true - 2D x maps to heading direction (for text along path)
   - :flip-plane-x? true - negate plane-x axis (equivalent to tr 180 before stamp)"
  ([points] (make-shape points {:centered? false}))
  ([points opts]
   (cond-> {:type :shape
            :points (vec points)
            :centered? (:centered? opts false)}
     (:holes opts) (assoc :holes (vec (map vec (:holes opts))))
     (:preserve-position? opts) (assoc :preserve-position? true)
     (:align-to-heading? opts) (assoc :align-to-heading? true)
     (:flip-plane-x? opts) (assoc :flip-plane-x? true))))

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

(defn- points-close?
  "Check if two 2D points are very close (within epsilon)."
  [[x1 y1] [x2 y2]]
  (let [eps 0.001
        dx (- x2 x1)
        dy (- y2 y1)]
    (< (+ (* dx dx) (* dy dy)) (* eps eps))))

(defn shape-from-recording
  "Extract shape from recorded turtle state.
   The path forms the shape outline.
   Removes duplicate closing point if present."
  [rec-state]
  (let [path (:path rec-state)
        ;; Remove last point if it's essentially the same as the first (closed polygon)
        clean-path (if (and (>= (count path) 4)
                           (points-close? (first path) (last path)))
                     (vec (butlast path))
                     path)]
    (when (>= (count clean-path) 3)
      (make-shape clean-path {:centered? false}))))

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

(defn- compute-plane-params
  "Compute plane transformation parameters from turtle state and shape options.
   Returns {:plane-x :plane-y :offset} for transforming 2D points to 3D."
  [shape turtle-pos turtle-heading turtle-up]
  (let [points (:points shape)
        centered? (:centered? shape)
        preserve-position? (:preserve-position? shape)
        align-to-heading? (:align-to-heading? shape)
        flip-plane-x? (:flip-plane-x? shape)
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
        ;; Flip plane-x if requested
        [rx ry rz] (if flip-plane-x?
                     [(- rx) (- ry) (- rz)]
                     [rx ry rz])
        ;; Choose plane axes
        [plane-x plane-y] (if align-to-heading?
                           [turtle-heading turtle-up]
                           [[rx ry rz] turtle-up])
        ;; Calculate offset
        offset (cond
                 preserve-position? [0 0]
                 centered? [0 0]
                 :else (let [[fx fy] (first points)]
                         [(- fx) (- fy)]))]
    {:plane-x plane-x
     :plane-y plane-y
     :offset offset
     :origin turtle-pos}))

(defn- transform-points-to-plane
  "Transform 2D points to 3D using precomputed plane parameters."
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

(defn transform-shape-to-plane
  "Transform a 2D shape to 3D points on a plane.

   The plane is defined by:
   - origin: turtle position
   - x-axis: perpendicular to turtle heading, in the 'right' direction
   - y-axis: perpendicular to both (derived)
   - The shape is placed on the plane perpendicular to turtle heading

   For centered shapes, the center is at turtle position.
   For non-centered shapes, the first point is at turtle position.
   For preserve-position? shapes, raw 2D coords are used (for text with built-in spacing).
   For align-to-heading? shapes, 2D x maps to heading (for text progression along path)."
  [shape turtle-pos turtle-heading turtle-up]
  (let [params (compute-plane-params shape turtle-pos turtle-heading turtle-up)]
    (transform-points-to-plane (:points shape) params)))

(defn transform-shape-with-holes-to-plane
  "Transform a 2D shape with holes to 3D points on a plane.
   Returns {:outer <3D-points> :holes [<3D-points> ...]}"
  [shape turtle-pos turtle-heading turtle-up]
  (let [params (compute-plane-params shape turtle-pos turtle-heading turtle-up)
        outer-3d (transform-points-to-plane (:points shape) params)
        holes-3d (when-let [holes (:holes shape)]
                   (mapv #(transform-points-to-plane % params) holes))]
    {:outer outer-3d
     :holes holes-3d}))
