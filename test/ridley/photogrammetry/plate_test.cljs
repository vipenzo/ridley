(ns ridley.photogrammetry.plate-test
  "The parametric registration-plate PROXY: it must carry the crown + zero-index
   under :anchors, scale the default count with the diameter, and — the reason it
   is built natively rather than eval'd from the example — produce the SAME object
   frame match-plate/pnp were validated against, so identity-free assignment
   recovers the marks end-to-end through the real bridge."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.photogrammetry.plate :as plate]
            [ridley.photogrammetry.bridge :as bridge]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.match-plate :as mp]
            [ridley.photogrammetry.synth :as synth]))

(defn- crown [p] (dissoc (:anchors p) :zero))

(deftest builds-a-parametric-plate
  (testing "defaults: 12 crown marks + zero-index + disc radius, renders as a mesh"
    (let [p (plate/registration-plate)]
      (is (= 12 (count (crown p))) "⌀130 → 12 crown marks by default")
      (is (contains? (:anchors p) :zero) "carries the zero-index")
      (is (= 1.25 (:mark-disc-r p)) "disc radius = disc/2")
      (is (seq (:vertices p)) "has geometry")
      (is (seq (:faces p)) "renders (a mesh, not empty)")))
  (testing "a bigger diameter gets denser marks by default"
    (is (> (count (crown (plate/registration-plate :d 260))) 12)))
  (testing ":marks pins the count; :d changes the crown radius"
    (is (= 16 (count (crown (plate/registration-plate :d 200 :marks 16)))))))

(defn- k* [] {:fx (* 4032 (/ 48.0 36.0)) :fy (* 4032 (/ 48.0 36.0))
              :cx 2016.0 :cy 1512.0 :k1 0.0 :k2 0.0})
(defn- dist-px [[ax ay] [bx by]]
  (Math/sqrt (+ (* (- ax bx) (- ax bx)) (* (- ay by) (- ay by)))))

(deftest plate-frame-registers-end-to-end
  (println "\n=== registration-plate: frame validato end-to-end (assign-marks) ===")
  (let [p (plate/registration-plate)
        targets (bridge/pnp-target-points p (:creation-pose p))
        det (bridge/plate-detect p)
        pose (synth/viewpoint 40 45 250.0)
        crown-px (mapv #(cam/project (k*) pose (:obj %)) targets)
        zero-px (cam/project (k*) pose (:zero-obj det))
        discs (conj crown-px zero-px)
        judge (fn [pt _r] (boolean (some #(<= (dist-px pt %) 12.0) discs)))
        picked [0 3 7 10]
        clicks (mapv #(nth crown-px %) picked)
        res (mp/assign-marks clicks targets (:zero-obj det) (k*) judge
                             {:disc-r (:disc-r det) :face-normal (:face-normal det)})]
    (is (= 12 (count targets)) "pnp targets exclude the zero-index")
    ;; the crown :objs sit on a circle of the crown radius (⌀130 → 58) in a face
    (is (< (Math/abs (- 58.0 (let [[x y _] (:obj (first targets))] (Math/sqrt (+ (* x x) (* y y)))))) 1e-6)
        "crown radius = d/2 - margin")
    (is (< (Math/abs (- 1.0 (nth (:face-normal det) 2))) 1e-6) "marked-face normal is +Z")
    (is (some? res))
    (is (:zero-hit? res) "the zero-index resolves the rotation on the native plate")
    (doseq [j (range 4)]
      (is (= (nth picked j) (get-in res [:assignment j]))
          "each click resolves to the mark it is"))))
