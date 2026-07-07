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
            [ridley.turtle.core :as turtle]
            [ridley.turtle.shape :as shape]))

(def ^:private profile (shape/circle-shape 3 12))

(defn- arg->dsl [a]
  (cond
    (keyword? a) (str a)
    (vector? a) (str "[" (str/join " " (map arg->dsl a)) "]")
    :else (str a)))

(defn- cmd->dsl [{:keys [cmd args] :as c}]
  (cond
    (= cmd :bezier-to)
    (str "(bezier-to " (arg->dsl (:end c)) " " (arg->dsl (:c1 c)) " " (arg->dsl (:c2 c)) " :local)")

    ;; side-trip's body is a sub-path; re-emit its HIGH-LEVEL commands (Fase
    ;; 2a, punto 6), mirroring cmd->code — not path-micro-commands' tessellation,
    ;; which would wall a curve into bare (tv …) calls and lose its :smooth tag
    ;; on re-eval (rec-tv* records those as plain hard corners).
    (= cmd :side-trip)
    (str "(side-trip " (str/join " " (map cmd->dsl (:commands (first args)))) ")")

    :else
    (str "(" (name cmd) (apply str (map #(str " " (arg->dsl %)) args)) ")")))

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

(defn- approx-vec= [a b tol]
  (and (= (count a) (count b)) (every? true? (map #(< (abs (- %1 %2)) tol) a b))))

(defn- mesh= [a b]
  (and (= (:faces a) (:faces b))
       (= (count (:vertices a)) (count (:vertices b)))
       (every? true? (map #(approx-vec= %1 %2 1e-6) (:vertices a) (:vertices b)))))

(deftest pre-confirm-value-directly-consumable
  ;; Closes dev-docs/code-issues.md, "il valore live di un nodo bezier non è
  ;; consumabile direttamente" (dev-docs/brief-recording-highlevel-fase2a.md,
  ;; punti 4/7) — request!'s pre-confirm `live` value is now a real
  ;; {:type :path :commands [{:cmd :bezier-to :c1 :c2 :end :steps}]} that any
  ;; consumer understands via path-micro-commands directly, no source re-eval
  ;; needed. Must produce the SAME mesh as the confirm→splice-source→re-eval
  ;; path (request-and-consume) — the two routes must not silently diverge.
  (testing "extrude(request! seed) == extrude(re-eval'd confirmed source)"
    (let [dsl "(path (bezier-to [30 10 0] [20 0 0] [28 5 0]))"
          seed (:result (h/eval-dsl dsl))
          direct-mesh (ops/pure-extrude-path profile (ep/request! seed))
          confirmed-mesh (request-and-consume dsl)]
      (is (map? direct-mesh) "the pre-confirm value must extrude to a real mesh, not nil")
      (is (mesh= direct-mesh confirmed-mesh)
          "direct consumption of request!'s live value must match the confirm path"))))

(deftest round-trip-preserves-compact-bezier-node
  ;; The recorder's own schema (Fase 2a) means confirm's baked source is ONE
  ;; compact (bezier-to …), not a wall of tessellated micro-th/tv — and
  ;; re-opening that source must recover the same node (c1/c2/end), not drift
  ;; through an extra tessellate/re-fit round-trip.
  (testing "baked commands stay compact and round-trip through re-open"
    (let [dsl "(path (bezier-to [30 10 0] [20 0 0] [28 5 0]))"
          seed (:result (h/eval-dsl dsl))
          baked (:commands (ep/request! seed))
          reopened-seed (:result (h/eval-dsl (commands->dsl baked)))
          reopened-baked (:commands (ep/request! reopened-seed))]
      (is (= 1 (count baked)) "baked commands must be ONE compact bezier-to, not tessellated micro")
      (is (= :bezier-to (:cmd (first baked))))
      (is (= 1 (count reopened-baked)))
      (is (approx-vec= (:end (first baked)) (:end (first reopened-baked)) 1e-6))
      (is (approx-vec= (:c1 (first baked)) (:c1 (first reopened-baked)) 1e-6))
      (is (approx-vec= (:c2 (first baked)) (:c2 (first reopened-baked)) 1e-6)))))

(deftest hand-written-arc-h-seed-becomes-equivalent-bezier-node
  ;; seed->nodes-3d's arc-h/arc-v branch (dev-docs/brief-recording-highlevel-
  ;; fase2a.md, punto 5): a hand-written arc-h (the bake never emits these in
  ;; 3D) must recover as ONE bezier node whose endpoint matches the arc's own
  ;; closed-form geometry — checked here against a high-step tessellation of
  ;; the real production turtle/arc-h, not against the closed form itself.
  (testing "arc-h 20 90 recovers as a bezier node landing where the real arc lands"
    (let [dsl "(path (arc-h 20 90))"
          seed (:result (h/eval-dsl dsl))
          baked (:commands (ep/request! seed))
          bez (first baked)
          identity-pose {:position [0 0 0] :heading [1 0 0] :up [0 0 1]}
          world-end (turtle/local->world identity-pose (:end bez))
          ground-truth (:position (turtle/arc-h identity-pose 20 90 :steps 10000))]
      (is (= 1 (count baked)))
      (is (= :bezier-to (:cmd bez)))
      (is (approx-vec= ground-truth world-end 1e-2)
          (str "expected ~" ground-truth " got " world-end)))))

(deftest side-trip-curve-confirms-compact-and-keeps-smoothness
  ;; dev-docs/brief-recording-highlevel-fase2a.md, punto 6 (coda): cmd->code's
  ;; :side-trip branch used to re-emit path-micro-commands' TESSELLATION of the
  ;; sub-path instead of its high-level (:commands sub) — a side-trip with a
  ;; curve inside confirmed to a wall of bare (tv …) calls, and re-parsing
  ;; those as literal DSL recorded them as PLAIN hard-corner rotations (rec-tv*,
  ;; not rec-tv-smooth*), degrading the curve to facets on re-eval — a real
  ;; geometry defect, not just source-text aesthetics. Now it walks (:commands
  ;; sub) directly, same as the top-level bake, so the curve stays one compact
  ;; (bezier-to …) and keeps its :smooth tag through the round-trip.
  ;;
  ;; cmd->dsl above mirrors this fix (same pattern as request-and-consume for
  ;; the top-level bake) rather than reaching into edit-path's private
  ;; serializer/session internals.
  (testing "confirmed source keeps the side-trip's curve compact"
    (let [dsl "(path (f 20) (side-trip (bezier-to [10 0 5] :steps 4)) (f 5))"
          seed (:result (h/eval-dsl dsl))
          baked (:commands (ep/request! seed))
          confirmed-src (commands->dsl baked)]
      (is (re-find #"\(bezier-to " confirmed-src)
          (str "confirmed source must keep the curve as one compact (bezier-to …): " confirmed-src))
      (is (not (re-find #"\(tv " confirmed-src))
          (str "confirmed source must NOT wall the curve into per-step (tv …) rotations: " confirmed-src))
      (testing "and re-evaluating it preserves the curve's :smooth continuity (not degraded to hard corners)"
        (let [reopened (:result (h/eval-dsl confirmed-src))
              sub-of (fn [commands] (first (keep #(when (= :side-trip (:cmd %)) (first (:args %))) commands)))
              orig-sub (sub-of (:commands seed))
              reopened-sub (sub-of (:commands reopened))]
          (is (= 1 (count (:commands reopened-sub)))
              "the re-opened side-trip's sub-path must still be ONE high-level command")
          (is (= :bezier-to (:cmd (first (:commands reopened-sub)))))
          (is (every? :smooth (filter #(= :tv (:cmd %)) (turtle/path-micro-commands orig-sub)))
              "sanity: the original (never round-tripped) curve is smooth")
          (is (every? :smooth (filter #(= :tv (:cmd %)) (turtle/path-micro-commands reopened-sub)))
              "the round-tripped curve must stay smooth — this is the geometry defect, not source aesthetics"))))))
