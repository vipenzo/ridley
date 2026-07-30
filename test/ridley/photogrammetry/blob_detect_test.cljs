(ns ridley.photogrammetry.blob-detect-test
  "Global dark-disc detector (fetta C): on a whole synthetic frame it must recall
   every disc-shaped dark region to a few px, REJECT the non-disc distractors an
   adaptive threshold would otherwise pass (the big dark 'part', a thin line/edge),
   and bound the candidate count — the recall + precision fit-crown's search rests
   on. Then the same on a projected registration-plate scene (crown + zero-index)."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [ridley.photogrammetry.blob-detect :as bd]
            [ridley.photogrammetry.blob :as blob]
            [ridley.photogrammetry.match-plate :as mp]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.plate :as plate]
            [ridley.photogrammetry.plate-scene :as scene]
            [ridley.photogrammetry.synth :as synth]))

(defn- scene-lum
  "A luminance sampler over a w×h light frame with filled dark discs at `discs`
   ([u v r] …). Optional deterministic gaussian noise."
  [w h light discs & {:keys [dark noise seed] :or {dark 30 noise 0.0 seed 3}}]
  (let [buf (js/Uint8Array. (* w h))
        _ (.fill buf light)
        r (synth/rng seed)]
    (doseq [[u v rad] discs]
      (doseq [y (range (max 0 (- v rad)) (min h (+ v rad 1)))
              x (range (max 0 (- u rad)) (min w (+ u rad 1)))]
        (when (<= (+ (* (- x u) (- x u)) (* (- y v) (- y v))) (* rad rad))
          (aset buf (+ x (* y w)) dark))))
    (when (pos? noise)
      (dotimes [k (* w h)] (aset buf k (max 0 (min 255 (Math/round (+ (aget buf k) (* noise (synth/gauss r)))))))))
    (fn [x y] (let [xi (Math/round x) yi (Math/round y)]
                (when (and (>= xi 0) (>= yi 0) (< xi w) (< yi h)) (aget buf (+ xi (* yi w))))))))

(defn- near [cands [u v] tol]
  (some (fn [{[cu cv] :center}] (< (Math/hypot (- cu u) (- cv v)) tol)) cands))

