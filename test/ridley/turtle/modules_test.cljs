(ns ridley.turtle.modules-test
  "Verify extracted modules are independently importable and functional."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.turtle.extrusion :as ext]
            [ridley.turtle.loft :as loft]
            [ridley.turtle.attachment :as att]
            [ridley.turtle.bezier :as bez]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.core :as t]))

;; ── Extrusion module ────────────────────────────────────────

(deftest extrusion-module-accessible
  (testing "Key extrusion functions are accessible from the module"
    (is (fn? ext/extrude-from-path) "extrude-from-path exists")
    (is (fn? ext/extrude-closed-from-path) "extrude-closed-from-path exists")
    (is (fn? ext/build-sweep-mesh) "build-sweep-mesh exists")
    (is (fn? ext/analyze-open-path) "analyze-open-path exists")
    (is (fn? ext/analyze-closed-path) "analyze-closed-path exists")))

(deftest extrusion-module-works-directly
  (testing "Can extrude using module directly"
    (let [rect (shape/rect-shape 10 10)
          path (t/make-path [{:cmd :f :args [20]}])
          turtle (ext/extrude-from-path (t/make-turtle) rect path)
          mesh (last (:meshes turtle))]
      (is (some? mesh) "Direct extrusion produces a mesh")
      (is (pos? (count (:faces mesh))) "Mesh has faces"))))

;; ── Loft module ─────────────────────────────────────────────

(deftest loft-module-accessible
  (testing "Key loft functions are accessible from the module"
    (is (fn? loft/loft-from-path) "loft-from-path exists")
    (is (fn? loft/analyze-loft-path) "analyze-loft-path exists")))

(deftest loft-module-works-directly
  (testing "Can loft using module directly"
    (let [circ (shape/circle-shape 10 8)
          xf (fn [sh t] (shape/scale-shape sh (max 0.01 (- 1 t)) (max 0.01 (- 1 t))))
          path (t/make-path [{:cmd :f :args [30]}])
          turtle (loft/loft-from-path (t/make-turtle) circ xf path 16)
          mesh (last (:meshes turtle))]
      (is (some? mesh) "Direct loft produces a mesh"))))

;; ── Attachment module ───────────────────────────────────────

(deftest attachment-module-accessible
  (testing "Key attachment functions are accessible from the module"
    (is (fn? att/compute-face-info-internal) "compute-face-info-internal exists")
    (is (fn? att/mesh-centroid) "mesh-centroid exists")
    (is (fn? att/translate-mesh) "translate-mesh exists")
    (is (fn? att/attached?) "attached? exists")
    (is (fn? att/inset) "inset exists")
    (is (fn? att/scale) "scale exists")))

;; ── Bezier module ───────────────────────────────────────────

(deftest bezier-module-accessible
  (testing "Key bezier functions are accessible from the module"
    (is (fn? bez/cubic-bezier-point) "cubic-bezier-point exists")
    (is (fn? bez/quadratic-bezier-point) "quadratic-bezier-point exists")
    (is (fn? bez/sample-bezier-segment) "sample-bezier-segment exists")
    (is (fn? bez/compute-bezier-control-points) "compute-bezier-control-points exists")))

(deftest bezier-pure-functions-work
  (testing "Bezier pure functions work directly"
    (let [p (bez/cubic-bezier-point [0 0 0] [10 10 0] [20 10 0] [30 0 0] 0.5)]
      (is (= 3 (count p)) "Returns 3D point")
      (is (every? number? p) "All components are numbers"))))

;; ── Facade consistency ──────────────────────────────────────

(deftest facade-matches-modules
  (testing "Core facade delegates to modules correctly"
    (let [rect (shape/rect-shape 10 10)
          path (t/make-path [{:cmd :f :args [20]}])
          turtle-init (t/make-turtle)
          via-facade (last (:meshes (t/extrude-from-path turtle-init rect path)))
          via-module (last (:meshes (ext/extrude-from-path turtle-init rect path)))]
      (is (= (count (:vertices via-facade)) (count (:vertices via-module)))
          "Facade and module produce same vertex count")
      (is (= (:faces via-facade) (:faces via-module))
          "Facade and module produce same faces"))))
