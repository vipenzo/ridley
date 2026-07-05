(ns ridley.turtle.loft-smooth-spine-drift-net-test
  "NET for the SMOOTH-branch inner-pivot spine drift in loft (accertamento
   2026-06-23, follow-up to the corner-assembly chain). KEEP SEPARATE from
   loft_corner_assembly_net_test.cljs: that net guards the CORNER branch (a
   per-piece-assembly root, tappe 1-2); THIS net guards the SMOOTH branch, an
   INDEPENDENT bug. Conflating them would undo the distinction the accertamento
   established.

   WHAT THE ACCERTAMENTO FOUND (loft.cljs:981-1011, the `else` of has-corner):
   on a smooth (bezier) rail, every per-step heading change > ~1.15° inserts
   `generate-round-corner-rings` inner-pivot transition rings, then advances the
   spine to the CENTROID of the last such ring (loft.cljs:999-1000) instead of to
   the rail point `corner-base`. The pivot sits `shape-radius` off the ring
   centroid, so the spine drifts 2·R·sin(α/2) per step — linear in the profile
   radius, present already at a single heading change, accumulated along the
   curve. The branch is ALREADY a continuous build (one build-sweep-mesh at
   flush), so this is NOT the corner-assembly root: the recommended fix is
   REMOVAL of the inner-pivot (spine continues from corner-base; the per-step
   bezier sections are already the smooth sweep).

   Only `bezier-to` tags its tessellated th/tv `:smooth` (macros.cljs:426-427) →
   the smooth branch. `arc-h`/`arc-v` emit PLAIN th (macros.cljs:132) → hard
   corners → the OTHER branch. So every rail here is a bezier.

   TOOL DISCIPLINE — all topology via mesh-diagnose / index-based edge incidence
   (:non-manifold-edges, :open-edges, :degenerate-faces, :is-watertight?,
   :edge-incidence-distribution), NEVER Manifold-WASM `manifold?`. Overlap is
   geometry the WASM validator tolerates and the index-based metric is meant to
   expose.

   STATUS: the inner-pivot removal LANDED (loft.cljs smooth branch, 2026-06-23).
   This net was written red-as-a-compass; it is now GREEN and stays as the
   permanent regression guard. The three families and how the fix resolved them:

     Family 1 — drift guard.  Was TARGET (red), NOW GREEN. The spine follows the
       rail within a tight tolerance independent of profile size. Every case
       flipped to 0.0000 (the extrude reference): 2.0 / 30.0 / 366 / 20.1 → 0.
       (One case was always green: the dormant lower guard — see below.)
       A clarification this net makes: the accertamento's 'profilo r=1 nessun
       drift' is really about CURVATURE, not profile size — on a curved rail even
       r=1 drifted (2.0); the truly-zero case is a rail gentle enough that no
       per-step heading exceeds the ~1.15° inner-pivot threshold (branch dormant).

     Family 2 — tight-curve overlap (THE BIVIO).  Was DIAGNOSTIC BASELINE, NOW a
       resolved post-removal guard. Calibrated against extrude (fold-count 0 on
       this tight curve). VERDICT: REMOVAL COMPLETE — the loft fold dropped 34→0
       with no degenerate geometry, so the fold was the inner-pivot's own and no
       residual auto-intersection surfaced at this profile/curvature.

     Family 3 — gentle-curve shape coincidence.  REFERENCE. The dormant path is
       byte-identical through the removal (the old dormant code already equalled
       the post-removal behaviour): bbox + face count unchanged."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.editor.sci-harness :as h]
            [ridley.editor.operations :as ops]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.shape-fn :as sfn]
            [ridley.turtle.extrusion :as extrusion]
            [ridley.geometry.mesh-utils :as mu]))

;; ── helpers (all topology via mesh-diagnose, never manifold?) ───────

(defn- rail [code] (:result (h/eval-dsl code)))

