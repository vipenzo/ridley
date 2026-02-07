(ns ridley.turtle.attachment
  "Attachment system: attach to meshes and faces for direct manipulation.

   This module contains the attachment logic extracted from turtle/core.cljs.
   Enables attaching the turtle to mesh elements (creation pose, faces) and
   manipulating them using standard turtle commands."
  (:require [ridley.math :as math]
            [ridley.turtle.extrusion :as extrusion]))

;; --- Re-export math utilities used throughout ---
(def v+ math/v+)
(def v- math/v-)
(def v* math/v*)
(def dot math/dot)
(def cross math/cross)
(def magnitude math/magnitude)
(def normalize math/normalize)
(def rotate-around-axis math/rotate-around-axis)
(def rotate-point-around-axis math/rotate-point-around-axis)

;; --- Re-export extrusion utilities we depend on ---
(def deg->rad extrusion/deg->rad)

;; ============================================================
;; Mesh manipulation when attached
;; ============================================================

(defn mesh-centroid
  "Calculate the centroid of a mesh from its vertices."
  [mesh]
  (let [verts (:vertices mesh)
        n (count verts)]
    (if (pos? n)
      (v* (reduce v+ [0 0 0] verts) (/ 1 n))
      [0 0 0])))

(defn translate-mesh
  "Translate all vertices of a mesh by an offset vector."
  [mesh offset]
  (-> mesh
      (update :vertices (fn [verts] (mapv #(v+ % offset) verts)))
      (update :creation-pose
              (fn [pose]
                (when pose
                  (update pose :position #(v+ % offset)))))))

(defn rotate-mesh
  "Rotate all vertices of a mesh around its centroid by angle (radians) around axis.
   Also rotates the creation-pose heading and up vectors."
  [mesh axis angle]
  (let [centroid (mesh-centroid mesh)
        rotate-vertex (fn [pt]
                        (let [rel (v- pt centroid)
                              rotated (rotate-point-around-axis rel axis angle)]
                          (v+ centroid rotated)))]
    (-> mesh
        (update :vertices (fn [verts] (mapv rotate-vertex verts)))
        (update :creation-pose
                (fn [pose]
                  (when pose
                    (-> pose
                        (update :position rotate-vertex)
                        (update :heading #(rotate-around-axis % axis angle))
                        (update :up #(rotate-around-axis % axis angle)))))))))

(defn scale-mesh
  "Scale all vertices of a mesh uniformly from its centroid."
  [mesh factor]
  (let [centroid (mesh-centroid mesh)]
    (-> mesh
        (update :vertices
                (fn [verts]
                  (mapv (fn [v]
                          (let [rel (v- v centroid)
                                scaled (v* rel factor)]
                            (v+ centroid scaled)))
                        verts))))))

(defn replace-mesh-in-state
  "Replace a mesh in the state's meshes vector.
   If the old mesh is not found, adds the new mesh to the end."
  [state old-mesh new-mesh]
  (let [meshes (:meshes state)
        found? (some #(identical? % old-mesh) meshes)]
    (if found?
      (update state :meshes
              (fn [ms] (mapv #(if (identical? % old-mesh) new-mesh %) ms)))
      ;; Not found - add the new mesh
      (update state :meshes conj new-mesh))))

(defn rotate-mesh-around-point
  "Rotate all vertices of a mesh around a given pivot point by angle (radians) around axis.
   Also rotates the creation-pose heading and up vectors, and rotates creation-pose position
   around the pivot."
  [mesh axis angle pivot]
  (let [rotate-vertex (fn [pt]
                        (let [rel (v- pt pivot)
                              rotated (rotate-point-around-axis rel axis angle)]
                          (v+ pivot rotated)))]
    (-> mesh
        (update :vertices (fn [verts] (mapv rotate-vertex verts)))
        (update :creation-pose
                (fn [pose]
                  (when pose
                    (-> pose
                        (update :position rotate-vertex)
                        (update :heading #(rotate-around-axis % axis angle))
                        (update :up #(rotate-around-axis % axis angle)))))))))

(defn rotate-attached-mesh
  "Rotate the attached mesh around the turtle position (attachment point) using the given axis.
   This ensures objects rotate around their attachment point, not their geometric center."
  [state axis angle-deg]
  (let [attachment (:attached state)
        mesh (:mesh attachment)
        rad (deg->rad angle-deg)
        pivot (:position state)
        new-mesh (rotate-mesh-around-point mesh axis rad pivot)
        ;; Also rotate the turtle's heading and up
        new-heading (rotate-around-axis (:heading state) axis rad)
        new-up (rotate-around-axis (:up state) axis rad)]
    (-> state
        (replace-mesh-in-state mesh new-mesh)
        (assoc :heading new-heading)
        (assoc :up new-up)
        (assoc-in [:attached :mesh] new-mesh)
        (assoc-in [:attached :original-pose] (:creation-pose new-mesh)))))

(defn scale-attached-mesh
  "Scale the attached mesh uniformly from its centroid."
  [state factor]
  (let [attachment (:attached state)
        mesh (:mesh attachment)
        new-mesh (scale-mesh mesh factor)]
    (-> state
        (replace-mesh-in-state mesh new-mesh)
        (assoc-in [:attached :mesh] new-mesh))))

(defn move-attached-mesh
  "Move the attached mesh along the turtle's heading direction."
  [state dist]
  (let [attachment (:attached state)
        mesh (:mesh attachment)
        heading (:heading state)
        offset (v* heading dist)
        new-mesh (translate-mesh mesh offset)
        new-pos (v+ (:position state) offset)]
    (-> state
        (replace-mesh-in-state mesh new-mesh)
        (assoc :position new-pos)
        (assoc-in [:attached :mesh] new-mesh)
        (assoc-in [:attached :original-pose :position] new-pos))))

;; ============================================================
;; Face extrusion when attached to face
;; ============================================================

(defn extract-face-perimeter
  "Extract ordered perimeter vertices from face triangles.
   Returns vector of vertex indices in order around the face boundary."
  [triangles]
  (let [;; Collect all edges from triangles
        edges (mapcat (fn [[a b c]] [[a b] [b c] [c a]]) triangles)
        ;; Count edge occurrences (boundary edges appear once, internal edges twice)
        edge-counts (frequencies (map (fn [[a b]] #{a b}) edges))
        ;; Keep only boundary edges (appear once)
        boundary-edges (filter (fn [[a b]] (= 1 (get edge-counts #{a b}))) edges)
        ;; Build adjacency map
        adj (reduce (fn [m [a b]] (update m a (fnil conj []) b))
                    {} boundary-edges)]
    ;; Walk the boundary starting from first edge
    (when (seq boundary-edges)
      (let [start (ffirst boundary-edges)]
        (loop [current start
               visited #{}
               result []]
          (if (contains? visited current)
            result
            (let [next-v (first (filter #(not (contains? visited %))
                                        (get adj current)))]
              (if next-v
                (recur next-v (conj visited current) (conj result current))
                (conj result current)))))))))

(defn build-face-extrusion
  "Extrude a face by creating new vertices and side faces.
   Returns updated mesh with extruded face."
  [mesh face-id face-info dist]
  (let [vertices (:vertices mesh)
        faces (:faces mesh)
        face-groups (:face-groups mesh)
        normal (:normal face-info)
        offset (v* normal dist)

        ;; Get face triangles and extract ordered perimeter
        face-triangles (:triangles face-info)
        perimeter (extract-face-perimeter face-triangles)
        n-old-verts (count vertices)
        n-perimeter (count perimeter)

        ;; Create new vertices at offset positions (for perimeter vertices only)
        new-verts (mapv (fn [idx]
                          (v+ (nth vertices idx) offset))
                        perimeter)

        ;; Map old perimeter vertex indices to new vertex indices
        index-mapping (zipmap perimeter
                              (range n-old-verts (+ n-old-verts n-perimeter)))

        ;; Build side faces (quads as two triangles each)
        ;; For each edge of the perimeter, create a quad connecting old to new vertices
        side-faces (vec
                    (mapcat
                     (fn [i]
                       (let [next-i (mod (inc i) n-perimeter)
                             ;; Old edge vertices (perimeter order)
                             b0 (nth perimeter i)
                             b1 (nth perimeter next-i)
                             ;; New edge vertices
                             t0 (get index-mapping b0)
                             t1 (get index-mapping b1)]
                         ;; Two triangles forming the quad
                         ;; Side faces need CCW winding when viewed from outside
                         ;; For extrusion outward: b0-b1 is bottom edge, t0-t1 is top edge
                         [[b0 b1 t1] [b0 t1 t0]]))
                     (range n-perimeter)))

        ;; Create new top face triangles (same winding, new indices)
        new-top-triangles (mapv (fn [[i j k]]
                                  [(get index-mapping i)
                                   (get index-mapping j)
                                   (get index-mapping k)])
                                face-triangles)

        ;; Remove old face triangles from faces list
        old-face-set (set face-triangles)
        remaining-faces (vec (remove old-face-set faces))

        ;; Combine all faces
        all-faces (vec (concat remaining-faces side-faces new-top-triangles))

        ;; Update face-groups
        side-face-id (keyword (str (name face-id) "-sides-" (count vertices)))
        new-face-groups (-> face-groups
                           (assoc face-id new-top-triangles)
                           (assoc side-face-id side-faces))]

    (assoc mesh
           :vertices (vec (concat vertices new-verts))
           :faces all-faces
           :face-groups new-face-groups)))

(defn extrude-attached-face
  "Extrude the attached face along its normal."
  [state dist]
  (let [attachment (:attached state)
        mesh (:mesh attachment)
        face-id (:face-id attachment)
        face-info (:face-info attachment)
        normal (:normal face-info)
        center (:center face-info)

        ;; Build extruded mesh
        new-mesh (build-face-extrusion mesh face-id face-info dist)

        ;; Calculate new face center
        new-center (v+ center (v* normal dist))

        ;; Update face info with new center and triangles
        new-triangles (get-in new-mesh [:face-groups face-id])
        new-face-info (-> face-info
                          (assoc :center new-center)
                          (assoc :triangles new-triangles)
                          ;; Update vertex indices to the new vertices
                          (assoc :vertices (vec (distinct (mapcat identity new-triangles)))))]
    (-> state
        (replace-mesh-in-state mesh new-mesh)
        (assoc :position new-center)
        (assoc-in [:attached :mesh] new-mesh)
        (assoc-in [:attached :face-info] new-face-info))))

(defn move-attached-face
  "Move the attached face along its normal WITHOUT creating side faces.
   Updates the vertex positions of the face directly."
  [state dist]
  (let [attachment (:attached state)
        mesh (:mesh attachment)
        face-info (:face-info attachment)
        normal (:normal face-info)
        offset (v* normal dist)

        ;; Get face vertices (from perimeter)
        face-triangles (:triangles face-info)
        perimeter (extract-face-perimeter face-triangles)

        ;; Move each perimeter vertex by offset
        vertices (:vertices mesh)
        new-vertices (reduce
                      (fn [verts idx]
                        (assoc verts idx (v+ (nth verts idx) offset)))
                      (vec vertices)
                      perimeter)

        new-mesh (assoc mesh :vertices new-vertices)
        new-center (v+ (:center face-info) offset)
        new-face-info (assoc face-info :center new-center)]

    (-> state
        (replace-mesh-in-state mesh new-mesh)
        (assoc :position new-center)
        (assoc-in [:attached :mesh] new-mesh)
        (assoc-in [:attached :face-info] new-face-info))))

(defn rotate-attached-face
  "Rotate the attached face around an axis passing through its center.
   Updates vertex positions of the face directly."
  [state axis angle-deg]
  (if-let [attachment (:attached state)]
    (let [mesh (:mesh attachment)
          face-info (:face-info attachment)
          center (:center face-info)
          rad (deg->rad angle-deg)

          ;; Get face vertices (from perimeter)
          face-triangles (:triangles face-info)
          perimeter (extract-face-perimeter face-triangles)]

      (if (seq perimeter)
        ;; Rotate each perimeter vertex around center
        (let [vertices (:vertices mesh)
              new-vertices (reduce
                            (fn [verts idx]
                              (let [v (nth verts idx)
                                    ;; Translate to origin, rotate, translate back
                                    v-centered (v- v center)
                                    v-rotated (rotate-point-around-axis v-centered axis rad)
                                    v-final (v+ v-rotated center)]
                                (assoc verts idx v-final)))
                            (vec vertices)
                            perimeter)

              new-mesh (assoc mesh :vertices new-vertices)

              ;; Also rotate the face normal and heading
              old-normal (:normal face-info)
              old-heading (:heading face-info)
              new-normal (rotate-around-axis old-normal axis rad)
              new-heading (rotate-around-axis old-heading axis rad)
              new-face-info (-> face-info
                                (assoc :normal new-normal)
                                (assoc :heading new-heading))

              ;; Update turtle orientation to match new face orientation
              new-up (normalize (cross new-normal new-heading))]

          (-> state
              (replace-mesh-in-state mesh new-mesh)
              (assoc :heading new-normal)
              (assoc :up new-up)
              (assoc-in [:attached :mesh] new-mesh)
              (assoc-in [:attached :face-info] new-face-info)))
        ;; No perimeter found, return state unchanged
        state))
    ;; No attachment, return state unchanged
    state))

;; ============================================================
;; Face inset
;; ============================================================

(defn build-face-inset
  "Inset a face by creating new smaller face and connecting trapezoid sides.
   Positive dist = inset (smaller), negative = outset (larger).
   Returns updated mesh."
  [mesh face-id face-info dist]
  (let [vertices (:vertices mesh)
        faces (:faces mesh)
        face-groups (:face-groups mesh)
        center (:center face-info)

        ;; Get face triangles and extract ordered perimeter
        face-triangles (:triangles face-info)
        perimeter (extract-face-perimeter face-triangles)
        n-old-verts (count vertices)
        n-perimeter (count perimeter)

        ;; Calculate inset direction for each vertex (toward center)
        ;; We move each vertex toward the centroid by dist units
        inset-verts (mapv (fn [idx]
                           (let [v (nth vertices idx)
                                 to-center (normalize (v- center v))
                                 ;; Move toward center by dist
                                 new-v (v+ v (v* to-center dist))]
                             new-v))
                         perimeter)

        ;; Map old perimeter vertex indices to new vertex indices
        index-mapping (zipmap perimeter
                              (range n-old-verts (+ n-old-verts n-perimeter)))

        ;; Build side faces (trapezoids as two triangles each)
        ;; Connect outer edge (old vertices) to inner edge (new vertices)
        side-faces (vec
                    (mapcat
                     (fn [i]
                       (let [next-i (mod (inc i) n-perimeter)
                             ;; Outer edge vertices (original)
                             o0 (nth perimeter i)
                             o1 (nth perimeter next-i)
                             ;; Inner edge vertices (inset)
                             i0 (get index-mapping o0)
                             i1 (get index-mapping o1)]
                         ;; Two triangles forming the trapezoid
                         ;; Winding: CCW from outside (same as face normal)
                         [[o0 o1 i1] [o0 i1 i0]]))
                     (range n-perimeter)))

        ;; Create new inner face triangles (same winding, new indices)
        new-inner-triangles (mapv (fn [[i j k]]
                                    [(get index-mapping i)
                                     (get index-mapping j)
                                     (get index-mapping k)])
                                  face-triangles)

        ;; Remove old face triangles from faces list
        old-face-set (set face-triangles)
        remaining-faces (vec (remove old-face-set faces))

        ;; Combine all faces
        all-faces (vec (concat remaining-faces side-faces new-inner-triangles))

        ;; Update face-groups
        ;; The inner face keeps the original face-id
        ;; Side faces get a new id
        side-face-id (keyword (str (name face-id) "-inset-sides-" (count vertices)))
        new-face-groups (-> face-groups
                           (assoc face-id new-inner-triangles)
                           (assoc side-face-id side-faces))]

    (assoc mesh
           :vertices (vec (concat vertices inset-verts))
           :faces all-faces
           :face-groups new-face-groups)))

(defn move-face-vertices-toward-center
  "Move face vertices toward center by dist units (in place).
   Positive dist = smaller face, negative = larger.
   Does NOT create new vertices - modifies existing ones.
   Returns updated mesh."
  [mesh face-info dist]
  (let [vertices (:vertices mesh)
        center (:center face-info)
        face-triangles (:triangles face-info)
        perimeter (extract-face-perimeter face-triangles)
        ;; Move each perimeter vertex toward center by dist
        new-vertices (reduce
                      (fn [verts idx]
                        (let [v (nth verts idx)
                              to-center (normalize (v- center v))
                              new-v (v+ v (v* to-center dist))]
                          (assoc verts idx new-v)))
                      (vec vertices)
                      perimeter)]
    (assoc mesh :vertices new-vertices)))

(defn inset-attached-face
  "Inset the attached face.
   In clone-face context (after attach-face-extrude): creates new inner face
   with side trapezoids, and enables extrude-mode so next f creates a spike.
   In attach-face context: moves existing vertices toward center (frustum base).
   Returns updated state."
  [state dist]
  (let [attachment (:attached state)
        mesh (:mesh attachment)
        face-id (:face-id attachment)
        face-info (:face-info attachment)
        clone-context? (:clone-context attachment)]

    (if clone-context?
      ;; Clone mode: create new inner face with connecting side faces
      ;; After this, enable extrude-mode so f will create spike side faces
      (let [new-mesh (build-face-inset mesh face-id face-info dist)
            new-triangles (get-in new-mesh [:face-groups face-id])
            new-face-info (-> face-info
                              (assoc :triangles new-triangles)
                              (assoc :vertices (vec (distinct (mapcat identity new-triangles)))))]
        (-> state
            (replace-mesh-in-state mesh new-mesh)
            (assoc-in [:attached :mesh] new-mesh)
            (assoc-in [:attached :face-info] new-face-info)
            ;; Enable extrude-mode so next f creates side faces for spike
            (assoc-in [:attached :extrude-mode] true)))

      ;; Attach mode: move existing face vertices toward center
      (let [new-mesh (move-face-vertices-toward-center mesh face-info dist)]
        (-> state
            (replace-mesh-in-state mesh new-mesh)
            (assoc-in [:attached :mesh] new-mesh))))))

(defn inset
  "Inset the attached face by dist units.
   Positive = smaller face (toward center).
   Negative = larger face (away from center).
   Only works when attached to a face."
  [state dist]
  (if-let [attachment (:attached state)]
    (if (= :face (:type attachment))
      (inset-attached-face state dist)
      state)
    state))

;; ============================================================
;; Face scale
;; ============================================================

(defn build-face-scale
  "Scale a face uniformly from its center.
   factor > 1 = larger, factor < 1 = smaller.
   Modifies vertices in place (doesn't create new vertices).
   Returns updated mesh."
  [mesh face-info factor]
  (let [vertices (:vertices mesh)
        center (:center face-info)
        ;; Get face triangles and extract ordered perimeter
        face-triangles (:triangles face-info)
        perimeter (extract-face-perimeter face-triangles)
        ;; Scale each perimeter vertex from the center
        new-vertices (reduce
                      (fn [verts idx]
                        (let [v (nth verts idx)
                              rel (v- v center)
                              scaled (v* rel factor)
                              new-v (v+ center scaled)]
                          (assoc verts idx new-v)))
                      (vec vertices)
                      perimeter)]
    (assoc mesh :vertices new-vertices)))

(defn scale-attached-face
  "Scale the attached face uniformly from its center.
   Returns updated state with scaled face."
  [state factor]
  (let [attachment (:attached state)
        mesh (:mesh attachment)
        face-id (:face-id attachment)
        face-info (:face-info attachment)
        ;; Build scaled mesh
        new-mesh (build-face-scale mesh face-info factor)
        ;; Update face-info: center stays the same after uniform scaling from center
        ;; but recalculate to get updated vertex positions
        new-face-info (assoc face-info
                             :center (:center face-info))]  ; center unchanged
    (-> state
        (replace-mesh-in-state mesh new-mesh)
        (assoc-in [:attached :mesh] new-mesh)
        (assoc-in [:attached :face-info] (assoc new-face-info :id face-id)))))

(defn scale
  "Scale the attached geometry uniformly from its centroid.
   factor > 1 = larger, factor < 1 = smaller.
   Works with both mesh attachment (:pose) and face attachment (:face)."
  [state factor]
  (if-let [attachment (:attached state)]
    (case (:type attachment)
      :pose (scale-attached-mesh state factor)
      :face (scale-attached-face state factor)
      state)
    state))

;; ============================================================
;; Attach/detach core functions
;; ============================================================

(defn compute-triangle-normal
  "Compute normal vector for a triangle given three vertices."
  [v0 v1 v2]
  (let [edge1 (v- v1 v0)
        edge2 (v- v2 v0)]
    (normalize (cross edge1 edge2))))

(defn compute-face-info-internal
  "Compute normal, heading, and center for a face group.
   Returns {:normal :heading :center :vertices :triangles}."
  [vertices face-triangles]
  (when (seq face-triangles)
    (let [all-indices (distinct (mapcat identity face-triangles))
          face-verts (mapv #(nth vertices % [0 0 0]) all-indices)
          center (v* (reduce v+ [0 0 0] face-verts) (/ 1 (count face-verts)))
          [i0 i1 i2] (first face-triangles)
          v0 (nth vertices i0 [0 0 0])
          v1 (nth vertices i1 [0 0 0])
          v2 (nth vertices i2 [0 0 0])
          normal (compute-triangle-normal v0 v1 v2)
          edge1 (v- v1 v0)
          heading (normalize edge1)]
      {:normal normal
       :heading heading
       :center center
       :vertices (vec all-indices)
       :triangles face-triangles})))

(defn clone-mesh
  "Create a deep copy of a mesh with fresh collections.
   Removes :registry-id so it can be registered separately."
  [mesh]
  (-> mesh
      (update :vertices vec)
      (update :faces vec)
      (update :face-groups #(when % (into {} (map (fn [[k v]] [k (vec v)]) %))))
      (dissoc :registry-id)))

(defn attached?
  "Check if turtle is currently attached to a mesh or face."
  [state]
  (some? (:attached state)))

;; Note: attach and attach-face need push-state which is in core.cljs
;; We'll need to pass push-state as a parameter or use a protocol
;; For now, these functions will be kept in core.cljs and just call
;; the helper functions from this module

(defn attach-impl
  "Implementation of attach - positions turtle at mesh's creation pose.
   Requires push-state to be passed in since it's defined in core.cljs."
  [state mesh push-state-fn]
  (if-let [pose (:creation-pose mesh)]
    (-> state
        (push-state-fn)
        (assoc :position (:position pose))
        (assoc :heading (:heading pose))
        (assoc :up (:up pose))
        (assoc :attached {:type :pose
                          :mesh mesh
                          :original-pose pose}))
    state))

(defn attach-face-impl
  "Implementation of attach-face - positions turtle at face center.
   Requires push-state to be passed in since it's defined in core.cljs."
  [state mesh face-id push-state-fn & {:keys [clone]}]
  (if-let [face-groups (:face-groups mesh)]
    (if-let [triangles (get face-groups face-id)]
      (let [info (compute-face-info-internal (:vertices mesh) triangles)
            normal (:normal info)
            center (:center info)
            ;; Derive up vector perpendicular to normal
            face-heading (:heading info)
            ;; up = normal × face-heading (perpendicular to both)
            up (normalize (cross normal face-heading))]
        (-> state
            (push-state-fn)
            (assoc :position center)
            (assoc :heading normal)
            (assoc :up up)
            (assoc :attached {:type :face
                              :mesh mesh
                              :face-id face-id
                              :face-info info
                              :extrude-mode clone})))  ; flag: if true, f() extrudes
      state)
    state))

(defn detach-impl
  "Implementation of detach - restores previous position.
   Requires pop-state to be passed in since it's defined in core.cljs."
  [state pop-state-fn]
  (if (:attached state)
    (-> state
        (pop-state-fn)
        (assoc :attached nil))
    state))
