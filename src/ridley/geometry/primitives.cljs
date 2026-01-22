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
        ;; Right vector = up cross heading (right-handed system)
        rx (- (* uy hz) (* uz hy))
        ry (- (* uz hx) (* ux hz))
        rz (- (* ux hy) (* uy hx))
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
   All faces CCW when viewed from outside (standard convention).
   Vertices: 0-3 back face (z=-), 4-7 front face (z=+)
   0=(-x,-y,-z), 1=(+x,-y,-z), 2=(+x,+y,-z), 3=(-x,+y,-z)
   4=(-x,-y,+z), 5=(+x,-y,+z), 6=(+x,+y,+z), 7=(-x,+y,+z)"
  []
  ;; Each quad split into two triangles, CCW from outside
  [[0 2 1] [0 3 2]   ; back  (-z): looking from -z, CCW is 0→2→1, 0→3→2
   [4 5 6] [4 6 7]   ; front (+z): looking from +z, CCW is 4→5→6, 4→6→7
   [0 1 5] [0 5 4]   ; bottom (-y): looking from -y, CCW is 0→1→5, 0→5→4
   [3 6 2] [3 7 6]   ; top (+y): looking from +y, CCW is 3→6→2, 3→7→6
   [0 4 7] [0 7 3]   ; left (-x): looking from -x, CCW is 0→4→7, 0→7→3
   [1 2 6] [1 6 5]]) ; right (+x): looking from +x, CCW is 1→2→6, 1→6→5

;; ============================================================
;; Pure mesh constructors (return mesh data at origin)
;; ============================================================

;; Default creation pose for pure mesh constructors (centered at origin)
;; Must match turtle's default orientation: facing +X, up +Z
(def ^:private default-creation-pose
  {:position [0 0 0]
   :heading [1 0 0]  ; +X forward (matches turtle default)
   :up [0 0 1]})     ; +Z up

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
    :face-groups (faces/box-face-groups)
    :creation-pose default-creation-pose}))

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
         position (:position turtle-state)
         heading (:heading turtle-state)
         up (:up turtle-state)
         transformed (apply-transform vertices position heading up)
         faces (make-box-faces)
         mesh {:type :mesh
               :primitive :box
               :vertices (vec transformed)
               :faces faces
               :face-groups (faces/box-face-groups)
               :creation-pose {:position position
                               :heading heading
                               :up up}}]
     (update turtle-state :meshes conj mesh))))

(defn- make-sphere-vertices
  "Generate sphere vertices with single vertices at poles.
   Layout: [north-pole, ring1-verts..., ring2-verts..., ..., south-pole]
   Total: 2 + segments * (rings - 1) vertices"
  [radius segments rings]
  (let [seg-step (/ (* 2 Math/PI) segments)
        ring-step (/ Math/PI rings)]
    (vec
     (concat
      ;; North pole (single vertex)
      [[0 radius 0]]
      ;; Middle rings (rings 1 to rings-1)
      (for [ring (range 1 rings)
            seg (range segments)]
        (let [phi (* ring ring-step)
              theta (* seg seg-step)
              sin-phi (Math/sin phi)
              cos-phi (Math/cos phi)
              sin-theta (Math/sin theta)
              cos-theta (Math/cos theta)]
          [(* radius sin-phi cos-theta)
           (* radius cos-phi)
           (* radius sin-phi sin-theta)]))
      ;; South pole (single vertex)
      [[0 (- radius) 0]]))))

(defn- make-sphere-faces
  "Generate sphere face indices with proper pole handling.
   Vertex layout: [north-pole, ring1..., ring2..., ..., south-pole]
   North pole = index 0
   Ring r (1 to rings-1) starts at: 1 + (r-1) * segments
   South pole = last vertex"
  [segments rings]
  (let [north-pole 0
        south-pole (+ 1 (* (dec rings) segments))
        ring-start (fn [r] (+ 1 (* (dec r) segments)))]
    (vec
     (concat
      ;; North pole triangles (connect pole to first ring)
      (for [seg (range segments)]
        (let [next-seg (mod (inc seg) segments)
              r1-curr (+ (ring-start 1) seg)
              r1-next (+ (ring-start 1) next-seg)]
          ;; CCW from outside: pole -> next -> curr
          [north-pole r1-next r1-curr]))
      ;; Middle quads (rings 1 to rings-2, connecting to ring+1)
      (apply concat
             (for [ring (range 1 (dec rings))
                   seg (range segments)]
               (let [next-seg (mod (inc seg) segments)
                     i0 (+ (ring-start ring) seg)
                     i1 (+ (ring-start ring) next-seg)
                     i2 (+ (ring-start (inc ring)) seg)
                     i3 (+ (ring-start (inc ring)) next-seg)]
                 ;; CCW from outside: i0->i1->i3 and i0->i3->i2
                 [[i0 i1 i3] [i0 i3 i2]])))
      ;; South pole triangles (connect last ring to pole)
      (for [seg (range segments)]
        (let [next-seg (mod (inc seg) segments)
              last-ring (dec rings)
              rl-curr (+ (ring-start last-ring) seg)
              rl-next (+ (ring-start last-ring) next-seg)]
          ;; CCW from outside: curr -> next -> pole
          [rl-curr rl-next south-pole]))))))

