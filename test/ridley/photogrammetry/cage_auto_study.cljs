(ns ridley.photogrammetry.cage-auto-study
  "Bench for ZERO-CLICK cage registration, against Vincenzo's battiscopa session
   (2026-08-25): eight real photographs of the new cage — key, slots, a part in
   the middle — every one registered BY HAND at 3.8-13.8px. His poses are the
   truth; auto-read gets no clicks and is measured on how many photos it
   registers and how far its camera lands from his.

       npx shadow-cljs compile cage-auto && node out/cage-auto.js"
  (:require [clojure.string :as str]
            [ridley.photogrammetry.blob :as blob]
            [ridley.photogrammetry.blob-detect :as bd]
            [ridley.photogrammetry.bridge :as bridge]
            [ridley.photogrammetry.cage :as cage]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.match-cage :as mc]
            [ridley.photogrammetry.pnp :as pnp]))

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

(defn- witness-line
  "CAGE_AUTO_ZERO=1: mc/index-witness under one pose, printably. Lever 2's
   instrument — per visible face: distance from the projected model zero to
   the nearest crown-free detector candidate, and every index-slot
   observation [sense k dist-px]."
  [label targets blobs intr pose]
  (when pose
    (let [{:keys [faces]} (mc/index-witness targets blobs intr pose 12 {})]
      (str "      testimone-zero (" label "): ["
           (apply str
                  (interpose " | "
                             (for [{:keys [axis sign zero-d hits]} faces]
                               (str (name axis) (if (pos? sign) "p" "m")
                                    " zero→" (if zero-d (str (fmt zero-d 0) "px") "?")
                                    (when (seq hits)
                                      (str " " (pr-str (mapv (fn [{:keys [sense k d]}]
                                                               [sense k (js/Math.round d)])
                                                             hits))))))))
           "]"))))

(defn- fmt-mounting [m]
  (if (seq m)
    (apply str (interpose " " (for [[axis {:keys [sense k d votes against]}] (sort-by (comp str first) m)]
                                (str (name axis) "=" (name sense)
                                     (when k (str "(k" k ")"))
                                     (when votes (str " " votes (when (pos? (or against 0))
                                                                  (str " contro " against))
                                                      " voti"))
                                     ", " (fmt d 0) "px"))))
    "nessuno"))

