(ns ridley.manifold.mesh-split-test
  "Tests for split-by-plane (mesh-split's primitive) and convex?.

   NOTE: Actual Manifold WASM tests require a browser environment (the WASM
   module is loaded via a CDN <script type=\"module\"> tag, never in Node).
   WASM-touching assertions skip gracefully in node/CI, same idiom as
   ridley.manifold.boolean-test."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.manifold.core :as manifold]
            [ridley.turtle.core :as t]
            [ridley.turtle.shape :as shape]
            [ridley.geometry.primitives :as prim]
            [ridley.geometry.mesh-utils :as mesh-utils]
            [ridley.test-helpers :as h]))

(defn- manifold-available? []
  (manifold/initialized?))

;; ── Fixtures ─────────────────────────────────────────────────

(def ^:private cube-2
  "A cube of side 2 centered at the origin (volume 8) — the exact fixture
   dev-docs/mesh-split-accertamento.md measured splitByPlane against."
  {:type :mesh
   :vertices [[-1 -1 -1] [1 -1 -1] [1 1 -1] [-1 1 -1]
              [-1 -1  1] [1 -1  1] [1 1  1] [-1 1  1]]
   :faces [[0 2 1] [0 3 2]   ;; bottom
           [4 5 6] [4 6 7]   ;; top
           [0 1 5] [0 5 4]   ;; front
           [2 3 7] [2 7 6]   ;; back
           [1 2 6] [1 6 5]   ;; right
           [0 4 7] [0 7 3]]})

(defn- shift-x
  "Translate a mesh's vertices by dx along X (no DSL/turtle involved)."
  [mesh dx]
  (update mesh :vertices #(mapv (fn [[x y z]] [(+ x dx) y z]) %)))

(defn- torus-mesh
  "Self-contained parametric torus (major radius R, minor radius r) — used
   only as a convex? false-case fixture, decoupled from revolve's internals."
  [R r major-segs minor-segs]
  (let [verts (vec (for [i (range major-segs)
                         j (range minor-segs)
                         :let [theta (* 2 Math/PI (/ i major-segs))
                               phi (* 2 Math/PI (/ j minor-segs))
                               cx (* (+ R (* r (Math/cos phi))) (Math/cos theta))
                               cy (* (+ R (* r (Math/cos phi))) (Math/sin theta))
                               cz (* r (Math/sin phi))]]
                     [cx cy cz]))
        idx (fn [i j] (+ (* (mod i major-segs) minor-segs) (mod j minor-segs)))
        faces (vec (for [i (range major-segs)
                         j (range minor-segs)
                         f [[(idx i j) (idx (inc i) j) (idx (inc i) (inc j))]
                            [(idx i j) (idx (inc i) (inc j)) (idx i (inc j))]]]
                     f))]
    {:type :mesh :vertices verts :faces faces}))

;; ── split-by-plane: worked examples from the brief ──────────

(deftest split-by-plane-centered-cube-halves-4-and-4
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "origin-pose split of a vol-8 centered cube -> 4 + 4"
      (let [{:keys [ahead behind]} (manifold/split-by-plane cube-2 [1 0 0] 0)]
        (is (h/approx= 4.0 (h/signed-volume ahead) 1e-6) "ahead volume (pure re-derivation)")
        (is (h/approx= 4.0 (h/signed-volume behind) 1e-6) "behind volume (pure re-derivation)")
        (is (h/approx= 4.0 (:volume (manifold/get-mesh-status ahead)) 1e-6) "ahead volume (Manifold's own)")
        (is (h/approx= 4.0 (:volume (manifold/get-mesh-status behind)) 1e-6) "behind volume (Manifold's own)")))))

(deftest split-by-plane-offset-cube-2-and-6
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "plane offset 0.5 along the normal -> 2 + 6"
      (let [{:keys [ahead behind]} (manifold/split-by-plane cube-2 [1 0 0] 0.5)]
        (is (h/approx= 2.0 (:volume (manifold/get-mesh-status ahead)) 1e-6))
        (is (h/approx= 6.0 (:volume (manifold/get-mesh-status behind)) 1e-6))))))

(deftest split-by-plane-plane-misses-mesh-no-exception
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "a plane entirely outside the mesh's bounds does not throw"
      (let [{:keys [ahead behind]} (manifold/split-by-plane cube-2 [1 0 0] 100)]
        (is (empty? (:faces ahead)) "far side is empty — legitimate result, not an error")
        (is (= 12 (count (:faces behind))) "near side keeps the whole cube")))))

;; ── Side convention: welds mesh-split to extrude/sdf-half-space ─────
;; extrude leaves the turtle at the path's end pose (position + heading);
;; splitting there — same math implicit-mesh-split uses — must put ALL the
;; material in :behind (the side the turtle came from), matching
;; sdf-half-space's documented default.

