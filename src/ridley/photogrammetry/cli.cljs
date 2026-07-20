(ns ridley.photogrammetry.cli
  "Command line for the extraction loop.

       node out/paq.js picks.edn [predictions.json]

   Reads the clicks exported by scripts/param-acq-tool.html, bootstraps the
   labelling, prints the residual report, and writes the reprojected
   proposals for the tool to overlay.

   Everything the operator needs to judge the result is printed: whether the
   leading hypotheses agree, what the dimensions came out as, and which
   observations look mislabelled."
  (:require [cljs.reader :as reader]
            [clojure.set :as set]
            [clojure.string :as str]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.box-fit :as bf]
            [ridley.photogrammetry.turntable-fit :as tt]
            [ridley.photogrammetry.bootstrap :as boot]
            [ridley.photogrammetry.match :as match]))

(def fs (js/require "fs"))

;; Session constants — see test-assets/param-acq/NOTE.md
(def focal-px (* 4032 (/ 48.0 36.0)))       ; 48mm equivalent on 4032 px
(def intrinsics {:fx focal-px :fy focal-px :cx 2016.0 :cy 1512.0 :k1 0.0 :k2 0.0})
;; Fallback only — the real values come from the session's NOTE.md via
;; session.json. Hardcoding one session's part here is how the box session
;; would silently get measured against the SD reader's caliper.
(def default-caliper {:x 42.2 :y 15.0 :z 80.0})
(def caliper (atom default-caliper))
(def nominal-distance 280.0)
(def nominal-elevation 30.0)

(defn- fmt [x n] (.toFixed (js/Number. x) n))
(defn- pad [s w]
  (let [s (str s)] (str s (apply str (repeat (max 0 (- w (count s))) " ")))))
(defn- deg->rad [d] (/ (* d Math/PI) 180.0))

