(ns ridley.turtle.extrusion-test
  "Comprehensive tests for extrusion operations.
   Covers joint modes, closed paths, edge cases."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.turtle.core :as t]
            [ridley.turtle.shape :as shape]
            [ridley.test-helpers :as h]))

;; ═══════════════════════════════════════════════════════════
;; Joint Mode Tests
;; ═══════════════════════════════════════════════════════════

(deftest joint-mode-flat-test
  (testing "Flat joint creates valid mesh at 90 degree corner"
    (let [circ (shape/circle-shape 5 8)
          path (t/make-path [{:cmd :f :args [30]}
                             {:cmd :th :args [90]}
                             {:cmd :f :args [30]}])
          turtle (-> (t/make-turtle)
                     (t/joint-mode :flat)
                     (t/extrude-from-path circ path))
          mesh (last (:meshes turtle))]
      (is (some? mesh) "Flat joint should create mesh")
      (is (pos? (count (:faces mesh))) "Should have faces")
      ;; Bounding box should span both arms of the L
      (let [bbox (h/mesh-bounding-box mesh)]
        (is (> (first (:size bbox)) 25) "X extent should cover first arm")
        (is (> (second (:size bbox)) 25) "Y extent should cover second arm")))))

(deftest joint-mode-round-test
  (testing "Round joint creates valid mesh at 90 degree corner"
    ;; NOTE: :round is documented as "Future" but may fall back to :flat
    (let [circ (shape/circle-shape 5 8)
          path (t/make-path [{:cmd :f :args [30]}
                             {:cmd :th :args [90]}
                             {:cmd :f :args [30]}])
          turtle (-> (t/make-turtle)
                     (t/joint-mode :round)
                     (t/extrude-from-path circ path))
          mesh (last (:meshes turtle))]
      (is (some? mesh) "Round joint should create mesh")
      (is (pos? (count (:faces mesh))) "Should have faces"))))

(deftest joint-mode-tapered-test
  (testing "Tapered joint creates valid mesh at 90 degree corner"
    ;; NOTE: :tapered is documented as "Future" but may fall back to :flat
    (let [circ (shape/circle-shape 5 8)
          path (t/make-path [{:cmd :f :args [30]}
                             {:cmd :th :args [90]}
                             {:cmd :f :args [30]}])
          turtle (-> (t/make-turtle)
                     (t/joint-mode :tapered)
                     (t/extrude-from-path circ path))
          mesh (last (:meshes turtle))]
      (is (some? mesh) "Tapered joint should create mesh")
      (is (pos? (count (:faces mesh))) "Should have faces"))))

(deftest joint-mode-all-produce-similar-extent
  (testing "All joint modes produce meshes with similar bounding box"
    (let [circ (shape/circle-shape 5 8)
          path (t/make-path [{:cmd :f :args [30]}
                             {:cmd :th :args [90]}
                             {:cmd :f :args [30]}])
          make-mesh (fn [mode]
                      (last (:meshes (-> (t/make-turtle)
                                          (t/joint-mode mode)
                                          (t/extrude-from-path circ path)))))
          flat-bbox (h/mesh-bounding-box (make-mesh :flat))
          round-bbox (h/mesh-bounding-box (make-mesh :round))
          tapered-bbox (h/mesh-bounding-box (make-mesh :tapered))
          tolerance 0.3]
      (doseq [[name bbox] [["round" round-bbox] ["tapered" tapered-bbox]]]
        (is (< (Math/abs (- (first (:size bbox)) (first (:size flat-bbox))))
               (* tolerance (first (:size flat-bbox))))
            (str name " X extent should be similar to flat"))
        (is (< (Math/abs (- (second (:size bbox)) (second (:size flat-bbox))))
               (* tolerance (second (:size flat-bbox))))
            (str name " Y extent should be similar to flat"))))))

;; ═══════════════════════════════════════════════════════════
;; Closed Extrusion Robustness
;; ═══════════════════════════════════════════════════════════

(deftest closed-extrusion-triangle-test
  (testing "Closed extrusion along triangular path"
    (let [circ (shape/circle-shape 3 8)
          path (t/make-path [{:cmd :f :args [30]}
                             {:cmd :th :args [120]}
                             {:cmd :f :args [30]}
                             {:cmd :th :args [120]}
                             {:cmd :f :args [30]}
                             {:cmd :th :args [120]}])
          turtle (-> (t/make-turtle)
                     (t/extrude-closed-from-path circ path))
          mesh (last (:meshes turtle))]
      (is (some? mesh) "Triangle torus should create mesh")
      (is (pos? (count (:faces mesh))) "Should have faces"))))

