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
;; ESCAPER (EXPECTED-RED) — what the shape-radius PROXY misses. The guard sizes
;; the miter by shape-radius (max extent from the CENTROID); an off-centre profile
;; reaches far across the bend FROM THE ORIGIN while keeping a small shape-radius,
;; so the guard's effective-dist stays positive (it lets the build through) but the
;; tube folds anyway. The downstream net SEES it (pairs>0). This is the brief's
;; "guard and net diverge" case and the reason the net is the safety layer the
;; proxy needs.
;; GREEN TRIPWIRE — asserts the CURRENT divergence (guard passes, net catches). It
;; flips RED when the proxy is refined (project the profile onto the corner's inner
;; normal → the guard will then REFUSE this) or the geometry is cured; revisit then.
;; ════════════════════════════════════════════════════════════════════
(deftest escaper-proxy-miss-is-caught
  (testing "an off-centre profile passes the shape-radius guard but self-intersects"
    (let [prof (off-disk 10 4 24)]            ; shape-radius 4, but reaches to x=14
      (is (not (refused? #(ext-mesh prof FIX-RAIL)))
          "the guard's shape-radius proxy lets this off-centre profile through")
      (is (pos? (self-intersection-pairs (ext-mesh prof FIX-RAIL)))
          "and the downstream net catches the fold the proxy missed"))))

;; ════════════════════════════════════════════════════════════════════
;; SECOND SELF-INTERSECTION FAMILY surfaced by this net — a :centered? false
;; profile (the default for make-shape / path-2d profiles) self-intersects at a
;; HARD corner even when it is geometrically CENTERED and narrow enough to fit (the
;; guard passes, effective-dist > 0). The IDENTICAL geometry as a :centered? true
;; circle (circle-shape) builds clean (verified: same point set, same face/vertex
;; count, 0 pairs vs ~19). So this is a corner-PLACEMENT defect tied to the
;; non-centered stamp path, INDEPENDENT of profile width — distinct from the width
;; disease above, and (like it) invisible to mesh-diagnose. The net is the only
;; thing that sees it; the fix belongs to a separate investigation of the
;; :centered? false corner placement.
;; GREEN TRIPWIRE — asserts the current divergence; flips RED when that corner
;; placement is fixed, at which point this becomes a regression guard.
;; ════════════════════════════════════════════════════════════════════
(deftest centered-false-corner-self-intersects
  (let [rail "(path (f 40) (th 89) (f 40))"        ; generous legs: width fits, guard passes
        cfalse (off-disk 0 5 24)                    ; :centered? false, centroid AT origin
        ctrue  (shape/circle-shape 5 24)]           ; identical geometry, :centered? true
    (testing ":centered? true builds the centered circle clean"
      (is (zero? (self-intersection-pairs (ext-mesh ctrue rail)))
          "the centred circle on the clean stamp path does not self-intersect"))
    (testing ":centered? false self-intersects on the SAME geometry + corner"
      (is (not (refused? #(ext-mesh cfalse rail)))
          "the corner is realizable (width fits) — the guard does not fire")
      (is (pos? (self-intersection-pairs (ext-mesh cfalse rail)))
          "yet the :centered? false corner placement folds the section"))))

;; ════════════════════════════════════════════════════════════════════
;; WHY THIS NET IS NEW — mesh-diagnose is blind. On a mesh the net flags as
;; pierced (the escaper, which DOES build), mesh-diagnose reads a pristine closed
;; 2-manifold: nm=0, watertight. A self-intersection passes the surface through
;; itself while every edge keeps two incident faces. Every existing topological
;; net sits GREEN here; that gap is why this family had to be built.
;; ════════════════════════════════════════════════════════════════════
(deftest the-blindness-of-mesh-diagnose
  (testing "a frankly self-intersecting mesh is topologically pristine (nm=0, watertight)"
    (doseq [[label prof rail]
            [["off-centre escaper" (off-disk 10 4 24) FIX-RAIL]
             [":centered? false corner" (off-disk 0 5 24) "(path (f 40) (th 89) (f 40))"]]]
      (let [m   (ext-mesh prof rail)
            dia (mu/mesh-diagnose m)]
        (is (pos? (self-intersection-pairs m))
            (str label " must self-intersect (premise of the blindness claim)"))
        (is (zero? (:non-manifold-edges dia))
            (str label " — mesh-diagnose sees nm=0 on a pierced mesh (BLIND)"))
        (is (true? (:is-watertight? dia))
            (str label " — mesh-diagnose calls a pierced mesh watertight (BLIND)"))))))
