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
;; Shape transformations
;; ============================================================

(defn translate-shape
  "Translate a shape by [dx dy]. Returns a new shape with translated points.
   Useful for positioning shapes before revolve (which uses X as radial distance)."
  [shape dx dy]
  (when (shape? shape)
    (let [translate-point (fn [[x y]] [(+ x dx) (+ y dy)])
          new-points (mapv translate-point (:points shape))
          new-holes (when (:holes shape)
                      (mapv (fn [hole] (mapv translate-point hole)) (:holes shape)))]
      (cond-> (assoc shape :points new-points)
        new-holes (assoc :holes new-holes)))))

(defn scale-shape
  "Scale a shape by [sx sy]. Returns a new shape with scaled points."
  [shape sx sy]
  (when (shape? shape)
    (let [scale-point (fn [[x y]] [(* x sx) (* y sy)])
          new-points (mapv scale-point (:points shape))
          new-holes (when (:holes shape)
                      (mapv (fn [hole] (mapv scale-point hole)) (:holes shape)))]
      (cond-> (assoc shape :points new-points)
        new-holes (assoc :holes new-holes)))))

(defn reverse-shape
  "Reverse the winding order of a shape's points.
   This flips the normal direction when the shape is extruded/revolved.
   Use when normals are pointing the wrong way."
  [shape]
  (when (shape? shape)
    (let [new-points (vec (reverse (:points shape)))
          ;; Holes also need to be reversed to maintain relative winding
          new-holes (when (:holes shape)
                      (mapv (fn [hole] (vec (reverse hole))) (:holes shape)))]
      (cond-> (assoc shape :points new-points)
        new-holes (assoc :holes new-holes)))))

(defn- signed-area-2d
  "Calculate the signed area of a 2D polygon.
   Positive = CCW (counter-clockwise), Negative = CW (clockwise).
   Uses the shoelace formula."
  [points]
  (let [n (count points)]
    (if (< n 3)
      0
      (/ (reduce
          (fn [sum i]
            (let [[x1 y1] (nth points i)
                  [x2 y2] (nth points (mod (inc i) n))]
              (+ sum (- (* x1 y2) (* x2 y1)))))
          0
          (range n))
         2))))

(defn- ensure-ccw
  "Ensure points are in counter-clockwise order (positive signed area).
   Reverses points if they are clockwise."
  [points]
  (if (neg? (signed-area-2d points))
    (vec (reverse points))
    points))

(defn path-to-shape
  "Convert a path to a 2D shape by tracing the commands.
   Extracts X and Y coordinates from the 3D path.
   Automatically ensures CCW winding for correct normals.
   Useful for creating revolve profiles from recorded paths."
  [path]
  (when (and (map? path) (= :path (:type path)))
    (let [commands (:commands path)
          ;; Trace the path to collect 2D points
          result (reduce
                  (fn [{:keys [pos heading points]} cmd]
                    (case (:cmd cmd)
                      :f (let [dist (first (:args cmd))
                               ;; Use only XY components of heading
                               hx (first heading)
                               hy (second heading)
                               new-pos [(+ (first pos) (* hx dist))
                                        (+ (second pos) (* hy dist))]]
                           {:pos new-pos
                            :heading heading
                            :points (conj points new-pos)})
                      :th (let [angle (first (:args cmd))
                                rad (* angle (/ Math/PI 180))
                                hx (first heading)
                                hy (second heading)
                                cos-a (Math/cos rad)
                                sin-a (Math/sin rad)
                                new-heading [(- (* hx cos-a) (* hy sin-a))
                                             (+ (* hx sin-a) (* hy cos-a))
                                             0]]
                            {:pos pos :heading new-heading :points points})
                      :set-heading (let [[h _up] (:args cmd)
                                         ;; Extract XY from 3D heading
                                         new-heading [(first h) (second h) 0]]
                                     {:pos pos :heading new-heading :points points})
                      ;; Skip unknown commands
                      {:pos pos :heading heading :points points}))
                  {:pos [0 0] :heading [1 0 0] :points [[0 0]]}
                  commands)
          raw-points (:points result)]
      (when (>= (count raw-points) 3)
        ;; Ensure CCW winding for correct outward-facing normals
        (make-shape (ensure-ccw raw-points) {:centered? false})))))

