(ns ridley.editor.edit-path-2d-test
  "Net for dev-docs/brief-recording-highlevel-lettura-2d.md: seed->nodes (2D) /
   project-2d-to-xy were rewritten to read a :2d path's RAW high-level commands
   directly (the same pose-walk pattern as seed->nodes-3d + a plane projection),
   instead of reconstructing nodes from path-micro-commands' tessellation
   (group-curve-runs / :pure / :arc-cap). These tests pin the corpus the brief's
   Verifica section calls for — straights, arcs, beziers, a leading move-to +
   bezier (the case that used to live on a hand-offset hack), a side-trip with a
   curve, marks, and a closed path — so a refactor at semantica invariata has
   something to hold it to, and the one enumerated exception (arc nodes recover
   EXACT r/sweep from args instead of being reconstructed from tessellation) is
   asserted as an improvement, never a regression.

   seed->nodes / project-2d-to-xy stay `defn-`; reached here via the fully-
   qualified symbol (verified over nREPL that ClojureScript allows this from a
   dev/test build), since request!'s public surface only returns the seed itself
   for a :2d path pre-confirm (the caller needing a live, traceable value), not
   the recovered node list — see request!'s own comment in edit_path.cljs."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.editor.edit-path :as ep]
            [ridley.editor.sci-harness :as h]
            [ridley.turtle.core :as turtle]))

(defn- nodes-of
  "Recover 2D editor nodes for a path-2d seed, exactly as request! does."
  ([seed] (nodes-of seed false))
  ([seed closed?]
   (ridley.editor.edit-path/seed->nodes
    (ridley.editor.edit-path/project-2d-to-xy seed) closed?)))

(defn- approx= [a b tol] (< (abs (- a b)) tol))