(defn- mounting-pass
  "Decode every photo once and read its index observations under the HAND
   pose. Returns (via `done`) {photo-idx obs}. This is the bench's stand-in
   for the session context edit_acquire accumulates from accepted
   registrations: battiscopa IS a hand-registered session, so photo i's
   zero-click attempt legitimately runs under the mounting the OTHER photos
   establish — leave-one-out, its own hand pose never informs its own run."
  [files state targets focal done]
  (let [acc (atom {})]
    (letfn [(step [i]
              (if (>= i (count files))
                (done @acc)
                (-> (decode (.join path dir (nth files i)))
                    (.then
                     (fn [res]
                       (let [{:keys [data lum-at w h]} (sampler res)
                             intr (cam/intrinsics-from-fov
                                   (cam/equiv-focal->hfov-deg focal (/ w h)) w h)
                             truth (solver-camera state i)
                             obs (when truth
                                   (let [blobs (bd/detect-blobs lum-at [w h]
                                                                (assoc bd/cage-opts :rgba data))]
                                     (:obs (mc/index-witness targets blobs intr truth 12 {}))))]
                         (when (aget (.-env js/process) "CAGE_AUTO_CTXONLY")
                           (println (str "  pass1 " i " (" (nth files i) "): "
                                         (if truth (pr-str obs) "senza posa a mano"))))
                         (swap! acc assoc i (or obs []))
                         (step (inc i)))))
                    (.catch (fn [_]
                              (swap! acc assoc i [])
                              (step (inc i)))))))]
      (step 0))))

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
        ;; the cage the SESSION declared, not a hardcoded nominal: battiscopa2
        ;; runs `(registration-cage :d 176 :phases {:y 180 :x 180})`, and the
        ;; bench reading k6/k6 against the unphased model while the app read
        ;; k0/k0 against the declared one cost a round of cross-talk
        ;; (2026-08-29). Preference order: CAGE_AUTO_PHASES (JSON, e.g.
        ;; '{"x":180,"y":180}') → the fingerprint persisted with the mounting
        ;; vote → nominal.
        phases (or (some-> (aget (.-env js/process) "CAGE_AUTO_PHASES")
                           (js/JSON.parse)
                           (js->clj :keywordize-keys true))
                   (get-in state [:cage-mounting-obs :cage :phases]))
        proxy (cage/registration-cage :d 176 :phases phases)
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
        ;; CAGE_AUTO_NOCTX=1: run WITHOUT the session-mounting context — the
        ;; pure cold-start ordering, for measuring what the arbiter buys
        noctx? (boolean (aget (.-env js/process) "CAGE_AUTO_NOCTX"))
        score (atom {:ok 0 :none 0 :far 0})]
    (println (str "\n=== auto-read (zero click) su battiscopa: " (count files)
                  " foto · focale della sessione " (fmt focal 1) "mm"
                  " · gabbia " (if (seq phases) (str ":phases " (pr-str phases)) "nominale")
                  (when conc? " · ipotesi CONCENTRICHE")
                  (when teeth? " · identità COI DENTI")
                  (if noctx? " · SENZA contesto di montaggio"
                      " · arbitro del montaggio (leave-one-out)") " ==="))
    (letfn [(step [i obs-by-photo]
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
                             ;; the session's mounting as the OTHER photos'
                             ;; hand registrations VOTED it — never its own
                             mounting (when-not noctx?
                                        (mc/vote-mounting
                                         (for [[j obs] obs-by-photo
                                               :when (not= j i)]
                                           obs)))
                             t0 (.now js/Date)
                             blobs (bd/detect-blobs lum-at [w h]
                                                    (assoc bd/cage-opts :rgba data))
                             cands (mapv :center blobs)
                             judge (fn [px r] (blob/disc-at? lum-at px r))
                             tr (atom [])
                             rr (mc/auto-read cands targets intr judge 12
                                              {:disc-r disc-r :trace tr
                                               :concentric? conc? :teeth? teeth?
                                               :mounting mounting :blobs blobs})
                             ms (- (.now js/Date) t0)
                             truth (solver-camera state i)]
                         (when (seq mounting)
                           (println (str "      montaggio (dalle altre foto): "
                                         (fmt-mounting mounting))))
                         (when (aget (.-env js/process) "CAGE_AUTO_RECALL")
                           (when-let [rl (recall-line (family-hits targets intr truth cands))]
                             (println (str "      recall (sotto la posa a mano): " rl))))
                         (when (aget (.-env js/process) "CAGE_AUTO_ZERO")
                           (when-let [l (witness-line "posa a mano" targets blobs intr truth)]
                             (println l))
                           (when-let [l (witness-line "posa auto" targets blobs intr (:pose rr))]
                             (println l)))
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
                                 ok? (if d (< d 15.0) true)
                                 ;; far from the hand pose, but the hand pose is
                                 ;; the session's DISSENTER while the result
                                 ;; agrees with the majority on every observed
                                 ;; index: the truth is the suspect, not the
                                 ;; result (foto 1, 2026-08-30: hand centre =
                                 ;; auto centre with x,y negated — the 180°
                                 ;; impostor, in the truth file)
                                 truth-suspect?
                                 (when (and (not ok?) (seq mounting))
                                   (let [dis? (fn [os]
                                                (some (fn [{:keys [axis sense k]}]
                                                        (when-let [m (get mounting axis)]
                                                          (or (not= sense (:sense m))
                                                              (not= k (:k m)))))
                                                      os))]
                                     (boolean (and (dis? (get obs-by-photo i))
                                                   (seq (:index-obs rr))
                                                   (not (dis? (:index-obs rr)))))))]
                             (swap! score update (if ok? :ok :far) inc)
                             (println (str "  foto " (inc i) " (" (nth files i) "): "
                                           (count cands) " candidati · seme "
                                           (name (:axis (:seed rr))) (if (pos? (:sign (:seed rr))) "p" "m")
                                           " (" (:crown-hits (:seed rr)) " corona)"
                                           " · spiega " (:explained rr)
                                           " (fuori-anello " (:off-ring rr) ")"
                                           " · rms " (fmt (:rms-px rr) 1)
                                           (when (:phase-suspect rr) " · SOSPETTO ANELLO GIRATO")
                                           (when (seq (:index-obs rr))
                                             (str " · indice visto "
                                                  (fmt-mounting (mc/mounting-of (:index-obs rr)))))
                                           " · camera a " (if d (str (fmt d 1) "mm") "?")
                                           " dalla tua · " ms "ms"
                                           (when-not ok?
                                             (if truth-suspect?
                                               "   ← LONTANA, ma dalla POSA A MANO FUORI DAL VOTO: la verità qui è il sospetto"
                                               "   ← LONTANA"))))
                             ;; a FAR registration is either the bug being
                             ;; hunted or a poisoned truth — print both camera
                             ;; centres so the reflection relation can be read
                             ;; off (a hand pose that is the through-plastic
                             ;; twin sits at the auto centre mirrored through
                             ;; the ring's plane), and the trace
                             (when-not ok?
                               (println (str "      camera auto "
                                             (pr-str (mapv #(js/Math.round %) c-auto))
                                             " · a mano "
                                             (pr-str (mapv #(js/Math.round %) c-true))))
                               (doseq [t @tr] (println (str "      " (pr-str t)))))))
                         (step (inc i) obs-by-photo))))
                    (.catch (fn [e]
                              (println (str "  foto " (inc i) " ERRORE: " (str e)))
                              (step (inc i) obs-by-photo))))))]
      (if noctx?
        (step 0 {})
        (mounting-pass files state targets focal
                       (fn [obs]
                         (println (str "  contesto: osservazioni-indice sotto le pose a mano: "
                                       (apply str (interpose " · "
                                                             (for [[j o] (sort obs) :when (seq o)]
                                                               (str "foto " (inc j) " "
                                                                    (fmt-mounting (mc/mounting-of o))))))))
                         ;; a contested ring is a twin among the HAND poses —
                         ;; say so, and say who dissents
                         (doseq [[axis {:keys [sense votes against contested?]}]
                                 (mc/vote-mounting (vals obs))
                                 :when contested?]
                           (println (str "  ⚠ SESSIONE CONTESA sull'anello " (name axis)
                                         ": " votes " pose leggono " (name sense)
                                         ", " against " il senso opposto (foto "
                                         (apply str (interpose ", "
                                                               (for [[j o] (sort obs)
                                                                     :let [r (get (mc/mounting-of o) axis)]
                                                                     :when (and r (not= sense (:sense r)))]
                                                                 (inc j))))
                                         ") — una registrazione a mano è il gemello")))
                         (when-not (aget (.-env js/process) "CAGE_AUTO_CTXONLY")
                           (step 0 obs))))))))

