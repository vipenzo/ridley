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
   - Turtle moves (extrusion is a side-effect)"
  (:require [ridley.turtle.extrusion :as extrusion]))

;; Forward declarations: ensure-path-2d (the planar-consumer normalizer) and the
;; 2D/3D tracers are defined later but referenced by earlier planar consumers.
(declare ensure-path-2d path-to-2d-waypoints path-to-3d-waypoints)

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

(defn ^:export set-image
  "Attach a reference image to a shape. The image becomes visible only when the
   shape is stamped, coplanar with the profile, in the same 2D frame you trace in.

   - path: absolute file path to an image (desktop only — read via the Rust server).
   - width: image width in the shape's local 2D units (calibrates the scale).
   - offset-x, offset-y: lower-left corner of the image in the shape's 2D frame.

   The image height is derived from its aspect ratio (no distortion)."
  [shape path width offset-x offset-y]
  (assoc shape :image {:path path
                       :width width
                       :offset [offset-x offset-y]}))

(defn ^:export image-board
  "Build a rectangular tracing board carrying a reference image, ready for
   edit-path-2d. Unlike a bare (set-image (rect …) …), the board is
   preserve-position?, so it keeps the TURTLE fixed at [0 0]: stamping it places
   the rect relative to the turtle by [orx ory] and leaves the turtle exactly on
   the point you want to become the extruded mesh's creation pose (typically OFF
   the contour you will trace).

   - path:         absolute image file path (desktop only — read via Rust server).
   - scale-factor: image width in Ridley units (calibrates scale — verify with ruler).
   - [imx imy]:    image lower-left corner RELATIVE to the rect's lower-left corner
                   (frames which part of the photo shows in the window). Because it
                   is relative to the rect, sliding [orx ory] carries the image along
                   — once framed, you don't re-correct [imx imy].
   - [orx ory]:    rect lower-left corner relative to the turtle (moves rect + image
                   together). Turtle-centered → [(- (/ w 2)) (- (/ h 2))].
   - [w h]:        rect dimensions.

   Tweak scale-factor / [imx imy] / [orx ory] until the object sits where you want
   and the turtle [0 0] is on your chosen creation-pose point. Then trace with
   edit-path-2d and extrude with the matching :preserve-position flag:
     (extrude (path-to-shape outline :preserve-position true) (f depth))"
  [path scale-factor [imx imy] [orx ory] [w h]]
  (-> (make-shape [[orx ory] [(+ orx w) ory] [(+ orx w) (+ ory h)] [orx (+ ory h)]]
                  {:preserve-position? true})
      (set-image path scale-factor (+ orx imx) (+ ory imy))))

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

(defn poly-shape
  "Create a shape from flat x y coordinate pairs.
   Origin [0,0] is anchored to turtle position.
   Accepts: (poly 0 0 5 0 5 5), (poly [0 0 5 0 5 5]), or (poly v)"
  ([x] (poly-shape x nil))
  ([x y & more]
   (let [nums (if (and (nil? y) (sequential? x))
                x
                (cons x (cons y more)))
         n (count nums)]
     (when (odd? n)
       (throw (js/Error. (str "poly: odd number of coordinates (" n "). Coordinates must be x y pairs."))))
     (when (< n 6)
       (throw (js/Error. (str "poly: need at least 3 points (6 coordinates), got " n "."))))
     (let [points (mapv vec (partition 2 nums))]
       (make-shape points {:centered? true})))))

