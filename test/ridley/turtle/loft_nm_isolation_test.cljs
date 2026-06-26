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

(deftest extrude-sharp-corner-is-refused
  ;; SUPERSEDED by the corner-realizability guard (2026-06-25). This fixture —
  ;; (circle 10) through (f 20)(th 150)(f 20) — is itself unrealizable: the th150
  ;; miter is 10·tan75 ≈ 37.3, far longer than the 20-unit legs, so effective-dist
  ;; is deeply negative and the tube folds back through itself (an invisible
  ;; self-intersection; corner-self-intersection-net-test). The old contract here
  ;; was "extrude CLOSES this corner watertight, where loft opened a hole" — but
  ;; that was patching geometry that should never have been built. The guard now
  ;; refuses the corner outright for BOTH operators, so the extrude-vs-loft
  ;; contrast at this corner is moot. (Realizable sharp corners — e.g. th90, or
  ;; th150 with legs > the miter — are covered by the loft-corner-assembly net.)
  (testing "an over-mitred sharp (th 150) corner is refused, not silently folded"
    (let [err (:error (h/eval-dsl "(extrude (circle 10 32) (f 20) (th 150) (f 20))"))]
      (is (and err (re-find #"too sharp for how wide" err))
          (str "extrude must refuse the unrealizable th150 corner; got error=" err)))))
