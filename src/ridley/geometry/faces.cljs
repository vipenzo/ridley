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
  "Build a map of edge [v0 v1] (sorted) → vector of triangle indices.
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

(defn ^:export find-sharp-edges
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
        ;; Build edge → [tri-a, tri-b] adjacency
        edge-adj (build-edge-adjacency faces)]
    (->> edge-adj
         (keep (fn [[edge tri-indices]]
                 ;; Only boundary edges have 1 triangle, interior have 2
                 (when (= 2 (count tri-indices))
                   (let [n1 (nth tri-normals (first tri-indices))
                         n2 (nth tri-normals (second tri-indices))
                         cos-a (dot n1 n2)
                         ;; Dihedral angle: supplement of angle between normals
                         ;; For a 90° edge, normals are perpendicular → dot=0 → angle=90°
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

(defn ^:export make-prism-along-edge
  "Create a triangular prism (wedge) along an edge for CSG chamfer.
   The prism sits in the corner between the two faces, with its
   cutting face at 45° to both surfaces.

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
    (let [;; Build adjacency: vertex-idx → vector of edge records
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
  "For a vertex on an edge loop, compute the 3 offset points for the
   chamfer strip cross-section. Returns [corner f1 f2]."
  [position normals d]
  (let [[n1 n2] normals
        bisector (normalize (v+ n1 n2))
        corner (v+ position (v* bisector (* d 1.5)))
        margin (* d 0.5)
        f1 (v+ position (v+ (v* n2 (- d)) (v* n1 margin)))
        f2 (v+ position (v+ (v* n1 (- d)) (v* n2 margin)))]
    [corner f1 f2]))

(defn ^:export chamfer-strip
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
                                      (compute-strip-offsets position normals distance))
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

(defn ^:export build-chamfer-strip
  "Build a continuous strip mesh from pre-filtered edge records.
   Takes a vector of {:edge :positions :normals ...} as returned by find-sharp-edges.
   Returns a single manifold mesh for CSG subtraction, or nil."
  [edges distance]
  (when (seq edges)
    (let [loops (edges->loops edges)]
      (when (seq loops)
        (let [all-verts (atom [])
              all-faces (atom [])
              _ (doseq [loop-path loops]
                  (let [n (count loop-path)
                        p-first (:position (first loop-path))
                        p-last (:position (last loop-path))
                        closed? (< (magnitude (v- p-first p-last)) 0.01)
                        stations (mapv (fn [{:keys [position normals]}]
                                        (compute-strip-offsets position normals distance))
                                      loop-path)
                        base-idx (count @all-verts)
                        _ (doseq [[corner f1 f2] stations]
                            (swap! all-verts into [corner f1 f2]))
                        seg-count (if closed? n (dec n))]
                    (doseq [i (range seg-count)]
                      (let [j (mod (inc i) n)
                            ci (+ base-idx (* i 3))
                            f1i (+ base-idx (* i 3) 1)
                            f2i (+ base-idx (* i 3) 2)
                            cj (+ base-idx (* j 3))
                            f1j (+ base-idx (* j 3) 1)
                            f2j (+ base-idx (* j 3) 2)]
                        (swap! all-faces into
                               [[ci cj f1j] [ci f1j f1i]
                                [ci f2i f2j] [ci f2j cj]
                                [f1i f1j f2j] [f1i f2j f2i]])))
                    (when-not closed?
                      (let [c0 base-idx f10 (+ base-idx 1) f20 (+ base-idx 2)
                            last-i (dec n)
                            cn (+ base-idx (* last-i 3))
                            f1n (+ base-idx (* last-i 3) 1)
                            f2n (+ base-idx (* last-i 3) 2)]
                        (swap! all-faces into
                               [[c0 f20 f10]
                                [cn f1n f2n]])))))
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

(defn ^:export chamfer-prisms
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