(defn ngon-shape
  "Create a regular n-sided polygon centered at origin.
   n: number of sides (e.g., 6 for hexagon)
   radius: distance from center to vertices"
  [n radius]
  (when-not (and (number? n) (number? radius))
    (throw (js/Error. (str "polygon: expected (polygon sides radius), got ("
                           (pr-str n) " " (pr-str radius) ")."
                           (when (sequential? n)
                             " For custom points use (poly x1 y1 x2 y2 ...) or (make-shape [[x y] ...]).")))))
  (when (< n 3)
    (throw (js/Error. (str "polygon: need at least 3 sides, got " n "."))))
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
   Useful for positioning shapes before revolve (which uses X as radial distance).
   Also accepts a vector of shapes (e.g. from text-shape, project-mesh)."
  [shape dx dy]
  (cond
    (sequential? shape) (mapv #(translate-shape % dx dy) shape)
    (shape? shape)
    (let [translate-point (fn [[x y]] [(+ x dx) (+ y dy)])
          new-points (mapv translate-point (:points shape))
          new-holes (when (:holes shape)
                      (mapv (fn [hole] (mapv translate-point hole)) (:holes shape)))]
      (cond-> (assoc shape :points new-points)
        new-holes (assoc :holes new-holes)))))

(defn scale-shape
  "Scale a shape by [sx sy] or uniformly by s, around its centroid.
   Returns a new shape with scaled points.
   Also accepts a vector of shapes (e.g. from text-shape, project-mesh) —
   each shape is scaled around its own centroid."
  ([shape s] (scale-shape shape s s))
  ([shape sx sy]
   (cond
     (sequential? shape) (mapv #(scale-shape % sx sy) shape)
     (shape? shape)
     (let [pts (:points shape)
           n (count pts)
           cx (/ (reduce + (map first pts)) n)
           cy (/ (reduce + (map second pts)) n)
           scale-point (fn [[x y]]
                         [(+ cx (* (- x cx) sx))
                          (+ cy (* (- y cy) sy))])
           new-points (mapv scale-point pts)
           new-holes (when (:holes shape)
                       (mapv (fn [hole] (mapv scale-point hole)) (:holes shape)))]
       (cond-> (assoc shape :points new-points)
         new-holes (assoc :holes new-holes))))))

(defn reverse-shape
  "Reverse the winding order of a shape's points.
   This flips the normal direction when the shape is extruded/revolved.
   Use when normals are pointing the wrong way.
   Also accepts a vector of shapes."
  [shape]
  (cond
    (sequential? shape) (mapv reverse-shape shape)
    (shape? shape)
    (let [new-points (vec (reverse (:points shape)))
          ;; Holes also need to be reversed to maintain relative winding
          new-holes (when (:holes shape)
                      (mapv (fn [hole] (vec (reverse hole))) (:holes shape)))]
      (cond-> (assoc shape :points new-points)
        new-holes (assoc :holes new-holes)))))

;; ============================================================
;; Fillet and Chamfer
;; ============================================================

(defn- v2-dist
  "Euclidean distance between two 2D points."
  [[x1 y1] [x2 y2]]
  (let [dx (- x2 x1) dy (- y2 y1)]
    (Math/sqrt (+ (* dx dx) (* dy dy)))))

(defn- v2-lerp
  "Linear interpolation between two 2D points."
  [[x1 y1] [x2 y2] t]
  [(+ x1 (* t (- x2 x1)))
   (+ y1 (* t (- y2 y1)))])

(defn- corner-angle
  "Angle (in radians) between edges prev->curr and curr->next.
   Returns 0..PI. Small = sharp corner, PI = straight (no corner)."
  [[px py] [cx cy] [nx ny]]
  (let [ax (- px cx) ay (- py cy)
        bx (- nx cx) by (- ny cy)
        dot (+ (* ax bx) (* ay by))
        ma (Math/sqrt (+ (* ax ax) (* ay ay)))
        mb (Math/sqrt (+ (* bx bx) (* by by)))
        denom (* ma mb)]
    (if (< denom 1e-10)
      Math/PI
      (Math/acos (max -1 (min 1 (/ dot denom)))))))

(defn- chamfer-corner
  "Replace a corner vertex with two points, cutting the corner at distance d
   along each adjacent edge. Returns [point-a point-b] or nil if edge too short."
  [prev curr nxt d]
  (let [d-prev (v2-dist prev curr)
        d-next (v2-dist curr nxt)]
    (when (and (> d-prev (* d 1.001)) (> d-next (* d 1.001)))
      (let [t-prev (/ d d-prev)
            t-next (/ d d-next)
            pa (v2-lerp curr prev t-prev)
            pb (v2-lerp curr nxt t-next)]
        [pa pb]))))

(defn- fillet-corner-arc
  "Replace a corner with a true circular arc.
   Computes the inscribed circle center and sweeps an arc from pa to pb."
  [prev curr nxt d n]
  (let [d-prev (v2-dist prev curr)
        d-next (v2-dist curr nxt)]
    (when (and (> d-prev (* d 1.001)) (> d-next (* d 1.001)))
      (let [t-prev (/ d d-prev)
            t-next (/ d d-next)
            pa (v2-lerp curr prev t-prev)
            pb (v2-lerp curr nxt t-next)
            half-angle (/ (corner-angle prev curr nxt) 2)
            sin-ha (Math/sin half-angle)]
        (if (< sin-ha 1e-6)
          [curr]
          (let [radius (/ d (Math/tan half-angle))
                [ax ay] [(- (first prev) (first curr)) (- (second prev) (second curr))]
                [bx by] [(- (first nxt) (first curr)) (- (second nxt) (second curr))]
                ma (Math/sqrt (+ (* ax ax) (* ay ay)))
                mb (Math/sqrt (+ (* bx bx) (* by by)))
                nax (/ ax ma) nay (/ ay ma)
                nbx (/ bx mb) nby (/ by mb)
                bsx (+ nax nbx) bsy (+ nay nby)
                bm (Math/sqrt (+ (* bsx bsx) (* bsy bsy)))
                center-dist (/ radius sin-ha)
                cx (+ (first curr) (* (/ bsx bm) center-dist))
                cy (+ (second curr) (* (/ bsy bm) center-dist))
                a-start (Math/atan2 (- (second pa) cy) (- (first pa) cx))
                a-end (Math/atan2 (- (second pb) cy) (- (first pb) cx))
                diff (- a-end a-start)
                diff (cond
                       (> diff Math/PI) (- diff (* 2 Math/PI))
                       (< diff (- Math/PI)) (+ diff (* 2 Math/PI))
                       :else diff)]
            (vec (for [i (range (inc n))]
                   (let [t (/ i n)
                         a (+ a-start (* t diff))]
                     [(+ cx (* radius (Math/cos a)))
                      (+ cy (* radius (Math/sin a)))])))))))))

(defn- apply-corner-op
  "Apply a corner operation (chamfer or fillet) to a polygon's points.
   op-fn: (fn [prev curr next] -> [replacement-points] or nil)
   indices: set of vertex indices to process, or nil for all.
   Returns new points vector."
  [points op-fn indices]
  (let [n (count points)]
    (into []
          (mapcat
           (fn [i]
             (if (and indices (not (contains? indices i)))
               [(nth points i)]
               (let [prev (nth points (mod (dec (+ i n)) n))
                     curr (nth points i)
                     nxt  (nth points (mod (inc i) n))
                     replacement (op-fn prev curr nxt)]
                 (or replacement [curr]))))
           (range n)))))

(defn ^:export chamfer-shape
  "Cut corners of a 2D shape by replacing each vertex with a flat cut
   at distance d along each adjacent edge.

   Options:
   - :indices [0 2 5] — only chamfer specific vertex indices (default: all)

   Usage:
     (chamfer-shape (rect 40 20) 3)         ; all corners
     (chamfer-shape (rect 40 20) 3 :indices [0 1])  ; only first two corners"
  [shape d & {:keys [indices]}]
  (when (and (map? shape) (= :shape (:type shape)) (pos? d))
    (let [idx-set (when indices (set indices))
          op-fn (fn [prev curr nxt]
                  (chamfer-corner prev curr nxt d))
          new-points (apply-corner-op (:points shape) op-fn idx-set)
          new-holes (when (:holes shape)
                      (mapv (fn [hole]
                              (apply-corner-op hole
                                               (fn [prev curr nxt] (chamfer-corner prev curr nxt d))
                                               nil))
                            (:holes shape)))]
      (cond-> (assoc shape :points new-points)
        new-holes (assoc :holes new-holes)))))

(defn ^:export fillet-shape
  "Round corners of a 2D shape with circular arcs at distance d from each vertex.

   Options:
   - :segments n — arc segments per corner (default: (default-segments 0.5),
     i.e. half the global curve resolution — corners are local details that
     usually need less detail than full curves)
   - :indices [0 2 5] — only fillet specific vertex indices (default: all)

   Usage:
     (fillet-shape (rect 40 20) 3)                     ; all corners, default segs
     (fillet-shape (rect 40 20) 3 :segments 16)        ; explicit override
     (fillet-shape (rect 40 20) 3 :indices [0 1])      ; only first two corners"
  [shape d & {:keys [segments indices]}]
  (when (and (map? shape) (= :shape (:type shape)) (pos? d))
    (let [segments (or segments (extrusion/default-segments 0.5))
          idx-set (when indices (set indices))
          op-fn (fn [prev curr nxt]
                  (fillet-corner-arc prev curr nxt d segments))
          new-points (apply-corner-op (:points shape) op-fn idx-set)
          new-holes (when (:holes shape)
                      (mapv (fn [hole]
                              (apply-corner-op hole
                                               (fn [prev curr nxt]
                                                 (fillet-corner-arc prev curr nxt d segments))
                                               nil))
                            (:holes shape)))]
      (cond-> (assoc shape :points new-points)
        new-holes (assoc :holes new-holes)))))

;; ============================================================
;; Winding order utilities
;; ============================================================

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
  "Ensure points are in counter-clockwise order (positive signed area), keeping the
   FIRST vertex fixed. A clockwise ring is flipped by reversing all points AFTER the
   first (`[p0 p1 … pn]` → `[p0 pn … p1]`), which gives the same ring traversed the
   other way but with the same start vertex — so the shape's seam / origin stays at
   the trace start instead of jumping to the last node for clockwise-wound paths."
  [points]
  (if (neg? (signed-area-2d points))
    (into [(first points)] (reverse (rest points)))
    points))

(defn path-has-mark?
  "True if a path seeds at least one (mark …). Used to decide whether to carry
   the source path on a derived shape so its marks can become mesh anchors."
  [path]
  (boolean (and (map? path) (= :path (:type path))
                (some #(= :mark (:cmd %)) (:commands path)))))

(defn- compute-mark-refs
  "Match a path's marks to vertices of the FINAL shape points, recording each as
   {:vertex i :head-offset deg}. The mark then IS point i, so it rides any
   shape-fn that preserves point indexing (taper/twist/displace); the heading is
   stored as an angle off the local edge tangent so it tracks too. Uses the
   injected resolver (handles sidetrip/tv/tr). In path-to-shape every mark sits
   at an f-endpoint = a vertex, so all match; unmatched marks are skipped."
  [path pts]
  (when-let [rf @extrusion/resolve-marks-ref]
    (let [marks (if (= :2d (:species path))
                  ;; :2d trace lives in (right,up); project marks to (a,b) so they
                  ;; match the projected shape points (same as ensure-path-2d).
                  (extrusion/resolve-2d-source-anchors rf path)
                  (rf {:position [0 0 0] :heading [1 0 0] :up [0 0 1]} path))
          n (count pts)
          eps2 (* 1e-4 1e-4)]
      (->> marks
           (keep (fn [[nm pose]]
                   (let [[mx my] (:position pose)
                         [hx hy] (:heading pose)
                         idx (first (filter (fn [i]
                                              (let [[px py] (nth pts i)]
                                                (< (+ (* (- px mx) (- px mx))
                                                      (* (- py my) (- py my)))
                                                   eps2)))
                                            (range n)))]
                     (when idx
                       (let [fwd? (< (inc idx) n)
                             [px py] (nth pts idx)
                             [bx by] (nth pts (if fwd? (inc idx) (max 0 (dec idx))))
                             dx (- bx px) dy (- by py)
                             m (Math/sqrt (+ (* dx dx) (* dy dy)))
                             [tx ty] (cond (not (pos? m)) [1.0 0.0]
                                           fwd?           [(/ dx m) (/ dy m)]
                                           :else          [(/ (- dx) m) (/ (- dy) m)])
                             off (* (/ 180 Math/PI)
                                    (Math/atan2 (- (* tx hy) (* ty hx))
                                                (+ (* tx hx) (* ty hy))))]
                         [nm {:vertex idx :head-offset off}])))))
           (into {})))))

(defn- drop-closing-dup
  "For a CLOSED path (edit-path-2d's :closed? flag), the baked closing segment runs the
   trace back to node 0, leaving a trailing vertex at (≈) the start; make-shape closes
   implicitly, so that vertex would only add a degenerate seam edge. Drop it. The bake
   rounds coordinates, so the return is approximate — use a generous tolerance, safe
   because the flag tells us this last vertex IS the seam return. Open paths (no flag)
   are untouched."
  [pts closed?]
  (if (and closed? (> (count pts) 1)
           (let [[ax ay] (first pts) [bx by] (peek pts)]
             (< (+ (* (- ax bx) (- ax bx)) (* (- ay by) (- ay by))) 1.0)))
    (subvec pts 0 (dec (count pts)))
    pts))

(defn- leading-move-to-xy
  "If the first command of `commands` is a (move-to [x y …]), return [x y];
   otherwise [0 0]. Lets a path anchor its trace at an absolute start point
   instead of the implicit origin (used by edit-path's baked output)."
  [commands]
  (let [c (first commands)]
    (if (and c (= :move-to (:cmd c)))
      (let [t (first (:args c))]
        [(first t) (second t)])
      [0 0])))

(defn path-to-shape
  "Convert a path to a 2D shape by tracing the commands.
   Extracts X and Y coordinates from the 3D path.
   Automatically ensures CCW winding for correct normals.
   Useful for creating revolve profiles from recorded paths.

   A leading (move-to [x y]) anchors the trace at that absolute point (no spurious
   [0 0] vertex), so a path baked by edit-path lands in the same 2D frame as the
   board it was traced over.

   If the path seeds marks, the source path is carried on the shape as
   :source-path so extrude/loft/revolve can resolve those marks into mesh
   anchors (in the section/base-face frame).

   :preserve-position true (opt-in) keeps the path's frame origin [0 0] as the
   extruded/stamped mesh's creation pose (instead of re-origining on the first
   vertex). Use it for image-traced outlines so the creation pose lands on the
   turtle point you set up the board around — typically OFF the contour."
  [path & {:keys [preserve-position]}]
  (when (and (map? path) (= :path (:type path)))
    (let [raw-points (drop-closing-dup (mapv :pos (ensure-path-2d path)) (:closed? path))]
      (when (>= (count raw-points) 3)
        ;; Ensure CCW winding for correct outward-facing normals
        (let [final-pts (ensure-ccw raw-points)]
          (cond-> (make-shape final-pts {:centered? false
                                         :preserve-position? preserve-position})
            (path-has-mark? path) (assoc :source-path path
                                         :mark-refs (compute-mark-refs path final-pts))))))))

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

(defn path-to-2d-waypoints
  "Extract 2D waypoints (position + heading direction) from a path.
   Projects XY from 3D turtle state. Returns vector of {:pos [x y] :dir [dx dy]}.
   A leading (move-to [x y]) anchors the first waypoint at that absolute point
   (so edit-path can round-trip a baked path back into editable nodes)."
  [path]
  (when (and (map? path) (= :path (:type path)))
    (let [commands (:commands path)
          [sx sy] (leading-move-to-xy commands)]
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
            ;; rt/lt: in-plane strafe (heading kept), right = [hy -hx]
            :rt (let [d (first (:args cmd)) hx (first heading) hy (second heading)
                      new-pos [(+ (first pos) (* hy d)) (- (second pos) (* hx d))]]
                  {:pos new-pos :heading heading
                   :waypoints (conj waypoints {:pos new-pos :dir heading})})
            :lt (let [d (first (:args cmd)) hx (first heading) hy (second heading)
                      new-pos [(- (first pos) (* hy d)) (+ (second pos) (* hx d))]]
                  {:pos new-pos :heading heading
                   :waypoints (conj waypoints {:pos new-pos :dir heading})})
            :move-to (let [t (first (:args cmd))]
                       {:pos [(first t) (second t)] :heading heading
                        :waypoints waypoints})
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
        {:pos [sx sy] :heading [1 0] :waypoints [{:pos [sx sy] :dir [1 0]}]}
        commands)))))

;; mark-pos / mark-x / mark-y / mark-z were here, but a correct trace
;; needs to handle :side-trip (and any future scoped commands) the same
;; way turtle/resolve-marks does. Reusing resolve-marks from here would
;; close the cycle ridley.turtle.core → loft → clipper → shape → core,
;; so they live in ridley.editor.implicit (which already requires
;; turtle.core) as implicit-mark-pos & friends, bound to `mark-pos` etc
;; in the SCI context.

(defn ^:export bounds-2d
  "Get the 2D bounding box of a path or shape.
   Returns {:min [x y] :max [x y] :center [cx cy] :size [w h]} or nil.
   For paths, includes the origin point."
  [obj]
  (let [pts (cond
              (and (map? obj) (= :path (:type obj)))
              (let [wps (ensure-path-2d obj)]
                (mapv :pos wps))
              (and (map? obj) (= :shape (:type obj)))
              (:points obj))]
    (when (seq pts)
      (let [xs (mapv first pts)
            ys (mapv second pts)
            x-min (apply min xs) x-max (apply max xs)
            y-min (apply min ys) y-max (apply max ys)]
        {:min [x-min y-min]
         :max [x-max y-max]
         :center [(/ (+ x-min x-max) 2) (/ (+ y-min y-max) 2)]
         :centroid [(/ (reduce + xs) (count xs)) (/ (reduce + ys) (count ys))]
         :size [(- x-max x-min) (- y-max y-min)]}))))

(defn- contour-perimeter
  "Sum of euclidean distances around a CLOSED ring of [x y] points,
   including the closing edge (last -> first). Returns 0 for < 2 points.
   Closure is implicit: the ring is NOT expected to repeat its first point."
  [points]
  (let [n (count points)]
    (if (< n 2)
      0
      (loop [i 0 total 0]
        (if (>= i n)
          total
          (let [[x1 y1] (nth points i)
                [x2 y2] (nth points (mod (inc i) n))
                dx (- x2 x1) dy (- y2 y1)]
            (recur (inc i) (+ total (Math/sqrt (+ (* dx dx) (* dy dy)))))))))))

(defn ^:export shape-perimeter
  "Total length of a shape's OUTER contour: the sum of euclidean distances
   between consecutive points of the closed ring, including the closing
   edge (last -> first). Holes are ignored — use `shape-perimeters` for the
   per-contour breakdown.

   The result is the length of the SAMPLED polygon, so low-resolution
   circles/arcs/beziers slightly UNDERESTIMATE (inscribed polygon): a circle
   of radius r at n segments gives 2·n·r·sin(π/n) < 2πr. Increase the
   shape's segment count for more precision. Returns nil for non-shapes."
  [shape]
  (when (shape? shape)
    (contour-perimeter (:points shape))))

(defn ^:export shape-perimeters
  "Per-contour lengths of a shape as a vector [outer hole1 hole2 ...].
   Element 0 is the outer contour (same value as `shape-perimeter`); the
   remaining elements are the hole contours in order. Each entry is a
   closed-ring perimeter (see `shape-perimeter` for the sampling caveat).
   Returns nil for non-shapes."
  [shape]
  (when (shape? shape)
    (into [(contour-perimeter (:points shape))]
          (map contour-perimeter (:holes shape)))))

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
   - :preserve-position true - keep the path's frame origin [0 0] as the mesh
     creation pose (instead of re-centering); for image-traced outlines.

   Returns a shape suitable for extrude, revolve, etc."
  [path width & {:keys [start-cap end-cap join miter-limit preserve-position]
                 :or {start-cap :flat end-cap :flat join :miter miter-limit 4}}]
  (assert (number? width) "stroke-shape requires a width argument: (stroke-shape path width)")
  (let [wps (ensure-path-2d path)
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
        ;; Marks live on the path centerline; carry the source path so the
        ;; extrude/loft step can resolve them as mesh anchors there.
        (cond-> (make-shape (ensure-ccw all-pts) {:centered? true
                                                  :preserve-position? preserve-position})
          (path-has-mark? path) (assoc :source-path path))))))

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
            new-pts (mapv lerp-pt pts1 pts2)
            ;; Interpolate holes if both shapes have matching holes
            holes1 (:holes shape1)
            holes2 (:holes shape2)
            new-holes (when (and holes1 holes2 (= (count holes1) (count holes2)))
                        (mapv (fn [h1 h2]
                                (if (= (count h1) (count h2))
                                  (mapv lerp-pt h1 h2)
                                  h1))
                              holes1 holes2))]
        (make-shape new-pts (cond-> {:centered? (:centered? shape1)
                                     ;; carry shape1's anchoring so a preserve-position?
                                     ;; profile lofts at its raw coords (parity with extrude
                                     ;; / loft shape-fn), not re-anchored on its first vertex
                                     :preserve-position? (:preserve-position? shape1)}
                              new-holes (assoc :holes new-holes)))))))

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