(deftest mesh-split-side-convention-matches-extrude
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "material lands entirely in :behind at the turtle's final pose"
      (let [rect (shape/rect-shape 10 10)
            path (t/make-path [{:cmd :f :args [20]}])
            turtle (-> (t/make-turtle) (t/extrude-from-path rect path))
            mesh (last (:meshes turtle))
            heading (:heading turtle)
            position (:position turtle)
            offset (apply + (map * heading position))
            {:keys [ahead behind]} (manifold/split-by-plane mesh heading offset)]
        (is (= [20 0 0] position) "sanity: turtle advanced to the path's end")
        (is (empty? (:faces ahead)) ":ahead is empty — nothing beyond the turtle")
        (is (h/approx= 2000.0 (:volume (manifold/get-mesh-status behind)) 1e-3)
            ":behind holds the full extruded volume (10*10*20)")))))

(deftest mesh-split-halves-are-manifold-clean
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "both halves of a non-trivial CSG result are watertight"
      (let [outer (last (:meshes (-> (t/make-turtle)
                                     (t/extrude-from-path (shape/rect-shape 20 20)
                                                          (t/make-path [{:cmd :f :args [20]}])))))
            hole (last (:meshes (-> (t/make-turtle)
                                    (t/extrude-from-path (shape/circle-shape 5)
                                                         (t/make-path [{:cmd :f :args [30]}])))))
            drilled (manifold/difference outer hole)
            {:keys [ahead behind]} (manifold/split-by-plane drilled [1 0 0] 10)
            ahead-diag (mesh-utils/mesh-diagnose ahead)
            behind-diag (mesh-utils/mesh-diagnose behind)]
        (is (pos? (count (:faces ahead))) "sanity: cut actually bisects the piece")
        (is (pos? (count (:faces behind))) "sanity: cut actually bisects the piece")
        (is (:is-watertight? ahead-diag))
        (is (zero? (:non-manifold-edges ahead-diag)))
        (is (:is-watertight? behind-diag))
        (is (zero? (:non-manifold-edges behind-diag)))))))

;; ── Metadata policy: both halves carry-meta from the source ─────

(deftest mesh-split-halves-inherit-carry-meta-policy
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "both :ahead and :behind inherit creation-pose/material/anchors
              from the source — same carry-meta single-source policy as
              hull/solidify, applied to BOTH halves since split has one input"
      (let [src (assoc cube-2
                       :creation-pose {:position [9 9 9] :heading [0 1 0] :up [0 0 1]}
                       :material {:color 123}
                       :anchors {:foo {:position [1 2 3]}})
            {:keys [ahead behind]} (manifold/split-by-plane src [1 0 0] 0)]
        (is (= (:creation-pose src) (:creation-pose ahead)))
        (is (= (:creation-pose src) (:creation-pose behind)))
        (is (= (:material src) (:material ahead)))
        (is (= (:material src) (:material behind)))
        (is (= (:anchors src) (:anchors ahead)))
        (is (= (:anchors src) (:anchors behind)))))))

;; ── convex? ──────────────────────────────────────────────────
;; The empty-mesh case is the one assertion that runs unconditionally in
;; Node/CI — convex? short-circuits before ever touching Manifold.

(deftest convex-empty-mesh-true
  (testing "the empty set is convex by definition"
    (is (true? (manifold/convex? {:type :mesh :vertices [] :faces []})))))

(deftest convex-box-true
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (is (true? (manifold/convex? (prim/box-mesh 10 10 10))))))

(deftest convex-tessellated-sphere-true
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (is (true? (manifold/convex? (prim/sphere-mesh 10))))))

(deftest convex-hex-prism-true
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (let [hex (last (:meshes (-> (t/make-turtle)
                                 (t/extrude-from-path (shape/ngon-shape 6 10)
                                                      (t/make-path [{:cmd :f :args [20]}])))))]
      (is (true? (manifold/convex? hex))))))

(deftest convex-fine-cylinder-true
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (is (true? (manifold/convex? (prim/cyl-mesh 8 20 64))))))

(deftest convex-frame-false
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "a box with a fully-enclosed box-shaped cavity is not convex"
      (let [outer (last (:meshes (-> (t/make-turtle)
                                     (t/extrude-from-path (shape/rect-shape 20 20)
                                                          (t/make-path [{:cmd :f :args [20]}])))))
            inner (shift-x (last (:meshes (-> (t/make-turtle)
                                              (t/extrude-from-path (shape/rect-shape 10 10)
                                                                   (t/make-path [{:cmd :f :args [10]}])))))
                           5)
            frame (manifold/difference outer inner)]
        (is (false? (manifold/convex? frame)))))))

(deftest convex-l-prism-false
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (let [l-prism (last (:meshes (-> (t/make-turtle)
                                     (t/extrude-from-path
                                      (shape/poly-shape [0 0 20 0 20 10 10 10 10 20 0 20])
                                      (t/make-path [{:cmd :f :args [15]}])))))]
      (is (false? (manifold/convex? l-prism))))))

(deftest convex-torus-false
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (is (false? (manifold/convex? (torus-mesh 12 4 24 12))))))
