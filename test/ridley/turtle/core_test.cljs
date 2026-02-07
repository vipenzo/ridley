(ns ridley.turtle.core-test
  "Tests for turtle movement functions.
   Verifies that basic movements produce expected state changes."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.turtle.core :as t]
            [ridley.turtle.shape :as shape]))

;; --- Helper functions for approximate equality ---

(def epsilon 1e-9)

(defn approx=
  "Check if two numbers are approximately equal."
  [a b]
  (< (Math/abs (- a b)) epsilon))

(defn vec-approx=
  "Check if two vectors are approximately equal component-wise."
  [[x1 y1 z1] [x2 y2 z2]]
  (and (approx= x1 x2)
       (approx= y1 y2)
       (approx= z1 z2)))

;; --- Initial state tests ---

(deftest make-turtle-test
  (testing "Initial turtle state"
    (let [turtle (t/make-turtle)]
      (is (vec-approx= (:position turtle) [0 0 0])
          "Turtle should start at origin")
      (is (vec-approx= (:heading turtle) [1 0 0])
          "Turtle should face +X initially")
      (is (vec-approx= (:up turtle) [0 0 1])
          "Turtle up should be +Z initially")
      (is (= (:pen-mode turtle) :on)
          "Pen should be on by default"))))

;; --- Forward movement tests ---

(deftest forward-basic-test
  (testing "Forward movement along +X axis"
    (let [turtle (-> (t/make-turtle)
                     (t/f 10))]
      (is (vec-approx= (:position turtle) [10 0 0])
          "Moving forward 10 should put turtle at [10 0 0]")
      (is (vec-approx= (:heading turtle) [1 0 0])
          "Heading should remain unchanged")))

  (testing "Forward with negative distance (backward)"
    (let [turtle (-> (t/make-turtle)
                     (t/f -5))]
      (is (vec-approx= (:position turtle) [-5 0 0])
          "Moving forward -5 should put turtle at [-5 0 0]")))

  (testing "Multiple forward movements accumulate"
    (let [turtle (-> (t/make-turtle)
                     (t/f 3)
                     (t/f 7))]
      (is (vec-approx= (:position turtle) [10 0 0])
          "3 + 7 = 10 units forward"))))

;; --- Turn horizontal (th) tests ---

(deftest turn-horizontal-test
  (testing "Turn left 90 degrees"
    (let [turtle (-> (t/make-turtle)
                     (t/th 90))]
      (is (vec-approx= (:heading turtle) [0 1 0])
          "After turning 90 left, heading should be +Y")
      (is (vec-approx= (:up turtle) [0 0 1])
          "Up should remain +Z")))

  (testing "Turn right 90 degrees (negative angle)"
    (let [turtle (-> (t/make-turtle)
                     (t/th -90))]
      (is (vec-approx= (:heading turtle) [0 -1 0])
          "After turning 90 right, heading should be -Y")))

  (testing "Turn left 180 degrees"
    (let [turtle (-> (t/make-turtle)
                     (t/th 180))]
      (is (vec-approx= (:heading turtle) [-1 0 0])
          "After turning 180, heading should be -X")))

  (testing "Turn left 45 degrees"
    (let [turtle (-> (t/make-turtle)
                     (t/th 45))
          sqrt2-2 (/ (Math/sqrt 2) 2)]
      (is (vec-approx= (:heading turtle) [sqrt2-2 sqrt2-2 0])
          "After turning 45 left, heading should be [sqrt2/2 sqrt2/2 0]")))

  (testing "Full rotation returns to original heading"
    (let [turtle (-> (t/make-turtle)
                     (t/th 360))]
      (is (vec-approx= (:heading turtle) [1 0 0])
          "360 degree turn should return to original heading"))))

;; --- Turn vertical (tv) tests ---

