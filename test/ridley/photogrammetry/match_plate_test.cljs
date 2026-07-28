(ns ridley.photogrammetry.match-plate-test
  "Identity-free plate registration (fetta B): from 4 clicks on ARBITRARY crown
   discs — in any order, no declared ids — the exhaustive search must recover
   which mark each click is, INCLUDING the rotation, which only the asymmetric
   zero-index can fix (the crown is 12-fold symmetric). The judge is injected (a
   synthetic disc-at?), so the whole recovery is node-testable without a photo."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.match-plate :as mp]
            [ridley.photogrammetry.pnp :as pnp]
            [ridley.photogrammetry.synth :as synth]))

;; --- Synthetic plate: 12 crown marks + a zero-index, in the object frame ------

(def CROWN-R 58.0)
(def INDEX-R 52.0)
(def PLATE-Z 1.5)
(def DISC-R 1.25)

(defn- crown-xy [r deg]
  (let [a (* deg (/ Math/PI 180.0))] [(* r (Math/cos a)) (* r (Math/sin a))]))

(def marks
  (mapv (fn [i]
          (let [[x y] (crown-xy CROWN-R (* i 30.0))]
            {:id (keyword (str "m" i)) :obj [x y PLATE-Z]}))
        (range 12)))

(def zero-obj (let [[x y] (crown-xy INDEX-R 0.0)] [x y PLATE-Z]))

(defn- k* [] {:fx (* 4032 (/ 48.0 36.0)) :fy (* 4032 (/ 48.0 36.0))
              :cx 2016.0 :cy 1512.0 :k1 0.0 :k2 0.0})

(defn- dist-px [[ax ay] [bx by]]
  (Math/sqrt (+ (* (- ax bx) (- ax bx)) (* (- ay by) (- ay by)))))

