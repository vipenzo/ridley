(ns ridley.geometry.symmetry-test
  "Pure tests for the area-weighted PCA + symmetry-plane candidates. No WASM —
   the Manifold B6 verification lives in symmetry-planes (browser-only); this
   covers the tessellation-invariant PCA (the permanent B7 test) and the
   degenerate-eigenvalue fallback."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.geometry.symmetry :as sym]
            [ridley.geometry.primitives :as prim]
            [ridley.test-helpers :as h]))

(defn- unit [[x y z]]
  (let [m (js/Math.sqrt (+ (* x x) (* y y) (* z z)))]
    [(/ x m) (/ y m) (/ z m)]))
(defn- adot [a b] (js/Math.abs (reduce + (map * a b))))
(defn- parallel? [a b] (h/approx= 1.0 (adot (unit a) (unit b)) 1e-4))

;; ── Jacobi eigendecomposition ───────────────────────────────

(deftest jacobi-diagonal-matrix
  (testing "a diagonal matrix returns its diagonal (descending) and the axes"
    (let [{:keys [values vectors]} (sym/jacobi-eigen-3x3 [[3 0 0] [0 1 0] [0 0 2]])]
      (is (h/vec-approx= [3.0 2.0 1.0] values 1e-9))
      (is (parallel? (nth vectors 0) [1 0 0]))
      (is (parallel? (nth vectors 1) [0 0 1]))
      (is (parallel? (nth vectors 2) [0 1 0])))))

(deftest jacobi-recovers-known-eigenpair
  (testing "a symmetric matrix with an off-diagonal is diagonalized"
    ;; [[2 1 0][1 2 0][0 0 5]] → eigenvalues 5,3,1; the 3/1 pair rotates x,y by 45°
    (let [{:keys [values vectors]} (sym/jacobi-eigen-3x3 [[2 1 0] [1 2 0] [0 0 5]])]
      (is (h/vec-approx= [5.0 3.0 1.0] values 1e-6))
      (is (parallel? (nth vectors 0) [0 0 1]))
      (is (parallel? (nth vectors 1) [1 1 0]))
      (is (parallel? (nth vectors 2) [1 -1 0])))))

(deftest jacobi-distinct-diagonal-with-offdiagonal
  (testing "off-diagonal AND distinct diagonal entries (app≠aqq) — the case the
            earlier tests missed: a wrong rotation-angle sign left this un-diagonalized
            and returned tilted-garbage axes on axis-aligned CAD parts"
    ;; [[6 2 0][2 3 0][0 0 1]] → 2×2 block [[6 2][2 3]] has eigenvalues 7,2 (vectors
    ;; (2,1) and (1,-2)); with the z-row → 7,2,1.
    (let [{:keys [values vectors]} (sym/jacobi-eigen-3x3 [[6 2 0] [2 3 0] [0 0 1]])]
      (is (h/vec-approx= [7.0 2.0 1.0] values 1e-6))
      (is (parallel? (nth vectors 0) [2 1 0]))
      (is (parallel? (nth vectors 1) [1 -2 0]))
      (is (parallel? (nth vectors 2) [0 0 1]))))
  (testing "a nearly-diagonal matrix (real covariance shape) barely rotates —
            eigenvalues ≈ the diagonal, axes ≈ axis-aligned"
    (let [{:keys [values vectors]} (sym/jacobi-eigen-3x3 [[50 0.1 0.2] [0.1 60 0.05] [0.2 0.05 13]])]
      (is (h/vec-approx= [60.0 50.0 13.0] values 0.2))
      (is (parallel? (nth vectors 0) [0 1 0]))
      (is (parallel? (nth vectors 1) [1 0 0]))
      (is (parallel? (nth vectors 2) [0 0 1])))))

;; ── area-weighted moments: triangulation invariance (B7) ────

(def ^:private quad-2tri
  "A 2×2 square in the z=0 plane, 2 triangles."
  {:vertices [[0 0 0] [2 0 0] [2 2 0] [0 2 0]]
   :faces [[0 1 2] [0 2 3]]})

(def ^:private quad-lopsided
  "SAME square, but triangulated unevenly (a fan from a corner + an off-centre
   split) so one region has many small triangles — the tessellation-imbalance
   that defeats vertex-mean PCA."
  {:vertices [[0 0 0] [2 0 0] [2 2 0] [0 2 0] [1 0 0] [1.5 0 0] [1.8 0 0]]
   :faces [[0 4 3] [4 5 3] [5 6 3] [6 2 3] [6 1 2]]})

(deftest area-weighted-centroid-is-triangulation-invariant
  (testing "the area-weighted centroid of a planar region is its area centroid,
            independent of HOW it's triangulated — the B7 property that makes the
            PCA tessellation-invariant"
    (let [c1 (:centroid (sym/area-weighted-moments quad-2tri))
          c2 (:centroid (sym/area-weighted-moments quad-lopsided))]
      (is (h/vec-approx= [1.0 1.0 0.0] c1 1e-9))
      (is (h/vec-approx= [1.0 1.0 0.0] c2 1e-9)
          "lopsided triangulation → SAME centroid (area-weighted, not vertex-mean)")
      (is (h/approx= 4.0 (:total-area (sym/area-weighted-moments quad-2tri)) 1e-9)))))

(deftest degenerate-mesh-has-no-moments
  (is (nil? (sym/area-weighted-moments {:vertices [] :faces []}))))

;; ── principal frame on a box ────────────────────────────────

(deftest principal-frame-of-a-box-is-axis-aligned
  (testing "a box's principal axes are its own edges, longest first"
    (let [box (prim/box-mesh 10 20 40)   ; longest along... (depends on box-mesh axis order)
          {:keys [centroid axes values]} (sym/principal-frame box)]
      (is (h/vec-approx= [0.0 0.0 0.0] centroid 1e-6) "box is centered at origin")
      ;; each principal axis is parallel to a coordinate axis
      (is (every? (fn [a] (some #(parallel? a %) [[1 0 0] [0 1 0] [0 0 1]])) axes))
      ;; eigenvalues descending
      (is (>= (nth values 0) (nth values 1)))
      (is (>= (nth values 1) (nth values 2))))))

;; ── candidate planes ────────────────────────────────────────

(deftest candidate-planes-box-three-axis-planes
  (testing "a generic box → 3 candidates, one per principal axis, through center"
    (let [cands (sym/candidate-planes (prim/box-mesh 10 20 40))]
      (is (= 3 (count cands)))
      (is (every? #(h/vec-approx= [0.0 0.0 0.0] (:position %) 1e-6) cands))
      (is (every? #(h/approx= 1.0 (js/Math.sqrt (reduce + (map * (:heading %) (:heading %)))) 1e-9) cands)
          "headings are unit normals"))))

(deftest candidate-planes-square-plate-adds-bbox-fallback
  (testing "a square plate (two equal principal moments → degenerate PCA subspace)
            triggers the bounding-box-axis fallback, so the true symmetry planes
            are among the candidates even though PCA's tied axes are arbitrary"
    (let [cands (sym/candidate-planes (prim/box-mesh 40 40 4))
          headings (map (comp unit :heading) cands)]
      ;; the plate's real mirror planes are normal to X and to Y — both must be
      ;; present among the candidates (the degenerate fallback guarantees it)
      (is (some #(parallel? % [1 0 0]) headings) "a plane normal to X is proposed")
      (is (some #(parallel? % [0 1 0]) headings) "a plane normal to Y is proposed")
      (is (>= (count cands) 3)))))

(deftest candidate-planes-empty-mesh-is-empty
  (is (= [] (sym/candidate-planes {:vertices [] :faces []}))))
