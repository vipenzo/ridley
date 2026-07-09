(ns ridley.sdf.auto-bounds-test
  "Tests for the rotate branch of auto-bounds — see dev-docs/brief-auto-bounds.md.

   The old rotate branch bounded the child by a sphere and returned the cube
   [±r]³, inflating by up to √3 per node (unbounded (√3)ⁿ in chains) and losing
   the child's off-centre position. The new branch rotates the child's 8 bounds
   corners by the node's exact cardinal rotation and takes their per-axis AABB —
   strictly tighter, exact for a single rotate on an axis-aligned box.

   auto-bounds is pure (no server round-trip), so these run in the Node suite."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.sdf.core :as sdf]))

(def ^:private eps 1e-6)

(defn- close? [a b] (< (js/Math.abs (- a b)) 1e-4))

(defn- box
  "Axis-aligned box node. auto-bounds gives half-extent 0.6·s per axis,
   so side 10 → half-extent 6.0."
  [s]
  {:op "box" :sx s :sy s :sz s})

;; --------------------------------------------------------------------------
;; 1. 90° about a cardinal axis of an axis-aligned box → bounds unchanged
;;    (exactness on the trivial case).
;; --------------------------------------------------------------------------
(deftest rotate-90-axis-aligned-is-identity
  (let [b (sdf/auto-bounds (sdf/sdf-rotate (box 10) :z 90))
        [[x0 x1] [y0 y1] [z0 z1]] b]
    (is (close? x0 -6.0)) (is (close? x1 6.0))
    (is (close? y0 -6.0)) (is (close? y1 6.0))
    (is (close? z0 -6.0)) (is (close? z1 6.0))))

;; --------------------------------------------------------------------------
;; 2. 45° about z of a box [±a]³ → x/y extend to a√2, z invariant
;;    (against the old cube ±a√3).
;; --------------------------------------------------------------------------
(deftest rotate-45-about-z-gives-sqrt2-not-sqrt3
  (let [a 6.0
        exp (* a (js/Math.sqrt 2)) ; ≈ 8.485, NOT a√3 ≈ 10.39
        b (sdf/auto-bounds (sdf/sdf-rotate (box 10) :z 45))
        [[x0 x1] [y0 y1] [z0 z1]] b]
    (is (close? x1 exp)) (is (close? x0 (- exp)))
    (is (close? y1 exp)) (is (close? y0 (- exp)))
    (is (close? z1 a))   (is (close? z0 (- a)))))

;; --------------------------------------------------------------------------
;; 3a. Off-centre child: box moved +10 x, rotated 90° about z.
;;     Right-hand +90 about z maps (x,y)→(-y,x), so the +10-on-x mass lands
;;     around +10 on y (correct sign — cures the centring loss).
;; --------------------------------------------------------------------------
(deftest rotate-decentred-about-z-lands-positive-y
  (let [child {:op "move" :dx 10 :dy 0 :dz 0 :a (box 10)}
        b (sdf/auto-bounds (sdf/sdf-rotate child :z 90))
        [[x0 x1] [y0 y1] [z0 z1]] b]
    ;; y bounds around +10 (positive), x collapses to ±6, z untouched
    (is (close? y0 4.0)) (is (close? y1 16.0))
    (is (close? x0 -6.0)) (is (close? x1 6.0))
    (is (close? z0 -6.0)) (is (close? z1 6.0))))

;; --------------------------------------------------------------------------
;; 3b. Convention trap: same off-centre box, rotated 90° about y.
;;     :y stores the angle negated (libfive convention). Right-hand +90 about
;;     y maps (x,y,z)→(z,y,-x), so the +10-on-x mass lands around −10 on z.
;;     If the recovery gets the sign wrong it lands at +z instead — this test
;;     pins the convention, per the brief's warning.
;; --------------------------------------------------------------------------
(deftest rotate-decentred-about-y-lands-negative-z
  (let [child {:op "move" :dx 10 :dy 0 :dz 0 :a (box 10)}
        b (sdf/auto-bounds (sdf/sdf-rotate child :y 90))
        [[x0 x1] [y0 y1] [z0 z1]] b]
    (is (close? z0 -16.0)) (is (close? z1 -4.0))
    (is (close? x0 -6.0)) (is (close? x1 6.0))
    (is (close? y0 -6.0)) (is (close? y1 6.0))))

;; --------------------------------------------------------------------------
;; 4. Containment ("mai peggiore"): for a sample of rotations and boxes, the
;;    new AABB is always inside the cube the old branch would have produced.
;; --------------------------------------------------------------------------
(defn- old-branch-cube
  "The cube [±r]³ the pre-fix rotate branch produced from a child's bounds."
  [child-bounds]
  (let [r (js/Math.sqrt (+ (js/Math.pow (apply max (map js/Math.abs (child-bounds 0))) 2)
                           (js/Math.pow (apply max (map js/Math.abs (child-bounds 1))) 2)
                           (js/Math.pow (apply max (map js/Math.abs (child-bounds 2))) 2)))]
    [[(- r) r] [(- r) r] [(- r) r]]))

(deftest rotate-aabb-contained-in-old-cube
  (doseq [axis [:x :y :z]
          angle [17 45 90 123 200 355]
          child [(box 10)
                 {:op "move" :dx 8 :dy -3 :dz 2 :a (box 6)}
                 {:op "move" :dx -12 :dy 5 :dz 0 :a (box 14)}]]
    (let [child-b (sdf/auto-bounds child)
          cube (old-branch-cube child-b)
          [[cx0 cx1] [cy0 cy1] [cz0 cz1]] cube
          new-b (sdf/auto-bounds (sdf/sdf-rotate child axis angle))
          [[nx0 nx1] [ny0 ny1] [nz0 nz1]] new-b]
      (is (<= (- cx0 eps) nx0) (str axis " " angle " x0"))
      (is (>= (+ cx1 eps) nx1) (str axis " " angle " x1"))
      (is (<= (- cy0 eps) ny0) (str axis " " angle " y0"))
      (is (>= (+ cy1 eps) ny1) (str axis " " angle " y1"))
      (is (<= (- cz0 eps) nz0) (str axis " " angle " z0"))
      (is (>= (+ cz1 eps) nz1) (str axis " " angle " z1")))))
