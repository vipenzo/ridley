(ns ridley.photogrammetry.cage-fit-study
  "Bench for the CAGE's fetta 2 — pose and identity over the detector's candidates.

       npx shadow-cljs compile cage-fit && node out/cage-fit.js

   It was written to RE-ESTABLISH the premises, because the two numbers fetta 2 had
   been designed around (a one-ring fit closing at 5.15px, and one out-of-plane
   point blowing it to 1008px) were computed with a pick that turned out to be
   143px from the mark it named. What it found instead, on IMG_9014, is the
   mechanism fetta 2 should be built on:

   1. The one-ring premise HOLDS, on clean picks: eight crown marks plus the
      zero-index close at 5.32px and leave the other two rings 92-157px from the
      marks the detector sees. (A ninth pick, :xm09, is 270px out — the clicked
      pixel is 21.6px from :xm10. That is a fourth wrong point in the fourteen.)

   2. But it is NOT an ambiguity to be searched, which is what the plan assumed.
      Seeding LM from 91 poses spanning ±150° about nine axes returns ONE minimum;
      jittering around it — 900 poses a round, six rounds, X-rms held under 8px —
      moves the four known inner marks from 92-157px to 97-162px. There is no pose
      fitting the clicked ring that explains the others. Sampling the neighbourhood
      cannot work because there is no neighbourhood.

   3. What DOES work is the thing the detector made possible. All 48 re-readings of
      the clicked crown fit the ring itself identically (5.3-5.4px, exactly the
      handover's second finding) — but scored against the candidates on the OTHER
      TWO RINGS they are not equal at all: `rot 3` puts the four known inner marks
      at 17-23px where the user's own reading leaves them at 92-157px. The 48
      collapse to 4, which are 2 physical poses (rot 3 and rot 9 differ by 180°,
      the other two are the same pair read from the far face) — and choosing
      between two is what `bridge/camera-sees-marks?` is for.

      So the crown's own symmetry is broken by the REST OF THE CAGE, not by more
      clicking. That is fetta 2.

   The 17-23px that remains is the next thing, and there is a named suspect: the
   ring `:phases`, the rotation each ring was glued at, which the cage documents
   say must be measured rather than assumed, and which slides marks along their
   own ring — the right shape of error for what is left."
  (:require [ridley.photogrammetry.cage :as cage]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.pnp :as pnp]
            [ridley.photogrammetry.blob-detect :as bd]
            [ridley.photogrammetry.synth :as synth]))

(def fs (js/require "fs"))
(def sharp (js/require "sharp"))

(def photo "test-assets/cage-presa/IMG_9014.jpeg")

;; The clean picks on IMG_9014, by ANCHOR ID rather than by the session file's
;; positional index — three of that file's fourteen are not marks at all (see
;; test-assets/cage-presa/NOTE.md), and two more have unreliable identity.
;; These nine are the ones the handover calls the reference seed, with xm07
;; corrected to the mark it actually names.
(def seed-picks
  {:xm00 [494.0 1005.8] :xm01 [992.3 578.5] :xm02 [1631.4 422.0]
   :xm03 [2261.8 573.9] :xm04 [2728.4 1022.3] :xm06 [2648.0 2362.9]
   :xm07 [2071.0 2853.0] :xm09 [304.6 2235.1] :zero-xm [693.9 890.8]})

;; The four inner-ring marks whose POSITION is known (identity is not — that is
;; exactly what fetta 2 has to work out).
(def inner-truth
  [[2274.1 2116.5] [1898.9 2047.2] [1021.4 1711.0] [661.1 1491.7]])

(defn- fmt [x n] (.toFixed (js/Number. x) n))