(deftest turn-vertical-test
  (testing "Pitch up 90 degrees"
    (let [turtle (-> (t/make-turtle)
                     (t/tv 90))]
      (is (vec-approx= (:heading turtle) [0 0 1])
          "After pitching up 90, heading should be +Z")
      (is (vec-approx= (:up turtle) [-1 0 0])
          "After pitching up 90, up should be -X")))

  (testing "Pitch down 90 degrees (negative angle)"
    (let [turtle (-> (t/make-turtle)
                     (t/tv -90))]
      (is (vec-approx= (:heading turtle) [0 0 -1])
          "After pitching down 90, heading should be -Z")
      (is (vec-approx= (:up turtle) [1 0 0])
          "After pitching down 90, up should be +X")))

  (testing "Pitch up 45 degrees"
    (let [turtle (-> (t/make-turtle)
                     (t/tv 45))
          sqrt2-2 (/ (Math/sqrt 2) 2)]
      (is (vec-approx= (:heading turtle) [sqrt2-2 0 sqrt2-2])
          "After pitching up 45, heading should be [sqrt2/2 0 sqrt2/2]")
      (is (vec-approx= (:up turtle) [(- sqrt2-2) 0 sqrt2-2])
          "After pitching up 45, up should be [-sqrt2/2 0 sqrt2/2]"))))

;; --- Roll (tr) tests ---

(deftest roll-test
  (testing "Roll 90 degrees clockwise"
    (let [turtle (-> (t/make-turtle)
                     (t/tr 90))]
      (is (vec-approx= (:heading turtle) [1 0 0])
          "Heading should remain +X after roll")
      (is (vec-approx= (:up turtle) [0 -1 0])
          "After rolling 90 clockwise, up should be -Y")))

  (testing "Roll 90 degrees counter-clockwise (negative)"
    (let [turtle (-> (t/make-turtle)
                     (t/tr -90))]
      (is (vec-approx= (:heading turtle) [1 0 0])
          "Heading should remain +X after roll")
      (is (vec-approx= (:up turtle) [0 1 0])
          "After rolling 90 counter-clockwise, up should be +Y")))

  (testing "Roll 180 degrees"
    (let [turtle (-> (t/make-turtle)
                     (t/tr 180))]
      (is (vec-approx= (:up turtle) [0 0 -1])
          "After rolling 180, up should be -Z"))))

;; --- Combined movement tests ---

(deftest combined-movement-test
  (testing "Turn then move"
    (let [turtle (-> (t/make-turtle)
                     (t/th 90)
                     (t/f 10))]
      (is (vec-approx= (:position turtle) [0 10 0])
          "After turning 90 left and moving 10, position should be [0 10 0]")))

  (testing "Move, turn, move forms L shape"
    (let [turtle (-> (t/make-turtle)
                     (t/f 5)
                     (t/th 90)
                     (t/f 3))]
      (is (vec-approx= (:position turtle) [5 3 0])
          "L shape: forward 5, turn left 90, forward 3 -> [5 3 0]")))

  (testing "Square path returns close to origin"
    (let [turtle (-> (t/make-turtle)
                     (t/f 10) (t/th 90)
                     (t/f 10) (t/th 90)
                     (t/f 10) (t/th 90)
                     (t/f 10) (t/th 90))]
      (is (vec-approx= (:position turtle) [0 0 0])
          "Square path should return to origin")
      (is (vec-approx= (:heading turtle) [1 0 0])
          "Four 90-degree turns should return to original heading")))

  (testing "Pitch up then move goes vertical"
    (let [turtle (-> (t/make-turtle)
                     (t/tv 90)
                     (t/f 10))]
      (is (vec-approx= (:position turtle) [0 0 10])
          "Pitching up 90 then moving 10 should reach [0 0 10]")))

  (testing "Roll then pitch then move"
    (let [turtle (-> (t/make-turtle)
                     (t/tr 90)   ; roll so up points -Y
                     (t/tv 90)   ; pitch up (now turns in XZ relative to new up)
                     (t/f 10))]
      ;; After roll 90: heading=[1 0 0], up=[0 -1 0]
      ;; After tv 90: rotate heading and up around right vector
      ;; right = heading x up = [1 0 0] x [0 -1 0] = [0 0 -1]
      ;; rotating heading [1 0 0] around [0 0 -1] by 90 degrees
      ;; Result: heading points toward [0 -1 0]
      (is (vec-approx= (:position turtle) [0 -10 0])
          "Roll 90, pitch 90, move 10 should go to [0 -10 0]"))))

