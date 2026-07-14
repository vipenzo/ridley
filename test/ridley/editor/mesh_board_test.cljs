(ns ridley.editor.mesh-board-test
  "Tests for the mesh-board scaffold display + comparison directive (brief-
   mesh-board.md Part 3). Pure dispatch/accumulator/error-path tests run
   unconditionally; boolean-touching fidelity/mode-mesh tests skip in
   node/CI (same idiom as mirror_test.cljs/boolean_test.cljs)."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.editor.mesh-board :as mb]
            [ridley.editor.state :as state]
            [ridley.manifold.core :as manifold]
            [ridley.geometry.primitives :as prim]
            [ridley.test-helpers :as h]))

(defn- available? [] (manifold/initialized?))

(defn- reset-accum! [] (state/reset-scene-accumulator!))

(defn- scaffold-count [] (count (:scaffolds @state/scene-accumulator)))

;; ── show / pass-through (referential inertia — design doc v2 citizenship) ──

(deftest mesh-board-show-tree-pushes-all-leaves-pass-through
  (reset-accum!)
  (let [t {:piece-1 (prim/box-mesh 2 2 2) :piece-2 (prim/box-mesh 3 3 3)}
        out (mb/mesh-board t)]
    (is (identical? t out) "pass-through — referential inertia")
    (is (= 2 (scaffold-count)))))

(deftest mesh-board-show-single-mesh-totality
  (reset-accum!)
  (let [m (prim/box-mesh 2 2 2)
        out (mb/mesh-board m)]
    (is (identical? m out))
    (is (= 1 (scaffold-count)))))

(deftest mesh-board-show-vector-legacy-emission
  (testing "an older emit body (vector, pre-Part-0) still displays — no names, no :only"
    (reset-accum!)
    (let [v [(prim/box-mesh 2 2 2) (prim/box-mesh 3 3 3)]
          out (mb/mesh-board v)]
      (is (identical? v out))
      (is (= 2 (scaffold-count))))))

;; ── :only subset ─────────────────────────────────────────

(deftest mesh-board-only-subset-by-name
  (reset-accum!)
  (let [t {:piece-1 (prim/box-mesh 2 2 2) :piece-2 (prim/box-mesh 3 3 3) :piece-3 (prim/box-mesh 4 4 4)}]
    (mb/mesh-board t {:only [:piece-2 :piece-3]})
    (is (= 2 (scaffold-count)))))

(deftest mesh-board-only-unknown-name-is-readable-error
  (reset-accum!)
  (let [t {:piece-1 (prim/box-mesh 2 2 2)}
        e (try (mb/mesh-board t {:only [:piece-9]}) (catch :default e e))]
    (is (instance? js/Error e))
    (is (re-find #"piece-9" (.-message e)))))

(deftest mesh-board-only-on-single-mesh-is-readable-error
  (reset-accum!)
  (let [e (try (mb/mesh-board (prim/box-mesh 2 2 2) {:only [:x]}) (catch :default e e))]
    (is (instance? js/Error e))
    (is (re-find #"mesh-board" (.-message e)))))

(deftest mesh-board-only-on-vector-is-readable-error
  (reset-accum!)
  (let [e (try (mb/mesh-board [(prim/box-mesh 2 2 2)] {:only [:x]}) (catch :default e e))]
    (is (instance? js/Error e))))

;; ── unsupported types: readable error, never a silent no-op ────

(deftest mesh-board-unsupported-type-is-readable-error
  (reset-accum!)
  (let [e (try (mb/mesh-board "not-a-tree") (catch :default e e))]
    (is (instance? js/Error e))
    (is (re-find #"mesh-board" (.-message e)))))

;; ── coexistence: multiple mesh-board calls in one eval accumulate together ──

(deftest mesh-board-multiple-calls-coexist-in-one-eval
  (reset-accum!)
  (mb/mesh-board (prim/box-mesh 2 2 2))
  (mb/mesh-board (prim/box-mesh 3 3 3))
  (is (= 2 (scaffold-count)) "both calls' scaffolds accumulate in the same eval"))

;; ── show vs. compare dispatch disambiguation ────────────────

(deftest mesh-board-two-arg-mesh-is-comparison-not-only
  (reset-accum!)
  (let [ref (prim/box-mesh 2 2 2)
        cand (prim/box-mesh 2 2 2)
        out (mb/mesh-board ref cand)]
    (is (identical? ref out) "compare form returns the reference (first arg)")))

(deftest mesh-board-two-arg-map-is-only-not-comparison
  (reset-accum!)
  (let [t {:piece-1 (prim/box-mesh 2 2 2)}
        out (mb/mesh-board t {:only [:piece-1]})]
    (is (identical? t out))))

(deftest mesh-board-unknown-mode-is-readable-error
  (if-not (available?)
    (is true "Skipped: Manifold WASM not available in node")
    (let [ref (prim/box-mesh 2 2 2)
          cand (prim/box-mesh 2 2 2)
          e (try (mb/mesh-board ref cand {:mode :bogus}) (catch :default e e))]
      (is (instance? js/Error e))
      (is (re-find #":bogus" (.-message e))))))

;; ── fidelity (WASM — union/intersection/volume) ─────────────

(deftest mesh-board-compare-identical-copy-is-100-percent-fidelity
  (if-not (available?)
    (is true "Skipped: Manifold WASM not available in node")
    (let [ref (prim/box-mesh 4 4 4)
          cand (prim/box-mesh 4 4 4)
          ratio (#'mb/fidelity-ratio ref cand)]
      (is (h/approx= 0.0 ratio 1e-6) "identical solids → 0 symmetric-difference ratio"))))

(deftest mesh-board-compare-with-added-boss-fidelity-matches-boss-volume
  (if-not (available?)
    (is true "Skipped: Manifold WASM not available in node")
    (let [ref (prim/box-mesh 10 10 10)
          boss (update (prim/box-mesh 2 2 2) :vertices
                       (fn [vs] (mapv (fn [[x y z]] [(+ x 6) y z]) vs)))
          cand (manifold/union ref boss)
          ratio (#'mb/fidelity-ratio ref cand)
          vref (:volume (manifold/get-mesh-status ref))
          vboss (:volume (manifold/get-mesh-status boss))]
      (is (h/approx= (/ vboss vref) ratio 1e-2) "ratio ~= boss-volume / reference-volume"))))

(deftest mesh-board-diff-mode-renders-deviation-geometry
  (if-not (available?)
    (is true "Skipped: Manifold WASM not available in node")
    (let [ref (prim/box-mesh 10 10 10)
          boss (update (prim/box-mesh 2 2 2) :vertices
                       (fn [vs] (mapv (fn [[x y z]] [(+ x 6) y z]) vs)))
          cand (manifold/union ref boss)]
      (reset-accum!)
      (mb/mesh-board ref cand {:mode :diff})
      (is (= 2 (scaffold-count)) "reference + the diff geometry, both scaffold"))))

(deftest mesh-board-label-appears-in-printed-message
  (if-not (available?)
    (is true "Skipped: Manifold WASM not available in node")
    (let [ref (prim/box-mesh 4 4 4)
          cand (prim/box-mesh 4 4 4)
          printed (atom nil)]
      (reset-accum!)
      (with-redefs [println (fn [& args] (reset! printed (apply str args)))]
        (mb/mesh-board ref cand {:label "gancio-sx"}))
      (is (some? @printed))
      (is (re-find #"gancio-sx" @printed)))))
