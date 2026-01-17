(ns ridley.geometry.primitives
  "3D primitive shapes: box, sphere, cylinder, cone.

   Two APIs:
   1. Pure functions (box-mesh, sphere-mesh, etc.) - return mesh data at origin
   2. Turtle functions (box, sphere, etc.) - transform and add to turtle state

   All primitives include face-groups for face-based modeling."
  (:require [ridley.geometry.faces :as faces]))

(defn apply-transform
  "Apply turtle position and orientation to mesh vertices.
   Used by stamp/make to position meshes at turtle location."
  [vertices position heading up]
  (let [[hx hy hz] heading
        [ux uy uz] up
        ;; Right vector = heading cross up
        rx (- (* hy uz) (* hz uy))
        ry (- (* hz ux) (* hx uz))
        rz (- (* hx uy) (* hy ux))
        [px py pz] position]
    (mapv (fn [vertex]
            (let [[x y z] vertex]
              ;; Transform: rotate then translate
              ;; x-axis = right, y-axis = up, z-axis = heading
              [(+ px (* x rx) (* y ux) (* z hx))
               (+ py (* x ry) (* y uy) (* z hy))
               (+ pz (* x rz) (* y uz) (* z hz))]))
          vertices)))

(defn- make-box-vertices
  "Generate box vertices centered at origin."
  [sx sy sz]
  (let [hx (/ sx 2) hy (/ sy 2) hz (/ sz 2)]
    [[(- hx) (- hy) (- hz)]
     [hx (- hy) (- hz)]
     [hx hy (- hz)]
     [(- hx) hy (- hz)]
     [(- hx) (- hy) hz]
     [hx (- hy) hz]
     [hx hy hz]
     [(- hx) hy hz]]))

(defn- make-box-faces
  "Generate box face indices (quads as triangles).
   All faces CCW when viewed from outside (Manifold requirement).
   Vertices: 0-3 back face (z=-), 4-7 front face (z=+)"
  []
  ;; Each quad split into two triangles, CCW from outside
  [[0 1 2] [0 2 3]   ; back  (-z): 0→1→2, 0→2→3
   [4 6 5] [4 7 6]   ; front (+z): 4→6→5, 4→7→6
   [0 4 5] [0 5 1]   ; bottom (-y): 0→4→5, 0→5→1
   [3 2 6] [3 6 7]   ; top (+y): 3→2→6, 3→6→7
   [0 3 7] [0 7 4]   ; left (-x): 0→3→7, 0→7→4
   [1 5 6] [1 6 2]]) ; right (+x): 1→5→6, 1→6→2

;; ============================================================
;; Pure mesh constructors (return mesh data at origin)
;; ============================================================

(defn box-mesh
  "Create a box mesh centered at origin.
   Returns mesh data (not transformed, not added to scene).
   (box-mesh size) - cube
   (box-mesh sx sy sz) - rectangular box"
  ([size] (box-mesh size size size))
  ([sx sy sz]
   {:type :mesh
    :primitive :box
    :vertices (vec (make-box-vertices sx sy sz))
    :faces (make-box-faces)
    :face-groups (faces/box-face-groups)}))

;; ============================================================
;; Turtle-aware functions (transform and add to turtle state)
;; ============================================================

(defn box
  "Create a box primitive at current turtle position.
   Returns turtle state with mesh added.
   (box size) - cube
   (box x y z) - rectangular box"
  ([turtle-state size]
   (box turtle-state size size size))
  ([turtle-state sx sy sz]
   (let [vertices (make-box-vertices sx sy sz)
         transformed (apply-transform vertices
                                       (:position turtle-state)
                                       (:heading turtle-state)
                                       (:up turtle-state))
         faces (make-box-faces)
         mesh {:type :mesh
               :primitive :box
               :vertices (vec transformed)
               :faces faces
               :face-groups (faces/box-face-groups)}]
     (update turtle-state :meshes conj mesh))))

(defn- make-sphere-vertices
  "Generate sphere vertices using UV sphere algorithm."
  [radius segments rings]
  (let [seg-step (/ (* 2 Math/PI) segments)
        ring-step (/ Math/PI rings)]
    (vec
     (for [ring (range (inc rings))
           seg (range segments)]
       (let [phi (* ring ring-step)
             theta (* seg seg-step)
             sin-phi (Math/sin phi)
             cos-phi (Math/cos phi)
             sin-theta (Math/sin theta)
             cos-theta (Math/cos theta)]
         [(* radius sin-phi cos-theta)
          (* radius cos-phi)
          (* radius sin-phi sin-theta)])))))

(defn- make-sphere-faces
  "Generate sphere face indices.
   All faces CCW when viewed from outside (Manifold requirement).
   UV sphere with rings from north pole (ring 0) to south pole."
  [segments rings]
  (vec
   (apply concat
          (for [ring (range rings)
                seg (range segments)]
            (let [next-seg (mod (inc seg) segments)
                  i0 (+ seg (* ring segments))
                  i1 (+ next-seg (* ring segments))
                  i2 (+ seg (* (inc ring) segments))
                  i3 (+ next-seg (* (inc ring) segments))]
              ;; CCW from outside: i0→i2→i1 and i2→i3→i1
              [[i0 i2 i1] [i2 i3 i1]])))))

(defn sphere-mesh
  "Create a sphere mesh centered at origin.
   Returns mesh data (not transformed, not added to scene)."
  ([radius] (sphere-mesh radius 16 12))
  ([radius segments rings]
   {:type :mesh
    :primitive :sphere
    :vertices (vec (make-sphere-vertices radius segments rings))
    :faces (make-sphere-faces segments rings)
    :face-groups (faces/sphere-face-groups segments rings)}))

