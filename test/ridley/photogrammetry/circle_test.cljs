(ns ridley.photogrammetry.circle-test
  "Measured CIRCLES and arcs (dev-docs/brief-observation-driven-acquire.md,
   gradino 3): the curved half of the edge gesture.

   A straight edge is pinned by a closed form — a line in an image spans a plane,
   two planes meet in one line. A curve spans a CONE, and two cones meet in a
   quartic, so that shortcut is gone. What replaces it is tested here: rays from
   two photos that pass close together mark a point of the curve, and the circle
   is fitted to those points.

   The subtlety the tests are really guarding is the GHOSTS. A ray from photo A
   pierces photo B's cone TWICE — once at the true point of the rim, once at a
   point that images correctly in both photos and lies nowhere near the object.
   Both meet to within a fraction of a millimetre, so no distance test separates
   them, and about half the recovered cloud is false. It is the circle itself
   that decides, by RANSAC; `:cloud-n` vs `:seed-n` reports the carnage."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.circle :as pc]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.synth :as synth]))

(defn- k* []
  {:fx (* 4032 (/ 48.0 36.0)) :fy (* 4032 (/ 48.0 36.0))
   :cx 2016.0 :cy 1512.0 :k1 0.0 :k2 0.0})

(defn- fmt [x n] (.toFixed (js/Number. x) n))

(defn- ring-pose [theta-deg]
  (let [a (* theta-deg (/ Math/PI 180.0))]
    (cam/look-at-pose [(* 260.0 (Math/cos a)) (* 260.0 (Math/sin a)) 150.0]
                      [0.0 0.0 20.0] [0.0 0.0 1.0])))

(defn- unit [v] (la/v-scale v (/ 1.0 (la/v-norm v))))

(defn- cross3 [[a b c] [d e f]]
  [(- (* b f) (* c e)) (- (* c d) (* a f)) (- (* a e) (* b d))])

(defn- circle-3d
  "Points of a known 3D circle, `from`→`to` degrees around it."
  [center normal radius from to n]
  (let [nn (unit normal)
        u (unit (cross3 nn (if (> (Math/abs (nth nn 2)) 0.9) [1.0 0.0 0.0] [0.0 0.0 1.0])))
        v (cross3 nn u)]
    (mapv (fn [i]
            (let [a (* (/ Math/PI 180.0) (+ from (* (- to from) (/ (double i) (dec n)))))]
              (la/v-add center
                        (la/v-add (la/v-scale u (* radius (Math/cos a)))
                                  (la/v-scale v (* radius (Math/sin a)))))))
          (range n))))

(defn- view-of
  "What ONE photo declares: the walked points along the curve as they land in
   that photo. `sigma` displaces each one, as a snap on a real edge would."
  ([center normal radius theta from to] (view-of center normal radius theta from to 0.0 nil))
  ([center normal radius theta from to sigma rng]
   (let [pose (ring-pose theta)
         k (k*)]
     {:pose pose :intrinsics k
      :points (vec (keep (fn [p]
                           (when-let [[u v] (cam/project k pose p)]
                             [(+ u (if rng (* sigma (synth/gauss rng)) 0.0))
                              (+ v (if rng (* sigma (synth/gauss rng)) 0.0))]))
                         (circle-3d center normal radius from to 60)))})))

(defn- dist [a b] (la/v-norm (la/v-sub a b)))

(defn- normal-deg [a b]
  (let [c (Math/abs (max -1.0 (min 1.0 (la/v-dot (unit a) (unit b)))))]
    (* (/ 180.0 Math/PI) (Math/acos c))))

;; ---------------------------------------------------------------------------

