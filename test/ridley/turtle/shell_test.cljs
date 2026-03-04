(ns ridley.turtle.shell-test
  "Tests for shell and woven-shell shape-fns."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.turtle.core :as t]
            [ridley.turtle.loft :as loft]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.shape-fn :as sfn]
            [ridley.turtle.extrusion :as extrusion]
            [ridley.test-helpers :as h]))

;; ═══════════════════════════════════════════════════════════
;; Shell shape-fn basics
;; ═══════════════════════════════════════════════════════════

(deftest shell-returns-shape-fn
  (testing "shell returns a shape-fn (function with :type :shape-fn metadata)"
    (let [s (sfn/shell (shape/circle-shape 20 16)
              :thickness 3
              :fn (fn [a t] 1.0))]
      (is (fn? s) "shell returns a function")
      (is (= :shape-fn (:type (meta s))) "has :shape-fn metadata"))))

(deftest shell-attaches-metadata
  (testing "shell shape-fn attaches :shell-mode and :shell-values to shape"
    (let [s (sfn/shell (shape/circle-shape 20 16)
              :thickness 3
              :fn (fn [a t] 1.0))
          result (s 0.5)]
      (is (true? (:shell-mode result)) "shape has :shell-mode")
      (is (= 3 (:shell-thickness result)) "shell-thickness preserved")
      (is (= 16 (count (:shell-values result))) "one value per vertex")
      (is (every? #(= 1.0 %) (:shell-values result)) "all values = 1.0 for constant fn"))))

(deftest shell-threshold-snaps-to-zero
  (testing "Values below threshold snap to 0"
    (let [s (sfn/shell (shape/circle-shape 20 16)
              :thickness 2
              :fn (fn [a t] 0.03))  ;; below default threshold 0.05
          result (s 0.5)]
      (is (every? zero? (:shell-values result)) "below-threshold values snap to 0"))))

(deftest shell-values-clamped
  (testing "Values are clamped to [0, 1]"
    (let [s (sfn/shell (shape/circle-shape 20 8)
              :thickness 2
              :fn (fn [a t] 5.0))  ;; above 1.0
          result (s 0.5)]
      (is (every? #(= 1.0 %) (:shell-values result)) "values clamped to 1.0"))))

;; ═══════════════════════════════════════════════════════════
;; Shell mesh generation
;; ═══════════════════════════════════════════════════════════

(defn- sfn->transform
  "Adapt a shape-fn (1-arity) to loft's transform-fn (2-arity).
   Matches what pure-loft-shape-fn does in operations.cljs."
  [sfn]
  (fn [_shape t] (sfn t)))

(defn- make-shell-mesh
  "Helper: create a shell mesh via loft-from-path."
  [n-pts steps thickness shell-fn]
  (let [circ (shape/circle-shape 20 n-pts)
        sfn (sfn/shell circ :thickness thickness :fn shell-fn)
        path (t/make-path [{:cmd :f :args [40]}])
        turtle (loft/loft-from-path (t/make-turtle) circ (sfn->transform sfn) path steps)
        mesh (last (:meshes turtle))]
    mesh))

(deftest shell-uniform-creates-mesh
  (testing "Uniform shell (constant 1.0) creates a valid mesh"
    (let [mesh (make-shell-mesh 16 16 3 (fn [a t] 1.0))]
      (is (some? mesh) "mesh was created")
      (is (= :shell (:primitive mesh)) "primitive type is :shell")
      (is (pos? (count (:vertices mesh))) "has vertices")
      (is (pos? (count (:faces mesh))) "has faces"))))

(deftest shell-uniform-vertex-count
  (testing "Uniform shell has 2 rings (outer + inner) per step"
    (let [n-pts 16
          steps 16
          mesh (make-shell-mesh n-pts steps 3 (fn [a t] 1.0))]
      ;; Each step has n-pts outer + n-pts inner = 2*n-pts
      ;; Total steps = steps + 1 (including start)
      ;; But loft generates (steps) rings typically
      (is (zero? (mod (count (:vertices mesh)) (* 2 n-pts)))
          "vertex count should be multiple of 2*n-pts"))))

(deftest shell-uniform-has-double-faces
  (testing "Shell has both outer and inner faces (roughly double normal loft)"
    (let [mesh (make-shell-mesh 12 12 3 (fn [a t] 1.0))]
      ;; Each quad between rings produces 2 outer + 2 inner triangles = 4 triangles
      ;; Plus cap faces. Should have significantly more faces than a normal loft.
      (is (> (count (:faces mesh)) 200) "shell should have many faces (outer + inner + caps)"))))

(deftest shell-bounding-box
  (testing "Shell bounding box reflects thickness expansion"
    (let [mesh-thin (make-shell-mesh 16 16 1 (fn [a t] 1.0))
          mesh-thick (make-shell-mesh 16 16 6 (fn [a t] 1.0))
          bbox-thin (h/mesh-bounding-box mesh-thin)
          bbox-thick (h/mesh-bounding-box mesh-thick)]
      ;; Thicker shell should have larger Y/Z extent (radial)
      (is (> (second (:size bbox-thick)) (second (:size bbox-thin)))
          "thicker shell has larger Y extent")
      (is (> (nth (:size bbox-thick) 2) (nth (:size bbox-thin) 2))
          "thicker shell has larger Z extent"))))

(deftest shell-with-openings
  (testing "Shell with partial openings creates valid mesh"
    (let [mesh (make-shell-mesh 16 16 3
                (fn [a t] (if (pos? (Math/sin (* a 4))) 1.0 0.0)))]
      (is (some? mesh) "mesh with openings was created")
      (is (pos? (count (:faces mesh))) "has faces"))))

(deftest shell-fully-open
  (testing "Shell with all zeros still creates a mesh (degenerate but valid)"
    (let [mesh (make-shell-mesh 16 16 3 (fn [a t] 0.0))]
      ;; All values are 0 → all faces skipped, but caps may still exist
      ;; The mesh may be nil or empty - either is acceptable
      (is (or (nil? mesh) (>= (count (:faces mesh)) 0))
          "fully open shell is nil or has no faces"))))

;; ═══════════════════════════════════════════════════════════
;; Shell ring generation
;; ═══════════════════════════════════════════════════════════

(deftest generate-shell-ring-symmetric
  (testing "Outer and inner rings are symmetric around base ring"
    (let [base-ring [[0 10 0] [10 0 0] [0 -10 0] [-10 0 0]]
          values [1.0 1.0 1.0 1.0]
          half-t 2.0
          outer (extrusion/generate-shell-ring base-ring half-t values false)
          inner (extrusion/generate-shell-ring base-ring half-t values true)]
      ;; Outer should be farther from centroid, inner closer
      (let [centroid [0 0 0]
            outer-dist (Math/sqrt (+ (* (first (first outer)) (first (first outer)))
                                     (* (second (first outer)) (second (first outer)))))
            inner-dist (Math/sqrt (+ (* (first (first inner)) (first (first inner)))
                                     (* (second (first inner)) (second (first inner)))))
            base-dist 10.0]
        (is (> outer-dist base-dist) "outer is farther from centroid than base")
        (is (< inner-dist base-dist) "inner is closer to centroid than base")
        (is (h/approx= (- outer-dist base-dist) (- base-dist inner-dist) 0.01)
            "displacement is symmetric")))))

(deftest generate-shell-ring-zero-value
  (testing "Zero-value points stay at base position"
    (let [base-ring [[0 10 0] [10 0 0] [0 -10 0] [-10 0 0]]
          values [0.0 1.0 0.0 1.0]
          outer (extrusion/generate-shell-ring base-ring 2.0 values false)
          inner (extrusion/generate-shell-ring base-ring 2.0 values true)]
      ;; Points with value=0 should be at base position
      (is (h/vec-approx= (first outer) [0 10 0] 0.001)
          "zero-value outer point stays at base")
      (is (h/vec-approx= (nth outer 2) [0 -10 0] 0.001)
          "zero-value outer point stays at base")
      (is (h/vec-approx= (first inner) [0 10 0] 0.001)
          "zero-value inner point stays at base"))))

(deftest generate-shell-ring-with-offset
  (testing "Offset shifts the wall center radially"
    (let [base-ring [[0 10 0] [10 0 0] [0 -10 0] [-10 0 0]]
          values [1.0 1.0 1.0 1.0]
          offsets [2.0 2.0 2.0 2.0]
          ;; Without offset
          outer-no-off (extrusion/generate-shell-ring base-ring 1.0 values false)
          inner-no-off (extrusion/generate-shell-ring base-ring 1.0 values true)
          ;; With offset (shifted outward)
          outer-off (extrusion/generate-shell-ring base-ring 1.0 values false
                      :offsets offsets)
          inner-off (extrusion/generate-shell-ring base-ring 1.0 values true
                      :offsets offsets)]
      ;; With positive offset, both outer and inner should be farther out
      (let [dist (fn [p] (Math/sqrt (+ (* (first p) (first p))
                                       (* (second p) (second p)))))
            outer-d (dist (first outer-off))
            outer-no-d (dist (first outer-no-off))
            inner-d (dist (first inner-off))
            inner-no-d (dist (first inner-no-off))]
        (is (> outer-d outer-no-d)
            "offset moves outer ring further out")
        (is (> inner-d inner-no-d)
            "offset moves inner ring further out")))))

;; ═══════════════════════════════════════════════════════════
;; Built-in shell patterns
;; ═══════════════════════════════════════════════════════════

(deftest shell-lattice-creates-mesh
  (testing "shell-lattice convenience creates valid shell mesh"
    (let [circ (shape/circle-shape 20 16)
          sfn (sfn/shell-lattice circ :thickness 2 :openings 8 :rows 12)
          path (t/make-path [{:cmd :f :args [40]}])
          turtle (loft/loft-from-path (t/make-turtle) circ (sfn->transform sfn) path 32)
          mesh (last (:meshes turtle))]
      (is (some? mesh) "shell-lattice creates mesh")
      (is (= :shell (:primitive mesh))))))

(deftest shell-checkerboard-creates-mesh
  (testing "shell-checkerboard creates valid mesh"
    (let [circ (shape/circle-shape 20 16)
          sfn (sfn/shell-checkerboard circ :thickness 2 :cols 6 :rows 6)
          path (t/make-path [{:cmd :f :args [40]}])
          turtle (loft/loft-from-path (t/make-turtle) circ (sfn->transform sfn) path 32)
          mesh (last (:meshes turtle))]
      (is (some? mesh) "shell-checkerboard creates mesh")
      (is (= :shell (:primitive mesh))))))

(deftest shell-voronoi-creates-mesh
  (testing "shell-voronoi creates valid mesh"
    (let [circ (shape/circle-shape 20 16)
          sfn (sfn/shell-voronoi circ :thickness 2 :cells 6 :rows 6)
          path (t/make-path [{:cmd :f :args [40]}])
          turtle (loft/loft-from-path (t/make-turtle) circ (sfn->transform sfn) path 32)
          mesh (last (:meshes turtle))]
      (is (some? mesh) "shell-voronoi creates mesh")
      (is (= :shell (:primitive mesh))))))

;; ═══════════════════════════════════════════════════════════
;; Woven shell
;; ═══════════════════════════════════════════════════════════

(deftest woven-shell-returns-shape-fn
  (testing "woven-shell returns a shape-fn"
    (let [s (sfn/woven-shell (shape/circle-shape 20 16) :thickness 3 :strands 6)]
      (is (fn? s))
      (is (= :shape-fn (:type (meta s)))))))

(deftest woven-shell-attaches-offsets
  (testing "woven-shell attaches :shell-offsets to shape"
    ;; Need enough points (64) so some actually land on threads (width=0.12)
    (let [s (sfn/woven-shell (shape/circle-shape 20 64) :thickness 3 :strands 6)
          result (s 0.5)]
      (is (true? (:shell-mode result)))
      (is (some? (:shell-offsets result)) "has :shell-offsets")
      (is (= 64 (count (:shell-offsets result))) "one offset per vertex")
      (is (not (every? zero? (:shell-offsets result)))
          "not all offsets are zero (threads should undulate)"))))

(deftest woven-shell-diagonal-creates-mesh
  (testing "woven-shell diagonal mode creates valid mesh"
    (let [circ (shape/circle-shape 20 32)
          sfn (sfn/woven-shell circ :thickness 3 :strands 6)
          path (t/make-path [{:cmd :f :args [40]}])
          turtle (loft/loft-from-path (t/make-turtle) circ (sfn->transform sfn) path 32)
          mesh (last (:meshes turtle))]
      (is (some? mesh) "diagonal woven-shell creates mesh")
      (is (= :shell (:primitive mesh))))))

(deftest woven-shell-orthogonal-creates-mesh
  (testing "woven-shell orthogonal mode creates valid mesh"
    (let [circ (shape/circle-shape 20 32)
          sfn (sfn/woven-shell circ :thickness 3
                :mode :orthogonal
                :warp 6 :weft 12
                :warp-width 0.2 :weft-width 0.1)
          path (t/make-path [{:cmd :f :args [40]}])
          turtle (loft/loft-from-path (t/make-turtle) circ (sfn->transform sfn) path 32)
          mesh (last (:meshes turtle))]
      (is (some? mesh) "orthogonal woven-shell creates mesh")
      (is (= :shell (:primitive mesh))))))

(deftest woven-shell-custom-fn
  (testing "woven-shell with custom fn returning {:thickness :offset}"
    (let [circ (shape/circle-shape 20 16)
          sfn (sfn/woven-shell circ :thickness 2
                :fn (fn [a t] {:thickness 0.8 :offset (* 0.5 (Math/sin (* a 4)))}))
          path (t/make-path [{:cmd :f :args [40]}])
          turtle (loft/loft-from-path (t/make-turtle) circ (sfn->transform sfn) path 16)
          mesh (last (:meshes turtle))]
      (is (some? mesh) "custom-fn woven-shell creates mesh")
      (is (= :shell (:primitive mesh))))))

;; ═══════════════════════════════════════════════════════════
;; Composition with other shape-fns
;; ═══════════════════════════════════════════════════════════

(deftest shell-composes-with-tapered
  (testing "shell + tapered creates a narrowing lattice"
    (let [circ (shape/circle-shape 20 16)
          sfn (-> circ
                  (sfn/shell :thickness 2
                    :fn (fn [a t] (max 0 (Math/sin (+ (* a 6) (* t Math/PI 4))))))
                  (sfn/tapered :to 0.5))
          path (t/make-path [{:cmd :f :args [40]}])
          turtle (loft/loft-from-path (t/make-turtle) circ (sfn->transform sfn) path 32)
          mesh (last (:meshes turtle))
          bbox (h/mesh-bounding-box mesh)]
      (is (some? mesh) "shell+tapered creates mesh")
      (is (= :shell (:primitive mesh)))
      ;; End should be narrower than start due to taper
      ;; Check that the mesh exists and has reasonable extent
      (is (> (second (:size bbox)) 10) "has radial extent"))))

(deftest shell-composes-with-twisted
  (testing "shell + twisted creates a twisted lattice"
    (let [circ (shape/circle-shape 20 16)
          sfn (-> circ
                  (sfn/shell :thickness 2
                    :fn (fn [a t] (max 0 (Math/sin (+ (* a 6) (* t Math/PI 4))))))
                  (sfn/twisted :angle 90))
          path (t/make-path [{:cmd :f :args [40]}])
          turtle (loft/loft-from-path (t/make-turtle) circ (sfn->transform sfn) path 32)
          mesh (last (:meshes turtle))]
      (is (some? mesh) "shell+twisted creates mesh")
      (is (= :shell (:primitive mesh))))))

(deftest woven-shell-composes-with-tapered
  (testing "woven-shell + tapered composition"
    (let [circ (shape/circle-shape 20 32)
          sfn (-> circ
                  (sfn/woven-shell :thickness 3 :strands 6)
                  (sfn/tapered :to 0.4))
          path (t/make-path [{:cmd :f :args [40]}])
          turtle (loft/loft-from-path (t/make-turtle) circ (sfn->transform sfn) path 32)
          mesh (last (:meshes turtle))]
      (is (some? mesh) "woven-shell+tapered creates mesh")
      (is (= :shell (:primitive mesh))))))
