(ns ridley.photogrammetry.match-cage-test
  "Identity on a CAGE: the user names one ring's marks, gets the naming wrong —
   which they cannot help, since a crown of twelve equal marks reads the same
   rotated and mirrored — and the REST OF THE CAGE puts it right.

   The tests are built so the thing under test cannot pass by accident: the picks
   handed in are always MISNAMED by a known amount, and the assertion is that the
   returned reading is exactly the one that undoes it. A search that ignored the
   other rings would score every reading the same and could only guess."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [ridley.photogrammetry.cage :as cage]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.match-cage :as mc]
            [ridley.photogrammetry.pnp :as pnp]
            [ridley.photogrammetry.blob-detect :as bd]
            [ridley.photogrammetry.synth :as synth]))

(def ^:private marks 12)

(defn- cage-targets
  "The proxy's marks in the shape bridge/pnp-target-points hands the editor."
  [proxy]
  (vec (for [[id pose] (sort-by key (:anchors proxy))]
         {:id id :obj (:position pose)
          :normal (let [h (:heading pose) n (la/v-norm h)]
                    (when (pos? n) (la/v-scale h (/ 1.0 n))))
          :index? (some? (cage/index-parts id))})))

(defn- scene
  "Where every front-facing mark of `proxy` lands, under `pose` — the ideal a
   detector would return on a perfect frame."
  [proxy targets intr pose]
  (let [c (cam/camera-center pose)]
    (vec (for [{:keys [obj normal]} targets
               :when (or (nil? normal) (pos? (la/v-dot normal (la/v-sub c obj))))
               :let [px (cam/project intr pose obj)]
               :when px]
           px))))