;; ============================================================
;; Path clipping and transformation
;; ============================================================

(defn- points-to-path
  "Convert a sequence of 2D points to a path.
   Generates set-heading + forward commands to trace from [0,0] through each point.
   Optional marks is a map of {waypoint-index -> [mark-names...]} to re-inject."
  ([pts] (points-to-path pts nil))
  ([pts marks]
   (when (>= (count pts) 2)
     (let [commands
           (loop [i 0 cmds [] pos [0 0]]
             (if (>= i (count pts))
               cmds
               (let [[tx ty] (nth pts i)
                     dx (- tx (first pos))
                     dy (- ty (second pos))
                     dist (Math/sqrt (+ (* dx dx) (* dy dy)))
                     ;; waypoint index is i+1 (0 is origin)
                     wp-idx (inc i)
                     mark-cmds (when marks
                                 (mapv (fn [name] {:cmd :mark :args [name]})
                                       (get marks wp-idx)))]
                 (if (< dist 0.0001)
                   (recur (inc i) (into cmds mark-cmds) [tx ty])
                   (recur (inc i)
                          (into (conj cmds
                                      {:cmd :set-heading :args [[(/ dx dist) (/ dy dist) 0] [0 0 1]]}
                                      {:cmd :f :args [dist]})
                                mark-cmds)
                          [tx ty])))))]
       {:type :path :commands commands}))))

