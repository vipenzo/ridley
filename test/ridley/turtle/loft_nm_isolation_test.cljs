(ns ridley.turtle.loft-nm-isolation-test
  "SANE BASELINES that the loft corner-assembly fix must PRESERVE (these stay
   GREEN throughout). The defect itself — loft + corner ⇒ non-manifold — is
   asserted as an EXPECTED-RED net in loft_corner_assembly_net_test (assert nm=0,
   red now, green when fixed); the mechanism diagnosis lives in
   loft_nm_origin_test. This file pins only the cases that are ALREADY watertight
   and must remain so: `extrude` of a section + corner (continuous build), and a
   `loft` with NO corner in the rail (caps built inline). If the fix breaks
   either, it has regressed a healthy path. See the investigation chain
   (sessions 2026-06-22)."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.editor.sci-harness :as h]
            [ridley.geometry.mesh-utils :as mu]))

(defn- diag [code]
  (let [{:keys [result error]} (h/eval-dsl code)]
    (if (or error (not (map? result)) (empty? (:vertices result)))
      {:err (or error "nil/empty mesh")}
      (let [d (mu/mesh-diagnose result)]
        {:f (count (:faces result)) :nm (:non-manifold-edges d)
         :oe (:open-edges d) :wt (:is-watertight? d)}))))

;; ── sane baselines the fix must preserve (stay GREEN) ───────────

(deftest extrude-same-corner-is-watertight
  (testing "extrude of the SAME section + corner is watertight (continuous build)"
    (let [r (diag "(extrude (circle 10 32) (f 20) (th 90) (f 20))")]
      (is (nil? (:err r)) (str "should build: " (:err r)))
      (is (zero? (:nm r)) (str "extrude+corner must be manifold; nm=" (:nm r)))
      (is (true? (:wt r)) "extrude+corner must be watertight"))))

(deftest loft-straight-rail-is-watertight
  (testing "loft with NO corner in the rail is watertight (caps built inline)"
    (let [r (diag "(loft (circle 10 32) (fn [s t] s) (f 50))")]
      (is (nil? (:err r)) (str "should build: " (:err r)))
      (is (zero? (:nm r)) (str "straight loft must be manifold; nm=" (:nm r)))
      (is (true? (:wt r)) "straight loft must be watertight"))))

(deftest extrude-sharp-corner-is-watertight
  ;; Pairs with visible-manifestation-sharp-corner-hole in the net: extrude CLOSES
  ;; the same sharp (th 150) corner that opens a 5-edge HOLE in loft (oe=0 here),
  ;; proving the visible hole is loft-assembly-specific, not a generic sharp-angle
  ;; limitation. Stays GREEN. (extrude leaves a few sliver/degenerate faces at the
  ;; sharp inner fold, but no hole — watertight.)
  (testing "extrude closes a sharp corner (no hole) — the loft target"
    (let [r (diag "(extrude (circle 10 32) (f 20) (th 150) (f 20))")]
      (is (nil? (:err r)) (str "should build: " (:err r)))
      (is (zero? (:oe r)) (str "extrude sharp corner must have no open edges; oe=" (:oe r)))
      (is (zero? (:nm r)) (str "extrude sharp corner must be manifold; nm=" (:nm r)))
      (is (true? (:wt r)) "extrude sharp corner must be watertight"))))
