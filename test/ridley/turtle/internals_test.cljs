(ns ridley.turtle.internals-test
  "Tests for internal pure functions of turtle core.
   These test the geometric computation engine directly."
  (:require [cljs.test :refer [deftest testing is are]]
            [ridley.turtle.core :as t]
            [ridley.turtle.shape :as shape]))

;; ── Helpers ─────────────────────────────────────────────────

(def epsilon 1e-6)

(defn approx= [a b]
  (< (Math/abs (- a b)) epsilon))

(defn vec-approx= [[x1 y1 z1] [x2 y2 z2]]
  (and (approx= x1 x2) (approx= y1 y2) (approx= z1 z2)))

;; ── shape-radius ────────────────────────────────────────────

(deftest shape-radius-test
  (testing "Circle shape has correct radius"
    (let [circ (shape/circle-shape 10 16)
          r (t/shape-radius circ)]
      (is (approx= r 10) "Circle radius 10 should report ~10")))

  (testing "Rectangle shape radius is half diagonal"
    (let [rect (shape/rect-shape 20 10)
          r (t/shape-radius rect)
          ;; half-diagonal of 20x10 centered rect: sqrt(10^2 + 5^2)
          expected (Math/sqrt (+ (* 10 10) (* 5 5)))]
      (is (> r 0) "Rectangle should have positive radius")
      (is (approx= r expected) "Rectangle radius should be half-diagonal"))))

;; ── compute-stamp-transform ─────────────────────────────────

(deftest compute-stamp-transform-test
  (testing "Default turtle pose produces expected transform"
    (let [turtle (t/make-turtle)
          rect (shape/rect-shape 10 10)
          xf (t/compute-stamp-transform turtle rect)]
      ;; plane-x should be right vector (heading x up = [1,0,0] x [0,0,1] = [0,1,0])
      (is (some? (:plane-x xf)) "Transform should have :plane-x")
      (is (some? (:plane-y xf)) "Transform should have :plane-y")
      (is (some? (:offset xf)) "Transform should have :offset")
      (is (some? (:origin xf)) "Transform should have :origin")
      (is (vec-approx= (:origin xf) [0 0 0]) "Origin should be at turtle position")))

  (testing "After movement, origin reflects new position"
    (let [turtle (-> (t/make-turtle) (t/f 50))
          rect (shape/rect-shape 10 10)
          xf (t/compute-stamp-transform turtle rect)]
      (is (vec-approx= (:origin xf) [50 0 0])))))

;; ── stamp-shape ─────────────────────────────────────────────