;; --- 3D path waypoints + rebuild (reverse-path / mirror-path) --------------
;; Tracing the full turtle frame (position, heading, up) here, locally, because
;; ridley.turtle.shape can't require ridley.turtle.core (that would close the
;; core → loft → clipper → shape cycle). 2D profiles are the planar special case
;; (z = 0, up = [0 0 1]).

(defn- v3- [[a b c] [d e f]] [(- a d) (- b e) (- c f)])
(defn- v3+ [[a b c] [d e f]] [(+ a d) (+ b e) (+ c f)])
(defn- v3* [[a b c] s] [(* a s) (* b s) (* c s)])
(defn- v3-dot [[a b c] [d e f]] (+ (* a d) (* b e) (* c f)))
(defn- v3-cross [[a b c] [d e f]]
  [(- (* b f) (* c e)) (- (* c d) (* a f)) (- (* a e) (* b d))])
(defn- v3-mag [v] (Math/sqrt (v3-dot v v)))
(defn- v3-normalize [v]
  (let [m (v3-mag v)] (if (> m 1e-9) (v3* v (/ 1.0 m)) v)))
(defn- to-vec3 [v] (if (>= (count v) 3) (vec (take 3 v)) [(nth v 0) (nth v 1) 0]))

(defn- v3-rotate
  "Rodrigues: rotate vector v around unit axis k by `deg` degrees."
  [v k deg]
  (let [r (* deg (/ Math/PI 180))
        c (Math/cos r) s (Math/sin r)
        kv (v3-cross k v)
        kk (* (v3-dot k v) (- 1 c))]
    (v3+ (v3+ (v3* v c) (v3* kv s)) (v3* k kk))))

