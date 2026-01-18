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
   Box faces are defined by triangle indices from make-box-faces."
  []
  {:back   [[0 1 2] [0 2 3]]
   :front  [[4 6 5] [4 7 6]]
   :bottom [[0 4 5] [0 5 1]]
   :top    [[2 6 7] [2 7 3]]
   :left   [[0 3 7] [0 7 4]]
   :right  [[1 5 6] [1 6 2]]})

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
   Sphere has no distinct faces, returns single :surface group."
  [segments rings]
  {:surface (vec
             (apply concat
                    (for [ring (range rings)
                          seg (range segments)]
                      (let [next-seg (mod (inc seg) segments)
                            i0 (+ seg (* ring segments))
                            i1 (+ next-seg (* ring segments))
                            i2 (+ seg (* (inc ring) segments))
                            i3 (+ next-seg (* (inc ring) segments))]
                        [[i0 i2 i1] [i1 i2 i3]]))))})

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
