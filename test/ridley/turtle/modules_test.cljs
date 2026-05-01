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

(defn- mesh-with-anchor
  "Build a minimal mesh with a creation-pose and a single anchor at offset [dx dy dz]."
  [creation-pos anchor-offset]
  {:type :mesh
   :vertices [creation-pos (mapv + creation-pos [1 0 0])]
   :creation-pose {:position creation-pos
                   :heading [1 0 0]
                   :up [0 0 1]}
   :anchors {:tip {:position (mapv + creation-pos anchor-offset)
                   :heading [1 0 0]
                   :up [0 0 1]}}})

(deftest translate-mesh-updates-anchors
  (testing "translate-mesh shifts both creation-pose and anchors by the same offset"
    (let [m (mesh-with-anchor [10 0 0] [5 0 0])
          t (att/translate-mesh m [0 100 0])]
      (is (= [10 100 0] (get-in t [:creation-pose :position]))
          "creation-pose moves by offset")
      (is (= [15 100 0] (get-in t [:anchors :tip :position]))
          "anchor position moves by offset (was [15 0 0], +[0 100 0])")
      (is (= [1 0 0] (get-in t [:anchors :tip :heading]))
          "anchor heading is unchanged by translation"))))

(deftest rotate-mesh-updates-anchors
  (testing "rotate-mesh keeps anchors aligned with the rotated mesh"
    (let [;; Mesh centered at [10 0 0] with anchor offset [5 0 0] (so anchor at [15 0 0])
          m (mesh-with-anchor [10 0 0] [5 0 0])
          ;; 90° around Z, around centroid
          ;; Vertices [[10 0 0] [11 0 0]] → centroid [10.5 0 0]
          ;; After 90° rot around Z: anchor relative to centroid was [4.5 0 0],
          ;; rotated → [0 4.5 0], so anchor lands at [10.5 4.5 0]
          t (att/rotate-mesh m [0 0 1] (/ js/Math.PI 2))]
      (is (some? (get-in t [:anchors :tip])) "anchor still present after rotation")
      (let [pos (get-in t [:anchors :tip :position])]
        (is (< (Math/abs (- (first pos) 10.5)) 0.001) "x ≈ 10.5")
        (is (< (Math/abs (- (second pos) 4.5)) 0.001) "y ≈ 4.5"))
      (let [h (get-in t [:anchors :tip :heading])]
        (is (< (Math/abs (- (first h) 0)) 0.001) "heading.x ≈ 0 after 90° rot")
        (is (< (Math/abs (- (second h) 1)) 0.001) "heading.y ≈ 1 after 90° rot")))))

(deftest translate-mesh-no-anchors-still-works
  (testing "translate-mesh on a mesh without :anchors does not blow up"
    (let [m {:type :mesh
             :vertices [[0 0 0] [1 0 0]]
             :creation-pose {:position [0 0 0] :heading [1 0 0] :up [0 0 1]}}
          t (att/translate-mesh m [10 0 0])]
      (is (= [10 0 0] (get-in t [:creation-pose :position])))
      (is (nil? (:anchors t)) "anchors stays nil"))))

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
