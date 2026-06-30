(ns ridley.turtle.corner-self-intersection-net-test
  "NET for GEOMETRIC SELF-INTERSECTION at a sweep corner — the THIRD guarantee
   family (accertamento 2026-06-25, `corner-self-intersection = the real disease`).
   The two families that already exist guard different things:
     • topological sanity  — non-manifold / watertight / open-edge counts
       (loft-corner-assembly-net, via mesh-diagnose).
     • input/rail fidelity — the first section coincides with `stamp`, the rail
       begins in the turtle's frame (shape-fidelity-net, loft-smooth-spine-drift).
   Neither sees what this net sees. When a profile is too WIDE for the bend of its
   rail, the section AFTER the corner folds back across the inner side of the turn
   and pierces the tube BEFORE the corner. That is a GEOMETRIC self-intersection:
   the surface passes through itself. It is topologically INVISIBLE — the mesh stays
   a closed orientable 2-manifold, so `mesh-diagnose` reads nm=0 / watertight=true
   on every broken case here. `the-blindness-of-mesh-diagnose` below demonstrates
   that directly: it asserts nm=0 on the same meshes this net flags as pierced. A
   net built on mesh-diagnose would sit GREEN over the entire defect — which is
   exactly why this family had to be built new.

   THE GOVERNING QUANTITY (diagnosis, already established): the miter-shortening
   R·tan(θ/2) — how far back the inner edge of the after-corner section has to move
   to meet the inner edge of the before-corner section — measured against the
   adjacent segment length. It is ALREADY computed in both constructors:
   `effective-dist` (extrusion.cljs:1389) and `calculate-loft-corner-shortening`
   (loft.cljs:125). When the miter exceeds the segment, effective-dist goes
   NEGATIVE and the after-corner section is placed BEHIND where it should be — the
   fold. The constructor computes the collision's signature and ignores its sign.

   THE MEASURED MATRIX (accertamento, the fixture this net promotes):
   profile `(circle R 24)`, rail `(f 10)(th 89)(f 20)`, planar. (Counts below are
   THIS file's strict-interior test, re-measured by `aaa-measured-matrix`; the
   accertamento reported ~19 at R=21 and ~42 at R=50 with a slightly looser EPS —
   the threshold R≈21 and the θ-dependence reproduce exactly, the absolute counts
   sit ~2 lower here. The contract asserts pairs=0 vs pairs>0, never an exact n.)
     • R ≤ 20 : healthy, tri-tri pairs = 0.
     • R ≈ 11–20 : GREY ZONE. effective-dist already < 0 from R≈11 (the segment
       is inverted — over-mitred) but the triangles do not yet cross FRANKLY (pairs
       stays 0 through R=20); the two thresholds — sign of effective-dist vs frank
       triangle crossing — are distinct, see the brief Part 2.
     • R ≥ 21 : FRANK self-intersection. 17 tri-tri pairs at R=21, rising with R
       (40 at R=50). The reds below assert pairs > 0 and DOCUMENT the count; they
       are EXPECTED-RED — they describe the current defect and stay red until a cure
       (miter-join or the upstream guard short-circuiting the build) lands, exactly
       like the loft-corner-assembly net's reds before its fix.

   THE TOOL — a strict-interior Möller–Trumbore edge/triangle piercing test (the
   accertamento's, validated against the matrix). Two triangles 'cross' iff an edge
   of one pierces the STRICT interior of the other: barycentric u,v,w and the edge
   parameter t all bounded away from 0/1 by EPS. The strictness is the whole point —
   adjacent mesh triangles legitimately share edges and vertices, and a non-strict
   test would count every shared edge as an intersection. Pairs that share any
   coincident vertex (an adjacency, within 1e-4) are skipped outright."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.editor.sci-harness :as h]
            [ridley.editor.operations :as ops]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.shape-fn :as sfn]
            [ridley.turtle.extrusion :as ext]
            [ridley.geometry.mesh-utils :as mu]))

;; ── strict-interior Möller–Trumbore edge/triangle pierce ─────────────
(def ^:private EPS 1e-7)
(defn- v- [a b] (mapv - a b))
(defn- vx [[ax ay az] [bx by bz]]
  [(- (* ay bz) (* az by)) (- (* az bx) (* ax bz)) (- (* ax by) (* ay bx))])
(defn- vd [a b] (reduce + (map * a b)))