(defn- approx-vec= [a b tol]
  (and (= (count a) (count b)) (every? true? (map #(approx= %1 %2 tol) a b))))

(deftest straight-segments-and-turns
  (testing "plain f/tv trace: positions and headings land where the flat 2D math predicts"
    (let [seed (:result (h/eval-dsl "(path-2d (move-to [0 0]) (f 20) (tv 90) (f 20))"))
          {:keys [nodes dropped]} (nodes-of seed)]
      (is (empty? dropped))
      (is (= 3 (count nodes)))
      (is (approx-vec= [0 0] (:pos (nth nodes 0)) 1e-9))
      (is (approx-vec= [20 0] (:pos (nth nodes 1)) 1e-9))
      (is (approx-vec= [20 20] (:pos (nth nodes 2)) 1e-6)))))

(deftest arc-node-recovers-exact-endpoint
  ;; The enumerated improvement: an arc-h/arc-v node's endpoint now comes from a
  ;; closed form over the command's own (exact) r/angle args, not from walking a
  ;; tessellated run — so it must match a high-step ground-truth tessellation of
  ;; the real turtle/arc-v tighter than the coarse recording steps would (arcs
  ;; inside path-2d always record as a genuine :arc-v — see project-2d-to-xy).
  (testing "(arc-h 10 90) recovers a node landing where the real arc lands"
    (let [seed (:result (h/eval-dsl "(path-2d (arc-h 10 90))"))
          {:keys [nodes dropped]} (nodes-of seed)
          entry {:position [0 0 0] :heading [6.123233995736766e-17 -1 0] :up [0 0 1]}
          ground-truth (:position (turtle/arc-v entry 10 90 :steps 10000))
          ;; project world [x y z] -> shape (a,b) = (-y, z), same convention as
          ;; ensure-path-2d / seed->nodes' own proj-pt.
          expected [(- (nth ground-truth 1)) (nth ground-truth 2)]]
      (is (empty? dropped))
      (is (= 2 (count nodes)))
      (is (:arc (nth nodes 1)))
      (is (approx-vec= expected (:pos (nth nodes 1)) 1e-2)
          (str "expected ~" expected " got " (:pos (nth nodes 1)))))))

(deftest leading-move-to-plus-bezier-no-offset-hack-needed
  ;; The historically-hacky case (dev-docs brief, "l'hack documentato del
  ;; move-to"): a leading (move-to …) anchors a non-origin start, followed by
  ;; beziers whose :local c1/c2/end are relative to the entry pose. The NEW
  ;; interpreter threads move-to straight into the pose (position starts at the
  ;; anchor's 3D embedding), so decoding needs no manual offset — verified here
  ;; against the OLD implementation's own output (nREPL, byte-identical to fp
  ;; noise) during development; this test pins the concrete numbers so a future
  ;; change can't silently drift.
  (testing "move-to anchor + two beziers (second one turns > 90°, flipping the in-plane normal)"
    (let [body (str "(move-to [0 -0.27]) "
                    "(bezier-to [0 10.37 -23.31] [0 1.64 -9.73] [0 6.94 -19.14] :local) "
                    "(bezier-to [0 9.12 5.25] [0 0 1.53] [0 3.55 15.61] :local)")
          seed (:result (h/eval-dsl (str "(path-2d " body ")")))
          {:keys [nodes dropped]} (nodes-of seed)]
      (is (empty? dropped))
      (is (= 3 (count nodes)))
      (is (approx-vec= [0 -0.27] (:pos (nth nodes 0)) 1e-9))
      (is (approx-vec= [-23.31 10.1] (:pos (nth nodes 1)) 1e-6))
      (is (:bez (nth nodes 1)))
      (is (approx-vec= [-21.571092904713172 20.478492285200254] (:pos (nth nodes 2)) 1e-6))
      (is (:bez (nth nodes 2))))))

(deftest side-trip-with-curve-keeps-native-tag
  ;; A side-trip's body is its own independently-scoped (path …) recording — its
  ;; (tv …) is a genuine 3D pitch, NOT the path-2d in-plane turn (verified via
  ;; nREPL: path-2d's rebinding does not reach inside a nested `path` macro
  ;; expansion). project-2d-to-xy must leave it untouched: renaming it to :th
  ;; (as the pre-refactor implementation did) mislabels it, and on a later
  ;; confirm→reopen cycle a real pitch would come back as a hard in-plane turn.
  (testing "the sub-path's :tv survives project-2d-to-xy unrenamed"
    (let [seed (:result (h/eval-dsl "(path-2d (f 10) (side-trip (tv 45) (f 5)) (mark :m1) (f 10))"))
          {:keys [nodes dropped]} (nodes-of seed)
          tail (:tail (nth nodes 1))
          sub-cmds (:commands (first (:args (first tail))))]
      (is (empty? dropped))
      (is (= 3 (count nodes)))
      (is (= :side-trip (:cmd (first tail))))
      (is (= :mark (:cmd (second tail))))
      (is (= :tv (:cmd (first sub-cmds)))
          (str "side-trip's own :tv must not be renamed to :th: " sub-cmds)))))

(deftest closed-path-folds-seam-into-node-0
  (testing "a 4-segment closed square folds its closing segment into node 0"
    (let [seed (:result (h/eval-dsl "(path-2d :closed (f 10)(tv 90)(f 10)(tv 90)(f 10)(tv 90)(f 10))"))
          {:keys [nodes closed? dropped]} (nodes-of seed true)]
      (is (empty? dropped))
      (is closed?)
      (is (= 4 (count nodes)))
      (is (approx-vec= [0 0] (:pos (nth nodes 0)) 1e-9))
      (is (approx-vec= [10 0] (:pos (nth nodes 1)) 1e-9))
      (is (approx-vec= [10 10] (:pos (nth nodes 2)) 1e-9))
      (is (approx-vec= [0 10] (:pos (nth nodes 3)) 1e-9)))))

(deftest unsupported-command-reports-as-dropped-keyword
  ;; :dropped must carry the cmd KEYWORD (e.g. :u, from a raw rt/lt call — path-2d
  ;; itself never emits :u since it rebinds rt/lt to a strafe already handled, but
  ;; a hand-built 2D seed can), not the whole command map — a plain regression
  ;; guard on the reduce's :else branch bookkeeping.
  (testing "an unsupported top-level command is dropped by keyword"
    (let [seed {:type :path :commands [{:cmd :f :args [5]} {:cmd :u :args [3]}]}
          {:keys [dropped]} (nodes-of seed)]
      (is (= [:u] dropped)))))

(deftest request-returns-traceable-seed-still-works
  ;; regression guard for the existing edit-path2d-script-test coverage: request!
  ;; must keep returning a fully-traceable seed for the surrounding script, even
  ;; though its internal recovery path changed completely.
  (testing "straight and bezier 2D seeds both proceed through request!"
    (let [straight (:result (h/eval-dsl "(path-2d (move-to [0 0]) (f 20) (tv 90) (f 20))"))
          bez (:result (h/eval-dsl "(path-2d (move-to [0 -0.27]) (bezier-to [0 10.37 -23.31] [0 1.64 -9.73] [0 6.94 -19.14] :local))"))]
      (is (= :2d (:species (ep/request! straight))))
      (is (= :2d (:species (ep/request! bez)))))))
