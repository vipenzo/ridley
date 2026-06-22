(ns ridley.turtle.loft-corner-assembly-net-test
  "EXPECTED-RED NET for the loft corner-assembly defect (non-regression net for
   the upcoming fix). See the investigation chain (sessions 2026-06-22). The
   defect is PRE-EXISTING, not a regression: a `loft` of a section along a rail
   with any corner assembles section + corner-bridge + caps as SEPARATE
   sub-meshes and seam-welds them (operations.cljs:178), producing non-manifold
   geometry; `extrude` and `revolve` use a continuous build and stay watertight.

   CONTRACT — these tests assert the TARGET state (nm=0 / watertight) and
   therefore FAIL NOW on the defective code. That is intentional: a test written
   to fail on the broken state PROVES it can see the defect (a test written after
   the fix, on already-sane geometry, never demonstrates it distinguishes broken
   from healed). The fix turns them green one mechanism at a time; each red→green
   transition is an independent verification, and all-green is the fix's closing
   condition. (Note: the brief's Part-1/Collocazione wording 'assert nm>0, green
   now' contradicts its Title/Principle/Verifica 'fail now'; the Verifica
   acceptance criterion — 'i nuovi test falliscono tutti … nm>0 dove il fix
   porterà nm=0' — is decisive, so these assert nm=0 and are red now.)

   TOOL DISCIPLINE — assertions use mesh-diagnose / index-based edge counts
   (:non-manifold-edges, :open-edges, :is-watertight?), NEVER Manifold-WASM
   `manifold?`. Manifold? tolerates the stacked geometry that mesh-diagnose flags
   — relying on it is exactly what masked this bug for months. The index-based
   count is stricter; here the strictness is the protection."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.editor.sci-harness :as h]
            [ridley.editor.operations :as ops]
            [ridley.turtle.core :as turtle]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.shape-fn :as sfn]
            [ridley.geometry.mesh-utils :as mu]))

;; ── helpers (all via mesh-diagnose, never manifold?) ─────────

(defn- d [mesh]
  (if (and (map? mesh) (seq (:vertices mesh)))
    (let [x (mu/mesh-diagnose mesh)]
      {:f (count (:faces mesh)) :nm (:non-manifold-edges x)
       :oe (:open-edges x) :wt (:is-watertight? x) :deg (:degenerate-faces x)})
    {:err "nil/empty mesh"}))

(defn- rail [code] (:result (h/eval-dsl code)))
(defn- lp [prof railcode] (ops/pure-loft-path prof (fn [s _t] s) (rail railcode) 64))

;; assert a loft case is watertight (RED now: it is non-manifold)
(defn- assert-target-watertight [label mesh]
  (let [r (d mesh)]
    (is (nil? (:err r)) (str label " — mesh must build: " (:err r)))
    (is (zero? (:nm r))
        (str label " — TARGET nm=0; CURRENT nm=" (:nm r) " (EXPECTED-RED: loft corner-assembly defect)"))
    (is (true? (:wt r)) (str label " — TARGET watertight; CURRENT wt=" (:wt r)))))

;; ── PART 1 — distinct generation mechanisms ─────────────────

(deftest mech-1-single-convex-corner
  ;; Base case: one hard th corner, smooth circle profile. Exercises a single
  ;; corner-bridge + the double-capping of both true ends.
  (testing "single convex corner, smooth profile"
    (assert-target-watertight "loft circle / (f 20)(th 90)(f 20)"
                              (lp (shape/circle-shape 10 32) "(path (f 20) (th 90) (f 20))"))))

(deftest mech-2-multiple-consecutive-corners
  ;; Three consecutive th corners: each joint flushes a sub-mesh + bridge, so the
  ;; interaction between ADJACENT joints (each section open at both seams) is
  ;; exercised — not measured by the single-corner case.
  (testing "multiple consecutive corners in the rail"
    (assert-target-watertight "loft circle / 3×(th 45)"
                              (lp (shape/circle-shape 10 32)
                                  "(path (f 15) (th 45) (f 15) (th 45) (f 15) (th 45) (f 15))"))))

(deftest mech-3-tv-corner-other-plane
  ;; A tv (pitch) corner instead of th: the corner-bridge is built in a different
  ;; plane (heading↔up rotation), a distinct construction path from th. (A
  ;; negative-th corner is the mirror of positive-th — same code path — so tv is
  ;; the genuinely-distinct 'other corner' mechanism here.)
  (testing "tv corner (vertical-plane bridge)"
    (assert-target-watertight "loft circle / (f 20)(tv 90)(f 20)"
                              (lp (shape/circle-shape 10 32) "(path (f 20) (tv 90) (f 20))"))))

(deftest mech-4-nonconvex-profile
  ;; Non-convex (star) profile: the nm count scales with profile vertices and a
  ;; concave profile triangulates the caps/joint differently than a circle, so
  ;; this exercises the profile-triangulation facet of the seam.
  (testing "non-convex profile (star) with a corner"
    (assert-target-watertight "loft star / (f 20)(th 90)(f 20)"
                              (lp (shape/star-shape 5 12 5) "(path (f 20) (th 90) (f 20))"))))

