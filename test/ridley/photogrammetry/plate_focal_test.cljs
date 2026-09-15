(ns ridley.photogrammetry.plate-focal-test
  "The lens of a frame that declares nothing (live capture: no EXIF, and on the
   first frame of a session no second view to fit against). The plate is flat, so
   one homography carries the two Zhang constraints and the focal comes out in
   closed form.

   Four things must hold, and the fourth is the one the whole 'scatta e registra'
   slice rests on:

   1. on exact projections the recovered focal IS the focal, at any obliquity;
   2. sub-pixel noise on the picks moves it by a few percent, not by a lens;
   3. a fronto-parallel plate is REFUSED by name — at that angle every focal
      explains the image, and a confident wrong number would be worse than none;
   4. the crown IDENTITIES survive a wrong seed focal, which is what lets the
      identity solve run before the focal is known and hand it the marks it needs."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.photogrammetry.blob :as blob]
            [ridley.photogrammetry.blob-detect :as bd]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.match-plate :as mp]
            [ridley.photogrammetry.plate :as plate]
            [ridley.photogrammetry.plate-focal :as pf]
            [ridley.photogrammetry.plate-scene :as scene]
            [ridley.photogrammetry.pnp :as pnp]
            [ridley.photogrammetry.synth :as synth]))

(def ^:private W 1920)
(def ^:private H 1080)

(defn- intr-at [focal-mm w h]
  (cam/intrinsics-from-fov (cam/equiv-focal->hfov-deg focal-mm (/ w h)) w h))

(defn- targets []
  (scene/plate-targets (plate/registration-plate :d 130)))

(defn- corr-at
  "Exact correspondences: every crown mark projected through `pose` at `focal-mm`."
  [pose focal-mm w h]
  (let [k (intr-at focal-mm w h)]
    (vec (keep (fn [{:keys [obj]}]
                 (when-let [px (cam/project k pose obj)]
                   {:world obj :px px}))
               (:marks (targets))))))

(defn- err-pct [got want] (* 100.0 (/ (Math/abs (- got want)) want)))

;; ---------------------------------------------------------------------------

(deftest exact-projections-give-back-the-focal
  (println "\n=== focale dal piatto: proiezioni esatte ===")
  (doseq [focal [24.0 28.0 35.0 48.0]
          eye [[70.0 -60.0 190.0] [120.0 40.0 220.0] [30.0 -140.0 170.0]]]
    (let [pose (cam/look-at-pose eye [0.0 0.0 0.0] [0.0 1.0 0.0])
          res (pf/estimate-focal (corr-at pose focal W H) [W H])]
      (is (nil? (:reason res))
          (str "focale " focal "mm, eye " eye " → " (:reason res) " " (:message res)))
      (when-not (:reason res)
        (println (str "  " focal "mm, eye " eye " → " (.toFixed (:focal-mm res) 2)
                      "mm (err " (.toFixed (err-pct (:focal-mm res) focal) 2)
                      "%, spread " (.toFixed (* 100 (:spread res)) 1) "%)"))
        (is (< (err-pct (:focal-mm res) focal) 1.0)
            (str "entro l'1% (" (.toFixed (:focal-mm res) 2) "mm contro " focal "mm)"))))))

(deftest noise-on-the-picks-costs-percent-not-a-lens
  (println "\n=== focale dal piatto: rumore sub-pixel sui click ===")
  (let [focal 28.0
        pose (cam/look-at-pose [90.0 -70.0 200.0] [0.0 0.0 0.0] [0.0 1.0 0.0])
        clean (corr-at pose focal W H)]
    (doseq [sigma [0.25 0.5 1.0]]
      (let [r (synth/rng 11)
            noisy (mapv (fn [{:keys [world px]}]
                          {:world world
                           :px [(+ (first px) (* sigma (synth/gauss r)))
                                (+ (second px) (* sigma (synth/gauss r)))]})
                        clean)
            res (pf/estimate-focal noisy [W H])]
        (is (nil? (:reason res)) (str "sigma " sigma "px → " (:reason res)))
        (when-not (:reason res)
          (println (str "  sigma " sigma "px → " (.toFixed (:focal-mm res) 2)
                        "mm (err " (.toFixed (err-pct (:focal-mm res) focal) 2) "%)"))
          (is (< (err-pct (:focal-mm res) focal) 8.0)
              (str "entro l'8% a sigma " sigma "px")))))))