;; ============================================================
;; Stroke shape: offset a path into a 2D outline
;; ============================================================

(defn- v2-mag [[x y]]
  (Math/sqrt (+ (* x x) (* y y))))

(defn- v2-normalize [[x y]]
  (let [m (v2-mag [x y])]
    (if (< m 0.0001) [0 0] [(/ x m) (/ y m)])))

(defn- v2-perp
  "Left-side perpendicular (rotate 90° CCW)."
  [[x y]]
  [(- y) x])

(defn- line-intersect-2d
  "Intersect two lines defined by point+direction.
   Returns parameter t along line1 (p1 + t*d1), or nil if parallel."
  [[p1x p1y] [d1x d1y] [p2x p2y] [d2x d2y]]
  (let [denom (- (* d1x d2y) (* d1y d2x))]
    (when (> (Math/abs denom) 0.0001)
      (let [dx (- p2x p1x)
            dy (- p2y p1y)]
        (/ (- (* dx d2y) (* dy d2x)) denom)))))

(defn- arc-pts
  "Generate arc points around center from angle start-a to end-a (radians).
   Includes start and end points. n = number of segments."
  [[cx cy] radius start-a end-a n]
  (mapv (fn [i]
          (let [t (/ i n)
                a (+ start-a (* t (- end-a start-a)))]
            [(+ cx (* radius (Math/cos a)))
             (+ cy (* radius (Math/sin a)))]))
        (range (inc n))))

(defn- path-to-2d-waypoints
  "Extract 2D waypoints (position + heading direction) from a path.
   Projects XY from 3D turtle state. Returns vector of {:pos [x y] :dir [dx dy]}."
  [path]
  (when (and (map? path) (= :path (:type path)))
    (let [commands (:commands path)]
      (:waypoints
       (reduce
        (fn [{:keys [pos heading waypoints]} cmd]
          (case (:cmd cmd)
            :f (let [dist (first (:args cmd))
                     hx (first heading) hy (second heading)
                     new-pos [(+ (first pos) (* hx dist))
                              (+ (second pos) (* hy dist))]]
                 {:pos new-pos :heading heading
                  :waypoints (conj waypoints {:pos new-pos :dir heading})})
            :th (let [angle (first (:args cmd))
                      rad (* angle (/ Math/PI 180))
                      hx (first heading) hy (second heading)
                      cos-a (Math/cos rad) sin-a (Math/sin rad)]
                  {:pos pos
                   :heading [(- (* hx cos-a) (* hy sin-a))
                             (+ (* hx sin-a) (* hy cos-a))]
                   :waypoints waypoints})
            :set-heading (let [[h _] (:args cmd)]
                           {:pos pos
                            :heading [(first h) (second h)]
                            :waypoints waypoints})
            {:pos pos :heading heading :waypoints waypoints}))
        {:pos [0 0] :heading [1 0] :waypoints [{:pos [0 0] :dir [1 0]}]}
        commands)))))

(defn- offset-point-2d
  "Offset a 2D point along a normal by a signed distance."
  [[px py] [nx ny] dist]
  [(+ px (* nx dist)) (+ py (* ny dist))])

(defn- miter-point-2d
  "Compute miter intersection point for an offset side.
   sign: +1 for left, -1 for right. Returns the miter point or nil."
  [p n1 n2 d1 d2 half-w sign]
  (let [off1 (offset-point-2d p n1 (* sign half-w))
        off2 (offset-point-2d p n2 (* sign half-w))
        t (line-intersect-2d off1 d1 off2 d2)]
    (when t
      [(+ (first off1) (* t (first d1)))
       (+ (second off1) (* t (second d1)))])))