(deftest mech-5-tapered-variant-shapefn-path
  ;; Variant via pure-loft-shape-fn (the shape-fn assembly path): a tapered
  ;; (per-ring varying) section along a corner. Report claims variants are hit
  ;; but no test exercised their non-manifold — this does.
  (testing "tapered shape-fn variant (pure-loft-shape-fn) with a corner"
    (let [efn (sfn/tapered (shape/circle-shape 10 32) :to 0.5)]
      (assert-target-watertight "loft (tapered circle :to 0.5) / (f 20)(th 90)(f 20)"
                                (ops/pure-loft-shape-fn efn (rail "(path (f 20) (th 90) (f 20))") 64)))))

(deftest mech-6-two-shape-variant-path
  ;; Variant via pure-loft-two-shapes (a distinct assembly entry point): morph
  ;; circle→circle along a corner.
  (testing "two-shape loft (pure-loft-two-shapes) with a corner"
    (assert-target-watertight "loft two-shape circle→circle / (f 20)(th 90)(f 20)"
                              (ops/pure-loft-two-shapes (shape/circle-shape 10 32)
                                                        (shape/circle-shape 6 24)
                                                        (rail "(path (f 20) (th 90) (f 20))") 64))))

;; ── PART 2 — isolate the two contributors (staged-fix criterion) ──

