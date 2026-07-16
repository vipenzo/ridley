(ns ridley.geometry.cut-candidates-test
  "Pure tests for cut-candidate geometry (dev-docs/brief-cut-candidates.md, Part 2):
   coplanar-face STEP detection with exact |ΔA| salience, and NECK detection on a
   sampled profile. No WASM — the section-area profile sampling itself lives in
   ridley.manifold.core behind the WASM-skip idiom (see dev-docs/code-issues.md
   'I test WASM Manifold skippano tutti in Node/CI')."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.geometry.cut-candidates :as cc]
            [ridley.geometry.primitives :as prim]
            [ridley.test-helpers :as h]))

(deftest vertex-offsets-and-range
  (testing "offsets along +Z from origin span the box's half-heights"
    (let [box (prim/box-mesh 40 40 10)          ; z ∈ [-5, 5]
          os (cc/vertex-offsets (:vertices box) [0 0 1] [0 0 0])]
      (is (h/approx= -5.0 (reduce min os) 1e-9))
      (is (h/approx= 5.0 (reduce max os) 1e-9))
      (is (= [-5.0 5.0] (cc/offset-range (:vertices box) [0 0 1] [0 0 0])))))
  (testing "empty mesh → nil range"
    (is (nil? (cc/offset-range [] [0 0 1] [0 0 0])))))

(deftest offset->pose-moves-only-along-heading
  (let [p (cc/offset->pose 7.0 [0 0 1] [0 1 0] [1 2 3])]
    (is (h/vec-approx= [1 2 10] (:position p) 1e-9) "position shifted +7 along Z only")
    (is (= [0 0 1] (:heading p)))
    (is (= [0 1 0] (:up p)))))