(defn- seg-pierces-tri?
  "Möller–Trumbore: does the segment p0→p1 pierce triangle (v0 v1 v2) in its
   STRICT interior? u,v,(u+v) and the segment parameter t are all required to sit
   strictly inside (EPS, 1-EPS) — so a segment merely touching an edge/vertex (the
   normal adjacency of a watertight mesh) does NOT count, only a frank crossing."
  [p0 p1 v0 v1 v2]
  (let [dir (v- p1 p0)
        e1 (v- v1 v0)
        e2 (v- v2 v0)
        pv (vx dir e2)
        det (vd e1 pv)]
    (if (< (Math/abs det) EPS)
      false                                   ; segment parallel to the triangle
      (let [inv (/ 1.0 det)
            tv (v- p0 v0)
            u  (* (vd tv pv) inv)]
        (if (or (<= u EPS) (>= u (- 1 EPS)))
          false
          (let [qv (vx tv e1)
                v  (* (vd dir qv) inv)]
            (if (or (<= v EPS) (>= (+ u v) (- 1 EPS)))
              false
              (let [t (* (vd e2 qv) inv)]
                (and (> t EPS) (< t (- 1 EPS)))))))))))

(defn- tris-cross?
  "Two triangles cross iff any of their 6 edges pierces the other's strict interior."
  [[a0 a1 a2] [b0 b1 b2]]
  (or (seg-pierces-tri? a0 a1 b0 b1 b2) (seg-pierces-tri? a1 a2 b0 b1 b2)
      (seg-pierces-tri? a2 a0 b0 b1 b2) (seg-pierces-tri? b0 b1 a0 a1 a2)
      (seg-pierces-tri? b1 b2 a0 a1 a2) (seg-pierces-tri? b2 b0 a0 a1 a2)))

(defn- aabb [[p q r]]
  [(mapv min p q r) (mapv max p q r)])
(defn- aabb-miss? [[lo1 hi1] [lo2 hi2]]
  (or (< (hi1 0) (lo2 0)) (> (lo1 0) (hi2 0))
      (< (hi1 1) (lo2 1)) (> (lo1 1) (hi2 1))
      (< (hi1 2) (lo2 2)) (> (lo1 2) (hi2 2))))

