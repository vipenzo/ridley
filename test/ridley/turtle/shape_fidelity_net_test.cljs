(ns ridley.turtle.shape-fidelity-net-test
  "NET for SHAPE FIDELITY — the first swept section must coincide with where
   `stamp` places the profile (accertamento 2026-06-23/24). This is the FIRST
   net in a new category: INPUT fidelity (does the mesh match what the user
   specifies), distinct from the existing nets on internal mesh sanity
   (non-manifold / watertight / spine-drift / fold-count) and rail fidelity.
   That category gap is exactly what let the bug through: the suite was green
   while the produced mesh did not match what the user saw with `stamp`. Keep
   this file SEPARATE; more INPUT-fidelity members will likely follow.

   THE DEFECT (diagnosis, already established): a rigid ROTATION (not distortion,
   not propagation). When a rail starts with an initial tangent ≠ the starting
   heading, the first section orients to the RAIL's tangent instead of the stamp
   pose. Shared by loft AND extrude (extrusion.cljs:1448 / loft.cljs:583). The
   invariant that catches it is 'the path's initial FRAME = the starting frame' —
   and the frame means heading AND up, not just tangent: a leading `tr` (roll)
   leaves the tangent aligned but rolls the section in its plane.

   THREE METRICS, because each alone is deceptive:
     • centroid — distance between first-section and stamp centroids (position).
     • normal   — angle between first-section and stamp plane normals (the plane
                  tilt: the bezier-storto reads 89.40° here).
     • RMS      — per-vertex RMS between the first section and stamp in their pose.
                  The ONLY one that catches a roll (tr): a profile rolled about its
                  own normal has matching centroid+normal but high RMS.

   NON-NEGOTIABLE: the profile MUST be off-centre AND asymmetric. A symmetric
   profile HIDES the bug — a circle rotated about its centre 'looks still', so its
   centroid stays put (and a circle is a non-event geometrically: its swept tube
   is identical regardless of roll). A net built on circles would be GREEN over
   the defect, exactly as the suite was. This is WHY this net is new rather than
   already written. The blindness is demonstrated in `symmetric-profile-is-blind`
   below (circle: centroid/normal read 0 over a real rotation). Every fidelity
   test here uses OFFP — an off-centre L (no rotational/reflection symmetry).

   STATUS: the fix has LANDED (Part 1: loft arc carve-out; Part 2: error at the
   rail-consumption point — extrusion.cljs validate-rail-start-frame!). The whole
   net is now GREEN. The two policy buckets the diagnosis split are realized:
     • LEGITIMATE-AND-CORRECTED — arc head (Family 3). The arc's true tangent IS
       the incoming heading (the half-step is a tessellation artifact), so it is
       NOT a violation; Part 1 carried extrude's carve-out into loft so both
       operators now coincide with stamp.
     • VIOLATING-AND-REJECTED — bezier-storto, th/tv, tr head (Family 2). The
       chosen policy is error-at-violation, so these now assert the consumer
       RAISES a readable error (direction vs roll variant), for loft AND extrude.

   CONVENTIONS (per family):
     Family 1 — conformant. GREEN (the fix must not break rails that already
       coincide with stamp): centroid≈stamp, normal≈stamp, RMS≈0.
     Family 2 — violating-and-rejected. GREEN, asserting the readable error.
     Family 3 — arc head. GREEN on BOTH operators (Part 1 ported the carve-out).
     + non-sweep-consumers-are-exempt — the scope guard: text-on-path and path
       construction must NOT trip the invariant."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.editor.sci-harness :as h]
            [ridley.editor.operations :as ops]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.core :as turtle]
            [ridley.turtle.extrusion :as ext]
            [ridley.geometry.mesh-utils :as mu]))

;; ── off-centre asymmetric profile (the non-negotiable constraint) ────
;; An L: 10×3 horizontal arm + 3×8 vertical arm. Centroid ≈ (6.3, 3.7) (off
;; origin), no rotational or reflection symmetry → a frame rotation of ANY kind
;; produces a measurable change in all of position/normal/shape.
(def OFFP (shape/make-shape [[2 0] [12 0] [12 3] [5 3] [5 8] [2 8]] {:centered? false}))
(def CIRC (shape/circle-shape 8 48))   ; symmetric — used ONLY to demonstrate blindness