(deftest closed-extrusion-hexagon-test
  (testing "Closed extrusion along hexagonal path"
    (let [rect (shape/rect-shape 4 4)
          path (t/make-path [{:cmd :f :args [20]}
                             {:cmd :th :args [60]}
                             {:cmd :f :args [20]}
                             {:cmd :th :args [60]}
                             {:cmd :f :args [20]}
                             {:cmd :th :args [60]}
                             {:cmd :f :args [20]}
                             {:cmd :th :args [60]}
                             {:cmd :f :args [20]}
                             {:cmd :th :args [60]}
                             {:cmd :f :args [20]}
                             {:cmd :th :args [60]}])
          turtle (-> (t/make-turtle)
                     (t/extrude-closed-from-path rect path))
          mesh (last (:meshes turtle))]
      (is (some? mesh) "Hexagonal torus should create mesh")
      (is (pos? (count (:faces mesh))) "Should have faces"))))

(deftest closed-extrusion-rect-shape-test
  (testing "Closed extrusion with rectangular profile (non-circular)"
    (let [rect (shape/rect-shape 6 4)
          path (t/make-path [{:cmd :f :args [25]}
                             {:cmd :th :args [90]}
                             {:cmd :f :args [25]}
                             {:cmd :th :args [90]}
                             {:cmd :f :args [25]}
                             {:cmd :th :args [90]}
                             {:cmd :f :args [25]}
                             {:cmd :th :args [90]}])
          turtle (-> (t/make-turtle)
                     (t/extrude-closed-from-path rect path))
          mesh (last (:meshes turtle))]
      (is (some? mesh) "Rect profile closed extrusion should work")
      (is (pos? (count (:faces mesh)))))))

;; ═══════════════════════════════════════════════════════════
;; Extrusion with Pitch (3D paths)
;; ═══════════════════════════════════════════════════════════

(deftest extrude-with-pitch-test
  (testing "Extrusion with tv creates proper 3D L-shape"
    (let [circ (shape/circle-shape 5 8)
          path (t/make-path [{:cmd :f :args [30]}
                             {:cmd :tv :args [90]}
                             {:cmd :f :args [30]}])
          turtle (-> (t/make-turtle)
                     (t/extrude-from-path circ path))
          mesh (last (:meshes turtle))
          bbox (h/mesh-bounding-box mesh)]
      (is (some? mesh))
      ;; First arm goes along X, second goes up along Z
      (is (> (first (:size bbox)) 25) "X extent from first arm")
      (is (> (nth (:size bbox) 2) 25) "Z extent from second arm")))

  (testing "Extrusion with combined th + tv"
    (let [rect (shape/rect-shape 6 6)
          path (t/make-path [{:cmd :f :args [20]}
                             {:cmd :th :args [90]}
                             {:cmd :f :args [20]}
                             {:cmd :tv :args [90]}
                             {:cmd :f :args [20]}])
          turtle (-> (t/make-turtle)
                     (t/extrude-from-path rect path))
          mesh (last (:meshes turtle))
          bbox (h/mesh-bounding-box mesh)]
      (is (some? mesh) "Combined th+tv extrusion should work")
      ;; Should extend in all 3 axes
      (is (> (first (:size bbox)) 15) "X extent")
      (is (> (second (:size bbox)) 15) "Y extent")
      (is (> (nth (:size bbox) 2) 15) "Z extent"))))

;; ═══════════════════════════════════════════════════════════
;; Loft with Transformations
;; ═══════════════════════════════════════════════════════════

(deftest loft-scale-down-test
  (testing "Loft with scale-down creates tapered shape"
    (let [circ (shape/circle-shape 15 12)
          xf (fn [sh t] (shape/scale-shape sh (max 0.1 (- 1 (* 0.8 t))) (max 0.1 (- 1 (* 0.8 t)))))
          path (t/make-path [{:cmd :f :args [40]}])
          turtle (-> (t/make-turtle)
                     (t/loft-from-path circ xf path 16))
          mesh (last (:meshes turtle))
          bbox (h/mesh-bounding-box mesh)]
      (is (some? mesh) "Scale-down loft should work")
      (is (h/approx= (first (:size bbox)) 40 1) "X extent = path length"))))

(deftest loft-scale-up-test
  (testing "Loft with scale-up (flared tube)"
    (let [circ (shape/circle-shape 5 8)
          xf (fn [sh t] (shape/scale-shape sh (+ 1 (* 2 t)) (+ 1 (* 2 t))))
          path (t/make-path [{:cmd :f :args [30]}])
          turtle (-> (t/make-turtle)
                     (t/loft-from-path circ xf path 16))
          mesh (last (:meshes turtle))
          bbox (h/mesh-bounding-box mesh)]
      (is (some? mesh) "Scale-up loft should work")
      ;; End diameter should be 3x start: start=10, end=30
      (is (> (second (:size bbox)) 25) "Y should reflect enlarged end"))))