(deftest triangle-path-test
  (testing "Equilateral triangle path"
    (let [turtle (-> (t/make-turtle)
                     (t/f 10) (t/th 120)
                     (t/f 10) (t/th 120)
                     (t/f 10) (t/th 120))]
      (is (vec-approx= (:position turtle) [0 0 0])
          "Equilateral triangle (exterior angles 120) returns to origin")
      (is (vec-approx= (:heading turtle) [1 0 0])
          "Three 120-degree turns return to original heading"))))

(deftest diagonal-movement-test
  (testing "45 degree movement in XY plane"
    (let [turtle (-> (t/make-turtle)
                     (t/th 45)
                     (t/f 10))
          sqrt2-2 (/ (Math/sqrt 2) 2)
          expected-x (* 10 sqrt2-2)
          expected-y (* 10 sqrt2-2)]
      (is (vec-approx= (:position turtle) [expected-x expected-y 0])
          "Turning 45 and moving 10 reaches diagonal position"))))

(deftest vertical-movement-test
  (testing "Simple vertical climb"
    (let [turtle (-> (t/make-turtle)
                     (t/tv 90)   ; pitch straight up
                     (t/f 10)
                     (t/f 5))]
      (is (vec-approx= (:position turtle) [0 0 15])
          "Two vertical moves (10 + 5) should reach [0 0 15]")))

  (testing "Diagonal upward path"
    (let [turtle (-> (t/make-turtle)
                     (t/tv 45)   ; pitch up 45 degrees
                     (t/f 10))
          sqrt2-2 (/ (Math/sqrt 2) 2)
          expected-x (* 10 sqrt2-2)
          expected-z (* 10 sqrt2-2)]
      (is (vec-approx= (:position turtle) [expected-x 0 expected-z])
          "Pitching 45 up and moving 10 reaches diagonal position")))

  (testing "3D diagonal path"
    (let [turtle (-> (t/make-turtle)
                     (t/th 45)   ; turn left 45
                     (t/tv 45)   ; pitch up 45
                     (t/f 10))
          ;; After th 45: heading = [sqrt2/2, sqrt2/2, 0], up = [0, 0, 1]
          ;; After tv 45: heading rotates up by 45 around right vector
          ;; right = heading x up
          ;; Result should have positive x, y, and z components
          [x y z] (:position turtle)]
      (is (and (> x 0) (> y 0) (> z 0))
          "3D diagonal path should have positive x, y, z components"))))

;; ============================================================
;; Extrusion tests
;; ============================================================