(defn- loft-mesh
  "Plain constant-profile loft along a bezier rail → hits the smooth branch with
   the inner-pivot active (dual-ring? / has-holes? both false)."
  [prof railcode]
  (ops/pure-loft-path prof (fn [s _t] s) (rail railcode) 64))

(defn- extrude-mesh
  "Reference sweep along the SAME rail. extrude-from-path follows the rail with
   NO drift (accertamento), so its last-ring centroid = the true rail endpoint."
  [prof railcode]
  (ops/pure-extrude-path prof (rail railcode)))

(defn- last-ring-centroid
  "Vertices of a :sweep mesh are laid out ring-by-ring with n-verts each and caps
   fan from existing ring vertices (no new vertices), so the final n-verts are the
   last ring. For a symmetric profile its centroid IS the spine end exactly; for
   an asymmetric one the in-frame profile-centroid term is identical to the
   extrude reference (same profile, same final frame) and cancels in the drift."
  [mesh n]
  (let [vs (:vertices mesh)
        ring (subvec (vec vs) (- (count vs) n))
        s (reduce (fn [[ax ay az] [x y z]] [(+ ax x) (+ ay y) (+ az z)]) [0 0 0] ring)]
    (mapv #(/ % n) s)))

(defn- dist [[ax ay az] [bx by bz]]
  (Math/sqrt (+ (* (- ax bx) (- ax bx)) (* (- ay by) (- ay by)) (* (- az bz) (- az bz)))))

(defn- spine-drift
  "Distance between the loft's spine end and the rail end (extrude reference),
   on the same bezier rail + same profile. n = profile vertex count."
  [prof railcode n]
  (let [lm (loft-mesh prof railcode)
        em (extrude-mesh prof railcode)]
    (dist (last-ring-centroid lm n) (last-ring-centroid em n))))

(defn- d [mesh]
  (if (and (map? mesh) (seq (:vertices mesh)))
    (let [x (mu/mesh-diagnose mesh)]
      {:f (count (:faces mesh)) :nm (:non-manifold-edges x)
       :oe (:open-edges x) :wt (:is-watertight? x) :deg (:degenerate-faces x)
       :dist (:edge-incidence-distribution x)})
    {:err "nil/empty mesh"}))

(defn- bbox [mesh]
  (mapv (fn [ax] (let [xs (map #(nth % ax) (:vertices mesh))]
                   [(/ (Math/round (* 1000 (apply min xs))) 1000.0)
                    (/ (Math/round (* 1000 (apply max xs))) 1000.0)]))
        [0 1 2]))

;; off-centre profile: big extent AND a centroid pushed off the rail. The
;; inner-pivot keys its pivot off the 3D ring centroid, so an off-rail centroid
;; displaces the pivot and AMPLIFIES the spine drift — the worst case, and the
;; geometry the bug emerged on.
(defn- offset-circle [r seg cx cy]
  (shape/make-shape
   (vec (for [i (range seg)]
          (let [a (* i (/ (* 2 Math/PI) seg))]
            [(+ cx (* r (Math/cos a))) (+ cy (* r (Math/sin a)))])))
   {:centered? false}))

;; Rails (all bezier → smooth branch). Auto-tessellated by the recorder; loft and
;; extrude consume the SAME path object so their rings align step-for-step.
(def C-RAIL   "180° U-turn sweep, curvature radius ~30 (drift visible, not yet 'tight')"
  "(path (bezier-to [0 60 0] [60 0 0] [60 60 0]))")
(def DORMANT-RAIL "shallow bend whose per-step heading stays below the ~1.15° inner-pivot threshold → branch dormant, drift 0 today"
  "(path (bezier-to [60 12 0] [25 0 0] [45 4 0]))")
(def TIGHT-RAIL  "hairpin fold, curvature radius ~6 (below the test profile radius)"
  "(path (bezier-to [0 10 0] [30 0 0] [30 10 0]))")

;; ════════════════════════════════════════════════════════════════════
;; FAMILY 1 — SPINE DRIFT GUARD
;; CONVENTION: was TARGET (red until the inner-pivot was removed). NOW GREEN —
;; a permanent regression guard that the spine follows the rail. The removal
;; (loft.cljs smooth branch, 2026-06-23) flipped every case to 0.0000 — the
;; extrude reference level, exactly: pre-fix drift → post-fix drift was
;;   r=1   C-rail        2.0000  → 0.0000
;;   r=15  C-rail       29.9963  → 0.0000
;;   off-centre r=10   366.3881  → 0.0000
;;   tapered r=15→0.3   20.1499  → 0.0000
;;   dormant r=15        0.0000  → 0.0000 (byte-stable, never fired)
;; Tolerance is ABSOLUTE on a rail spanning ~60 units; it must be independent of
;; profile size (that independence is the contract the bug violated).
;; ════════════════════════════════════════════════════════════════════

(def ^:private DRIFT-TOL
  "Spine must sit on the rail to within this many units, whatever the profile.
   Post-fix every case reads 0.0000 (extrude level); this leaves wide margin for
   bezier-tessellation noise while catching any drift regression (the bug ran
   ≥2 even at r=1, up to ~366 asymmetric)."
  0.5)

(defn- assert-on-rail [label prof railcode]
  (let [n (count (:points prof))
        dr (spine-drift prof railcode n)]
    (println (str ">>> F1 " label ": spine drift = " (.toFixed dr 4)
                  "  (R=" (.toFixed (extrusion/shape-radius prof) 2)
                  ", TARGET < " DRIFT-TOL ")"))
    (is (< dr DRIFT-TOL)
        (str label " — spine must stay on rail (<" DRIFT-TOL "); drift="
             (.toFixed dr 4) " (regression guard: smooth-branch inner-pivot drift, ∝R)"))
    dr))

(deftest f1-smallest-profile-still-drifts
  ;; The SMALLEST profile (r=1) on the curved rail: drift is NOT zero — it is the
  ;; lower end of the ∝R line (~2.0 here, vs ~30 at r=15 → ratio 15 = the radius
  ;; ratio). This corrects the 'r=1 nessun drift' phrasing: the bug fires at every
  ;; profile size once the branch is active; small only means small, not absent.
  (testing "smallest circle r=1 on the curved bezier rail — drift small but present (∝R lower end)"
    (assert-on-rail "circle r=1 / C-rail" (shape/circle-shape 1 24) C-RAIL)))

(deftest f1-large-symmetric-profile
  ;; Large symmetric profile: drift ∝ R (was 30.0, now 0). The headline case.
  (testing "large circle r=15 on a curved bezier rail — drift large today"
    (assert-on-rail "circle r=15 / C-rail" (shape/circle-shape 15 32) C-RAIL)))

(deftest f1-large-asymmetric-profile
  ;; THE CASE THE BUG EMERGED ON: large AND centroid pushed off the rail. The
  ;; pivot keyed off the 3D ring centroid, so the off-rail centroid amplified the
  ;; drift beyond the symmetric case — the worst case (was 366, now 0).
  (testing "large off-centre profile on a curved bezier rail — worst-case drift"
    (assert-on-rail "offset-circle r=10@(20,0) / C-rail" (offset-circle 10 32 20 0) C-RAIL)))

(deftest f1-tapered-real-trigger
  ;; Honours the real trigger named in the accertamento: a taper/twist/morph loft
  ;; (not just constant profile) along a bezier still hits the same branch. The
  ;; profile is symmetric, so its ring centroid is the spine end at any taper
  ;; scale → the extrude (base profile) reference still gives the rail endpoint.
  (testing "tapered large circle (shape-fn) on a curved bezier rail"
    (let [efn (sfn/tapered (shape/circle-shape 15 32) :to 0.3)
          lm  (ops/pure-loft-shape-fn efn (rail C-RAIL) 64)
          em  (extrude-mesh (shape/circle-shape 15 32) C-RAIL)
          n   32
          dr  (dist (last-ring-centroid lm n) (last-ring-centroid em n))]
      (println (str ">>> F1 tapered circle r=15→0.3 / C-rail: spine drift = " (.toFixed dr 4)
                    "  (TARGET < " DRIFT-TOL ")"))
      (is (< dr DRIFT-TOL)
          (str "tapered loft — spine must stay on rail (<" DRIFT-TOL "); drift="
               (.toFixed dr 4) " (regression guard: inner-pivot fired regardless of transform)")))))

(deftest f1-dormant-rail-lower-guard
  ;; LOWER GUARD — CONVENTION: GREEN NOW, must STAY green. This is the literal
  ;; 'drift oggi trascurabile, deve restare tale' case. On a rail gentle enough
  ;; that no per-step heading reaches the ~1.15° threshold, the inner-pivot never
  ;; fires → drift is 0 TODAY, for ANY profile size (drift is curvature-driven,
  ;; not profile-driven — here a LARGE r=15 profile on the dormant rail still
  ;; reads 0). Removal must keep this at 0 (must not introduce drift where the
  ;; branch was dormant). Unlike the four cases above, this one is NOT red now.
  (testing "large profile on a sub-threshold (dormant) bezier rail — drift 0 today, must stay 0"
    (assert-on-rail "circle r=15 / dormant-rail" (shape/circle-shape 15 32) DORMANT-RAIL)))

;; ════════════════════════════════════════════════════════════════════
;; FAMILY 2 — TIGHT-CURVE OVERLAP (THE BIVIO) — RESOLVED
;; CONVENTION: was DIAGNOSTIC BASELINE; NOW a post-removal regression guard.
;;
;; THE BIVIO (the heart of the fix session) and its VERDICT: the inner-pivot was
;; supposedly added to protect against ring overlap on the INNER side of tight
;; curves (curvature radius below the profile radius). The two outcomes were:
;;   • stays clean (fold→extrude level, no degenerate) → the fold was the
;;     inner-pivot's own; the build-continuous smooth branch doesn't suffer it;
;;     REMOVAL IS COMPLETE.
;;   • gains degenerate / fold stays high → a REAL assembly-independent geometric
;;     auto-intersection (profile too big for the rail's bend), handle SEPARATELY.
;;
;; MEASURED VERDICT — REMOVAL COMPLETE. Calibration first: extrude on this exact
;; tight curve gives spine-fold-count = 0 (the sanity reference — it follows the
;; rail). Pre-removal the loft folded 34× (the inner-pivot was already failing to
;; protect the case it was added for). Post-removal the loft reads fold = 0 — down
;; to the extrude reference — with degenerate=0, nm=0, open-edges=0, watertight.
;; The ondeggiamento was entirely the inner-pivot's; the spine now follows the
;; rail; NO residual geometric auto-intersection surfaced. (Had fold stayed high,
;; the out-of-scope flank — self-intersecting bezier logic — would apply; it does
;; not arise here at this profile/curvature.)
;;
;; NOTE on detection: a regular sweep grid stays TOPOLOGICALLY manifold even when
;; its geometry folds through itself (every interior edge keeps incidence 2), so
;; nm/oe read clean regardless. The geometric signatures that move are
;; :degenerate-faces and spine-fold (centroid steps reversing along the heading)
;; — both index-based. spine-fold is the metric that exposed the fold (34) and
;; now confirms its disappearance (0).
;; ════════════════════════════════════════════════════════════════════

(defn- face-normal [vs [a b c]]
  (let [[ax ay az] (nth vs a) [bx by bz] (nth vs b) [cx cy cz] (nth vs c)
        ux (- bx ax) uy (- by ay) uz (- bz az)
        vx (- cx ax) vy (- cy ay) vz (- cz az)
        nx (- (* uy vz) (* uz vy)) ny (- (* uz vx) (* ux vz)) nz (- (* ux vy) (* uy vx))
        m (Math/sqrt (+ (* nx nx) (* ny ny) (* nz nz)))]
    (if (< m 1e-9) [0 0 0] [(/ nx m) (/ ny m) (/ nz m)])))

(defn- ring-centroids
  "Per-ring centroids of a :sweep mesh (n verts/ring, caps add no verts)."
  [mesh n]
  (let [vs (vec (:vertices mesh))
        k  (quot (count vs) n)]
    (vec (for [r (range k)]
           (let [ring (subvec vs (* r n) (* (inc r) n))
                 s (reduce (fn [[ax ay az] [x y z]] [(+ ax x) (+ ay y) (+ az z)]) [0 0 0] ring)]
             (mapv #(/ % n) s))))))

(defn- spine-fold-count
  "Count consecutive centroid steps that REVERSE direction (dot < 0) — the spine
   doubling back, the index-based signature of an inner-side fold."
  [mesh n]
  (let [cs (ring-centroids mesh n)
        steps (mapv (fn [a b] [(- (b 0) (a 0)) (- (b 1) (a 1)) (- (b 2) (a 2))])
                    cs (rest cs))]
    (count (filter (fn [[u v]]
                     (neg? (+ (* (u 0) (v 0)) (* (u 1) (v 1)) (* (u 2) (v 2)))))
                   (map vector steps (rest steps))))))

(defn- backfacing-count
  "Faces whose normal opposes the local sweep direction (centroid trend) → folded
   side wall pointing inward."
  [mesh n]
  (let [vs (vec (:vertices mesh))
        cs (ring-centroids mesh n)
        sweep (let [a (first cs) b (last cs)] [(- (b 0) (a 0)) (- (b 1) (a 1)) (- (b 2) (a 2))])
        sm (Math/sqrt (reduce + (map * sweep sweep)))
        sweepn (if (pos? sm) (mapv #(/ % sm) sweep) [1 0 0])]
    (count (filter (fn [f]
                     (let [nrm (face-normal vs f)]
                       (< (+ (* (nrm 0) (sweepn 0)) (* (nrm 1) (sweepn 1)) (* (nrm 2) (sweepn 2)))
                          -0.999)))
                   (:faces mesh)))))

(deftest f2-tight-curve-overlap-resolved
  (testing "RESOLVED (inner-pivot REMOVED): big profile on a bezier fold, curvature radius < profile radius — spine clean"
    (let [prof  (shape/circle-shape 10 32)
          n     32
          lm    (loft-mesh prof TIGHT-RAIL)
          em    (extrude-mesh prof TIGHT-RAIL)        ; sanity reference (follows the rail)
          rr    (d lm)
          fold  (spine-fold-count lm n)
          eref  (spine-fold-count em n)               ; the calibration target
          back  (backfacing-count lm n)]
      (println "\n==== FAMILY 2 — TIGHT-CURVE OVERLAP RESOLVED (inner-pivot removed) ====")
      (println (str "  rail = " TIGHT-RAIL))
      (println (str "  profile = circle r=10 (curvature radius ~6 < R)"))
      (println (str "  CALIBRATION: extrude spine-fold-count=" eref " (sanity reference)"))
      (println (str "  mesh-diagnose: nm=" (:nm rr) " open-edges=" (:oe rr)
                    " degenerate=" (:deg rr) " watertight=" (:wt rr)))
      (println (str "  edge-incidence-distribution=" (:dist rr)))
      (println (str "  loft spine-fold-count=" fold " (was 34 with inner-pivot)  backfacing-faces=" back))
      (println "  VERDICT: REMOVAL COMPLETE — fold 34→0 = extrude reference, no degenerate.")
      (println "  The fold was the inner-pivot's; the spine now follows the rail. No residual")
      (println "  auto-intersection at this profile/curvature (had fold stayed high → separate fix).")
      ;; Post-removal regression guard. The headline is fold == extrude reference
      ;; (0) — the spine no longer doubles back — with topology not regressing.
      (is (nil? (:err rr)) (str "tight-curve loft must build: " (:err rr)))
      (is (= 0 eref)       "calibration: extrude follows the rail with fold-count 0 on this tight curve")
      (is (= eref fold)    (str "spine-fold-count must drop to the extrude reference (" eref
                                "); was 34 with inner-pivot, now " fold))
      (is (= 0 (:deg rr))  "no degenerate faces — removal uncovered no geometric auto-intersection")
      (is (= 0 (:nm rr))   "topology must not regress: non-manifold edges = 0")
      (is (= 0 (:oe rr))   "topology must not regress: open edges = 0")
      (is (= true (:wt rr)) "tight-curve loft stays watertight")
      (is (= 0 back)       "no inward-folded side walls"))))

;; ════════════════════════════════════════════════════════════════════
;; FAMILY 3 — GENTLE-CURVE SHAPE COINCIDENCE
;; CONVENTION: REFERENCE. Pins shape (bbox + face count) on the gentle case where
;; drift is already trivial. Why the DORMANT rail is the RIGHT 'dolce' case and
;; the guarantee is exact, not vacuous: when the per-step heading stays below the
;; ~1.15° threshold, heading-angle is nil → no inner-pivot rings are spliced AND
;; the spine already continues from corner-base (loft.cljs:992,1001) — i.e. the
;; CURRENT dormant path ALREADY equals the post-removal behaviour. So removal must
;; leave this bbox AND face count byte-identical; a change would mean the removal
;; touched more than the firing branch. (On FIRING rails the shape necessarily
;; changes — the inner-pivot always drifts, never just smooths, so its removal
;; shrinks the envelope toward the extrude reference; that is Family 1, not a
;; shape-preservation regression. There is no firing rail where rings are present
;; yet removal preserves the shape — the accertamento established they only add
;; drift + redundant rings.)
;; ════════════════════════════════════════════════════════════════════

(deftest f3-gentle-curve-shape-reference
  (testing "REFERENCE (inner-pivot active): dormant gentle bezier — pin bbox + faces (must be byte-stable through removal)"
    (let [lm (loft-mesh (shape/circle-shape 8 24) DORMANT-RAIL)
          bb (bbox lm)
          nf (count (:faces lm))]
      (println "\n==== FAMILY 3 — GENTLE-CURVE SHAPE REFERENCE (inner-pivot active) ====")
      (println (str "  bbox=" bb " faces=" nf))
      (println "  Dormant path already == post-removal behaviour → removal must leave these IDENTICAL.")
      (is (map? lm) "gentle loft must build")
      ;; Pinned reference values measured with the inner-pivot ACTIVE (current
      ;; code). Because the dormant path already equals post-removal behaviour,
      ;; the fix brief should find these UNCHANGED — a change is a red flag that
      ;; removal reached beyond the firing branch.
      ;;
      ;; UPDATED 2026-07-04 (rail-start-tangent brief): x moved -0.02 → 0. This
      ;; rail's leading bezier th is a sub-1° tessellated-chord veer around an
      ;; analytically-exact-tangent c1 (dev-docs/brief-rail-start-tangent.md) —
      ;; exactly the artifact that brief's Part 1 fix excludes from the start
      ;; cap's frame, same carve-out arc heads already had. The cap now stamps
      ;; perfectly flush with the incoming heading (x=0) instead of tilted by
      ;; that leftover chord sliver. Unrelated to inner-pivot; face count (this
      ;; net's actual subject) is unchanged.
      (is (= [[0 63.727] [-8.0 19.079] [-8.0 8.0]] bb)
          (str "REFERENCE bbox (inner-pivot active); CURRENT " bb))
      (is (= 3116 nf)
          (str "REFERENCE face count (inner-pivot active); CURRENT " nf)))))