(defn- seed-probe!
  "CAGE_AUTO_SEED=<n>: the user's HAND PICKS of photo n (1-based), probed —
   distance of each click to the nearest detected candidate (a starved
   detector is invisible in the app: it just arbitrates blind), the solve on
   the hand picks alone, and the best per-ring relabeling of them (greedy,
   96 solves). Built the night foto 3 of battiscopa3 registered at 72px and
   nobody could say which click was the traitor."
  []
  (let [n (js/parseInt (aget (.-env js/process) "CAGE_AUTO_SEED") 10)
        idx (dec n)
        state (js->clj (js/JSON.parse (.readFileSync fs (str dir "/acquire-state.json") "utf8"))
                       :keywordize-keys true)
        phases (or (some-> (aget (.-env js/process) "CAGE_AUTO_PHASES")
                           (js/JSON.parse) (js->clj :keywordize-keys true))
                   (get-in state [:cage-mounting-obs :cage :phases]))
        proxy (cage/registration-cage :d 176 :phases phases)
        targets (cage-targets proxy)
        by-id (into {} (map (juxt :id identity) targets))
        picks (get-in state [:pnp (keyword (str idx)) :picks])
        ;; CAGE_AUTO_PICKS_FILE: a JSON [["ym00",[u,v]], …] to probe a pick set
        ;; that never reached the state file — the camera-dietro refusal prints
        ;; the list in the log but does not save it (grab-04, 30/8)
        hand (if-let [pf (aget (.-env js/process) "CAGE_AUTO_PICKS_FILE")]
               (into {} (map (fn [[id px]] [(keyword id) (vec px)])
                             (js->clj (js/JSON.parse (.readFileSync fs pf "utf8")))))
               (into {} (keep (fn [[k v]]
                                (when-not (:proposed? v)
                                  [(:id (nth targets (js/parseInt (name k) 10))) (:px v)]))
                              picks)))
        focal (or (some-> (aget (.-env js/process) "CAGE_AUTO_FOCAL") js/parseFloat)
                  (get-in state [:focal :mm] 48.0))
        files (->> (.readdirSync fs dir) (filter #(re-find #"(?i)\.jpe?g$" %)) sort vec)
        file (nth files idx)]
    (println (str "\n=== sonda del seme: foto " n " (" file ") · " (count hand)
                  " click a mano · gabbia " (if (seq phases) (pr-str phases) "nominale")
                  " · focale " (fmt focal 1) "mm ==="))
    (-> (decode (.join path dir file))
        (.then
         (fn [res]
           (let [{:keys [data lum-at w h]} (sampler res)
                 intr (cam/intrinsics-from-fov (cam/equiv-focal->hfov-deg focal (/ w h)) w h)
                 cands (mapv :center (bd/detect-blobs lum-at [w h]
                                                      (assoc bd/cage-opts :rgba data)))
                 near (fn [[u v]] (reduce min js/Infinity
                                          (map (fn [[cu cv]] (Math/hypot (- cu u) (- cv v))) cands)))
                 corr-of (fn [pm] (vec (for [[id px] pm :when (by-id id)]
                                         {:ci id :world (:obj (by-id id)) :px px})))
                 solve (fn [pm] (when (>= (count pm) 6) (pnp/solve-pnp (corr-of pm) intr {})))
                 behind (fn [sol pm]
                          (when sol
                            (let [c (cam/camera-center (:pose sol))]
                              (vec (for [[id _] pm
                                         :let [t (by-id id)
                                               nrm (:normal t)]
                                         :when (and nrm (neg? (la/v-dot nrm (la/v-sub c (:obj t)))))]
                                     id)))))
                 report (fn [tag sol pm]
                          (println (str "  " tag ": "
                                        (if sol (str "rms " (fmt (:rms-px sol) 1) "px · scartati "
                                                     (pr-str (mapv :ci (:outliers sol)))
                                                     " · camera DIETRO a " (pr-str (behind sol pm)))
                                            "nessun solve"))))
                 axes (group-by (comp cage/anchor-axis key) hand)
                 relab (fn [ids rd] (into {} (keep (fn [id]
                                                     (when-let [i2 (cage/relabel id rd 12)]
                                                       [i2 (hand id)]))
                                                   ids)))]
             (println (str "  click → candidato rilevato più vicino (px): "
                           (pr-str (into (sorted-map)
                                         (for [[id px] hand] [id (js/Math.round (near px))])))))
             (report "solve sui SOLI click, nomi tuoi" (solve hand) hand)
             (doseq [[axis ids] (map (fn [[a m]] [a (vec (keys m))]) axes)]
               (let [others (into {} (mapcat (fn [[a m]] (when (not= a axis) m)) axes))
                     ;; POSSIBLE-FIRST, the rule the production code lives by:
                     ;; reprojection error cannot see a change of face (3mm
                     ;; through the plastic), so ranking by rms alone hands back
                     ;; the reading whose discs face away — every time. The
                     ;; physical test decides the candidate SET, the error only
                     ;; ranks within it. Without this the probe agreed with the
                     ;; app's own wrong answer on grab-04 (2026-08-31).
                     cands (keep (fn [rd]
                                   (let [pm (merge others (relab ids rd))]
                                     (when-let [sol (solve pm)]
                                       {:rd rd :rms (:rms-px sol)
                                        :nout (count (:outliers sol))
                                        :behind (count (behind sol pm))})))
                                 (cage/crown-misreadings 12))
                     poss (filterv #(zero? (:behind %)) cands)
                     best (first (sort-by (juxt :nout :rms) (or (seq poss) cands)))]
                 (println (str "  anello " (name axis) " rietichettato (altri fermi): "
                               (count poss) "/" (count cands) " possibili · meglio "
                               (pr-str (:rd best)) " → rms " (fmt (:rms best) 1)
                               "px · " (:nout best) " scartati"
                               (if (zero? (:behind best)) " · possibile"
                                   (str " · IMPOSSIBILE (camera dietro a "
                                        (:behind best) " punti)"))))
                 (when (seq poss)
                   (doseq [c (take 3 (sort-by (juxt :nout :rms) poss))]
                     (println (str "      possibile: " (pr-str (:rd c)) " → rms "
                                   (fmt (:rms c) 1) "px · " (:nout c) " scartati")))))))))
        (.catch (fn [e] (println (str "  ERRORE: " (str e))))))))

(defn- fit-probe!
  "CAGE_AUTO_FIT=<n>: the app's OWN solve on photo n (1-based), taken apart —
   hand clicks alone vs hand+proposals, and the GREEDY DROP TRACE (rms after
   each rejection). Built the night Vincenzo asked why the app kept telling him
   to re-click points he was sure of (battiscopa3 grab-01, 30/8): the answer is
   in the trace — when the rms does not COLLAPSE on a drop, the dropped point
   was not the culprit, it was merely the worst of a uniformly bad fit."
  []
  (let [n (js/parseInt (aget (.-env js/process) "CAGE_AUTO_FIT") 10)
        idx (dec n)
        state (js->clj (js/JSON.parse (.readFileSync fs (str dir "/acquire-state.json") "utf8"))
                       :keywordize-keys true)
        phases (or (some-> (aget (.-env js/process) "CAGE_AUTO_PHASES")
                           (js/JSON.parse) (js->clj :keywordize-keys true))
                   (get-in state [:cage-mounting-obs :cage :phases]))
        proxy (cage/registration-cage :d 176 :phases phases)
        targets (cage-targets proxy)
        picks (get-in state [:pnp (keyword (str idx)) :picks])
        entry (fn [[k v]] (let [t (nth targets (js/parseInt (name k) 10))]
                            {:ci (:id t) :world (:obj t) :normal (:normal t)
                             :px (:px v) :prop? (boolean (:proposed? v))}))
        by-id (into {} (map (juxt :id identity) targets))
        ;; CAGE_AUTO_FLIP=yz re-reads those rings on their OTHER face before
        ;; tracing — the way to ask "would the fit collapse if this ring's
        ;; declared face were the other one?"
        flips (set (map str (or (aget (.-env js/process) "CAGE_AUTO_FLIP") "")))
        reface (fn [id] (if (contains? flips (name (cage/anchor-axis id)))
                          (or (cage/relabel id {:flip-face? true :mirror? false :rot 0} 12) id)
                          id))
        all (mapv (fn [{:keys [ci px prop?]}]
                    (let [j (reface ci) t (by-id j)]
                      {:ci j :world (:obj t) :normal (:normal t) :px px :prop? prop?}))
                  (if-let [pf (aget (.-env js/process) "CAGE_AUTO_PICKS_FILE")]
                    (mapv (fn [[id px]] {:ci (keyword id) :px (vec px) :prop? false})
                          (js->clj (js/JSON.parse (.readFileSync fs pf "utf8"))))
                    (mapv entry picks)))
        hand (filterv (complement :prop?) all)
        focal (or (some-> (aget (.-env js/process) "CAGE_AUTO_FOCAL") js/parseFloat)
                  (get-in state [:focal :mm] 48.0))
        files (->> (.readdirSync fs dir) (filter #(re-find #"(?i)\.jpe?g$" %)) sort vec)
        file (nth files idx)]
    (-> (decode (.join path dir file))
        (.then
         (fn [res]
           (let [{:keys [w h]} (sampler res)
                 intr (cam/intrinsics-from-fov (cam/equiv-focal->hfov-deg focal (/ w h)) w h)
                 solve (fn [cs] (when (>= (count cs) 6) (pnp/solve-pnp (vec cs) intr {})))
                 ;; RAW fit, no internal cleaning, so each row of the trace is
                 ;; the honest rms of exactly that set (solve-pnp with its own
                 ;; rejection on would nest a cleaning inside every row)
                 raw (fn [cs] (when (>= (count cs) 6)
                                (pnp/solve-pnp (vec cs) intr {:max-outliers 0})))
                 ;; the greedy loop of solve-pnp, printed: rms of the FULL set,
                 ;; then after each worst-point rejection
                 trace (fn [cs]
                         (loop [cs (vec cs) out [] acc []]
                           (if-let [r (raw cs)]
                             (let [acc (conj acc [(count cs) (:rms-px r) (last out)])]
                               (if (or (<= (count cs) 6) (>= (count out) 4))
                                 acc
                                 (let [worst (apply max-key :residual-px (:per-point r))]
                                   (recur (vec (remove #(= (:ci %) (:ci worst)) cs))
                                          (conj out (:ci worst)) acc))))
                             acc)))
                 show (fn [tag cs]
                        (println (str "  " tag " (" (count cs) " punti):"))
                        (doseq [[nn rms dropped] (trace cs)]
                          (println (str "    n=" nn " rms " (fmt rms 1) "px"
                                        (when dropped (str "   ← tolto " dropped)))))) ]
             (println (str "\n=== sonda del fit: foto " n " (" file ") · " (count all)
                           " punti (" (count hand) " a mano, " (- (count all) (count hand))
                           " proposte) · focale " (fmt focal 1) "mm ==="))
             (show "TUTTI (come fa l'app)" all)
             (show "SOLO i tuoi click" hand)
             (when-let [r (solve all)]
               (println (str "  verdetto dell'app: rms " (fmt (:rms-px r) 1) "px, scartati "
                             (pr-str (mapv :ci (:outliers r)))
                             " — di cui proposte: "
                             (pr-str (vec (for [o (:outliers r)
                                                :let [e (first (filter #(= (:ci %) (:ci o)) all))]
                                                :when (:prop? e)]
                                            (:ci o)))))))
             (println (str "  residui per punto (fit su TUTTI, ordinati):"))
             (when-let [r (solve all)]
               (doseq [p (sort-by :residual-px > (concat (:per-point r) (:outliers r)))]
                 (println (str "    " (fmt (:residual-px p) 1) "px  " (:ci p)
                               (when (:prop? (first (filter #(= (:ci %) (:ci p)) all)))
                                 "  (proposta automatica)"))))))))
        (.catch (fn [e] (println (str "  ERRORE: " (str e))))))))

(defn- face-probe!
  "CAGE_AUTO_FACE=<n>: on photo n (1-based), the eight ways the three rings'
   FACES can be declared, each scored on the user's HAND CLICKS alone and
   filtered by the physical test (the disc was photographed, so the camera was
   in front of it). The rms alone must never decide a face — through 3mm of
   plastic the two faces are the same pixels — so the possible ones are listed
   first and the error only ranks WITHIN them."
  []
  (let [n (js/parseInt (aget (.-env js/process) "CAGE_AUTO_FACE") 10)
        idx (dec n)
        state (js->clj (js/JSON.parse (.readFileSync fs (str dir "/acquire-state.json") "utf8"))
                       :keywordize-keys true)
        phases (or (some-> (aget (.-env js/process) "CAGE_AUTO_PHASES")
                           (js/JSON.parse) (js->clj :keywordize-keys true))
                   (get-in state [:cage-mounting-obs :cage :phases]))
        proxy (cage/registration-cage :d 176 :phases phases)
        targets (cage-targets proxy)
        by-id (into {} (map (juxt :id identity) targets))
        picks (get-in state [:pnp (keyword (str idx)) :picks])
        ;; CAGE_AUTO_PICKS_FILE works here too: the camera-dietro refusal prints
        ;; its pick list in the log and saves nothing, so that log line is often
        ;; the ONLY record of the set that failed
        hand (if-let [pf (aget (.-env js/process) "CAGE_AUTO_PICKS_FILE")]
               (into {} (map (fn [[id px]] [(keyword id) (vec px)])
                             (js->clj (js/JSON.parse (.readFileSync fs pf "utf8")))))
               (into {} (keep (fn [[k v]]
                                (when-not (:proposed? v)
                                  [(:id (nth targets (js/parseInt (name k) 10))) (:px v)]))
                              picks)))
        focal (or (some-> (aget (.-env js/process) "CAGE_AUTO_FOCAL") js/parseFloat)
                  (get-in state [:focal :mm] 48.0))
        files (->> (.readdirSync fs dir) (filter #(re-find #"(?i)\.jpe?g$" %)) sort vec)
        file (nth files idx)]
    (-> (decode (.join path dir file))
        (.then
         (fn [res]
           (let [{:keys [w h]} (sampler res)
                 intr (cam/intrinsics-from-fov (cam/equiv-focal->hfov-deg focal (/ w h)) w h)
                 corr-of (fn [pm] (vec (for [[id px] pm :when (by-id id)]
                                         {:ci id :world (:obj (by-id id)) :px px})))
                 solve (fn [pm] (when (>= (count pm) 6) (pnp/solve-pnp (corr-of pm) intr {})))
                 behind (fn [sol pm]
                          (let [c (cam/camera-center (:pose sol))]
                            (vec (for [[id _] pm
                                       :let [t (by-id id) nrm (:normal t)]
                                       :when (and nrm (neg? (la/v-dot nrm (la/v-sub c (:obj t)))))]
                                   id))))
                 flip-ring (fn [pm axis]
                             (into {} (map (fn [[id px]]
                                             [(if (= axis (cage/anchor-axis id))
                                                (cage/relabel id {:flip-face? true :mirror? false :rot 0} 12)
                                                id)
                                              px])
                                           pm)))
                 ;; the SET of faces a ring's picks name. More than one means the
                 ;; picks contradict themselves — crown on one face, zero-index
                 ;; on the other is physically unseeable and no relabeling of the
                 ;; RING can cure it (battiscopa3 grab-04, 2026-08-31)
                 faces-of (fn [pm axis]
                            (set (keep (fn [[id _]]
                                         (when (= axis (cage/anchor-axis id))
                                           (or (:sign (cage/mark-parts id))
                                               (:sign (cage/index-parts id)))))
                                       pm)))
                 face-tag (fn [pm axis]
                            (let [fs (faces-of pm axis)]
                              (cond (empty? fs) "--"
                                    (> (count fs) 1) (str (name axis) "?!")
                                    :else (str (name axis) (if (pos? (first fs)) "p" "m")))))]
             (println (str "\n=== sonda delle facce: foto " n " (" file ") · " (count hand)
                           " click a mano · focale " (fmt focal 1) "mm ==="))
             (doseq [fx [false true] fy [false true] fz [false true]]
               (let [pm (cond-> hand
                          fx (flip-ring :x) fy (flip-ring :y) fz (flip-ring :z))]
                 (when-let [sol (solve pm)]
                   (let [bh (behind sol pm)]
                     (println (str "  " (face-tag pm :x) " " (face-tag pm :y)
                                   " " (face-tag pm :z)
                                   "  rms " (fmt (:rms-px sol) 1) "px"
                                   " · scartati " (pr-str (mapv :ci (:outliers sol)))
                                   (if (seq bh)
                                     (str " · IMPOSSIBILE: camera dietro a " (pr-str bh))
                                     " · possibile"))))))))))
        (.catch (fn [e] (println (str "  ERRORE: " (str e))))))))

(defn- joint-probe!
  "CAGE_AUTO_JOINT=<n>: the FULL joint search over ring namings that production
   only approximates. For each of the 48 misreadings of the anchor ring (the one
   with the most picks) solve from that ring alone, then let every other ring
   pick its own best POSSIBLE misreading against that pose, refit, and keep what
   survives the physical test.

   `cage-relabel-rescue` anchors on two readings only — the two faces at rot 0 —
   so when the anchor ring is itself rotated or mirrored (which is the ordinary
   case: a crown of twelve equal discs reads the same turned) every ring
   downstream is judged against a wrong pose. Built for battiscopa3 grab-06
   (2026-08-31), where all eight clicks sat within a pixel of a detected disc
   and the fit was 30px."
  []
  (let [n (js/parseInt (aget (.-env js/process) "CAGE_AUTO_JOINT") 10)
        idx (dec n)
        state (js->clj (js/JSON.parse (.readFileSync fs (str dir "/acquire-state.json") "utf8"))
                       :keywordize-keys true)
        phases (or (some-> (aget (.-env js/process) "CAGE_AUTO_PHASES")
                           (js/JSON.parse) (js->clj :keywordize-keys true))
                   (get-in state [:cage-mounting-obs :cage :phases]))
        proxy (cage/registration-cage :d 176 :phases phases)
        targets (cage-targets proxy)
        by-id (into {} (map (juxt :id identity) targets))
        picks (get-in state [:pnp (keyword (str idx)) :picks])
        hand (if-let [pf (aget (.-env js/process) "CAGE_AUTO_PICKS_FILE")]
               (into {} (map (fn [[id px]] [(keyword id) (vec px)])
                             (js->clj (js/JSON.parse (.readFileSync fs pf "utf8")))))
               (into {} (keep (fn [[k v]]
                                (when-not (:proposed? v)
                                  [(:id (nth targets (js/parseInt (name k) 10))) (:px v)]))
                              picks)))
        focal (or (some-> (aget (.-env js/process) "CAGE_AUTO_FOCAL") js/parseFloat)
                  (get-in state [:focal :mm] 48.0))
        files (->> (.readdirSync fs dir) (filter #(re-find #"(?i)\.jpe?g$" %)) sort vec)
        file (nth files idx)]
    (-> (decode (.join path dir file))
        (.then
         (fn [res]
           (let [{:keys [w h]} (sampler res)
                 intr (cam/intrinsics-from-fov (cam/equiv-focal->hfov-deg focal (/ w h)) w h)
                 corr-of (fn [pm] (vec (for [[id px] pm :when (by-id id)]
                                         {:ci id :world (:obj (by-id id)) :px px})))
                 solve (fn [pm] (when (>= (count pm) 6) (pnp/solve-pnp (corr-of pm) intr {})))
                 behind (fn [pose pm]
                          (let [c (cam/camera-center pose)]
                            (vec (for [[id _] pm
                                       :let [t (by-id id) nrm (:normal t)]
                                       :when (and nrm (neg? (la/v-dot nrm (la/v-sub c (:obj t)))))]
                                   id))))
                 relab (fn [pm rd] (into {} (keep (fn [[id px]]
                                                    (when-let [j (cage/relabel id rd 12)]
                                                      [j px])))
                                         pm))
                 rings (group-by (comp cage/anchor-axis key) hand)
                 anchor-axis (key (apply max-key (comp count val) rings))
                 anchor (into {} (get rings anchor-axis))
                 others (into {} (mapcat val (dissoc rings anchor-axis)))
                 ;; reproject-nearest: given a pose, the reading of ONE ring that
                 ;; lands its picks closest to where that pose says its discs are,
                 ;; among the POSSIBLE ones (the camera must be in front)
                 best-for (fn [pose pm]
                            (->> (cage/crown-misreadings 12)
                                 (keep (fn [rd]
                                         (let [pm' (relab pm rd)]
                                           (when (= (count pm') (count pm))
                                             (when (empty? (behind pose pm'))
                                               (let [e (reduce
                                                        + (for [[id px] pm'
                                                                :let [p (cam/project intr pose (:obj (by-id id)))]]
                                                            (if p (Math/hypot (- (first p) (first px))
                                                                              (- (second p) (second px)))
                                                                1e9)))]
                                                 {:rd rd :err e :pm pm'}))))))
                                 (sort-by :err) first))
                 ;; CAGE_AUTO_SEEDALL=1 seeds each anchor reading from ALL the
                 ;; picks; the default seeds from the ANCHOR RING ALONE, which is
                 ;; what cage-relabel-rescue does. Keeping both apart is the only
                 ;; way to say WHICH change buys a rescue (2026-08-31).
                 seed-all? (boolean (aget (.-env js/process) "CAGE_AUTO_SEEDALL"))
                 results
                 (->> (cage/crown-misreadings 12)
                      (keep (fn [rd]
                              (let [a (relab anchor rd)]
                                (when (= (count a) (count anchor))
                                  (when-let [seed (if seed-all?
                                                    (solve (merge a others))
                                                    (pnp/solve-pnp (corr-of a) intr {}))]
                                    (let [pose (:pose seed)
                                          picked (keep (fn [[ax pm]]
                                                         (best-for pose (into {} pm)))
                                                       (dissoc rings anchor-axis))]
                                      (when (= (count picked) (dec (count rings)))
                                        (let [pm (apply merge a (map :pm picked))]
                                          (when-let [sol (solve pm)]
                                            {:anchor rd :pm pm :rms (:rms-px sol)
                                             :behind (count (behind (:pose sol) pm))
                                             :nout (count (:outliers sol))})))))))))
                      (sort-by :rms))
                 poss (filterv #(zero? (:behind %)) results)
                 tag (fn [pm] (str/join " " (for [ax [:x :y :z]
                                                  :let [fs (set (keep (fn [[id _]]
                                                                        (when (= ax (cage/anchor-axis id))
                                                                          (:sign (or (cage/mark-parts id)
                                                                                     (cage/index-parts id)))))
                                                                      pm))]
                                                  :when (seq fs)]
                                              (str (name ax) (if (> (count fs) 1) "?!"
                                                                 (if (pos? (first fs)) "p" "m"))))))]
             (println (str "\n=== sonda congiunta: foto " n " (" file ") · " (count hand)
                           " click · anello di ancoraggio " (name anchor-axis)
                           " (" (count anchor) " pick) · focale " (fmt focal 1) "mm ==="))
             (println (str "  " (count results) " letture congiunte provate · "
                           (count poss) " fisicamente possibili"))
             (doseq [r (take 5 poss)]
               (println (str "    " (tag (:pm r)) "  rms " (fmt (:rms r) 1) "px · ancora "
                             (pr-str (:anchor r)) " · " (:nout r) " scartati")))
             (when (empty? poss)
               (println "  nessuna possibile; le migliori per rms, impossibili:")
               (doseq [r (take 3 results)]
                 (println (str "    " (tag (:pm r)) "  rms " (fmt (:rms r) 1)
                               "px · camera dietro a " (:behind r) " punti")))))))
        (.catch (fn [e] (println (str "  ERRORE: " (str e))))))))

(defn ^:export main [& _]
  (cond
    (aget (.-env js/process) "CAGE_AUTO_SYNTH") (synth-run!)
    (aget (.-env js/process) "CAGE_AUTO_SEED") (seed-probe!)
    (aget (.-env js/process) "CAGE_AUTO_FIT") (fit-probe!)
    (aget (.-env js/process) "CAGE_AUTO_FACE") (face-probe!)
    (aget (.-env js/process) "CAGE_AUTO_JOINT") (joint-probe!)
    :else (main*)))