(defn- self-intersection-pairs
  "Count pairs of NON-ADJACENT triangles that cross. Adjacency is by coincident
   vertex position (rounded to 1e-4): faces sharing any vertex are skipped, so only
   triangles that meet through open space — the fold — are counted."
  [mesh]
  (let [vs    (vec (:vertices mesh))
        faces (vec (:faces mesh))
        n     (count faces)
        pkey  (fn [i] (let [[x y z] (nth vs i)]
                        [(Math/round (* 1e4 x)) (Math/round (* 1e4 y)) (Math/round (* 1e4 z))]))
        fkeys (mapv (fn [f] (set (map pkey f))) faces)
        ftris (mapv (fn [f] (mapv #(nth vs %) f)) faces)
        boxes (mapv aabb ftris)]
    (loop [i 0 cnt 0]
      (if (>= i n)
        cnt
        (let [ki (nth fkeys i) bi (nth boxes i) ti (nth ftris i)
              add (loop [j (inc i) c 0]
                    (if (>= j n)
                      c
                      (recur (inc j)
                             (if (and (not (some ki (nth fkeys j)))
                                      (not (aabb-miss? bi (nth boxes j)))
                                      (tris-cross? ti (nth ftris j)))
                               (inc c) c))))]
          (recur (inc i) (+ cnt add)))))))

;; ── builders ────────────────────────────────────────────────────────
(defn- ev [code] (:result (h/eval-dsl code)))
(defn- ext-mesh  [profile railcode] (ops/pure-extrude-path profile (ev railcode)))
(defn- loft-mesh [profile railcode] (ops/pure-loft-path profile (fn [s _t] s) (ev railcode) 64))
(defn- circ [r] (shape/circle-shape r 24))
(defn- shell-mesh
  "Loft a uniform-wall shell (value 1.0 everywhere) of base-radius circle through
   railcode. pure-loft-path emits the side-wall/corner/cap sub-meshes already
   combined and vertex-welded into one mesh."
  [base-r thickness railcode]
  (let [sf (sfn/shell (circ base-r) :thickness thickness :fn (fn [_a _t] 1.0))]
    (ops/pure-loft-shape-fn sf (ev railcode) 64)))
(def ^:private LONG-RAIL "(path (f 40) (th 89) (f 40))")  ; generous legs: outer skin fits

;; the accertamento fixture rail (planar): a 89° turn between a short and a long leg
(def ^:private FIX-RAIL "(path (f 10) (th 89) (f 20))")

;; an off-centre disk: a small circle (radius rr) whose centre sits at (cx,0). Its
;; shape-radius (max dist from CENTROID) is rr — but its extent FROM THE ORIGIN (the
;; rail pivot, where stamp puts [0 0]) is cx+rr. It is the brief's "ribbon to one
;; side": the shape-radius proxy the guard uses underestimates how far the profile
;; reaches across the bend. Off-centre profiles are necessarily :centered? false.
(defn- off-disk [cx rr n]
  (shape/make-shape (vec (for [i (range n)]
                           (let [a (* 2 Math/PI (/ i n))]
                             [(+ cx (* rr (Math/cos a))) (* rr (Math/sin a))])))
                    {:centered? false}))

(defn- refusal
  "Return the guard's error message if building THUNK is refused, else nil."
  [thunk]
  (try (thunk) nil (catch :default e (.-message e))))
(defn- refused? [thunk]
  (let [m (refusal thunk)] (boolean (and m (re-find #"too sharp for how wide" m)))))
(defn- safe-pairs [thunk]
  (try (self-intersection-pairs (thunk)) (catch :default _ :refused)))

;; ════════════════════════════════════════════════════════════════════
;; DIAGNOSTIC — the measured matrix (printed, not asserted). Post-guard: the
;; over-mitred rows are REFUSED at the extrude entry, so the raw disease can no
;; longer be built through the production path; the rows that still build read 0.
;; ════════════════════════════════════════════════════════════════════
(deftest aaa-measured-matrix
  (println "\n==== CORNER MATRIX (post-guard) — circle R / (f 10)(th 89)(f 20) ====")
  (doseq [r [8 10 12 15 18 20 21 25 30 50]]
    (let [p (safe-pairs #(ext-mesh (circ r) FIX-RAIL))]
      (println (str ">>> R=" r "  → "
                    (if (= p :refused) "REFUSED by guard (effective-dist < 0)"
                        (str "BUILT, tri-tri-pairs=" p))))))
  (println "==== θ sweep at R=21 ====")
  (doseq [th [30 60 89 120]]
    (let [p (safe-pairs #(ext-mesh (circ 21) (str "(path (f 10) (th " th ") (f 20))")))]
      (println (str ">>> th=" th "  → " (if (= p :refused) "REFUSED" (str "BUILT, tri-tri=" p))))))
  (is true))

;; ════════════════════════════════════════════════════════════════════
;; GREEN — realizable cases build and do NOT self-intersect. The guard and the
;; cure must keep these. (The disease is θ×R, not R alone: a wide profile is fine
;; through a gentle corner.)
;; ════════════════════════════════════════════════════════════════════
(deftest green-realizable-builds-clean
  (testing "a profile that fits the bend builds clean (pairs=0)"
    (is (zero? (self-intersection-pairs (ext-mesh (circ 8) FIX-RAIL)))
        "circle R=8 fits the 89° corner on a 10-leg")
    (is (zero? (self-intersection-pairs (loft-mesh (circ 8) FIX-RAIL)))
        "loft of the same fitting profile is equally clean"))
  (testing "a WIDE profile is fine through a GENTLE corner (θ×R, not R alone)"
    (is (zero? (self-intersection-pairs (ext-mesh (circ 21) "(path (f 10) (th 30) (f 20))")))
        "R=21 through th30 does not fold (miter 5.6 < 10-leg)"))
  (testing "a WIDE profile is fine through a SHARP corner when the LEGS are LONG"
    ;; the third exit the guard names: lengthen the segment. R=21 @89° folds on a
    ;; 10-unit leg but builds clean on 30-unit legs (miter 20.6 < 30).
    (is (zero? (self-intersection-pairs (ext-mesh (circ 21) "(path (f 30) (th 89) (f 30))")))
        "R=21 @89° on long (30) legs does not fold")))

;; ════════════════════════════════════════════════════════════════════
;; GUARD — the upstream realizability guard (Part 3) refuses the over-mitred
;; corners that used to silently build a self-intersecting tube. Conservative
;; threshold (effective-dist < 0), the developer-chosen policy. Both operators.
;; ════════════════════════════════════════════════════════════════════
(deftest guard-refuses-overmitre
  (testing "extrude refuses the wide-profile sharp corners (the symmetric disease)"
    (is (refused? #(ext-mesh (circ 21) FIX-RAIL)) "R=21 @89° must be refused")
    (is (refused? #(ext-mesh (circ 50) FIX-RAIL)) "R=50 @89° must be refused")
    (is (refused? #(ext-mesh (circ 21) "(path (f 10) (th 120) (f 20))")) "R=21 @120° must be refused"))
  (testing "loft is guarded by the SAME magnitude — it refuses identically"
    (is (refused? #(loft-mesh (circ 21) FIX-RAIL)) "loft R=21 @89° must be refused"))
  (testing "the refusal names the three ways out (round / narrow / lengthen)"
    (let [msg (refusal #(ext-mesh (circ 21) FIX-RAIL))]
      (is (re-find #"(?i)arc" msg)      "message must offer rounding the corner into an arc")
      (is (re-find #"(?i)narrow" msg)   "message must offer narrowing the profile")
      (is (re-find #"(?i)longer" msg)   "message must offer lengthening the segment"))))

;; ════════════════════════════════════════════════════════════════════
;; ESCAPER — RESOLVED by the directional projection fix (2026-06-30). The guard
;; used to size the miter by shape-radius (max from the CENTROID); an off-centre
;; profile reaches far across the bend FROM THE STAMP ORIGIN while keeping a small
;; shape-radius, so the guard let it through and the tube folded. The directional
;; fix (corner-inner-extent: project the STAMPED points onto the corner's inner
;; normal) measures the real reach and sizes the miter correctly — so the escaper
;; now BUILDS CLEAN (tri-tri=0), it is not refused. (Measured: the stamped reach is
;; 8, not the authored-origin 14, so the f10 leg DOES fit once mitred correctly.)
;; REGRESSION GUARD — was a green tripwire asserting the defect; now asserts the cure.
;; ════════════════════════════════════════════════════════════════════
(deftest escaper-now-builds-clean
  (testing "the directional fix sizes the off-centre overhang correctly → clean build"
    (let [prof (off-disk 10 4 24)]            ; shape-radius 4 from centroid; stamped reach 8
      (is (not (refused? #(ext-mesh prof FIX-RAIL)))
          "the off-centre profile is realizable on FIX once mitred on its real reach")
      (is (zero? (self-intersection-pairs (ext-mesh prof FIX-RAIL)))
          "and it builds without self-intersection (was the escaper; directional fix cures it)"))))

;; ════════════════════════════════════════════════════════════════════
;; :centered? false CORNER — SUBSUMED by the directional fix (SIGNAL). Prior sessions
;; treated this as a SEPARATE, out-of-scope mechanism: a :centered? false profile
;; self-intersected at a hard corner even when geometrically centred. MEASURED NOW:
;; it was the SAME proxy defect. compute-stamp-transform offsets a :centered? false
;; profile so its FIRST point sits on the rail, which moves the centroid OFF the rail
;; → an overhang the centroid-based shape-radius under-mitred. The directional fix
;; measures from the stamp origin, so it sizes that overhang correctly and the corner
;; now BUILDS CLEAN. The directional fix therefore resolves the :centered? false
;; corner family too — a scope discovery, not a separate fix. (If a residual
;; :centered? false placement defect ever reappears that is NOT the proxy, it would
;; show here again; today it is gone.)
;; ════════════════════════════════════════════════════════════════════
(deftest centered-false-corner-now-clean
  (let [rail "(path (f 40) (th 89) (f 40))"
        cfalse (off-disk 0 5 24)                    ; :centered? false, centroid AT origin
        ctrue  (shape/circle-shape 5 24)]           ; identical geometry, :centered? true
    (testing ":centered? true still builds clean (unchanged)"
      (is (zero? (self-intersection-pairs (ext-mesh ctrue rail)))
          "the centred circle on the clean stamp path does not self-intersect"))
    (testing ":centered? false now builds clean too (directional fix subsumed it)"
      (is (not (refused? #(ext-mesh cfalse rail)))
          "the corner is realizable and the guard does not fire")
      (is (zero? (self-intersection-pairs (ext-mesh cfalse rail)))
          "the :centered? false overhang is now mitred correctly → no fold"))))

;; ════════════════════════════════════════════════════════════════════
;; WHY THIS NET EXISTS — mesh-diagnose is blind to self-intersection. Demonstrated on
;; a mesh that STILL self-intersects after the fix: a shell at the realizability EDGE
;; (t=0.2/FIX, eff≈0.07) builds with a residual discrete fold (tri-tri>0) yet
;; mesh-diagnose reads a pristine closed 2-manifold (nm=0, watertight). A
;; self-intersection passes the surface through itself while every edge keeps two
;; incident faces — so only the tri-tri net sees it. (This edge residual is a REAL
;; dual-ring corner self-intersection, NOT a proxy failure and NOT a discrete
;; artefact: verified 2026-06-30, tri-tri GROWS with resolution (24→52, 48→60,
;; 96→86), so refining the mesh does not remove it. The directional fix improved it
;; 126→52 but the dual-ring corner at the realizability edge still folds — a known
;; hole, separate follow-up. Used here precisely because it is a current pierced mesh.)
;; ════════════════════════════════════════════════════════════════════
(deftest the-blindness-of-mesh-diagnose
  (testing "a self-intersecting mesh is topologically pristine (nm=0, watertight)"
    (let [m   (shell-mesh 10 0.2 FIX-RAIL)          ; edge shell: builds, tri-tri>0
          dia (mu/mesh-diagnose m)]
      (is (pos? (self-intersection-pairs m))
          "shell at the realizability edge self-intersects (premise of the blindness claim)")
      (is (zero? (:non-manifold-edges dia))
          "mesh-diagnose sees nm=0 on the pierced mesh (BLIND)")
      (is (true? (:is-watertight? dia))
          "mesh-diagnose calls the pierced mesh watertight (BLIND)"))))

;; ════════════════════════════════════════════════════════════════════
;; SHELL CORNER SELF-INTERSECTION — the last broken sweep path
;; (dev-docs/shell-corner-accertamento.md + dev-docs/shell-corner-fix-attempt.md).
;; The plain loft is clean at every realizable corner; SHELL self-intersects at
;; EVERY corner, clean only on a straight rail. MEASURED facts:
;;   • the crossings are OUTER-skin vs OUTER-skin, clustered at the corner; the
;;     INNER skin is uninvolved (no inner-inner, no inner-outer pairs). NOT an
;;     inner-ring fold.
;;   • it is a GEOMETRIC fold (tri-tri > 0) yet topologically pristine — mesh-
;;     diagnose reads nm=0 / watertight on every case here.
;;   • CAUSE (corrected 2026-06-29, see fix-attempt doc): the corner miter is sized
;;     on the BASE radius (shape-radius of the probe = the centreline circle), but
;;     shell's OUTER skin sits at base + thickness/2. The outer skin is therefore
;;     UNDER-mitered and folds back across itself at the bend. This is the
;;     shape-radius PROXY family (the same root as the off-centre escaper above:
;;     the miter doesn't know the profile's true outer extent), NOT the per-segment
;;     assembly. The control below shows it: a PLAIN loft at the OUTER radius —
;;     which mitres ON that radius — is clean, while the shell at the same outer
;;     radius (mitred on the base) folds.
;;   • FALSIFIED HYPOTHESIS: extending the continuous build (tappa-2) to the
;;     dual-ring path was tried and made ZERO difference (tri-tri 126/16 unchanged).
;;     The continuous and per-segment builds sweep the SAME rings into the SAME
;;     bands+caps — geometrically identical, so tri-tri (a geometric measure) cannot
;;     differ. tappa-2 cures TOPOLOGICAL defects (the plain loft's old caps/seams);
;;     shell's defect is GEOMETRIC (ring placement). Different signature → different
;;     cure, exactly as the accertamento's own clue warned.
;; The shell+corner cases are EXPECTED-RED (assert the TARGET tri-tri=0, red today,
;; green when the REAL fix lands) — the fix's compass, like loft-corner-assembly-net's
;; mech-* reds. The real fix is to size the shell corner miter on the OUTER extent
;; (base + thickness/2) instead of the base radius — i.e. the shape-radius proxy
;; refinement of the guard/shortening, a SEPARATE item to be done with that work.
;; ════════════════════════════════════════════════════════════════════

(deftest shell-straight-is-clean
  (testing "GREEN baseline — a shell on a straight rail has no self-intersection"
    (is (zero? (self-intersection-pairs (shell-mesh 10 3 "(path (f 30))")))
        "shell circle r=10 / straight must be clean (0 pairs)")))

(deftest shell-corner-outer-geometry-fits-control
  ;; GREEN, permanent invariant + the diagnosis pivot: a PLAIN loft at the shell's
  ;; OUTER radius builds clean — because it mitres ON that radius. The shell at the
  ;; SAME outer radius folds (see EXPECTED-RED) because it mitres on the BASE radius
  ;; (shape-radius of the centreline) and so UNDER-mitres the outer skin. So the
  ;; cause is the miter BASIS (base vs outer), the shape-radius proxy — not the build
  ;; assembly and not raw width. The two EXPECTED-RED fixtures are chosen so their
  ;; outer skin fits when mitred correctly (plain = 0), so the REAL fix
  ;; (miter on outer extent) drives them to 0:
  ;;   • FIX  + thin wall t=0.2 → outer R=10.1 (FIX only admits outer < ~10.2)
  ;;   • LONG + wall t=3        → outer R=11.5 (LONG has ample room)
  (testing "plain loft at the EXPECTED-RED fixtures' outer radius is clean"
    (is (zero? (self-intersection-pairs (loft-mesh (circ 10.1) FIX-RAIL)))
        "plain loft outer R=10.1 fits the tight FIX corner (0 pairs)")
    (is (zero? (self-intersection-pairs (loft-mesh (circ 11.5) LONG-RAIL)))
        "plain loft outer R=11.5 fits the generous LONG corner (0 pairs)")))

(deftest shell-corner-builds-clean
  ;; REGRESSION GUARD (was EXPECTED-RED; the directional + wall-aware fix landed
  ;; 2026-06-30). A comfortably-realizable shell — outer skin (base + thickness/2)
  ;; well within the corner — now builds with NO self-intersection, because the miter
  ;; is sized on the outer skin's reach toward the inner normal (corner-inner-extent,
  ;; wall-aware), not the centroid-max shape-radius. Uses LONG (generous legs) so the
  ;; cases sit clear of the realizability edge (see the t=0.2/FIX edge note below).
  (testing "shell on a generous corner builds clean across thicknesses"
    (is (zero? (self-intersection-pairs (shell-mesh 10 3 LONG-RAIL)))
        "shell r=10 t=3 / LONG — outer skin 11.5 mitred correctly → tri-tri=0")
    (is (zero? (self-intersection-pairs (shell-mesh 10 6 LONG-RAIL)))
        "shell r=10 t=6 / LONG — outer skin 13 mitred correctly → tri-tri=0")))

;; DIAGNOSTIC — mesh-diagnose blindness + the realizability-edge residual. After the
;; fix, comfortably-realizable shells build clean and over-wide ones are refused; the
;; only self-intersecting shell left is the near-degenerate EDGE (t=0.2/FIX, eff≈0.07)
;; where the dual-ring corner leaves a residual fold (52, down from 126). NOT a sizing
;; failure (plain@outer is clean) and NOT a discrete artefact (verified: tri-tri grows
;; with resolution 24→52, 48→60, 96→86) — a REAL residual dual-ring corner fold at the
;; realizability edge, a known hole for a separate follow-up.
;; Refusal-safe (t=3/FIX is now refused → caught). Printed, not asserted.
(deftest shell-corner-diagnostic
  (doseq [[label base th rail]
          [["edge residual   : shell t=0.2 / FIX " 10 0.2 FIX-RAIL]
           ["clean realizable : shell t=3   / LONG" 10 3 LONG-RAIL]
           ["over-wide→refuse : shell t=3   / FIX " 10 3 FIX-RAIL]]]
    (let [r (try (let [m (shell-mesh base th rail)
                       dia (mu/mesh-diagnose m)]
                   (str "tri-tri=" (self-intersection-pairs m)
                        "  nm=" (:non-manifold-edges dia)
                        "  watertight=" (:is-watertight? dia)))
                 (catch :default e (str "REFUSED: " (subs (.-message e) 0 (min 30 (count (.-message e)))))))]
      (println (str ">>> " label ": " r))))
  (is true))

;; ════════════════════════════════════════════════════════════════════
;; DIRECTIONAL-PROXY COVERAGE NET — the compass that makes the general fix safe
;; instead of broad-and-blind (accertamento dev-docs/proxy-projection-accertamento.md;
;; this net dev-docs/proxy-net-accertamento.md).
;;
;; The guard sizes the corner miter by `shape-radius` (max distance from the
;; CENTROID, direction-independent). The correct quantity is the profile's reach
;; toward the corner's INNER NORMAL, measured FROM THE SPINE: h(n_in) = max_P
;; dot(P, n_in). shape-radius is a wrong proxy for it, producing THREE symptoms of
;; the SAME defect, which the directional projection (the planned general fix) cures
;; at once. Each test below asserts the behaviour the PROJECTION dictates — so it is
;; RED today exactly where shape-radius diverges, and flips GREEN when the fix lands.
;;
;; Verdict measured: the fix is BROAD (changes the rejection boundary for every
;; non-circular profile), and the existing nets exercise rejection only with CIRCLES
;; (where projection == shape-radius). So the polygon shifts below are TODAY UNSEEN —
;; this net is the coverage that stops the fix from changing common cases silently.
;; Metric: tri-tri + guard behaviour, never mesh-diagnose (blind to self-intersection).
;; ════════════════════════════════════════════════════════════════════

(defn- built-clean?
  "True iff THUNK builds (not refused) AND the result does not self-intersect
   (tri-tri = 0). False on refusal. The 'accepted + geometrically sound' target."
  [thunk]
  (let [p (safe-pairs thunk)] (and (number? p) (zero? p))))

;; centered polygons (centroid AT origin → :centered? irrelevant; shape-radius uses
;; the circumradius, the projection uses the directional extent which is smaller)
(defn- square [h] (shape/make-shape [[(- h) (- h)] [h (- h)] [h h] [(- h) h]] {:centered? true}))
(defn- rect [hw hh] (shape/make-shape [[(- hw) (- hh)] [hw (- hh)] [hw hh] [(- hw) hh]] {:centered? true}))
(defn- hexagon [r]                          ; vertex-up: +x reach is 0.866r, +y reach is r
  (shape/make-shape (vec (for [i (range 6)]
                           (let [a (+ (/ Math/PI 2) (* (/ Math/PI 3) i))]
                             [(* r (Math/cos a)) (* r (Math/sin a))])))
                    {:centered? true}))

;; ── Family 1 — centered polygons (the OVER-conservative symptom, newly found) ──
;; shape-radius (circumradius) > directional reach → the guard refuses realizable
;; corners. The projection accepts them and they build sound (tri-tri=0). RED today
;; because the corner is wrongly refused. THIS IS THE ZONE NO EXISTING TEST GUARDS.
(deftest fam1-square-th-EXPECTED-RED
  ;; square half=10: shape-radius=14.14, reach toward a th bend (±x)=10. On f12 legs
  ;; the miter needs 10 (fits, eff=2) but shape-radius asks 14.14 (eff=-2.14 → refused).
  (testing "square on a th corner the projection accepts but shape-radius refuses"
    (is (built-clean? #(ext-mesh (square 10) "(path (f 12) (th 90) (f 12))"))
        "TARGET accepted+clean: square half10 / (f12 th90 f12) — proj 10 fits, shape-radius 14.14 wrongly refuses (RED today)")))

(deftest fam1-hexagon-th-EXPECTED-RED
  ;; hexagon vertex-up circumR=10: shape-radius=10, reach toward th (±x)=8.66.
  ;; f9.5 legs: miter needs 8.66 (eff=0.84) but shape-radius asks 10 (eff=-0.5 → refused).
  (testing "hexagon on a th corner the projection accepts but shape-radius refuses"
    (is (built-clean? #(ext-mesh (hexagon 10) "(path (f 9.5) (th 90) (f 9.5))"))
        "TARGET accepted+clean: hexagon circumR10 / (f9.5 th90 f9.5) — proj 8.66 fits, shape-radius 10 wrongly refuses (RED today)")))

(deftest fam1-rectangle-orientation-the-heart-of-directional
  ;; SAME rectangle (20×6: hw=10, hh=3), SAME leg (f6), opposite correct verdict by
  ;; PLANE — this is the core of 'directional'. shape-radius=10.44 (refuses both today).
  ;;   • tv bend → inner normal ±y → reach=hh=3 → miter needs 3, fits f6 → must BUILD.
  ;;   • th bend → inner normal ±x → reach=hw=10 → miter needs 10 > 6 → must STAY REFUSED.
  (testing "rect on tv: projection (reach 3) accepts where shape-radius (10.44) refuses"
    (is (built-clean? #(ext-mesh (rect 10 3) "(path (f 6) (tv 90) (f 6))"))
        "TARGET accepted+clean: rect 20x6 / tv / f6 — proj y=3 fits, shape-radius wrongly refuses (RED today)"))
  (testing "rect on th: reach (10) genuinely exceeds the leg → refusal is CORRECT"
    (is (refused? #(ext-mesh (rect 10 3) "(path (f 6) (th 90) (f 6))"))
        "rect 20x6 / th / f6 — proj x=10 > leg 6, the corner truly does not fit → must refuse (GREEN now & after)")))

;; ── Family 2 — asymmetric off-centre (the escaper) — RESOLVED, builds clean ──
;; off-disk cx=10 rr=4 (:centered? false): shape-radius from the centroid = 4 (under-
;; sized); the directional fix measures the reach in the STAMP frame. CORRECTION vs the
;; accertamento: compute-stamp-transform puts the FIRST point on the rail, so the
;; stamped reach toward the inner normal is 8, not the authored-origin 14 — and
;; 8·tan(44.5°) ≈ 7.86 < leg 10, so the corner is REALIZABLE. The fix mitres it
;; correctly and it BUILDS CLEAN (tri-tri=0); it is NOT refused. (The brief's "refused"
;; target was based on the authored-origin 14; the stamp-frame measurement is 8.)
;; Invariant: extrude and loft, fed the same projection at the same point, build
;; identically — asserted on both.
(deftest fam2-asymmetric-escaper-builds-clean
  (testing "off-centre overhang, mitred on its real (stamp-frame) reach → clean build"
    (is (built-clean? #(ext-mesh (off-disk 10 4 24) FIX-RAIL))
        "extrude: off-disk cx10 rr4 / FIX builds with tri-tri=0 (was the escaper)")
    (is (built-clean? #(loft-mesh (off-disk 10 4 24) FIX-RAIL))
        "loft builds identically clean (extrude≡loft invariant under the directional fix)")))

;; ── Family 3 — shell boundary (recontextualised: under-mitred OUTER skin) ──
;; The two realizable shell reds (t=0.2/FIX, t=3/LONG) live above in
;; shell-corner-tri-tri-zero-EXPECTED-RED (build-clean target). Here is the OTHER
;; side of the boundary: a shell whose OUTER skin is too wide for the bend must be
;; REFUSED, aligning with the plain loft at that outer radius (plain 11.5/FIX is
;; already refused). Today shell builds it folded → RED.
;; COMPLICATION A — wall-dependence: this case's correctness depends on the WALL, not
;; the points. The base points give reach 10 (10·tan44.5 ≈ 9.83 < leg 10 → a
;; points-only projection would ACCEPT, wrongly). Only a projection of the swept
;; OUTER skin (base + t/2 = 11.5 → 11.3 > 10 → refuse) gets it right. So this test
;; stays RED under a points-only fix and only goes green under a wall-aware one —
;; the guard against shipping the elegant-but-incomplete version.
(deftest fam3-shell-outer-too-wide-must-refuse-EXPECTED-RED
  (testing "shell whose outer skin overhangs the bend must be refused (wall-aware)"
    (is (refused? #(shell-mesh 10 3 FIX-RAIL))
        "TARGET refused: shell t=3 / FIX — outer skin 11.5 too wide (aligns with plain 11.5/FIX refused); today builds folded (RED), and a POINTS-ONLY fix would still wrongly accept (Complication A)"))
  (testing "the alignment reference: the plain loft at the outer radius IS already refused"
    (is (refused? #(loft-mesh (circ 11.5) FIX-RAIL))
        "plain loft R=11.5 / FIX is refused today — the behaviour shell must match")))

;; ── Control — the circle does not move ──
;; projection == shape-radius in every direction → the fix must leave the circle's
;; rejection boundary identical. GREEN now AND after: the non-regression guard on the
;; one profile the broad fix must not touch.
(deftest control-circle-unchanged
  (testing "a circle corner that builds clean today must keep building clean (proj=shape-radius)"
    (is (built-clean? #(ext-mesh (circ 10) "(path (f 30) (th 90) (f 30))"))
        "circle r10 / (f30 th90 f30) builds clean — proj=shape-radius=10, the fix must not move it")
    (is (refused? #(ext-mesh (circ 21) FIX-RAIL))
        "circle r21 / FIX stays refused — proj=shape-radius=21, boundary identical under the fix")))

;; ── Composite corner (Piece 3) — th+tv at one vertex, general inner normal ──
;; A pure th or tv corner has its inner normal on a profile axis (±x / ±y); a
;; COMPOSITE th+tv corner bends in a tilted plane, so n_in is a GENERAL 2D direction
;; in the profile frame, and the turn angle is the angle BETWEEN the headings (not the
;; sum of the rotation magnitudes). The directional fix derives n_in from the two 3D
;; headings and the angle from acos(h0·h1), so it handles this; the pure-corner net
;; above does not exercise it. We don't pin the projection number (less crisp for a
;; composite) — we verify the BUILT mesh on a realizable composite corner does not
;; self-intersect, on both operators.
(deftest composite-corner-builds-clean
  (let [rail "(path (f 40) (th 35) (tv 35) (f 40))"   ; generous legs → realizable
        prof (rect 10 3)]                              ; asymmetric reach by direction
    (testing "extrude on a composite th+tv corner builds without self-intersection"
      (is (built-clean? #(ext-mesh prof rail))
          "extrude: rect 20x6 / (f40 th35 tv35 f40) — composite corner, tri-tri=0"))
    (testing "loft on the same composite corner builds clean (invariant)"
      (is (built-clean? #(loft-mesh prof rail))
          "loft: same composite corner builds clean too"))))