(defn path-to-3d-waypoints
  "Trace a path's full turtle frame. Returns a vector of {:pos :heading :up}
   (3D), one per position, starting at the origin pose. Handles f/b/u/lt/rt
   moves and th/tv/tr/set-heading rotations (right = heading × up)."
  [path]
  (when (and (map? path) (= :path (:type path)))
    (let [final
          (reduce
           (fn [{:keys [pos heading up] :as st} {:keys [cmd args]}]
             (let [right (v3-normalize (v3-cross heading up))
                   d (first args)
                   move (fn [dir dist]
                          (let [np (v3+ pos (v3* dir dist))]
                            (assoc st :pos np
                                   :waypoints (conj (:waypoints st)
                                                    {:pos np :heading heading :up up}))))]
               (case cmd
                 :f  (move heading d)
                 :b  (move heading (- d))
                 :u  (move up d)
                 :rt (move right d)
                 :lt (move right (- d))
                 :th (assoc st :heading (v3-normalize (v3-rotate heading up d)))
                 :tv (assoc st :heading (v3-normalize (v3-rotate heading right d))
                            :up (v3-normalize (v3-rotate up right d)))
                 :tr (assoc st :up (v3-normalize (v3-rotate up heading d)))
                 :set-heading (if (= :local (nth args 2 nil))
                                ;; :local — vectors in the current [right up heading]
                                ;; frame → world, so the rail composes with the pose.
                                (let [l->w (fn [[lx ly lz]]
                                             (v3+ (v3* right lx) (v3+ (v3* up ly) (v3* heading lz))))]
                                  (assoc st :heading (v3-normalize (l->w (to-vec3 (first args))))
                                         :up (v3-normalize (l->w (to-vec3 (second args))))))
                                (assoc st :heading (v3-normalize (to-vec3 (first args)))
                                       :up (v3-normalize (to-vec3 (second args)))))
                 st)))
           (let [p0 {:pos [0 0 0] :heading [1 0 0] :up [0 0 1]}]
             (assoc p0 :waypoints [p0]))
           (:commands path))
          wps (:waypoints final)]
      ;; The trailing rotation a bezier emits sets the final frame but adds no
      ;; waypoint, so carry the final heading/up onto the last waypoint — that's
      ;; the exact end tangent mirror-path needs.
      (if (seq wps)
        (assoc wps (dec (count wps))
               (assoc (peek wps) :heading (:heading final) :up (:up final)))
        wps))))

