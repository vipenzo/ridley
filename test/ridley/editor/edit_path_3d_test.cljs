(ns ridley.editor.edit-path-3d-test
  "The 3D rail editor (edit-path) must never produce a rail that
   validate-rail-start-frame! (extrusion.cljs) rejects — see
   dev-docs/brief-rail-start-tangent.md Part 2. Node 0 is the rail's pinned
   anchor with a FIXED heading [1 0 0]; segment 1 must always leave along it,
   however node 1 got there: an explicit bezier drawn off-axis, a plain straight
   node parked off-axis, or a recovered seed whose source folded a manual
   rotation into its first waypoint. `request!` (called by the `(edit-path …)`
   macro) is the public surface that both opens the interactive session AND
   returns the value the surrounding script proceeds with — so exercising it
   end to end with these seeds and feeding the result into extrude is the
   through-line acceptance test for conform-rail-start-3d /
   reconstrain-handles-3d, without reaching into the editor's private session
   state.

   A bezier segment's `live` value is a COMPACT, source-level (bezier-to …)
   command (meant to be re-evaluated on confirm — see the open item in
   dev-docs/code-issues.md: no consumer understands a raw :bezier-to command
   inside :commands, unrelated to this brief). So `request-and-consume` below
   re-evaluates the baked commands as DSL, exactly mirroring confirm!'s
   splice-source-and-re-eval flow — the real, working path a confirmed edit
   takes — rather than feeding `request!`'s return value to extrude directly."
  (:require [cljs.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ridley.editor.edit-path :as ep]
            [ridley.editor.sci-harness :as h]
            [ridley.editor.operations :as ops]
            [ridley.turtle.shape :as shape]))

(def ^:private profile (shape/circle-shape 3 12))

(defn- arg->dsl [a]
  (cond
    (keyword? a) (str a)
    (vector? a) (str "[" (str/join " " (map arg->dsl a)) "]")
    :else (str a)))

(defn- cmd->dsl [{:keys [cmd args]}]
  (str "(" (name cmd) (apply str (map #(str " " (arg->dsl %)) args)) ")"))

(defn- commands->dsl
  "Re-render a baked :commands vector as (path …) DSL source — the same shape
   confirm! splices back into the editor via nodes->code-3d, for a real
   re-eval (through the `path` macro's recorders) instead of consuming the
   compact bake value directly."
  [commands]
  (str "(path " (str/join " " (map cmd->dsl commands)) ")"))

(defn- request-and-consume
  "Run a 3D seed path through request! (as (edit-path …) would), then re-eval
   the baked commands as DSL (mirrors confirm!) and feed the result into
   extrude — the same check validate-rail-start-frame! makes at consumption
   time. Returns the mesh, or throws if the rail is invalid."
  [dsl]
  (let [seed (:result (h/eval-dsl dsl))
        result (ep/request! seed)
        rebuilt (:result (h/eval-dsl (commands->dsl (:commands result))))]
    (ops/pure-extrude-path profile rebuilt)))

(deftest straight-rail-still-works
  ;; regression: an on-axis rail (no curve involved at all) must keep working.
  (testing "plain on-axis straight rail"
    (is (map? (request-and-consume "(path (f 20))")))))

(deftest seed-with-manual-th-conforms
  ;; A hand-written path that rotates before its first (or only) f folds that
  ;; rotation into node 1's waypoint on recovery (seed->nodes-3d) — node 1 comes
  ;; back as a PLAIN node sitting off the anchor's fixed [1 0 0] heading. Without
  ;; conform-rail-start-3d this rail would be rejected the moment it's opened.
  (testing "seed with a manual th folded into its first waypoint re-bakes without tripping the guard"
    (is (map? (request-and-consume "(path (th 45) (f 20))")))))

(deftest seed-with-off-axis-set-heading-conforms
  ;; Same failure mode via set-heading instead of th: node 1 recovers as a plain
  ;; node whose direction from the anchor is off +X.
  (testing "seed with an off-axis set-heading before the first f re-bakes without tripping the guard"
    (is (map? (request-and-consume "(path (set-heading [0 1 0] [0 0 1]) (f 15))")))))

(deftest seed-with-off-axis-bezier-c1-conforms
  ;; A hand-written bezier whose c1 is NOT along the entry heading (the same
  ;; shape reconstrain-handles-3d must correct after a free drag of node 1's
  ;; handle in the live editor).
  (testing "seed with a bezier head whose c1 is off-axis re-bakes without tripping the guard"
    (is (map? (request-and-consume
               "(path (bezier-to [30 10 0] [10 15 0] [25 5 0]))")))))

(deftest seed-with-already-tangent-bezier-unaffected
  ;; regression: a bezier already drawn tangent (c1 along the entry heading)
  ;; must keep coinciding with stamp — conform-rail-start-3d must not perturb it.
  (testing "already-tangent bezier head is left alone"
    (is (map? (request-and-consume "(path (bezier-to [30 10 0] [20 0 0] [28 5 0]))")))))
