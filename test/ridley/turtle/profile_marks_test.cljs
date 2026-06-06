(ns ridley.turtle.profile-marks-test
  "Tests for profile marks → mesh anchors and the :on / slice-mesh-:on family.
   A marked path-to-shape profile carries point-index :mark-refs; extruding /
   lofting it stamps them as mesh :anchors, composes them with rail locations
   (:on), and hands the morphed profile back via slice-mesh :on."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.turtle.core :as t]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.shape-fn :as sfn]
            [ridley.turtle.loft :as loft]
            [ridley.turtle.extrusion :as ext]
            [ridley.editor.impl :as impl]
            [ridley.editor.implicit :as imp]
            [ridley.test-helpers :as h]))

;; A triangle profile with a mark at every corner (each mark is a vertex).
(defn- tri-profile []
  (shape/path-to-shape
   (t/make-path [{:cmd :mark :args [:foot-1]} {:cmd :f :args [30]} {:cmd :th :args [120]}
                 {:cmd :mark :args [:foot-2]} {:cmd :f :args [30]} {:cmd :th :args [120]}
                 {:cmd :mark :args [:foot-3]} {:cmd :f :args [30]}])))

(defn- extrude-mesh [shp rail-cmds]
  (last (:meshes (t/extrude-from-path (t/make-turtle) shp (t/make-path rail-cmds)))))

(deftest path-to-shape-records-mark-refs
  (testing "each corner mark is recorded as a point-index ref"
    (let [sh (tri-profile)]
      (is (= {:foot-1 0 :foot-2 1 :foot-3 2}
             (into {} (map (fn [[k v]] [k (:vertex v)]) (:mark-refs sh))))
          "marks map to consecutive vertex indices"))))

(deftest extrude-stamps-mesh-anchors
  (testing "extruding a marked profile yields mesh :anchors at the corners"
    (let [mesh (extrude-mesh (tri-profile) [{:cmd :f :args [4]}])
          a (:anchors mesh)]
      (is (= #{:foot-1 :foot-2 :foot-3} (set (keys a))) "all marks present")
      (is (h/vec-approx= [0 0 0] (:position (:foot-1 a)) 1e-4)
          "foot-1 lands at the base-section origin")
      (is (some? (:section-anchors mesh)) "section anchors kept for composition")
      (is (some? (:rail-path mesh)) "rail kept for :on"))))

(deftest on-composition-mark-and-fraction
  (testing ":on composes a profile mark with a rail mark or a fraction t"
    (let [rail [{:cmd :f :args [10]} {:cmd :mark :args [:mid]} {:cmd :f :args [10]}]
          mesh (extrude-mesh (tri-profile) rail)
          at-0   (#'impl/compose-rail-pose mesh :foot-1 0)
          at-mid (#'impl/compose-rail-pose mesh :foot-1 :mid)
          at-1   (#'impl/compose-rail-pose mesh :foot-1 1)
          base   (get-in mesh [:anchors :foot-1 :position])]
      (is (h/vec-approx= base (:position at-0) 1e-4) ":at ≡ :on 0 (base section)")
      (is (h/vec-approx= (:position at-mid) (:position (#'impl/compose-rail-pose mesh :foot-1 0.5)) 1e-4)
          "named rail mark :mid equals fraction 0.5 on a uniform rail")
      (is (not (h/vec-approx= (:position at-0) (:position at-1) 1e-4))
          "the two ends of the sweep are distinct"))))

(deftest on-anchors-grid-product
  (testing "grid [rail-sel shape-pat] yields the product of rail locs × marks"
    (let [mesh (extrude-mesh (tri-profile) [{:cmd :f :args [20]}])]
      (is (= 6 (count (impl/on-anchors-grid-poses mesh [0 1] "foot")))
          "3 feet × 2 fractions")
      (is (= 3 (count (impl/on-anchors-grid-poses mesh 0.5 "foot")))
          "3 feet × one fraction")
      (is (= 0 (count (impl/on-anchors-grid-poses mesh [0 1] "nope")))
          "no shape match → empty"))))

(deftest face-anchor-steps-onto-wall
  (testing ":face steps an embroid wall anchor onto a face with opposite heading"
    (let [wp (t/make-path [{:cmd :f :args [20]} {:cmd :mark :args [:mount]}])
          efn (sfn/embroid wp 3 :wall {:style :honeycomb :cells 4 :border 2})
          mesh (last (:meshes (loft/loft-from-path (t/make-turtle) (efn 0)
                                                   (fn [_ s] (efn s))
                                                   (t/make-path [{:cmd :f :args [30]}]) 8)))
          anc (get-in mesh [:anchors :mount])
          outer (#'impl/face-pose anc :outer)
          inner (#'impl/face-pose anc :inner)]
      (is (some? (:half-width anc)) "embroid anchor carries the wall half-width")
      (is (h/vec-approx= (mapv - (:position outer) (:position anc))
                         (mapv #(* -1 %) (mapv - (:position inner) (:position anc))) 1e-4)
          "outer and inner step opposite ways off the centerline")
      (is (h/vec-approx= (:heading outer) (mapv #(* -1 %) (:heading inner)) 1e-4)
          "the two face headings are opposite"))))

(deftest slice-on-is-morph-aware
  (testing "slice-mesh :on returns the profile AT t; marks ride the taper"
    (let [base (tri-profile)
          mesh (last (:meshes (loft/loft-from-path (t/make-turtle) base
                                                   (fn [_ s] ((sfn/tapered base :to 0.5) s))
                                                   (t/make-path [{:cmd :f :args [20]}]) 8)))
          s0 (first (imp/implicit-slice-mesh mesh :on 0))
          s1 (first (imp/implicit-slice-mesh mesh :on 1))
          a0 (ext/mark-refs->section-anchors s0)
          a1 (ext/mark-refs->section-anchors s1)]
      (is (some? (:mark-refs s1)) "the morphed slice still carries mark-refs")
      (is (h/vec-approx= (:position (:foot-2 a0)) [30 0 0] 1e-2)
          "foot-2 at full size on the base section")
      (is (not (h/vec-approx= (:position (:foot-2 a0)) (:position (:foot-2 a1)) 1e-2))
          "foot-2 moves under the taper (marks track the scaled points)"))))