(defn ^:export ensure-path-2d
  "Normalize any path to a planar 2D trace at the planar-consumer boundary
   (path-to-shape, stroke-shape, bounds-2d, 2D booleans). Returns a vector of
   2D waypoints [{:pos [a b] :dir [da db]} ...].

   - :2d species (from path-2d / edit-path): the path is recorded so its trace
     lives in its frame's (right, up) plane — the same plane a shape stamps into.
     3D-trace the full turtle frame and project each pose onto the canonical
     right = [0 -1 0] and up = [0 0 1] (a = -y, b = z). The projection is
     lossless (the trace never leaves that plane) and re-embed is identity, so
     `(follow-path P)` == `(stamp (path-to-shape P))` holds by construction.
   - :3d (default): today's XY tracer (legacy, non-breaking) via
     path-to-2d-waypoints.

   Rail consumers (extrude-from-path, loft) keep the 3D path; only planar
   consumers route through here."
  [path]
  (when (and (map? path) (= :path (:type path)))
    (if (= :2d (:species path))
      ;; A leading (move-to [a b]) anchors the start in shape coords. The 3D
      ;; trace ignores move-to (path-to-3d-waypoints has no such case), so the
      ;; trace is relative to the origin; offset the projected points by (a,b).
      (let [mv (some (fn [{:keys [cmd args]}] (when (= :move-to cmd) (first args)))
                     (:commands path))
            ox (if mv (first mv) 0)
            oy (if mv (second mv) 0)]
        (mapv (fn [{:keys [pos heading]}]
                (let [[_ y z] pos
                      [_ hy hz] heading]
                  {:pos [(+ (- y) ox) (+ z oy)] :dir [(- hy) hz]}))
              (path-to-3d-waypoints path)))
      (path-to-2d-waypoints path))))

;; -- rotation-minimizing frame (twist-free sweep) ------------------------------
;; extrude/loft orient the swept section by the turtle's up, which on a NON-planar
;; rail accumulates a roll (holonomy) and makes the tube "spiral". These rebuild a
;; rail with a parallel-transported up so the frame is twist-free.

(defn- rmf-rot
  "Rotate v around unit axis by ang radians (Rodrigues)."
  [v axis ang]
  (let [c (Math/cos ang) s (Math/sin ang)]
    (v3+ (v3* v c)
         (v3+ (v3* (v3-cross axis v) s)
              (v3* axis (* (v3-dot axis v) (- 1 c)))))))

(defn- rmf-safe-up
  "Up perpendicular to dir, from reference ref; never zero (switches reference when
   ref ∥ dir, else a zero up would collapse the section frame)."
  [dir ref]
  (let [u (v3- ref (v3* dir (v3-dot ref dir)))]
    (if (> (v3-mag u) 1e-6)
      (v3-normalize u)
      (let [alt (if (> (Math/abs (nth dir 2)) 0.9) [1 0 0] [0 0 1])]
        (v3-normalize (v3- alt (v3* dir (v3-dot alt dir))))))))

(defn- rmf-transport
  "Parallel-transport up `u` from heading `h` to heading `d` (minimal rotation)."
  [u h d]
  (let [dt (max -1.0 (min 1.0 (v3-dot h d)))]
    (if (> dt 0.999999)
      u
      (let [axis (v3-cross h d) am (v3-mag axis)]
        (if (< am 1e-9)
          (rmf-safe-up d u)
          (rmf-rot u (v3* axis (/ 1.0 am)) (Math/acos dt)))))))

(defn positions->rmf-commands
  "Commands tracing world `positions` (≥ 2 points) as a twist-free rail, shifted so
   the first point is the origin. Per segment: `(set-heading [h-local][up-local] :local)`
   + `(f dist)`, where the new heading and the rotation-minimizing (parallel-transport)
   up are expressed in the PREVIOUS segment's [right up heading] frame. Because the
   frame is given relative to the current frame (`:local`), the rail composes under
   the consumption pose (rotates/translates with the turtle) — unlike absolute
   set-heading — while the parallel-transported up keeps the section twist-free."
  [positions]
  (when (>= (count positions) 2)
    (let [origin (first positions)]
      (loop [cur [0 0 0] h [1 0 0] u [0 0 1] ps (rest positions) cmds []]
        (if (empty? ps)
          cmds
          (let [tgt (v3- (first ps) origin) delta (v3- tgt cur) dist (v3-mag delta)]
            (if (< dist 1e-6)
              (recur cur h u (rest ps) cmds)
              (let [d (v3-normalize delta)
                    up* (rmf-transport u h d)                 ; twist-free up (world)
                    r (v3-normalize (v3-cross h u))
                    w->l (fn [v] [(v3-dot v r) (v3-dot v u) (v3-dot v h)])  ; world → local frame
                    h-loc (w->l d) up-loc (w->l up*)]
                (recur tgt d up* (rest ps)
                       (conj cmds {:cmd :set-heading :args [h-loc up-loc :local]}
                             {:cmd :f :args [dist]}))))))))))

(defn ^:export ensure-untwisted
  "Re-frame a 3D rail for a twist-free sweep: keep the node positions, but rederive
   the turtle up by parallel transport, so extrude/loft don't roll (spiral) the
   section along a NON-planar rail. Rebuilt with per-segment (set-heading … :local),
   i.e. frames RELATIVE to the previous one, so the rail still composes under the
   consumption pose (rotates with it). Positions come from the path's
   traced waypoints (curves become their tessellated polyline). For a planar rail
   it's effectively a no-op (the up was already twist-free). Call it by hand when a
   hand-written non-planar rail's tube twists: (extrude prof (ensure-untwisted p))."
  [path]
  (if (and (map? path) (= :path (:type path)))
    (let [pts (mapv :pos (path-to-3d-waypoints path))]
      (if (>= (count pts) 2)
        {:type :path :commands (positions->rmf-commands pts)}
        path))
    path))

