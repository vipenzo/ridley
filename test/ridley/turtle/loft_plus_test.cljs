(ns ridley.turtle.loft-plus-test
  "Verification for loft+ (chainable loft, Roadmap 1.5 / brief-loft-plus).

   Covers:
   - REGRESSION: the rich pure-loft-path* / -shape-fn* / -two-shapes* produce a
     mesh byte-identical to the plain wrappers (the ephemeral :loft-end-shape key
     on the state is invisible to plain-loft callers).
   - end-face threading: the end 2D section is the shape actually stamped on the
     LAST ring (never a re-eval at nominal t=1), and stamping it at the reported
     end pose lands on the loft surface (crack-free seam).
   - transform-> pipeline: loft+ → extrude+ unions into a watertight manifold.
   - two-shape mode: the end section carries the resampled point count.
   - guards: shell/embroid profile and a shape-fn-as-second-arg are rejected."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.editor.sci-harness :as h]
            [ridley.editor.impl :as impl]
            [ridley.editor.operations :as ops]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.shape-fn :as sfn]
            [ridley.turtle.extrusion :as extrusion]
            [ridley.manifold.core :as manifold]
            [ridley.editor.state :as state]
            [ridley.geometry.mesh-utils :as mu]))

;; ── helpers ────────────────────────────────────────────────────────

(defn- val* [code] (:result (h/eval-dsl code)))
(defn- path* [code] (val* code))               ;; a (path …) value

(defn- try* [thunk]
  (try (thunk) nil (catch :default e (.-message e))))

(defn- dist [[ax ay az] [bx by bz]]
  (Math/sqrt (+ (* (- ax bx) (- ax bx))
                (* (- ay by) (- ay by))
                (* (- az bz) (- az bz)))))

