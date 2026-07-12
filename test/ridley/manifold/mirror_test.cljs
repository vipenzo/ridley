(ns ridley.manifold.mirror-test
  "Tests for mesh-mirror, mirror?/symmetric-about-plane?, and symmetry-planes.
   WASM-touching assertions skip in node/CI (same idiom as boolean-test); the
   empty-mesh short-circuits run unconditionally. Comparisons use volumes /
   centroids, never = on the whole mesh map (typed-array identity trap, A2)."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.manifold.core :as manifold]
            [ridley.geometry.primitives :as prim]
            [ridley.test-helpers :as h]))

(defn- available? [] (manifold/initialized?))

(defn- centroid [{:keys [vertices]}]
  (let [n (count vertices)]
    (mapv #(/ % n) (reduce (fn [acc v] (mapv + acc v)) [0.0 0.0 0.0] vertices))))

;; ── mesh-mirror ─────────────────────────────────────────────

(deftest mirror-through-origin-plane-preserves-volume
  (if-not (available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "reflecting a box through a plane through the origin keeps a positive
              volume equal to the original (winding-correct, B6)"
      (let [box (prim/box-mesh 10 20 30)
            m (manifold/mirror-by-plane box [1 0 0] [0 0 0])]
        (is (h/approx= 6000.0 (:volume (manifold/get-mesh-status m)) 1e-3))
        (is (h/approx= 6000.0 (h/signed-volume m) 1e-3) "positive volume, not inside-out")))))

(deftest mirror-off-origin-reflects-position
  (if-not (available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "mirror through x=5 sends a box centered at the origin to one centered
              at [10 0 0] (translate∘mirror∘translate, B7)"
      (let [box (prim/box-mesh 10 10 10)
            m (manifold/mirror-by-plane box [1 0 0] [5 0 0])]
        (is (h/vec-approx= [10.0 0.0 0.0] (centroid m) 1e-4))
        (is (h/approx= 1000.0 (:volume (manifold/get-mesh-status m)) 1e-3))))))

(deftest mirror-double-is-identity
  (if-not (available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "mirroring twice through the same plane returns the original (volume +
              centroid within epsilon)"
      (let [box (prim/box-mesh 10 20 30)
            once (manifold/mirror-by-plane box [1 0 0] [7 0 0])
            twice (manifold/mirror-by-plane once [1 0 0] [7 0 0])]
        (is (h/vec-approx= (centroid box) (centroid twice) 1e-4))
        (is (h/approx= (h/signed-volume box) (h/signed-volume twice) 1e-3))))))

;; ── mirror? / symmetric-about-plane? ────────────────────────

(deftest symmetric-empty-mesh-true
  (testing "the empty set is symmetric by definition (runs in node)"
    (is (true? (manifold/symmetric-about-plane? {:type :mesh :vertices [] :faces []} [1 0 0] [0 0 0])))))

(deftest symmetric-box-about-its-mid-plane-true
  (if-not (available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "a box IS symmetric about the plane through its centre (B6 positive)"
      (is (true? (manifold/symmetric-about-plane? (prim/box-mesh 10 20 30) [1 0 0] [0 0 0]))))))

(deftest symmetric-off-centre-plane-false
  (if-not (available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "a plane off the centre splits into unequal halves — the free
              volumetric gate rejects it (B6 negative)"
      (is (false? (manifold/symmetric-about-plane? (prim/box-mesh 10 20 30) [1 0 0] [3 0 0]))))))

;; ── symmetry-planes ─────────────────────────────────────────

(deftest symmetry-planes-empty-mesh-is-empty
  (testing "no symmetry planes for an empty mesh (runs in node)"
    (is (= [] (manifold/symmetry-planes {:type :mesh :vertices [] :faces []})))))

(deftest symmetry-planes-box-finds-its-three
  (if-not (available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "a rectangular box has three mirror planes (through the centre, normal
              to each principal axis), all verified and returned"
      (let [planes (manifold/symmetry-planes (prim/box-mesh 10 20 30))]
        (is (= 3 (count planes)))
        (is (every? #(h/vec-approx= [0.0 0.0 0.0] (:position %) 1e-4) planes)
            "all through the centroid")
        (is (every? #(contains? % :heading) planes))))))

(deftest symmetry-planes-square-plate-degenerate-fallback
  (if-not (available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "a square plate (degenerate PCA subspace) still yields its real mirror
              planes via the bbox fallback — at least the two in-plane axes"
      (let [planes (manifold/symmetry-planes (prim/box-mesh 40 40 4))]
        (is (>= (count planes) 2)
            "the degenerate fallback recovers the true planes PCA's arbitrary axes would miss")))))