(defn- compute-offset-pts
  "Compute offset points for one side of the stroke.
   sign: +1 for left side, -1 for right side.
   At a left turn (cross-z > 0), the outside of the curve is the right side.
   At a right turn (cross-z < 0), the outside is the left side."
  [wps seg-dirs seg-normals half-w join-mode miter-limit sign]
  (let [n (count wps)
        n-segs (count seg-dirs)
        is-outer? (if (pos? sign)
                    (fn [cross-z] (neg? cross-z))   ;; left: outer on right turns
                    (fn [cross-z] (pos? cross-z)))]  ;; right: outer on left turns
    (loop [i 0, pts []]
      (if (>= i n)
        pts
        (let [p (:pos (nth wps i))]
          (cond
            ;; First or last point: simple offset
            (or (zero? i) (= i (dec n)))
            (let [seg-idx (if (zero? i) 0 (dec n-segs))
                  [nx ny] (nth seg-normals seg-idx)]
              (recur (if (zero? i) 1 (inc i))
                     (conj pts (offset-point-2d p [nx ny] (* sign half-w)))))

            ;; Interior vertex: handle join
            :else
            (let [n1 (nth seg-normals (dec i))
                  n2 (nth seg-normals i)
                  d1 (nth seg-dirs (dec i))
                  d2 (nth seg-dirs i)
                  cross-z (- (* (first d1) (second d2))
                             (* (second d1) (first d2)))
                  outer? (is-outer? cross-z)]
              (cond
                ;; Inner side: always miter (converging)
                (not outer?)
                (let [mp (miter-point-2d p n1 n2 d1 d2 half-w sign)]
                  (recur (inc i)
                         (conj pts (or mp (offset-point-2d p n1 (* sign half-w))))))

                ;; Outer side with :bevel
                (= join-mode :bevel)
                (let [p1 (offset-point-2d p n1 (* sign half-w))
                      p2 (offset-point-2d p n2 (* sign half-w))]
                  (recur (inc i) (-> pts (conj p1) (conj p2))))

                ;; Outer side with :round
                (= join-mode :round)
                (let [sn1 (if (pos? sign) n1 [(- (first n1)) (- (second n1))])
                      sn2 (if (pos? sign) n2 [(- (first n2)) (- (second n2))])
                      a1 (Math/atan2 (second sn1) (first sn1))
                      a2 (Math/atan2 (second sn2) (first sn2))
                      ;; Ensure correct arc direction
                      a2 (if (pos? sign)
                           (if (< a2 a1) (+ a2 (* 2 Math/PI)) a2)
                           (if (> a2 a1) (- a2 (* 2 Math/PI)) a2))
                      arc (arc-pts p half-w a1 a2 8)]
                  (recur (inc i) (into pts arc)))

                ;; Outer side with :miter (default)
                :else
                (let [mp (miter-point-2d p n1 n2 d1 d2 half-w sign)]
                  (if (and mp (<= (v2-mag [(- (first mp) (first p))
                                           (- (second mp) (second p))])
                               (* miter-limit half-w)))
                    (recur (inc i) (conj pts mp))
                    ;; Miter limit exceeded: bevel fallback
                    (let [p1 (offset-point-2d p n1 (* sign half-w))
                          p2 (offset-point-2d p n2 (* sign half-w))]
                      (recur (inc i) (-> pts (conj p1) (conj p2))))))))))))))

