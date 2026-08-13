(ns ridley.photogrammetry.triangulate-test
  "Plane marks (dev-docs/brief-plane-marks.md, gradino 1): a flat zone of the
   object becomes a Ridley mark by triangulating a few hand-clicked points from
   registered cameras and fitting a plane to them.

   The properties under test are the ones the gesture's guards rest on: the
   triangulation must recover a known point at the click-noise floor and must
   REFUSE (or loudly report) the degenerate baseline; the plane fit must recover
   a known plane, orient its normal toward the cameras rather than into the
   object, and report a non-flat zone as non-flat instead of quietly tilting."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.synth :as synth]
            [ridley.photogrammetry.triangulate :as tri]))

(defn- k* []
  {:fx (* 4032 (/ 48.0 36.0)) :fy (* 4032 (/ 48.0 36.0))
   :cx 2016.0 :cy 1512.0 :k1 0.0 :k2 0.0})

(defn- fmt [x n] (.toFixed (js/Number. x) n))

(defn- ring-pose
  "A camera on a ~260mm ring at 150mm height, looking at the origin from
   `theta-deg` — the acquisition geometry the plate sessions actually use."
  [theta-deg]
  (let [a (* theta-deg (/ Math/PI 180.0))]
    (cam/look-at-pose [(* 260.0 (Math/cos a)) (* 260.0 (Math/sin a)) 150.0]
                      [0.0 0.0 20.0] [0.0 0.0 1.0])))

(defn- obs
  "Observations of `world` from the given ring angles, with gaussian click noise."
  ([world thetas] (obs world thetas 0.0 nil))
  ([world thetas sigma rng]
   (let [kk (k*)]
     (vec (keep (fn [th]
                  (let [pose (ring-pose th)]
                    (when-let [[u v] (cam/project kk pose world)]
                      {:pose pose
                       :px [(+ u (if rng (* sigma (synth/gauss rng)) 0.0))
                            (+ v (if rng (* sigma (synth/gauss rng)) 0.0))]})))
                thetas)))))