(defn- decode [file]
  (let [^js img (sharp file)
        ^js pipe (.raw (.ensureAlpha (.rotate img)))]
    (.toBuffer pipe #js {:resolveWithObject true})))

(defn- sampler [^js res]
  (let [data (.-data res) info (.-info res)
        w (.-width info) h (.-height info)]
    {:data data :w w :h h
     :lum-at (fn [x y]
               (let [xi (Math/round x) yi (Math/round y)]
                 (when (and (>= xi 0) (>= yi 0) (< xi w) (< yi h))
                   (let [o (* 4 (+ xi (* yi w)))]
                     (+ (* 0.299 (aget data o)) (* 0.587 (aget data (+ o 1)))
                        (* 0.114 (aget data (+ o 2))))))))}))

(defn- nearest [pts [u v]]
  (reduce (fn [b [cu cv]] (min b (Math/hypot (- cu u) (- cv v)))) js/Infinity pts))

(defn- ring-of
  "Which ring an anchor id belongs to — :x, :y or :z — crown marks and
   zero-indices alike."
  [id]
  (:axis (or (cage/mark-parts id) (cage/index-parts id))))

(defn- axis-angle->rvec [axis deg]
  (let [n (la/v-norm axis)]
    (if (< n 1e-12) [0.0 0.0 0.0]
        (la/v-scale axis (/ (* deg (/ Math/PI 180.0)) n)))))

(defn- compose-rot
  "rvec of (Rot(a) · Rot(b)) — a rotation applied in the CAMERA frame on top of a
   pose's object→camera rotation."
  [a b]
  (cam/rot-mat->rodrigues (la/mat*mat (cam/rodrigues a) (cam/rodrigues b))))

(defn- perturbed
  "`pose` with the object turned `deg` about the camera-frame axis `axis`, PIVOTED
   on the object point `pivot` so the pivot's image barely moves — the only kind of
   perturbation worth trying on a ring whose centre the fit already knows."
  [pose axis deg pivot]
  (let [dr (axis-angle->rvec axis deg)
        R (cam/rodrigues (:rvec pose))
        pc (la/v-add (la/mat*vec R pivot) (:t pose))
        dR (cam/rodrigues dr)]
    {:rvec (compose-rot dr (:rvec pose))
     ;; keep the pivot where it was: t' = pc − dR·(R·pivot)
     :t (la/v-sub pc (la/mat*vec dR (la/mat*vec R pivot)))}))

(defn- explains
  "How many of the cage's targets this pose puts within `tol` px of a detected
   candidate, and the median of those distances."
  [obj intr pose cands tol]
  (let [ds (keep (fn [[_ p]] (when-let [px (cam/project intr pose p)]
                               (nearest cands px)))
                 obj)
        hit (filterv #(< % tol) ds)]
    {:hits (count hit) :n (count ds)
     :median (if (seq ds) (nth (sort ds) (quot (count ds) 2)) js/Infinity)}))

(defn ^:export main [& _]
  (let [proxy (cage/registration-cage :d 176)
        anchors (:anchors proxy)
        obj (into {} (for [[id pose] anchors] [id (:position pose)]))]
    (println (str "\n=== gabbia ⌀176: " (count anchors) " bersagli ==="))
    (-> (decode photo)
        (.then
         (fn [res]
           (let [{:keys [data w h lum-at]} (sampler res)
                 intr (cam/intrinsics-from-fov
                       (cam/equiv-focal->hfov-deg 48.0 (/ w h)) w h)
                 cands (mapv :center (bd/detect-blobs lum-at [w h]
                                                      (assoc bd/cage-opts :rgba data)))
                 corr (vec (for [[id px] seed-picks]
                             {:ci id :world (obj id) :px px}))
                 sol (pnp/solve-pnp corr intr {})
                 keep-corr (mapv #(select-keys % [:world :px]) (:per-point sol))]
             (println (str "  " w "x" h " · " (count cands) " candidati rilevati"))
             (if (nil? sol)
               (println "  NESSUNA soluzione dai nove punti dell'anello X")
               (let [pose (:pose sol)
                     pred (into {} (for [[id p] obj
                                         :let [px (cam/project intr pose p)]
                                         :when px]
                                     [id px]))
                     by-ring (group-by (comp ring-of key) pred)]
                 (println (str "\n  -- posa dai NOVE punti dell'anello X --"))
                 (println (str "  rms " (fmt (:rms-px sol) 2) "px su "
                               (count (:per-point sol)) " punti · metodo " (:method sol)))
                 ;; A seed point solve-pnp threw out is not noise on a cage: the mark
                 ;; is THERE (the detector finds it) and the NAME is wrong. Say which
                 ;; name would have fitted, because that is fetta 2's whole question
                 ;; asked on a single point.
                 (doseq [o (:outliers sol)]
                   (let [alts (for [i (range 12)
                                    :let [alt (cage/mark-id :x -1 i)
                                          p (obj alt)
                                          px (when p (cam/project intr pose p))]
                                    :when px]
                                [alt (Math/hypot (- (first px) (first (:px o)))
                                                 (- (second px) (second (:px o))))])
                         [best d] (first (sort-by second alts))]
                     (println (str "  SCARTATO " (:ci o) " a " (fmt (:residual-px o) 0)
                                   "px — il pixel cliccato è a " (fmt d 1) "px da " best))))
                 ;; How far is each ring's PREDICTION from the nearest thing the
                 ;; detector actually saw? For the seeded ring this is the fit's own
                 ;; residual; for the other two it is the number fetta 2 must beat.
                 (doseq [[axis entries] (sort-by key by-ring)]
                   (let [ds (sort (map (fn [[_ px]] (nearest cands px)) entries))
                         n (count ds)]
                     (println (str "  anello " (name axis) " · " n " bersagli davanti"
                                   " · dist. al candidato più vicino:"
                                   " min " (fmt (first ds) 0)
                                   " · mediana " (fmt (nth ds (quot n 2)) 0)
                                   " · max " (fmt (last ds) 0)))))
                 ;; And the sharper question, on the four inner marks whose position
                 ;; is KNOWN: does any prediction land near them?
                 (println (str "  ai 4 mark interni NOTI, la predizione più vicina: "
                               (mapv (fn [px] (fmt (nearest (vals pred) px) 0))
                                     inner-truth)))
                 ;; The seeded ring's own marks, for scale: this is what "registered"
                 ;; looks like.
                 (println (str "  ai 9 punti del seme: "
                               (mapv (fn [[id _]] (fmt (nearest cands (pred id)) 1))
                                     seed-picks)))
                 ;; Is the other rings' error TANGENTIAL (the ring's projected
                 ;; curve passes through the true marks, only the numbering slides
                 ;; along it — a glued-in phase) or RADIAL (the curve misses them,
                 ;; so the geometry itself is wrong)? Sample each ring's crown circle
                 ;; densely and measure the known inner marks against the CURVE.
                 (println "\n  -- la curva dell'anello passa dai mark veri? --")
                 (doseq [k (range cage/ring-count)]
                   (let [axis (cage/ring-axis k)
                         r (:crown (cage/ring-radii 176.0 k))
                         curve (keep (fn [i]
                                       (let [a (* i (/ (* 2 Math/PI) 720))]
                                         (cam/project intr pose
                                                      (cage/place axis [(* r (Math/cos a))
                                                                        (* r (Math/sin a))
                                                                        0.0]))))
                                     (range 720))]
                     (println (str "  anello " (name axis) " (r=" (fmt r 1) "mm) · "
                                   "dai 4 mark interni noti alla CURVA: "
                                   (mapv (fn [px] (fmt (nearest curve px) 0)) inner-truth)))))
                 ;; ── the fork ────────────────────────────────────────────────
                 ;; The valley is flat: sampling it (900 poses a round, six rounds
                 ;; of shrinking jitter, X-rms held under 8px) moves the four known
                 ;; inner marks from 92-157px to 97-162px. There is no pose fitting
                 ;; the X ring that explains the other two rings, so the error is
                 ;; not the pose. It is the MODEL — and the model has exactly two
                 ;; numbers that a single coplanar ring cannot check: the focal
                 ;; (depth absorbs it, ring by ring, but not consistently across
                 ;; rings at different depths) and the ring RADII. Sweep both.
                 (println "\n  -- e se fosse la focale? --")
                 (doseq [f [30.0 36.0 42.0 48.0 54.0 60.0 70.0 85.0]]
                   (let [in2 (cam/intrinsics-from-fov (cam/equiv-focal->hfov-deg f (/ w h)) w h)
                         s2 (pnp/solve-pnp corr in2 {})]
                     (when s2
                       (let [p2 (:pose s2)
                             curves (for [k (range cage/ring-count)]
                                      (let [axis (cage/ring-axis k)
                                            r (:crown (cage/ring-radii 176.0 k))]
                                        (keep (fn [i]
                                                (let [a (* i (/ (* 2 Math/PI) 720))]
                                                  (cam/project in2 p2
                                                               (cage/place axis [(* r (Math/cos a))
                                                                                 (* r (Math/sin a)) 0.0]))))
                                              (range 720))))]
                         (println (str "  f=" (fmt f 0) "mm · rms X " (fmt (:rms-px s2) 2)
                                       "px · dai 4 interni alla curva più vicina: "
                                       (mapv (fn [px] (fmt (apply min (map #(nearest % px) curves)) 0))
                                             inner-truth)))))))
                 (println "\n  -- e se fosse il raggio dell'anello? --")
                 (let [curve-at (fn [r axis]
                                  (keep (fn [i] (let [a (* i (/ (* 2 Math/PI) 720))]
                                                  (cam/project intr pose
                                                               (cage/place axis [(* r (Math/cos a))
                                                                                 (* r (Math/sin a)) 0.0]))))
                                        (range 720)))]
                   (doseq [axis [:y :z]]
                     (let [best (apply min-key
                                       (fn [r] (reduce + (map #(nearest (curve-at r axis) %) inner-truth)))
                                       (map #(* 0.5 %) (range 60 200)))]
                       (println (str "  anello " (name axis) " · raggio nominale "
                                     (fmt (:crown (cage/ring-radii 176.0 (if (= axis :y) 1 2))) 1)
                                     "mm · raggio che meglio passa dai 4: " (fmt best 1) "mm · distanze "
                                     (mapv (fn [px] (fmt (nearest (curve-at best axis) px) 0)) inner-truth)))))
                   ;; and DRAW them, because a number that surprising has to be
                   ;; looked at on the photograph before it is believed
                   (.writeFileSync
                    ^js fs "/tmp/cage-curves.json"
                    (js/JSON.stringify
                     (clj->js {:candidates cands
                               :curves (for [[label axis r colour]
                                             [["x nominale" :x 85.5 "red"]
                                              ["y nominale" :y 69.5 "yellow"]
                                              ["z nominale" :z 53.5 "cyan"]
                                              ["z best-fit" :z 79.0 "lime"]]]
                                         {:label label :colour colour
                                          :pts (vec (take-nth 4 (curve-at r axis)))})})))
                   (println "  scritto /tmp/cage-curves.json"))
                 ;; ── what radius do the candidates actually sit on? ──────────
                 ;; Assumption-free: for each of the three ring planes, sweep the
                 ;; circle's radius and count how many DETECTED candidates the
                 ;; projected curve passes through. A physical ring shows up as a
                 ;; peak. If the three peaks come out in the model's ratio
                 ;; (85.5 : 69.5 : 53.5) the model is right and the trouble is
                 ;; elsewhere; if they do not, the cage on the bench is not the cage
                 ;; in the code.
                 (println "\n  -- su che raggio stanno davvero i candidati? --")
                 (let [curve-at (fn [r axis]
                                  (keep (fn [i] (let [a (* i (/ (* 2 Math/PI) 720))]
                                                  (cam/project intr pose
                                                               (cage/place axis [(* r (Math/cos a))
                                                                                 (* r (Math/sin a)) 0.0]))))
                                        (range 720)))
                       hits-at (fn [r axis]
                                 (let [c (curve-at r axis)]
                                   (count (filter #(< (nearest c %) 12.0) cands))))]
                   (doseq [axis [:x :y :z]]
                     (let [prof (for [ri (range 40 200)
                                      :let [r (* 0.5 ri)]]
                                  [r (hits-at r axis)])
                           peak (apply max (map second prof))
                           tops (->> prof (filter #(>= (second %) (max 3 (- peak 1))))
                                     (map first))]
                       (println (str "  piano " (name axis)
                                     " · nominale " (fmt (:crown (cage/ring-radii 176.0
                                                                                  (case axis :x 0 :y 1 :z 2))) 1)
                                     "mm · picco " peak " candidati"
                                     " a r≈" (if (seq tops)
                                               (str (fmt (first tops) 1) "-" (fmt (last tops) 1))
                                               "—") "mm")))))
                 ;; ── the 48 readings, arbitrated by the OTHER rings ──────────
                 ;; The handover's second finding: a crown of twelve equal marks is
                 ;; invariant under rotation and mirrored from its other face, so
                 ;; all 48 re-readings of it fit the ring ITSELF equally well —
                 ;; measured, the same 32.5px for every one. That is true and it is
                 ;; why the gesture is closed. But it is a statement about the RING,
                 ;; and the detector has just supplied something the ring does not
                 ;; contain: where the other two rings' marks are on the photograph.
                 ;; Try every reading and let the rest of the cage arbitrate.
                 (println "\n  -- le 48 riletture, arbitrate dagli ALTRI anelli --")
                 (let [readings (for [m (cage/crown-misreadings 12)
                                      :let [c (vec (keep (fn [[id px]]
                                                           (when-let [id2 (cage/relabel id m 12)]
                                                             {:ci id2 :world (obj id2) :px px}))
                                                         seed-picks))
                                            sol2 (when (>= (count c) 6) (pnp/solve-pnp c intr {}))]
                                      :when sol2]
                                  (let [e (explains obj intr (:pose sol2) cands 14.0)]
                                    (assoc m :rms (:rms-px sol2) :hits (:hits e)
                                           :pose (:pose sol2)
                                           :inner (mapv (fn [px]
                                                          (nearest (keep (fn [[_ q]] (cam/project intr (:pose sol2) q)) obj) px))
                                                        inner-truth))))
                       ranked (->> readings (sort-by (comp - :hits)))]
                   (println (str "  riletture risolte: " (count readings)
                                 " · rms tutti fra " (fmt (apply min (map :rms readings)) 1)
                                 " e " (fmt (apply max (map :rms readings)) 1) "px"))
                   (doseq [r (take 5 ranked)]
                     (println (str "   rot " (:rot r) " mirror " (:mirror? r)
                                   " faccia-girata " (:flip-face? r)
                                   " · rms " (fmt (:rms r) 2) "px · spiega " (:hits r) "/78"
                                   " · ai 4 interni " (mapv #(fmt % 0) (:inner r))))))
                 ;; ── the same fact from the other side ───────────────────────
                 ;; The peaks above say 85.5 on the X plane (nominal), 53.5 on the Y
                 ;; and ~69.5 on the Z: the two inner rings look SWAPPED. They are
                 ;; not — that is the mislabelling seen in the mirror. Turning the
                 ;; cage 90° about X (which is what `rot 3` on a twelve-mark crown
                 ;; amounts to) carries ring-normal y onto z and z onto −y, so a
                 ;; crown read three marks out of phase presents exactly as a cage
                 ;; with its inner rings exchanged. Kept as a cross-check: it must
                 ;; agree with the reading search, and it does (15-22px vs 17-23px).
                 (println "\n  -- e se i due anelli interni fossero scambiati? --")
                 (let [swapped (into {} (for [[id p] obj
                                              :let [ax (ring-of id)]]
                                          [id (case ax
                                                :y (cage/place :z (cage/unplace :y p))
                                                :z (cage/place :y (cage/unplace :z p))
                                                p)]))
                       e0 (explains obj intr pose cands 14.0)
                       e1 (explains swapped intr pose cands 14.0)]
                   (println (str "  modello com'è:  spiega " (:hits e0) "/" (:n e0)
                                 " · ai 4 interni noti "
                                 (mapv (fn [px] (fmt (nearest (keep (fn [[_ q]] (cam/project intr pose q)) obj) px) 0))
                                       inner-truth)))
                   (println (str "  Y e Z scambiati: spiega " (:hits e1) "/" (:n e1)
                                 " · ai 4 interni noti "
                                 (mapv (fn [px] (fmt (nearest (keep (fn [[_ q]] (cam/project intr pose q)) swapped) px) 0))
                                       inner-truth)))))))))
        (.catch (fn [e] (println "  ERRORE" (str e)))))))
