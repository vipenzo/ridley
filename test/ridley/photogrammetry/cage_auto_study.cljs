(ns ridley.photogrammetry.cage-auto-study
  "Bench for ZERO-CLICK cage registration, against Vincenzo's battiscopa session
   (2026-08-25): eight real photographs of the new cage — key, slots, a part in
   the middle — every one registered BY HAND at 3.8-13.8px. His poses are the
   truth; auto-read gets no clicks and is measured on how many photos it
   registers and how far its camera lands from his.

       npx shadow-cljs compile cage-auto && node out/cage-auto.js"
  (:require [ridley.photogrammetry.blob :as blob]
            [ridley.photogrammetry.blob-detect :as bd]
            [ridley.photogrammetry.bridge :as bridge]
            [ridley.photogrammetry.cage :as cage]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.match-cage :as mc]))

(def fs (js/require "fs"))
(def path (js/require "path"))
(def sharp (js/require "sharp"))

(def dir
  "CAGE_AUTO_DIR points the bench at another session's folder (a live-grab
   session, a new shoot) without touching the committed truth run."
  (or (aget (.-env js/process) "CAGE_AUTO_DIR") "test-assets/cage-battiscopa"))

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

(defn- cage-targets [proxy]
  (vec (for [[id pose] (sort-by key (:anchors proxy))]
         {:id id :obj (:position pose)
          :normal (let [h (:heading pose) n (la/v-norm h)]
                    (when (pos? n) (la/v-scale h (/ 1.0 n))))
          :index? (some? (cage/index-parts id))})))

(defn- solver-camera
  "His registered EDITOR camera pose for one photo, converted into the cage's
   object frame — the frame auto-read's poses live in — via the session's
   proxy pose."
  [state idx]
  (let [pp (:proxy-pose state)
        cam-pose (if (zero? idx)
                   (:camera-pose-0 state)
                   (get-in state [:photos (keyword (str idx)) :camera-pose]))]
    (when (and pp cam-pose)
      (bridge/editor->solver-pose cam-pose pp))))