(defn- raw-combine [meshes]
  (reduce (fn [acc m]
            (let [off (count (:vertices acc))]
              {:vertices (into (:vertices acc) (:vertices m))
               :faces (into (:faces acc) (mapv (fn [f] (mapv #(+ % off) f)) (:faces m)))}))
          {:vertices [] :faces []} (filter map? meshes)))

(defn- dup-face-total [mesh]
  (let [vs (:vertices mesh)
        k (fn [i] (let [p (nth vs i)] (mapv #(Math/round (* 1e4 (nth p %))) [0 1 2])))
        groups (frequencies (map (fn [f] (sort (map k f))) (:faces mesh)))]
    (reduce + 0 (keep (fn [[_ n]] (when (> n 1) n)) groups))))

(defn- subs-for [railcode]
  (:meshes (turtle/loft-from-path (turtle/make-turtle) (shape/circle-shape 10 32)
                                  (fn [s _t] s) (rail railcode) 64)))

(deftest contributor-a-double-capping-isolated
  ;; (a): each true end is capped TWICE — by do-build AND a separate make-cap-mesh
  ;; (loft.cljs:812-815). The fix's first stage removes the separate caps. This
  ;; test goes green when the FULL mesh no longer carries duplicate cap faces AND
  ;; the separate :cap sub-meshes no longer change the nm count.
  (testing "double-capping contributes duplicate faces (fix-stage-a closes this)"
    (let [ms   (subs-for "(path (f 20) (th 90) (f 20))")
          full (mu/merge-vertices (raw-combine ms) 1e-4)
          nocap (mu/merge-vertices (raw-combine (remove #(= :cap (:primitive %)) ms)) 1e-4)]
      (is (zero? (dup-face-total full))
          (str "TARGET 0 dup cap faces; CURRENT " (dup-face-total full) " (EXPECTED-RED: double-capping)"))
      (is (= (:nm (d full)) (:nm (d nocap)))
          (str "TARGET: separate caps add no nm; CURRENT full=" (:nm (d full))
               " vs no-sep-caps=" (:nm (d nocap)) " (EXPECTED-RED)")))))

(deftest contributor-b-corner-bridge-isolated
  ;; (b): the corner-bridge seam (incidence-3 T-junctions) is non-manifold even
  ;; with the double-caps removed. Isolated by dropping the separate :cap
  ;; sub-meshes (so only the bridge defect remains) and asserting that residual
  ;; is manifold. Goes green ONLY when fix-stage-b closes the bridge seam —
  ;; independent of stage-a.
  (testing "corner-bridge seam is non-manifold independent of double-capping"
    (let [ms (subs-for "(path (f 20) (th 90) (f 20))")
          nocap (mu/merge-vertices (raw-combine (remove #(= :cap (:primitive %)) ms)) 1e-4)]
      (is (zero? (:nm (d nocap)))
          (str "TARGET bridge nm=0; CURRENT " (:nm (d nocap)) " (EXPECTED-RED: corner-bridge seam)")))))

;; ── PART 3 — search for a VISIBLE manifestation (printing) ───
;; Is the defect ever visible (hole / missing face / opposite-wound coincident
;; faces → z-fight), not just topological? Measure open-edges, degenerate faces,
;; and opposite-wound duplicate-face pairs across the mechanisms.

(defn- face-normal [vs [a b c]]
  (let [[ax ay az] (nth vs a) [bx by bz] (nth vs b) [cx cy cz] (nth vs c)
        ux (- bx ax) uy (- by ay) uz (- bz az)
        vx (- cx ax) vy (- cy ay) vz (- cz az)
        nx (- (* uy vz) (* uz vy)) ny (- (* uz vx) (* ux vz)) nz (- (* ux vy) (* uy vx))
        m (Math/sqrt (+ (* nx nx) (* ny ny) (* nz nz)))]
    (if (< m 1e-9) [0 0 0] [(/ nx m) (/ ny m) (/ nz m)])))

(defn- opposite-wound-dups [mesh]
  ;; coincident faces (same 3 positions) whose normals point opposite ways →
  ;; render as a doubled surface with conflicting normals (visible z-fight/black).
  (let [vs (:vertices mesh)
        k (fn [i] (let [p (nth vs i)] (mapv #(Math/round (* 1e4 (nth p %))) [0 1 2])))
        by-pos (group-by (fn [f] (sort (map k f))) (:faces mesh))]
    (reduce (fn [acc [_ fs]]
              (if (>= (count fs) 2)
                (let [ns (map #(face-normal vs %) fs)]
                  (if (some (fn [[i j]] (< (reduce + (map * (nth ns i) (nth ns j))) -0.5))
                            (for [i (range (count ns)) j (range (count ns)) :when (< i j)] [i j]))
                    (inc acc) acc))
                acc))
            0 by-pos)))

(deftest part3-visible-manifestation-search
  (println "\n==== PART 3: search for VISIBLE manifestation ====")
  (doseq [[label mesh]
          [["circle 1 th-corner" (lp (shape/circle-shape 10 32) "(path (f 20) (th 90) (f 20))")]
           ["circle tv-corner"   (lp (shape/circle-shape 10 32) "(path (f 20) (tv 90) (f 20))")]
           ["circle sharp th150"  (lp (shape/circle-shape 10 32) "(path (f 20) (th 150) (f 20))")]
           ["star 1 th-corner"   (lp (shape/star-shape 5 12 5) "(path (f 20) (th 90) (f 20))")]
           ["circle 3 corners"   (lp (shape/circle-shape 10 32) "(path (f 15) (th 45) (f 15) (th 45) (f 15))")]
           ;; controls: does EXTRUDE share the sharp-corner open-edge? if extrude
           ;; th150 is watertight but loft th150 has oe>0, the hole is the loft
           ;; ASSEMBLY (visible). if extrude also opens, it's a generic sharp-angle
           ;; limitation, not this defect.
           ["EXTRUDE th90"  (:result (h/eval-dsl "(extrude (circle 10 32) (f 20) (th 90) (f 20))"))]
           ["EXTRUDE th150" (:result (h/eval-dsl "(extrude (circle 10 32) (f 20) (th 150) (f 20))"))]
           ["loft th120"    (lp (shape/circle-shape 10 32) "(path (f 20) (th 120) (f 20))")]
           ["loft th90 small-r big-prof" (lp (shape/circle-shape 18 32) "(path (f 20) (th 90) (f 20))")]]]
    (let [r (d mesh)]
      (println (str ">>> " label ": nm=" (:nm r) " oe=" (:oe r) " degenerate=" (:deg r)
                    " opposite-wound-dups=" (opposite-wound-dups mesh)))))
  ;; FINDING: at moderate angles the defect is purely topological (oe=0,
  ;; degenerate=0, no opposite-wound faces → invisible at render). But at a SHARP
  ;; corner (th 150) loft leaves oe=5 OPEN EDGES — a real hole — while extrude at
  ;; the same corner stays oe=0. The visible case is captured below.
  (is true))

(deftest visible-manifestation-sharp-corner-hole
  ;; HIGHEST-VALUE GUARDRAIL. The defect is usually topological-only (invisible).
  ;; The ONE visible manifestation found: at a SHARP corner the loft per-piece
  ;; assembly fails to CLOSE — it leaves OPEN EDGES (oe>0), i.e. missing faces / a
  ;; hole, visible at render. Extrude at the SAME corner is watertight (oe=0, see
  ;; extrude-sharp-corner-is-watertight in loft_nm_isolation_test), proving the
  ;; hole is the loft assembly, not a generic sharp-angle limitation. EXPECTED-RED:
  ;; asserts oe=0 / watertight; current loft th150 gives oe=5.
  (testing "loft along a sharp (th 150) corner leaves a VISIBLE open-edge hole"
    (let [r (d (lp (shape/circle-shape 10 32) "(path (f 20) (th 150) (f 20))"))]
      (is (nil? (:err r)) (str "mesh must build: " (:err r)))
      (is (zero? (:oe r))
          (str "TARGET oe=0 (no hole); CURRENT oe=" (:oe r) " — VISIBLE HOLE (EXPECTED-RED)"))
      (is (true? (:wt r)) (str "TARGET watertight; CURRENT wt=" (:wt r))))))
