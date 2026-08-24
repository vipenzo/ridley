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
  "The nine picks Vincenzo made on the largest ring of IMG_9014, WITH THE NAMES HE
   GAVE THEM — which is the point: eight of them are three marks out of phase, and
   nothing in that ring can say so. (`xm07` is corrected to the mark it actually
   names; the session file has it 143px away on blank band. `xm09` is left exactly
   as clicked, wrong name and all, because a real run will contain such a pick and
   the search has to survive one.)"
  {:xm00 [494.0 1005.8] :xm01 [992.3 578.5] :xm02 [1631.4 422.0]
   :xm03 [2261.8 573.9] :xm04 [2728.4 1022.3] :xm06 [2648.0 2362.9]
   :xm07 [2071.0 2853.0] :xm09 [304.6 2235.1] :zero-xm [693.9 890.8]})

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
                      (println "\n=== gabbia reale: la lettura, arbitrata dagli altri anelli ===")
                      (println (str "  " (count cands) " candidati · lettura "
                                    (when r (str "rot " (:rot (:reading r))
                                                 " mirror " (:mirror? (:reading r))
                                                 " faccia-girata " (:flip-face? (:reading r))))
                                    " · spiega " (when r (:explained r))
                                    " · corr " (when r (count (:corr r)))))
                      (is (some? r) "una lettura si trova sui pixel veri")
                      (when r
                        (println (str "  rms anello " (.toFixed (:rms-px r) 2)
                                      "px · posa su tutte le corrispondenze "
                                      (when (:full r) (str (.toFixed (:rms-px (:full r)) 2) "px"))))
                        (println (str "  ai 4 mark interni noti: "
                                      (mapv #(.toFixed (near (:pose (:full r)) %) 0) inner-9014)
                                      " (con la lettura dell'utente erano 157 127 92 139)"))
                        (is (= 3 (:rot (:reading r)))
                            (str "il resto della gabbia dice rot 3, non " (:rot (:reading r))))
                        (is (>= (:explained r) 15) "conferma una buona parte della gabbia")
                        (is (some? (:full r)) "e risolve la posa su tutte le corrispondenze")
                        (when (:full r)
                          (doseq [px inner-9014]
                            (is (< (near (:pose (:full r)) px) 26.0)
                                (str "il mark interno " (mapv #(Math/round %) px)
                                     " ha ora un mark addosso ("
                                     (.toFixed (near (:pose (:full r)) px) 1) "px)")))))
                      (done))))
                 (.catch (fn [e]
                           (println "  errore:" (str e))
                           (is false "il ramo reale non deve lanciare")
                           (done))))))))