(deftest loft-with-path-turns-test
  (testing "Loft along L-shaped path with taper"
    (let [circ (shape/circle-shape 10 8)
          xf (fn [sh t] (shape/scale-shape sh (- 1 (* 0.5 t)) (- 1 (* 0.5 t))))
          path (t/make-path [{:cmd :f :args [30]}
                             {:cmd :th :args [90]}
                             {:cmd :f :args [30]}])
          turtle (-> (t/make-turtle)
                     (t/loft-from-path circ xf path 16))
          meshes (:meshes turtle)]
      ;; Loft with corners may create multiple meshes (one per segment)
      (is (pos? (count meshes)) "Loft with turns should create meshes"))))

(deftest loft-lerp-between-shapes-test
  (testing "Loft morphing between two circles (different sizes)"
    (let [circ-large (shape/circle-shape 10 8)
          circ-small (shape/circle-shape 5 8)
          xf (fn [sh t] (shape/lerp-shape circ-large circ-small t))
          path (t/make-path [{:cmd :f :args [30]}])
          turtle (-> (t/make-turtle)
                     (t/loft-from-path circ-large xf path 16))
          mesh (last (:meshes turtle))
          bbox (h/mesh-bounding-box mesh)]
      (is (some? mesh) "Lerp loft should work")
      (is (h/approx= (first (:size bbox)) 30 1) "X extent = path length")
      ;; Start diameter ~20, end diameter ~10
      (is (> (second (:size bbox)) 15) "Y extent reflects start diameter"))))

;; ═══════════════════════════════════════════════════════════
;; Edge Cases
;; ═══════════════════════════════════════════════════════════

(deftest extrude-very-short-segments
  (testing "Extrusion with short segments doesn't crash"
    (let [circ (shape/circle-shape 5 8)
          ;; Segments shorter than shape radius
          path (t/make-path [{:cmd :f :args [3]}
                             {:cmd :th :args [90]}
                             {:cmd :f :args [3]}])
          result (try
                   (-> (t/make-turtle)
                       (t/extrude-from-path circ path))
                   :ok
                   (catch :default e :error))]
      ;; The key test is that it doesn't throw
      (is (= result :ok) "Should not crash on short segments"))))

(deftest extrude-acute-angle
  (testing "Extrusion with acute angle (> 90 degrees) doesn't crash"
    (let [circ (shape/circle-shape 5 8)
          path (t/make-path [{:cmd :f :args [30]}
                             {:cmd :th :args [150]}  ;; Sharp turn
                             {:cmd :f :args [30]}])
          result (try
                   (-> (t/make-turtle)
                       (t/extrude-from-path circ path))
                   :ok
                   (catch :default e :error))]
      (is (= result :ok) "Should not crash on acute angle"))))

(deftest extrude-multiple-corners
  (testing "Extrusion with many corners creates valid mesh"
    (let [circ (shape/circle-shape 3 8)
          ;; Zigzag path with 4 corners
          path (t/make-path [{:cmd :f :args [15]}
                             {:cmd :th :args [60]}
                             {:cmd :f :args [15]}
                             {:cmd :th :args [-60]}
                             {:cmd :f :args [15]}
                             {:cmd :th :args [60]}
                             {:cmd :f :args [15]}
                             {:cmd :th :args [-60]}
                             {:cmd :f :args [15]}])
          turtle (-> (t/make-turtle)
                     (t/extrude-from-path circ path))
          mesh (last (:meshes turtle))]
      (is (some? mesh) "Multi-corner extrusion should work")
      (is (> (count (:faces mesh)) 20) "Should have substantial face count"))))

(deftest extrude-with-roll
  (testing "Extrusion with tr (roll) works"
    (let [rect (shape/rect-shape 10 6)
          path (t/make-path [{:cmd :f :args [10]}
                             {:cmd :tr :args [45]}
                             {:cmd :f :args [10]}
                             {:cmd :tr :args [45]}
                             {:cmd :f :args [10]}])
          turtle (-> (t/make-turtle)
                     (t/extrude-from-path rect path))
          mesh (last (:meshes turtle))]
      (is (some? mesh) "Extrusion with roll should work"))))

;; ═══════════════════════════════════════════════════════════
;; Watertight Invariant
;; ═══════════════════════════════════════════════════════════

