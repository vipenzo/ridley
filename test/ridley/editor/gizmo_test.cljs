(ns ridley.editor.gizmo-test
  "Pure projection/snap math behind the edit-attach gizmo handles
   (dev-docs/brief-edit-attach-handles.md), written before the gizmo's rendering/
   pointer-handling code per the brief's own instruction. The ray-line and
   ray-plane-angle math live in ridley.math (closest-point-on-line,
   signed-angle-around-axis) so they're reusable — and testable here without
   mocking THREE/DOM — by both viewport/raycast-line-point and the gizmo itself.
   Snap/free-drag rounding are gizmo-specific policy and stay in gizmo.cljs."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.math :as m]
            [ridley.editor.gizmo :as gizmo]))

;; ============================================================
;; closest-point-on-line (arrow / stretch-handle drag projection)
;; ============================================================

(deftest closest-point-on-line-perpendicular-ray
  (testing "ray straight down onto the X axis lands at its own x, y=z=0"
    (is (= [3.0 0.0 0.0]
           (m/closest-point-on-line [3 5 0] [0 -1 0] [0 0 0] [1 0 0])))))

(deftest closest-point-on-line-skew-ray
  (testing "a ray that doesn't touch the line still finds the nearest point on it"
    ;; Line = X axis. Ray starts at [2 1 1] pointing straight down (-Y) — never
    ;; crosses the line (it's offset in Z) but the closest point on the line to
    ;; that ray is still directly below/along at x=2.
    (is (= [2.0 0.0 0.0]
           (m/closest-point-on-line [2 1 1] [0 -1 0] [0 0 0] [1 0 0])))))

(deftest closest-point-on-line-offset-origin
  (testing "line not through the world origin"
    (is (= [0.0 4.0 0.0]
           (m/closest-point-on-line [0 4 -10] [0 0 1] [0 0 0] [0 1 0])))))

(deftest closest-point-on-line-parallel-returns-nil
  (testing "ray parallel to the axis line has no unique closest point"
    (is (nil? (m/closest-point-on-line [0 5 5] [1 0 0] [0 0 0] [1 0 0])))))

;; ============================================================
;; signed-angle-around-axis (ring drag angle)
;; ============================================================

(deftest signed-angle-quarter-turn-matches-rotate-point-around-axis
  (testing "the angle this reports is exactly what rotate-point-around-axis needs
            to turn v-from into v-to — so feeding it straight into th/tv/tr is
            guaranteed consistent with the existing (shipped) rotation commands"
    (let [v-from [1 0 0]
          v-to [0 1 0]
          axis [0 0 1]
          angle (m/signed-angle-around-axis v-from v-to axis)]
      (is (< (Math/abs (- angle (/ Math/PI 2))) 1e-9))
      (let [rotated (m/rotate-point-around-axis v-from axis angle)]
        (is (< (m/magnitude (m/v- rotated v-to)) 1e-9))))))

(deftest signed-angle-opposite-direction-is-negative
  (testing "swapping from/to negates the angle"
    (let [axis [0 0 1]
          a (m/signed-angle-around-axis [1 0 0] [0 1 0] axis)
          b (m/signed-angle-around-axis [0 1 0] [1 0 0] axis)]
      (is (< (Math/abs (+ a b)) 1e-9)))))

(deftest signed-angle-projects-off-plane-vectors
  (testing "vectors with a component along the axis are projected into the
            perpendicular plane first, not rejected"
    (let [axis [0 0 1]
          v-from [1 0 5]   ; way off the XY plane
          v-to [0 2 -3]]   ; also off-plane, and not unit length
      (is (< (Math/abs (- (m/signed-angle-around-axis v-from v-to axis)
                          (/ Math/PI 2)))
             1e-9)))))

(deftest signed-angle-zero-for-parallel-vectors
  (is (< (Math/abs (m/signed-angle-around-axis [2 0 0] [5 0 0] [0 0 1])) 1e-9)))

;; ============================================================
;; snap-round / free-round / snap-scale-factor (drag → command quantization)
;; ============================================================

(deftest snap-round-basic
  (testing "rounds to the nearest multiple of step"
    (is (= 10.0 (gizmo/snap-round 8.7 5)))
    (is (= 5.0 (gizmo/snap-round 7.4 5)))
    (is (= 0.0 (gizmo/snap-round 2.4 5)))
    (is (= -15.0 (gizmo/snap-round -13.2 5)))))

(deftest snap-round-zero-step-is-identity
  (is (= 8.7 (gizmo/snap-round 8.7 0))))

(deftest free-round-tenths
  (testing "free (unsnapped) drags still round to 0.1 before ever reaching a command
            — (f 12.3847) must never appear in generated code"
    (is (= 12.4 (gizmo/free-round 12.3847)))
    (is (= (- 0.1) (gizmo/free-round -0.06)))
    (is (= 0.0 (gizmo/free-round 0.04)))))

(deftest snap-scale-factor-grid
  (testing "snaps to the nearest integer power of scale-step, matching what
            repeated keyboard stretch presses would produce"
    (is (< (Math/abs (- (gizmo/snap-scale-factor 1.15 1.1) 1.1)) 1e-9))
    (is (< (Math/abs (- (gizmo/snap-scale-factor 1.25 1.1) 1.21)) 1e-9))
    (is (< (Math/abs (- (gizmo/snap-scale-factor 0.9 1.1) (/ 1.0 1.1))) 1e-9))))

(deftest snap-scale-factor-degenerate-inputs-pass-through
  (is (= 0 (gizmo/snap-scale-factor 0 1.1)))
  (is (= 1.3 (gizmo/snap-scale-factor 1.3 1.0))))

;; ============================================================
;; nearest-point / snap-delta-commands (origin-mode vertex snap)
;; ============================================================

(deftest nearest-point-picks-closest-vertex
  (testing "the nearest of a face's three vertices to the impact point"
    (is (= [1 0 0]
           (gizmo/nearest-point [0.9 0.1 0] [[1 0 0] [0 5 0] [0 0 5]])))))

(deftest snap-delta-commands-decomposes-on-pose-frame
  (testing "components of (target - pivot) on the pose's own {heading,right,up}
            frame become cp-f/cp-rt/cp-u directly, zero components dropped"
    ;; pose heading +X, up +Z → right = heading × up = +X × +Z = [0 -1 0].
    (let [pose {:heading [1 0 0] :up [0 0 1]}
          ;; target offset +2 along heading and +3 along up, nothing along right.
          cmds (gizmo/snap-delta-commands pose [0 0 0] [2 0 3] 0.01)]
      (is (= [[:cp-u 3.0] [:cp-f 2.0]] cmds)))))

(deftest snap-delta-commands-right-axis-sign-follows-pose
  (testing "a target displaced along the pose's right axis yields a positive cp-rt
            (right = heading × up = [0 -1 0], so board -Y is +right)"
    (let [pose {:heading [1 0 0] :up [0 0 1]}
          cmds (gizmo/snap-delta-commands pose [0 0 0] [0 -1.5 0] 0.01)]
      (is (= [[:cp-rt 1.5]] cmds)))))

(deftest snap-delta-commands-rounds-to-step
  (testing "each cp value is rounded to the (fine) snap step, no float noise"
    (let [pose {:heading [1 0 0] :up [0 0 1]}
          cmds (gizmo/snap-delta-commands pose [0 0 0] [1.2349 0 0] 0.01)]
      (is (= [[:cp-f 1.23]] cmds)))))

(deftest snap-delta-commands-dead-on-emits-nothing
  (testing "clicking the pose position itself emits no commands"
    (let [pose {:heading [1 0 0] :up [0 0 1]}]
      (is (= [] (gizmo/snap-delta-commands pose [5 5 5] [5 5 5] 0.01))))))

;; ============================================================
;; closest-point-on-triangle (Alt+snap: face point nearest the pose)
;; ============================================================

(def ^:private tri [[0 0 0] [4 0 0] [0 4 0]])

(deftest closest-point-on-triangle-interior-foot-of-perpendicular
  (testing "a point above the triangle projects straight down onto it"
    (is (= [1.0 1.0 0.0] (gizmo/closest-point-on-triangle [1 1 5] tri)))))

(deftest closest-point-on-triangle-vertex-region
  (testing "a point past vertex A clamps to A"
    (is (= [0 0 0] (gizmo/closest-point-on-triangle [-1 -1 0] tri)))))

(deftest closest-point-on-triangle-edge-region
  (testing "a point off edge AB clamps to the nearest point on that edge"
    (is (= [2.0 0.0 0.0] (gizmo/closest-point-on-triangle [2 -1 0] tri)))))

(deftest closest-point-on-triangle-far-edge-bc
  (testing "a point past the hypotenuse clamps to the nearest point on edge BC"
    (is (= [2.0 2.0 0.0] (gizmo/closest-point-on-triangle [5 5 0] tri)))))