;; ── metrics (all geometric; no manifold?/topology here — this is input fidelity) ──
(defn- ev [code] (:result (h/eval-dsl code)))
(defn- cen [r] (let [n (count r) s (reduce (fn [[a b c] [x y z]] [(+ a x) (+ b y) (+ c z)]) [0 0 0] r)] (mapv #(/ % n) s)))
(defn- dst [[a b c] [d e f]] (Math/sqrt (+ (* (- a d) (- a d)) (* (- b e) (- b e)) (* (- c f) (- c f)))))
(defn- adeg [u v] (* (/ 180 Math/PI) (Math/acos (max -1 (min 1 (Math/abs (reduce + (map * u v))))))))
(defn- rms [a b] (Math/sqrt (/ (reduce + (map (fn [x y] (let [d (dst x y)] (* d d))) a b)) (count a))))
(defn- first-ring [mesh n] (subvec (vec (:vertices mesh)) 0 n))

(defn- fidelity
  "Three fidelity metrics of a mesh's first ring vs the profile's stamp pose.
   The first n vertices of a :sweep mesh ARE the first ring (caps add no verts),
   in the SAME order as stamp, so vertex i ↔ vertex i for the RMS."
  [profile mesh]
  (let [n  (count (:points profile))
        sr (ext/stamp-shape (turtle/make-turtle) profile)
        r  (first-ring mesh n)]
    {:cd (dst (cen sr) (cen r))
     :na (adeg (ext/ring-plane-normal sr) (ext/ring-plane-normal r))
     :rms (rms sr r)}))

(defn- ext-mesh  [profile railcode] (ops/pure-extrude-path profile (ev railcode)))
(defn- loft-mesh [profile railcode] (ops/pure-loft-path profile (fn [s _t] s) (ev railcode) 64))

;; coincidence thresholds. Margin: conformant cases read cd<0.02 / na<0.3 / rms<0.03;
;; violations read cd≥0.6 / na≥5 / rms≥1. The coarse arc-loft gap reads na=11.25.
(def ^:private CD-TOL 0.5)
(def ^:private NA-TOL 1.0)
(def ^:private RMS-TOL 0.3)

(defn- report [label profile mesh]
  (let [{:keys [cd na rms]} (fidelity profile mesh)]
    (println (str ">>> " label ": centroid-dist=" (.toFixed cd 3)
                  " normal-angle=" (.toFixed na 2) "deg  RMS=" (.toFixed rms 3)))
    {:cd cd :na na :rms rms}))

(defn- assert-coincide
  "Assert the first section coincides with stamp on all three metrics."
  [label profile mesh]
  (let [{:keys [cd na rms]} (report label profile mesh)]
    (is (< cd CD-TOL)   (str label " — centroid must match stamp (<" CD-TOL "); cd=" (.toFixed cd 3)))
    (is (< na NA-TOL)   (str label " — normal must match stamp (<" NA-TOL "°); na=" (.toFixed na 2)))
    (is (< rms RMS-TOL) (str label " — shape (with orientation) must match stamp (<" RMS-TOL "); rms=" (.toFixed rms 3)))
    {:cd cd :na na :rms rms}))

;; rails
(def STRAIGHT      "(path (f 30))")
(def TANGENT-BEZ   "(path (bezier-to [30 10 0] [20 0 0] [28 5 0]))")     ; c1 along +x → starts tangent
(def BEZIER-STORTO "(path (bezier-to [40 0 25] [15 0 0] [30 0 10] :local))") ; V0, initial tangent ≈ −y (~89°)
(def TH45          "(path (th 45) (f 30))")
(def TR90          "(path (tr 90) (f 30))")                              ; roll: tangent stays, section rolls
(def ARC-HEAD      "(path (arc-h 12 90 :steps 4))")                      ; coarse → half-step 11.25°, resolution-robust

;; ════════════════════════════════════════════════════════════════════
;; FAMILY 1 — CONFORMANT (GREEN now, must STAY green)
;; ════════════════════════════════════════════════════════════════════

(deftest f1-straight-rail
  (testing "rail starting with f — first section already coincides with stamp"
    (assert-coincide "F1 extrude / straight" OFFP (ext-mesh OFFP STRAIGHT))
    (assert-coincide "F1 loft / straight"    OFFP (loft-mesh OFFP STRAIGHT))))

(deftest f1-tangent-bezier-rail
  (testing "rail bezier drawn to start tangent to heading (c1 along +x) — coincides with stamp"
    (assert-coincide "F1 extrude / tangent-bezier" OFFP (ext-mesh OFFP TANGENT-BEZ))
    (assert-coincide "F1 loft / tangent-bezier"    OFFP (loft-mesh OFFP TANGENT-BEZ))))

;; ════════════════════════════════════════════════════════════════════
;; FAMILY 2 — VIOLATING-AND-REJECTED (GREEN now, asserting the error)
;; CONVENTION: the target WAS coincidence (red on the old silent defect). The
;; chosen policy is ERROR-AT-VIOLATION, so the green form asserts the consumer
;; RAISES the readable error — for loft AND extrude. The message distinguishes a
;; DIRECTION violation (rotate the turtle) from a ROLL violation (roll it), so the
;; tests assert the RIGHT variant. The error lives at the rail-consumption point
;; (validate-rail-start-frame!, extrusion.cljs); see the non-sweep-consumer guard
;; in `non-sweep-consumers-are-exempt`.
;; ════════════════════════════════════════════════════════════════════

(deftest f2-bezier-storto-head
  (testing "rail starting with a crooked bezier (initial tangent ≈ −y, ~89°) — DIRECTION error"
    (is (thrown-with-msg? js/Error #"begin with a turn" (ext-mesh OFFP BEZIER-STORTO))
        "extrude must reject a rail that starts heading off-tangent")
    (is (thrown-with-msg? js/Error #"begin with a turn" (loft-mesh OFFP BEZIER-STORTO))
        "loft must reject a rail that starts heading off-tangent")))

(deftest f2-explicit-th-head
  (testing "rail starting with an explicit th 45° — DIRECTION error"
    (is (thrown-with-msg? js/Error #"begin with a turn" (ext-mesh OFFP TH45)))
    (is (thrown-with-msg? js/Error #"begin with a turn" (loft-mesh OFFP TH45)))))

(deftest f2-tr-roll-head
  ;; THE TEST THAT JUSTIFIES THE WHOLE-FRAME INVARIANT. A leading tr (roll about
  ;; the heading) leaves the TANGENT aligned — a direction-only invariant would be
  ;; BLIND — but rolls the section. The invariant compares heading AND up, so it
  ;; catches it and raises the ROLL variant (a different remedy: roll, not rotate).
  (testing "rail starting with a tr 90° roll — ROLL error (a direction-only check would be blind)"
    (let [msg (fn [build] (try (build OFFP TR90) nil
                               (catch :default e (.-message e))))
          em (msg ext-mesh)
          lm (msg loft-mesh)]
      (is (some? em) "extrude must reject a rolled rail")
      (is (some? lm) "loft must reject a rolled rail")
      (is (re-find #"begin with a twist" em)
          (str "extrude must use the ROLL message (proves the frame-whole check); got: " em))
      (is (re-find #"begin with a twist" lm)
          (str "loft must use the ROLL message; got: " lm))
      ;; a roll must NOT be misreported as a direction violation (different remedy)
      (is (not (re-find #"begin with a turn" em))
          (str "a roll must not be reported as a direction violation; got: " em)))))

(deftest f2-initial-tangent-angle-spectrum
  ;; The invariant rejects across the whole spectrum with no degenerate gap: a
  ;; GENTLE 5° start (smaller than the historical off-tangent fixtures at 17° and
  ;; 2.5° — the previously-invisible violations now covered), the right-ish 89°,
  ;; and past-square 135°. All are DIRECTION violations.
  (testing "initial-tangent spectrum 5° / 89° / 135° (th-head) — all rejected as DIRECTION errors"
    (doseq [a [5 89 135]]
      (let [rail (str "(path (th " a ") (f 30))")]
        (is (thrown-with-msg? js/Error #"begin with a turn" (ext-mesh OFFP rail))
            (str "extrude must reject a th" a "° start"))
        (is (thrown-with-msg? js/Error #"begin with a turn" (loft-mesh OFFP rail))
            (str "loft must reject a th" a "° start"))))))

(deftest non-sweep-consumers-are-exempt
  ;; SCOPE GUARD: the invariant is a property of a path USED AS A SWEEP RAIL, so it
  ;; lives only at extrude/loft consumption — NOT at path construction, NOT in
  ;; non-sweep consumers. text-on-path legitimately takes paths whose initial
  ;; tangent differs from the heading (e.g. (arc-h 50 180) in the manual). Building
  ;; such a path and running text-on-path must NOT raise the rail error.
  (testing "text-on-path on an off-tangent path does not trip the sweep invariant"
    (let [{:keys [error]} (h/eval-dsl
                           "(text-on-path \"X\" (path (arc-h 50 180)) :size 8 :depth 2)")]
      (is (or (nil? error)
              (not (re-find #"begin with a turn|begin with a twist" (str error))))
          (str "text-on-path must be exempt from the rail invariant; got: " error)))
    ;; and constructing an off-tangent path is itself fine (no eager validation)
    (let [{:keys [error]} (h/eval-dsl "(path (th 45) (f 20))")]
      (is (nil? error) (str "constructing an off-tangent path must not error; got: " error)))))

;; ════════════════════════════════════════════════════════════════════
;; FAMILY 3 — ARC loft/extrude ASYMMETRY
;; extrude has the :arc-cap :lead carve-out (extrusion.cljs:1458-1463) → the first
;; section stays perpendicular to the INCOMING heading despite the leading arc
;; half-step → CONFORMANT (GREEN now). loft lacks the carve-out (loft.cljs:583
;; only) → the half-step rolls the first section → VIOLATES (RED now). A coarse
;; arc (:steps 4) makes the gap 11.25° (resolution-robust), not a 0.7° hair.
;; These two guard that the carve-out is present on BOTH operators.
;; POLICY BUCKET: LEGITIMATE-AND-CORRECTED — an arc head is a valid path (NOT a
;; violation: its true tangent IS the incoming heading; the half-step is a
;; tessellation artifact the carve-out absorbs, which is why it does not trip the
;; sweep-invariant error). Both operators must coincide with stamp.
;; ════════════════════════════════════════════════════════════════════

(deftest f3-arc-head-extrude-conformant
  (testing "arc-h head via EXTRUDE — carve-out keeps first section aligned with stamp (GREEN)"
    (assert-coincide "F3 extrude / arc-head" OFFP (ext-mesh OFFP ARC-HEAD))))

(deftest f3-arc-head-loft-conformant
  ;; Part 1 of the fix carried the carve-out into loft (loft.cljs start-cap-state):
  ;; was rolled 11.25° / rms 1.182, now coincides (0 / 0 / 0).
  (testing "arc-h head via LOFT — carve-out now ported, first section aligned with stamp (GREEN after Part 1)"
    (assert-coincide "F3 loft / arc-head" OFFP (loft-mesh OFFP ARC-HEAD))))

;; ════════════════════════════════════════════════════════════════════
;; WHY off-centre asymmetric — symmetric-profile blindness (diagnostic, GREEN)
;; ════════════════════════════════════════════════════════════════════

(deftest symmetric-profile-is-blind
  ;; Evidence for the non-negotiable off-centre constraint, shown directly on the
  ;; stamp rings — the swept mesh that would exhibit it now RAISES the invariant
  ;; error (Family 2), so it cannot be built to measure. Rolling a profile's
  ;; section about the heading (the bug's roll) leaves a symmetric circle's
  ;; centroid AND normal unmoved — BLIND — while the off-centre L's centroid moves.
  ;; A net built on circles would sit green over the very defect this net exists
  ;; for; that is why every fidelity test above uses the off-centre L.
  (testing "a symmetric profile hides the rotation that the off-centre profile exposes"
    (let [roll90 (fn [ring] (mapv (fn [[x y z]] [x (- z) y]) ring)) ; 90° about the heading (x)
          c (ext/stamp-shape (turtle/make-turtle) CIRC)
          l (ext/stamp-shape (turtle/make-turtle) OFFP)
          shift (fn [ring] (dst (cen ring) (cen (roll90 ring))))]
      (println (str "\n  -- off-centre constraint -- centroid shift under a roll:"
                    "  circle=" (.toFixed (shift c) 3) "  off-centre L=" (.toFixed (shift l) 3)))
      (is (< (shift c) 0.01) "circle centroid is BLIND to a roll about the heading (why circles can't be used)")
      (is (> (shift l) 1.0)  "off-centre L centroid EXPOSES the roll"))))

;; ════════════════════════════════════════════════════════════════════
;; FAMILY 4 — ANALYTIC BEZIER VEER (rail-start-tangent brief, 2026-07-04)
;; validate-rail-start-frame! used to measure the FIRST TESSELLATED CHORD of a
;; bezier head, not the analytic tangent. A bezier whose c1 sits exactly along
;; the entry heading (:local right/up = 0) is tangent BY CONSTRUCTION, but on a
;; sharply-curved start the first chord veers off it — a resolution artifact
;; (shrinks as :steps grows) that used to trip the 1° tolerance. The fix tags
;; the leading th/tv with the ANALYTIC veer (angle of c1−p0 vs entry heading,
;; computed once at record time, pose- and resolution-independent) and the
;; guard checks that instead of the tessellated chord.
;; ════════════════════════════════════════════════════════════════════

;; brief repro, first leg only (dev-docs/brief-rail-start-tangent.md:12):
;; c1 = [0 0 24.63] :local — right/up components are 0, pure heading → exact
;; analytic tangent — yet curved enough that the first chord reads ~3.8°/2.6°
;; off at 16/24 steps (both over the 1° tolerance) before this fix.
(defn- tangent-bez-storto [steps]
  (str "(path (bezier-to [0 -8.24 73.42] [0 0 24.63] [0 -28.37 59.61] :local :steps " steps "))"))

;; c1 at exactly 10° off the entry heading in the (right, heading) plane —
;; sin(10°)=0.1736, cos(10°)=0.9848, scaled to length 10 — a REAL violation
;; that analytic measurement must still catch regardless of :steps.
(def BEZ-10DEG "(path (bezier-to [30 5 30] [1.736 0 9.848] [20 0 20] :local))")

(deftest f4-tangent-bezier-resolution-independent
  ;; THE regression test for the false positive: write this FIRST — it must
  ;; FAIL against the pre-fix chord measurement (both extrude and loft raise
  ;; "begin with a turn" at :steps 8, since the chord-veer only shrinks, never
  ;; vanishes, with resolution) and PASS once the guard reads the analytic veer.
  (testing "analytically-tangent bezier head coincides with stamp at low AND high resolution"
    (doseq [steps [8 64]]
      (let [rail (tangent-bez-storto steps)]
        (assert-coincide (str "F4 extrude / tangent-bezier steps=" steps) OFFP (ext-mesh OFFP rail))
        (assert-coincide (str "F4 loft / tangent-bezier steps=" steps) OFFP (loft-mesh OFFP rail))))))

(deftest f4-bezier-10deg-off-axis-still-rejected
  ;; The guard must not be weakened: a bezier head genuinely off-tangent by 10°
  ;; (well over the 1° tolerance) is rejected at any resolution.
  (testing "a bezier head 10° off the entry heading is a DIRECTION violation"
    (is (thrown-with-msg? js/Error #"begin with a turn" (ext-mesh OFFP BEZ-10DEG)))
    (is (thrown-with-msg? js/Error #"begin with a turn" (loft-mesh OFFP BEZ-10DEG)))))

(deftest f4-tangent-bezier-low-res-watertight
  ;; Trap (brief Verifica #3): the cap carve-out excludes the bezier's leading
  ;; th/tv from the frame check, so the first ring stamps perpendicular to the
  ;; incoming heading while the spine advances along the (slightly deviated)
  ;; first chord — same accepted compromise as the arc carve-out. At very
  ;; coarse resolution this must still produce a clean, watertight mesh with no
  ;; fold at the first ring, not just "doesn't throw".
  (testing ":steps 4 on a sharply-curved tangent bezier stays watertight, no fold at the first ring"
    (doseq [[label mesh] [["extrude" (ext-mesh OFFP (tangent-bez-storto 4))]
                          ["loft"    (loft-mesh OFFP (tangent-bez-storto 4))]]]
      (let [diag (mu/mesh-diagnose mesh)]
        (is (:is-watertight? diag) (str label " :steps 4 — mesh must stay watertight; diag=" diag))
        (is (zero? (:non-manifold-edges diag)) (str label " :steps 4 — no non-manifold edges; diag=" diag))
        (is (zero? (:degenerate-faces diag)) (str label " :steps 4 — no degenerate/folded faces; diag=" diag))))))