(defn- family-hits
  "Under the TRUTH pose: for each visible (ring,face) family, which detector
   candidates sit within 8px of one of its marks. {[axis sign] {:n-vis n
   :idxs #{candidate-index …}}} — the ceiling any ellipse search works under,
   and the ground truth the hypotheses are audited against."
  [targets intr truth cands]
  (when truth
    (let [c (cam/camera-center truth)
          fams (group-by (fn [t]
                           (let [p (or (cage/mark-parts (:id t)) (cage/index-parts (:id t)))]
                             [(:axis p) (:sign p)]))
                         targets)]
      (into {}
            (for [[[axis sign] ts] fams
                  :let [vis (filterv (fn [{:keys [normal obj]}]
                                       (or (nil? normal)
                                           (pos? (la/v-dot normal (la/v-sub c obj)))))
                                     ts)
                        idxs (set (for [{:keys [obj]} vis
                                        :let [px (cam/project intr truth obj)]
                                        :when px
                                        [i [u v]] (map-indexed vector cands)
                                        :when (< (Math/hypot (- u (nth px 0))
                                                             (- v (nth px 1)))
                                                 8.0)]
                                    i))]
                  :when (seq vis)]
              [[axis sign] {:n-vis (count vis) :idxs idxs}])))))

(defn- recall-line
  "CAGE_AUTO_RECALL=1: per visible (ring,face), how many marks have a candidate
   within 8px. Lever 3's instrument: when every ring of a refused frame sits
   below the 8-inlier floor, the detector — not the geometry — is starving."
  [fh]
  (when fh
    (apply str
           (interpose " · "
                      (for [[[axis sign] {:keys [n-vis idxs]}] (sort-by (comp str first) fh)]
                        (str (name axis) (if (pos? sign) "p" "m") " "
                             (count idxs) "/" n-vis))))))

(defn- coverage-report!
  "CAGE_AUTO_RECALL=1, on a refusal: for each family with enough detected discs
   to be findable, WHICH hypothesis covers its candidates, what the size-ratio
   hint said, and whether the pair (hypothesis, right axis) was ever attempted
   under the identity budget — the questions that separate 'the ellipse stage
   missed the ring' from 'the ring was found and never tried' from 'tried and
   the identification itself failed'. Built the day sizes alone proved
   unreadable (2026-08-29: five refusals, every true ring present at 8-9 discs,
   none ever attempted — the budget burned six faces at a time on junk)."
  [fh tr]
  (let [hyps-note (first (filter #(= :hyps (:stage %)) tr))
        sets (mapv set (:sets hyps-note))
        hints (:hints hyps-note)
        tried (set (keep (fn [t] (when (= :seed (:stage t))
                                   [(:hyp-i t) (first (:face t))]))
                         tr))]
    (when (seq sets)
      (doseq [[[axis sign] {:keys [idxs]}] (sort-by (comp str first) fh)
              :when (>= (count idxs) 6)]
        (let [cov (mapv #(count (filter % idxs)) sets)
              best (apply max-key cov (range (count sets)))]
          (println (str "      anello " (name axis) (if (pos? sign) "p" "m")
                        ": " (count idxs) " dischetti rilevati · ipotesi #" best
                        " ne copre " (nth cov best) " (taglia " (count (nth sets best))
                        (when hints (str ", hint " (pr-str (nth hints best)))) ")"
                        " · provata con asse " (name axis) "? "
                        (if (contains? tried [best axis]) "sì" "NO"))))))))

(defn- synth-run!
  "CAGE_AUTO_SYNTH=1: the synthetic scene of match-cage-test, with the full
   trace — for debugging why a refusal happens where sight says it should not."
  []
  (let [proxy (cage/registration-cage :d 176)
        targets (cage-targets proxy)
        w 3024 h 4032
        intr (cam/intrinsics-from-fov (cam/equiv-focal->hfov-deg 48.0 (/ w h)) w h)
        pose (cam/look-at-pose [150.0 -210.0 190.0] [0.0 0.0 0.0] [0.0 0.0 1.0])
        c (cam/camera-center pose)
        cands (vec (for [{:keys [obj normal]} targets
                         :when (or (nil? normal) (pos? (la/v-dot normal (la/v-sub c obj))))
                         :let [px (cam/project intr pose obj)]
                         :when px]
                     px))
        judge (fn [px _r] (boolean (some (fn [[u v]]
                                           (< (Math/hypot (- u (first px)) (- v (second px))) 4.0))
                                         cands)))
        tr (atom [])
        rr (mc/auto-read cands targets intr judge 12 {:disc-r 1.25 :trace tr})]
    (println (str "SINTETICO: " (count cands) " candidati · "
                  (if rr (str "seme " (pr-str (:seed rr)) " rms " (fmt (:rms-px rr) 2))
                      "RIFIUTATA")))
    (doseq [t @tr] (println (str "  " (pr-str t))))))

(defn- main* []
  (let [state (if (.existsSync fs (str dir "/acquire-state.json"))
                ;; the truth file: hand-registered poses. A live session that was
                ;; never registered has none — the bench still runs, it just
                ;; can't say how far the camera lands from anyone's hand.
                (js->clj (js/JSON.parse (.readFileSync fs (str dir "/acquire-state.json") "utf8"))
                         :keywordize-keys true)
                {})
        proxy (cage/registration-cage :d 176)
        targets (cage-targets proxy)
        disc-r (:mark-disc-r proxy)
        files (->> (.readdirSync fs dir) (filter #(re-find #"(?i)\.jpe?g$" %)) sort vec)
        ;; CAGE_AUTO_FOCAL sweeps a hypothesis lens over a session whose real
        ;; one was never measured (a grabbed frame has no EXIF)
        focal (or (some-> (aget (.-env js/process) "CAGE_AUTO_FOCAL") js/parseFloat)
                  (get-in state [:focal :mm] 48.0))
        ;; CAGE_AUTO_CONC=1: the centre-pinned concentric hypothesis stream
        ;; (ellipse/fit-concentric-ranked) instead of the free RANSAC — the
        ;; enriched stream the 2026-08-29 audit measured surfacing the sparse
        ;; rings inside contaminated supersets. Behind an env because its
        ;; production wiring is decided HERE, by these numbers.
        conc? (boolean (aget (.-env js/process) "CAGE_AUTO_CONC"))
        ;; CAGE_AUTO_TEETH=1: the comb-locked identity (zero-click lever 1).
        ;; Off = the production wiring. On (2026-08-30): foto 6 e 7 REGISTER
        ;; from the through-plastic twin (548/764mm out) because only the
        ;; twin's zero passes the pixel judge — the measured reason the gate
        ;; stays closed until the twin arbiter (lever 2) exists.
        teeth? (boolean (aget (.-env js/process) "CAGE_AUTO_TEETH"))
        score (atom {:ok 0 :none 0 :far 0})]
    (println (str "\n=== auto-read (zero click) su battiscopa: " (count files)
                  " foto · focale della sessione " (fmt focal 1) "mm"
                  (when conc? " · ipotesi CONCENTRICHE")
                  (when teeth? " · identità COI DENTI") " ==="))
    (letfn [(step [i]
              (if (>= i (count files))
                (let [{:keys [ok none far]} @score]
                  (println (str "\n  BILANCIO: " ok " registrate da sola, " far
                                " lontane dalla verità, " none " rifiutate (su "
                                (count files) ")")))
                (-> (decode (.join path dir (nth files i)))
                    (.then
                     (fn [res]
                       (let [{:keys [data lum-at w h]} (sampler res)
                             ;; intrinsics from the photo's OWN size: the truth
                             ;; fixture is all 3024×4032 (same numbers as the old
                             ;; fixed pair), a live-grab folder is 1920×1440
                             intr (cam/intrinsics-from-fov
                                   (cam/equiv-focal->hfov-deg focal (/ w h)) w h)
                             t0 (.now js/Date)
                             cands (mapv :center (bd/detect-blobs lum-at [w h]
                                                                  (assoc bd/cage-opts :rgba data)))
                             judge (fn [px r] (blob/disc-at? lum-at px r))
                             tr (atom [])
                             rr (mc/auto-read cands targets intr judge 12
                                              {:disc-r disc-r :trace tr
                                               :concentric? conc? :teeth? teeth?})
                             ms (- (.now js/Date) t0)
                             truth (solver-camera state i)]
                         (when (aget (.-env js/process) "CAGE_AUTO_RECALL")
                           (when-let [rl (recall-line (family-hits targets intr truth cands))]
                             (println (str "      recall (sotto la posa a mano): " rl))))
                         (if (nil? rr)
                           (do (swap! score update :none inc)
                               (println (str "  foto " (inc i) " (" (nth files i) "): "
                                             (count cands) " candidati · RIFIUTATA · " ms "ms"))
                               (when (aget (.-env js/process) "CAGE_AUTO_RECALL")
                                 (when-let [fh (family-hits targets intr truth cands)]
                                   (coverage-report! fh @tr)))
                               (doseq [t @tr] (println (str "      " (pr-str t)))))
                           (let [pose (:pose rr)
                                 c-auto (cam/camera-center pose)
                                 c-true (when truth (cam/camera-center truth))
                                 d (when c-true (la/v-norm (la/v-sub c-auto c-true)))
                                 ;; no truth on file → registered is all the bench
                                 ;; can attest; only a MEASURED distance flags far
                                 ok? (if d (< d 15.0) true)]
                             (swap! score update (if ok? :ok :far) inc)
                             (println (str "  foto " (inc i) " (" (nth files i) "): "
                                           (count cands) " candidati · seme "
                                           (name (:axis (:seed rr))) (if (pos? (:sign (:seed rr))) "p" "m")
                                           " (" (:crown-hits (:seed rr)) " corona)"
                                           " · spiega " (:explained rr)
                                           " · rms " (fmt (:rms-px rr) 1)
                                           (when (:phase-suspect rr) " · SOSPETTO ANELLO GIRATO")
                                           " · camera a " (if d (str (fmt d 1) "mm") "?")
                                           " dalla tua · " ms "ms"
                                           (when-not ok? "   ← LONTANA")))))
                         (step (inc i)))))
                    (.catch (fn [e]
                              (println (str "  foto " (inc i) " ERRORE: " (str e)))
                              (step (inc i)))))))]
      (step 0))))

(defn ^:export main [& _]
  (if (aget (.-env js/process) "CAGE_AUTO_SYNTH")
    (synth-run!)
    (main*)))
