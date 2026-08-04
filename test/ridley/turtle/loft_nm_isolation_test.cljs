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
            [ridley.editor.operations :as ops]
            [ridley.turtle.shape :as shape]
            [ridley.clipper.core :as clipper]
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

;; ── holed profile: orientation, which edge counting cannot see ─────

;; Built here, not in the DSL: the SCI test harness has no shape-difference
;; binding (see the harness's own note on binding drift).
(def ^:private ring-shape
  (clipper/shape-difference (shape/circle-shape 35 64) (shape/circle-shape 33 64)))

(defn- rail [code]
  (h/eval-dsl "1")                      ;; reset the turtle to the origin
  (:result (h/eval-dsl (str "(path " code ")"))))

(defn- loft* [code]
  (let [p (rail code)]
    (h/eval-dsl "1")
    (:mesh (ops/pure-loft-path* ring-shape (fn [s _t] s) p))))

(defn- extrude* [code]
  (let [p (rail code)]
    (h/eval-dsl "1")
    (ops/pure-extrude-path ring-shape p)))

(defn- winding-mismatches
  "Directed edges without an opposite twin. This is the invariant Manifold
   enforces (\"Not manifold\") and the one edge-COUNT watertightness is blind
   to: two neighbouring faces wound the same way share an edge twice in the
   SAME direction, which still counts as incidence 2."
  [mesh]
  (let [de (reduce (fn [acc [a b c]]
                     (-> acc (update [a b] (fnil inc 0))
                         (update [b c] (fnil inc 0))
                         (update [c a] (fnil inc 0))))
                   {} (:faces mesh))]
    (count (filter (fn [[[a b] n]] (not= n (get de [b a] 0))) de))))

(defn- signed-volume
  "Divergence-theorem volume. Positive iff the faces are wound outward — so a
   sub-mesh that got reversed shows up as volume LOST, not just as bad winding."
  [mesh]
  (let [vs (:vertices mesh)]
    (reduce (fn [acc [a b c]]
              (let [[px py pz] (vs a) [qx qy qz] (vs b) [rx ry rz] (vs c)]
                (+ acc (/ (- (* px (- (* qy rz) (* qz ry)))
                             (* py (- (* qx rz) (* qz rx)))
                             (- (* pz (- (* qx ry) (* qy rx)))))
                          6))))
            0 (:faces mesh))))

(deftest holed-loft-on-a-curved-rail-is-manifold
  ;; A profile WITH A HOLE takes loft's per-segment branch: every corner (and an
  ;; arc IS a chain of small corners) flushes its own sub-mesh, and they are
  ;; welded afterwards. Two defects lived in that assembly, both invisible to
  ;; nm/open-edge counting and both fatal to Manifold:
  ;;   1. build-sweep-mesh-with-holes treated the :start/:end cap selector as
  ;;      merely truthy, so intermediate sections capped BOTH ends — the weld
  ;;      stacked an internal cap on a seam ring (that one nm-counting DID see);
  ;;   2. its backward-sweep test compared each sub-mesh against the fixed
  ;;      creation heading. A corner bridge's rings share a centroid, so the
  ;;      sign was float noise (≈half the bridges reversed), and past 90° of
  ;;      turn every section read as "backward" and was reversed too.
  ;; Symptom for the user: transform-> drew only its first segment.
  (doseq [[label code] [["arc 90"   "(arc-v 80 90)"]
                        ["arc 180"  "(arc-v 80 180)"]
                        ["corner"   "(f 20) (tv 30) (f 20)"]
                        ["straight" "(f 30)"]]]
    (testing (str "holed loft — " label)
      (let [m (loft* code)
            d (mu/mesh-diagnose m)]
        (is (zero? (:non-manifold-edges d)) (str label ": nm=" (:non-manifold-edges d)))
        (is (zero? (:open-edges d)) (str label ": open=" (:open-edges d)))
        (is (zero? (winding-mismatches m))
            (str label ": faces wound inconsistently — Manifold would refuse this mesh"))
        (is (pos? (signed-volume m)) (str label ": normals must point outward")))))

  (testing "volume scales with the arc — reversed sub-meshes used to eat it"
    (let [v90 (signed-volume (loft* "(arc-v 80 90)"))
          v180 (signed-volume (loft* "(arc-v 80 180)"))]
      ;; same section, twice the sweep → twice the volume (it was 0.67× when the
      ;; far half of the arc came out reversed)
      (is (< (Math/abs (- (/ v180 v90) 2)) 0.01)
          (str "expected v180/v90 ≈ 2, got " (/ v180 v90)))))

  (testing "a genuinely backward sweep is still reversed (the case (2) exists for)"
    (let [holed (extrude* "(f -30)")
          plain (:result (h/eval-dsl "(extrude (circle 35) (f -30))"))]
      (doseq [[label m] [["holed" holed] ["plain" plain]]]
        (is (zero? (winding-mismatches m)) (str label " backward extrude: winding"))
        (is (pos? (signed-volume m)) (str label " backward extrude: normals outward"))))))