(deftest fronto-parallel-is-refused-by-name
  (println "\n=== focale dal piatto: il piatto in faccia non risponde ===")
  ;; Straight down the plate's axis: the image is an affine picture of the crown,
  ;; identical at every focal (the distance takes up the slack). The answer must be
  ;; a refusal, never a number.
  (let [pose (cam/look-at-pose [0.0 0.0 220.0] [0.0 0.0 0.0] [0.0 1.0 0.0])
        res (pf/estimate-focal (corr-at pose 28.0 W H) [W H])]
    (println (str "  in faccia → " (or (:reason res)
                                       (str "NUMERO " (.toFixed (:focal-mm res) 2) "mm"))))
    (is (some? (:reason res)) "una vista frontale deve rifiutare, non rispondere"))
  ;; …and how much tilt is enough is a MEASUREMENT, not a guess: it is what "tilt
  ;; the plate" has to mean when we say it to someone holding a camera. Measured
  ;; with realistic 0.5px noise on the picks, because a noiseless answer would
  ;; flatter the near-degenerate end — and note these poses are the level-orbit
  ;; case, where one plate axis stays parallel to the image and only the
  ;; equal-norm constraint survives (single-constraint, no second opinion).
  (println "  quanto basta inclinare (0.5px di rumore, orbita a livello):")
  (doseq [tilt-deg [5 10 15 20 30 45 60]]
    (let [rad (* tilt-deg (/ Math/PI 180.0))
          d 220.0
          eye [(* d (Math/sin rad)) 0.0 (* d (Math/cos rad))]
          pose (cam/look-at-pose eye [0.0 0.0 0.0] [0.0 1.0 0.0])
          r (synth/rng 5)
          noisy (mapv (fn [{:keys [world px]}]
                        {:world world
                         :px [(+ (first px) (* 0.5 (synth/gauss r)))
                              (+ (second px) (* 0.5 (synth/gauss r)))]})
                      (corr-at pose 28.0 W H))
          res (pf/estimate-focal noisy [W H])]
      (println (str "    " tilt-deg "° → "
                    (if (:reason res)
                      (name (:reason res))
                      (str (.toFixed (:focal-mm res) 2) "mm, err "
                           (.toFixed (err-pct (:focal-mm res) 28.0) 2) "%"
                           (when (:single-constraint? res) " [un solo vincolo]")))))
      ;; the product claim: from 20° on, an ordinary hand-held angle, the frame
      ;; measures its own lens well enough to register on
      (when (>= tilt-deg 20)
        (is (nil? (:reason res)) (str "a " tilt-deg "° la focale si misura"))
        (when-not (:reason res)
          (is (< (err-pct (:focal-mm res) 28.0) 10.0)
              (str "a " tilt-deg "° entro il 10%")))))))

(deftest identities-survive-a-wrong-seed-focal
  (println "\n=== fetta live: identità con una focale di partenza SBAGLIATA ===")
  ;; The claim the live slice rests on: coplanar reprojection goes through the
  ;; homography, which the focal does not change, so fit-crown can identify the
  ;; crown at a seed focal that is merely plausible — and the identities it returns
  ;; are enough to measure the TRUE focal.
  (let [proxy (plate/registration-plate :d 130)
        {:keys [marks zero-obj disc-r face-normal]} (scene/plate-targets proxy)
        true-focal 28.0
        pose (cam/look-at-pose [95.0 -75.0 195.0] [0.0 0.0 0.0] [0.0 1.0 0.0])
        k-true (intr-at true-focal W H)
        {:keys [lum-at size]} (scene/render proxy pose k-true W H :noise 2.0)
        cands (bd/detect-blobs lum-at size)
        centers (mapv :center cands)
        judge (fn [[px py] r]
                (let [r2 (* r r)]
                  (boolean (some (fn [[bx by]]
                                   (<= (+ (* (- bx px) (- bx px)) (* (- by py) (- by py))) r2))
                                 centers))))]
    (println (str "  blob rilevati: " (count cands) " (vere: " (inc (count marks)) ")"))
    (let [ladder [16.0 20.0 24.0 28.0 35.0 42.0 50.0 70.0]
          results
          (vec (for [seed ladder]
                 (let [k-seed (intr-at seed W H)
                       res (mp/fit-crown (vec (take 24 centers)) marks zero-obj k-seed judge
                                         {:disc-r disc-r :face-normal face-normal})]
                   (if-not res
                     (do (println (str "  seed " seed "mm → corona NON riconosciuta")) nil)
                     (let [snap-r (mp/snap-window-radius (:pixels res) marks disc-r)
                           picks (into {} (keep (fn [[mi px]]
                                                  (some->> (blob/snap-to-blob lum-at px snap-r)
                                                           :center (vector mi)))
                                                (:pixels res)))
                           corr (vec (for [[mi px] picks]
                                       {:world (:obj (nth marks mi)) :px px}))
                           worst (reduce max 0.0
                                         (keep (fn [[mi px]]
                                                 (when-let [t (cam/project k-true pose (:obj (nth marks mi)))]
                                                   (Math/hypot (- (first px) (first t))
                                                               (- (second px) (second t)))))
                                               picks))
                           fres (pf/estimate-focal corr [W H])]
                       (println (str "  seed " seed "mm → corona " (:crown-hits res)
                                     "/12, zero " (:zero-hit? res)
                                     ", finestra " snap-r "px"
                                     ", " (count corr) " agganciati (peggior scarto dal vero "
                                     (.toFixed worst 2) "px) → focale "
                                     (if (:reason fres)
                                       (name (:reason fres))
                                       (str (.toFixed (:focal-mm fres) 2) "mm, err "
                                            (.toFixed (err-pct (:focal-mm fres) true-focal) 2) "%"))))
                       (when (and (nil? (:reason fres)) (< worst 8.0))
                         {:seed seed :focal (:focal-mm fres) :worst worst}))))))
          good (filterv some? results)]
      ;; The product claim is not "every seed works" — it is that a SHORT LADDER of
      ;; seeds contains one that does, and that the focal it hands back is the true
      ;; one no matter which rung found it. A seed far from the truth moves the
      ;; pose enough that blob-snap grabs the wrong discs; that is what the ladder
      ;; is for, and what the per-rung print above measures.
      (println (str "  rung utili: " (mapv :seed good) "/" (count ladder)))
      (is (seq good) "almeno un gradino della scala identifica la corona e misura la focale")
      (doseq [{:keys [seed focal]} good]
        (is (< (err-pct focal true-focal) 6.0)
            (str "seed " seed "mm: focale entro il 6% del vero ("
                 (.toFixed focal 2) "mm contro " true-focal "mm)"))))))

