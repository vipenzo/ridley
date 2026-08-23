(ns ridley.photogrammetry.blob-detect-test
  "Global dark-disc detector (fetta C): on a whole synthetic frame it must recall
   every disc-shaped dark region to a few px, REJECT the non-disc distractors an
   adaptive threshold would otherwise pass (the big dark 'part', a thin line/edge),
   and bound the candidate count — the recall + precision fit-crown's search rests
   on. Then the same on a projected registration-plate scene (crown + zero-index).

   The second half is the CAGE, whose scene is the plate's turned inside out —
   thin bright bands over a dark background instead of one wide white field — and
   which therefore breaks the plate's threshold outright. Those tests fix both
   halves of the fix: that the local MAX reference sees marks the local mean
   cannot, and that the full-resolution enclosure test tells a mark from the
   background beside a band edge."
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
  (let [proxy (plate/registration-plate :d 130)
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

(defn- cage-lum
  "A luminance sampler over a w×h DARK frame carrying bright BANDS with dark marks
   on them — a registration cage's scene, as opposed to scene-lum's plate.

   `bands` are [x0 y0 x1 y1 halfwidth] segments painted `band` bright; `marks` are
   [u v a b angle lum] filled ellipses painted on top. The numbers the callers use
   are the ones measured on IMG_9014 (2026-08-23): background ~35, band ~216, an
   outer-ring mark ~40 and 30px across, an inner-ring one only ~145 — a moulded
   dimple, not a printed dot — and 27×9px because it is seen edge-on."
  [w h {:keys [bg band bands marks noise seed] :or {bg 35 band 216 noise 0.0 seed 7}}]
  (let [buf (js/Uint8Array. (* w h))
        r (synth/rng seed)]
    (.fill buf bg)
    (doseq [[x0 y0 x1 y1 hw] bands]
      (let [dx (- x1 x0) dy (- y1 y0) len2 (+ (* dx dx) (* dy dy))]
        (dotimes [y h]
          (dotimes [x w]
            (let [t (max 0.0 (min 1.0 (/ (+ (* (- x x0) dx) (* (- y y0) dy)) len2)))
                  px (+ x0 (* t dx)) py (+ y0 (* t dy))]
              (when (<= (Math/hypot (- x px) (- y py)) hw)
                (aset buf (+ x (* y w)) band)))))))
    (doseq [[u v a b ang lum] marks]
      (let [ca (Math/cos ang) sa (Math/sin ang)]
        (doseq [y (range (max 0 (- v a)) (min h (+ v a 1)))
                x (range (max 0 (- u a)) (min w (+ u a 1)))]
          (let [dx (- x u) dy (- y v)
                e (+ (/ (Math/pow (+ (* dx ca) (* dy sa)) 2) (* a a))
                     (/ (Math/pow (- (* dy ca) (* dx sa)) 2) (* b b)))]
            (when (<= e 1.0) (aset buf (+ x (* y w)) lum))))))
    (when (pos? noise)
      (dotimes [k (* w h)]
        (aset buf k (max 0 (min 255 (Math/round (+ (aget buf k) (* noise (synth/gauss r)))))))))
    (fn [x y] (let [xi (Math/round x) yi (Math/round y)]
                (when (and (>= xi 0) (>= yi 0) (< xi w) (< yi h)) (aget buf (+ xi (* yi w))))))))

;; One scene, shared by the two cage tests: a WIDE band with deep round marks (the
;; outer ring, which the plate's settings already handled) and two THIN ones with
;; shallow elongated marks (the inner rings, which they did not) — including one
;; mark pushed against a band edge, the case that the enclosure test has to keep
;; and that killed the first three attempts at the geometry.
(def ^:private cage-scene
  {:bands [[80 130 1120 200 36]      ; wide band, top
           [110 430 1090 470 13]     ; thin band
           [140 700 1060 620 13]]    ; thin band
   :marks [[260 141 15 15 0.0 40] [600 164 15 15 0.0 40] [940 187 15 15 0.0 40]
           [330 439 13 4 0.04 145] [700 456 13 4 0.04 145]
           [420 676 13 4 -0.087 145]
           ;; pushed against the third band's lower edge, with 4px of band left
           ;; under it — the case that decides the whole slice
           [820 646 13 4 -0.087 145]]})

(def ^:private cage-truth
  (mapv (fn [[u v]] [u v]) (:marks cage-scene)))

(deftest cage-needs-the-max-reference
  (println "\n=== detector gabbia: bande chiare su fondo scuro ===")
  (let [w 1200 h 900
        lum (cage-lum w h (assoc cage-scene :noise 2.0))
        plate-mode (bd/detect-blobs lum [w h])
        cage-mode (bd/detect-blobs lum [w h] bd/cage-opts)
        found (fn [cands] (count (filter #(near cands % 8.0) cage-truth)))
        dists (fn [cands] (mapv (fn [[u v]]
                                  (.toFixed (reduce (fn [b {[cu cv] :center}]
                                                      (min b (Math/hypot (- cu u) (- cv v))))
                                                    js/Infinity cands) 1))
                                cage-truth))]
    (println (str "  modo piatto: " (count plate-mode) " candidati · "
                  (found plate-mode) "/" (count cage-truth) " mark"
                  " · modo gabbia: " (count cage-mode) " candidati · "
                  (found cage-mode) "/" (count cage-truth) " mark"))
    (println (str "    distanze gabbia " (dists cage-mode)))
    ;; The failure this exists to pin down: a mean taken over a window wider than
    ;; the thin bands is mostly BACKGROUND, so it sits below the shallow marks and
    ;; they are never dark. Measured on the real photograph before the fix: the big
    ;; ring gave every mark, the two inner rings gave none.
    (is (< (found plate-mode) (count cage-truth))
        "le tarature del PIATTO non bastano su fondo scuro (è il guasto misurato)")
    (is (= (count cage-truth) (found cage-mode))
        "il modo gabbia recupera TUTTI i mark, anelli sottili compresi")
    (is (<= (count cage-mode) 12) "e l'insieme dei candidati resta stretto")))

(deftest enclosure-tells-a-mark-from-an-edge
  (testing "un dischetto è circondato di chiaro; il fondo accanto a un bordo no"
    (let [w 600 h 400
          lum (cage-lum w h {:bands [[40 200 560 200 60]]
                             :marks [[300 200 14 14 0.0 40]]})
          len 26.0 contrast 55.0 dirs 16]
      ;; on the mark: white all round
      (is (> (bd/enclosed-frac lum 300 200 len contrast dirs) 0.95)
          "il mark: chiaro in ogni direzione")
      ;; in the background 8px below the band's lower edge: white on ONE side
      (is (< (bd/enclosed-frac lum 300 268 len contrast dirs) 0.6)
          "il fondo accanto al bordo: chiaro solo da una parte")
      ;; deep in the background: nothing bright within reach
      (is (< (bd/enclosed-frac lum 300 380 len contrast dirs) 0.05)
          "il fondo lontano: nessun chiaro a portata")
      ;; a shallow dip — the leather's grain — is enclosed but not deep enough
      (let [grain (cage-lum w h {:bg 120 :band 120 :bands [] :marks [[300 200 8 8 0.0 90]]})]
        (is (< (bd/enclosed-frac grain 300 200 len contrast dirs) 0.05)
            "una fossetta poco profonda (la grana della pelle) non passa il contrasto")))))

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
                   proxy (plate/registration-plate :d 130)
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

;; ── the CAGE on real pixels ──────────────────────────────────────────────────

(def ^:private cage-9014-truth
  "Every mark on test-assets/cage-presa/IMG_9014.jpeg whose position is KNOWN, in
   display coordinates (3024×4032, EXIF Orientation 6 applied). Read off the
   photograph at 4× zoom, one by one — not lifted from the session file, which is
   wrong in three places (see that folder's NOTE.md).

   The point of listing them by ring is that the ring is the whole difficulty: the
   outer ring's marks are deep round holes on a wide band and were never the
   problem, while the inner rings' are shallow moulded dimples, 27×9px, on bands
   barely wider than themselves — and before this slice the detector found NONE of
   them. `interni` includes the two hardest cases in the frame, marks sitting
   against a band's edge with the black background 4px away."
  {:corona [[494.0 1005.8] [992.3 578.5] [1631.4 422.0] [2261.8 573.9] [2728.4 1022.3]
            [2648.0 2362.9] [2071.0 2853.0] [304.6 2235.1]]
   :zero   [[693.9 890.8]]
   :interni [[2274.1 2116.5] [1898.9 2047.2] [1021.4 1711.0] [661.1 1491.7]]})

(deftest real-cage-photo-detect
  ;; The fetta-1 REAL-DATA gate. Guarded exactly like the plate one above: it runs
  ;; where node fs+sharp and the (untracked) photos are present and prints a skip
  ;; elsewhere, so the suite never depends on the images.
  ;;
  ;; `.rotate()` with no argument APPLIES the EXIF orientation. Without it the
  ;; frame comes out 4032×3024 and every truth coordinate misses by a right angle
  ;; — the mistake that cost a day on 19 August.
  (let [fs (node-require "fs")
        sharp (node-require "sharp")
        dir "test-assets/cage-presa"
        file (str dir "/IMG_9014.jpeg")
        file2 (str dir "/IMG_9015.jpeg")]
    (if (or (nil? fs) (nil? sharp) (not (.existsSync fs file)) (not (.existsSync fs file2)))
      (println "\n=== gabbia reale: SALTATO (fs/sharp/foto assenti) ===")
      (async done
             (let [decode (fn [f] (-> (sharp f) (.rotate) (.ensureAlpha) (.raw)
                                      (.toBuffer #js {:resolveWithObject true})))
                   run (fn [^js res]
                         (let [data (.-data res) info (.-info res)
                               w (.-width info) h (.-height info)
                               lum-at (fn [x y]
                                        (let [xi (Math/round x) yi (Math/round y)]
                                          (when (and (>= xi 0) (>= yi 0) (< xi w) (< yi h))
                                            (let [o (* 4 (+ xi (* yi w)))]
                                              (+ (* 0.299 (aget data o)) (* 0.587 (aget data (+ o 1)))
                                                 (* 0.114 (aget data (+ o 2))))))))]
                           {:w w :h h :cands (bd/detect-blobs lum-at [w h]
                                                              (assoc bd/cage-opts :rgba data))}))]
               (println "\n=== gabbia reale: Presa foto 0 e 1 (JPEG veri) ===")
               (-> (js/Promise.all #js [(decode file) (decode file2)])
                   (.then (fn [^js both]
                            (let [{w :w h :h cands :cands} (run (aget both 0))
                                  grain (:cands (run (aget both 1)))
                                  hits (fn [pts] (count (filter #(near cands % 14.0) pts)))
                                  [nc nz ni] (mapv #(hits (get cage-9014-truth %))
                                                   [:corona :zero :interni])]
                              (println (str "  IMG_9014 " w "x" h " · " (count cands) " candidati"
                                            " · corona " nc "/8 · zero " nz "/1 · interni " ni "/4"))
                              (println (str "  IMG_9015 (grana della pelle in luce) · "
                                            (count grain) " candidati"))
                              (is (= 8 nc) "tutta la corona esterna visibile")
                              (is (= 1 nz) "lo zero-indice")
                              (is (= 4 ni) "e i quattro mark noti degli anelli INTERNI — erano zero")
                              ;; Before this slice: 89 candidates for 10 true marks, all of
                              ;; them on the outer ring. The bar is precision, not just a cap.
                              (is (<= (count cands) 45)
                                  (str "candidati contenuti (" (count cands) ", il modo precedente ne dava 89)"))
                              (is (<= (count grain) 45)
                                  (str "la grana della pelle non inonda il fotogramma ("
                                       (count grain) ", senza il test di contrasto erano 80)"))
                              (done))))
                   (.catch (fn [e]
                             (println "  errore decodifica/detect:" (str e))
                             (is false "il ramo reale non deve lanciare")
                             (done)))))))))