(deftest a-full-circle-comes-back-from-two-photos
  (println "\n=== Cerchio: una circonferenza nota da due foto ===")
  (doseq [[label center normal radius]
          [["orizzontale" [0.0 0.0 40.0] [0.0 0.0 1.0] 25.0]
           ["verticale"   [5.0 -8.0 35.0] [1.0 0.0 0.0] 18.0]
           ["obliqua"     [-6.0 4.0 30.0] [0.4 0.5 0.77] 22.0]]]
    (let [views [(view-of center normal radius 0 0 360)
                 (view-of center normal radius 100 0 360)]
          r (pc/fit-circle views)]
      (println (str "  " label ": centro " (fmt (dist (:center r) center) 4)
                    " mm dal vero · raggio " (fmt (:radius r) 3) " (vero " radius
                    ") · normale " (fmt (normal-deg (:normal r) normal) 3)
                    "° · arco " (fmt (:span-deg r) 0) "° · riproiezione "
                    (fmt (:rms-px r) 3) " px · nuvola " (:seed-n r) "/" (:cloud-n r)))
      (testing label
        (is (some? r) "two photos of a full circle must be enough")
        (is (< (dist (:center r) center) 0.05) "centre to a twentieth of a millimetre")
        (is (< (Math/abs (- (:radius r) radius)) 0.05) "and the radius with it")
        (is (< (normal-deg (:normal r) normal) 0.3) "the plane it lies in")
        (is (< (:rms-px r) 0.5) "with the declared points back on the curve")))))

(deftest the-cloud-the-fit-is-built-on-is-clean
  (println "\n=== Cerchio: la nuvola su cui si costruisce è pulita ===")
  ;; A ray of A pierces B's CONE twice, so ghost meetings exist by construction
  ;; and the only question is how many survive. What keeps them out is the
  ;; distance threshold, and it is not a guess: on Vincenzo's plate a right
  ;; pairing passed at 0.15 mm and a wrong one at 3.3 mm. The first version used
  ;; 1.5 mm and let in so many that RANSAC preferred a circle of radius 1838 mm
  ;; to a rim of 65.
  (let [center [0.0 0.0 40.0] normal [0.0 0.0 1.0] radius 25.0
        r (pc/fit-circle [(view-of center normal radius 0 0 360)
                          (view-of center normal radius 100 0 360)])
        off (mapv (fn [p]
                    (let [d (la/v-sub p (:center r))
                          out (la/v-dot d (:normal r))
                          inp (Math/sqrt (max 0.0 (- (la/v-dot d d) (* out out))))]
                      (Math/hypot out (- inp (:radius r)))))
                  (:points-3d r))]
    (println (str "  nuvola " (:cloud-n r) " incroci → " (:seed-n r)
                  " tenuti · scarto peggiore dal cerchio "
                  (fmt (reduce max off) 4) " mm"))
    (is (> (:seed-n r) 10) "enough points must survive to fit with")
    (is (< (reduce max off) 0.5)
        "and every one of them must actually be ON the circle")))

(deftest grazing-photos-do-not-produce-a-confident-wrong-circle
  (println "\n=== Cerchio: due foto radenti non inventano un cerchio sicuro ===")
  ;; The real failure, reproduced: two cameras low over the circle's own plane
  ;; see it nearly edge-on, so their rays are almost coplanar and pass close to
  ;; each other all over the place. That is what flooded the cloud on the plate.
  ;; The fit may refuse; what it may NOT do is answer confidently and wrongly.
  (let [center [0.0 0.0 40.0] normal [0.0 0.0 1.0] radius 25.0
        low (fn [theta from to]
              (let [a (* theta (/ Math/PI 180.0))
                    pose (cam/look-at-pose [(* 300.0 (Math/cos a)) (* 300.0 (Math/sin a)) 46.0]
                                           [0.0 0.0 40.0] [0.0 0.0 1.0])]
                {:pose pose :intrinsics (k*)
                 :points (vec (keep #(cam/project (k*) pose %)
                                    (circle-3d center normal radius from to 60)))}))
        r (pc/fit-circle [(low 0 100 260) (low 40 100 260)])]
    (println (str "  radenti → " (if r (str "raggio " (fmt (:radius r) 1)
                                            " (vero " radius "), scarto "
                                            (fmt (:rms-px r) 1) " px, arco "
                                            (fmt (:span-deg r) 0) "°")
                                     "rifiutato (nil)")))
    (is (or (nil? r)
            (< (Math/abs (- (:radius r) radius)) radius)
            (> (:rms-px r) 4.0))
        "either it gets the circle, or its residual SAYS it did not")))