(defn- mesh-bounding-box
  "Calculate bounding box of a mesh.
   Returns {:min [x y z] :max [x y z] :size [w h d]}"
  [mesh]
  (when-let [vertices (:vertices mesh)]
    (let [xs (map first vertices)
          ys (map second vertices)
          zs (map #(nth % 2) vertices)
          min-pt [(apply min xs) (apply min ys) (apply min zs)]
          max-pt [(apply max xs) (apply max ys) (apply max zs)]]
      {:min min-pt
       :max max-pt
       :size [(- (first max-pt) (first min-pt))
              (- (second max-pt) (second min-pt))
              (- (nth max-pt 2) (nth min-pt 2))]})))

(defn- size-approx=
  "Check if bounding box size is approximately equal to expected dimensions."
  [[w1 h1 d1] [w2 h2 d2]]
  (and (approx= w1 w2)
       (approx= h1 h2)
       (approx= d1 d2)))

;; ============================================================
;; Mesh comparison utilities
;; ============================================================

(defn- vertices-identical?
  "Check if two meshes have identical vertices (same count, same positions)."
  [mesh1 mesh2]
  (let [v1 (:vertices mesh1)
        v2 (:vertices mesh2)]
    (and (= (count v1) (count v2))
         (every? true? (map vec-approx= v1 v2)))))

(defn- faces-identical?
  "Check if two meshes have identical faces (same indices)."
  [mesh1 mesh2]
  (= (:faces mesh1) (:faces mesh2)))

(defn- meshes-identical?
  "Check if two meshes are identical (same vertices and faces)."
  [mesh1 mesh2]
  (and (vertices-identical? mesh1 mesh2)
       (faces-identical? mesh1 mesh2)))

(defn- get-face-vertices
  "Get the 3D vertices of a face by its index."
  [mesh face-idx]
  (let [vertices (:vertices mesh)
        face (nth (:faces mesh) face-idx)]
    (mapv #(nth vertices %) face)))

(defn- identical-face?
  "Check if face at id1 in mesh1 is identical to face at id2 in mesh2.
   Compares the actual 3D positions of the vertices."
  [mesh1 id1 mesh2 id2]
  (let [verts1 (get-face-vertices mesh1 id1)
        verts2 (get-face-vertices mesh2 id2)]
    (and (= (count verts1) (count verts2))
         (every? true? (map vec-approx= verts1 verts2)))))

;; ============================================================
;; Face normal utilities
;; ============================================================

(defn- face-normal
  "Calculate the normal vector of a face (assumes triangular face).
   Uses right-hand rule: CCW winding gives outward normal."
  [mesh face-idx]
  (let [verts (get-face-vertices mesh face-idx)]
    (when (>= (count verts) 3)
      (let [[v0 v1 v2] (take 3 verts)
            ;; Edge vectors
            e1 (t/v- v1 v0)
            e2 (t/v- v2 v0)
            ;; Cross product gives normal
            n (t/cross e1 e2)]
        (t/normalize n)))))

(defn- face-centroid
  "Calculate the centroid of a face."
  [mesh face-idx]
  (let [verts (get-face-vertices mesh face-idx)
        n (count verts)]
    (when (pos? n)
      (let [sum (reduce t/v+ verts)]
        (t/v* sum (/ 1.0 n))))))

(defn- normal-points-toward?
  "Check if the normal of a face points toward a given point.
   The normal should have a positive dot product with the vector
   from the face centroid to the target point."
  [mesh face-idx target-point]
  (let [normal (face-normal mesh face-idx)
        centroid (face-centroid mesh face-idx)]
    (when (and normal centroid)
      (let [to-target (t/normalize (t/v- target-point centroid))
            dot-product (t/dot normal to-target)]
        (> dot-product 0)))))

(defn- find-cap-faces
  "Find faces that are likely end caps (perpendicular to extrusion direction).
   Returns {:start [...face-indices...] :end [...face-indices...]}"
  [mesh extrusion-dir]
  (let [faces (:faces mesh)
        n-faces (count faces)
        threshold 0.9  ;; cos(~25°) - faces nearly perpendicular to extrusion
        dir-normalized (t/normalize extrusion-dir)]
    (reduce
     (fn [acc idx]
       (let [n (face-normal mesh idx)]
         (when n
           (let [dot-val (t/dot n dir-normalized)]
             (cond
               ;; Normal points same direction as extrusion -> end cap
               (> dot-val threshold)
               (update acc :end conj idx)
               ;; Normal points opposite to extrusion -> start cap
               (< dot-val (- threshold))
               (update acc :start conj idx)
               :else acc)))))
     {:start [] :end []}
     (range n-faces))))

(defn- all-normals-point-outward?
  "Check that all face normals point away from mesh centroid.
   This is a basic sanity check for correct face orientation."
  [mesh]
  (let [vertices (:vertices mesh)
        mesh-centroid (t/v* (reduce t/v+ vertices)
                            (/ 1.0 (count vertices)))
        n-faces (count (:faces mesh))]
    (every?
     (fn [idx]
       (normal-points-toward? mesh idx
                              ;; Point far from centroid along normal
                              (let [fc (face-centroid mesh idx)
                                    n (face-normal mesh idx)]
                                (t/v+ fc (t/v* n 100)))))
     (range n-faces))))

(deftest extrude-basic-test
  (testing "Extrude rectangle creates mesh with correct dimensions"
    (let [rect (shape/rect-shape 40 40)
          path (t/make-path [{:cmd :f :args [40]}])
          turtle (-> (t/make-turtle)
                     (t/extrude-from-path rect path))
          mesh (last (:meshes turtle))
          bbox (mesh-bounding-box mesh)]
      (is (some? mesh)
          "Extrusion should create a mesh")
      (is (some? (:vertices mesh))
          "Mesh should have vertices")
      (is (some? (:faces mesh))
          "Mesh should have faces")
      ;; Rect 40x40 extruded 40 units forward (along X)
      ;; Shape is in YZ plane (perpendicular to heading)
      ;; So we expect: X size = 40, Y size = 40, Z size = 40
      (is (size-approx= (:size bbox) [40 40 40])
          "Extruded 40x40 rect by 40 should have bounding box 40x40x40")))

  (testing "Extrude circle creates mesh"
    (let [circ (shape/circle-shape 10 16)
          path (t/make-path [{:cmd :f :args [20]}])
          turtle (-> (t/make-turtle)
                     (t/extrude-from-path circ path))
          mesh (last (:meshes turtle))
          bbox (mesh-bounding-box mesh)]
      (is (some? mesh)
          "Circle extrusion should create a mesh")
      ;; Circle radius 10 -> diameter 20, extruded 20 units
      ;; Bounding box should be approximately 20x20x20
      (is (approx= (first (:size bbox)) 20)
          "X size (extrusion length) should be 20")
      (is (approx= (second (:size bbox)) 20)
          "Y size (diameter) should be 20")
      (is (approx= (nth (:size bbox) 2) 20)
          "Z size (diameter) should be 20"))))

(deftest extrude-with-turn-test
  (testing "Extrude with 90 degree turn"
    (let [rect (shape/rect-shape 10 10)
          path (t/make-path [{:cmd :f :args [20]}
                             {:cmd :th :args [90]}
                             {:cmd :f :args [20]}])
          turtle (-> (t/make-turtle)
                     (t/extrude-from-path rect path))
          mesh (last (:meshes turtle))]
      (is (some? mesh)
          "Extrusion with turn should create a mesh")
      (is (> (count (:vertices mesh)) 8)
          "L-shaped extrusion should have more than 8 vertices")))

  (testing "Extrude with pitch creates 3D shape"
    (let [rect (shape/rect-shape 10 10)
          path (t/make-path [{:cmd :f :args [20]}
                             {:cmd :tv :args [90]}
                             {:cmd :f :args [20]}])
          turtle (-> (t/make-turtle)
                     (t/extrude-from-path rect path))
          mesh (last (:meshes turtle))
          bbox (mesh-bounding-box mesh)]
      (is (some? mesh)
          "Extrusion with pitch should create a mesh")
      ;; After pitch 90, we move in +Z direction
      ;; So the mesh should extend in Z
      (is (> (nth (:max bbox) 2) 10)
          "Mesh should extend upward in Z after pitch"))))

(deftest extrude-closed-path-test
  (testing "Extrude along square path (closed)"
    (let [circ (shape/circle-shape 5 8)
          ;; Square path: 4 sides of 30 units each
          path (t/make-path [{:cmd :f :args [30]}
                             {:cmd :th :args [90]}
                             {:cmd :f :args [30]}
                             {:cmd :th :args [90]}
                             {:cmd :f :args [30]}
                             {:cmd :th :args [90]}
                             {:cmd :f :args [30]}
                             {:cmd :th :args [90]}])
          turtle (-> (t/make-turtle)
                     (t/extrude-closed-from-path circ path))
          mesh (last (:meshes turtle))]
      (is (some? mesh)
          "Closed extrusion should create a mesh"))))

;; ============================================================
;; Face orientation tests
;; ============================================================

(deftest face-normal-orientation-test
  (testing "Simple extrusion has outward-pointing normals"
    (let [rect (shape/rect-shape 20 20)
          path (t/make-path [{:cmd :f :args [30]}])
          turtle (-> (t/make-turtle)
                     (t/extrude-from-path rect path))
          mesh (last (:meshes turtle))]
      (is (all-normals-point-outward? mesh)
          "All face normals should point outward from mesh center")))

  (testing "End cap normal points in extrusion direction"
    (let [rect (shape/rect-shape 10 10)
          path (t/make-path [{:cmd :f :args [20]}])
          turtle (-> (t/make-turtle)
                     (t/extrude-from-path rect path))
          mesh (last (:meshes turtle))
          caps (find-cap-faces mesh [1 0 0])]  ;; extrusion along +X
      (is (pos? (count (:end caps)))
          "Should have end cap faces")
      (is (pos? (count (:start caps)))
          "Should have start cap faces")
      ;; End cap normal should point toward +X (away from mesh)
      (when-let [end-face (first (:end caps))]
        (is (normal-points-toward? mesh end-face [100 0 0])
            "End cap normal should point in +X direction"))
      ;; Start cap normal should point toward -X
      (when-let [start-face (first (:start caps))]
        (is (normal-points-toward? mesh start-face [-100 0 0])
            "Start cap normal should point in -X direction")))))

(deftest extrusion-with-turn-normals-test
  (testing "L-shaped extrusion end cap points in final direction"
    (let [rect (shape/rect-shape 10 10)
          path (t/make-path [{:cmd :f :args [30]}
                             {:cmd :th :args [90]}
                             {:cmd :f :args [30]}])
          turtle (-> (t/make-turtle)
                     (t/extrude-from-path rect path))
          mesh (last (:meshes turtle))
          ;; After turning 90 left, final direction is +Y
          caps (find-cap-faces mesh [0 1 0])]
      (is (pos? (count (:end caps)))
          "Should have end cap faces pointing in +Y")
      (when-let [end-face (first (:end caps))]
        (is (normal-points-toward? mesh end-face [30 100 0])
            "End cap should point toward final extrusion direction")))))

(deftest cap-position-vs-normal-test
  (testing "Start cap at X=0 has normal pointing -X, end cap at X=20 has normal pointing +X"
    (let [rect (shape/rect-shape 10 10)
          path (t/make-path [{:cmd :f :args [20]}])
          turtle (-> (t/make-turtle)
                     (t/extrude-from-path rect path))
          mesh (last (:meshes turtle))
          vertices (:vertices mesh)
          faces (:faces mesh)]
      ;; Find faces at X≈0 and X≈20
      (doseq [face-idx (range (count faces))]
        (let [verts (mapv #(nth vertices %) (nth faces face-idx))
              avg-x (/ (reduce + (map first verts)) (count verts))
              normal (face-normal mesh face-idx)
              normal-x (first normal)]
          ;; Start cap: X≈0 should have normal pointing -X
          (when (< avg-x 1)
            (is (< normal-x -0.9)
                (str "Start cap face at X=" avg-x " should have normal pointing -X, got " normal-x)))
          ;; End cap: X≈20 should have normal pointing +X
          (when (> avg-x 19)
            (is (> normal-x 0.9)
                (str "End cap face at X=" avg-x " should have normal pointing +X, got " normal-x))))))))

(deftest triangle-shape-extrusion-test
  (testing "Triangle shape with closing point creates valid caps"
    ;; This tests the fix for shapes where the last point nearly coincides with the first
    (let [;; Create triangle manually (simulating (shape (f 30) (th 120) (f 30) (th 120) (f 30)))
          triangle-pts [[0 0] [30 0] [15 25.98]]  ; equilateral triangle
          triangle (shape/make-shape triangle-pts {:centered? false})
          path (t/make-path [{:cmd :f :args [40]}])
          turtle (-> (t/make-turtle)
                     (t/extrude-from-path triangle path))
          mesh (last (:meshes turtle))
          faces (:faces mesh)]
      (is (some? mesh) "Triangle extrusion should create a mesh")
      (is (> (count faces) 3) "Triangle extrusion should have caps (more than just side faces)")
      ;; Verify we have faces at both ends
      (let [vertices (:vertices mesh)
            face-x-positions (map (fn [face]
                                    (let [verts (mapv #(nth vertices %) face)]
                                      (/ (reduce + (map first verts)) (count verts))))
                                  faces)
            has-start-cap (some #(< % 1) face-x-positions)
            has-end-cap (some #(> % 39) face-x-positions)]
        (is has-start-cap "Should have start cap faces at X≈0")
        (is has-end-cap "Should have end cap faces at X≈40")))))

(deftest loft-cap-orientation-test
  (testing "Loft between two rings has correctly oriented caps"
    (let [;; Create two rings at different positions (same vertex count)
          rect1 (shape/rect-shape 30 30)
          rect2 (shape/rect-shape 15 15)
          turtle1 (t/make-turtle)
          turtle2 (assoc turtle1 :position [40 0 0])
          ring1 (#'t/stamp-shape turtle1 rect1)
          ring2 (#'t/stamp-shape turtle2 rect2)
          ;; Use sweep-two-shapes directly
          mesh (t/sweep-two-shapes ring1 ring2)
          vertices (:vertices mesh)
          faces (:faces mesh)]
      (is (some? mesh) "Loft should create a mesh")
      ;; Check cap orientations
      (doseq [face-idx (range (count faces))]
        (let [verts (mapv #(nth vertices %) (nth faces face-idx))
              avg-x (/ (reduce + (map first verts)) (count verts))
              normal (face-normal mesh face-idx)
              normal-x (first normal)]
          ;; Start cap: X≈0 should have normal pointing -X
          (when (< avg-x 1)
            (is (< normal-x -0.9)
                (str "Loft start cap at X=" avg-x " should point -X, got " normal-x)))
          ;; End cap: X≈40 should have normal pointing +X
          (when (> avg-x 39)
            (is (> normal-x 0.9)
                (str "Loft end cap at X=" avg-x " should point +X, got " normal-x))))))))

(deftest mesh-identity-test
  (testing "Same extrusion twice produces identical meshes"
    (let [rect (shape/rect-shape 15 15)
          path (t/make-path [{:cmd :f :args [25]}])
          turtle1 (-> (t/make-turtle)
                      (t/extrude-from-path rect path))
          turtle2 (-> (t/make-turtle)
                      (t/extrude-from-path rect path))
          mesh1 (last (:meshes turtle1))
          mesh2 (last (:meshes turtle2))]
      (is (meshes-identical? mesh1 mesh2)
          "Two identical extrusions should produce identical meshes")))

  (testing "Corresponding faces are identical"
    (let [rect (shape/rect-shape 10 10)
          path (t/make-path [{:cmd :f :args [20]}])
          turtle1 (-> (t/make-turtle)
                      (t/extrude-from-path rect path))
          turtle2 (-> (t/make-turtle)
                      (t/extrude-from-path rect path))
          mesh1 (last (:meshes turtle1))
          mesh2 (last (:meshes turtle2))]
      ;; Check first few faces are identical
      (doseq [i (range (min 5 (count (:faces mesh1))))]
        (is (identical-face? mesh1 i mesh2 i)
            (str "Face " i " should be identical in both meshes"))))))

;; ============================================================
;; Loft tests
;; ============================================================

(deftest loft-basic-test
  (testing "Loft between two circles creates tapered mesh"
    (let [start-shape (shape/circle-shape 20 16)
          end-shape (shape/circle-shape 10 16)
          ;; Create a transform function that interpolates between shapes
          transform-fn (shape/make-lerp-fn start-shape end-shape)
          path (t/make-path [{:cmd :f :args [40]}])
          turtle (-> (t/make-turtle)
                     (t/loft-from-path start-shape transform-fn path))
          mesh (last (:meshes turtle))]
      (is (some? mesh)
          "Loft should create a mesh")
      (is (some? (:vertices mesh))
          "Loft mesh should have vertices")
      (is (some? (:faces mesh))
          "Loft mesh should have faces")
      (is (> (count (:vertices mesh)) 0)
          "Loft mesh should have at least some vertices")))

  (testing "Loft creates mesh with correct approximate dimensions"
    (let [start-shape (shape/circle-shape 20 16)
          end-shape (shape/circle-shape 10 16)
          transform-fn (shape/make-lerp-fn start-shape end-shape)
          path (t/make-path [{:cmd :f :args [40]}])
          turtle (-> (t/make-turtle)
                     (t/loft-from-path start-shape transform-fn path))
          mesh (last (:meshes turtle))
          bbox (mesh-bounding-box mesh)]
      (when bbox
        ;; Start diameter 40, end diameter 20, length 40
        ;; X size should be 40 (length)
        ;; Y and Z should be 40 (max diameter at start)
        (is (approx= (first (:size bbox)) 40)
            "X size (loft length) should be 40")
        (is (approx= (second (:size bbox)) 40)
            "Y size (start diameter) should be 40")
        (is (approx= (nth (:size bbox) 2) 40)
            "Z size (start diameter) should be 40")))))

;; --- Attach rotation pivot tests ---

(deftest rotate-attached-mesh-uses-attachment-point-as-pivot
  (testing "Rotation in attach context uses creation-pose position as pivot, not centroid"
    (let [;; Create a mesh extending from origin along +X (asymmetric)
          mesh {:type :mesh
                :vertices [[0 0 0] [10 0 0] [10 1 0] [0 1 0]
                           [0 0 1] [10 0 1] [10 1 1] [0 1 1]]
                :faces [[0 1 2] [0 2 3] [4 5 6] [4 6 7]
                        [0 1 5] [0 5 4] [2 3 7] [2 7 6]
                        [1 2 6] [1 6 5] [0 4 7] [0 7 3]]
                :creation-pose {:position [0 0 0]
                                :heading [1 0 0]
                                :up [0 0 1]}}
          ;; Add mesh to state, then attach
          state (-> (t/make-turtle)
                    (update :meshes conj mesh)
                    (t/attach mesh))
          ;; Rotate 90° around Z axis (up)
          rotated-state (t/rotate-attached-mesh state [0 0 1] 90)
          rotated-mesh (get-in rotated-state [:attached :mesh])
          verts (:vertices rotated-mesh)]
      ;; Vertex [10 0 0] should rotate to [0 10 0] around origin pivot
      ;; If it rotated around centroid [5 0.5 0.5], it would end up elsewhere
      (is (some (fn [[x y _z]]
                  (and (< (Math/abs x) 0.1)
                       (< (Math/abs (- y 10)) 0.1)))
                verts)
          "Vertex at [10 0 0] should rotate to near [0 10 0] around origin pivot")
      ;; Vertex [0 0 0] should stay at [0 0 0] (it's the pivot)
      (is (some (fn [[x y _z]]
                  (and (< (Math/abs x) 0.1)
                       (< (Math/abs y) 0.1)))
                verts)
          "Vertex at origin (pivot) should remain at origin"))))