(defn- poses->path
  "Rebuild a path from a list of {:pos :up} poses. Shifts so the first pose sits
   at the origin (a relative path: following it continues from the current pose),
   and emits set-heading + forward per segment, the up re-orthogonalized to the
   segment direction so the swept frame stays well-defined."
  [poses]
  (when (>= (count poses) 2)
    (let [origin (:pos (first poses))]
      (loop [cur [0 0 0]
             ps (rest poses)
             cmds []]
        (if (empty? ps)
          {:type :path :commands cmds}
          (let [{:keys [pos up]} (first ps)
                tgt (v3- pos origin)
                delta (v3- tgt cur)
                dist (v3-mag delta)]
            (if (< dist 1e-6)
              (recur cur (rest ps) cmds)
              (let [dir (v3* delta (/ 1.0 dist))
                    up* (let [u (v3- up (v3* dir (v3-dot up dir)))]
                          (if (> (v3-mag u) 1e-6) (v3-normalize u)
                              (v3-normalize (v3-cross dir [0 0 1]))))]
                (recur tgt (rest ps)
                       (conj cmds
                             {:cmd :set-heading :args [dir up*]}
                             {:cmd :f :args [dist]}))))))))))

(defn ^:export reverse-path
  "Return a new path tracing `path`'s waypoints in reverse order (last → first).
   Rebuilt from poses and shifted to start at the origin, so
   `(follow-path (reverse-path p))` retraces p backward from the current pose.
   Works in 3D (the full turtle frame is carried)."
  [path]
  (let [wps (path-to-3d-waypoints path)]
    (when (and wps (>= (count wps) 2))
      (poses->path (vec (reverse wps))))))

