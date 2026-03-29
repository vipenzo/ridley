(ns ridley.geometry.faces
  "Face metadata and selection for meshes.

   Face groups are collections of triangles that form a logical face.
   For primitives, faces have semantic names (:top, :bottom, :front, etc).
   For complex meshes, faces have numeric IDs.")

;; ============================================================
;; Vector math utilities
;; ============================================================

(defn- v+ [[x1 y1 z1] [x2 y2 z2]]
  [(+ x1 x2) (+ y1 y2) (+ z1 z2)])

(defn- v- [[x1 y1 z1] [x2 y2 z2]]
  [(- x1 x2) (- y1 y2) (- z1 z2)])

(defn- v* [[x y z] s]
  [(* x s) (* y s) (* z s)])

(defn- cross [[x1 y1 z1] [x2 y2 z2]]
  [(- (* y1 z2) (* z1 y2))
   (- (* z1 x2) (* x1 z2))
   (- (* x1 y2) (* y1 x2))])

(defn- magnitude [[x y z]]
  (Math/sqrt (+ (* x x) (* y y) (* z z))))

(defn- normalize [v]
  (let [m (magnitude v)]
    (if (zero? m) v (v* v (/ 1 m)))))

;; ============================================================
;; Face info computation
;; ============================================================

(defn compute-triangle-normal
  "Compute normal vector for a triangle given three vertices."
  [v0 v1 v2]
  (let [edge1 (v- v1 v0)
        edge2 (v- v2 v0)]
    (normalize (cross edge1 edge2))))

(defn compute-triangle-center
  "Compute center point of a triangle."
  [v0 v1 v2]
  (v* (v+ (v+ v0 v1) v2) (/ 1 3)))

(defn compute-face-info
  "Compute normal, heading, and center for a face group (set of triangle indices).
   Returns {:normal [x y z] :heading [x y z] :center [x y z] :vertices [indices]}.

   The heading is derived from the first edge of the first triangle,
   projected onto the face plane (perpendicular to normal)."
  [vertices face-triangles]
  (when (seq face-triangles)
    (let [;; Collect all unique vertex indices
          all-indices (distinct (mapcat identity face-triangles))
          ;; Get actual vertex positions
          face-verts (mapv #(nth vertices % [0 0 0]) all-indices)
          ;; Compute center as average of all vertices
          center (v* (reduce v+ [0 0 0] face-verts) (/ 1 (count face-verts)))
          ;; Compute normal from first triangle (assuming coplanar)
          [i0 i1 i2] (first face-triangles)
          v0 (nth vertices i0 [0 0 0])
          v1 (nth vertices i1 [0 0 0])
          v2 (nth vertices i2 [0 0 0])
          normal (compute-triangle-normal v0 v1 v2)
          ;; Heading is first edge direction (v0 -> v1), normalized
          edge1 (v- v1 v0)
          heading (normalize edge1)]
      {:normal normal
       :heading heading
       :center center
       :vertices (vec all-indices)
       :triangles face-triangles})))

;; ============================================================
;; Face group definitions for primitives
;; ============================================================

(defn box-face-groups
  "Return face groups for a box mesh.
   Box faces are defined by triangle indices from make-box-faces.
   Must match exactly the triangles in make-box-faces (primitives.cljs).

   Face naming convention (Z is up):
   - :top = +Z, :bottom = -Z
   - :front = +Y, :back = -Y
   - :right = +X, :left = -X"
  []
  ;; From make-box-faces (vertices 0-3 at z=-, 4-7 at z=+):
  ;; [0 2 1] [0 3 2]   ; -Z face (bottom)
  ;; [4 5 6] [4 6 7]   ; +Z face (top)
  ;; [0 1 5] [0 5 4]   ; -Y face (back)
  ;; [3 6 2] [3 7 6]   ; +Y face (front)
  ;; [0 4 7] [0 7 3]   ; -X face (left)
  ;; [1 2 6] [1 6 5]   ; +X face (right)
  {:bottom [[0 2 1] [0 3 2]]   ; -Z
   :top    [[4 5 6] [4 6 7]]   ; +Z
   :back   [[0 1 5] [0 5 4]]   ; -Y
   :front  [[3 6 2] [3 7 6]]   ; +Y
   :left   [[0 4 7] [0 7 3]]   ; -X
   :right  [[1 2 6] [1 6 5]]}) ; +X

(defn cylinder-face-groups
  "Return face groups for a cylinder mesh.
   segments: number of segments used to create the cylinder."
  [segments]
  (let [bottom-center (* 2 segments)
        top-center (inc bottom-center)
        ;; Side faces: 2 triangles per segment
        side-faces (vec
                    (mapcat (fn [i]
                              (let [next-i (mod (inc i) segments)
                                    b0 i
                                    b1 next-i
                                    t0 (+ i segments)
                                    t1 (+ next-i segments)]
                                [[b0 t0 t1] [b0 t1 b1]]))
                            (range segments)))
        ;; Bottom cap
        bottom-faces (vec
                      (for [i (range segments)]
                        (let [next-i (mod (inc i) segments)]
                          [bottom-center next-i i])))
        ;; Top cap
        top-faces (vec
                   (for [i (range segments)]
                     (let [next-i (mod (inc i) segments)]
                       [(+ i segments) (+ next-i segments) top-center])))]
    {:bottom bottom-faces
     :top top-faces
     :side side-faces}))

(defn cone-face-groups
  "Return face groups for a cone/frustum mesh.
   Same structure as cylinder."
  [segments]
  (cylinder-face-groups segments))

(defn sphere-face-groups
  "Return face groups for a sphere mesh.
   Sphere has no distinct faces, returns single :surface group.
   Uses new vertex layout: [north-pole, ring1..., ring2..., ..., south-pole]"
  [segments rings]
  (let [segments (int segments)
        rings (int rings)
        north-pole 0
        south-pole (+ 1 (* (dec rings) segments))
        ring-start (fn [r] (+ 1 (* (dec r) segments)))]
    {:surface (vec
               (concat
                ;; North pole triangles
                (for [seg (range segments)]
                  (let [next-seg (mod (inc seg) segments)
                        r1-curr (+ (ring-start 1) seg)
                        r1-next (+ (ring-start 1) next-seg)]
                    [north-pole r1-next r1-curr]))
                ;; Middle quads
                (apply concat
                       (for [ring (range 1 (dec rings))
                             seg (range segments)]
                         (let [next-seg (mod (inc seg) segments)
                               i0 (+ (ring-start ring) seg)
                               i1 (+ (ring-start ring) next-seg)
                               i2 (+ (ring-start (inc ring)) seg)
                               i3 (+ (ring-start (inc ring)) next-seg)]
                           [[i0 i1 i3] [i0 i3 i2]])))
                ;; South pole triangles
                (for [seg (range segments)]
                  (let [next-seg (mod (inc seg) segments)
                        last-ring (dec rings)
                        rl-curr (+ (ring-start last-ring) seg)
                        rl-next (+ (ring-start last-ring) next-seg)]
                    [rl-curr rl-next south-pole]))))}))

;; ============================================================
;; Face operations
;; ============================================================

(defn list-faces
  "List all faces in a mesh with their info.
   Returns a sequence of {:id face-id :normal [x y z] :center [x y z]}."
  [mesh]
  (when-let [face-groups (:face-groups mesh)]
    (let [vertices (:vertices mesh)]
      (mapv (fn [[face-id triangles]]
              (let [info (compute-face-info vertices triangles)]
                (assoc info :id face-id)))
            face-groups))))

(defn get-face
  "Get info for a specific face by ID.
   Returns {:id face-id :normal [x y z] :center [x y z] :vertices [indices]}."
  [mesh face-id]
  (when-let [triangles (get-in mesh [:face-groups face-id])]
    (let [info (compute-face-info (:vertices mesh) triangles)]
      (assoc info :id face-id))))

(defn face-ids
  "Get all face IDs for a mesh."
  [mesh]
  (keys (:face-groups mesh)))

(defn- compute-triangle-area
  "Compute the area of a triangle using cross product."
  [v0 v1 v2]
  (let [edge1 (v- v1 v0)
        edge2 (v- v2 v0)
        cross-vec (cross edge1 edge2)]
    (/ (magnitude cross-vec) 2)))

(defn- extract-edges-from-triangles
  "Extract unique edges from a list of triangles.
   Returns vector of [v0 v1] pairs where v0 < v1."
  [triangles]
  (let [all-edges (mapcat (fn [[i j k]]
                            [[i j] [j k] [k i]])
                          triangles)
        ;; Normalize edge direction (smaller index first)
        normalized (map (fn [[a b]] (if (< a b) [a b] [b a])) all-edges)]
    (vec (distinct normalized))))

(defn face-info
  "Get detailed info for a specific face by ID.
   Returns {:id face-id
            :normal [x y z]
            :heading [x y z]
            :center [x y z]
            :vertices [indices]
            :vertex-positions [[x y z] ...]
            :area number
            :edges [[v0 v1] ...]
            :triangles [[i j k] ...]}"
  [mesh face-id]
  (when-let [triangles (get-in mesh [:face-groups face-id])]
    (let [vertices (:vertices mesh)
          base-info (compute-face-info vertices triangles)
          ;; Get unique vertex indices
          vertex-indices (:vertices base-info)
          ;; Get actual positions
          vertex-positions (mapv #(nth vertices % [0 0 0]) vertex-indices)
          ;; Calculate total area
          area (reduce + 0 (map (fn [[i j k]]
                                  (compute-triangle-area
                                   (nth vertices i [0 0 0])
                                   (nth vertices j [0 0 0])
                                   (nth vertices k [0 0 0])))
                                triangles))
          ;; Extract edges
          edges (extract-edges-from-triangles triangles)]
      (assoc base-info
             :id face-id
             :vertex-positions vertex-positions
             :area area
             :edges edges))))

;; ============================================================
;; Mesh with face metadata
;; ============================================================

(defn add-face-groups
  "Add face groups to an existing mesh based on its primitive type."
  [mesh & {:keys [segments rings] :or {segments 24 rings 12}}]
  (let [groups (case (:primitive mesh)
                 :box (box-face-groups)
                 :cylinder (cylinder-face-groups segments)
                 :cone (cone-face-groups segments)
                 :sphere (sphere-face-groups segments rings)
                 ;; For other primitives, create numeric IDs per triangle
                 (zipmap (range (count (:faces mesh)))
                         (map vector (:faces mesh))))]
    (assoc mesh :face-groups groups)))

;; ============================================================
;; Sharp edge detection
;; ============================================================

(defn- dot [[x1 y1 z1] [x2 y2 z2]]
  (+ (* x1 x2) (* y1 y2) (* z1 z2)))

(defn- build-edge-adjacency
  "Build a map of edge [v0 v1] (sorted) -> vector of triangle indices.
   Each edge maps to the 1 or 2 triangles that share it."
  [faces]
  (reduce-kv
    (fn [acc tri-idx [i j k]]
      (let [add-edge (fn [m a b]
                       (let [edge (if (< a b) [a b] [b a])]
                         (update m edge (fnil conj []) tri-idx)))]
        (-> acc
            (add-edge i j)
            (add-edge j k)
            (add-edge k i))))
    {}
    (vec faces)))

(defn find-sharp-edges
  "Find edges where adjacent triangle normals differ by more than angle-deg degrees.
   Returns a vector of {:edge [v0 v1] :positions [p0 p1] :angle degrees :midpoint [x y z]}.

   Options:
   - :angle  minimum dihedral angle in degrees (default 30)
   - :where  predicate fn called with [x y z] for BOTH edge endpoints;
             edge is included only if (where p0) AND (where p1) are truthy

   Usage:
     (find-sharp-edges mesh :angle 80)
     (find-sharp-edges mesh :angle 80 :where #(> (first %) 0))"
  [mesh & {:keys [angle where] :or {angle 30}}]
  (let [vertices (:vertices mesh)
        faces (:faces mesh)
        ;; Precompute triangle normals
        tri-normals (mapv (fn [[i j k]]
                            (compute-triangle-normal
                              (nth vertices i)
                              (nth vertices j)
                              (nth vertices k)))
                          faces)
        ;; Build edge -> [tri-a, tri-b] adjacency
        edge-adj (build-edge-adjacency faces)]
    (->> edge-adj
         (keep (fn [[edge tri-indices]]
                 ;; Only boundary edges have 1 triangle, interior have 2
                 (when (= 2 (count tri-indices))
                   (let [n1 (nth tri-normals (first tri-indices))
                         n2 (nth tri-normals (second tri-indices))
                         cos-a (dot n1 n2)
                         ;; Dihedral angle: supplement of angle between normals
                         ;; For a 90 deg edge, normals are perpendicular -> dot=0 -> angle=90 deg
                         edge-angle-rad (Math/acos (max -1 (min 1 cos-a)))
                         edge-angle-deg (* edge-angle-rad (/ 180 Math/PI))
                         [v0 v1] edge
                         p0 (nth vertices v0)
                         p1 (nth vertices v1)]
                     (when (and (> edge-angle-deg angle)
                                (or (nil? where)
                                    (and (where p0) (where p1))))
                       {:edge edge
                        :positions [p0 p1]
                        :normals [n1 n2]
                        :angle edge-angle-deg
                        :midpoint (v* (v+ p0 p1) 0.5)})))))
         vec)))

;; ============================================================
;; Chamfer/fillet geometry generation
;; ============================================================

(defn make-prism-along-edge
  "Create a triangular prism (wedge) along an edge for CSG chamfer.
   The prism sits in the corner between the two faces, with its
   cutting face at 45 deg to both surfaces.

   p0, p1: edge endpoints
   n1, n2: normals of the two adjacent faces
   d: chamfer distance

   Returns a mesh {:vertices [...] :faces [...]}."
  [p0 p1 n1 n2 d]
  (let [;; Edge direction
        edge-dir (normalize (v- p1 p0))
        ;; Extend beyond edge endpoints so adjacent prisms overlap,
        ;; preventing gaps on curved edges (circles, fillets)
        edge-margin (* d 0.3)
        ep0 (v+ p0 (v* edge-dir (- edge-margin)))
        ep1 (v+ p1 (v* edge-dir edge-margin))
        ;; The prism straddles the corner:
        ;; - corner: outside the mesh (along bisector)
        ;; - face1-pt: d INTO face-1 surface (along -n2, perpendicular to face-1)
        ;; - face2-pt: d INTO face-2 surface (along -n1, perpendicular to face-2)
        ;; The triangle covers the corner wedge from outside to d inside each face.
        bisector (normalize (v+ n1 n2))
        off-corner (v* bisector (* d 1.5))
        ;; Face points: d along the other face's inward normal (along the surface),
        ;; plus a margin OUTWARD past the surface (along own normal).
        ;; All 3 prism vertices end up OUTSIDE the mesh.
        ;; Their triangle's intersection with the mesh volume IS the corner wedge.
        margin (* d 0.5)
        off-f1 (v+ (v* n2 (- d)) (v* n1 margin))
        off-f2 (v+ (v* n1 (- d)) (v* n2 margin))
        ;; 6 vertices: 3 at each end of the prism
        v0 (v+ ep0 off-corner)   ;; corner (slightly outside)
        v1 (v+ ep0 off-f1)       ;; on face-1, d from edge
        v2 (v+ ep0 off-f2)       ;; on face-2, d from edge
        v3 (v+ ep1 off-corner)
        v4 (v+ ep1 off-f1)
        v5 (v+ ep1 off-f2)
        ;; Determine correct winding via signed volume.
        ;; If signed volume is negative, faces are inverted.
        base-faces [[0 2 1] [3 4 5]
                     [0 1 4] [0 4 3]
                     [0 3 5] [0 5 2]
                     [1 2 5] [1 5 4]]
        verts [v0 v1 v2 v3 v4 v5]
        signed-vol (reduce (fn [acc [i j k]]
                             (let [a (nth verts i)
                                   b (nth verts j)
                                   c (nth verts k)]
                               (+ acc (dot a (cross b c)))))
                           0 base-faces)
        faces (if (neg? signed-vol)
                (mapv (fn [[a b c]] [a c b]) base-faces)
                base-faces)]
    {:type :mesh
     :primitive :chamfer-prism
     :vertices verts
     :faces faces})) ;; n1-n2 side (the cutting face)

;; ============================================================
;; Edge loop ordering
;; ============================================================

(defn- edges->loops
  "Order a collection of edges into connected loops.
   Each edge is {:edge [v0 v1] :positions [p0 p1] :normals [n1 n2] ...}.
   Returns a vector of loops, where each loop is a vector of
   {:position [x y z] :normals [n1 n2]} for each vertex in order.
   Closed loops have first vertex = last vertex."
  [edges]
  (when (seq edges)
    (let [;; Build adjacency: vertex-idx -> vector of edge records
          adj (reduce (fn [m {:keys [edge] :as e}]
                        (let [[v0 v1] edge]
                          (-> m
                              (update v0 (fnil conj []) e)
                              (update v1 (fnil conj []) e))))
                      {} edges)
          ;; Walk from an edge, following shared vertices
          walk-loop (fn [start-edge used]
                      (loop [current-edge start-edge
                             current-v (first (:edge start-edge))
                             path [{:position (first (:positions start-edge))
                                    :normals (:normals start-edge)}]
                             used (conj used (:edge start-edge))]
                        (let [;; Other vertex of current edge
                              [v0 v1] (:edge current-edge)
                              next-v (if (= current-v v0) v1 v0)
                              next-pos (if (= current-v v0)
                                         (second (:positions current-edge))
                                         (first (:positions current-edge)))
                              path (conj path {:position next-pos
                                               :normals (:normals current-edge)})
                              ;; Find next unused edge at next-v
                              candidates (filterv #(not (used (:edge %)))
                                                  (get adj next-v []))
                              next-edge (first candidates)]
                          (if next-edge
                            (recur next-edge next-v path (conj used (:edge next-edge)))
                            {:path path :used used}))))]
      ;; Collect all loops
      (loop [remaining edges
             used #{}
             loops []]
        (if-let [start (first (remove #(used (:edge %)) remaining))]
          (let [{:keys [path used]} (walk-loop start used)]
            (recur remaining used (conj loops path)))
          loops)))))

(defn- compute-strip-offsets
  "For a vertex on an edge loop, compute offset points for the strip cross-section.
   segments=1: chamfer -> returns [corner f1 f2] (3 points)
   segments>1: fillet -> returns [corner f1 A arc1..arc_{N-1} B f2] (segments+4 points)
     where A,B are mesh-surface tangent points and the arc is a true quarter-circle
     from fillet center C with radius d. The cross-section has a concave arc face."
  [position normals d segments]
  (let [[n1 n2] normals
        bisector (normalize (v+ n1 n2))
        corner (v+ position (v* bisector (* d 1.5)))
        margin (* d 0.5)
        f1 (v+ position (v+ (v* n2 (- d)) (v* n1 margin)))
        f2 (v+ position (v+ (v* n1 (- d)) (v* n2 margin)))]
    (if (<= segments 1)
      ;; Chamfer: triangle cross-section
      [corner f1 f2]
      ;; Fillet: concave arc cross-section
      ;; C = fillet center (inside mesh, distance d from both faces)
      ;; A = C + d*n1 (tangent point on face 1, on mesh surface)
      ;; B = C + d*n2 (tangent point on face 2, on mesh surface)
      ;; Arc from A to B via slerp on n1->n2, radius d from C
      (let [cos-a (dot n1 n2)
            denom (max 0.01 (+ 1 cos-a))
            center (v- position (v* (v+ n1 n2) (/ d denom)))
            theta (Math/acos (max -1 (min 1 cos-a)))
            sin-theta (Math/sin theta)
            ;; Arc points from A (tangent on face 1) to B (tangent on face 2)
            arc-pts (mapv (fn [i]
                            (let [t (/ i (dec segments))
                                  dir (if (< (Math/abs sin-theta) 0.001)
                                        (normalize (v+ (v* n1 (- 1 t)) (v* n2 t)))
                                        (let [s1 (/ (Math/sin (* (- 1 t) theta)) sin-theta)
                                              s2 (/ (Math/sin (* t theta)) sin-theta)]
                                          (v+ (v* n1 s1) (v* n2 s2))))]
                              (v+ center (v* (normalize dir) d))))
                          (range segments))]
        ;; Cross-section: corner, f1, A, arc..., B, f2
        ;; = corner, f1, arc[0]=A, arc[1], ..., arc[N-1]=B, f2
        (into [corner f1] (conj arc-pts f2))))))

(defn- compute-fillet-arc
  "Compute arc points from f1 to f2 for the fillet fill solid.
   The arc curves AWAY from corner (toward the mesh interior),
   creating a convex quarter-circle fill.
   Returns a vector of arc points [arc0=f1, arc1, ..., arcN=f2]."
  [position normals d segments]
  (let [[n1 n2] normals
        bisector (normalize (v+ n1 n2))
        margin (* d 0.5)
        f1 (v+ position (v+ (v* n2 (- d)) (v* n1 margin)))
        f2 (v+ position (v+ (v* n1 (- d)) (v* n2 margin)))
        ;; The arc center is inside the mesh: at position offset d along
        ;; each face's inward direction (away from the edge, along the surface)
        ;; For a 90 deg edge: center is at position + (-n2*d)*component_along_face1 + ...
        ;; Simplified: the center is at the point equidistant from f1 and f2,
        ;; at distance d from both, on the mesh-interior side
        mid-chord (v* (v+ f1 f2) 0.5)
        corner (v+ position (v* bisector (* d 1.5)))
        ;; Direction from corner toward chord midpoint (toward mesh interior)
        toward-mesh (normalize (v- mid-chord corner))
        ;; Arc radius = distance from f1 to f2 / (2 * sin(half_arc_angle))
        ;; For simplicity, use slerp-like interpolation:
        ;; Compute arc via trigonometric blend between f1-direction and f2-direction
        ;; from the fillet center
        cos-a (max -1 (min 1 (dot (normalize n1) (normalize n2))))
        dihedral (Math/acos cos-a)
        ;; Fillet arc center: on the mesh interior, d from both surfaces
        ;; center = position + d * (-n1_surface_tangent) + d * (-n2_surface_tangent)
        ;; Approximate: midpoint of chord + toward_mesh * sagitta_depth
        chord-half-len (* 0.5 (magnitude (v- f2 f1)))
        ;; For a true circle tangent to both faces at f1, f2:
        ;; radius = chord_half_len / sin(dihedral/2)
        arc-r (if (> (Math/abs (Math/sin (/ dihedral 2))) 0.01)
                (/ chord-half-len (Math/sin (/ dihedral 2)))
                chord-half-len)
        sagitta (- arc-r (Math/sqrt (max 0 (- (* arc-r arc-r) (* chord-half-len chord-half-len)))))
        arc-center (v+ mid-chord (v* toward-mesh sagitta))]
    ;; Generate arc points using angle sweep from f1 to f2 around arc-center
    (mapv (fn [i]
            (let [u (/ i segments)
                  ;; Blend from f1 to f2, with circular bulge toward mesh
                  base (v+ f1 (v* (v- f2 f1) u))
                  ;; Bulge: sin curve, max at midpoint
                  bulge (* sagitta (Math/sin (* u Math/PI)))]
              (v+ base (v* toward-mesh bulge))))
          (range (inc segments)))))

(defn build-fillet-fill-strip
  "Build a fill solid for fillet: a rounded strip that gets union'd with the
   chamfered mesh to produce rounded edges. The fill has a circular-segment
   cross-section that bridges the flat chamfer cut with a convex arc.

   The fill cross-section at each station has:
   - f1, f2: the chamfer face endpoints (on the mesh surfaces)
   - arc points between f1 and f2, bulging toward the mesh interior

   The fill is a closed solid: arc surface + flat bottom (chamfer face) + end caps."
  [edges distance segments]
  (when (seq edges)
    (let [loops (edges->loops edges)]
      (when (seq loops)
        (let [all-verts (atom [])
              all-faces (atom [])
              ;; Each station has: segments+1 arc points + 2 chord endpoints = segments+1 points
              ;; (arc0=f1, arc1, ..., arcN=f2) -- chord endpoints are arc endpoints
              pts-per-station (inc segments)
              _ (doseq [loop-path loops]
                  (let [n (count loop-path)
                        p-first (:position (first loop-path))
                        p-last (:position (last loop-path))
                        closed? (< (magnitude (v- p-first p-last)) 0.01)
                        ;; Compute arc points for each station
                        stations (mapv (fn [{:keys [position normals]}]
                                        (compute-fillet-arc position normals distance segments))
                                      loop-path)
                        base-idx (count @all-verts)
                        _ (doseq [station stations]
                            (swap! all-verts into station))
                        seg-count (if closed? n (dec n))]
                    ;; Connect adjacent stations
                    (doseq [i (range seg-count)]
                      (let [j (mod (inc i) n)
                            bi (+ base-idx (* i pts-per-station))
                            bj (+ base-idx (* j pts-per-station))]
                        ;; Arc surface: quads between consecutive arc points
                        (doseq [k (range segments)]
                          (swap! all-faces into
                                 [[(+ bi k) (+ bj k) (+ bj (inc k))]
                                  [(+ bi k) (+ bj (inc k)) (+ bi (inc k))]]))
                        ;; Flat bottom: single quad from f1_i,f2_i to f1_j,f2_j
                        ;; f1 = arc[0], f2 = arc[segments]
                        (let [f1i (+ bi 0)
                              f2i (+ bi segments)
                              f1j (+ bj 0)
                              f2j (+ bj segments)]
                          (swap! all-faces into
                                 [[f1i f2i f2j]
                                  [f1i f2j f1j]]))))
                    ;; End caps for open loops
                    (when-not closed?
                      (let [;; Start cap: fan from f1 to arc points to f2
                            b0 base-idx
                            _ (doseq [k (range (dec segments))]
                                (swap! all-faces conj
                                       [(+ b0 0) (+ b0 (+ k 2)) (+ b0 (+ k 1))]))
                            ;; End cap
                            last-i (dec n)
                            bn (+ base-idx (* last-i pts-per-station))
                            _ (doseq [k (range (dec segments))]
                                (swap! all-faces conj
                                       [(+ bn 0) (+ bn (+ k 1)) (+ bn (+ k 2))]))]))))
              verts (vec @all-verts)
              base-faces (vec @all-faces)
              ;; Signed volume winding check
              signed-vol (reduce (fn [acc [i j k]]
                                   (let [a (nth verts i)
                                         b (nth verts j)
                                         c (nth verts k)]
                                     (+ acc (dot a (cross b c)))))
                                 0 base-faces)
              faces (if (neg? signed-vol)
                      (mapv (fn [[a b c]] [a c b]) base-faces)
                      base-faces)]
          {:type :mesh
           :primitive :fillet-fill
           :vertices verts
           :faces faces})))))

;; ============================================================
;; Fillet tube (cylinder-along-edge approach)
;; ============================================================

(defn- compute-tube-offsets
  "For a vertex on an edge loop, compute the fillet tube cross-section.
   Returns [center arc0 arc1 ... arcN] where:
   - center is inside the mesh (at distance r from both faces)
   - arc0..arcN form a circular arc from face1 tangent to face2 tangent"
  [position normals r segments]
  (let [[n1 n2] normals
        ;; Center of fillet circle: inside mesh, distance r from both face planes
        ;; Solve: dot(C - P, n1) = -r AND dot(C - P, n2) = -r
        ;; => C = P - r/(1 + n1.n2) * (n1 + n2)
        cos-a (dot n1 n2)
        denom (max 0.01 (+ 1 cos-a))
        center (v- position (v* (v+ n1 n2) (/ r denom)))
        ;; Arc sweeps from n1 direction to n2 direction around center
        ;; using spherical linear interpolation (slerp)
        theta (Math/acos (max -1 (min 1 cos-a)))
        sin-theta (Math/sin theta)
        arc-pts (mapv (fn [i]
                        (let [t (/ i segments)
                              dir (if (< (Math/abs sin-theta) 0.001)
                                    (normalize (v+ (v* n1 (- 1 t)) (v* n2 t)))
                                    (let [s1 (/ (Math/sin (* (- 1 t) theta)) sin-theta)
                                          s2 (/ (Math/sin (* t theta)) sin-theta)]
                                      (v+ (v* n1 s1) (v* n2 s2))))]
                          (v+ center (v* (normalize dir) r))))
                      (range (inc segments)))]
    (into [center] arc-pts)))

(defn- make-tube-segment
  "Create a single closed tube segment (pie-slice cylinder) along one edge.
   Returns a manifold mesh {:vertices [...] :faces [...]}.
   Face winding is explicitly computed for outward normals (no signed-vol flip)."
  [p0 p1 n1 n2 r segments]
  (let [edge-dir (normalize (v- p1 p0))
        edge-margin (* r 0.3)
        ep0 (v+ p0 (v* edge-dir (- edge-margin)))
        ep1 (v+ p1 (v* edge-dir edge-margin))
        ;; Compute tube cross-section at each endpoint
        station0 (compute-tube-offsets ep0 [n1 n2] r segments)
        station1 (compute-tube-offsets ep1 [n1 n2] r segments)
        ;; Vertex layout: station0 (indices 0..pts-1) then station1 (indices pts..2*pts-1)
        ;; Each station: [center, arc0, arc1, ..., arcN] = segments+2 points
        pts (+ 2 segments)
        verts (into (vec station0) station1)
        last-arc (inc segments)
        ;; Build faces with explicit outward-normal winding.
        ;; Walls/arc: outward normals point away from pie-slice interior.
        ;; Caps: outward normals point along +/-edge direction.
        faces (atom [])
        ;; Wall 1 (center->arc[0] side): normal points away from arc interior
        _ (swap! faces into [[(+ pts 1) pts 0] [1 (+ pts 1) 0]])
        ;; Wall 2 (center->arc[N] side): normal points away from arc interior
        _ (swap! faces into [[(+ pts last-arc) last-arc 0] [pts (+ pts last-arc) 0]])
        ;; Arc surface: normals point outward (away from center)
        _ (doseq [k (range segments)]
            (let [k1 (inc k) k2 (+ k 2)]
              (swap! faces into [[(+ pts k2) (+ pts k1) k1] [(+ pts k2) k1 k2]])))
        ;; End cap at ep0 (normal points toward -edge_dir)
        _ (doseq [k (range segments)]
            (swap! faces conj [0 (+ k 2) (+ k 1)]))
        ;; End cap at ep1 (normal points toward +edge_dir)
        _ (doseq [k (range segments)]
            (swap! faces conj [pts (+ pts k 1) (+ pts k 2)]))]
    {:type :mesh
     :primitive :fillet-tube-segment
     :vertices verts
     :faces (vec @faces)}))

(defn- make-fillet-cutter
  "Create a single closed fillet cutter prism along one edge.
   Cross-section: [corner, f1, A, arc1..arc_{N-1}, B, f2] -- a polygon with
   a concave arc face that produces a convex fillet when subtracted from mesh.
   Returns a manifold mesh."
  [p0 p1 n1 n2 d segments]
  (let [edge-dir (normalize (v- p1 p0))
        edge-margin (* d 0.3)
        ep0 (v+ p0 (v* edge-dir (- edge-margin)))
        ep1 (v+ p1 (v* edge-dir edge-margin))
        ;; Compute cross-section at each endpoint
        station0 (compute-strip-offsets ep0 [n1 n2] d segments)
        station1 (compute-strip-offsets ep1 [n1 n2] d segments)
        ;; Vertex layout: station0 then station1
        ;; Cross-section: [corner, f1, arc0...arc_{N-1}, f2] = segments+3 points
        pts (count station0)
        verts (into (vec station0) station1)
        ;; Build faces: quad for each polygon edge between stations
        ;; + end caps (fan from corner vertex)
        faces (atom [])
        ;; Side walls: connect polygon edges between stations
        _ (doseq [e (range pts)]
            (let [e1 (mod (inc e) pts)]
              (swap! faces into
                     [[(+ e pts) e e1] [(+ e pts) e1 (+ e1 pts)]])))
        ;; End cap at ep0 (fan from vertex 0 = corner)
        _ (doseq [k (range 1 (dec pts))]
            (swap! faces conj [0 (inc k) k]))
        ;; End cap at ep1 (fan from vertex pts = corner)
        _ (doseq [k (range 1 (dec pts))]
            (swap! faces conj [pts (+ pts k) (+ pts k 1)]))
        base-faces (vec @faces)
        ;; Signed volume winding check
        signed-vol (reduce (fn [acc [i j k]]
                             (let [a (nth verts i)
                                   b (nth verts j)
                                   c (nth verts k)]
                               (+ acc (dot a (cross b c)))))
                           0 base-faces)
        final-faces (if (neg? signed-vol)
                      (mapv (fn [[a b c]] [a c b]) base-faces)
                      base-faces)]
    {:type :mesh
     :primitive :fillet-cutter
     :vertices verts
     :faces final-faces}))

(defn build-fillet-cutters
  "Build individual fillet cutter prisms, one per edge.
   Each cutter has a concave arc cross-section.
   Returns a vector of manifold meshes, or nil."
  [edges distance segments]
  (when (seq edges)
    (let [cutters (mapv (fn [{:keys [positions normals]}]
                          (let [[p0 p1] positions
                                [n1 n2] normals]
                            (make-fillet-cutter p0 p1 n1 n2 distance segments)))
                        edges)]
      (when (seq cutters) cutters))))

(defn find-fillet-vertices
  "Find vertices where 2+ selected edges converge.
   Returns a seq of {:position [...] :normals [n1 n2 n3...]} for vertices
   that need spherical vertex blending."
  [edges]
  (let [;; Group edges by vertex position (rounded to avoid floating point issues)
        vertex-map (atom {})]
    (doseq [{:keys [positions normals]} edges
            [pos _] (map vector positions (repeat normals))]
      (let [k (mapv #(Math/round (double (* % 1000))) pos)]
        (swap! vertex-map update k
               (fn [old]
                 (let [entry (or old {:position pos :normal-set #{}})
                       [n1 n2] normals]
                   (-> entry
                       (update :normal-set conj
                               (mapv #(Math/round (double (* % 100))) n1)
                               (mapv #(Math/round (double (* % 100))) n2))))))))
    ;; Return vertices with 3+ unique normals (meaning 2+ edges converge)
    (->> (vals @vertex-map)
         (filter #(>= (count (:normal-set %)) 3))
         (mapv (fn [{:keys [position normal-set]}]
                 {:position position
                  :normals (mapv (fn [rn] (mapv #(/ % 100.0) rn))
                                (vec normal-set))})))))

(defn compute-fillet-vertex-center
  "Compute the sphere center for a fillet vertex where N faces meet.
   The center is at distance r from each face plane.
   Works for any face angles, not just orthogonal."
  [position normals r]
  (let [n-count (count normals)
        sum-n (reduce v+ normals)
        ;; For orthogonal normals, avg-dot = 1. For non-orthogonal, adjust.
        avg-dot (/ (reduce + (map #(dot sum-n %) normals)) n-count)
        correction (max 0.5 avg-dot)]
    (v- position (v* sum-n (/ r correction)))))

(defn chamfer-strip
  "Generate a single continuous strip mesh along sharp edges for CSG chamfer.
   More robust and smoother than individual prisms on curved edges.

   Returns a single mesh, or nil if no edges found.
   Options same as find-sharp-edges."
  [mesh distance & {:keys [angle where] :or {angle 30}}]
  (let [edges (find-sharp-edges mesh :angle angle :where where)]
    (when (seq edges)
      (let [loops (edges->loops edges)
            ;; Build one strip mesh from all loops
            all-verts (atom [])
            all-faces (atom [])
            _ (doseq [loop-path loops]
                (let [n (count loop-path)
                      ;; Is it a closed loop? Check if first and last positions are close
                      p-first (:position (first loop-path))
                      p-last (:position (last loop-path))
                      closed? (< (magnitude (v- p-first p-last)) 0.01)
                      ;; For each vertex, compute 3 offset points
                      stations (mapv (fn [{:keys [position normals]}]
                                      (compute-strip-offsets position normals distance 1))
                                    loop-path)
                      base-idx (count @all-verts)
                      ;; Add all vertices: 3 per station (corner, f1, f2)
                      _ (doseq [[corner f1 f2] stations]
                          (swap! all-verts into [corner f1 f2]))
                      ;; Connect adjacent stations with 6 triangles each
                      ;; Station i has vertices at base-idx + i*3 + {0,1,2}
                      seg-count (if closed? n (dec n))]
                  (doseq [i (range seg-count)]
                    (let [j (mod (inc i) n)
                          ;; Vertex indices for station i and j
                          ci (+ base-idx (* i 3))       ; corner i
                          f1i (+ base-idx (* i 3) 1)    ; f1 i
                          f2i (+ base-idx (* i 3) 2)    ; f2 i
                          cj (+ base-idx (* j 3))       ; corner j
                          f1j (+ base-idx (* j 3) 1)    ; f1 j
                          f2j (+ base-idx (* j 3) 2)]   ; f2 j
                      ;; 3 quads = 6 triangles between stations
                      (swap! all-faces into
                             [[ci cj f1j] [ci f1j f1i]      ;; corner-f1 side
                              [ci f2i f2j] [ci f2j cj]      ;; corner-f2 side
                              [f1i f1j f2j] [f1i f2j f2i]]) ;; f1-f2 side (cutting face)
                      ))
                  ;; Cap open loops at both ends
                  (when-not closed?
                    (let [;; Start cap
                          c0 base-idx f10 (+ base-idx 1) f20 (+ base-idx 2)
                          ;; End cap
                          last-i (dec n)
                          cn (+ base-idx (* last-i 3))
                          f1n (+ base-idx (* last-i 3) 1)
                          f2n (+ base-idx (* last-i 3) 2)]
                      (swap! all-faces into
                             [[c0 f20 f10]       ;; start cap
                              [cn f1n f2n]])))))  ;; end cap
            verts (vec @all-verts)
            base-faces (vec @all-faces)
            ;; Signed volume winding check
            signed-vol (reduce (fn [acc [i j k]]
                                 (let [a (nth verts i)
                                       b (nth verts j)
                                       c (nth verts k)]
                                   (+ acc (dot a (cross b c)))))
                               0 base-faces)
            faces (if (neg? signed-vol)
                    (mapv (fn [[a b c]] [a c b]) base-faces)
                    base-faces)]
        {:type :mesh
         :primitive :chamfer-strip
         :vertices verts
         :faces faces}))))

(defn build-chamfer-strip
  "Build a continuous strip mesh from pre-filtered edge records.
   Takes a vector of {:edge :positions :normals ...} as returned by find-sharp-edges.
   segments: 1 = chamfer (flat cut), >1 = fillet (arc with N segments).
   Returns a single manifold mesh for CSG subtraction, or nil."
  [edges distance & {:keys [segments] :or {segments 1}}]
  (when (seq edges)
    (let [loops (edges->loops edges)]
      (when (seq loops)
        (let [all-verts (atom [])
              all-faces (atom [])
              ;; Points per station:
              ;; segments=1: 3 points [corner, f1, f2]
              ;; segments>1: segments+3 points [corner, f1, arc0..arc_{N-1}, f2]
              pts-per-station (if (<= segments 1) 3 (+ 3 segments))
              _ (doseq [loop-path loops]
                  (let [n (count loop-path)
                        p-first (:position (first loop-path))
                        p-last (:position (last loop-path))
                        closed? (< (magnitude (v- p-first p-last)) 0.01)
                        stations (mapv (fn [{:keys [position normals]}]
                                        (compute-strip-offsets position normals distance segments))
                                      loop-path)
                        base-idx (count @all-verts)
                        _ (doseq [station stations]
                            (swap! all-verts into station))
                        seg-count (if closed? n (dec n))]
                    ;; Connect adjacent stations: quad for each edge of the polygon
                    ;; Polygon vertices: 0, 1, ..., M-1 (wrap: M-1 -> 0)
                    (doseq [i (range seg-count)]
                      (let [j (mod (inc i) n)
                            bi (+ base-idx (* i pts-per-station))
                            bj (+ base-idx (* j pts-per-station))]
                        (doseq [e (range pts-per-station)]
                          (let [e1 (mod (inc e) pts-per-station)]
                            (swap! all-faces into
                                   [[(+ bi e) (+ bj e) (+ bj e1)]
                                    [(+ bi e) (+ bj e1) (+ bi e1)]])))))
                    ;; Cap open loops: fan triangulation from vertex 0
                    (when-not closed?
                      (let [b0 base-idx
                            _ (doseq [k (range 1 (dec pts-per-station))]
                                (swap! all-faces conj
                                       [(+ b0 0) (+ b0 (inc k)) (+ b0 k)]))
                            last-i (dec n)
                            bn (+ base-idx (* last-i pts-per-station))
                            _ (doseq [k (range 1 (dec pts-per-station))]
                                (swap! all-faces conj
                                       [(+ bn 0) (+ bn k) (+ bn (inc k))]))]))))
              verts (vec @all-verts)
              base-faces (vec @all-faces)
              signed-vol (reduce (fn [acc [i j k]]
                                   (let [a (nth verts i)
                                         b (nth verts j)
                                         c (nth verts k)]
                                     (+ acc (dot a (cross b c)))))
                                 0 base-faces)
              faces (if (neg? signed-vol)
                      (mapv (fn [[a b c]] [a c b]) base-faces)
                      base-faces)]
          {:type :mesh
           :primitive :chamfer-strip
           :vertices verts
           :faces faces})))))

(defn chamfer-prisms
  "Generate individual triangular prism meshes along sharp edges.
   Returns a vector of prism meshes, or nil if no edges found.

   These prisms should be union'd together (via mesh-union) and then
   subtracted from the original mesh (via mesh-difference) to create chamfers.
   Use chamfer-edges for the complete workflow.

   distance: chamfer size in mm
   Options (same as find-sharp-edges):
   - :angle  minimum dihedral angle (default 30)
   - :where  predicate on vertex positions (both endpoints must match)"
  [mesh distance & {:keys [angle where] :or {angle 30}}]
  (let [edges (find-sharp-edges mesh :angle angle :where where)]
    (when (seq edges)
      (mapv (fn [{:keys [positions normals]}]
              (let [[p0 p1] positions
                    [n1 n2] normals]
                (make-prism-along-edge p0 p1 n1 n2 distance)))
            edges))))