(deftest click-noise-degrades-gracefully
  (println "\n=== Cerchio: rumore di 2px sui punti agganciati ===")
  (let [rng (synth/rng 19)
        center [0.0 0.0 40.0] normal [0.0 0.0 1.0] radius 25.0
        trials (vec (keep (fn [i]
                            (pc/fit-circle
                             [(view-of center normal radius (* i 7) 0 360 2.0 rng)
                              (view-of center normal radius (+ 100 (* i 7)) 0 360 2.0 rng)]))
                          (range 12)))
        errs (mapv #(dist (:center %) center) trials)
        rerr (mapv #(Math/abs (- (:radius %) radius)) trials)]
    (println (str "  " (count trials) " prove: centro medio "
                  (fmt (/ (reduce + errs) (count errs)) 3) " mm, peggiore "
                  (fmt (reduce max errs) 3) " · raggio medio "
                  (fmt (/ (reduce + rerr) (count rerr)) 3) " mm"))
    (is (= 12 (count trials)) "noise must not break the fit")
    (is (< (/ (reduce + errs) (count errs)) 1.0)
        "2px of snap noise is worth well under a millimetre on the centre")))

(deftest an-arc-is-measurable-and-says-how-much-of-it-was-seen
  (println "\n=== Cerchio: un ARCO, e quanto se ne è visto ===")
  (doseq [[from to] [[0 200] [0 140] [30 100]]]
    (let [center [0.0 0.0 40.0] normal [0.0 0.0 1.0] radius 25.0
          views [(view-of center normal radius 0 from to)
                 (view-of center normal radius 100 from to)]
          r (pc/fit-circle views)]
      (println (str "  arco " (- to from) "° → " (if r (str "centro "
                                                            (fmt (dist (:center r) center) 3)
                                                            " mm, raggio " (fmt (:radius r) 2)
                                                            ", riportato " (fmt (:span-deg r) 0) "°"
                                                            (when (< (:span-deg r) pc/min-arc-deg)
                                                              " (SOTTO la soglia)"))
                                                     "non risolto")))
      (testing (str (- to from) "°")
        (is (some? r) "a partial arc is still a measurement")
        (is (< (Math/abs (- (:span-deg r) (- to from))) 15.0)
            "and it must report honestly how much of the circle it rests on")))))

(deftest a-short-arc-is-reported-as-under-conditioned
  (println "\n=== Cerchio: un arco corto NON è una misura del raggio ===")
  (let [center [0.0 0.0 40.0] normal [0.0 0.0 1.0] radius 25.0
        r (pc/fit-circle [(view-of center normal radius 0 0 50)
                          (view-of center normal radius 100 0 50)])]
    (println (str "  arco di 50° → arco riportato " (fmt (:span-deg r) 0)
                  "° (soglia " pc/min-arc-deg "°), raggio " (fmt (:radius r) 2)
                  " contro " radius))
    (is (< (:span-deg r) pc/min-arc-deg)
        "the number that tells the caller not to trust the radius must be there")))

(deftest the-mark-stands-on-the-circle-facing-the-cameras
  (println "\n=== Cerchio: il mark ci si posa sopra, rivolto alle camere ===")
  (let [circ {:center [0.0 0.0 40.0] :normal [0.0 0.0 -1.0] :radius 25.0}
        m (pc/circle-mark circ {:toward [0.0 0.0 300.0] :up-hints [[0.0 0.0 1.0] [0.0 1.0 0.0]]})]
    (println (str "  posizione " (clj->js (mapv #(fmt % 1) (:position m)))
                  " heading " (clj->js (mapv #(fmt % 2) (:heading m)))
                  " up " (clj->js (mapv #(fmt % 2) (:up m)))
                  " raggio " (:radius m)))
    (is (< (dist (:position m) [0.0 0.0 40.0]) 1e-9) "origin at the centre")
    (is (> (la/v-dot (:heading m) [0.0 0.0 1.0]) 0.99)
        "the normal is flipped to face the cameras, so an extrusion comes OUT")
    (is (< (Math/abs (la/v-dot (:heading m) (:up m))) 1e-9) "up ⊥ heading")
    (is (= 25.0 (:radius m)) "and the radius travels with it")))