(defn- on-mesh?
  "Is `pt` within eps of SOME mesh vertex? (robust to merge-vertices reordering)"
  [pt verts eps]
  (boolean (some #(< (dist pt %) eps) verts)))

(defn- rad
  "Max radial extent of a 2D shape's points (a scale proxy for a centred profile)."
  [s]
  (apply max (map (fn [[x y]] (Math/sqrt (+ (* x x) (* y y)))) (:points s))))

(def ^:private circle (shape/circle-shape 20 24))
(def ^:private taper-fn (fn [s t] (shape/scale-shape s (- 1 (* 0.4 t)))))

;; ── 1. regression: rich mesh == plain mesh ─────────────────────────

(deftest rich-loft-mesh-matches-plain
  (testing "pure-loft-path* mesh is byte-identical to pure-loft-path"
    (let [p (path* "(path (f 30))")
          plain (ops/pure-loft-path circle taper-fn p 32)
          rich  (ops/pure-loft-path* circle taper-fn p 32)]
      (is (= (:vertices plain) (:vertices (:mesh rich))))
      (is (= (:faces plain) (:faces (:mesh rich))))))

  (testing "pure-loft-shape-fn* mesh is byte-identical to pure-loft-shape-fn"
    (let [sf (sfn/tapered (shape/circle-shape 20 24) :to 0.6)
          p  (path* "(path (f 30))")
          plain (ops/pure-loft-shape-fn sf p)
          rich  (ops/pure-loft-shape-fn* sf p)]
      (is (= (:vertices plain) (:vertices (:mesh rich))))
      (is (= (:faces plain) (:faces (:mesh rich))))))

  (testing "pure-loft-two-shapes* mesh is byte-identical to pure-loft-two-shapes"
    (let [s1 (shape/circle-shape 20 24)
          s2 (shape/circle-shape 8 12)
          p  (path* "(path (f 40))")
          plain (ops/pure-loft-two-shapes s1 s2 p 32)
          rich  (ops/pure-loft-two-shapes* s1 s2 p 32)]
      (is (= (:vertices plain) (:vertices (:mesh rich))))
      (is (= (:faces plain) (:faces (:mesh rich)))))))

;; ── 2. end/start shape threading ───────────────────────────────────

(deftest end-shape-is-last-stamped
  (testing "start-shape = transform-fn at 0; end-shape ≈ transform-fn at last-ring t"
    (let [p (path* "(path (f 30))")
          rich (ops/pure-loft-path* circle taper-fn p 32)]
      ;; start section is the profile at t=0 exactly
      (is (= (:points (taper-fn circle 0)) (:points (:start-shape rich))))
      ;; on a straight single segment the last ring reaches t=1
      (is (= (count (:points circle)) (count (:points (:end-shape rich)))))
      (let [end (:points (:end-shape rich))
            expect (:points (taper-fn circle 1))]
        (is (every? true? (map (fn [[ax ay] [bx by]]
                                 (and (< (Math/abs (- ax bx)) 1e-9)
                                      (< (Math/abs (- ay by)) 1e-9)))
                               end expect)))))))

;; ── 3. loft+-impl structure + crack-free seam ──────────────────────

(deftest loft+-structure-and-seam
  (let [p   (path* "(path (f 30))")
        sf  (sfn/tapered (shape/circle-shape 20 24) :to 0.5)
        res (impl/loft+-impl sf p)
        {:keys [mesh start-face end-face]} res]
    (testing "returns a single chaining map with mesh + faces"
      (is (map? res))
      (is (seq (:vertices mesh)))
      (is (map? start-face))
      (is (map? end-face)))
    (testing "start pose at origin, end pose at the rail end"
      (is (< (dist (:pos (:pose start-face)) [0 0 0]) 1e-9))
      ;; rail is (f 30) along +X → end 30 units along heading, heading unchanged
      (is (< (dist (:pos (:pose end-face)) [30 0 0]) 1e-6))
      (is (< (dist (:heading (:pose end-face)) [1 0 0]) 1e-9)))
    (testing "end section stamped at end pose lands on the loft surface (no crack)"
      (let [ep (:pose end-face)
            stamp-state {:position (:pos ep) :heading (:heading ep) :up (:up ep)}
            ring3d (extrusion/stamp-shape stamp-state (:shape end-face))]
        (is (every? #(on-mesh? % (:vertices mesh) 1e-6) ring3d))))))

;; ── 4. transform-> pipeline (loft+ → extrude+) ─────────────────────

(deftest transform-pipeline-seam
  ;; The end-to-end union needs Manifold-WASM, which isn't loaded in node/CI, so
  ;; drive transform-> one step at a time and assert the SEAM directly: extrude+
  ;; started from loft+'s :end-face begins with a first ring that coincides with
  ;; the loft surface. That is exactly what makes the union crack-free — no WASM
  ;; needed to prove it. When Manifold IS available, also assert the union is
  ;; watertight with no non-manifold edges.
  (let [steps [{:op :loft+
                :args [(fn [s t] (shape/scale-shape s (- 1 (* 0.4 t))))
                       (path* "(path (f 30))")]}
               {:op :extrude+ :args [(path* "(path (f 20))")]}]]
    (testing "loft+ mesh and the chained extrude+ mesh share the seam section"
      (let [;; step 1: loft+ from origin — transform->step injects the entering
            ;; shape as loft+'s first (profile) arg, then the step's own args.
            _ (h/eval-dsl "1")                ;; reset turtle to origin (side effect)
            loft-res (apply impl/loft+-impl circle (:args (first steps)))
            end (:end-face loft-res)
            ;; step 2: extrude+ started at the loft's end-face (as transform->step does)
            _ (reset! @state/turtle-state-var
                      (state/init-turtle (:pose end) @@state/turtle-state-var))
            ext-res (impl/extrude+-impl (:shape end) (first (:args (second steps))))
            ext-mesh (:mesh ext-res)
            ;; first ring of the extrude = its start-face shape stamped at the start pose
            sp (:pose (:start-face ext-res))
            ring0 (extrusion/stamp-shape
                   {:position (:pos sp) :heading (:heading sp) :up (:up sp)}
                   (:shape (:start-face ext-res)))]
        (is (seq (:vertices (:mesh loft-res))))
        (is (seq (:vertices ext-mesh)))
        ;; the extrude's first ring lands on the loft surface → no lip/gap
        (is (every? #(on-mesh? % (:vertices (:mesh loft-res)) 1e-6) ring0))))

    (testing "union is watertight when Manifold-WASM is available (skipped in node)"
      (if-not (manifold/initialized?)
        (is true "Skipped: Manifold WASM not available in node")
        (let [m (impl/transform->impl circle steps)
              diag (mu/mesh-diagnose m)]
          (is (seq (:vertices m)))
          (is (zero? (:non-manifold-edges diag)) (str diag))
          (is (:is-watertight? diag) (str diag)))))))

;; ── 5. two-shape mode end section ──────────────────────────────────

(deftest two-shape-end-section
  (testing "end-face carries the resampled point count (max of the two profiles)"
    (let [s1 (shape/circle-shape 20 24)
          s2 (shape/circle-shape 8 10)
          res (impl/loft+-impl s1 s2 (path* "(path (f 40))"))
          n1 (count (:points s1))
          n2 (count (:points s2))]
      (is (= (max n1 n2) (count (:points (:shape (:end-face res)))))))))

;; ── 6. guards ──────────────────────────────────────────────────────

(deftest guards
  (testing "shell profile is rejected in chaining"
    (let [sh (sfn/shell (shape/circle-shape 20 24) :thickness 2)
          msg (try* #(impl/loft+-impl sh (path* "(path (f 30))")))]
      (is (some? msg))
      (is (re-find #"shell|embroid" msg))))

  (testing "a shape-fn as the second (transform) argument is rejected"
    (let [sfn2 (sfn/tapered (shape/circle-shape 10 24) :to 0.5)
          msg (try* #(impl/loft+-impl circle sfn2 (path* "(path (f 30))")))]
      (is (some? msg))
      (is (re-find #"shape-fn" msg)))))

;; ── 7. clamp-divergence trap (falsification of the central constraint) ──

(deftest clamp-divergence-trap
  ;; The brief's central constraint: end-face must be the shape stamped on the
  ;; ACTUAL last ring, never (transform-fn shape 1), because loft-from-path's
  ;; ring loop floors the last segment's effective length at `min-step` while
  ;; total-effective-dist floors at 0.001 — on a corner + SHORT segment the two
  ;; clamps could diverge, leaving the last ring at t<1. A naive t=1 end-face
  ;; would then mismatch the real ring and crack the next segment's seam.
  ;;
  ;; We tried to CONSTRUCT that divergence. Result — falsification failed, and
  ;; the reason is itself the guarantee: every sharp corner + short-segment path
  ;; that would trigger the clamp divergence is first rejected by the shared
  ;; realizability guard (validate-corner-realizability!) — the section folds
  ;; back through the tube. So for any REALIZABLE corner path the last ring sits
  ;; at t=1 and the two clamps agree. The threaded mechanism stays correct by
  ;; construction (it stamps the ACTUAL last section, whatever its t); it merely
  ;; coincides with t=1 wherever the geometry is legal. If the realizability
  ;; guard is ever loosened, THIS is the test that must grow a genuine t<1 case.
  (testing "the would-be-divergent configs (sharp corner + short segment) are rejected upstream"
    (doseq [code ["(path (f 40) (th 90) (f 2))"
                  "(path (f 20) (th 120) (f 3))"
                  "(path (f 25) (th 90) (f 8) (th 90) (f 6))"]]
      (let [msg (try* #(ops/pure-loft-path* circle taper-fn (path* code) 32))]
        (is (some? msg) (str "expected the realizability guard to reject " code))
        (is (re-find #"too sharp|fold" msg)))))

  (testing "on a realizable corner path the threaded end-shape IS the last ring (t=1 here)"
    (let [tf  (fn [s t] (shape/scale-shape s (- 1 (* 0.5 t))))
          res (impl/loft+-impl (shape/circle-shape 20 24) tf
                               (path* "(path (f 40) (th 90) (f 40))"))
          end (:end-face res)]
      ;; the last ring reached t=1: end section == transform-fn at nominal 1
      (is (< (Math/abs (- (rad (:shape end)) (rad (tf (shape/circle-shape 20 24) 1)))) 1e-9))
      ;; and it is the ACTUAL stamped last ring: at the end pose it lands on the mesh
      (let [ep (:pose end)
            ring (extrusion/stamp-shape
                  {:position (:pos ep) :heading (:heading ep) :up (:up ep)}
                  (:shape end))]
        (is (every? #(on-mesh? % (:vertices (:mesh res)) 1e-6) ring))))))