(defn sphere
  "Create a sphere primitive at current turtle position.
   Returns turtle state with mesh added."
  ([turtle-state radius]
   (sphere turtle-state radius 16 12))
  ([turtle-state radius segments rings]
   (let [vertices (make-sphere-vertices radius segments rings)
         transformed (apply-transform vertices
                                       (:position turtle-state)
                                       (:heading turtle-state)
                                       (:up turtle-state))
         faces (make-sphere-faces segments rings)
         mesh {:type :mesh
               :primitive :sphere
               :vertices (vec transformed)
               :faces faces
               :face-groups (faces/sphere-face-groups segments rings)}]
     (update turtle-state :meshes conj mesh))))

(defn- make-cylinder-vertices
  "Generate cylinder vertices."
  [radius height segments]
  (let [step (/ (* 2 Math/PI) segments)
        half-h (/ height 2)]
    (vec
     (concat
      ;; Bottom circle
      (for [i (range segments)]
        (let [theta (* i step)]
          [(* radius (Math/cos theta))
           (- half-h)
           (* radius (Math/sin theta))]))
      ;; Top circle
      (for [i (range segments)]
        (let [theta (* i step)]
          [(* radius (Math/cos theta))
           half-h
           (* radius (Math/sin theta))]))
      ;; Center points for caps
      [[0 (- half-h) 0]
       [0 half-h 0]]))))

(defn- make-cylinder-faces
  "Generate cylinder face indices.
   All faces CCW when viewed from outside (Manifold requirement)."
  [segments]
  (let [bottom-center (* 2 segments)
        top-center (inc bottom-center)]
    (vec
     (apply concat
            (concat
             ;; Side faces - CCW from outside: b0→b1→t1 and b0→t1→t0
             (for [i (range segments)]
               (let [next-i (mod (inc i) segments)
                     b0 i
                     b1 next-i
                     t0 (+ i segments)
                     t1 (+ next-i segments)]
                 [[b0 b1 t1] [b0 t1 t0]]))
             ;; Bottom cap - CCW from below (looking up at bottom)
             (for [i (range segments)]
               (let [next-i (mod (inc i) segments)]
                 [[bottom-center next-i i]]))
             ;; Top cap - CCW from above (looking down at top)
             (for [i (range segments)]
               (let [next-i (mod (inc i) segments)]
                 [[top-center (+ i segments) (+ next-i segments)]])))))))

(defn cyl-mesh
  "Create a cylinder mesh centered at origin.
   Returns mesh data (not transformed, not added to scene)."
  ([radius height] (cyl-mesh radius height 24))
  ([radius height segments]
   {:type :mesh
    :primitive :cylinder
    :vertices (vec (make-cylinder-vertices radius height segments))
    :faces (make-cylinder-faces segments)
    :face-groups (faces/cylinder-face-groups segments)}))

(defn cyl
  "Create a cylinder primitive at current turtle position.
   Returns turtle state with mesh added."
  ([turtle-state radius height]
   (cyl turtle-state radius height 24))
  ([turtle-state radius height segments]
   (let [vertices (make-cylinder-vertices radius height segments)
         transformed (apply-transform vertices
                                       (:position turtle-state)
                                       (:heading turtle-state)
                                       (:up turtle-state))
         faces (make-cylinder-faces segments)
         mesh {:type :mesh
               :primitive :cylinder
               :vertices (vec transformed)
               :faces faces
               :face-groups (faces/cylinder-face-groups segments)}]
     (update turtle-state :meshes conj mesh))))

(defn- make-cone-vertices
  "Generate cone/frustum vertices."
  [r1 r2 height segments]
  (let [step (/ (* 2 Math/PI) segments)
        half-h (/ height 2)]
    (vec
     (concat
      ;; Bottom circle (r1)
      (for [i (range segments)]
        (let [theta (* i step)]
          [(* r1 (Math/cos theta))
           (- half-h)
           (* r1 (Math/sin theta))]))
      ;; Top circle (r2)
      (for [i (range segments)]
        (let [theta (* i step)]
          [(* r2 (Math/cos theta))
           half-h
           (* r2 (Math/sin theta))]))
      ;; Center points for caps
      [[0 (- half-h) 0]
       [0 half-h 0]]))))

(defn cone-mesh
  "Create a cone/frustum mesh centered at origin.
   Returns mesh data (not transformed, not added to scene).
   (cone-mesh r1 r2 height) - frustum with bottom radius r1, top radius r2
   Use r2=0 for a proper cone."
  ([r1 r2 height] (cone-mesh r1 r2 height 24))
  ([r1 r2 height segments]
   {:type :mesh
    :primitive :cone
    :vertices (vec (make-cone-vertices r1 r2 height segments))
    :faces (make-cylinder-faces segments)
    :face-groups (faces/cone-face-groups segments)}))

(defn cone
  "Create a cone or frustum primitive at current turtle position.
   Returns turtle state with mesh added.
   (cone r1 r2 height) - frustum with bottom radius r1, top radius r2
   Use r2=0 for a proper cone."
  ([turtle-state r1 r2 height]
   (cone turtle-state r1 r2 height 24))
  ([turtle-state r1 r2 height segments]
   (let [vertices (make-cone-vertices r1 r2 height segments)
         transformed (apply-transform vertices
                                       (:position turtle-state)
                                       (:heading turtle-state)
                                       (:up turtle-state))
         faces (make-cylinder-faces segments)
         mesh {:type :mesh
               :primitive :cone
               :vertices (vec transformed)
               :faces faces
               :face-groups (faces/cone-face-groups segments)}]
     (update turtle-state :meshes conj mesh))))