(deftest simple-extrusion-watertight
  (testing "Simple rect extrusion is watertight"
    (let [rect (shape/rect-shape 20 20)
          path (t/make-path [{:cmd :f :args [30]}])
          turtle (-> (t/make-turtle)
                     (t/extrude-from-path rect path))
          mesh (last (:meshes turtle))]
      (is (h/watertight? mesh) "Simple extrusion should be watertight"))))

(deftest circle-extrusion-watertight
  (testing "Circle extrusion is watertight"
    (let [circ (shape/circle-shape 10 16)
          path (t/make-path [{:cmd :f :args [30]}])
          turtle (-> (t/make-turtle)
                     (t/extrude-from-path circ path))
          mesh (last (:meshes turtle))]
      (is (h/watertight? mesh) "Circle extrusion should be watertight"))))

(deftest extrusion-with-turn-watertight
  (testing "Extrusion with 90 degree turn is watertight"
    (let [rect (shape/rect-shape 8 8)
          path (t/make-path [{:cmd :f :args [20]}
                             {:cmd :th :args [90]}
                             {:cmd :f :args [20]}])
          turtle (-> (t/make-turtle)
                     (t/extrude-from-path rect path))
          mesh (last (:meshes turtle))]
      (is (h/watertight? mesh) "L-shaped extrusion should be watertight"))))

;; ═══════════════════════════════════════════════════════════
;; Determinism
;; ═══════════════════════════════════════════════════════════

(deftest extrusion-deterministic-test
  (testing "Same inputs always produce same mesh"
    (let [make-mesh (fn []
                      (let [circ (shape/circle-shape 8 12)
                            path (t/make-path [{:cmd :f :args [25]}
                                               {:cmd :th :args [45]}
                                               {:cmd :f :args [15]}])]
                        (last (:meshes (-> (t/make-turtle)
                                           (t/extrude-from-path circ path))))))
          m1 (make-mesh)
          m2 (make-mesh)]
      (is (= (count (:vertices m1)) (count (:vertices m2)))
          "Same vertex count")
      (is (= (count (:faces m1)) (count (:faces m2)))
          "Same face count")
      (is (= (:faces m1) (:faces m2))
          "Same face indices"))))

(deftest closed-extrusion-deterministic-test
  (testing "Closed extrusion is deterministic"
    (let [make-mesh (fn []
                      (let [circ (shape/circle-shape 5 8)
                            path (t/make-path [{:cmd :f :args [20]} {:cmd :th :args [90]}
                                               {:cmd :f :args [20]} {:cmd :th :args [90]}
                                               {:cmd :f :args [20]} {:cmd :th :args [90]}
                                               {:cmd :f :args [20]} {:cmd :th :args [90]}])]
                        (last (:meshes (-> (t/make-turtle)
                                           (t/extrude-closed-from-path circ path))))))
          m1 (make-mesh)
          m2 (make-mesh)]
      (is (= (count (:vertices m1)) (count (:vertices m2))))
      (is (= (:faces m1) (:faces m2))))))

;; ═══════════════════════════════════════════════════════════
;; Normals Orientation
;; ═══════════════════════════════════════════════════════════

(deftest extrusion-normals-outward-test
  (testing "All face normals point outward from mesh center"
    (let [circ (shape/circle-shape 8 12)
          path (t/make-path [{:cmd :f :args [40]}])
          turtle (-> (t/make-turtle)
                     (t/extrude-from-path circ path))
          mesh (last (:meshes turtle))]
      (is (h/all-normals-outward? mesh)
          "All normals should point outward"))))

;; ═══════════════════════════════════════════════════════════
;; Path with Multiple Segment Types
;; ═══════════════════════════════════════════════════════════

(deftest extrude-spiral-path
  (testing "Extrusion along spiral path (combined th + tv + tr)"
    (let [rect (shape/rect-shape 4 4)
          ;; Helical path segment
          path (t/make-path [{:cmd :f :args [10]}
                             {:cmd :th :args [30]}
                             {:cmd :tv :args [15]}
                             {:cmd :f :args [10]}
                             {:cmd :th :args [30]}
                             {:cmd :tv :args [15]}
                             {:cmd :f :args [10]}
                             {:cmd :th :args [30]}
                             {:cmd :tv :args [15]}
                             {:cmd :f :args [10]}])
          turtle (-> (t/make-turtle)
                     (t/extrude-from-path rect path))
          mesh (last (:meshes turtle))
          bbox (h/mesh-bounding-box mesh)]
      (is (some? mesh) "Spiral extrusion should create mesh")
      ;; Should have extent in all 3 dimensions
      (is (> (first (:size bbox)) 10) "X extent")
      (is (> (second (:size bbox)) 5) "Y extent")
      (is (> (nth (:size bbox) 2) 5) "Z extent"))))
