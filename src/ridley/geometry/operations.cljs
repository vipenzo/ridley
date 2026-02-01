(ns ridley.geometry.operations
  "Generative operations: extrude, revolve, sweep, loft.
   These operations take 2D profiles and generate 3D meshes.")

;; ============================================================
;; Utility functions
;; ============================================================

(defn- v+ [[x1 y1 z1] [x2 y2 z2]]
  [(+ x1 x2) (+ y1 y2) (+ z1 z2)])

(defn- v- [[x1 y1 z1] [x2 y2 z2]]
  [(- x1 x2) (- y1 y2) (- z1 z2)])

(defn- v* [[x y z] s]
  [(* x s) (* y s) (* z s)])

(defn- magnitude [[x y z]]
  (Math/sqrt (+ (* x x) (* y y) (* z z))))

(defn- normalize [v]
  (let [m (magnitude v)]
    (if (zero? m)
      v
      (v* v (/ 1 m)))))

(defn- rotate-point-around-axis
  "Rotate point around an axis passing through origin using Rodrigues' formula."
  [point axis angle]
  (let [k (normalize axis)
        [kx ky kz] k
        [px py pz] point
        cos-a (Math/cos angle)
        sin-a (Math/sin angle)
        dot-kp (+ (* kx px) (* ky py) (* kz pz))
        ;; k × p
        cross-x (- (* ky pz) (* kz py))
        cross-y (- (* kz px) (* kx pz))
        cross-z (- (* kx py) (* ky px))]
    [(+ (* px cos-a) (* cross-x sin-a) (* kx dot-kp (- 1 cos-a)))
     (+ (* py cos-a) (* cross-y sin-a) (* ky dot-kp (- 1 cos-a)))
     (+ (* pz cos-a) (* cross-z sin-a) (* kz dot-kp (- 1 cos-a)))]))

(defn- ensure-3d
  "Ensure a point has 3 numeric coordinates."
  [point]
  (cond
    (nil? point) [0 0 0]
    (not (sequential? point)) [0 0 0]
    (< (count point) 3) [(or (nth point 0 nil) 0)
                         (or (nth point 1 nil) 0)
                         0]
    :else [(or (nth point 0) 0)
           (or (nth point 1) 0)
           (or (nth point 2) 0)]))

(defn- path-to-points
  "Extract points from path geometry segments.
   Segments have the form {:type :line :from [x y z] :to [x y z]}.
   Returns a vector of 3D points forming the path."
  [path-or-state]
  (let [segments (or (:segments path-or-state) (:geometry path-or-state) [])]
    (if (or (nil? segments) (empty? segments))
      []
      (let [first-point (ensure-3d (get-in segments [0 :from]))
            rest-points (mapv #(ensure-3d (:to %)) segments)]
        (into [first-point] rest-points)))))

;; ============================================================
;; EXTRUDE - Linear extrusion along a direction
;; ============================================================

(defn extrude
  "Extrude a 2D path/shape along a direction vector to create a 3D mesh.

   Arguments:
   - path: A path or turtle state with geometry
   - direction: [x y z] vector for extrusion direction
   - distance: How far to extrude

   Returns a mesh {:vertices [...] :faces [...]}."
  [path direction distance]
  (let [points (path-to-points path)
        n (count points)]
    (if (< n 2)
      {:type :mesh :primitive :extrude :vertices [] :faces []}
      (let [dir (normalize direction)
            offset (v* dir distance)
            bottom-verts points
            top-verts (mapv #(v+ % offset) points)
            vertices (into (vec bottom-verts) top-verts)
            side-faces (vec
                        (mapcat (fn [i]
                                  (let [next-i (mod (inc i) n)
                                        b0 i
                                        b1 next-i
                                        t0 (+ i n)
                                        t1 (+ next-i n)]
                                    ;; CCW winding for outward normals
                                    [[b0 t1 b1] [b0 t0 t1]]))
                                (range n)))
            closed? (or (:closed? path)
                        (and (>= n 3)
                             (< (magnitude (v- (first points) (last points))) 0.001)))
            cap-faces (when (and closed? (>= n 3))
                        (let [bottom-cap (vec (for [i (range 1 (dec n))]
                                                [0 i (inc i)]))
                              top-cap (vec (for [i (range 1 (dec n))]
                                             [n (+ n i 1) (+ n i)]))]
                          (concat bottom-cap top-cap)))]
        {:type :mesh
         :primitive :extrude
         :vertices vertices
         :faces (vec (concat side-faces cap-faces))}))))

(defn extrude-z
  "Convenience: extrude along Z axis."
  [path distance]
  (extrude path [0 0 1] distance))

(defn extrude-y
  "Convenience: extrude along Y axis."
  [path distance]
  (extrude path [0 1 0] distance))

;; ============================================================
;; REVOLVE - Revolution around an axis
;; ============================================================

(defn revolve
  "Revolve a 2D profile around an axis to create a 3D solid mesh.

   The profile is automatically closed if not already closed, ensuring
   the resulting mesh is watertight (suitable for 3D printing).

   Arguments:
   - profile: A path or turtle state with geometry (in XY plane, will revolve around Y)
   - axis: [x y z] axis vector to revolve around (default [0 1 0] = Y axis)
   - angle: Total angle in degrees (default 360 for full revolution)
   - segments: Number of segments around the revolution (default 24)

   Returns a mesh {:vertices [...] :faces [...]}."
  ([profile]
   (revolve profile [0 1 0] 360 24))
  ([profile axis]
   (revolve profile axis 360 24))
  ([profile axis angle]
   (revolve profile axis angle 24))
  ([profile axis angle segments]
   (let [raw-points (path-to-points profile)
         ;; Auto-close the profile for solid mesh
         points (if (and (>= (count raw-points) 2)
                         (> (magnitude (v- (first raw-points) (last raw-points))) 0.001))
                  (conj (vec raw-points) (first raw-points))
                  raw-points)
         n (count points)
         angle-rad (* angle (/ Math/PI 180))
         full-revolution? (>= (Math/abs angle) 359.9)
         ;; For full revolution, we don't need the last ring (it overlaps with first)
         num-rings (if full-revolution? segments (inc segments))
         step (/ angle-rad segments)
         axis-norm (normalize axis)
         ;; Generate vertices for each ring
         all-verts (vec
                    (for [seg (range num-rings)
                          pt points]
                      (rotate-point-around-axis pt axis-norm (* seg step))))
         ;; Generate faces connecting adjacent rings (CCW winding for outward normals)
         faces (vec
                (apply concat
                       (for [seg (range segments)
                             i (range (dec n))]
                         (let [ring-offset (* seg n)
                               ;; For full revolution, wrap around to first ring
                               next-ring-offset (if (and full-revolution? (= seg (dec segments)))
                                                  0
                                                  (* (inc seg) n))
                               p0 (+ ring-offset i)
                               p1 (+ ring-offset (inc i))
                               p2 (+ next-ring-offset (inc i))
                               p3 (+ next-ring-offset i)]
                           ;; Two triangles per quad (CCW winding)
                           [[p0 p2 p1] [p0 p3 p2]]))))
         ;; Add end caps only if not full revolution
         cap-faces (when (and (not full-revolution?) (>= n 3))
                     (let [start-cap (vec (for [i (range 1 (dec n))]
                                            [0 (inc i) i]))
                           end-offset (* segments n)
                           end-cap (vec (for [i (range 1 (dec n))]
                                          [end-offset (+ end-offset i) (+ end-offset (inc i))]))]
                       (concat start-cap end-cap)))]
     {:type :mesh
      :primitive :revolve
      :vertices all-verts
      :faces (vec (concat faces cap-faces))})))

;; ============================================================
;; LOFT - Transition between profiles
;; ============================================================

(defn loft
  "Create a mesh that transitions between multiple profiles.

   Arguments:
   - profiles: A sequence of paths/shapes (must have same number of points)

   Returns a mesh {:vertices [...] :faces [...]}."
  [& profiles]
  (let [profile-points (mapv path-to-points profiles)
        n (count (first profile-points))  ; Points per profile
        num-profiles (count profiles)
        ;; Flatten all vertices
        all-verts (vec (apply concat profile-points))
        ;; Generate faces between adjacent profiles (CCW winding for outward normals)
        faces (vec
               (apply concat
                      (for [p (range (dec num-profiles))
                            i (range n)]
                        (let [next-i (mod (inc i) n)
                              ring-offset (* p n)
                              next-ring-offset (* (inc p) n)
                              p0 (+ ring-offset i)
                              p1 (+ ring-offset next-i)
                              p2 (+ next-ring-offset next-i)
                              p3 (+ next-ring-offset i)]
                          ;; CCW winding
                          [[p0 p2 p1] [p0 p3 p2]]))))
        ;; Add end caps
        cap-faces (when (>= n 3)
                    (let [;; First profile cap (CW for inward normal)
                          start-cap (vec (for [i (range 1 (dec n))]
                                           [0 i (inc i)]))
                          ;; Last profile cap (CCW for outward normal)
                          end-offset (* (dec num-profiles) n)
                          end-cap (vec (for [i (range 1 (dec n))]
                                         [end-offset (+ end-offset (inc i)) (+ end-offset i)]))]
                      (concat start-cap end-cap)))]
    {:type :mesh
     :primitive :loft
     :vertices all-verts
     :faces (vec (concat faces cap-faces))}))
