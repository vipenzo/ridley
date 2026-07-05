(ns ridley.turtle.bezier-canonical-frame-net-test
  "NET for the canonical bezier frame (dev-docs/brief-bezier-canonical-frame.md):
   the up/roll propagated along a cubic bezier must depend ONLY on the four
   control points and the entry up — never on tessellation resolution. Before
   this fix, three code paths (turtle runtime, recorder, editor) each computed
   up differently, all per-tessellation-chord, so the SAME curve produced a
   different exit-up (and, for chained beziers using :local control points, a
   different WORLD POSITION) depending on :steps / global resolution. That is
   the originally observed symptom: an edit-path rail's extrusion end did not
   coincide with the last editor node, and moved when the model resolution
   changed while the editor's nodes stayed put.

   Curve used throughout: p0=[0 0 0], entry heading=[1 0 0], entry up=[0 0 1]
   (turtle defaults), c1=[10 0 0] (tangent to the entry heading, so the rail
   also satisfies validate-rail-start-frame! unmodified), c2=[15 8 5],
   p3=[20 5 12] — NOT coplanar with p0/c1 (scalar triple product ≠ 0), i.e. a
   curve with real torsion, which is exactly the case per-chord laws disagree
   on most."
  (:require [cljs.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ridley.editor.sci-harness :as h]
            [ridley.editor.edit-path :as ep]
            [ridley.editor.operations :as ops]
            [ridley.turtle.core :as turtle]
            [ridley.turtle.shape :as shape]))

;; ── shared curve / helpers ──────────────────────────────────────────
(def ^:private P3 [20 5 12])
(def ^:private C1 [10 0 0])
(def ^:private C2 [15 8 5])

(defn- bez-dsl [steps]
  (str "(bezier-to " P3 " " C1 " " C2 " :steps " steps ")"))

(defn- path-dsl [steps]
  (str "(path " (bez-dsl steps) ")"))

(defn- vdot [a b] (reduce + (map * a b)))
(defn- vangle
  "Angle (radians) between two (assumed unit) vectors."
  [u v] (Math/acos (max -1 (min 1 (vdot u v)))))
(defn- dst [[a b c] [d e f]]
  (Math/sqrt (+ (* (- a d) (- a d)) (* (- b e) (- b e)) (* (- c f) (- c f)))))
