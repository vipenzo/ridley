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
    (let [p (plate/registration-plate :d 130)]
      (is (= 12 (count (crown p))) "⌀130 → 12 crown marks by default")
      (is (contains? (:anchors p) :zero) "carries the zero-index")
      (is (= 1.25 (:mark-disc-r p)) "disc radius = disc/2")
      (is (seq (:vertices p)) "has geometry")
      (is (seq (:faces p)) "renders (a mesh, not empty)")))
  (testing "a bigger plate is a SCALED COPY, not a denser one"
    ;; It used to get proportionally more marks ("denser is what the extra
    ;; circumference and the tighter PnP want"), and that reasoning was never
    ;; checked against what a denser crown COSTS. assign-marks enumerates C(m,k)
    ;; subsets over the k marks a frame actually shows, and an object on the plate
    ;; always hides some: at 12 marks with 3 hidden that is ~4000 candidates (~4s),
    ;; at 24 it is ~85000 (~80s), at 36 half a million. A denser crown does not buy
    ;; accuracy — twelve marks spread around a 350mm plate condition the pose
    ;; BETTER than twelve around a 130 — it buys a registration that stops working
    ;; the moment three marks are covered. See plate/max-default-marks.
    (println "\n=== piatto: taglie diverse sono copie in scala ===")
    (doseq [d [130 200 250 300 350]]
      (let [p (plate/registration-plate :d d)
            [x y _] (:position (get (:anchors p) :m00))
            crown-r (Math/hypot x y)
            disc (* 2 (:mark-disc-r p))]
        (println (str "  ⌀" d " → " (count (crown p)) " mark, corona R"
                      (.toFixed crown-r 1) ", dischetto ⌀" (.toFixed disc 2)))
        (is (<= (count (crown p)) plate/max-default-marks)
            (str "⌀" d " non deve superare il tetto dei mark"))
        ;; the shape is the same at every size: crown radius and disc are the same
        ;; FRACTIONS of the diameter as on the reference 130
        (is (< (Math/abs (- (/ crown-r d) (/ 58.0 130.0))) 1e-9)
            (str "⌀" d ": la corona sta alla stessa frazione del diametro"))
        (is (< (Math/abs (- (/ disc d) (/ 2.5 130.0))) 1e-9)
            (str "⌀" d ": il dischetto scala col piatto"))))
    ;; …and the zero-index keeps its place relative to the crown
    (let [p (plate/registration-plate :d 300)
          [zx zy _] (:position (:zero (:anchors p)))
          [mx my _] (:position (:m00 (:anchors p)))]
      (is (< (Math/abs (- (- (Math/hypot mx my) (Math/hypot zx zy))
                          (* 300 (/ 6.0 130.0))))
             1e-9)
          "lo zero-indice resta alla stessa distanza proporzionale dal suo mark")))
  (testing ":marks pins the count; :disc pins the size"
    (is (= 16 (count (crown (plate/registration-plate :d 200 :marks 16)))))
    (is (= 2.0 (:mark-disc-r (plate/registration-plate :d 300 :disc 4.0))))))

(defn- k* [] {:fx (* 4032 (/ 48.0 36.0)) :fy (* 4032 (/ 48.0 36.0))
              :cx 2016.0 :cy 1512.0 :k1 0.0 :k2 0.0})
(defn- dist-px [[ax ay] [bx by]]
  (Math/sqrt (+ (* (- ax bx) (- ax bx)) (* (- ay by) (- ay by)))))

(deftest plate-frame-registers-end-to-end
  (println "\n=== registration-plate: frame validato end-to-end (assign-marks) ===")
  (let [p (plate/registration-plate :d 130)
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