(deftest step-candidates-box-two-caps
  (testing "a box swept along Z has exactly two steps — its top and bottom caps —
            each with salience = the box's XY section area (|ΔA|), summed over the
            two coplanar triangles of each cap"
    (let [box (prim/box-mesh 40 30 10)          ; XY section = 1200
          steps (cc/step-candidates box [0 0 1] [0 0 0] {})
          by-off (sort-by :offset steps)]
      (is (= 2 (count steps)) "two caps, not the four ⊥ side faces")
      (is (h/approx= -5.0 (:offset (first by-off)) 1e-6))
      (is (h/approx= 5.0 (:offset (second by-off)) 1e-6))
      (is (h/approx= 1200.0 (:salience (first by-off)) 1e-6) "bottom cap |ΔA| = 40·30")
      (is (h/approx= 1200.0 (:salience (second by-off)) 1e-6) "top cap |ΔA| = 40·30")))
  (testing "swept along X the two caps are the ±X faces, salience = Y·Z section"
    (let [box (prim/box-mesh 40 30 10)          ; YZ section = 300
          steps (cc/step-candidates box [1 0 0] [0 0 0] {})]
      (is (= 2 (count steps)))
      (is (every? #(h/approx= 300.0 (:salience %) 1e-6) steps)))))

(deftest step-candidates-off-axis-none
  (testing "a diagonal heading grazes no face flush → no exact steps"
    (let [box (prim/box-mesh 40 30 10)
          h (let [d (js/Math.sqrt 3)] [(/ 1 d) (/ 1 d) (/ 1 d)])]
      (is (empty? (cc/step-candidates box h [0 0 0] {:angle-tol 1.0}))))))

(deftest step-candidates-snap-normal
  (testing "an axis-aligned cap reports the sweep heading itself as its normal —
            snapping is a no-op when the face is already ⊥ heading (regression guard
            for aligned cuts)"
    (let [box (prim/box-mesh 40 30 10)                 ; caps ⊥ Z at z = ±5
          cands (cc/step-candidates box [0 0 1] [0 0 0] {})]
      (is (every? #(h/vec-approx= [0 0 1] (:normal %) 1e-9) cands))))
  (testing "a flat step face tilted ≤angle-tol from the sweep heading reports the
            FACE's own normal (not the heading), so the candidate cut lands flush
            instead of shaving an oblique wafer off the flat face — the wafer bug
            (live-confirmed 2026-07-14). :point lies on that face plane."
    (let [th (* 0.8 (/ js/Math.PI 180.0))            ; 0.8° < angle-tol 1° → still a step
          s (js/Math.sin th) c (js/Math.cos th)
          rotx (fn [[x y z]] [x (- (* c y) (* s z)) (+ (* s y) (* c z))])
          verts (mapv rotx [[-20 -20 10] [20 -20 10] [20 20 10] [-20 20 10]])
          mesh {:vertices verts :faces [[0 1 2] [0 2 3]]}
          face-n [0 (- s) c]                          ; +Z rotated about +X by 0.8°
          cands (cc/step-candidates mesh [0 0 1] [0 0 0] {:tol 0.1 :angle-tol 1.0})]
      (is (pos? (count cands)))
      (is (not-any? #(h/vec-approx= [0 0 1] (:normal %) 1e-4) cands)
          "NOT the raw sweep heading")
      (is (every? #(h/vec-approx= face-n (:normal %) 1e-6) cands)
          "snapped to the tilted face's own normal")
      (is (every? (fn [{p :point}]
                    (h/approx= 0.0 (reduce + (map * (map - p (first verts)) face-n)) 1e-6))
                  cands)
          ":point lies on the face plane"))))

(deftest step-candidates-plane-cluster-splits-blended
  (testing "two distinct near-parallel faces at the SAME sweep offset but normals
            >angle-tol apart stay SEPARATE candidates (plane-clustering), each snapped
            to its OWN normal — not merged into one blended-normal group that a tilted
            heading would shave a wafer off both of (STL-confirmed 2026-07-15). Two
            quads tilted ±0.7° about X (1.4° apart), both centred at z=5."
    (let [t (js/Math.tan (* 0.7 (/ js/Math.PI 180.0)))
          quad (fn [y0 y1 zf] [[-5 y0 (zf y0)] [5 y0 (zf y0)] [5 y1 (zf y1)] [-5 y1 (zf y1)]])
          A (quad -10 -2 (fn [y] (+ 5 (* t (+ y 6)))))       ; tilt +, centroid z=5
          B (quad 2 10 (fn [y] (+ 5 (* t (- 6 y)))))         ; tilt −, centroid z=5
          mesh {:vertices (vec (concat A B))
                :faces [[0 1 2] [0 2 3] [4 5 6] [4 6 7]]}
          cands (cc/step-candidates mesh [0 0 1] [0 0 0] {:tol 0.1 :angle-tol 1.0})]
      (is (= 2 (count cands)) "two clusters, one per face — NOT one blended group")
      (let [nys (sort (map #(nth (:normal %) 1) cands))]
        (is (< (first nys) -0.005) "one candidate snapped to the −y-tilted face normal")
        (is (> (last nys) 0.005) "the other to the +y-tilted face normal")))))

(deftest step-pose-flush-and-centred
  (testing "step-pose sets heading = the face normal, lands the plane ON the face
            (through :point), and re-centres it laterally through ref"
    (let [raw [0.0 -0.05 1.0]
          m (js/Math.sqrt (reduce + (map * raw raw)))
          n (mapv #(/ % m) raw)
          point [3 4 10] ref [1 1 1] up [0 1 0]
          {pos :position hd :heading u :up} (cc/step-pose n point ref up)
          on-plane (reduce + (map * (map - pos point) n))     ; (pos−point)·n
          in-plane (let [d (map - pos ref)                    ; (pos−ref) minus its n-part
                         dn (reduce + (map * d n))]
                     (map - d (map #(* dn %) n)))]
      (is (h/vec-approx= n hd 1e-9) "heading snapped to the normal")
      (is (= up u) "up carried through")
      (is (h/approx= 0.0 on-plane 1e-9) "plane passes through the face")
      (is (h/vec-approx= [0 0 0] (vec in-plane) 1e-9) "laterally at ref's projection")))
  (testing "a 5th `bias` arg shifts :position along `normal` by that signed amount,
            leaving heading/up untouched — the raw mechanism brief-step-bias.md's
            cut-candidates layers a ΔA-derived sign onto (Part 1)"
    (let [n [0 0 1] point [0 0 5] ref [1 1 1] up [0 1 0]
          flush (cc/step-pose n point ref up)
          biased-pos (cc/step-pose n point ref up 0.002)
          biased-neg (cc/step-pose n point ref up -0.002)]
      (is (h/vec-approx= (mapv + (:position flush) [0 0 0.002]) (:position biased-pos) 1e-9))
      (is (h/vec-approx= (mapv + (:position flush) [0 0 -0.002]) (:position biased-neg) 1e-9))
      (is (= (:heading biased-pos) (:heading flush)) "bias never touches heading")))
  (testing "omitting bias == bias 0 (default arity)"
    (is (= (cc/step-pose [0 0 1] [0 0 5] [1 1 1] [0 1 0])
           (cc/step-pose [0 0 1] [0 0 5] [1 1 1] [0 1 0] 0.0)))))

;; ── bias ε sign (dev-docs/brief-step-bias.md Part 1) ────────────────────
;; bbox-diagonal/default-bias-epsilon are pure/node-testable; the SIGN convention
;; (bias = −sign(:sum) · ε along the snapped normal) is exercised the way
;; manifold/core.cljs's cut-candidates actually applies it, on a single flat quad
;; whose winding fixes :sum's sign directly — the real question this brief asks
;; ("segno... da verificare, non solo dedotto") is answered by the two cases below.

(deftest bbox-diagonal-and-default-epsilon
  (testing "bbox-diagonal is the AABB's diagonal length; empty → 0"
    (is (h/approx= 0.0 (cc/bbox-diagonal []) 1e-12))
    (is (h/approx= (js/Math.sqrt (+ (* 40 40) (* 30 30) (* 10 10)))
                   (cc/bbox-diagonal (:vertices (prim/box-mesh 40 30 10))) 1e-6)))
  (testing "default-bias-epsilon: floor at 1e-3mm for a tiny/empty mesh, scales
            1e-4× diagonal for a large one"
    (is (h/approx= 1e-3 (cc/default-bias-epsilon []) 1e-12))
    (is (h/approx= 1e-3 (cc/default-bias-epsilon (:vertices (prim/box-mesh 1 1 1))) 1e-9)
        "small box: floor dominates (diag √3 × 1e-4 ≈ 1.7e-4 < 1e-3)")
    (let [big (:vertices (prim/box-mesh 10000 10000 10000))]
      (is (h/approx= (* 1e-4 (cc/bbox-diagonal big)) (cc/default-bias-epsilon big) 1e-6)
          "large box: the scale-aware term dominates the floor"))))

(defn- flat-quad
  "A single-normal flat quad (2 tris) at z=`z`, footprint `w`×`w` centred on the
   origin, with `n` its outward normal (±[0 0 1] — winding chosen accordingly)."
  [z w n]
  (let [h (/ w 2.0)
        verts [[(- h) (- h) z] [h (- h) z] [h h z] [(- h) h z]]
        faces (if (pos? (nth n 2)) [[0 1 2] [0 2 3]] [[0 2 1] [0 3 2]])]
    {:vertices verts :faces faces}))

(defn- bias-of
  "Reproduces cut-candidates' own bias computation from a single step-candidates
   cluster: eps · (−sign :sum), the SIGNED shift step-pose applies along :normal."
  [mesh heading point eps]
  (let [[{:keys [sum]}] (cc/step-candidates mesh heading point {})]
    (* eps (- (js/Math.sign sum)))))

(deftest bias-sign-points-into-the-bulk
  (testing "a +Z-outward-normal face (solid below, by the outward-normal convention)
            → :sum > 0 → bias is NEGATIVE (into −Z, the bulk side)"
    (let [q (flat-quad 5.0 10 [0 0 1])]
      (is (neg? (bias-of q [0 0 1] [0 0 0] 0.002)))))
  (testing "a −Z-outward-normal face (solid above) → :sum < 0 → bias is POSITIVE
            (into +Z, the bulk side) — the mirror case, confirms the sign isn't a
            coincidence of the first fixture's axis"
    (let [q (flat-quad 5.0 10 [0 0 -1])]
      (is (pos? (bias-of q [0 0 1] [0 0 0] 0.002)))))
  (testing "bias magnitude is exactly eps regardless of direction"
    (is (h/approx= 0.002 (js/Math.abs (bias-of (flat-quad 5.0 10 [0 0 1]) [0 0 1] [0 0 0] 0.002)) 1e-12))))

;; ── heal-slivers classification (dev-docs/brief-step-bias.md Part 2) ────
;; component-thickness/default-sliver-threshold are the pure half of heal-slivers;
;; the WASM orchestration (decompose/concat/union) is manifold/core.cljs's job and
;; lives in mesh-split-test.cljs behind the WASM-skip idiom.

(deftest component-thickness-along-normal
  (testing "extension along `normal`, not the mesh's own longest axis"
    (let [box (prim/box-mesh 40 30 10)]           ; x∈[-20,20] y∈[-15,15] z∈[-5,5]
      (is (h/approx= 10.0 (cc/component-thickness (:vertices box) [0 0 1]) 1e-9))
      (is (h/approx= 30.0 (cc/component-thickness (:vertices box) [0 1 0]) 1e-9))
      (is (h/approx= 40.0 (cc/component-thickness (:vertices box) [1 0 0]) 1e-9))))
  (testing "a tilted normal reads the projected extension, not any axis-aligned one —
            along the space diagonal a side-2 cube reads its full diagonal 2√3, not
            the side length"
    (let [box (prim/box-mesh 2 2 2)
          diag (let [d (js/Math.sqrt 3)] [(/ 1 d) (/ 1 d) (/ 1 d)])]
      (is (h/approx= (* 2.0 (js/Math.sqrt 3)) (cc/component-thickness (:vertices box) diag) 1e-6))))
  (testing "empty vertex list → 0.0"
    (is (h/approx= 0.0 (cc/component-thickness [] [0 0 1]) 1e-12))))

(deftest default-sliver-threshold-is-2x-bias-epsilon
  (is (h/approx= (* 2.0 (cc/default-bias-epsilon []))
                 (cc/default-sliver-threshold []) 1e-15))
  (let [big (:vertices (prim/box-mesh 5000 5000 5000))]
    (is (h/approx= (* 2.0 (cc/default-bias-epsilon big))
                   (cc/default-sliver-threshold big) 1e-9))))

(deftest profile-minima-finds-the-waist
  (testing "a dumbbell profile (high–low–high) yields one neck at the waist, depth =
            bell − waist; a monotone step yields none"
    (let [dumbbell (map-indexed (fn [i a] {:offset (* i 1.0) :area a})
                                [100 100 90 60 40 60 90 100 100])
          necks (cc/profile-minima dumbbell 1.0)]
      (is (= 1 (count necks)))
      (is (h/approx= 4.0 (:offset (first necks)) 1e-9) "waist at the index-4 minimum")
      (is (h/approx= 20.0 (:salience (first necks)) 1e-9) "depth = min(60,60) − 40"))
    (let [step (map-indexed (fn [i a] {:offset (* i 1.0) :area a})
                            [100 100 100 40 40 40])]
      (is (empty? (cc/profile-minima step 1.0)) "a step-down is not a valley")))
  (testing "a flat-bottomed valley collapses to its middle sample"
    (let [flat (map-indexed (fn [i a] {:offset (* i 1.0) :area a})
                            [100 50 50 50 100])]
      (is (= 1 (count (cc/profile-minima flat 1.0))))
      (is (h/approx= 2.0 (:offset (first (cc/profile-minima flat 1.0))) 1e-9))))
  (testing "sub-threshold dips are dropped"
    (let [shallow (map-indexed (fn [i a] {:offset (* i 1.0) :area a})
                               [100 99.5 100])]
      (is (empty? (cc/profile-minima shallow 1.0))))))

;; ── rotation ──
(deftest rotate-about-and-angle-pose
  (testing "Rodrigues: +X about +Z by 90° → +Y"
    (is (h/vec-approx= [0 1 0] (cc/rotate-about [1 0 0] [0 0 1] (/ js/Math.PI 2)) 1e-9)))
  (testing "angle->pose rotates heading and up about the axis, position fixed"
    (let [p (cc/angle->pose (/ js/Math.PI 2) [0 0 1] [0 1 0] [1 2 3] [1 0 0])]
      (is (h/vec-approx= [0 -1 0] (:heading p) 1e-9) "heading +Z about +X 90° → −Y")
      (is (h/vec-approx= [0 0 1] (:up p) 1e-9) "up +Y about +X 90° → +Z")
      (is (= [1 2 3] (:position p))))))

(deftest rotation-step-a-face-through-the-axis
  (testing "a box face lying ON the axis plane is a rotation step (salience = its
            area); the offset faces are not — why a centred box yields none"
    (let [box (-> (prim/box-mesh 20 20 20)                          ; centred x ∈ [-10,10]
                  (update :vertices (partial mapv (fn [[x y z]] [(+ x 10) y z]))))  ; x ∈ [0,20]
          ;; pivot about +Y through the origin; the −X face sits on the plane x=0
          steps (cc/rotation-step-candidates box [0 0 1] [0 0 0] [0 1 0] {})]
      (is (= 1 (count steps)) "only the face through the origin")
      (is (h/approx= (/ js/Math.PI 2) (js/Math.abs (:offset (first steps))) 1e-6))
      (is (h/approx= 400.0 (:salience (first steps)) 1e-6) "20×20 face area")))
  (testing "a centred box has no rotation step about an axis through its centre"
    (is (empty? (cc/rotation-step-candidates (prim/box-mesh 20 20 20) [0 0 1] [0 0 0] [0 1 0] {})))))

;; ── reflex (dev-docs/brief-cut-candidates-reflex.md) ──
;; Test meshes are built by hand (no WASM/CSG): an L-shaped prism has exactly one
;; reflex edge — the vertical edge at the inner corner (1,1), interior angle 270° →
;; excess π/2 — whose two walls' planes (x=1, y=1) are the two candidates.

(defn- l-prism
  "An L-cross-section prism (inner reflex corner at (1,1)) extruded z∈[0,h], the whole
   thing offset by [dx dy dz]. Vertices 0-5 bottom (z=0), 6-11 top (z=h), CCW so every
   outward normal points away from the solid. One reflex edge: the vertical (3,9) at
   (1+dx, 1+dy)."
  ([h] (l-prism h [0 0 0]))
  ([h [dx dy dz]]
   (let [xy [[0 0] [2 0] [2 1] [1 1] [1 2] [0 2]]
         v (fn [[x y] z] [(+ x dx) (+ y dy) (+ z dz)])
         bottom (mapv #(v % 0) xy)
         top (mapv #(v % h) xy)
         walls (mapcat (fn [i] (let [n (mod (inc i) 6)]
                                 [[i n (+ n 6)] [i (+ n 6) (+ i 6)]]))
                       (range 6))]
     {:vertices (vec (concat bottom top))
      :faces (vec (concat [[0 2 1] [0 3 2] [0 4 3] [0 5 4]]        ; bottom cap (−Z)
                          [[6 7 8] [6 8 9] [6 9 10] [6 10 11]]     ; top cap (+Z)
                          walls))})))

(defn- merge-meshes
  "Concatenate meshes into one face list, index-offsetting each so they stay disjoint."
  [& meshes]
  (reduce (fn [{va :vertices fa :faces} {vb :vertices fb :faces}]
            (let [o (count va)]
              {:vertices (vec (concat va vb))
               :faces (vec (concat fa (mapv (fn [[i j k]] [(+ i o) (+ j o) (+ k o)]) fb)))}))
          meshes))

(deftest reflex-candidates-l-prism
  (testing "an L-prism yields its two interior planes (x=1, y=1), each salience =
            edge-length × angle-excess = h · π/2, ranked by that mass, kind :reflex"
    (let [h 4.0
          cands (cc/reflex-candidates (l-prism h))
          headings (set (map (comp :heading :pose) cands))]
      (is (= 2 (count cands)) "exactly two clusters — the two adjacent face-planes")
      (is (every? #(= :reflex (:kind %)) cands))
      (is (every? #(h/approx= (* h (/ js/Math.PI 2)) (:salience %) 1e-9) cands)
          "salience = h·(π/2): length h × excess π/2 (interior 270°)")
      (is (>= (:salience (first cands)) (:salience (last cands))) "sorted by salience desc")
      ;; the two candidate normals are the two wall outward normals +X and +Y
      (is (some #(h/vec-approx= [1 0 0] % 1e-9) headings) "the x=1 plane (normal +X)")
      (is (some #(h/vec-approx= [0 1 0] % 1e-9) headings) "the y=1 plane (normal +Y)")
      ;; position projects the concavity's midpoint onto each plane: (1,1,h/2)
      (is (every? #(h/vec-approx= [1 1 (/ h 2)] (:position (:pose %)) 1e-9) cands)
          "position lands on the plane, at the reflex edge's mid-height"))))

(deftest reflex-candidates-convex-is-empty
  (testing "a convex box has no reflex edges → []"
    (is (= [] (cc/reflex-candidates (prim/box-mesh 30 20 10))))
    (is (= [] (cc/reflex-candidates (prim/box-mesh 5))))))

(deftest reflex-candidates-pure
  (testing "identical input → identical output (B5)"
    (let [m (l-prism 3.0)]
      (is (= (cc/reflex-candidates m) (cc/reflex-candidates m))))))

(deftest reflex-candidates-salience-ranks-by-mass
  (testing "two L-prisms, one taller: its planes outrank the shorter's (length weight)"
    ;; the shorter prism is offset in x AND y so its planes don't share an offset with
    ;; the tall one (a shared y=1 plane would cluster the two together)
    (let [tall (l-prism 6.0 [0 0 0])
          short (l-prism 2.0 [10 5 0])
          cands (cc/reflex-candidates (merge-meshes tall short))]
      (is (= 4 (count cands)) "two planes from each prism, none shared")
      (is (h/approx= (* 6.0 (/ js/Math.PI 2)) (:salience (nth cands 0)) 1e-9))
      (is (h/approx= (* 6.0 (/ js/Math.PI 2)) (:salience (nth cands 1)) 1e-9))
      (is (h/approx= (* 2.0 (/ js/Math.PI 2)) (:salience (nth cands 2)) 1e-9))
      (is (h/approx= (* 2.0 (/ js/Math.PI 2)) (:salience (nth cands 3)) 1e-9))
      (is (every? #(< (nth (:position (:pose %)) 0) 5) (take 2 cands))
          "the top-two candidates sit at the tall prism (x≈1), not the short one (x≈11)"))))

(deftest reflex-tol-gates-on-excess
  (testing "raising :reflex-tol above the edge's angle-excess (π/2 = 90°) drops it"
    (let [m (l-prism 4.0)]
      (is (= 2 (count (cc/reflex-candidates m {:reflex-tol 89.0}))) "just under 90° — kept")
      (is (= [] (cc/reflex-candidates m {:reflex-tol 91.0})) "just over 90° — filtered as near-flat"))))