(defn ^:export stroke-shape
  "Create a 2D outline shape by stroking a path with a given width.

   (stroke-shape path width)
   (stroke-shape path width :start-cap :round :end-cap :flat :join :miter)

   Options:
   - :start-cap  :flat (default), :round, :square
   - :end-cap    :flat (default), :round, :square
   - :join       :miter (default), :bevel, :round
   - :miter-limit  maximum miter ratio before falling back to bevel (default 4)

   Returns a shape suitable for extrude, revolve, etc."
  [path width & {:keys [start-cap end-cap join miter-limit]
                 :or {start-cap :flat end-cap :flat join :miter miter-limit 4}}]
  (assert (number? width) "stroke-shape requires a width argument: (stroke-shape path width)")
  (let [wps (path-to-2d-waypoints path)
        n (count wps)
        half-w (/ width 2.0)]
    (when (and wps (>= n 2))
      (let [seg-dirs (mapv (fn [i]
                             (let [p0 (:pos (nth wps i))
                                   p1 (:pos (nth wps (inc i)))]
                               (v2-normalize [(- (first p1) (first p0))
                                              (- (second p1) (second p0))])))
                           (range (dec n)))
            seg-normals (mapv v2-perp seg-dirs)
            n-segs (count seg-dirs)

            left-pts (compute-offset-pts wps seg-dirs seg-normals half-w join miter-limit +1)
            right-pts (compute-offset-pts wps seg-dirs seg-normals half-w join miter-limit -1)

            ;; End cap
            end-pos (:pos (nth wps (dec n)))
            end-dir (nth seg-dirs (dec n-segs))
            end-cap-pts
            (case end-cap
              :round (let [n-left (v2-perp end-dir)
                           a-start (Math/atan2 (second n-left) (first n-left))
                           a-end (- a-start Math/PI)]
                       (rest (arc-pts end-pos half-w a-start a-end 8)))
              :square (let [[nx ny] (v2-perp end-dir)
                            ext [(+ (first end-pos) (* (first end-dir) half-w))
                                 (+ (second end-pos) (* (second end-dir) half-w))]]
                        [[(+ (first ext) (* nx half-w))
                          (+ (second ext) (* ny half-w))]
                         [(- (first ext) (* nx half-w))
                          (- (second ext) (* ny half-w))]])
              [])

            ;; Start cap
            start-pos (:pos (nth wps 0))
            start-dir (nth seg-dirs 0)
            start-cap-pts
            (case start-cap
              :round (let [n-right [(second start-dir) (- (first start-dir))]
                           a-start (Math/atan2 (second n-right) (first n-right))
                           a-end (+ a-start Math/PI)]
                       (rest (arc-pts start-pos half-w a-start a-end 8)))
              :square (let [[nx ny] (v2-perp start-dir)
                            ext [(- (first start-pos) (* (first start-dir) half-w))
                                 (- (second start-pos) (* (second start-dir) half-w))]]
                        [[(- (first ext) (* nx half-w))
                          (- (second ext) (* ny half-w))]
                         [(+ (first ext) (* nx half-w))
                          (+ (second ext) (* ny half-w))]])
              [])

            ;; Combine: left (forward) + end-cap + right (reversed) + start-cap
            all-pts (-> (vec left-pts)
                        (into end-cap-pts)
                        (into (rseq (vec right-pts)))
                        (into start-cap-pts))]
        (make-shape (ensure-ccw all-pts) {:centered? true})))))

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

(defn replay-path-to-recording
  "Replay a path's commands into a recording state.
   Used by the shape macro to incorporate path variables.
   Returns the updated recording state."
  [rec-state path]
  (if-not (and (map? path) (= :path (:type path)))
    rec-state
    (reduce
     (fn [state cmd-map]
       (let [cmd (:cmd cmd-map)
             args (:args cmd-map)]
         (case cmd
           :f (rec-f state (first args))
           :th (rec-th state (first args))
           :set-heading
           (let [h (first args)
                 hx (first h)
                 hy (second h)
                 angle (Math/atan2 hy hx)]
             (assoc state :heading [(Math/cos angle) (Math/sin angle)]))
           ;; Skip unknown commands
           state)))
     rec-state
     (:commands path))))

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
   Removes duplicate closing point if present.
   Ensures CCW winding for correct outward-facing normals."
  [rec-state]
  (let [path (:path rec-state)
        ;; Remove last point if it's essentially the same as the first (closed polygon)
        clean-path (if (and (>= (count path) 4)
                           (points-close? (first path) (last path)))
                     (vec (butlast path))
                     path)]
    (when (>= (count clean-path) 3)
      ;; Ensure CCW winding for correct outward-facing normals
      (make-shape (ensure-ccw clean-path) {:centered? false}))))

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