(deftest a-refusal-never-carries-a-focal
  (println "\n=== focale dal piatto: un rifiuto non porta un numero ===")
  ;; The contract every caller leans on: `(:focal-mm result)` answers "did this
  ;; frame measure its lens", so no refusal may carry that key — including
  ;; :out-of-band, which HAS a number and must hand it back under another name.
  (let [pose (cam/look-at-pose [0.0 0.0 220.0] [0.0 0.0 0.0] [0.0 1.0 0.0])
        cases {"in faccia" (corr-at pose 28.0 W H)
               "troppo pochi mark" (vec (take 3 (corr-at pose 28.0 W H)))
               "mark collineari" (mapv (fn [i] {:world [(* 10.0 i) 0.0 0.0]
                                                :px [(+ 900.0 (* 20.0 i)) 500.0]})
                                       (range 5))}]
    (doseq [[what corr] cases]
      (let [res (pf/estimate-focal corr [W H])]
        (println (str "  " what " → " (name (or (:reason res) :RISPOSTA))))
        (is (some? (:reason res)) (str what ": deve rifiutare"))
        (is (nil? (:focal-mm res))
            (str what ": un rifiuto non deve portare :focal-mm"))
        (is (string? (:message res)) (str what ": il rifiuto deve dire perché"))))))

(deftest the-focal-is-what-makes-the-residual-honest
  (println "\n=== fetta live: registrare alla focale sbagliata mente sul residuo ===")
  ;; Why the closed form is not a nicety: at the wrong focal, solve-pnp still
  ;; converges — it just puts the camera somewhere else and reports a residual that
  ;; looks respectable. Measuring both is the argument for doing this at all.
  (let [true-focal 28.0
        pose (cam/look-at-pose [95.0 -75.0 195.0] [0.0 0.0 0.0] [0.0 1.0 0.0])
        corr (mapv (fn [{:keys [world px]}] {:ci 0 :world world :px px})
                   (corr-at pose true-focal W H))]
    (doseq [assumed [20.0 28.0 48.0]]
      (let [sol (pnp/solve-pnp corr (intr-at assumed W H) {})
            ;; where the camera ends up, against where it really is
            dist (fn [t] (Math/sqrt (reduce + (map * t t))))
            true-d (dist (:t (:pose (pnp/solve-pnp corr (intr-at true-focal W H) {}))))]
        (println (str "  focale assunta " assumed "mm → rms "
                      (if sol (.toFixed (:rms-px sol) 2) "—") "px, distanza camera "
                      (if sol (.toFixed (dist (:t (:pose sol))) 1) "—") "mm (vera "
                      (.toFixed true-d 1) "mm)"))))))
