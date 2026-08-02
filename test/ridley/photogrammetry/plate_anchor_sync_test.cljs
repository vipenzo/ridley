(ns ridley.photogrammetry.plate-anchor-sync-test
  "The plate's crown must stay glued to the plate through every transform.

   A crown mark sits at radius 58 on a disc of radius 65, in the plane of its
   marked face. That relationship is what the whole registration rests on, and
   it is also what the eye checks: project them together and every dot must fall
   INSIDE the disc's outline, on a circle concentric with it. A dot outside the
   disc is geometrically impossible — unless the anchors and the mesh have
   drifted apart.

   Vincenzo (2026-08-02) photographed exactly that: crown dots off the edge of
   the drawn proxy. These tests walk a plate through the transforms edit-acquire
   actually applies — the gizmo's translate and rotate, and the rigid
   group-transform used by snap/PnP/canonicalize — and check the relationship
   survives each one."
  (:require [cljs.test :refer [deftest is testing]]
            [ridley.photogrammetry.plate :as plate]
            [ridley.turtle.attachment :as attachment]
            [ridley.math :as m]))

(defn- crown [mesh] (vals (dissoc (:anchors mesh) :zero)))

(defn- geometry
  "Measured relationship between the anchors and the MESH they ride on:
   the crown's radius about the mesh axis, how far the crown deviates from a
   circle, and how far the anchors sit off the marked face."
  [mesh]
  (let [{:keys [position heading]} (:creation-pose mesh)
        n (m/normalize heading)
        pts (mapv :position (crown mesh))
        ;; the mesh's own extent, to compare the crown against
        rim (reduce max (map (fn [v]
                               (let [d (m/v- v position)]
                                 (m/magnitude (m/v- d (m/v* n (m/dot d n))))))
                             (:vertices mesh)))
        radii (mapv (fn [p] (let [d (m/v- p position)]
                              (m/magnitude (m/v- d (m/v* n (m/dot d n))))))
                    pts)
        heights (mapv (fn [p] (m/dot (m/v- p position) n)) pts)]
    {:rim rim
     :radius-min (reduce min radii) :radius-max (reduce max radii)
     :height-min (reduce min heights) :height-max (reduce max heights)}))

(defn- fmt [x] (.toFixed (js/Number. x) 4))

(defn- check! [label mesh]
  (let [{:keys [rim radius-min radius-max height-min height-max]} (geometry mesh)]
    (println (str "  " label ": corona r " (fmt radius-min) "…" (fmt radius-max)
                  " dentro un bordo di " (fmt rim)
                  " · quota " (fmt height-min) "…" (fmt height-max)))
    ;; Tolerance in MICROMETRES, not floating-point epsilon: group-transform
    ;; through a pose whose heading and up are not exactly perpendicular — every
    ;; pose that came out of a solver, including the one in Vincenzo's source —
    ;; is a hair of shear rather than a pure rotation. Measured here: the crown
    ;; leaves its plane by 2.5 µm and its height moves by 1.3 µm. Worth knowing,
    ;; worth nothing — four orders of magnitude below anything visible, and the
    ;; radius does not move at all.
    (testing label
      (is (< (- radius-max radius-min) 1e-2) "the crown stays a CIRCLE")
      (is (< radius-max rim)
          "and stays INSIDE the disc — a dot outside it is what desync looks like")
      (is (< (- height-max height-min) 1e-2) "all marks stay coplanar")
      (is (< (js/Math.abs (- height-max 1.5)) 1e-2)
          "on the marked face, half the thickness up"))))

(deftest the-crown-survives-every-transform-edit-acquire-applies
  (println "\n=== La corona resta incollata al piatto? ===")
  (let [p0 (plate/registration-plate)]
    (check! "appena creato      " p0)
    (check! "dopo una traslazione" (attachment/translate-mesh p0 [12.0 -5.0 30.0]))
    (check! "dopo una rotazione  " (attachment/rotate-mesh p0 [0.3 0.9 0.2] 0.7))
    (check! "dopo group-transform"
            (first (attachment/group-transform
                    [p0] [0 0 0] [0 0 1] [0 1 0]
                    [12 -5 30] [-0.7308 0.633 0.2556] [-0.5566 -0.7693 0.3137])))
    (testing "and through a whole chain of them, as a session accumulates"
      (check! "catena completa     "
              (-> p0
                  (attachment/translate-mesh [5.0 2.0 -3.0])
                  (attachment/rotate-mesh [0.0 1.0 0.0] 0.4)
                  (attachment/translate-mesh [-8.0 1.0 4.0])
                  (attachment/rotate-mesh [1.0 0.0 0.0] -0.9)
                  ((fn [m] (first (attachment/group-transform
                                   [m]
                                   (:position (:creation-pose m))
                                   (:heading (:creation-pose m))
                                   (:up (:creation-pose m))
                                   [0 0 0] [0 0 1] [0 1 0])))))))))
