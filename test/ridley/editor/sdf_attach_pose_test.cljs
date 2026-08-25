(ns ridley.editor.sdf-attach-pose-test
  "dev-docs/BUG-sdf-attach-creation-pose.md: attach on an SDF must start its
   replay turtle at the SDF's creation-pose, mirroring turtle/attach-move on
   the mesh branch. Before the fix the turtle started at world origin with the
   default frame, so every th/tv/tr pivoted around the origin (lever arm equal
   to the SDF's distance from it) and every translation followed the world
   axes instead of the SDF's frame.

   Exercises impl/attach-impl directly, same no-SCI pattern as
   stretch_material_frame_test.cljs. Assertions read the resulting tree's
   :creation-pose and :anchors — both are transported by the same sdf ops that
   move the geometry, so they are a faithful, cheap proxy for where the
   geometry went (no server round-trip)."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.editor.impl :as impl]
            [ridley.sdf.core :as sdf]
            [ridley.turtle.core :as turtle]
            [ridley.test-helpers :as th]))

(defn- cmd [c & args] {:cmd c :args (vec args)})

(defn- run [node & cmds]
  (impl/attach-impl node {:type :path :commands (vec cmds)}))

;; ── Rotation pivots on the SDF, not on the world origin ─────────

(deftest tv-out-of-origin-pivots-on-the-sdf
  (testing "an SDF created 30 ahead keeps its position under (tv 80) — no lever arm"
    (let [node   (sdf/sdf-move (sdf/sdf-sphere 2) 30 0 0)
          result (run node (cmd :tv 80))
          cp     (:creation-pose result)
          ;; The frame must end up exactly where a turtle standing on the
          ;; SDF would: same tv, same starting pose.
          expected (turtle/tv (assoc (turtle/make-turtle) :position [30 0 0]) 80)]
      (is (th/vec-approx= [30 0 0] (:position cp) 1e-9)
          "rotation must pivot on the SDF's own position")
      (is (th/vec-approx= (:heading expected) (:heading cp) 1e-9))
      (is (th/vec-approx= (:up expected) (:up cp) 1e-9)))))

;; ── Translation follows the SDF's frame, not the world axes ─────

(deftest f-follows-the-sdf-heading
  (testing "(f 10) on an SDF whose frame is rotated 90° around Z moves along +Y"
    (let [node   (sdf/sdf-rotate (sdf/sdf-sphere 2) :z 90)
          result (run node (cmd :f 10))
          cp     (:creation-pose result)]
      (is (th/vec-approx= [0 10 0] (:position cp) 1e-9))
      (is (= "move" (:op result)))
      (is (th/vec-approx= [0 10 0] [(:dx result) (:dy result) (:dz result)] 1e-9)))))

;; ── cp-* rotations use the SDF's frame for their axis ───────────

(deftest cp-th-axis-is-the-sdf-up
  (testing "cp-th rotates around the SDF's up (tilted by a prior rotate), not world Z"
    ;; After (sdf-rotate :x 90): probe anchor [0 5 0]→[0 0 5], up [0 0 1]→[0 -1 0].
    ;; (cp-th 90) = rotate geometry by -90° around the up axis through the
    ;; creation-pose. Around the SDF's up: probe lands on [5 0 0]. Around
    ;; world Z (the bug), the probe sits ON the axis and would not move.
    (let [node   (-> (sdf/sdf-sphere 2)
                     (assoc-in [:anchors :probe]
                               {:position [0 5 0] :heading [1 0 0] :up [0 0 1]})
                     (sdf/sdf-rotate :x 90))
          result (run node (cmd :cp-th 90))
          probe  (get-in result [:anchors :probe])]
      (is (th/vec-approx= [0 0 5] (get-in node [:anchors :probe :position]) 1e-9)
          "sanity: the prior rotate puts the probe on the Z axis")
      (is (th/vec-approx= [5 0 0] (:position probe) 1e-6)))))

;; ── Degenerate case: pristine SDF (pose at origin) is unchanged ─

(deftest pristine-node-attach-behaves-as-before
  (testing "creation-pose at origin/default frame ⇒ identical to pre-fix behavior"
    (let [result (run (sdf/sdf-sphere 2) (cmd :f 10))]
      (is (th/vec-approx= [10 0 0] (get-in result [:creation-pose :position]) 1e-9))
      (is (= "move" (:op result)))
      (is (th/vec-approx= [10 0 0] [(:dx result) (:dy result) (:dz result)] 1e-9)))))