(deftest recalls-discs-rejects-distractors
  (println "\n=== detector: recupera i dischetti, scarta parte/linee ===")
  (let [w 900 h 675
        discs [[200 180 14] [640 160 13] [300 480 14] [700 500 13] [460 330 14]]
        ;; distractors: a big dark 'part' (over-area) and a thin dark line (low fill)
        part [[460 560 34]]
        line (for [t (range 0 60)] [(+ 100 t) 100 2])
        lum (scene-lum w h 235 (concat discs part line) :noise 3.0)
        cands (bd/detect-blobs lum [w h])]
    (println (str "  candidati: " (count cands)
                  " · centri " (mapv (fn [c] (mapv #(Math/round %) (:center c))) cands)))
    (doseq [[u v _] discs]
      (is (near cands [u v] 6.0) (str "il dischetto (" u "," v ") va rilevato")))
    (is (not (near cands [460 560] 20.0)) "la 'parte' (sovradimensionata) NON è un candidato")
    (is (<= (count cands) 10) "l'insieme dei candidati resta limitato")))

(deftest sub-pixel-centroids
  (testing "centroid within ~1.5px of a clean disc centre"
    (let [w 700 h 520
          discs [[333 271 15] [180 400 14]]
          lum (scene-lum w h 232 discs)
          cands (bd/detect-blobs lum [w h])]
      (doseq [[u v _] discs]
        (let [c (first (filter (fn [{[cu cv] :center}] (< (Math/hypot (- cu u) (- cv v)) 8.0)) cands))]
          (is (some? c) (str "disco (" u "," v ") rilevato"))
          (when c
            (is (< (Math/hypot (- ((:center c) 0) u) (- ((:center c) 1) v)) 1.5)
                (str "centro entro 1.5px, err "
                     (.toFixed (Math/hypot (- ((:center c) 0) u) (- ((:center c) 1) v)) 2)))))))))

(deftest plate-scene-recall
  (println "\n=== detector: scena piatto sintetica (corona + zero) ===")
  (let [proxy (plate/registration-plate)
        w 4032 h 3024
        intr (cam/intrinsics-from-fov (cam/equiv-focal->hfov-deg 48.0 (/ w h)) w h)]
    (doseq [eye [[40.0 -30.0 210.0] [90.0 60.0 240.0]]]
      (let [pose (cam/look-at-pose eye [0.0 0.0 0.0] [0.0 1.0 0.0])
            {:keys [lum-at size truth]} (scene/render proxy pose intr w h)
            cands (bd/detect-blobs lum-at size)
            recalled (count (filter (fn [[_ px]] (near cands px 12.0)) truth))]
        (println (str "  eye=" eye " · candidati " (count cands)
                      " · recall corona " recalled "/" (count truth)))
        (is (>= recalled (dec (count truth)))
            (str "quasi tutti i mark della corona rilevati (" recalled "/" (count truth) ")"))
        (is (<= (count cands) 40) "candidati limitati")))))

(deftest rgba-fast-path-matches
  ;; The production fast path (:rgba raw byte array, downsampled in one tight loop)
  ;; must find the SAME discs as the lum-at path — a grey disc scene rendered into an
  ;; RGBA buffer (R=G=B=grey, A=255) is detected identically.
  (testing "the :rgba downsample finds the same discs as lum-at"
    (let [w 800 h 600
          discs [[240 200 14] [560 200 13] [400 420 14]]
          lum (scene-lum w h 235 discs)
          rgba (js/Uint8ClampedArray. (* 4 w h))]
      (dotimes [y h]
        (dotimes [x w]
          (let [g (or (lum x y) 0) o (* 4 (+ x (* y w)))]
            (aset rgba o g) (aset rgba (+ o 1) g) (aset rgba (+ o 2) g) (aset rgba (+ o 3) 255))))
      (let [a (bd/detect-blobs lum [w h])
            b (bd/detect-blobs lum [w h] {:rgba rgba})]
        (doseq [[u v _] discs]
          (is (near a [u v] 6.0) (str "lum-at path trova (" u "," v ")"))
          (is (near b [u v] 6.0) (str ":rgba path trova (" u "," v ")")))
        (is (= (count a) (count b)) "stesso numero di candidati sui due percorsi")))))

(defn- node-require [m] (try (js/require m) (catch :default _ nil)))

(deftest real-photo-detect-and-fit
  ;; The fetta-C REAL-DATA gate, folded into the suite: run the ACTUAL cljs detector
  ;; + fit-crown on a real JPEG (param-plate-paper photo 0), decoded with sharp, and
  ;; check recall of the 12 crown marks (vs the hand-clicked ground truth in
  ;; acquire-state.json) and that fit-crown selects+identifies the crown. Guarded —
  ;; runs only where node fs+sharp and the (untracked) photos are present; elsewhere
  ;; (CI) it prints a skip and passes, so the suite never depends on the photos.
  (let [fs (node-require "fs")
        sharp (node-require "sharp")
        dir "test-assets/param-plate-paper"
        state-path (str dir "/acquire-state.json")
        file (str dir "/IMG_8938.jpeg")]
    (if (or (nil? fs) (nil? sharp)
            (not (.existsSync fs state-path)) (not (.existsSync fs file)))
      (println "\n=== detector reale: SALTATO (fs/sharp/foto assenti) ===")
      (async done
             (let [st (js/JSON.parse (.readFileSync fs state-path "utf8"))
                   picks (js->clj (aget (aget (.-pnp st) "0") "picks"))
                   gt (into {} (for [[k v] picks] [(js/parseInt k) (get v "px")]))
                   proxy (plate/registration-plate)
                   {:keys [marks zero-obj disc-r face-normal]} (scene/plate-targets proxy)]
               (println "\n=== detector reale: param-plate-paper foto 0 (JPEG vero) ===")
               ;; RGBA (ensureAlpha) so the test exercises the PRODUCTION fast path
               ;; (:rgba raw array the browser feeds), not a greyscale-only sampler.
               (-> (.toBuffer (.raw (.ensureAlpha (sharp file))) #js {:resolveWithObject true})
                   (.then (fn [res]
                            (let [data (.-data res) info (.-info res)
                                  w (.-width info) h (.-height info)
                                  lum-at (fn [x y] (let [xi (Math/round x) yi (Math/round y)]
                                                     (when (and (>= xi 0) (>= yi 0) (< xi w) (< yi h))
                                                       (let [o (* 4 (+ xi (* yi w)))]
                                                         (+ (* 0.299 (aget data o)) (* 0.587 (aget data (+ o 1)))
                                                            (* 0.114 (aget data (+ o 2))))))))
                                  intr (cam/intrinsics-from-fov (cam/equiv-focal->hfov-deg 48.0 (/ w h)) w h)
                                  cands (bd/detect-blobs lum-at [w h] {:rgba data})
                                  near? (fn [[u v]] (some (fn [{[cu cv] :center}]
                                                            (< (Math/hypot (- cu u) (- cv v)) 22.0)) cands))
                                  recalled (count (filter (fn [[_ px]] (near? px)) gt))
                                  judge (fn [px r] (blob/disc-at? lum-at px r))
                                  fit (mp/fit-crown (mapv :center cands) marks zero-obj intr judge
                                                    {:disc-r disc-r :face-normal face-normal})]
                              (println (str "  " w "x" h " · candidati " (count cands)
                                            " · recall " recalled "/" (count gt)
                                            " · fit-crown " (if fit (str "crown " (:crown-hits fit)
                                                                         " zero " (:zero-hit? fit)) "NIL")))
                              (is (>= recalled 10) "il detector recupera la maggior parte della corona su pixel reali")
                              (is (some? fit) "fit-crown registra dai blob reali")
                              (is (and fit (:zero-hit? fit)) "lo zero-indice si aggancia su dati reali")
                              (is (and fit (>= (:crown-hits fit) 10)) "quasi tutta la corona sui dischi reali")
                              (done))))
                   (.catch (fn [e]
                             (println "  errore decodifica/detect:" (str e))
                             (is false "il ramo reale non deve lanciare")
                             (done)))))))))