(defn- disc-judge
  "A fake disc-at? for the synthetic scene: true iff the query pixel is within
   `tol` px of a TRUE projected disc centre. `discs` is the set of disc object
   points that actually carry ink (the 12 crown, plus the zero-index when the
   scene includes it). Ignores r — the synthetic discs are points."
  [pose discs tol]
  (let [pix (keep #(cam/project (k*) pose %) discs)]
    (fn [px _r] (boolean (some #(<= (dist-px px %) tol) pix)))))

(defn- crown-pixel [pose i] (cam/project (k*) pose (:obj (nth marks i))))

;; ---------------------------------------------------------------------------

(deftest recovers-scrambled-clicks-including-rotation
  (println "\n=== fetta B: identità dei mark da 4 click qualsiasi (con rotazione) ===")
  (let [crown-objs (mapv :obj marks)
        all-discs (conj crown-objs zero-obj)
        ;; the 4 physical marks the user clicks — an ASYMMETRIC pick so no
        ;; accidental symmetry helps — and a fixed permutation of the click order
        ;; (the software must not rely on the user clicking them in ring order).
        picked [0 2 5 9]
        click-src [5 0 9 2]]
    (doseq [[az el] [[0 40] [70 50] [140 35] [230 55]]]
      (let [pose (synth/viewpoint az el 250.0)
            judge (disc-judge pose all-discs 10.0)
            clicks (mapv #(crown-pixel pose %) click-src)
            res (mp/assign-marks clicks marks zero-obj (k*) judge {:disc-r DISC-R :face-normal [0 0 1]})]
        (println (str "  az=" az " el=" el " → crown-hits " (:crown-hits res)
                      " zero-hit? " (:zero-hit? res) " assignment " (:assignment res)))
        (testing (str "az " az " el " el)
          (is (some? res) "must return a candidate")
          (is (= 12 (:crown-hits res)) "the correct pose reprojects the whole crown onto discs")
          (is (:zero-hit? res) "the zero-index must land on its disc (the rotation is right)")
          ;; every click resolves to the exact mark it came from — proves both the
          ;; subset and the rotation were recovered (a 30°-shifted answer would map
          ;; each click to the neighbouring mark index)
          (doseq [j (range 4)]
            (is (= (nth click-src j) (get-in res [:assignment j]))
                (str "click " j " (mark " (nth click-src j) ") misassigned to "
                     (get-in res [:assignment j])))))))))

(deftest zero-index-resolves-a-symmetric-pick
  ;; 4 marks 90° apart is the WORST case for rotation: the crown count ties across
  ;; every quarter-turn (each maps the pick onto another 90°-spaced set), so
  ;; nothing but the asymmetric zero-index can decide which quarter is the true
  ;; one. Recovering the exact ids from a symmetric pick is the proof the zero-
  ;; index does its job — the whole reason the plate carries it.
  (println "\n=== fetta B: lo zero-indice risolve un click simmetrico (90°) ===")
  (doseq [[az el] [[25 45] [110 35] [200 50]]]
    (let [pose (synth/viewpoint az el 250.0)
          all-discs (conj (mapv :obj marks) zero-obj)
          judge (disc-judge pose all-discs 12.0)
          picked [0 3 6 9]                                ; the maximally-symmetric pick
          clicks (mapv #(crown-pixel pose %) picked)
          res (mp/assign-marks clicks marks zero-obj (k*) judge {:disc-r DISC-R :face-normal [0 0 1]})]
      (println (str "  az=" az " el=" el " → zero-hit? " (:zero-hit? res)
                    " assignment " (:assignment res) " score " (:score res)))
      (testing (str "az " az)
        (is (some? res))
        (is (:zero-hit? res) "the zero-index must resolve the quarter-turn ambiguity")
        ;; the winning score carries the zero bonus — i.e. the zero-index, not the
        ;; (tied) crown count, is what elected this orientation
        (is (>= (:score res) mp/zero-bonus) "the win includes the zero-index bonus")
        (doseq [j (range 4)]
          (is (= (nth picked j) (get-in res [:assignment j]))
              "the symmetric pick resolves to the exact marks, not a quarter-turn"))))))

(deftest rejects-too-few-clicks
  (testing "fewer than 4 clicks → nil (a homography needs 4 coplanar points)"
    (let [pose (synth/viewpoint 30 45 250.0)
          judge (disc-judge pose (conj (mapv :obj marks) zero-obj) 10.0)
          clicks (mapv #(crown-pixel pose %) [0 3 6])]
      (is (nil? (mp/assign-marks clicks marks zero-obj (k*) judge {:disc-r DISC-R :face-normal [0 0 1]})))))
  (testing "a missing zero-index object → nil (nothing to break the symmetry with)"
    (let [pose (synth/viewpoint 30 45 250.0)
          judge (disc-judge pose (mapv :obj marks) 10.0)
          clicks (mapv #(crown-pixel pose %) [0 3 6 9])]
      (is (nil? (mp/assign-marks clicks marks nil (k*) judge {:disc-r DISC-R :face-normal [0 0 1]}))))))

(deftest survives-click-noise
  ;; The batch clicks are blob-snapped (sub-px), so ~2px is already a pessimistic
  ;; hand-click residual; the assignment must still come back exact. The disc
  ;; judge's tolerance models "landed anywhere on the disc" — a real disc images
  ;; ~25px across, so 18px is realistic (not a fudge to pass).
  (println "\n=== fetta B: assegnazione robusta al rumore di click ===")
  (let [rng (synth/rng 31)
        pose (synth/viewpoint 100 40 250.0)
        all-discs (conj (mapv :obj marks) zero-obj)
        judge (disc-judge pose all-discs 18.0)
        picked [0 3 7 10]
        clicks (mapv (fn [i] (let [[u v] (crown-pixel pose i)]
                               [(+ u (* 2.0 (synth/gauss rng)))
                                (+ v (* 2.0 (synth/gauss rng)))]))
                     picked)
        res (mp/assign-marks clicks marks zero-obj (k*) judge {:disc-r DISC-R :face-normal [0 0 1]})]
    (println (str "  zero-hit? " (:zero-hit? res) " crown-hits " (:crown-hits res)
                  " assignment " (:assignment res)))
    (is (some? res))
    (is (:zero-hit? res))
    (doseq [j (range 4)]
      (is (= (nth picked j) (get-in res [:assignment j]))))))

;; ---------------------------------------------------------------------------
;; REAL-GEOMETRY regression — the paper-plate live gate (test-assets/param-plate-
;; paper, photo 1: 12 real disc clicks, iPhone 48mm-eq) exposed two things a
;; SYNTHETIC scene hides: (1) the marked-face normal is +Z-front-facing on real
;; data (so front-facing? with [0 0 1] is right); (2) estimate-homography's
;; UNREFINED seed reprojects the crown ~30px off — comparable to the disc size —
;; so the tight on-disc judge misses it. assign-marks' coarse-shortlist +
;; LM-refine + tight-score two-pass must still recover the exact identities from
;; as few as 4 clicks on this real oblique pose. :obj = plate-local [58cos30i
;; 58sin30i 1.5], zero at [52 0 1.5] — the replay convention that reproduced the
;; real fit. A perfect detector (proximity to the real disc pixels) is injected;
;; disc-at? on real LUMINANCE is validated separately (blob-snap + disc_check.js).

(def real-K {:fx (* 4032 (/ 48.0 36.0)) :fy (* 4032 (/ 48.0 36.0))
             :cx 2016.0 :cy 1512.0 :k1 0.0 :k2 0.0})

(def real-px-1
  [[3218.993 1879.945] [3298.751 1464.619] [3055.781 1093.775] [2610.352 838.678]
   [2065.807 726.64] [1516.539 769.356] [1055.131 965.28] [791.206 1311.538]
   [851.53 1739.381] [1304.244 2130.588] [2028.306 2318.092] [2760.542 2213.503]])

(deftest recovers-identities-on-real-oblique-pose
  (println "\n=== fetta B: recupero identità sulla geometria REALE (param-plate-paper foto 1) ===")
  (let [truesol (pnp/solve-pnp (mapv (fn [m p] {:world (:obj m) :px p}) marks real-px-1)
                               real-K {})
        [_ _ row2] (cam/rodrigues (:rvec (:pose truesol)))
        nz+ (reduce + (map * row2 [0.0 0.0 1.0]))
        zero-px (cam/project real-K (:pose truesol) zero-obj)
        alldisc (conj real-px-1 zero-px)
        ;; a perfect detector: a disc sits within a disc-radius of a real pixel
        judge (fn [p _r] (boolean (some #(<= (dist-px p %) 30.0) alldisc)))]
    (println (str "  true pose: " (name (:method truesol)) " rms " (.toFixed (:rms-px truesol) 2)
                  "px, +Z object " (if (neg? nz+) "front-facing (nz " "BACK-facing (nz ")
                  (.toFixed nz+ 3) ")"))
    (is (neg? nz+) "the marked-face +Z normal must be front-facing on the real pose")
    (doseq [picks [[0 3 7 10] [2 5 8 11] [0 1 3 4 6 7 9 10] (vec (range 12))]]
      (let [clicks (mapv #(nth real-px-1 %) picks)
            res (mp/assign-marks clicks marks zero-obj real-K judge
                                 {:disc-r DISC-R :face-normal [0.0 0.0 1.0]})]
        (println (str "  " (count picks) " click " picks " → "
                      (if res (str "crown " (:crown-hits res) " zero " (:zero-hit? res)
                                   " ok? " (= (mapv (:assignment res) (range (count picks))) picks))
                          "NIL")))
        (testing (str (count picks) " real clicks " picks)
          (is (some? res) "must assign")
          (is (:zero-hit? res) "the zero-index must resolve the rotation on real data")
          (is (>= (:crown-hits res) 10) "the refined pose reprojects the crown onto the discs")
          (doseq [j (range (count picks))]
            (is (= (nth picks j) (get-in res [:assignment j]))
                "each real click resolves to the mark it actually is")))))))