(defn- centroid [pts]
  (let [n (count pts)
        s (reduce (fn [[a b c] [x y z]] [(+ a x) (+ b y) (+ c z)]) [0 0 0] pts)]
    (mapv #(/ % n) s)))
(defn- last-ring [mesh n]
  (let [vs (vec (:vertices mesh))] (subvec vs (- (count vs) n))))

;; ── law 1: turtle runtime (direct bezier-to, no recording) ──────────
(defn- turtle-up [steps]
  (:up (:turtle (h/eval-dsl (bez-dsl steps)))))

;; ── law 2: recorder (path macro → :commands → real turtle replay) ───
;; run-path replays the recorded :f/:th/:tv/:tr commands with the SAME
;; turtle/th, turtle/tv, turtle/tr the live recorder used — so this
;; reconstructs exactly the recorder's own final frame, without duplicating
;; any rotation math here.
(defn- recorder-frame [steps]
  (let [p (:result (h/eval-dsl (path-dsl steps)))]
    (turtle/run-path (turtle/make-turtle) p)))
(defn- recorder-up [steps] (:up (recorder-frame steps)))

(deftest turtle-exit-up-resolution-independent
  ;; Must FAIL pre-fix: bezier-walk chains parallel-transport-up per chord, so
  ;; the accumulated result depends on step count for a torsional curve.
  (testing "direct turtle bezier-to: exit up must not depend on :steps"
    (let [u4 (turtle-up 4) u128 (turtle-up 128)
          a (vangle u4 u128)]
      (println ">>> turtle exit-up angle (steps 4 vs 128, rad):" a)
      (is (< a 1e-6) (str "turtle-runtime exit-up must be resolution-independent; angle=" a " rad")))))

(deftest recorder-exit-up-resolution-independent
  ;; Must FAIL pre-fix: rec-bezier-to*'s projected-RMF `new-up` is dead code
  ;; (never fed back into the emitted th/tv); the REAL per-chord law is the
  ;; th-then-tv Euler composition, which leaves a residual roll that grows
  ;; with curvature/torsion and does not cancel across step counts.
  (testing "recorded (path (bezier-to ...)): exit up must not depend on :steps"
    (let [u4 (recorder-up 4) u128 (recorder-up 128)
          a (vangle u4 u128)]
      (println ">>> recorder exit-up angle (steps 4 vs 128, rad):" a)
      (is (< a 1e-6) (str "recorder exit-up must be resolution-independent; angle=" a " rad")))))

(deftest cross-law-agreement-turtle-vs-recorder
  ;; Must FAIL pre-fix: the turtle runtime uses parallel-transport-up per
  ;; chord; the recorder's real (byproduct) law is th-then-tv Euler
  ;; composition. Different laws on the SAME curve at the SAME step count.
  (testing "turtle-runtime and recorder must agree on exit up for the same curve"
    (let [ut (turtle-up 16) ur (recorder-up 16)
          a (vangle ut ur)]
      (println ">>> cross-law angle turtle vs recorder (steps=16, rad):" a)
      (is (< a 1e-6) (str "turtle-runtime and recorder exit-up must agree; angle=" a " rad")))))

(deftest mark-pose-invariant-after-3d-bezier
  ;; Must FAIL pre-fix: a mark placed right after a 3D bezier-to inherits
  ;; whatever roll error the recorder accumulated, which is resolution-
  ;; dependent (same root cause as recorder-exit-up-resolution-independent).
  (testing "a mark after a 3D bezier-to has a resolution-independent pose"
    (let [mark-pose (fn [steps]
                      (let [p (:result (h/eval-dsl
                                        (str "(path " (bez-dsl steps) " (mark :M))")))]
                        (get (turtle/resolve-marks
                              {:position [0 0 0] :heading [1 0 0] :up [0 0 1]} p)
                             :M)))
          m4 (mark-pose 4) m128 (mark-pose 128)
          dp (dst (:position m4) (:position m128))
          au (vangle (:up m4) (:up m128))]
      (println ">>> mark pos-delta:" dp " up-angle (rad):" au)
      (is (< dp 1e-4) (str "mark position must be resolution-independent; delta=" dp))
      (is (< au 1e-6) (str "mark up must be resolution-independent; angle=" au " rad")))))

;; ── law 3: editor (edit-path round trip through request!) ───────────
;; Mirrors test/ridley/editor/edit_path_3d_test.cljs's request-and-consume: a
;; bezier node's `live`/baked value is a compact (bezier-to ...) command meant
;; to be re-evaluated as source (see dev-docs/code-issues.md), so re-render the
;; baked :commands as DSL and re-eval through the `path` macro, exactly as
;; confirm! does, instead of consuming the bake value directly.
(defn- arg->dsl [a]
  (cond
    (keyword? a) (str a)
    (vector? a) (str "[" (str/join " " (map arg->dsl a)) "]")
    :else (str a)))
(defn- cmd->dsl [{:keys [cmd args]}]
  (str "(" (name cmd) (apply str (map #(str " " (arg->dsl %)) args)) ")"))
(defn- commands->dsl [commands]
  (str "(path " (str/join " " (map cmd->dsl commands)) ")"))
(defn- request-and-consume [dsl]
  (let [seed (:result (h/eval-dsl dsl))
        result (ep/request! seed)]
    (:result (h/eval-dsl (commands->dsl (:commands result))))))

(deftest round-trip-editor-mesh-end-matches-last-node
  ;; THE originally observed symptom: a chained 3D bezier rail's baked/re-
  ;; evaluated end position must coincide with the true last-node position.
  ;; Must FAIL pre-fix — the editor bakes each bezier's :local control points
  ;; against its OWN (24-step, projection-RMF) exit frame, but re-eval
  ;; interprets them against the recorder's (resolution-tied, Euler) exit
  ;; frame; for a torsional curve those frames disagree, so the second
  ;; bezier's :local target lands at the wrong world position.
  (testing "chained 3D bezier rail: mesh end coincides with the last node's true position"
    (let [p3b [35 -3 20]
          seed (str "(path " (bez-dsl 16)
                    " (bezier-to " p3b " [24 7 14] [30 -2 18]))")
          profile (shape/circle-shape 3 12)
          rebuilt (request-and-consume seed)
          mesh (ops/pure-extrude-path profile rebuilt)
          end-centroid (centroid (last-ring mesh (count (:points profile))))
          d (dst end-centroid p3b)]
      (println ">>> mesh end vs true last node, dist:" d)
      (is (< d 0.005) (str "mesh end must coincide with the last node position; dist=" d)))))

(deftest brief-repro-editor-roundtrip-matches-direct-eval
  ;; Exact reproduction of dev-docs/brief-bezier-canonical-frame.md's repro: a
  ;; 4-bezier chained rail with :local control points (+ marks), extruded with
  ;; a circle. Before the fix, the editor's bake (24-step projection-RMF) and
  ;; the recorder's re-eval (resolution-tied Euler+dead-RMF) disagreed on each
  ;; segment's exit frame, so a later segment's :local coordinates were
  ;; interpreted in the wrong frame — the round-trip mesh end drifted from
  ;; what direct (non-editor) evaluation of the same source produces.
  (testing "brief's 4-chained-bezier repro: editor round-trip agrees with direct eval"
    (let [seed (str "(path "
                    "(bezier-to [-5.09 -38.75 56.4] [0 0 6.87] [-2.54 -35.2 35.82] :local) "
                    "(bezier-to [-7.07 39.4 -5.43] [0 0 19.92] [-3.82 22.48 16.76] :local) "
                    "(mark :PB) "
                    "(bezier-to [25.2 -34.78 8.01] [0 0 9.07] [23.39 -30.35 -22.96] :local) "
                    "(mark :PA) "
                    "(bezier-to [-57.02 54.85 29.17] [0 0 30.86] [-49.48 44.49 34.88] :local))")
          profile (shape/circle-shape 5 16)
          n (count (:points profile))
          rebuilt (request-and-consume seed)
          roundtrip-mesh (ops/pure-extrude-path profile rebuilt)
          direct-mesh (ops/pure-extrude-path profile (:result (h/eval-dsl seed)))
          roundtrip-end (centroid (last-ring roundtrip-mesh n))
          direct-end (centroid (last-ring direct-mesh n))
          d (dst roundtrip-end direct-end)]
      (println ">>> brief repro: editor-roundtrip vs direct-eval mesh end, dist:" d)
      (is (< d 0.005) (str "editor round-trip mesh end must coincide with direct evaluation; dist=" d)))))
