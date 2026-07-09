(ns ridley.editor.stretch-material-frame-test
  "dev-docs/brief-stretch-material-frame.md: stretch-f/rt/u must act along the
   mesh's MATERIAL axes, not the pose's current axes — otherwise a stretch after a
   cp-th/cp-tv/cp-tr rotation blends more than one physical dimension of the object
   (cp-* rotates geometry relative to a pose deliberately held fixed, so pose axes
   and material axes diverge after one). Defining invariant: (stretch-f k) before
   and after a (cp-th a) must produce the identical mesh, for all 9 combinations of
   stretch axis × cp-rotation axis.

   Written BEFORE impl.cljs's fix, per the brief's own instruction — the sign of the
   offset update (brief's A1) is validated by this test actually passing, not by
   inspection. Exercises impl/attach-impl directly with a hand-built asymmetric box,
   same no-SCI pattern as move_to_mate_test.cljs."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.editor.impl :as impl]
            [ridley.geometry.primitives :as primitives]
            [ridley.turtle.attachment :as attachment]
            [ridley.test-helpers :as th]))

;; Asymmetric so heading/right/up extents are numerically distinguishable —
;; box-mesh's sx/sy/sz map to heading/right/up respectively (creation-pose heading
;; +X, up +Z, so right = heading×up = -Y; sy is the right-axis extent).
(defn- test-mesh []
  (primitives/box-mesh 4 2 3))

(defn- cmd [c & args] {:cmd c :args (vec args)})

(defn- run [& cmds]
  (impl/attach-impl (test-mesh) {:type :path :commands (vec cmds)}))

(defn- meshes-approx= [m1 m2]
  (and (= (count (:vertices m1)) (count (:vertices m2)))
       (every? true? (map #(th/vec-approx= %1 %2 1e-6) (:vertices m1) (:vertices m2)))))

;; ============================================================
;; Commutation invariant — the brief's defining test
;; ============================================================

(def ^:private stretch-cmds
  {:f  #(cmd :stretch-f %)
   :rt #(cmd :stretch-rt %)
   :u  #(cmd :stretch-u %)})

(def ^:private cp-cmds
  {:th #(cmd :cp-th %)
   :tv #(cmd :cp-tv %)
   :tr #(cmd :cp-tr %)})

(deftest stretch-cp-commute
  (doseq [[sk sf] stretch-cmds
          [ck cf] cp-cmds]
    (testing (str "stretch-" (name sk) " commutes with cp-" (name ck))
      (let [s (sf 1.6)
            c (cf 37) ; not a multiple of 90 — catches axis/sign errors 90° hides
            before (run s c)
            after  (run c s)]
        (is (meshes-approx= before after)
            (str "(stretch-" (name sk) " 1.6)(cp-" (name ck) " 37) should produce "
                 "the same mesh as (cp-" (name ck) " 37)(stretch-" (name sk) " 1.6)"))))))

;; ============================================================
;; Regression — identity offset (no cp-rotation) is bit-identical to before
;; ============================================================

(deftest stretch-no-cp-matches-plain-pose-axis
  (testing "without any cp-rotation, stretch resolves to exactly the bare pose axis
            (the material offset is still identity), matching pre-fix behavior"
    (let [moved (run (cmd :f 5) (cmd :th 30))
          actual (run (cmd :f 5) (cmd :th 30) (cmd :stretch-f 1.7))
          pose (:creation-pose moved)
          expected (attachment/stretch-mesh-along-axis
                    moved (:heading pose) 1.7 (:position pose))]
      (is (meshes-approx= actual expected)))))

;; ============================================================
;; Reflection (negative factor) after a cp-rotation stays a valid mesh
;; ============================================================

(deftest stretch-negative-factor-after-cp-stays-watertight
  (testing "reflection (negative stretch factor) after a cp-rotation keeps the mesh
            manifold — the winding flip in stretch-mesh-along-axis must still apply
            correctly to the (now material, not pose) axis"
    (let [m (run (cmd :cp-th 60) (cmd :stretch-f -1.2))]
      (is (th/watertight? m)))))

;; ============================================================
;; Pivot still follows cp translations (unchanged by this brief)
;; ============================================================

(deftest stretch-pivot-follows-cp-translation
  (testing "cp-rt then stretch-f still pivots at the shifted anchor position, exactly
            as before — this brief only changes stretch DIRECTION, never the pivot"
    (let [moved (run (cmd :cp-rt 10))
          actual (run (cmd :cp-rt 10) (cmd :stretch-f 2.0))
          pose (:creation-pose moved)
          expected (attachment/stretch-mesh-along-axis
                    moved (:heading pose) 2.0 (:position pose))]
      (is (meshes-approx= actual expected)))))
