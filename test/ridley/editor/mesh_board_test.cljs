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

;; ── raw mesh-split composite: named error, never obscure ──────
;; brief-split-tree.md Part 3. The emitted call is nude now, so its value IS a
;; composite — passing it straight here is the likely mistake, and the answer
;; has to name the cure.

(deftest mesh-board-raw-composite-names-split-tree
  (reset-accum!)
  (let [composite {:behind (prim/box-mesh 2 2 2) :ahead (prim/box-mesh 3 3 3)}
        e (try (mb/mesh-board composite) (catch :default e e))]
    (is (instance? js/Error e))
    (is (re-find #"split-tree" (.-message e)) "the error names the conversion")
    (is (zero? (scaffold-count)) "nothing was displayed")))

(deftest mesh-board-one-cut-composite-is-not-a-two-piece-tree
  (testing "the sharp edge: at ONE cut both values are meshes, so without the
            guard this displayed happily as a tree named :behind/:ahead — a
            success that teaches the wrong shape and breaks at the second cut"
    (reset-accum!)
    (let [e (try (mb/mesh-board {:behind (prim/box-mesh 2 2 2) :ahead (prim/box-mesh 3 3 3)})
                 (catch :default e e))]
      (is (instance? js/Error e)))))

(deftest mesh-board-nested-composite-does-not-reach-the-scaffolds-as-a-mesh
  (testing "two cuts: the nested :ahead map used to be pushed AS a display mesh"
    (reset-accum!)
    (let [composite {:behind (prim/box-mesh 2 2 2)
                     :ahead {:behind (prim/box-mesh 3 3 3) :ahead (prim/box-mesh 4 4 4)}}
          e (try (mb/mesh-board composite) (catch :default e e))]
      (is (instance? js/Error e))
      (is (re-find #"split-tree" (.-message e)))
      (is (zero? (scaffold-count))))))

(deftest mesh-board-split-tree-of-a-composite-displays-every-leaf
  (testing "the cure the error names actually works, :only included"
    (reset-accum!)
    (let [composite {:behind (prim/box-mesh 2 2 2)
                     :ahead {:behind (prim/box-mesh 3 3 3) :ahead (prim/box-mesh 4 4 4)}}
          t (manifold/split-tree composite)]
      (mb/mesh-board t)
      (is (= 3 (scaffold-count)) "three leaves")
      (reset-accum!)
      (mb/mesh-board t {:only [:piece-2]})
      (is (= 1 (scaffold-count)) ":only works on split-tree's names"))))

(deftest mesh-board-composite-as-candidate-is-not-a-silent-noop
  (testing "a composite in the second slot is a map, so the show! branch would
            read it as an opts map and quietly display the first arg alone"
    (reset-accum!)
    (let [e (try (mb/mesh-board (prim/box-mesh 2 2 2)
                                {:behind (prim/box-mesh 3 3 3) :ahead (prim/box-mesh 4 4 4)})
                 (catch :default e e))]
      (is (instance? js/Error e))
      (is (re-find #"split-tree" (.-message e))))))

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

(deftest mesh-board-unknown-views-entry-is-readable-error
  (testing "validated synchronously, before any boolean op — no WASM needed to fail fast"
    (reset-accum!)
    (let [ref (prim/box-mesh 2 2 2)
          cand (prim/box-mesh 2 2 2)
          e (try (mb/mesh-board ref cand {:views [:bogus]}) (catch :default e e))]
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

;; ── views: directional booleans, not the old :mode scaffold push ──────

(deftest mesh-board-compare-default-pushes-no-scaffolds
  (testing "the compare form's visuals moved to inset windows (Parte 2) — no
            in-place ghost by default, unlike the old :overlay/:mode default"
    (reset-accum!)
    (let [ref (prim/box-mesh 4 4 4)
          cand (prim/box-mesh 4 4 4)]
      (mb/mesh-board ref cand)
      (is (zero? (scaffold-count))))))

(deftest mesh-board-ghost-true-pushes-reference-and-candidate-with-distinct-colors
  (testing ":ghost is now opt-in, and the candidate is tinted for its role
            (the inset's grey/blue pair) so the two overlaid wireframes are
            told apart"
    (reset-accum!)
    (let [ref (prim/box-mesh 4 4 4)
          cand (prim/box-mesh 4 4 4)]
      (mb/mesh-board ref cand {:ghost true})
      (is (= 2 (scaffold-count)))
      (let [[a b] (:scaffolds @state/scene-accumulator)]
        (is (nil? (:material a)) "reference stays the wireframe default (grey)")
        (is (= 0x66ccff (get-in b [:material :color])) "candidate tinted by role")))))

(deftest mesh-board-view-mesh-directionality
  (if-not (available?)
    (is true "Skipped: Manifold WASM not available in node")
    (let [ref (prim/box-mesh 10 10 10)
          boss (update (prim/box-mesh 2 2 2) :vertices
                       (fn [vs] (mapv (fn [[x y z]] [(+ x 6) y z]) vs)))
          cand (manifold/union ref boss)
          vboss (:volume (manifold/get-mesh-status boss))
          missing (#'mb/view-mesh :missing ref cand)
          excess (#'mb/view-mesh :excess ref cand)
          isect (#'mb/view-mesh :intersection ref cand)]
      (testing ":missing = reference − candidate — the boss already covers all of
                the reference, so nothing is missing"
        (is (h/approx= 0.0 (:volume (manifold/get-mesh-status missing)) 1e-6)))
      (testing ":excess = candidate − reference — exactly the boss volume overshoots"
        (is (h/approx= vboss (:volume (manifold/get-mesh-status excess)) 1e-2)))
      (testing ":intersection matches manifold/intersection directly"
        (is (h/approx= (:volume (manifold/get-mesh-status (manifold/intersection ref cand)))
                       (:volume (manifold/get-mesh-status isect)) 1e-6))))))

;; ── comparison-inset bookkeeping (active-views) — the window lifecycle
;; itself needs a real DOM/canvas to observe, but the per-label tracking that
;; decides what to mount/unmount is plain data, testable headless ──────────

(deftest mesh-board-compare-tracks-requested-views-per-label
  (reset-accum!)
  (let [ref (prim/box-mesh 4 4 4)
        cand (prim/box-mesh 4 4 4)]
    (mb/mesh-board ref cand {:views [:missing] :label "gancio-sx"})
    (is (= #{:missing} (get @@#'mb/active-views "gancio-sx")))))

(deftest mesh-board-compare-shrinking-views-drops-tracked-entries
  (testing "a re-eval (or a REPL re-invocation) with fewer :views doesn't leave
            the previous ones tracked as active — the no-orphan-canvas guarantee
            at the bookkeeping level"
    (reset-accum!)
    (let [ref (prim/box-mesh 4 4 4)
          cand (prim/box-mesh 4 4 4)]
      (mb/mesh-board ref cand {:views [:intersection :missing :excess] :label "t"})
      (is (= #{:intersection :missing :excess} (get @@#'mb/active-views "t")))
      (mb/mesh-board ref cand {:views [:excess] :label "t"})
      (is (= #{:excess} (get @@#'mb/active-views "t"))))))

(deftest mesh-board-compare-two-labels-track-distinct-groups
  (testing "two coexisting compare! calls, distinct :label, get distinct
            bookkeeping groups — never overwrite each other's views"
    (reset-accum!)
    (let [ref (prim/box-mesh 4 4 4)
          cand (prim/box-mesh 4 4 4)]
      (mb/mesh-board ref cand {:views [:missing] :label "a"})
      (mb/mesh-board ref cand {:views [:excess] :label "b"})
      (is (= #{:missing} (get @@#'mb/active-views "a")))
      (is (= #{:excess} (get @@#'mb/active-views "b"))))))

(deftest mesh-board-reset-compare-views-clears-tracking
  (reset-accum!)
  (let [ref (prim/box-mesh 4 4 4)
        cand (prim/box-mesh 4 4 4)]
    (mb/mesh-board ref cand {:label "t"})
    (mb/reset-compare-views!)
    (is (empty? @@#'mb/active-views))))

;; ── Parte 4.1 — empty result is an explicit label, never stale content ────

(deftest mesh-board-view-label-empty-result-is-explicit
  (testing "nil (couldn't compute) and an empty-vertex mesh (genuinely no
            overlap) both format as 'vuoto' — the caller never has a reason
            to skip updating the inset and leave the last non-empty content"
    (is (= "intersection: vuoto" (#'mb/view-label :intersection nil)))
    (is (= "missing: vuoto" (#'mb/view-label :missing {:vertices [] :faces []})))))

(deftest mesh-board-view-label-formats-volume
  (if-not (available?)
    (is true "Skipped: Manifold WASM not available in node")
    (is (re-find #"^excess: \d+\.\d mm³$" (#'mb/view-label :excess (prim/box-mesh 4 4 4))))))

(deftest mesh-board-compare-non-overlapping-does-not-throw
  (testing "the real-use bug: pieces that stop touching must not crash or
            silently skip — active-views bookkeeping still records the full
            requested set even though every view's boolean result is empty"
    (if-not (available?)
      (is true "Skipped: Manifold WASM not available in node")
      (let [ref (prim/box-mesh 4 4 4)
            cand (update (prim/box-mesh 4 4 4) :vertices
                         (fn [vs] (mapv (fn [[x y z]] [(+ x 40) y z]) vs)))]
        (reset-accum!)
        (is (identical? ref (mb/mesh-board ref cand {:label "far-apart"})))
        (is (= #{:intersection :missing :excess} (get @@#'mb/active-views "far-apart")))))))

;; ── fidelity print goes through the app's print buffer now (Parte 1) ──────

(deftest mesh-board-label-appears-in-printed-message
  (if-not (available?)
    (is true "Skipped: Manifold WASM not available in node")
    (let [ref (prim/box-mesh 4 4 4)
          cand (prim/box-mesh 4 4 4)]
      (reset-accum!)
      (state/reset-print-buffer!)
      (mb/mesh-board ref cand {:label "gancio-sx"})
      (let [printed (state/get-print-output)]
        (is (re-find #"gancio-sx" printed))
        (is (re-find #"fedeltà" printed))))))