(defn- dist [a b]
  (Math/sqrt (reduce + (map #(* (- %1 %2) (- %1 %2)) a b))))

;; ---------------------------------------------------------------------------
;; Triangulation

(deftest triangulates-a-known-point-exactly-without-noise
  (println "\n=== Triangolazione: punto noto da raggi esatti ===")
  (doseq [[label world thetas] [["centro alto" [0.0 0.0 40.0] [0 90]]
                                ["angolo"      [18.0 -12.0 35.0] [0 120 240]]
                                ["basso"       [-25.0 5.0 3.0] [30 200]]]]
    (let [r (tri/triangulate (k*) (obs world thetas))]
      (println (str "  " label " " world " da " (count thetas) " foto → "
                    (mapv #(fmt % 3) (:point r))
                    ", errore " (fmt (dist world (:point r)) 4) "mm"
                    ", parallasse " (fmt (:parallax-deg r) 1) "°"))
      (testing label
        (is (some? r) "must triangulate")
        (is (< (dist world (:point r)) 1e-6)
            "exact rays → the point back to floating-point noise")
        (is (< (:max-residual-px r) 1e-6) "and zero reprojection residual")))))

(deftest click-noise-degrades-gracefully-and-is-reported
  (println "\n=== Triangolazione: rumore di click (2px) → errore mm + residuo ===")
  (let [rng (synth/rng 23)
        world [12.0 -8.0 38.0]
        trials (vec (for [_ (range 40)]
                      (tri/triangulate (k*) (obs world [0 90 180] 2.0 rng))))
        errs (mapv #(dist world (:point %)) trials)
        worst (reduce max errs)
        mean-res (/ (reduce + (map :rms-px trials)) (count trials))]
    (println (str "  40 prove, 3 foto, σ=2px → errore medio "
                  (fmt (/ (reduce + errs) (count errs)) 3) "mm, peggiore "
                  (fmt worst 3) "mm; residuo medio " (fmt mean-res 2) "px"))
    (is (every? some? trials) "noise must not break the solve")
    (is (< worst 2.0) (str "worst 3-view error " (fmt worst 3) " mm"))
    (is (> mean-res 0.2) "the residual must actually REPORT the noise, not read 0")))

(deftest a-mis-clicked-view-is-named-by-leave-one-out-not-by-its-residual
  (println "\n=== Triangolazione: chi ha sbagliato click? ===")
  (let [world [10.0 10.0 30.0]
        good (obs world [0 90 180])
        bad (assoc-in good [2 :px 0] (+ 120.0 (get-in good [2 :px 0])))
        r (tri/triangulate (k*) bad)
        residuals (mapv :residual-px (:per-obs r))
        by-residual (:i (apply max-key :residual-px (:per-obs r)))]
    (println (str "  residui per foto: " (mapv #(fmt % 1) residuals)
                  "px → residuo massimo = foto " by-residual
                  ", leave-one-out = foto " (:worst-obs r)))
    (testing "the aggregate alarm fires"
      (is (> (:max-residual-px r) 20.0) "a 120px slip must not pass quietly"))
    (testing "and the culprit is named correctly — which the residual alone does not do"
      (is (= 2 (:worst-obs r)) "leave-one-out finds the tampered view")
      ;; This used to assert that the max residual named an INNOCENT view. It did,
      ;; but only because ring-pose 90° was a half-turn and camera/rot-mat->rodrigues
      ;; got half-turns wrong until 2026-08-13 — the synthetic camera for that view
      ;; was not where the test thought it was. With the cameras correct, the max
      ;; residual happens to land on the guilty view here.
      ;;
      ;; That does not make the residual an identifier, and the numbers say why:
      ;; least squares SPREADS a slip across every ray, so the innocent views come
      ;; back nearly as accused as the guilty one. What is worth pinning is that
      ;; separation, not which view happens to top the list — a ranking whose top
      ;; two are within a few percent cannot single anybody out, and that is
      ;; exactly the gap :worst-obs exists to fill.
      (let [sorted (sort > residuals)]
        (is (> (/ (second sorted) (first sorted)) 0.8)
            (str "the residuals do not separate the guilty view from the innocent ones "
                 "(" (mapv #(fmt % 1) sorted) ") — which is why leave-one-out exists"))
        (is (some? by-residual) "…and the ranking itself is still reported")))))

(deftest a-clean-set-blames-nobody
  (let [rng (synth/rng 5)
        r (tri/triangulate (k*) (obs [4.0 2.0 33.0] [0 90 180 270] 1.5 rng))]
    (is (nil? (:worst-obs r))
        "ordinary click noise must not get an innocent view accused"))
  (testing "two views are symmetric — neither can be blamed"
    (let [good (obs [4.0 2.0 33.0] [0 90])
          bad (assoc-in good [1 :px 0] (+ 90.0 (get-in good [1 :px 0])))]
      (is (nil? (:worst-obs (tri/triangulate (k*) bad)))
          "with only two rays there is no evidence for which one is wrong"))))

(deftest a-degenerate-baseline-is-visible-as-tiny-parallax
  (println "\n=== Triangolazione: baseline degenere (foto adiacenti) ===")
  (let [world [0.0 0.0 40.0]
        near (tri/triangulate (k*) (obs world [0 2]))
        wide (tri/triangulate (k*) (obs world [0 90]))]
    (println (str "  foto a 2° → parallasse " (fmt (:parallax-deg near) 2)
                  "°, soglia " tri/min-parallax-deg "°"
                  " | foto a 90° → " (fmt (:parallax-deg wide) 1) "°"))
    (is (< (:parallax-deg near) tri/min-parallax-deg)
        "adjacent shots must fall below the guard")
    (is (> (:parallax-deg wide) tri/min-parallax-deg)
        "a quarter turn must pass it")))

(deftest one-view-cannot-triangulate
  (is (nil? (tri/triangulate (k*) (obs [0.0 0.0 40.0] [0])))
      "a single ray leaves the depth free — no point"))

;; ---------------------------------------------------------------------------
;; Plane fit

(defn- plane-points
  "`n` points spread on the plane through `o` spanned by u,v (both unit)."
  [o u v n radius]
  (mapv (fn [i]
          (let [a (* 2.0 Math/PI (/ (double i) n))
                du (* radius (Math/cos a))
                dv (* radius (Math/sin a))]
            (mapv (fn [oi ui vi] (+ oi (* du ui) (* dv vi))) o u v)))
        (range n)))

(deftest recovers-a-known-plane-and-faces-the-cameras
  (println "\n=== Fit del piano: normale e orientamento ===")
  (let [o [0.0 0.0 45.0]
        pts (plane-points o [1.0 0.0 0.0] [0.0 1.0 0.0] 5 20.0)
        cams-centre [180.0 0.0 200.0]           ; the ring, well above the face
        r (tri/fit-plane-mark pts {:toward cams-centre :up-hints [[0.0 0.0 1.0]]})]
    (println (str "  heading " (mapv #(fmt % 4) (:heading r))
                  " up " (mapv #(fmt % 4) (:up r))
                  " planarità " (fmt (:flatness-mm r) 5) "mm"))
    (is (> (nth (:heading r) 2) 0.999) "a level face's normal is +Z…")
    (is (< (:flatness-mm r) 1e-9) "…and the fit is exact on exact points")
    (is (< (Math/abs (reduce + (map * (:heading r) (:up r)))) 1e-9)
        "heading ⊥ up — the mark is a proper turtle frame")
    (testing "the normal follows the cameras, never into the object"
      (let [below (tri/fit-plane-mark pts {:toward [180.0 0.0 -200.0]})]
        (is (< (nth (:heading below) 2) -0.999)
            "cameras underneath → the mark faces DOWN")))))

(deftest the-up-hint-lands-in-the-plane
  (let [o [0.0 0.0 0.0]
        ;; a plane tilted 30° about X
        n [0.0 (- (Math/sin (/ Math/PI 6))) (Math/cos (/ Math/PI 6))]
        u [1.0 0.0 0.0]
        v [0.0 (Math/cos (/ Math/PI 6)) (Math/sin (/ Math/PI 6))]
        pts (plane-points o u v 6 15.0)
        r (tri/fit-plane-mark pts {:toward [0.0 -100.0 200.0] :up-hints [[0.0 0.0 1.0]]})]
    (is (< (Math/abs (- 1.0 (Math/abs (reduce + (map * (:heading r) n))))) 1e-9)
        "normal matches the tilted plane")
    (is (< (Math/abs (reduce + (map * (:heading r) (:up r)))) 1e-9)
        "up is IN the plane, not the raw world +Z")
    (is (> (nth (:up r) 2) 0.0) "and still points up-ish, as hinted")))

(deftest a-hint-along-the-normal-falls-through-to-the-next-one
  (testing "the level top face: the object's own up IS the normal, so the second
            hint (its heading) is what gives the mark a meaningful up"
    (let [pts (plane-points [0.0 0.0 45.0] [1.0 0.0 0.0] [0.0 1.0 0.0] 5 20.0)
          r (tri/fit-plane-mark pts {:toward [0.0 0.0 300.0]
                                     :up-hints [[0.0 0.0 1.0] [0.0 1.0 0.0]]})]
      (is (< (dist (:up r) [0.0 1.0 0.0]) 1e-9)
          "the usable hint wins; the parallel one is skipped, not projected to noise"))))

(deftest a-non-flat-zone-reports-its-bumpiness
  (println "\n=== Fit del piano: una zona che piana non è lo dice ===")
  (let [o [0.0 0.0 45.0]
        flat (plane-points o [1.0 0.0 0.0] [0.0 1.0 0.0] 6 20.0)
        ;; push one point 3mm proud — a rounded shoulder inside the clicked zone
        bumpy (update-in (vec flat) [3 2] + 3.0)
        r (tri/fit-plane-mark bumpy {:toward [180.0 0.0 200.0]})]
    (println (str "  scarti per punto (mm): " (mapv #(fmt % 2) (:per-point r))
                  " → planarità " (fmt (:flatness-mm r) 2) "mm"))
    ;; A least-squares plane TILTS to absorb part of a local bump, so a 3mm step
    ;; on 6 points reads as ~1.5mm of residual, not 3. That is enough to see —
    ;; the point of the number is 'this zone is not flat', not its exact height.
    (is (> (:flatness-mm r) 1.0) "a 3mm bump must surface as millimetres, not hide")
    (is (= 3 (first (apply max-key second (map-indexed (fn [i d] [i (Math/abs d)])
                                                       (:per-point r)))))
        "and :per-point names WHICH click to re-take")))

(deftest three-points-fit-exactly-so-flatness-checks-nothing
  (println "\n=== Fit del piano: 3 punti non verificano niente ===")
  (testing "any three points give flatness 0 — even ones that are not level"
    (let [ragged [[0.0 0.0 40.0] [20.0 3.0 41.7] [-6.0 18.0 38.2]]
          r (tri/fit-plane-mark ragged {:toward [0.0 0.0 300.0]})]
      (println (str "  3 punti sghembi → planarità " (fmt (:flatness-mm r) 4)
                    "mm, esatto? " (:exact? r)
                    ", presa " (fmt (:width-mm r) 1) "mm → "
                    (fmt (:tilt-per-mm-deg r) 2) "°/mm"))
      (is (< (:flatness-mm r) 1e-9) "zero by arithmetic, not by merit")
      (is (:exact? r) "and the fit says so, so the UI can stop claiming a check")))
  (testing "a fourth point makes it a real check again"
    (let [r (tri/fit-plane-mark [[0.0 0.0 40.0] [20.0 0.0 40.0] [0.0 20.0 40.0]
                                 [15.0 15.0 42.5]]
                                {:toward [0.0 0.0 300.0]})]
      (is (not (:exact? r)))
      (is (> (:flatness-mm r) 0.5) "the out-of-plane point finally shows up"))))

(deftest a-thin-spread-is-reported-as-poor-conditioning
  (println "\n=== Fit del piano: presa larga vs presa stretta ===")
  (let [fat (tri/fit-plane-mark [[0.0 0.0 40.0] [30.0 0.0 40.0] [15.0 26.0 40.0]]
                                {:toward [0.0 0.0 300.0]})
        thin (tri/fit-plane-mark [[0.0 0.0 40.0] [30.0 0.0 40.0] [15.0 1.5 40.0]]
                                 {:toward [0.0 0.0 300.0]})]
    (println (str "  triangolo largo: presa " (fmt (:width-mm fat) 1) "mm → "
                  (fmt (:tilt-per-mm-deg fat) 2) "°/mm"
                  " | stretto: presa " (fmt (:width-mm thin) 1) "mm → "
                  (fmt (:tilt-per-mm-deg thin) 2) "°/mm"))
    (is (< (:width-mm thin) (:width-mm fat)) "the sliver is narrower…")
    (is (> (:tilt-per-mm-deg thin) (* 3 (:tilt-per-mm-deg fat)))
        "…and its normal is far more sensitive — the number a user can act on
         when flatness has nothing to say")
    (is (< (js/Math.abs (- 26.0 (:width-mm fat))) 0.5)
        "for a triangle the width IS its shortest height")))

(deftest degenerate-inputs-return-nil
  (testing "fewer than 3 points is not a plane"
    (is (nil? (tri/fit-plane-mark [[0 0 0] [1 0 0]] {}))))
  (testing "collinear points are not a plane"
    (is (nil? (tri/fit-plane-mark [[0.0 0.0 0.0] [1.0 0.0 0.0] [2.0 0.0 0.0]
                                   [3.0 0.0 0.0]]
                                  {:toward [0.0 0.0 100.0]}))
        "plane-frame must refuse a line rather than invent a normal")))

;; ---------------------------------------------------------------------------
;; The one-click plate-parallel case + the disc preview

(deftest plane-through-point-declares-rather-than-fits
  (let [r (tri/plane-through-point [3.0 -4.0 52.0] [0.0 0.0 2.0] [[0.0 1.0 0.0]])]
    (is (= [0.0 0.0 1.0] (:heading r)) "normal normalised from the plate axis")
    (is (= [3.0 -4.0 52.0] (:position r)))
    (is (zero? (:flatness-mm r)) "nothing was fitted, so nothing is claimed")
    (is (< (Math/abs (reduce + (map * (:heading r) (:up r)))) 1e-12)))
  (testing "an up-hint parallel to the normal falls back to a valid in-plane axis"
    (let [r (tri/plane-through-point [0.0 0.0 10.0] [0.0 0.0 1.0] [[0.0 0.0 1.0]])]
      (is (some? (:up r)))
      (is (< (Math/abs (reduce + (map * (:heading r) (:up r)))) 1e-12)))))

(deftest the-verification-disc-lies-in-the-plane
  (let [mark {:position [0.0 0.0 45.0] :heading [0.0 0.0 1.0] :up [0.0 1.0 0.0]}
        {:keys [vertices faces]} (tri/disc-mesh mark 10.0 24)]
    (is (= 25 (count vertices)) "centre + rim")
    (is (= 24 (count faces)) "one triangle per segment")
    (is (every? (fn [[_ _ z]] (< (Math/abs (- z 45.0)) 1e-9)) vertices)
        "every vertex sits ON the plane — otherwise the check would be a lie")
    (is (every? (fn [p] (< (Math/abs (- 10.0 (Math/hypot (nth p 0) (nth p 1)))) 1e-9))
                (rest vertices))
        "and the rim is at the requested radius")))
