(ns ridley.manifold.mesh-split-test
  "Tests for split-by-plane/mesh-split (primitive + composite), split-parts,
   and convex?.

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
            [ridley.editor.implicit :as impl]
            [ridley.editor.state :as state]
            [ridley.test-helpers :as h]))

(defn- set-turtle-pose!
  "Point the global turtle atom (that implicit-mesh-split reads) at `state`.
   Same pattern used by loft_plus_test.cljs for testing impl/*-impl directly."
  [turtle-state]
  (reset! @state/turtle-state-var turtle-state))

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

;; ── Composite mesh-split (path + marks) ─────────────────────
;; implicit-mesh-split reads the global turtle atom — set-turtle-pose! points
;; it at a scratch state, same pattern loft_plus_test.cljs uses for impl/*-impl.

(defn- mesh-without-raw-arrays
  "manifold->mesh stamps ::raw-arrays (typed-array cache for zero-copy
   rendering) onto every mesh it builds. Two independent WASM round-trips
   produce numerically-identical geometry but DIFFERENT Float32Array/
   Uint32Array object identities, so a bare `=` on two meshes that should be
   structurally equal spuriously fails on that key alone (typed arrays have
   no cljs value semantics). Strip it before comparing."
  [mesh]
  (dissoc mesh ::manifold/raw-arrays))

(deftest mesh-split-composite-single-mark-identical-to-primitive
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "(mesh-split m (path (mark :x))) == (mesh-split m) at the same pose"
      (set-turtle-pose! (t/make-turtle))
      (let [block (last (:meshes (-> (t/make-turtle)
                                     (t/extrude-from-path (shape/rect-shape 20 20)
                                                          (t/make-path [{:cmd :f :args [30]}])))))
            primitive (impl/implicit-mesh-split block)
            composite (impl/implicit-mesh-split block (t/make-path [{:cmd :mark :args [:only]}]))]
        (is (= (mesh-without-raw-arrays (:behind primitive))
               (mesh-without-raw-arrays (:behind composite))))
        (is (= (mesh-without-raw-arrays (:ahead primitive))
               (mesh-without-raw-arrays (:ahead composite))))))))

(deftest mesh-split-composite-default-marks-in-path-order
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "no marks-vector -> cuts at every mark, in appearance order"
      (set-turtle-pose! (t/make-turtle))
      (let [block (last (:meshes (-> (t/make-turtle)
                                     (t/extrude-from-path (shape/rect-shape 20 20)
                                                          (t/make-path [{:cmd :f :args [30]}])))))
            p (t/make-path [{:cmd :f :args [10]} {:cmd :mark :args [:cut-1]}
                            {:cmd :f :args [10]} {:cmd :mark :args [:cut-2]}])
            result (impl/implicit-mesh-split block p)
            piece-1 (:behind result)
            piece-2 (get-in result [:ahead :behind])
            remaining (get-in result [:ahead :ahead])]
        (is (h/approx= 4000.0 (h/signed-volume piece-1) 1e-3))
        (is (h/approx= 4000.0 (h/signed-volume piece-2) 1e-3))
        (is (h/approx= 4000.0 (h/signed-volume remaining) 1e-3))))))

(deftest mesh-split-composite-explicit-marks-vector-selection-and-order
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "marks-vector selects a subset, in the vector's own order"
      (set-turtle-pose! (t/make-turtle))
      (let [block (last (:meshes (-> (t/make-turtle)
                                     (t/extrude-from-path (shape/rect-shape 20 20)
                                                          (t/make-path [{:cmd :f :args [30]}])))))
            p (t/make-path [{:cmd :f :args [5]} {:cmd :mark :args [:cut-a]}
                            {:cmd :f :args [5]} {:cmd :mark :args [:cut-b]}
                            {:cmd :f :args [5]} {:cmd :mark :args [:cut-c]}])
            ;; select only cut-c (f=15) and cut-a (f=5), in THAT order — the
            ;; first cut is at x=15, not x=5, and the result must reflect it
            result (impl/implicit-mesh-split block p [:cut-c :cut-a])]
        (is (h/approx= 6000.0 (h/signed-volume (:behind result)) 1e-3)
            "first cut (cut-c, x=15) detaches the 0..15 slab (vol 6000)")
        (is (contains? (:ahead result) :behind) "still a composite node, not a leaf")))))

(deftest mesh-split-composite-missing-mark-throws
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "a marks-vector entry absent from the path throws, naming it"
      (set-turtle-pose! (t/make-turtle))
      (let [block (prim/box-mesh 20 20 20)
            p (t/make-path [{:cmd :f :args [10]} {:cmd :mark :args [:cut-1]}])]
        (try
          (impl/implicit-mesh-split block p [:cut-1 :bogus])
          (is false "should have thrown")
          (catch :default e
            (is (re-find #"bogus" (.-message e)))))))))

(deftest mesh-split-composite-path-without-marks-throws
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "(mesh-split m path) with zero marks in path throws"
      (set-turtle-pose! (t/make-turtle))
      (let [block (prim/box-mesh 20 20 20)
            p (t/make-path [{:cmd :f :args [10]}])]
        (try
          (impl/implicit-mesh-split block p)
          (is false "should have thrown")
          (catch :default e
            (is (re-find #"no marks" (.-message e)))))))))

(deftest mesh-split-composite-empty-cut-mid-chain-preserves-position
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing ":behind empty at a mid-chain mark that misses the remaining —
              chain continues, positional correspondence preserved"
      (set-turtle-pose! (t/make-turtle))
      (let [block (last (:meshes (-> (t/make-turtle)
                                     (t/extrude-from-path (shape/rect-shape 20 20)
                                                          (t/make-path [{:cmd :f :args [30]}])))))
            ;; cut-1 at x=10 (normal cut); cut-2 backs up to x=-10 — entirely
            ;; before the block, so :behind at cut-2 is empty, :ahead is the
            ;; whole cut-1 remainder unchanged
            p (t/make-path [{:cmd :f :args [10]} {:cmd :mark :args [:cut-1]}
                            {:cmd :f :args [-20]} {:cmd :mark :args [:cut-2]}])
            result (impl/implicit-mesh-split block p)]
        (is (pos? (count (:faces (:behind result)))) "cut-1 detaches a real piece")
        (is (empty? (:faces (get-in result [:ahead :behind])))
            "cut-2's :behind is empty (its plane misses the remaining)")
        (is (h/approx= 8000.0 (h/signed-volume (get-in result [:ahead :ahead])) 1e-3)
            "chain continues: final remaining is untouched by the no-op cut")))))

(deftest mesh-split-composite-plus-shape-three-convex-pieces
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "the brief's own worked example: a plus/cross cut by two parallel
              marks -> three convex pieces, volumes summing to the original"
      (set-turtle-pose! (t/make-turtle))
      (let [bar-h (prim/box-mesh 90 30 10)   ; long along X
            bar-v (prim/box-mesh 30 90 10)   ; long along Y — same center
            cross (manifold/union bar-h bar-v)
            p (t/make-path [{:cmd :f :args [-15]} {:cmd :mark :args [:cut-1]}
                            {:cmd :f :args [30]} {:cmd :mark :args [:cut-2]}])
            result (impl/implicit-mesh-split cross p)
            parts (manifold/split-parts result)]
        (is (= 3 (count parts)))
        (is (every? manifold/convex? parts))
        (is (h/approx= 45000.0 (apply + (map #(:volume (manifold/get-mesh-status %)) parts))
                       1e-1))))))

;; ── mesh-split composite: branching map spec (brief-split-tree.md Part 1) ──

(defn- block-20x20x30
  "A box 20x20 cross-section, 20 units of x per stretch — extruded from a
   rect, so x runs [0,30] and cross-section area is 400 (matches the
   default-marks-in-path-order fixture)."
  []
  (last (:meshes (-> (t/make-turtle)
                     (t/extrude-from-path (shape/rect-shape 20 20)
                                          (t/make-path [{:cmd :f :args [30]}]))))))

(deftest mesh-split-composite-map-spec-branches-a-detached-piece
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "a map spec's :cut-1 branches the piece DETACHED at that mark —
              its own sub-path resolves from :cut-1's own cut-pose (x=10),
              not the live turtle (still at the origin) — Part 1 Q4"
      (set-turtle-pose! (t/make-turtle))
      (let [block (block-20x20x30)
            p (t/make-path [{:cmd :f :args [10]} {:cmd :mark :args [:cut-1]}
                            {:cmd :f :args [10]} {:cmd :mark :args [:cut-2]}])
            sub (t/make-path [{:cmd :f :args [-5]} {:cmd :mark :args [:sub-1]}])
            result (impl/implicit-mesh-split block p {:cut-1 sub :cut-2 nil})
            parts (manifold/split-parts result)
            vols (mapv #(:volume (manifold/get-mesh-status %)) parts)]
        (is (manifold/split-composite? (:behind result))
            "cut-1's :behind is itself a node, not a leaf mesh")
        (is (= 4 (count parts)) "two marks, one branched once more -> 4 leaves")
        ;; DFS behind-first: [0,5] [5,10] [10,20] [20,30]
        (is (h/approx= 2000.0 (nth vols 0) 1e-3))
        (is (h/approx= 2000.0 (nth vols 1) 1e-3))
        (is (h/approx= 4000.0 (nth vols 2) 1e-3))
        (is (h/approx= 4000.0 (nth vols 3) 1e-3))
        (is (h/approx= 12000.0 (apply + vols) 1e-1) "volumes sum to the original")))))

(deftest mesh-split-composite-map-spec-full-recursive-form-nests-twice
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "[path spec] branches a branch's own detached piece again —
              three levels deep, still one call"
      (set-turtle-pose! (t/make-turtle))
      (let [block (block-20x20x30)
            p (t/make-path [{:cmd :f :args [10]} {:cmd :mark :args [:cut-1]}])
            sub1 (t/make-path [{:cmd :f :args [-3]} {:cmd :mark :args [:sub-1]}])
            sub2 (t/make-path [{:cmd :f :args [-2]} {:cmd :mark :args [:sub-2]}])
            result (impl/implicit-mesh-split block p {:cut-1 [sub1 {:sub-1 sub2}]})
            parts (manifold/split-parts result)
            vols (mapv #(:volume (manifold/get-mesh-status %)) parts)]
        ;; cut-1 @ x=10 -> behind=[0,10] (branches), ahead=[10,30] (leaf, vol 8000)
        ;; sub-1 @ x=7 (10-3) within [0,10] -> behind=[0,7] (branches), ahead=[7,10] (vol1200)
        ;; sub-2 @ x=5 (7-2) within [0,7] -> behind=[0,5] (vol2000), ahead=[5,7] (vol800)
        (is (= 4 (count parts)))
        (is (h/approx= 2000.0 (nth vols 0) 1e-3))
        (is (h/approx= 800.0  (nth vols 1) 1e-3))
        (is (h/approx= 1200.0 (nth vols 2) 1e-3))
        (is (h/approx= 8000.0 (nth vols 3) 1e-3))
        (is (h/approx= 12000.0 (apply + vols) 1e-1))))))

(deftest mesh-split-composite-map-spec-unknown-mark-throws
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "a map spec key absent from the path throws, naming it — same
              contract as the vector form's missing-mark error"
      (set-turtle-pose! (t/make-turtle))
      (let [block (block-20x20x30)
            p (t/make-path [{:cmd :f :args [10]} {:cmd :mark :args [:cut-1]}])]
        (try
          (impl/implicit-mesh-split block p {:bogus nil})
          (is false "should have thrown")
          (catch :default e
            (is (re-find #"bogus" (.-message e)))))))))

(deftest split-tree-names-a-branching-composite-in-dfs-order
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "split-tree works unmodified on a branching composite — it
              already recurses generically via split-parts"
      (set-turtle-pose! (t/make-turtle))
      (let [block (block-20x20x30)
            p (t/make-path [{:cmd :f :args [10]} {:cmd :mark :args [:cut-1]}
                            {:cmd :f :args [10]} {:cmd :mark :args [:cut-2]}])
            sub (t/make-path [{:cmd :f :args [-5]} {:cmd :mark :args [:sub-1]}])
            result (impl/implicit-mesh-split block p {:cut-1 sub :cut-2 nil})
            named (manifold/split-tree result)]
        (is (= [:piece-1 :piece-2 :piece-3 :piece-4] (sort (keys named))))
        (is (h/approx= 2000.0 (:volume (manifold/get-mesh-status (:piece-1 named))) 1e-3))
        (is (h/approx= 4000.0 (:volume (manifold/get-mesh-status (:piece-4 named))) 1e-3))))))

;; ── split-parts ──────────────────────────────────────────────

(deftest split-parts-chain-yields-n-plus-1-leaves-in-order
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "N marks -> N+1 leaves, in chain order"
      (set-turtle-pose! (t/make-turtle))
      (let [block (last (:meshes (-> (t/make-turtle)
                                     (t/extrude-from-path (shape/rect-shape 20 20)
                                                          (t/make-path [{:cmd :f :args [30]}])))))
            p (t/make-path [{:cmd :f :args [10]} {:cmd :mark :args [:cut-1]}
                            {:cmd :f :args [10]} {:cmd :mark :args [:cut-2]}])
            result (impl/implicit-mesh-split block p)
            parts (manifold/split-parts result)]
        (is (= 3 (count parts)))
        (is (every? #(h/approx= 4000.0 (h/signed-volume %) 1e-3) parts))))))

(deftest split-parts-bare-mesh-returns-singleton-vector
  (let [mesh (prim/box-mesh 10 10 10)]
    (is (= [mesh] (manifold/split-parts mesh)))))

(deftest split-parts-hand-built-tree-depth-first-behind-first
  (testing "a :behind that is itself a node is walked depth-first, behind before ahead
            (future-proofing: the composite is a chain today, but the shape is a tree)"
    (let [leaf-a {:type :mesh :vertices [] :faces [] :marker :a}
          leaf-b {:type :mesh :vertices [] :faces [] :marker :b}
          leaf-c {:type :mesh :vertices [] :faces [] :marker :c}
          leaf-d {:type :mesh :vertices [] :faces [] :marker :d}
          ;; :behind is itself a {:behind :ahead} node, not a leaf
          tree {:behind {:behind leaf-a :ahead leaf-b}
                :ahead  {:behind leaf-c :ahead leaf-d}}]
      (is (= [leaf-a leaf-b leaf-c leaf-d] (manifold/split-parts tree))))))

;; ── split-tree (brief-split-tree.md Part 2) ─────────────────────
;; The bridge from the nude emitted call to every consumer that wants names.
;; Pure structure — hand-built nodes, no WASM.

(defn- leaf [k] {:type :mesh :vertices [] :faces [] :marker k})

(deftest split-tree-names-pieces-in-cut-order
  (testing "a 2-cut chain → :piece-1 :piece-2 :piece-3, the piece detached at
            :cut-1 first and the final remaining last — the same names and the
            same order edit-mesh-split's own scene labels show"
    (let [a (leaf :a) b (leaf :b) c (leaf :c)
          composite {:behind a :ahead {:behind b :ahead c}}]
      (is (= {:piece-1 a :piece-2 b :piece-3 c} (manifold/split-tree composite)))
      (is (= [:piece-1 :piece-2 :piece-3] (sort (keys (manifold/split-tree composite))))))))

(deftest split-tree-single-cut-is-two-pieces
  (let [a (leaf :a) b (leaf :b)]
    (is (= {:piece-1 a :piece-2 b} (manifold/split-tree {:behind a :ahead b})))))

(deftest split-tree-three-cuts-is-four-pieces
  (let [a (leaf :a) b (leaf :b) c (leaf :c) d (leaf :d)]
    (is (= {:piece-1 a :piece-2 b :piece-3 c :piece-4 d}
           (manifold/split-tree {:behind a :ahead {:behind b :ahead {:behind c :ahead d}}})))))

(deftest split-tree-bare-mesh-is-a-one-piece-tree
  (testing "totality, exactly like split-parts' singleton — an uncut mesh is one piece"
    (let [m (prim/box-mesh 10 10 10)]
      (is (= {:piece-1 m} (manifold/split-tree m))))))

(deftest split-tree-values-are-the-very-same-meshes
  (testing "a pure renaming — no copy, no transform (mesh-board's pass-through
            and attach's group replay both depend on the leaves being untouched)"
    (let [a (leaf :a) b (leaf :b)
          t (manifold/split-tree {:behind a :ahead b})]
      (is (identical? a (:piece-1 t)))
      (is (identical? b (:piece-2 t))))))

;; ── split-composite? (the guard's predicate) ────────────────────

(deftest split-composite-recognizes-nodes-and-nothing-else
  (is (true? (manifold/split-composite? {:behind (leaf :a) :ahead (leaf :b)}))
      "one cut — the case that would otherwise pass for a 2-piece named tree")
  (is (true? (manifold/split-composite? {:behind (leaf :a) :ahead {:behind (leaf :b) :ahead (leaf :c)}}))
      "two cuts — the case that breaks consumers obscurely")
  (is (false? (manifold/split-composite? {:piece-1 (leaf :a) :piece-2 (leaf :b)}))
      "a named tree is not a composite")
  (is (false? (manifold/split-composite? (prim/box-mesh 2 2 2))) "a mesh is not a composite")
  (is (false? (manifold/split-composite? [(leaf :a)])) "a vector is not a composite")
  (is (false? (manifold/split-composite? nil))))

;; ── mesh-components (decompose wrapper) ─────────────────────────
;; A two-box mesh built by concat-meshes (linear merge, no boolean) is the
;; accertamento's minimal control case (A1): a single Ridley-valid mesh that is
;; topologically two components, hull-ratio ≈ 0.5. Its separation is topological
;; — no plane, no mark. concat-meshes is pure CLJS, so the fixture builds in
;; node; the decompose() itself needs Manifold WASM (skipped in node/CI).

(def ^:private two-boxes
  "cube-2 (centroid [0 0 0]) merged with a copy shifted +10 in X (centroid
   [10 0 0]) into ONE mesh — two disjoint connected components, vol 8 each."
  (manifold/concat-meshes cube-2 (shift-x cube-2 10)))

(deftest mesh-components-empty-mesh-yields-empty-vector
  (testing "empty mesh (no faces) → [] without touching Manifold (pure/node)"
    (is (= [] (manifold/mesh-components {:type :mesh :vertices [] :faces []})))))

(deftest mesh-centroid-is-vertex-mean-order-independent
  (testing "vertex-mean centroid, invariant to vertex order (pure/node)"
    (let [c0 (#'manifold/mesh-centroid cube-2)
          c10 (#'manifold/mesh-centroid (shift-x cube-2 10))
          ;; same vertex SET, permuted order → identical centroid
          shuffled (update cube-2 :vertices (comp vec reverse))
          cshuf (#'manifold/mesh-centroid shuffled)]
      (is (h/vec-approx= [0.0 0.0 0.0] c0 1e-9))
      (is (h/vec-approx= [10.0 0.0 0.0] c10 1e-9))
      (is (h/vec-approx= c0 cshuf 1e-12) "permuting vertices does not move the centroid")
      (is (= [0.0 0.0 0.0] (#'manifold/mesh-centroid {:vertices []})) "empty → origin"))))

(deftest order-components-contract-volume-desc-then-centroid-lex
  (testing "order-components: decreasing volume, then lexicographic centroid
            (x,y,z) tie-break — pure, WASM-independent contract lock"
    (let [big     {:mesh :big     :volume 10.0 :centroid [5.0 5.0 5.0]}
          small-a {:mesh :small-a :volume 2.0  :centroid [0.0 1.0 0.0]}
          small-b {:mesh :small-b :volume 2.0  :centroid [0.0 0.0 9.0]}  ; ties vol with small-a, lower y
          small-c {:mesh :small-c :volume 2.0  :centroid [-3.0 0.0 0.0]} ; ties vol, lowest x → first among the 2.0s
          entries [small-a big small-b small-c]]
      (is (= [:big :small-c :small-b :small-a]
             (#'manifold/order-components entries))
          "biggest first; the three vol-2 pieces ordered by centroid x then y then z"))))

(deftest mesh-components-two-boxes-two-ordered-components
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "A1 control case: a two-box single mesh decomposes into 2 valid
              components, ordered deterministically (equal volume → centroid x)"
      (let [comps (manifold/mesh-components two-boxes)]
        (is (= 2 (count comps)))
        (is (every? #(h/approx= 8.0 (:volume (manifold/get-mesh-status %)) 1e-6) comps)
            "both components are valid vol-8 boxes")
        (is (h/vec-approx= [0.0 0.0 0.0] (#'manifold/mesh-centroid (first comps)) 1e-6)
            "centroid-0 sorts first (lower x)")
        (is (h/vec-approx= [10.0 0.0 0.0] (#'manifold/mesh-centroid (second comps)) 1e-6))))))

(deftest mesh-components-order-is-construction-order-invariant
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "same geometry, permuted construction order → identical vector
              (measured on volumes + centroids, per the A2 comparison trap:
              never = on the whole map)"
      (let [a (manifold/mesh-components (manifold/concat-meshes cube-2 (shift-x cube-2 10)))
            b (manifold/mesh-components (manifold/concat-meshes (shift-x cube-2 10) cube-2))
            key-of (fn [comps]
                     (mapv (fn [m] [(js/Math.round (h/signed-volume m))
                                    (mapv #(js/Math.round %) (#'manifold/mesh-centroid m))])
                           comps))]
        (is (= (key-of a) (key-of b))
            "the DSL ordering absorbs decompose()'s implementation-defined order")))))

(deftest mesh-components-single-component-yields-singleton
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "a one-component mesh → [mesh] (the whole thing)"
      (let [comps (manifold/mesh-components cube-2)]
        (is (= 1 (count comps)))
        (is (h/approx= 8.0 (h/signed-volume (first comps)) 1e-6))))))

(deftest mesh-components-inherit-carry-meta
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "every component inherits the source's pose/material/anchors"
      (let [src (assoc two-boxes
                       :creation-pose {:position [9 9 9] :heading [0 1 0] :up [0 0 1]}
                       :material {:color 123}
                       :anchors {:foo {:position [1 2 3]}})
            comps (manifold/mesh-components src)]
        (is (= 2 (count comps)))
        (is (every? #(= (:creation-pose src) (:creation-pose %)) comps))
        (is (every? #(= (:material src) (:material %)) comps))
        (is (every? #(= (:anchors src) (:anchors %)) comps))))))

;; ── Per-component finiteness criterion (Parte 2) ────────────────
;; green = every connected component is convex. Welds the criterion to the
;; A1/A4-measured cases: two convex boxes in one mesh (hull-ratio ~0.5, plain
;; convex? = false) are ALREADY finished; a genuinely concave single mesh is not.

(deftest finished-two-convex-components-in-one-mesh-true
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "the U-with-two-convex-prongs case (A4): concave by hull-ratio but
              finished by the per-component criterion"
      (is (false? (manifold/convex? two-boxes))
          "sanity: hull-ratio reads the two-box mesh as concave")
      (is (true? (manifold/finished? two-boxes))
          "but every component is convex → finished")
      (let [rpt (manifold/component-report two-boxes)]
        (is (= 2 (:count rpt)) "badge shows 2 components")
        (is (true? (:finished? rpt)))))))

(deftest finished-genuinely-concave-single-component-false
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "a single genuinely-concave component (torus, A4-style) → not finished"
      (let [torus (torus-mesh 12 4 24 12)
            rpt (manifold/component-report torus)]
        (is (false? (manifold/finished? torus)))
        (is (= 1 (:count rpt)))
        (is (false? (:finished? rpt)))))))

(deftest finished-single-convex-and-empty-true
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "single convex mesh and empty mesh are both finished"
      (is (true? (manifold/finished? (prim/box-mesh 10 10 10))))
      (is (true? (manifold/finished? {:type :mesh :vertices [] :faces []}))
          "empty → finished (every? over [] is true)"))))

;; ── heal-slivers (dev-docs/brief-step-bias.md Part 2) ────────────────────
;; Hand-built {:ahead :behind} inputs (like two-boxes above) rather than
;; provoking a REAL fp32-noise sliver via an actual split — deterministic and
;; gives full control over which half a sliver lands in and its exact
;; thickness. The real bug reproduction is left to live verification against
;; the real mount.stl (dev-docs/brief-step-bias.md's own Verifica section).

(defn- shift [mesh [dx dy dz]]
  (update mesh :vertices (partial mapv (fn [[x y z]] [(+ x dx) (+ y dy) (+ z dz)]))))

(deftest heal-slivers-moves-a-sliver-to-the-other-half
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "a thin (sub-threshold along the cut normal) component sitting in
              :ahead is removed from :ahead and fused into :behind via union —
              the swap brief-step-bias.md Part 2 describes for the 'stolen
              sheet' bug. sliver touches :behind's boundary (z=0) so union welds
              them into ONE clean component; it is disjoint (gap to z=1) from
              :ahead's own box, so concat-meshes' no-overlap input stays valid."
      (let [normal [0 0 1]
            behind-box (shift (prim/box-mesh 10 10 10) [0 0 -5])       ; z ∈ [-10, 0]
            ahead-box  (shift (prim/box-mesh 10 10 10) [0 0 6])        ; z ∈ [1, 11]
            sliver     (shift (prim/box-mesh 10 10 0.0005) [0 0 0.00025]) ; z ∈ [0, 0.0005]
            ahead (manifold/concat-meshes ahead-box sliver)
            healed (manifold/heal-slivers {:ahead ahead :behind behind-box}
                                          normal ahead-box 0.01)
            ahead-comps (manifold/mesh-components (:ahead healed))
            behind-comps (manifold/mesh-components (:behind healed))]
        (is (= 1 (count ahead-comps)) "sliver removed — ahead is one clean component")
        (is (= 1 (count behind-comps)) "sliver fused in — behind welds to one component")
        (is (h/approx= 1000.0 (:ahead-volume healed) 1e-2) "ahead volume unaffected by the swap")
        (is (h/approx= 1000.05 (:behind-volume healed) 1e-2) "behind gained the sliver's volume")
        (let [{:keys [ahead behind]} (:sliver-report healed)]
          (is (= 2 (:components ahead)) "raw ahead had 2 components (box + sliver)")
          (is (= 1 (:slivers ahead)) "one of them sub-threshold")
          (is (= 1 (:components behind)))
          (is (= 0 (:slivers behind))))))))

(deftest heal-slivers-both-halves-one-pass-no-ping-pong
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "a sliver in EACH half swaps to the other in a single pass — the
              swapped-in sliver (now welded into a large healthy mass) is never
              reclassified, so there's no ping-pong by construction (only the
              ORIGINAL decomposition is ever classified)"
      (let [normal [0 0 1]
            behind-box (shift (prim/box-mesh 10 10 10) [0 0 -5])            ; z ∈ [-10, 0]
            ahead-box  (shift (prim/box-mesh 10 10 10) [0 0 6])             ; z ∈ [1, 11]
            sliver-a   (shift (prim/box-mesh 10 10 0.0005) [0 0 0.00025])   ; in :ahead input, touches behind (z=0)
            sliver-b   (shift (prim/box-mesh 10 10 0.0005) [0 0 0.99975])   ; in :behind input, touches ahead (z=1)
            ahead (manifold/concat-meshes ahead-box sliver-a)
            behind (manifold/concat-meshes behind-box sliver-b)
            healed (manifold/heal-slivers {:ahead ahead :behind behind} normal ahead-box 0.01)]
        (is (= 1 (count (manifold/mesh-components (:ahead healed)))))
        (is (= 1 (count (manifold/mesh-components (:behind healed)))))
        (is (h/approx= 1000.05 (:ahead-volume healed) 1e-2))
        (is (h/approx= 1000.05 (:behind-volume healed) 1e-2))))))

(deftest heal-slivers-thin-but-wide-tab-untouched
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "a component thin in X but with a large extension ALONG THE CUT
              NORMAL survives — the thickness criterion is directional, not a
              volume floor (brief: 'una linguetta staccata apposta' must not
              be eaten). No sub-threshold component anywhere → fast path
              returns the input untouched (plus the sliver-report)."
      (let [normal [0 0 1]
            main (prim/box-mesh 10 10 10)
            tab  (shift (prim/box-mesh 0.2 10 5) [20 0 2.5])   ; thin in X, thickness 5 along Z ≥ threshold
            ahead (manifold/concat-meshes main tab)
            behind (prim/box-mesh 10 10 10)
            healed (manifold/heal-slivers {:ahead ahead :behind behind :ahead-volume :orig-a :behind-volume :orig-b}
                                          normal ahead 0.01)]
        (is (= ahead (:ahead healed)) "untouched — no reassembly performed")
        (is (= behind (:behind healed)))
        (is (= :orig-a (:ahead-volume healed)) "fast path doesn't even recompute volumes")
        (is (= 0 (:slivers (:ahead (:sliver-report healed)))))))))

(deftest heal-slivers-emptied-half-is-legitimate
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "a half whose ONLY component is a sliver, with nothing swapped in
              from the other side, becomes a legitimate empty mesh — the same
              'plane grazes' case split-by-plane already documents"
      (let [normal [0 0 1]
            sliver-only (shift (prim/box-mesh 10 10 0.0005) [0 0 0.00025]) ; z ∈ [0, 0.0005]
            behind (shift (prim/box-mesh 10 10 10) [0 0 -5])               ; z ∈ [-10, 0] — touches, no overlap
            healed (manifold/heal-slivers {:ahead sliver-only :behind behind} normal behind 0.01)]
        (is (empty? (:faces (:ahead healed))) "ahead had only a sliver and got nothing back — empty")
        (is (= 1 (count (manifold/mesh-components (:behind healed)))))
        (is (h/approx= 1000.0005 (:behind-volume healed) 1e-2)
            "the lone sliver still gets fused into behind")))))

(deftest heal-slivers-default-threshold-from-source-mesh
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "omitting `threshold` uses cut-cand/default-sliver-threshold of
              `source-mesh` (the PRE-split mesh) — a big source mesh scales the
              default threshold up with its bbox diagonal"
      (let [normal [0 0 1]
            big-source (prim/box-mesh 20000 20000 20000)     ; diag → big default threshold
            behind-box (shift (prim/box-mesh 10 10 10) [0 0 -5])
            ahead-box  (shift (prim/box-mesh 10 10 10) [0 0 6])
            ;; 0.5mm-thick "sliver" — sub-threshold for the BIG source's default
            ;; (order of metres) but would be healthy at the default 0.01 used above
            sliver (shift (prim/box-mesh 10 10 0.5) [0 0 0.25])
            ahead (manifold/concat-meshes ahead-box sliver)
            healed (manifold/heal-slivers {:ahead ahead :behind behind-box} normal big-source)]
        (is (= 1 (count (manifold/mesh-components (:ahead healed))))
            "0.5mm classified as a sliver against the big source's scaled-up default")))))

;; ── :heal-slivers opts wiring through split-by-plane/split-live ─────────
;; Integration smoke test: confirms the opts plumbing itself, not fp32-noise
;; bug reproduction (a clean synthetic cube has no real ambiguity to heal).

(deftest split-by-plane-heal-slivers-opt-is-a-noop-on-a-clean-split
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "opts threading works end-to-end and a clean split (nothing to
              heal) still reports a sliver-report with zero slivers"
      (let [{:keys [ahead behind sliver-report]}
            (manifold/split-by-plane cube-2 [1 0 0] 0 {:heal-slivers true})]
        (is (h/approx= 4.0 (h/signed-volume ahead) 1e-6))
        (is (h/approx= 4.0 (h/signed-volume behind) 1e-6))
        (is (= 0 (:slivers (:ahead sliver-report))))
        (is (= 0 (:slivers (:behind sliver-report))))))))

(deftest split-by-plane-without-opts-has-no-sliver-report
  (if-not (manifold-available?)
    (is true "Skipped: Manifold WASM not available in node")
    (testing "backward compatibility: no opts arg → heal-slivers never runs, no
              :sliver-report key at all (existing callers unaffected)"
      (let [result (manifold/split-by-plane cube-2 [1 0 0] 0)]
        (is (not (contains? result :sliver-report)))))))