(defn sphere-mesh
  "Create a sphere mesh centered at origin.
   Returns mesh data (not transformed, not added to scene)."
  ([radius] (sphere-mesh radius 16 12))
  ([radius segments rings]
   {:type :mesh
    :primitive :sphere
    :vertices (vec (make-sphere-vertices radius segments rings))
    :faces (make-sphere-faces segments rings)
    :face-groups (faces/sphere-face-groups segments rings)
    :creation-pose default-creation-pose}))

(defn sphere
  "Create a sphere primitive at current turtle position.
   Returns turtle state with mesh added."
  ([turtle-state radius]
   (sphere turtle-state radius 16 12))
  ([turtle-state radius segments rings]
   (let [vertices (make-sphere-vertices radius segments rings)
         position (:position turtle-state)
         heading (:heading turtle-state)
         up (:up turtle-state)
         transformed (apply-transform vertices position heading up)
         faces (make-sphere-faces segments rings)
         mesh {:type :mesh
               :primitive :sphere
               :vertices (vec transformed)
               :faces faces
               :face-groups (faces/sphere-face-groups segments rings)
               :creation-pose {:position position
                               :heading heading
                               :up up}}]
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
   All faces CCW when viewed from outside (standard convention).
   Bottom verts: 0 to segments-1, Top verts: segments to 2*segments-1
   bottom-center: 2*segments, top-center: 2*segments+1"
  [segments]
  (let [bottom-center (* 2 segments)
        top-center (inc bottom-center)]
    (vec
     (apply concat
            (concat
             ;; Side faces - CCW from outside
             (for [i (range segments)]
               (let [next-i (mod (inc i) segments)
                     b0 i
                     b1 next-i
                     t0 (+ i segments)
                     t1 (+ next-i segments)]
                 [[b0 t0 t1] [b0 t1 b1]]))
             ;; Bottom cap - CCW from below (looking from -y)
             (for [i (range segments)]
               (let [next-i (mod (inc i) segments)]
                 [[bottom-center i next-i]]))
             ;; Top cap - CCW from above (looking from +y)
             (for [i (range segments)]
               (let [next-i (mod (inc i) segments)]
                 [[top-center (+ next-i segments) (+ i segments)]])))))))

(defn cyl-mesh
  "Create a cylinder mesh centered at origin.
   Returns mesh data (not transformed, not added to scene)."
  ([radius height] (cyl-mesh radius height 24))
  ([radius height segments]
   {:type :mesh
    :primitive :cylinder
    :vertices (vec (make-cylinder-vertices radius height segments))
    :faces (make-cylinder-faces segments)
    :face-groups (faces/cylinder-face-groups segments)
    :creation-pose default-creation-pose}))

(defn cyl
  "Create a cylinder primitive at current turtle position.
   Returns turtle state with mesh added."
  ([turtle-state radius height]
   (cyl turtle-state radius height 24))
  ([turtle-state radius height segments]
   (let [vertices (make-cylinder-vertices radius height segments)
         position (:position turtle-state)
         heading (:heading turtle-state)
         up (:up turtle-state)
         transformed (apply-transform vertices position heading up)
         faces (make-cylinder-faces segments)
         mesh {:type :mesh
               :primitive :cylinder
               :vertices (vec transformed)
               :faces faces
               :face-groups (faces/cylinder-face-groups segments)
               :creation-pose {:position position
                               :heading heading
                               :up up}}]
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
    :face-groups (faces/cone-face-groups segments)
    :creation-pose default-creation-pose}))

(defn cone
  "Create a cone or frustum primitive at current turtle position.
   Returns turtle state with mesh added.
   (cone r1 r2 height) - frustum with bottom radius r1, top radius r2
   Use r2=0 for a proper cone."
  ([turtle-state r1 r2 height]
   (cone turtle-state r1 r2 height 24))
  ([turtle-state r1 r2 height segments]
   (let [vertices (make-cone-vertices r1 r2 height segments)
         position (:position turtle-state)
         heading (:heading turtle-state)
         up (:up turtle-state)
         transformed (apply-transform vertices position heading up)
         faces (make-cylinder-faces segments)
         mesh {:type :mesh
               :primitive :cone
               :vertices (vec transformed)
               :faces faces
               :face-groups (faces/cone-face-groups segments)
               :creation-pose {:position position
                               :heading heading
                               :up up}}]
     (update turtle-state :meshes conj mesh))))