(defn ^:export mirror-path
  "Return a new path: `path` reflected across the plane through its END point.
   For a curve meant to be symmetric about that plane — e.g. half of a symmetric
   corner — `(reverse-path (mirror-path half))` is the continuation that completes
   it: follow the half, then follow this, and the two halves join into the full
   symmetric curve.

   The mirror PLANE normal defaults to the heading at the end of the path (its
   normal is the turtle's heading there; the plane itself spans the right and up
   axes — exactly the turtle's right/up plane). Because that heading is read from
   the discretized curve, the default is approximate; pass the normal `[nx ny]` /
   `[nx ny nz]` explicitly for an exact result. For a corner whose chord runs
   along the 45° diagonal, the symmetry-plane normal is that diagonal, `[1 1]`.
   Works in 3D."
  ([path]
   (let [wps (path-to-3d-waypoints path)]
     (when (and wps (>= (count wps) 2))
       (mirror-path path (:heading (last wps))))))
  ([path normal]
   (let [wps (path-to-3d-waypoints path)]
     (when (and wps (>= (count wps) 2))
       (let [n (v3-normalize (to-vec3 normal))
             a (:pos (last wps))
             reflect-pt (fn [p] (v3- p (v3* n (* 2 (v3-dot (v3- p a) n)))))
             reflect-v  (fn [v] (v3- v (v3* n (* 2 (v3-dot v n)))))]
         (poses->path
          (mapv (fn [{:keys [pos up]}]
                  {:pos (reflect-pt pos) :up (reflect-v up)})
                wps)))))))

(defn ^:export poly-path
  "Create an open path from flat x y coordinate pairs.
   Like poly but produces a path instead of a shape.
   (poly-path 0 0 30 0 45 90)  →  path from (0,0) → (30,0) → (45,90)
   Accepts: (poly-path 0 0 30 0 ...), (poly-path [0 0 30 0 ...]), or (poly-path v)"
  ([x] (poly-path x nil))
  ([x y & more]
   (let [nums (if (and (nil? y) (sequential? x))
                x
                (cons x (cons y more)))
         n (count nums)]
     (when (odd? n)
       (throw (js/Error. (str "poly-path: odd number of coordinates (" n "). Coordinates must be x y pairs."))))
     (when (< n 4)
       (throw (js/Error. (str "poly-path: need at least 2 points (4 coordinates), got " n "."))))
     (let [pts (mapv vec (partition 2 nums))]
       (points-to-path pts)))))

(defn ^:export poly-path-closed
  "Create a closed path from flat x y coordinate pairs.
   Like poly-path but adds a final segment back to the first point.
   (poly-path-closed 0 0 30 0 45 90 0 90)  →  closed quadrilateral path
   Useful with bezier-as + path-to-shape for smoothed polygons."
  ([x] (poly-path-closed x nil))
  ([x y & more]
   (let [nums (if (and (nil? y) (sequential? x))
                x
                (cons x (cons y more)))
         n (count nums)]
     (when (odd? n)
       (throw (js/Error. (str "poly-path-closed: odd number of coordinates (" n "). Coordinates must be x y pairs."))))
     (when (< n 6)
       (throw (js/Error. (str "poly-path-closed: need at least 3 points (6 coordinates), got " n "."))))
     (let [pts (mapv vec (partition 2 nums))
           closed-pts (conj pts (first pts))]
       (points-to-path closed-pts)))))

(defn ^:export subpath-y
  "Extract the portion of a path within a height range [from-h, to-h].
   Heights are measured as distance from the path's starting Y position,
   regardless of whether Y increases or decreases along the path.
   Clips segments at boundaries and outputs a path with Y starting at 0.

   (subpath-y path 2 13)  ; keep height 2..13, output Y=0..11"
  [path from-h to-h]
  (let [wps (path-to-2d-waypoints path)
        pts (mapv :pos wps)
        n (count pts)
        eps 0.0001
        y-start (second (first pts))
        y-end (second (peek pts))
        ;; Detect direction: -1 if path goes downward, +1 if upward.
        ;; Use eps so a path that returns to (or near) its start height — e.g.
        ;; a closed silhouette whose last point lands ~1e-14 below the first
        ;; from float noise — counts as upward instead of flipping all heights
        ;; negative (which would empty every positive height range).
        y-dir (if (< y-end (- y-start eps)) -1 1)
        ;; Convert each point to [x, height] where height >= 0
        xh-pts (mapv (fn [[x y]] [x (* y-dir (- y y-start))]) pts)
        in? (fn [[_ h]] (and (>= h (- from-h eps)) (<= h (+ to-h eps))))
        lerp-h (fn [[x1 h1] [x2 h2] target-h]
                 (let [dh (- h2 h1)]
                   (if (< (Math/abs dh) eps)
                     [x1 target-h]
                     (let [t (/ (- target-h h1) dh)]
                       [(+ x1 (* t (- x2 x1))) target-h]))))
        result
        (loop [i 0 acc []]
          (if (>= i n)
            acc
            (let [p (nth xh-pts i)]
              (if (zero? i)
                (recur 1 (if (in? p) (conj acc p) acc))
                (let [prev (nth xh-pts (dec i))
                      pi (in? prev)
                      ci (in? p)
                      [_ ph] prev
                      [_ ch] p]
                  (cond
                    ;; Both in range
                    (and pi ci)
                    (recur (inc i) (conj acc p))

                    ;; Entering range
                    (and (not pi) ci)
                    (let [bh (if (< ph from-h) from-h to-h)]
                      (recur (inc i) (-> acc (conj (lerp-h prev p bh)) (conj p))))

                    ;; Leaving range
                    (and pi (not ci))
                    (let [bh (if (> ch to-h) to-h from-h)]
                      (recur (inc i) (conj acc (lerp-h prev p bh))))

                    ;; Both outside — check if segment crosses entire range
                    :else
                    (if (or (and (< ph from-h) (> ch to-h))
                            (and (> ph to-h) (< ch from-h)))
                      (let [bp1 (lerp-h prev p from-h)
                            bp2 (lerp-h prev p to-h)]
                        (if (< ph ch)
                          (recur (inc i) (-> acc (conj bp1) (conj bp2)))
                          (recur (inc i) (-> acc (conj bp2) (conj bp1)))))
                      (recur (inc i) acc))))))))]
    (when (>= (count result) 2)
      (let [shifted (mapv (fn [[x h]] [x (- h from-h)]) result)]
        (points-to-path shifted)))))

(defn ^:export offset-x
  "Shift all X coordinates of a path by dx.
   Useful for moving a profile inward/outward relative to the revolve axis.

   (offset-x path -2.5)  ; shift profile 2.5 units toward the axis"
  [path dx]
  (let [wps (path-to-2d-waypoints path)
        ;; Skip the implicit [0,0] origin, work with real waypoints
        real-pts (mapv :pos (rest wps))
        shifted (mapv (fn [[x y]] [(+ x dx) y]) real-pts)]
    (when (>= (count shifted) 2)
      (points-to-path shifted))))

(defn- fit-scale-factors
  "Compute scale factors for fitting a set of 2D points to target dimensions.
   Returns [sx sy] where each is 1.0 if no target was given for that axis.
   Sign-aware: if target is positive but points go mostly negative (or vice versa),
   the scale factor is negated to flip the points into the target quadrant.
   This means (fit path :y 180) always produces positive Y values, regardless
   of whether the path was drawn upward or downward."
  [pts target-x target-y]
  (let [xs (mapv first pts)
        ys (mapv second pts)
        x-min (apply min xs) x-max (apply max xs)
        y-min (apply min ys) y-max (apply max ys)
        x-extent (- x-max x-min)
        y-extent (- y-max y-min)
        ;; Dominant direction: the coordinate farthest from 0
        x-far (if (>= (Math/abs x-max) (Math/abs x-min)) x-max x-min)
        y-far (if (>= (Math/abs y-max) (Math/abs y-min)) y-max y-min)
        ;; Scale magnitude (always positive)
        sx-mag (if (and target-x (> x-extent 0.0001)) (/ (Math/abs target-x) x-extent) 1.0)
        sy-mag (if (and target-y (> y-extent 0.0001)) (/ (Math/abs target-y) y-extent) 1.0)
        ;; Flip sign when target and dominant direction disagree
        flip-x? (and target-x (> x-extent 0.0001)
                     (> (Math/abs x-far) 0.0001)
                     (neg? (* target-x x-far)))
        flip-y? (and target-y (> y-extent 0.0001)
                     (> (Math/abs y-far) 0.0001)
                     (neg? (* target-y y-far)))]
    [(* (if flip-x? -1 1) sx-mag)
     (* (if flip-y? -1 1) sy-mag)]))

(defn ^:export fit
  "Scale a path or shape to fit target dimensions.
   Works on both paths and shapes. Specify :x and/or :y to set the
   desired extent for each axis. Scaling is proportional from the origin.

   (fit path :y 180)          ; scale Y to 180, keep X as-is
   (fit shape :x 200 :y 130)  ; scale both axes independently"
  [obj & {:keys [x y]}]
  (when (and (map? obj) (or x y))
    (cond
      ;; Path
      (= :path (:type obj))
      (let [wps (path-to-2d-waypoints obj)
            real-pts (mapv :pos (rest wps))
            ;; Extract marks: scan commands, map each mark name to its waypoint index
            marks (let [cmds (:commands obj)]
                    (reduce (fn [{:keys [wp-idx result]} cmd]
                              (case (:cmd cmd)
                                :f    {:wp-idx (inc wp-idx) :result result}
                                :mark {:wp-idx wp-idx
                                       :result (update result wp-idx (fnil conj []) (first (:args cmd)))}
                                {:wp-idx wp-idx :result result}))
                            {:wp-idx 0 :result {}}
                            cmds))]
        (when (>= (count real-pts) 2)
          ;; Include origin [0,0] in extent calculation so scaling from
          ;; origin doesn't overshoot the target dimensions.
          (let [all-pts (cons [0 0] real-pts)
                [sx sy] (fit-scale-factors all-pts x y)
                scaled (mapv (fn [[px py]]
                               [(if x (* px sx) px)
                                (if y (* py sy) py)])
                             real-pts)]
            (points-to-path scaled (:result marks)))))

      ;; Shape
      (= :shape (:type obj))
      (let [pts (:points obj)]
        (when (>= (count pts) 2)
          (let [[sx sy] (fit-scale-factors pts x y)
                scale-pt (fn [[px py]]
                           [(if x (* px sx) px)
                            (if y (* py sy) py)])
                scaled-pts (mapv scale-pt pts)
                scaled-holes (when (:holes obj)
                               (mapv (fn [hole] (mapv scale-pt hole))
                                     (:holes obj)))]
            (cond-> (assoc obj :points scaled-pts)
              scaled-holes (assoc :holes scaled-holes)))))

      :else nil)))