(defn- misname
  "The picks a user would hand in having started counting `k` marks late: the
   pixel of the true mark `i`, filed under the name of mark `i − k`."
  [targets intr pose axis ids k]
  (into {} (for [i ids
                 :let [true-id (cage/mark-id axis -1 i)
                       said-id (cage/mark-id axis -1 (mod (- i k) marks))
                       t (first (filter #(= true-id (:id %)) targets))
                       px (cam/project intr pose (:obj t))]
                 :when px]
             [said-id px])))

(defn- setup [eye]
  (let [proxy (cage/registration-cage :d 176)
        targets (cage-targets proxy)
        w 3024 h 4032
        intr (cam/intrinsics-from-fov (cam/equiv-focal->hfov-deg 48.0 (/ w h)) w h)
        ]
    {:proxy proxy :targets targets :intr intr
     :pose (cam/look-at-pose eye [0.0 0.0 0.0] [0.0 0.0 1.0])
     :size [w h]}))

;; A vantage that shows all three rings obliquely — the cage's normal working
;; condition, and the only one in which the other two rings can arbitrate at all.
(def ^:private eye [150.0 -210.0 190.0])

(deftest reads-the-crown-the-user-misnamed
  (println "\n=== gabbia: la corona che l'utente ha chiamato male ===")
  (let [{:keys [targets intr pose]} (setup eye)
        cands (scene nil targets intr pose)]
    (doseq [k [0 3 5 9]]
      (let [picks (misname targets intr pose :x [0 1 2 3 8 9 10] k)
            r (mc/read-crown picks targets cands intr marks)]
        (println (str "  sfasamento vero " k " · letto "
                      (when r (str "rot " (:rot (:reading r))
                                   " mirror " (:mirror? (:reading r))
                                   " · spiega " (:explained r)
                                   " · corr " (count (:corr r))
                                   " · rms pieno "
                                   (when (:full r) (.toFixed (:rms-px (:full r)) 2))))))
        (is (some? r) (str "una lettura si trova (sfasamento " k ")"))
        (when r
          (is (= k (:rot (:reading r)))
              (str "la rilettura annulla lo sfasamento: attesa rot " k
                   ", ottenuta " (:rot (:reading r))))
          (is (false? (:mirror? (:reading r))) "senza specchiatura")
          (is (>= (count (:corr r)) 12)
              (str "raccoglie i mark di TUTTA la gabbia, non solo i cliccati ("
                   (count (:corr r)) ")"))
          (is (and (:full r) (< (:rms-px (:full r)) 2.0))
              (str "e la posa risolta su tutti chiude stretta ("
                   (when (:full r) (.toFixed (:rms-px (:full r)) 2)) "px)")))))))

(deftest the-clicked-ring-alone-cannot-choose
  ;; The premise the whole namespace rests on, asserted rather than assumed: every
  ;; reading fits the CLICKED RING equally well. If this ever stopped being true
  ;; the search would be pointless — and, more to the point, a future reader is
  ;; entitled to see the fact that motivates the design, checked.
  (testing "tutte le riletture spiegano l'anello cliccato allo stesso modo"
    (let [{:keys [targets intr pose]} (setup eye)
          picks (misname targets intr pose :x [0 1 2 3 8 9 10] 3)
          by-id (into {} (map (juxt :id identity) targets))
          rmss (for [m (cage/crown-misreadings marks)
                     :let [p2 (into {} (keep (fn [[id px]]
                                               (when-let [i2 (cage/relabel id m marks)]
                                                 [i2 px]))
                                             picks))
                           corr (vec (keep (fn [[id px]]
                                             (when-let [t (by-id id)]
                                               {:world (:obj t) :px px}))
                                           p2))
                           sol (when (>= (count corr) 4)
                                 (pnp/solve-pnp corr intr {}))]
                     :when sol]
                 (:rms-px sol))]
      (println (str "\n=== gabbia: le 48 riletture viste dal solo anello ===\n"
                    "  " (count rmss) " riletture · rms fra "
                    (.toFixed (apply min rmss) 2) " e " (.toFixed (apply max rmss) 2) "px"))
      (is (>= (count rmss) 40) "quasi tutte le riletture si risolvono")
      (is (< (- (apply max rmss) (apply min rmss)) 1.0)
          "e l'anello da solo non le distingue: sono tutte lo stesso residuo"))))

(deftest a-frame-that-shows-one-ring-cannot-decide
  ;; Down a cage axis only one ring is presented, so there is nothing to arbitrate
  ;; with. The honest outcome is either a refusal or a declared tie — never a
  ;; confident wrong answer, which is what a scorer blind to the other rings would
  ;; produce every time.
  (testing "una vista che mostra un anello solo non decide di nascosto"
    (let [{:keys [targets intr pose]} (setup [0.0 0.0 320.0])
          cands (scene nil targets intr pose)
          picks (misname targets intr pose :z [0 1 2 3 4 5 6] 4)
          r (mc/read-crown picks targets cands intr marks)]
      (println (str "\n=== gabbia: una vista che non può arbitrare ===\n"
                    "  " (if r (str "lettura rot " (:rot (:reading r))
                                    " · spiega " (:explained r)
                                    " · in parità " (count (:ties r)))
                             "nessuna lettura")))
      (is (or (nil? r) (seq (:ties r)) (= 4 (:rot (:reading r))))
          "o rifiuta, o dichiara la parità, o ha ragione — mai una risposta sicura e sbagliata"))))

(deftest a-glued-ring-is-diagnosed-not-outvoted
  ;; The assembly error nothing else can see, reproduced synthetically: the cage
  ;; PHOTOGRAPHED has its big ring glued a quarter turn round (:phases {:x 90}),
  ;; the proxy MODELLING it does not know. The user clicks marks of that ring plus
  ;; its zero-index, with the labels the index pins — which are RIGHT.
  ;;
  ;; Scored on candidates alone, the readings that explain most are the ones that
  ;; rotate the labels and silently throw the clicked index away. The index cannot
  ;; be outvoted: read-crown must return the index-keeping reading AND name the
  ;; real culprit — the glued ring — as :phase-suspect. Found live on Vincenzo's
  ;; cage (2026-08-24) before it was a test.
  (println "
=== gabbia: l'anello incollato girato si diagnostica, non si vota ===")
  (let [glued (cage/registration-cage :d 176 :phases {:x 90})
        model (cage/registration-cage :d 176)
        truth-targets (cage-targets glued)
        targets (cage-targets model)
        w 3024 h 4032
        intr (cam/intrinsics-from-fov (cam/equiv-focal->hfov-deg 48.0 (/ w h)) w h)
        pose (cam/look-at-pose eye [0.0 0.0 0.0] [0.0 0.0 1.0])
        cands (scene nil truth-targets intr pose)
        ;; picks named as the index pins them, pixels from the GLUED cage
        pick-of (fn [id] (let [t (first (filter #(= id (:id %)) truth-targets))]
                           (cam/project intr pose (:obj t))))
        picks (into {} (for [id [:xm00 :xm03 :xm06 :xm09 :zero-xm]
                             :let [px (pick-of id)] :when px]
                         [id px]))
        r (mc/read-crown picks targets cands intr marks)]
    (println (str "  lettura " (when r (str "rot " (:rot (:reading r))
                                            " · spiega " (:explained r)
                                            " · sospetto " (pr-str (:phase-suspect r))))))
    (is (some? r) "una lettura si trova")
    (when r
      (is (= 0 (:rot (:reading r)))
          "lo zero-indice non si mette ai voti: vince la lettura che lo tiene")
      (is (some? (:phase-suspect r)) "e l'anello incollato girato viene DIAGNOSTICATO")
      (when-let [ps (:phase-suspect r)]
        (is (= :x (:axis ps)) "sull'asse giusto")
        (is (= 3 (:steps ps)) (str "di 3 passi (90°), non " (:steps ps)))))
    ;; and with the phase DECLARED, the same picks read clean: no suspect, and the
    ;; other rings' marks are collected
    (let [r2 (mc/read-crown picks truth-targets cands intr marks)]
      (println (str "  col modello fasato: "
                    (when r2 (str "rot " (:rot (:reading r2)) " · spiega " (:explained r2)
                                  " · corr " (count (:corr r2))
                                  " · sospetto " (pr-str (:phase-suspect r2))))))
      (is (some? r2) "col modello fasato la lettura c'è")
      (when r2
        (is (= 0 (:rot (:reading r2))) "i nomi restano giusti")
        (is (nil? (:phase-suspect r2)) "nessun sospetto: la fase dichiarata spiega tutto")
        (is (>= (count (:corr r2)) 20)
            (str "e il resto della gabbia si raccoglie (" (count (:corr r2)) ")"))))))

(deftest auto-read-registers-with-no-clicks
  ;; Zero-click on a synthetic cage: candidates are every front-facing mark's
  ;; true pixel, the judge is proximity to a candidate, and auto-read gets no
  ;; picks at all. It must find a ring, pin its index, and land the pose on the
  ;; truth — and must NOT return the ring-family twin (the same discs read as a
  ;; different ring, which rms alone cannot reject: measured live, 697mm out at
  ;; rms 1.3).
  (println "\n=== gabbia: lettura senza click ===")
  (let [{:keys [targets intr pose]} (setup eye)
        cands (scene nil targets intr pose)
        judge (fn [px _r] (boolean (some (fn [[u v]]
                                           (< (Math/hypot (- u (first px)) (- v (second px))) 4.0))
                                         cands)))
        rr (mc/auto-read cands targets intr judge marks {:disc-r 1.25})]
    (println (str "  " (count cands) " candidati · "
                  (if rr (str "seme " (name (:axis (:seed rr)))
                              " · spiega " (:explained rr)
                              " · off-ring " (:off-ring rr)
                              " · rms " (.toFixed (:rms-px rr) 2))
                      "RIFIUTATA")))
    (is (some? rr) "la gabbia si legge da sola")
    (when rr
      (let [c-auto (cam/camera-center (:pose rr))
            c-true (cam/camera-center pose)
            d (la/v-norm (la/v-sub c-auto c-true))]
        (println (str "  camera a " (.toFixed d 2) "mm dalla verità"))
        (is (< d 5.0) (str "e la posa è quella vera (" (.toFixed d 1) "mm)"))
        (is (pos? (:off-ring rr)) "confermata anche fuori dall'anello del seme")))))

(deftest mutual-nearest-refuses-a-shared-disc
  (testing "due mark non possono rivendicare lo stesso dischetto"
    (let [{:keys [targets intr pose]} (setup eye)
          ;; one candidate only, sitting between two neighbouring marks
          preds (scene nil targets intr pose)
          [a b] (take 2 (sort-by (fn [[u v]] (+ u v)) preds))
          mid [(/ (+ (first a) (first b)) 2.0) (/ (+ (second a) (second b)) 2.0)]
          corr (mc/assign targets [mid] intr pose 1e6)]
      (is (<= (count corr) 1)
          (str "un solo dischetto non può servire due mark (" (count corr) ")")))))

;; ── on real pixels ───────────────────────────────────────────────────────────

(def ^:private picks-9014
  "The five picks of Vincenzo's live gate on IMG_9014 (2026-08-24), with the names
   he gave them — which the zero-index proves RIGHT. Four crown marks 3 apart
   ({0,3,6,9}, the 4-fold-symmetric subset, invariant under the very rotation in
   question) plus the zero: the pick set that makes candidate scoring weakest and
   the index most decisive, i.e. the hard case."
  {:xm00 [492.5 1004.4] :xm03 [2260.4 574.7] :xm06 [2648.4 2359.1]
   :xm09 [697.2 2752.9] :zero-xm [693.9 890.8]})

(def ^:private inner-9014
  "Four marks on the two INNER rings whose pixel position is known — read off the
   photograph at 4× zoom. Their identity is not known, and is not needed: the test
   is that the solved pose puts SOME mark on each of them, which is precisely what
   the user's own reading fails to do (92-157px)."
  [[2274.1 2116.5] [1898.9 2047.2] [1021.4 1711.0] [661.1 1491.7]])

(defn- node-require [m] (try (js/require m) (catch :default _ nil)))

(deftest real-cage-photo-read-crown
  ;; The fetta-2 gate, on Vincenzo's photograph. Guarded like the detector's:
  ;; skipped where node fs+sharp or the (untracked) photo are absent.
  (let [fs (node-require "fs")
        sharp (node-require "sharp")
        file "test-assets/cage-presa/IMG_9014.jpeg"]
    (if (or (nil? fs) (nil? sharp) (not (.existsSync ^js fs file)))
      (println "\n=== gabbia reale: lettura SALTATA (fs/sharp/foto assenti) ===")
      (async done
             (-> (let [^js img (sharp file)]
                   (.toBuffer (.raw (.ensureAlpha (.rotate img)))
                              #js {:resolveWithObject true}))
                 (.then
                  (fn [^js res]
                    (let [data (.-data res) info (.-info res)
                          w (.-width info) h (.-height info)
                          lum-at (fn [x y]
                                   (let [xi (Math/round x) yi (Math/round y)]
                                     (when (and (>= xi 0) (>= yi 0) (< xi w) (< yi h))
                                       (let [o (* 4 (+ xi (* yi w)))]
                                         (+ (* 0.299 (aget data o)) (* 0.587 (aget data (+ o 1)))
                                            (* 0.114 (aget data (+ o 2))))))))
                          intr (cam/intrinsics-from-fov
                                (cam/equiv-focal->hfov-deg 48.0 (/ w h)) w h)
                          cands (mapv :center
                                      (bd/detect-blobs
                                       lum-at [w h]
                                       (assoc bd/cage-opts :rgba data)))
                          targets (cage-targets (cage/registration-cage :d 176))
                          r (mc/read-crown picks-9014 targets cands intr marks)
                          near (fn [pose px]
                                 (reduce (fn [b {:keys [obj]}]
                                           (if-let [[u v] (cam/project intr pose obj)]
                                             (min b (Math/hypot (- u (first px)) (- v (second px))))
                                             b))
                                         js/Infinity targets))]
                      (println "\n=== gabbia reale: lo zero-indice smaschera l'anello girato ===")
                      (println (str "  " (count cands) " candidati · lettura "
                                    (when r (str "rot " (:rot (:reading r))
                                                 " · spiega " (:explained r)
                                                 " · sospetto " (pr-str (:phase-suspect r))))))
                      ;; Vincenzo's cage has its big ring GLUED 90° round (confirmed
                      ;; three ways on 2026-08-24: candidates, the clicked zero, and
                      ;; the faces he read off the part). The unphased model must
                      ;; keep his labels — the zero pins them — and DIAGNOSE the
                      ;; turn, not rename his picks to paper over it.
                      (is (some? r) "una lettura si trova sui pixel veri")
                      (when r
                        (is (= 0 (:rot (:reading r)))
                            "i nomi di Vincenzo erano GIUSTI: lo zero-indice li conferma")
                        (is (= 3 (:steps (:phase-suspect r) 0))
                            (str "e l'anello grande è diagnosticato incollato a 90° ("
                                 (pr-str (:phase-suspect r)) ")")))
                      ;; declared, the same picks register the whole cage
                      (let [phased (cage-targets (cage/registration-cage :d 176 :phases {:x 90}))
                            r2 (mc/read-crown picks-9014 phased cands intr marks)
                            near2 (fn [pose px]
                                    (reduce (fn [b {:keys [obj]}]
                                              (if-let [[u v] (cam/project intr pose obj)]
                                                (min b (Math/hypot (- u (first px)) (- v (second px))))
                                                b))
                                            js/Infinity phased))]
                        (println (str "  col modello fasato {:x 90}: "
                                      (when r2 (str "rot " (:rot (:reading r2))
                                                    " · spiega " (:explained r2)
                                                    " · corr " (count (:corr r2))
                                                    " · rms pieno "
                                                    (when (:full r2) (.toFixed (:rms-px (:full r2)) 2))))))
                        (is (some? r2) "col modello fasato la lettura c'è")
                        (when r2
                          (is (= 0 (:rot (:reading r2))) "nomi giusti anche qui")
                          (is (nil? (:phase-suspect r2)) "e nessun sospetto residuo")
                          (is (>= (:explained r2) 15) "la gabbia intera si spiega")
                          (when (:full r2)
                            (println (str "  ai 4 mark interni noti: "
                                          (mapv #(.toFixed (near2 (:pose (:full r2)) %) 0) inner-9014)
                                          " (col modello non fasato erano 92-157)"))
                            (doseq [px inner-9014]
                              (is (< (near2 (:pose (:full r2)) px) 30.0)
                                  (str "il mark interno " (mapv #(Math/round %) px)
                                       " ha ora un mark addosso ("
                                       (.toFixed (near2 (:pose (:full r2)) px) 1) "px)"))))))
                      (done))))
                 (.catch (fn [e]
                           (println "  errore:" (str e))
                           (is false "il ramo reale non deve lanciare")
                           (done))))))))
