(ns ridley.turtle.preserve-position-test
  "Regression tests for the :preserve-position? shape attribute — the silent-drift
   family. WITH the flag a non-centred profile is stamped/extruded/lofted at its
   RAW 2D coords (frame origin [0 0] = the creation pose); WITHOUT it the profile is
   re-anchored so its FIRST vertex lands on the pose. There is no error either way —
   the only symptom is geometry silently shifted, so these assert the observable.

   The profile sits away from the frame origin ([30 10]…). At the default turtle pose
   the plane axes are right = [0 -1 0], up = [0 0 1], so a profile point [px py] maps
   to world [_, -px, py] — i.e. world-Y = -px. The offset is therefore directly
   readable off the offset value and off the lofted vertices."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.turtle.core :as t]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.loft :as loft]
            [ridley.turtle.extrusion :as ex]))

(def ^:private pts [[30 10] [50 10] [40 30]])

;; ---- the shared stamp/extrude offset core (compute-stamp-transform) -------------
;; Both `stamp` and `extrude` derive a section's 2D→3D offset here; the flag collapses
;; it to [0 0] instead of −(first vertex). Guarding the offset guards both ops at once.

(deftest stamp-transform-offset-honors-flags
  (let [st     (t/make-turtle)
        offset (fn [opts] (:offset (ex/compute-stamp-transform st (shape/make-shape pts opts))))]
    (testing "plain (non-centred): re-anchor on the first vertex"
      (is (= [-30 -10] (offset {:centered? false}))))
    (testing ":preserve-position? — no re-anchor, raw coords"
      (is (= [0 0] (offset {:centered? false :preserve-position? true}))))
    (testing ":centered? — also [0 0] (same offset, different intent)"
      (is (= [0 0] (offset {:centered? true}))))))

;; ---- two-shape loft must match (lerp-shape used to silently drop the flag) -------

(defn- loft-world-y
  "world-Y bounds of a two-shape loft of `shp` to itself along a short straight path —
   the form that routes through make-lerp-fn → lerp-shape (where the flag was lost)."
  [shp]
  (let [r  (loft/loft-from-path (t/make-turtle) shp (shape/make-lerp-fn shp shp)
                                (t/make-path [{:cmd :f :args [5]}]) 4)
        ys (mapcat #(map second (:vertices %)) (:meshes r))]
    [(Math/round (apply min ys)) (Math/round (apply max ys))]))

(deftest two-shape-loft-honors-preserve-position
  (testing "without the flag: section re-anchored on the first vertex"
    (is (= [-20 0] (loft-world-y (shape/make-shape pts {:centered? false})))))
  (testing "with the flag: section kept at raw coords (parity with extrude/stamp)"
    (is (= [-50 -30] (loft-world-y (shape/make-shape pts {:centered? false :preserve-position? true}))))))