(defn- load-picks [path]
  (let [raw (reader/read-string (.readFileSync fs path "utf8"))
        photos (if (map? raw) (:photos raw) raw)]
    {:photos (vec photos)
     :picks (mapv :picks photos)
     :thetas (mapv #(deg->rad (:theta-deg %)) photos)}))

(defn- report-line [o]
  (str "    " (if (:suspect? o) "!! " "   ")
       "foto " (:view o) "  spigolo " (:edge o)
       " (" (name (boot/group-of (:edge o))) ")"
       "  residuo " (fmt (:residual-px o) 2) " px"))

(defn- synthetic-photos
  "Fabricate a session with known truth, so the whole CLI path can be
   exercised without waiting for real clicks."
  []
  (let [dims [(:x @caliper) (:y @caliper) (:z @caliper)]
        yaw 0.7
        axis (tt/axis-from-params 0.4 0.03 3.0 -2.0)
        el (deg->rad nominal-elevation)
        base (cam/look-at-pose [(* nominal-distance (Math/cos el)) 0.0
                                (* nominal-distance (Math/sin el))]
                               [0.0 0.0 0.0] [0.0 0.0 1.0])
        degs [0 -30 -60 -90]]
    (vec (for [d degs
               :let [pose (tt/pose-at-angle base axis (+ yaw (deg->rad d)))]]
           {:image (str "SYNTH_" d ".jpeg")
            :theta-deg d
            :picks (into {}
                         (for [g [:vertical :top :bottom]
                               :let [order (boot/predicted-order dims pose intrinsics g)]
                               :when (seq order)]
                           [g (mapv (fn [e]
                                      (let [{:keys [corners]} (bf/edge-geometry dims e)]
                                        {:p1 (cam/project intrinsics pose (first corners))
                                         :p2 (cam/project intrinsics pose (second corners))}))
                                    order)]))}))))

(declare run check init-session run-matched diagnose)

;; The angle sequence of the standard session: a dense arc for the bootstrap
;; plus four spread shots to widen the baseline. The four marked ★ are the
;; ones clicked by hand; the rest are confirmed from reprojected proposals.
(def session-thetas [0 -10 -20 -30 -40 -50 -60 -70 -80 -90 -130 180 90 45])
(def bootstrap-thetas #{0 -30 -60 -90})

(declare parse-note)

(defn- dir-of [path]
  (let [i (.lastIndexOf path "/")]
    (if (neg? i) "." (subs path 0 i))))

(defn- adopt-session!
  "Take the caliper from the session directory, so each session is measured
   against its OWN part. Reports which source won, because measuring the box
   against the SD reader's caliper would produce a plausible, wrong verdict."
  [dir]
  (let [sj (str dir "/session.json")
        from-json (when (.existsSync fs sj)
                    (some-> (js->clj (js/JSON.parse (.readFileSync fs sj "utf8"))
                                     :keywordize-keys true)
                            :caliper))
        from-note (:caliper (parse-note dir))
        c (or from-json from-note)]
    (if c
      (do (reset! caliper c)
          (println (str "  calibro (" (if from-json "session.json" "NOTE.md") "): X "
                        (:x c) "  Y " (:y c) "  Z " (:z c) " mm")))
      (println (str "  ⚠️  nessun calibro in " dir " — uso i default della sessione 1 ("
                    (:x @caliper) "/" (:y @caliper) "/" (:z @caliper)
                    "). Le quote sotto NON sono confrontabili col tuo pezzo.")))))

(defn ^:export main [& args]
  (cond
    (= "--selftest" (first args))
    (do (println "\n=== SELFTEST: sessione sintetica, verità nota ===")
        (println (str "  Quote vere: " (:x @caliper) " x " (:y @caliper)
                      " x " (:z @caliper) " mm"))
        (run (synthetic-photos) "/tmp/paq-selftest.json"))

    (= "--check" (first args))
    (do (adopt-session! (dir-of (second args)))
        (check (:photos (load-picks (second args)))))

    (= "--init-session" (first args))
    (init-session (second args))

    (= "--diagnose" (first args))
    (let [picks-path (nth args 1)
          target (nth args 2)
          dir (dir-of picks-path)]
      (adopt-session! dir)
      (diagnose (:photos (load-picks picks-path)) target))

    (= "--match" (first args))
    (let [picks-path (second args)
          dir (dir-of picks-path)]
      (adopt-session! dir)
      (run-matched (:photos (load-picks picks-path))
                   (or (nth args 2 nil) (str dir "/predictions.json"))))

    :else
    (let [deep? (some #{"--deep"} args)
          args (remove #{"--deep"} args)
          [picks-path out-path] args
          dir (dir-of picks-path)]
      (adopt-session! dir)
      (run (:photos (load-picks picks-path))
           (or out-path (str dir "/predictions.json"))
           deep?))))

;; ---------------------------------------------------------------------------
;; Checking the clicks without fitting
;;
;; The bootstrap can only run once several photos are in. Waiting until then
;; to discover that a photo has the wrong number of edges in a group wastes
;; the operator's afternoon, so the counts are checkable one photo at a time.

(def ^:private groups [:vertical :top :bottom])

(defn- nominal-base []
  (let [el (deg->rad nominal-elevation)]
    (cam/look-at-pose [(* nominal-distance (Math/cos el)) 0.0
                       (* nominal-distance (Math/sin el))]
                      [0.0 0.0 0.0] [0.0 0.0 1.0])))

(defn- counts-of [picks]
  (into {} (for [g groups] [g (count (get picks g []))])))

(defn- fmt-counts [c]
  (str/join " " (for [g groups] (str (name g) "×" (get c g 0)))))

(defn- photo-compatibility
  "Which hypotheses predict exactly the numbers of edges this photo has."
  [photo dims]
  (let [base (nominal-base)
        axis (tt/axis-from-params 0.0 0.0 0.0 0.0)
        theta (deg->rad (:theta-deg photo))
        clicked (counts-of (:picks photo))]
    (->> (boot/hypotheses 8)
         (keep (fn [{:keys [yaw sense] :as h}]
                 (let [pose (tt/pose-at-angle base axis (+ yaw (* sense theta)))
                       pred (into {} (for [g groups]
                                       [g (count (boot/predicted-order
                                                  dims pose intrinsics g))]))]
                   (when (every? (fn [g]
                                   (or (zero? (get clicked g 0))
                                       (= (get clicked g) (get pred g))))
                                 groups)
                     {:hyp h :pred pred}))))
         vec)))

(defn- check [photos]
  (let [dims [(:x @caliper) (:y @caliper) (:z @caliper)]]
    (println (str "\n=== Controllo click — " (count photos) " foto ===\n"))
    (doseq [p photos]
      (let [clicked (counts-of (:picks p))
            compat (photo-compatibility p dims)
            missing (remove #(pos? (get clicked % 0)) groups)]
        (println (str "  " (:image p) "  θ=" (:theta-deg p) "°"))
        (println (str "    cliccati : " (fmt-counts clicked)))
        ;; Hard geometric ceilings, independent of orientation: a box never
        ;; shows more than 3 of its 4 verticals (one is always occluded), and
        ;; a face contributes at most its 4 edges. Counts above these are
        ;; over-clicking — the actual failure in the confirmation pass, where
        ;; accepting a far-off proposal and then adding clicks left top×8.
        (let [over (keep (fn [[g cap]] (when (> (get clicked g 0) cap)
                                         (str (name g) " " (get clicked g) ">" cap)))
                         {:vertical 3 :top 4 :bottom 4})]
          (when (seq over)
            (println (str "    ✗ TROPPI spigoli (impossibile da qualunque angolo): "
                          (str/join ", " over)
                          "\n       svuota il gruppo e ri-accetta la proposta, poi ritocca"))))
        (if (seq missing)
          ;; The groups already clicked narrow the orientation down, so the
          ;; count for a missing group can be predicted instead of guessed at.
          ;; "manca bottom" is a fact; "manca bottom, dovrebbero essere 2" is
          ;; something the operator can act on without counting edges by eye.
          (let [preds (->> (photo-compatibility p dims)
                           (map (fn [c] (into {} (map (fn [g] [g (get (:pred c) g)]) missing))))
                           distinct)]
            (println (str "    ⚠️  gruppo mancante: "
                          (str/join ", " (map name missing))
                          "  — servono TUTTI E TRE (vedi PROTOCOLLO)"))
            (cond
              (empty? preds)
              (println "       (nessuna ipotesi compatibile: controlla anche i gruppi già cliccati)")
              (= 1 (count preds))
              (println (str "       da cliccare: "
                            (str/join ", " (map (fn [[g n]] (str (name g) "×" n))
                                                (first preds)))))
              :else
              (println (str "       da cliccare, secondo le "
                            (count preds) " orientazioni ancora possibili: "
                            (str/join " oppure "
                                      (map (fn [pr] (str/join ", "
                                                              (map (fn [[g n]] (str (name g) "×" n)) pr)))
                                           preds))))))
          (if (seq compat)
            (println (str "    ✓ conteggi compatibili con " (count compat)
                          "/16 ipotesi di orientamento"))
            (println (str "    ✗ NESSUNA ipotesi prevede questi conteggi."
                          "\n       Probabile: uno spigolo di troppo o mancante in un gruppo."
                          "\n       Attesi tipicamente: vertical×2-3, top×4, bottom×2-4."))))
        (println)))
    ;; Per-photo compatibility is necessary but NOT sufficient: the bootstrap
    ;; needs ONE orientation that explains every photo at once. Photos can each
    ;; look fine alone and still share no common hypothesis, in which case the
    ;; fit silently drops the ones that disagree — which reads as "foto usate
    ;; 2/4" long after the clicking is done.
    (let [compat-sets (mapv (fn [p]
                              (when (every? #(pos? (get (counts-of (:picks p)) % 0)) groups)
                                (set (map (fn [c] [(:yaw (:hyp c)) (:sense (:hyp c))])
                                          (photo-compatibility p dims)))))
                            photos)
          complete (keep-indexed (fn [i s] (when s i)) compat-sets)
          shared (when (seq complete)
                   (reduce set/intersection
                           (map #(nth compat-sets %) complete)))]
      (println (str "  Ipotesi compatibili con TUTTE le foto complete: "
                    (count shared)))
      (when (empty? shared)
        (println "  ✗ nessuna orientazione spiega tutte le foto insieme.")
        (println "    Il fit userà solo il sottoinsieme più numeroso e scarterà il resto.")
        (println "    Cause tipiche, in ordine:")
        (println "      · una foto quasi FRONTALE (2 verticali, 1 basso): è quasi")
        (println "        degenere, e quanti spigoli si vedano dipende da frazioni")
        (println "        di grado. Preferisci foto ad angolo generico (3 verticali).")
        (println "      · un conteggio sbagliato in un gruppo su una foto sola.")
        (doseq [[i s] (map-indexed vector compat-sets) :when s]
          (println (str "      foto " i " (" (:image (nth photos i)) "): "
                        (count s) " ipotesi")))))
    (let [ready (count (filter (fn [p] (every? #(pos? (get (counts-of (:picks p)) % 0))
                                               groups))
                               photos))]
      (println (str "  Foto complete (tutti e tre i gruppi): " ready))
      (cond
        (< ready 2) (println "  → il bootstrap ne richiede almeno 2, meglio 4. Continua a cliccare.")
        (< ready 4) (println "  → si può provare il fit, ma con 4 foto è molto più solido.")
        :else (println "  → pronto per il fit.")))))

(defn- parse-note
  "Read the session's NOTE.md as the single source of truth: the photo/angle
   table and the caliper dimensions.

   Deriving these from the operator's own notes beats hardcoding them here or
   inferring angles from filename order. The angle of a shot is a fact only
   the notes record, and a wrong one is INVISIBLE to every check downstream —
   the residual report cannot see it, because a consistent set of edges fitted
   at the wrong angle just moves the camera."
  [dir]
  (let [path (str dir "/NOTE.md")]
    (when (.existsSync fs path)
      (let [txt (.readFileSync fs path "utf8")
            rows (->> (.split txt "\n")
                      (keep (fn [l]
                              (when-let [m (re-find #"^\s*\|\s*([\w.\-]+\.(?:jpe?g|png))\s*\|\s*(-?\d+)\s*\|(.*)$" l)]
                                {:image (nth m 1)
                                 :theta-deg (js/parseInt (nth m 2) 10)
                                 :star? (boolean (re-find #"★" (nth m 3)))})))
                      vec)
            dim (fn [k]
                  (when-let [m (re-find (re-pattern (str "(?m)^\\s*-\\s*" k "\\s*:\\s*_*([0-9]+(?:\\.[0-9]+)?)"))
                                        txt)]
                    (js/parseFloat (nth m 1))))]
        {:photos rows
         :caliper (let [x (dim "X") y (dim "Y") z (dim "Z")]
                    (when (and x y z) {:x x :y y :z z}))}))))

(defn- init-session
  "Build session.json from the directory's NOTE.md, falling back to filename
   order paired with the standard angle sequence when there is no table."
  [dir]
  (let [note (parse-note dir)
        files (->> (.readdirSync fs dir)
                   (filter #(re-find #"(?i)\.(jpe?g|png)$" %))
                   sort vec)
        from-note (seq (:photos note))
        photos (if from-note
                 (vec (:photos note))
                 (mapv (fn [f th] {:image f :theta-deg th :star? (bootstrap-thetas th)})
                       files (take (count files) session-thetas)))]
    (println (str "\n=== Init sessione: " dir " ==="))
    (println (str "  immagini nella cartella : " (count files)))
    (println (str "  angoli presi da         : "
                  (if from-note "NOTE.md (tabella)" "sequenza standard + ordine dei nomi")))
    (when-not from-note
      (println "  ⚠️  senza tabella nel NOTE.md l'ordine dei nomi DEVE essere quello di"
               "\n      scatto: un file fuori posto sballa tutti gli angoli successivi,"
               "\n      ed è un errore che nessun controllo a valle può vedere."))
    (let [missing (remove (set files) (map :image photos))]
      (when (seq missing)
        (println (str "  ⚠️  citate nel NOTE ma assenti: " (str/join ", " missing)))))
    (if-let [c (:caliper note)]
      (println (str "  calibro                 : X " (:x c) "  Y " (:y c) "  Z " (:z c) " mm"))
      (println "  ⚠️  calibro non letto dal NOTE.md — il fit userà i valori di default"))
    (doseq [p photos]
      (println (str "    " (:image p) "  θ=" (:theta-deg p) "°" (when (:star? p) "  ★"))))
    (let [payload (clj->js {:dir dir
                            :photos (mapv (fn [p] [(:image p) (:theta-deg p)]) photos)
                            :bootstrap (vec (keep #(when (:star? %) (:image %)) photos))
                            :caliper (:caliper note)})]
      (.writeFileSync fs (str dir "/session.json") (js/JSON.stringify payload nil 1))
      (println (str "  scritto " dir "/session.json"))
      (println (str "  apri il tool con  ?s=" (last (str/split dir #"/")))))))

(defn- report-dims
  "Report the two measured dimensions against the caliper.

   Which horizontal dimension gets called X and which Y is a naming
   convention the photographs cannot settle: rotating the object frame by 90
   degrees swaps them and reprojects identically. So the two fitted values
   are matched to the two caliper values by whichever pairing is closer, and
   the swap is stated rather than silently absorbed — an unreported swap
   would look like two enormous errors."
  [fit]
  (let [d (:dims fit)
        [f0 f1] [(nth d 0) (nth d 1)]
        direct (+ (Math/abs (- f0 (:x @caliper))) (Math/abs (- f1 (:y @caliper))))
        swapped (+ (Math/abs (- f0 (:y @caliper))) (Math/abs (- f1 (:x @caliper))))
        swap? (< swapped direct)
        pairs (if swap?
                [[f0 (:y @caliper)] [f1 (:x @caliper)]]
                [[f0 (:x @caliper)] [f1 (:y @caliper)]])]
    (println "\n--- Quote ---")
    (when swap?
      (println "  (assi X/Y scambiati rispetto alla convenzione: irrilevante,"
               "\n   la foto non può decidere quale delle due si chiami X)"))
    (doseq [[got cal] pairs]
      (let [e (- got cal)]
        (println (str "  " (fmt got 3) " mm   vs calibro " cal
                      "   Δ " (if (pos? e) "+" "") (fmt e 3) " mm"
                      (if (< (Math/abs e) 0.2) "   ✓" "   ✗")))))
    (println (str "  " (fmt (nth d 2) 3) " mm   (Z, vincolo di scala)"))
    ;; Signature of the residual bias (Vincenzo, 2026-07-19): a silhouette
    ;; shifted inward removes a CONSTANT amount per side regardless of size;
    ;; an intrinsics/scale error is PROPORTIONAL. Printing both makes the two
    ;; distinguishable at a glance — constant-absolute points at the material
    ;; or a fillet, proportional at the principal point / focal.
    (let [rows (map (fn [[got cal]]
                      {:per-side (/ (Math/abs (- got cal)) 2.0)
                       :pct (* 100.0 (/ (- got cal) cal))})
                    pairs)]
      (println "  firma del bias:")
      (doseq [[r [_ cal]] (map vector rows pairs)]
        (println (str "    lato " cal " mm → "
                      (fmt (:per-side r) 3) " mm/lato  ·  "
                      (fmt (:pct r) 1) "%")))
      (println (str "    (per-lato ~costante → silhouette/materiale;"
                    " % ~costante → intrinseche)")))
    (when-let [f (:fillet fit)]
      (println (str "  raccordi stimati: "
                    (str/join "  " (map #(str (fmt % 2) " mm") f)))))))

(defn- run [photos out-path & [deep?]]
  (let [picks (mapv :picks photos)
        thetas (mapv #(deg->rad (:theta-deg %)) photos)
        dims [(:x @caliper) (:y @caliper) (:z @caliper)]
        base (let [el (deg->rad nominal-elevation)]
               (cam/look-at-pose [(* nominal-distance (Math/cos el)) 0.0
                                  (* nominal-distance (Math/sin el))]
                                 [0.0 0.0 0.0] [0.0 0.0 1.0]))
        opts {:picks picks :thetas thetas :intrinsics intrinsics :dims dims
              :base base :axis-params {:phi 0.0 :psi 0.0 :a 0.0 :b 0.0}
              :sigma-px 1.0
              ;; Z is pinned by the caliper; X and Y are the measurements
              :scale-constraint {:axis 2 :value (:z @caliper) :sigma 0.02}
              ;; The part is not centred on the plate (confirmed on this
              ;; session), and the offset is a poor thing to leave LM to find
              ;; on its own from zero. Seeded on a grid: coarse by default,
              ;; dense with --deep.
              :axis-offsets (if deep?
                              (vec (for [a [-45 -30 -15 0 15 30 45]
                                         b [-45 -30 -15 0 15 30 45]]
                                     [(double a) (double b)]))
                              [[0.0 0.0] [25.0 0.0] [-25.0 0.0]
                               [0.0 25.0] [0.0 -25.0]
                               [18.0 18.0] [-18.0 18.0]
                               [18.0 -18.0] [-18.0 -18.0]])
              :lm {:max-iterations (if deep? 60 200)}}]
    (println (str "\nFoto con click: " (count photos)))
    (doseq [p photos]
      (println (str "  " (:image p) "  θ=" (:theta-deg p) "°  "
                    (str/join ", " (map (fn [[g cs]] (str (name g) "×" (count cs)))
                                        (:picks p))))))

    (let [ranked (boot/bootstrap opts)]
      (if (empty? ranked)
        ;; Say WHICH of the two possible causes it is. Reporting a count
        ;; mismatch when the real problem is "only one photo" sends the
        ;; operator back to re-click work that was already correct.
        (let [complete (filter (fn [p] (every? #(pos? (count (get (:picks p) % [])))
                                               groups))
                               photos)]
          (println "\n--- Il bootstrap non è partito ---")
          (if (< (count complete) 2)
            (do (println (str "  Foto con tutti e tre i gruppi: " (count complete)
                              " — ne servono almeno 2, meglio 4."))
                (println "  I click fatti finora NON sono da rifare: mancano solo le altre foto.")
                (println "  Foto di bootstrap: IMG_8880, IMG_8883, IMG_8886, IMG_8889."))
            (do (println "  Ci sono abbastanza foto, quindi il problema è nei conteggi:")
                (println "  in qualche gruppo il numero di spigoli cliccati non corrisponde")
                (println "  a quelli visibili. Dettaglio per foto:")
                (check photos))))
        (let [best (first ranked)
              con (boot/consensus ranked)
              fit (:fit best)
              obs (:observations best)
              rep (boot/residual-report fit obs {:sigma-px 1.0})]
          (println (str "\n--- Bootstrap (" (count ranked) " ipotesi valutate) ---"))
          (println (str "  reproiezione       : " (fmt (:rms-px best) 2) " px"))
          (println (str "  foto usate         : " (:photos-used best) "/" (count photos)))
          (println (str "  ipotesi in testa   : " (:n-leading con)
                        "  concordi sulle quote: " (:agree? con)))
          (when-not (:agree? con)
            (println (str "  ⚠️  le ipotesi in testa NON concordano (scarto "
                          (pr-str (mapv #(fmt % 2) (:dim-spread con)))
                          " mm): etichettatura ambigua, servono più click")))
          (report-dims fit)

          ;; Refit with the fillet radii as parameters. The part is visibly
          ;; rounded (~1.7 mm measured off IMG_8880), and that is the dominant
          ;; systematic term; showing both fits side by side is the only way
          ;; to tell a labelling problem from a model problem.
          (let [fit2 (tt/fit obs
                             (mapv #(+ (:yaw (:hypothesis best))
                                       (* (:sense (:hypothesis best)) %))
                                   thetas)
                             intrinsics dims base
                             {:phi 0.0 :psi 0.0 :a 0.0 :b 0.0}
                             {:sigma-px 1.0
                              :scale-constraint (:scale-constraint opts)
                              :angle-prior-sigma-deg 1.0
                              :fillet-init [0.5 0.5 0.5]
                              :lm {:max-iterations 250}})]
            ;; Which assumption is actually costing us? Sweeping the two that
            ;; were fixed by fiat — how much the plate's angles are trusted,
            ;; and whether the edges are modelled as rounded — is cheaper than
            ;; arguing about it.
            (println "\n=== Diagnosi: quale ipotesi sta costando ===")
            (println (str "  " (pad "prior angoli" 14) (pad "raccordi" 10)
                          (pad "reproiez." 11) "quote (ordinate)"))
            (doseq [sig [1.0 5.0 20.0]
                    fil [nil [0.5 0.5 0.5]]]
              (let [r (tt/fit obs
                              (mapv #(+ (:yaw (:hypothesis best))
                                        (* (:sense (:hypothesis best)) %))
                                    thetas)
                              intrinsics dims base
                              {:phi 0.0 :psi 0.0 :a 0.0 :b 0.0}
                              (cond-> {:sigma-px 1.0
                                       :scale-constraint (:scale-constraint opts)
                                       :angle-prior-sigma-deg sig
                                       :lm {:max-iterations 250}}
                                fil (assoc :fillet-init fil)))
                    d (sort (take 2 (:dims r)))]
                (println (str "  " (pad (str sig "°") 14)
                              (pad (if fil "sì" "no") 10)
                              (pad (str (fmt (bf/rms-reprojection-px r obs {:sigma-px 1.0}) 2) " px") 11)
                              (str/join " · " (map #(fmt % 2) d))
                              (when fil (str "   r=" (str/join "/" (map #(fmt % 1) (:fillet r)))))))))
            (println (str "  (calibro, ordinato: "
                          (str/join " · " (map #(fmt % 2)
                                               (sort [(:x @caliper) (:y @caliper)])))
                          ")"))

            ;; Free poses: throw the turntable away entirely and give every
            ;; view its own six parameters. This separates the two remaining
            ;; suspects. If free poses fit and the turntable does not, the
            ;; angles or the axis are wrong. If free poses ALSO fail, no camera
            ;; placement can explain these observations and the LABELLING is
            ;; wrong — no amount of solver tuning will help.
            (let [hyp (:hypothesis best)
                  axis0 (tt/axis-from-params 0.0 0.0 (:a hyp 0.0) (:b hyp 0.0))
                  poses (mapv #(tt/pose-at-angle base axis0
                                                 (+ (:yaw hyp) (* (:sense hyp) %)))
                              thetas)
                  ks (vec (repeat (count poses) intrinsics))
                  fr (bf/fit obs ks dims poses
                             {:sigma-px 1.0
                              :scale-constraint (:scale-constraint opts)
                              :lm {:max-iterations 250}})
                  d (sort (take 2 (:dims fr)))]
              (println "\n=== Pose LIBERE (senza vincolo di giradischi) ===")
              (println (str "  reproiezione: "
                            (fmt (bf/rms-reprojection-px fr obs {:sigma-px 1.0}) 2)
                            " px   quote " (str/join " · " (map #(fmt % 2) d))))
              (println "  se anche così non fitta, il problema NON è il giradischi:")
              (println "  nessuna posizione di camera spiega questi spigoli → etichette."))

            ;; Leave-one-out. The per-observation alarm flags whatever stands
            ;; out ABOVE the median, so it goes blind when many observations
            ;; are bad at once — precisely the case where a whole photo is
            ;; wrong. Refitting without each photo in turn does not have that
            ;; weakness: if dropping one photo moves the answer a lot, that
            ;; photo disagrees with the others.
            (let [hyp-thetas (mapv #(+ (:yaw (:hypothesis best))
                                       (* (:sense (:hypothesis best)) %))
                                   thetas)
                  fit-opts {:sigma-px 1.0
                            :scale-constraint (:scale-constraint opts)
                            :angle-prior-sigma-deg 1.0
                            :lm {:max-iterations 250}}
                  axis0 {:phi 0.0 :psi 0.0 :a 0.0 :b 0.0}]

              ;; --- registration, measured apart from the dimensions --------
              (println "\n=== Qualità di registrazione ===")
              (let [marks (vec (mapcat (fn [i p]
                                         (map #(assoc % :view i) (:marks p)))
                                       (range) photos))]
                (if (empty? marks)
                  (println "  (nessuna tacca cliccata — la metrica assoluta richiede\n   i fiducial del bersaglio, vedi NOTE.md)")
                  (let [axis (tt/axis-from-params 0.0 0.0 0.0 0.0)
                        reg (boot/registration-report fit (:pose fit) axis
                                                      hyp-thetas intrinsics marks)]
                    (println (str "  tacche riproiettate: " (:n reg)
                                  "   RMS " (fmt (:rms-px reg) 2) " px"
                                  "   mediana " (fmt (:median-px reg) 2)
                                  "   max " (fmt (:max-px reg) 2) " px"))
                    (println "  (le tacche NON entrano nel fit: è una verifica indipendente)"))))

              ;; --- generalisation to an unseen view ------------------------
              (when (> (count (distinct (map :view obs))) 2)
                (println "\n=== Hold-out: come se la cava una foto MAI vista ===")
                (println (str "  " (pad "foto" 26) (pad "train" 11) (pad "hold-out" 11)
                              "quote del fit ridotto"))
                (doseq [h (boot/holdout-report obs hyp-thetas intrinsics dims base
                                               axis0 fit-opts)]
                  (when h
                    (println (str "  " (pad (str (:view h) " " (:image (nth photos (:view h)))) 26)
                                  (pad (str (fmt (:train-px h) 2) " px") 11)
                                  (pad (str (fmt (:holdout-px h) 2) " px") 11)
                                  (str/join " · " (map #(fmt % 2) (sort (take 2 (:dims h)))))))))
                (println "  hold-out ~ train  → la registrazione generalizza")
                (println "  hold-out >> train → sovradattamento, o quella foto è incoerente")))

            (println "\n=== Rifit con i raccordi come parametro ===")
            (println (str "  reproiezione: " (fmt (bf/rms-reprojection-px fit2 obs {:sigma-px 1.0}) 2)
                          " px  (contro " (fmt (:rms-px best) 2) " px a spigoli vivi)"))
            (report-dims fit2))

          (println (str "\n--- Residui per osservazione (mediana "
                        (fmt (:median-px rep) 2) " px, soglia allarme "
                        (fmt (:flag-threshold-px rep) 2) " px) ---"))
          (if (zero? (:n-suspect rep))
            (println "  nessun sospetto: le etichette sono coerenti fra loro")
            (println (str "  " (:n-suspect rep) " OSSERVAZIONI SOSPETTE (!!)")))
          (doseq [o (take 12 (:observations rep))]
            (println (report-line o)))

          ;; proposals for every photo, including the ones never clicked
          (let [axis (tt/axis-from-params (:phi (:axis-params fit) 0.0)
                                          (:psi (:axis-params fit) 0.0)
                                          (:a (:axis-params fit) 0.0)
                                          (:b (:axis-params fit) 0.0))
                hyp (:hypothesis best)
                all-thetas (mapv #(+ (:yaw hyp) (* (:sense hyp) (deg->rad (:theta-deg %))))
                                 photos)
                props (boot/reproject fit (:pose fit) axis all-thetas intrinsics)
                payload (clj->js
                         {:images (mapv :image photos)
                          :dims (:dims fit)
                          :rmsPx (:rms-px best)
                          :views (mapv (fn [p img]
                                         {:image img
                                          :edges (mapv (fn [e]
                                                         {:edge (:edge e)
                                                          :group (name (:group e))
                                                          :p1 (:p1 e) :p2 (:p2 e)})
                                                       (:edges p))})
                                       props (map :image photos))})]
            (.writeFileSync fs out-path (js/JSON.stringify payload nil 1))
            (println (str "\nProposte scritte in " out-path
                          " — caricale nel tool per confermarle."))))))))

(set! *main-cli-fn* main)

;; ---------------------------------------------------------------------------
;; The matched pipeline: correspondence per photo, then a joint fit
;;
;; Replaces the order-based bootstrap. Each photo is solved on its own from the
;; measured dimensions, so the click order is irrelevant and a photo can be
;; missing an edge without disqualifying itself. The joint fit then uses FREE
;; poses: each photo's labelling and pose are self-consistent even when two
;; photos land on different members of the box's symmetry group, because those
;; symmetries flip signs without ever permuting the axes — so every photo
;; constrains the same three dimensions the same way.

(defn- run-matched [photos out-path]
  (let [dims [(:x @caliper) (:y @caliper) (:z @caliper)]
        n (count photos)]
    (println (str "\n=== Corrispondenza per-foto (quote note: "
                  (str/join " × " (map #(fmt % 1) dims)) " mm) ==="))
    (let [solved (match/solve-session dims intrinsics photos {:sigma-px 1.0})
          ;; keep the ORIGINAL photo alongside each solve, then renumber the
          ;; observations to compact 0..k-1. The joint fit, hold-out and
          ;; consistency all index poses positionally; leaving the session's
          ;; photo index on the observations while compacting the pose vector
          ;; is what crashed the pipeline the moment a photo failed to solve.
          ok-pairs (vec (keep (fn [[p s]] (when s [p s]))
                              (map vector photos solved)))
          ok-photos (mapv first ok-pairs)
          ok (mapv second ok-pairs)]
      (doseq [[p s] (map vector photos solved)]
        (if s
          (println (str "  " (pad (:image p) 20) " θ=" (pad (str (:theta-deg p) "°") 7)
                        (pad (str (:matched s) " spigoli") 13)
                        "reproiez. " (fmt (:rms-px s) 2) " px"
                        "   az=" (fmt (match/azimuth-of (:pose s)) 1) "°"))
          (println (str "  " (pad (:image p) 20) " θ=" (pad (str (:theta-deg p) "°") 7)
                        "(non risolta: pochi spigoli coerenti)"))))
      (if (< (count ok) 2)
        (println "\n  Troppe poche foto risolte per un fit congiunto.")
        (let [;; the turntable is not used to solve anything; comparing the
              ;; recovered azimuths with the recorded angles is therefore a
              ;; real check on the session, not a restatement of an assumption
              cons (match/turntable-consistency ok)]
          (when cons
            (println (str "\n=== Controllo giradischi (indipendente) ==="))
            (println (str "  verso " (if (pos? (:sense cons)) "+" "-")
                          "   scarto max " (fmt (:max-err-deg cons) 2) "°"))
            (doseq [r (:rows cons)]
              (println (str "    " (pad (:image r) 20) " θ=" (pad (str (:theta r) "°") 7)
                            "err " (fmt (:err-deg r) 2) "°"))))

          (let [;; compact views 0..k-1, aligned with `poses` and `ok-photos`
                obs (vec (mapcat (fn [i s] (map #(assoc % :view i) (:obs s)))
                                 (range) ok))
                poses (mapv :pose ok)
                ks (vec (repeat (count poses) intrinsics))
                fit (bf/fit obs ks dims poses
                            {:sigma-px 1.0
                             :scale-constraint {:axis 2 :value (:z @caliper) :sigma 0.02}
                             :lm {:max-iterations 250}})]
            (println "\n=== GATE 1 — quote ===")
            ;; The reprojection of THIS fit is ~0 and meaningless: with only Z
            ;; pinned, the free X and Y simply shrink until the wireframe sits
            ;; on the clicks, absorbing the silhouette bias into the dimensions
            ;; (which is exactly what makes them the measurement). The honest
            ;; model-fit number is the per-photo reprojection, where all three
            ;; dimensions were held at the caliper values.
            (println (str "  reproiez. a quote di calibro (modello a spigoli vivi): "
                          (fmt (/ (reduce + 0.0 (map :rms-px ok)) (count ok)) 2)
                          " px media per-foto"))
            (report-dims fit)

            (println "\n=== GATE 2 — registrazione ===")
            (let [holdouts (atom [])
                  views (range (count ok))]
              (println (str "  " (pad "foto" 22) (pad "per-foto" 11) "hold-out"))
              (doseq [v views]
                (let [others (remove #{v} views)
                      remap (into {} (map-indexed (fn [i vv] [vv i]) others))
                      train (mapv #(update % :view remap)
                                  (remove #(= v (:view %)) obs))
                      test (mapv #(assoc % :view 0) (filter #(= v (:view %)) obs))
                      keep-poses (mapv #(nth poses %) others)
                      r (bf/fit train (vec (repeat (count keep-poses) intrinsics))
                                dims keep-poses
                                {:sigma-px 1.0
                                 :scale-constraint {:axis 2 :value (:z @caliper) :sigma 0.02}
                                 :lm {:max-iterations 150}})
                      ;; score the held-out photo at its own solved pose with
                      ;; the dimensions the OTHER photos agreed on
                      rf (bf/make-residual-fn test [intrinsics] 1 {:sigma-px 1.0})
                      pv (bf/pack (:dims r) [(nth poses v)])
                      rs (rf pv)
                      px (Math/sqrt (/ (reduce + 0.0 (map #(* % %) rs)) (max 1 (count rs))))]
                  (println (str "  " (pad (:image (nth ok-photos v)) 22)
                                (pad (str (fmt (:rms-px (nth ok v)) 2) " px") 11)
                                (fmt px 2) " px"))
                  (swap! holdouts conj px)))
              ;; Verdict from the numbers. The baseline is the per-photo
              ;; at-caliper reprojection, NOT the reduced fit's training
              ;; residual: that one is ~0 because its free X/Y absorb the error,
              ;; which would make any ratio meaningless.
              (let [base (/ (reduce + 0.0 (map :rms-px ok)) (count ok))
                    hs (keep identity @holdouts)
                    ho (when (seq hs) (/ (reduce + 0.0 hs) (count hs)))]
                (when ho
                  (println (str "  media: baseline per-foto " (fmt base 2)
                                " px, hold-out " (fmt ho 2) " px  ("
                                (fmt (/ ho (max 1e-9 base)) 1) "x)"))
                  (println (if (< ho (* 2.0 base))
                             "  → la registrazione generalizza alle viste non usate"
                             "  → hold-out ben sopra il baseline: le camere NON generalizzano ancora")))))

            ;; --- consistency points: triangulate each marked point with the
            ;; per-photo poses and see whether the views agree. Independent of
            ;; the dimensions, so it measures registration and nothing else.
            (let [image->pose (into {} (map (fn [s] [(:image s) (:pose s)]) ok))
                  marks (vec (for [p photos
                                   m (:marks p)
                                   :let [pose (get image->pose (:image p))]
                                   :when pose]
                               (assoc m :pose pose)))
                  by-id (group-by :id marks)
                  ready (filter (fn [[_ ms]] (> (count ms) 1)) by-id)]
              (let [dropped (- (reduce + 0 (map #(count (:marks %)) photos)) (count marks))]
                (println "\n  punti di consistenza:")
                (when (pos? dropped)
                  (println (str "    (" dropped " click su foto non risolte, ignorati)"))))
              (if (empty? ready)
                (println (str "    " (count marks) " click usabili, nessun punto ancora in ≥2 viste"
                              " — servono per la metrica (vedi NOTE.md)"))
                (let [errs (for [[_id ms] ready
                                 :let [rays (mapv #(boot/pixel-ray intrinsics (:pose %) (:px %))
                                                  ms)
                                       p3 (boot/triangulate rays)]
                                 :when p3
                                 m ms
                                 :let [pp (cam/project intrinsics (:pose m) p3)]
                                 :when pp]
                             (Math/sqrt (+ (Math/pow (- (first pp) (first (:px m))) 2)
                                           (Math/pow (- (second pp) (second (:px m))) 2))))
                      rms (Math/sqrt (/ (reduce + 0.0 (map #(* % %) errs)) (max 1 (count errs))))]
                  (println (str "    " (count ready) " punti in ≥2 viste, "
                                "consistenza multi-vista RMS " (fmt rms 2) " px"))
                  (println "    (le camere concordano su dove sta ogni punto: è il")
                  (println "     numero che certifica il 'registratore' anche a quote storte)"))))

            ;; --- predictions for EVERY photo of the session, so the 10 that
            ;; were never clicked arrive pre-drawn and only need confirming.
            (let [dir (dir-of out-path)
                  sj (str dir "/session.json")
                  session (when (.existsSync fs sj)
                            (js->clj (js/JSON.parse (.readFileSync fs sj "utf8"))
                                     :keywordize-keys true))
                  all-photos (if session
                               (mapv (fn [[img th]] {:image img :theta-deg th})
                                     (:photos session))
                               (mapv #(select-keys % [:image :theta-deg]) photos))
                  ;; build the turntable only from the cleanly-solved photos,
                  ;; not the over-clicked ones — a bad photo dragged the fit to
                  ;; ~17px and poisoned the proposals it then produces
                  tt (match/fit-turntable dims intrinsics ok-photos {:sigma-px 1.0})]
              (if (nil? tt)
                (do (println "\n  ⚠️  turntable non ricostruibile: niente predictions estese")
                    (.writeFileSync fs out-path
                                    (js/JSON.stringify
                                     (clj->js {:dims (:dims fit) :views []}) nil 1)))
                (let [proj (match/reproject-turntable intrinsics tt all-photos)
                      solved (set (map :image ok-photos))]
                  (println (str "\n=== Predictions su tutte le " (count all-photos) " foto ==="))
                  (println (str "  turntable dai " (:used tt) " scatti risolti puliti (reproiez. "
                                (fmt (:rms tt) 2) " px)"))
                  (println (str "  → " (- (count all-photos) (count solved))
                                " foto hanno una proposta da confermare o correggere"))
                  (.writeFileSync
                   fs out-path
                   (js/JSON.stringify
                    (clj->js {:dims (:dims fit)
                              :rmsPx (bf/rms-reprojection-px fit obs {:sigma-px 1.0})
                              :views (mapv (fn [v]
                                             {:image (:image v)
                                              :clicked (contains? solved (:image v))
                                              :edges (mapv (fn [e]
                                                             {:edge (:edge e) :group (:group e)
                                                              :p1 (:p1 e) :p2 (:p2 e)})
                                                           (:edges v))})
                                           proj)})
                    nil 1))
                  (println (str "  scritto " out-path
                                " — caricalo nel tool e conferma le proposte")))))))))))

;; ---------------------------------------------------------------------------
;; Diagnose one photo: is it the clicks or the search?
;;
;; "non risolta" conflated two different failures. This separates them, and
;; cheaply: the target's pose is seeded from its Klein TWIN (the photo at
;; theta +/- 180, which views the same box from the opposite side), one solve
;; instead of a whole-session fit. The seeded pose is refined against the
;; target's clicks, bypassing the coarse sweep.
;;   - clicks fit the seeded pose but the search finds nothing -> search bug
;;     (chirality / sweep), and the photo is a regression test;
;;   - one line stays huge while the rest fall in -> that line is an intruder
;;     click, and its group/index says where Vincenzo should look.

(defn- twin-of [photos theta]
  (let [want (- (mod (+ theta 180 180) 360) 180)]
    (first (filter #(= want (:theta-deg %)) photos))))

(defn- per-line-report [dims pose picks rms matched]
  (let [res (match/nearest-edge-residuals dims intrinsics pose picks)
        rs (sort (map :residual-px res))
        median (nth rs (quot (count rs) 2))
        ;; An intruder is a DRAMATIC outlier — a line matched to no real edge,
        ;; tens of pixels off while the rest sit near the noise floor. A line a
        ;; couple of pixels above a photo whose whole fit is ~6px is just its
        ;; noisiest edge (the bottom ones, at the plate junction, always are).
        ;; So flag only residuals both far above the median AND large absolute.
        limit (max 15.0 (* 4.0 median))
        bad (filter #(>= (:residual-px %) limit) res)]
    (println (str "      posa trovata, reproiez. " (fmt rms 2)
                  " px su " matched " spigoli (mediana per-linea "
                  (fmt median 1) " px, soglia intruso " (fmt limit 1) " px)"))
    (println "      residuo per-linea (spigolo piu vicino, dal peggiore):")
    (doseq [r res]
      (println (str "        " (if (>= (:residual-px r) limit) "!! " "   ")
                    (pad (name (:group r)) 9) "#" (:index r)
                    " -> spigolo " (pad (str (:edge r)) 3)
                    "  " (fmt (:residual-px r) 2) " px")))
    {:bad bad :median median :limit limit}))

(defn- diagnose [photos target-image]
  (let [dims [(:x @caliper) (:y @caliper) (:z @caliper)]
        target (first (filter #(= target-image (:image %)) photos))]
    (if-not target
      (println (str "\n  " target-image " non e nel picks.edn"))
      (let [theta (:theta-deg target)
            twin (twin-of photos theta)]
        (println (str "\n=== Diagnosi " target-image "  theta=" theta "deg ==="))
        ;; (1) does the search find the target on its own? Sweep the seed
        ;; budget: if more seeds fixes it, the bug is coverage (the true pose's
        ;; coarse neighbour was out-ranked by a cheap-but-wrong assignment and
        ;; dropped before refinement).
        (println "  (1) ricerca sulla foto da sola, al variare dei semi tenuti:")
        (doseq [ns [6 20 40 80]]
          (let [s (match/solve-photo dims intrinsics (:picks target) 0
                                     {:sigma-px 1.0 :n-seeds ns})]
            (println (str "      n-seeds " (pad (str ns) 4) "→ "
                          (if s (str (fmt (:rms-px s) 2) " px") "nulla")))))
        (let [self (match/solve-photo dims intrinsics (:picks target) 0 {:sigma-px 1.0})]
          ;; (2) seed from the Klein twin
          (if-not twin
            (println (str "  (2) nessuna gemella a theta=" (- (mod (+ theta 180 180) 360) 180)
                          "deg per il seme"))
            (let [tw (match/solve-photo dims intrinsics (:picks twin) 0 {:sigma-px 1.0})]
              (println (str "  gemella " (:image twin) " (theta=" (:theta-deg twin) "deg): "
                            (if tw (str "risolta a " (fmt (:rms-px tw) 2) " px")
                                "non risolta — non posso usarla come seme")))
              (when tw
                (let [seed (match/twin-seed-pose (:pose tw))
                      seeded (match/refine-from-pose dims intrinsics (:picks target) seed
                                                     {:sigma-px 1.0})]
                  (println (str "  (2) posa seminata dalla gemella, poi rifinita:"))
                  (if-not seeded
                    (println (str "      i conteggi per gruppo non combaciano con la posa"
                                  " seminata\n      (un gruppo ha piu click di spigoli visibili la)"))
                    (let [rep (per-line-report dims (:pose seeded) (:picks target)
                                               (:rms-px seeded) (:matched seeded))
                          bad (:bad rep)]
                      (println)
                      (cond
                        (seq bad)
                        (do (println (str "  VERDETTO: " (count bad) " linea/e resta/no fuori (> "
                                          (fmt (:limit rep) 0)
                                          " px) anche con la posa giusta -> intruso:"))
                            (doseq [r bad]
                              (println (str "    - gruppo " (name (:group r)) ", click #" (:index r)
                                            " (" (fmt (:residual-px r) 1) " px dal piu vicino)"))))
                        (nil? self)
                        (do (println "  VERDETTO: i click sono BUONI (nessun intruso, la posa")
                            (println "  seminata li spiega) ma la ricerca da sola NON la trova")
                            (println "  -> bug di ricerca. Regressione da fissare."))
                        :else
                        (do (println (str "  VERDETTO: nessun intruso e la ricerca la trova ora"
                                          " (" (fmt (:rms-px self) 2) " px)."))
                            (println (str "  I click sono a posto; " (fmt (:rms-px seeded) 2)
                                          " px e' il rumore di questa foto (gli spigoli bassi,"
                                          " al bordo col piatto, sono i piu difficili da cliccare).")))))))))))))))