(deftest stamp-shape-test
  (testing "Stamp places shape at turtle position"
    (let [turtle (t/make-turtle)
          rect (shape/rect-shape 10 10)
          ring (t/stamp-shape turtle rect)]
      (is (vector? ring) "stamp-shape should return a vector of 3D points")
      (is (every? #(= 3 (count %)) ring) "Each point should be [x y z]")
      ;; At default pose, shape is in YZ plane at x=0
      (is (every? #(approx= (first %) 0) ring)
          "At origin facing +X, shape should be in YZ plane (all x≈0)")))

  (testing "Stamp after movement places shape at new position"
    (let [turtle (-> (t/make-turtle) (t/f 50))
          rect (shape/rect-shape 10 10)
          ring (t/stamp-shape turtle rect)]
      ;; Shape should be in YZ plane at x=50
      (is (every? #(approx= (first %) 50) ring)
          "After f 50, shape should be at x≈50"))))

;; ── analyze-open-path ───────────────────────────────────────

(deftest analyze-open-path-test
  (testing "Simple forward path needs no shortening"
    (let [commands [{:cmd :f :args [20]}]
          radius 5
          result (t/analyze-open-path commands radius)]
      (is (vector? result) "Should return vector of analyzed segments")
      (is (= 1 (count result)) "One forward = one segment")
      (is (= 0 (:shorten-start (first result)))
          "First segment start should not be shortened")
      (is (= 0 (:shorten-end (first result)))
          "Last segment end should not be shortened")))

  (testing "Path with turn needs shortening at corner"
    (let [commands [{:cmd :f :args [30]}
                    {:cmd :th :args [90]}
                    {:cmd :f :args [30]}]
          radius 5
          result (t/analyze-open-path commands radius)]
      ;; Should have 2 forward segments
      (is (= 2 (count result)) "Two forward segments expected")
      ;; First segment should be shortened at end (corner)
      (is (> (:shorten-end (first result)) 0)
          "First segment should be shortened at end (corner)")
      ;; Second segment should be shortened at start
      (is (> (:shorten-start (second result)) 0)
          "Second segment should be shortened at start (corner)"))))

;; ── analyze-closed-path ─────────────────────────────────────

(deftest analyze-closed-path-test
  (testing "Square path has shortening at all corners"
    (let [commands [{:cmd :f :args [30]}
                    {:cmd :th :args [90]}
                    {:cmd :f :args [30]}
                    {:cmd :th :args [90]}
                    {:cmd :f :args [30]}
                    {:cmd :th :args [90]}
                    {:cmd :f :args [30]}
                    {:cmd :th :args [90]}]
          radius 5
          result (t/analyze-closed-path commands radius)]
      (is (vector? result) "Should return vector")
      (is (= 4 (count result)) "Four forward segments in square")
      ;; All forward segments should have shortening at both ends
      (doseq [seg result]
        (is (> (:shorten-start seg) 0) "Each segment start shortened")
        (is (> (:shorten-end seg) 0) "Each segment end shortened")))))

;; ── is-rotation? predicates ─────────────────────────────────

(deftest predicate-tests
  (testing "is-rotation?"
    (is (t/is-rotation? :th) "th is a rotation")
    (is (t/is-rotation? :tv) "tv is a rotation")
    (is (t/is-rotation? :tr) "tr is a rotation")
    (is (t/is-rotation? :set-heading) "set-heading is a rotation")
    (is (not (t/is-rotation? :f)) "f is not a rotation"))

  (testing "is-corner-rotation?"
    (is (t/is-corner-rotation? :th) "th is a corner rotation")
    (is (t/is-corner-rotation? :tv) "tv is a corner rotation")
    (is (t/is-corner-rotation? :tr) "tr is a corner rotation")
    (is (not (t/is-corner-rotation? :set-heading)) "set-heading is not a corner rotation")
    (is (not (t/is-corner-rotation? :f)) "f is not a corner rotation"))

  (testing "is-path?"
    (is (t/is-path? {:type :path :commands []}) "Map with :type :path is a path")
    (is (not (t/is-path? {:type :shape})) "Map with :type :shape is not a path")
    (is (not (t/is-path? nil)) "nil is not a path"))

  (testing "is-simple-forward-path?"
    (let [simple-path (t/make-path [{:cmd :f :args [10]}])
          complex-path (t/make-path [{:cmd :f :args [10]}
                                     {:cmd :th :args [90]}
                                     {:cmd :f :args [10]}])]
      (is (t/is-simple-forward-path? simple-path)
          "Single forward is simple")
      (is (not (t/is-simple-forward-path? complex-path))
          "Path with rotation is not simple"))))

;; ── compute-triangle-normal ─────────────────────────────────

(deftest compute-triangle-normal-test
  (testing "XY plane triangle has Z normal"
    (let [n (t/compute-triangle-normal [0 0 0] [1 0 0] [0 1 0])]
      (is (vec-approx= n [0 0 1])
          "CCW triangle in XY plane should have +Z normal")))

  (testing "Reversed winding has negative Z normal"
    (let [n (t/compute-triangle-normal [0 0 0] [0 1 0] [1 0 0])]
      (is (vec-approx= n [0 0 -1])
          "CW triangle in XY plane should have -Z normal")))

  (testing "XZ plane triangle has Y normal"
    (let [n (t/compute-triangle-normal [0 0 0] [0 0 1] [1 0 0])]
      (is (vec-approx= n [0 1 0])
          "CCW triangle in XZ plane should have +Y normal"))))

;; ── build-sweep-mesh ────────────────────────────────────────

(deftest build-sweep-mesh-test
  (testing "Two rings create a valid tube mesh"
    (let [;; Simple square ring at z=0 and z=10
          ring1 [[1 1 0] [-1 1 0] [-1 -1 0] [1 -1 0]]
          ring2 [[1 1 10] [-1 1 10] [-1 -1 10] [1 -1 10]]
          mesh (t/build-sweep-mesh [ring1 ring2] false nil true)]
      (is (some? mesh) "Should create a mesh")
      (is (vector? (:vertices mesh)) "Mesh should have vertices")
      (is (vector? (:faces mesh)) "Mesh should have faces")
      (is (= 8 (count (:vertices mesh))) "Two 4-point rings = 8 vertices")
      (is (pos? (count (:faces mesh))) "Should have faces")))

  (testing "Single ring produces no mesh"
    (let [ring1 [[1 1 0] [-1 1 0] [-1 -1 0] [1 -1 0]]
          mesh (t/build-sweep-mesh [ring1] false nil true)]
      (is (nil? mesh) "Single ring should return nil")))

  (testing "Empty rings produce no mesh"
    (let [mesh (t/build-sweep-mesh [] false nil true)]
      (is (nil? mesh) "Empty rings should return nil"))))

;; ── build-segment-mesh ──────────────────────────────────────

(deftest build-segment-mesh-test
  (testing "Two rings create a segment mesh without caps"
    (let [ring1 [[1 1 0] [-1 1 0] [-1 -1 0] [1 -1 0]]
          ring2 [[1 1 10] [-1 1 10] [-1 -1 10] [1 -1 10]]
          mesh (t/build-segment-mesh [ring1 ring2])]
      (is (some? mesh) "Should create a mesh")
      (is (= :segment (:primitive mesh)) "Should be a segment primitive")
      (is (= 8 (count (:vertices mesh))) "Two 4-point rings = 8 vertices")
      ;; 4 quads = 8 triangles for side faces
      (is (= 8 (count (:faces mesh))) "4 quads = 8 triangles"))))

;; ── build-corner-mesh ───────────────────────────────────────

(deftest build-corner-mesh-test
  (testing "Two rings create a corner mesh"
    (let [ring1 [[1 1 0] [-1 1 0] [-1 -1 0] [1 -1 0]]
          ring2 [[1 1 5] [-1 1 5] [-1 -1 5] [1 -1 5]]
          mesh (t/build-corner-mesh ring1 ring2)]
      (is (some? mesh) "Should create a mesh")
      (is (= :corner (:primitive mesh)) "Should be a corner primitive")
      (is (= 8 (count (:vertices mesh))) "Two 4-point rings = 8 vertices")))

  (testing "Mismatched ring sizes produce no mesh"
    (let [ring1 [[1 1 0] [-1 1 0] [-1 -1 0] [1 -1 0]]
          ring2 [[1 1 0] [-1 1 0] [-1 -1 0]]  ;; Only 3 points
          mesh (t/build-corner-mesh ring1 ring2)]
      (is (nil? mesh) "Mismatched rings should return nil"))))

;; ── Integration: extrude invariants ─────────────────────────

(deftest extrude-invariants-test
  (testing "Extruded box volume matches expected"
    (let [rect (shape/rect-shape 20 20)
          path (t/make-path [{:cmd :f :args [30]}])
          turtle (-> (t/make-turtle)
                     (t/extrude-from-path rect path))
          mesh (last (:meshes turtle))
          verts (:vertices mesh)
          xs (map first verts)
          ys (map second verts)
          zs (map #(nth % 2) verts)
          bbox-size [(- (apply max xs) (apply min xs))
                     (- (apply max ys) (apply min ys))
                     (- (apply max zs) (apply min zs))]]
      ;; 20×20 rect extruded 30 = bounding box 30×20×20
      (is (approx= (first bbox-size) 30) "X extent = 30 (extrusion length)")
      (is (approx= (second bbox-size) 20) "Y extent = 20 (rect width)")
      (is (approx= (nth bbox-size 2) 20) "Z extent = 20 (rect height)")))

  (testing "Closed extrusion has more faces than open"
    (let [circ (shape/circle-shape 5 8)
          open-path (t/make-path [{:cmd :f :args [30]}])
          closed-path (t/make-path [{:cmd :f :args [30]} {:cmd :th :args [90]}
                                    {:cmd :f :args [30]} {:cmd :th :args [90]}
                                    {:cmd :f :args [30]} {:cmd :th :args [90]}
                                    {:cmd :f :args [30]} {:cmd :th :args [90]}])
          open-mesh (last (:meshes (-> (t/make-turtle)
                                       (t/extrude-from-path circ open-path))))
          closed-mesh (last (:meshes (-> (t/make-turtle)
                                         (t/extrude-closed-from-path circ closed-path))))]
      (is (some? open-mesh) "Open extrusion creates mesh")
      (is (some? closed-mesh) "Closed extrusion creates mesh")
      ;; Closed has 4 segments × ring, open has 1 segment
      (is (> (count (:faces closed-mesh)) (count (:faces open-mesh)))
          "Closed extrusion should have more faces"))))

;; ── transform-2d-to-3d ──────────────────────────────────────

(deftest transform-2d-to-3d-test
  (testing "Identity-like transform preserves points in right/up plane"
    (let [params {:plane-x [0 1 0]  ;; right (Y)
                  :plane-y [0 0 1]  ;; up (Z)
                  :offset [0 0]
                  :origin [0 0 0]}
          points [[1 2] [-1 3]]
          result (t/transform-2d-to-3d points params)]
      (is (= 2 (count result)) "Should have same number of points")
      ;; [1,2] -> origin + 1*plane-x + 2*plane-y = [0, 1, 2]
      (is (vec-approx= (first result) [0 1 2]))
      ;; [-1,3] -> origin + (-1)*plane-x + 3*plane-y = [0, -1, 3]
      (is (vec-approx= (second result) [0 -1 3]))))

  (testing "Origin offset is applied"
    (let [params {:plane-x [1 0 0]
                  :plane-y [0 1 0]
                  :offset [0 0]
                  :origin [10 20 30]}
          points [[0 0]]
          result (t/transform-2d-to-3d points params)]
      (is (vec-approx= (first result) [10 20 30])))))
