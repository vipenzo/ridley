(ns ridley.geometry.primitives
  "3D primitive shapes: box, sphere, cylinder, cone.
   Each primitive is placed at turtle position with turtle orientation.
   All primitives include face-groups for face-based modeling."
  (:require [ridley.geometry.faces :as faces]))

(defn- apply-transform
  "Apply turtle position and orientation to mesh vertices."
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
  "Generate box face indices (quads as triangles)."
  []
  ;; Each face as two triangles
  [[0 1 2] [0 2 3]   ; back
   [4 6 5] [4 7 6]   ; front
   [0 4 5] [0 5 1]   ; bottom
   [2 6 7] [2 7 3]   ; top
   [0 3 7] [0 7 4]   ; left
   [1 5 6] [1 6 2]]) ; right

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
  "Generate sphere face indices."
  [segments rings]
  (vec
   (for [ring (range rings)
         seg (range segments)]
     (let [next-seg (mod (inc seg) segments)
           current (* ring segments)
           next-ring (* (inc ring) segments)]
       ;; Two triangles per quad
       [[current (+ current seg)]
        [next-ring (+ next-ring seg)]
        [next-ring (+ next-ring next-seg)]
        [current (+ current next-seg)]])))
  ;; Simplified: generate triangles
  (vec
   (apply concat
          (for [ring (range rings)
                seg (range segments)]
            (let [next-seg (mod (inc seg) segments)
                  i0 (+ seg (* ring segments))
                  i1 (+ next-seg (* ring segments))
                  i2 (+ seg (* (inc ring) segments))
                  i3 (+ next-seg (* (inc ring) segments))]
              [[i0 i2 i1] [i1 i2 i3]])))))

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
  "Generate cylinder face indices."
  [segments]
  (let [bottom-center (* 2 segments)
        top-center (inc bottom-center)]
    (vec
     (apply concat
            (concat
             ;; Side faces
             (for [i (range segments)]
               (let [next-i (mod (inc i) segments)
                     b0 i
                     b1 next-i
                     t0 (+ i segments)
                     t1 (+ next-i segments)]
                 [[b0 t0 t1] [b0 t1 b1]]))
             ;; Bottom cap
             (for [i (range segments)]
               (let [next-i (mod (inc i) segments)]
                 [[bottom-center next-i i]]))
             ;; Top cap
             (for [i (range segments)]
               (let [next-i (mod (inc i) segments)]
                 [[(+ i segments) (+ next-i segments) top-center]])))))))

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
