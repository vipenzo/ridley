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
            [ridley.photogrammetry.ellipse :as ellipse]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.match-cage :as mc]
            [ridley.photogrammetry.match-plate :as mp]
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
        intr (cam/intrinsics-from-fov (cam/equiv-focal->hfov-deg 48.0 (/ w h)) w h)]
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

;; ── contamination: the identity must digest an intruder (zero-click lever 1) ─

(defn- visible-face
  "The face of ring `axis` whose marked side is toward the camera under `pose`."
  [targets pose axis]
  (let [c (cam/camera-center pose)]
    (first (filter #(and (= axis (:axis %))
                         (pos? (la/v-dot (:face-normal %) (la/v-sub c (:zero-obj %)))))
                   (mc/ring-faces targets)))))

(defn- contaminated-ring
  "One ring's contaminated hypothesis under `pose`: 8 true discs of ring `axis`
   (positions with gaps) + 2 riders ON the ring's own image ellipse at
   half-step anomalies. Returns {:face :pts} — pts 0-7 true, 8-9 riders."
  [targets intr pose axis]
  (let [face (visible-face targets pose axis)
        ring-px (mapv (fn [i] (cam/project intr pose (:obj (nth (:marks face) i))))
                      [0 1 2 3 4 6 8 9])
        rider-px (mapv (fn [half]
                         (cam/project intr pose
                                      (cage/turn-about-axis axis
                                                            (:obj (nth (:marks face) 0))
                                                            (* half 30.0))))
                       [5.5 10.5])]
    {:face face :pts (vec (concat ring-px rider-px))}))

(deftest a-rider-on-the-ellipse-poisons-the-free-search-not-the-comb
  ;; Foto 3/6/7's disease, synthesized: a ring hypothesis of 8 true discs (with
  ;; gaps) + 2 riders ON the ring's own image ellipse (points of the cage that
  ;; ride the conic at half-step anomalies — the audited shape of the
  ;; contaminated supersets). assign-marks must explain EVERY click as a mark,
  ;; so the free search hands the riders to the homography and least squares
  ;; spreads the damage (PREMISE, asserted). The comb's teeth leave the riders
  ;; off the correspondences entirely, and the same identity closes on the
  ;; truth — at 24 candidates instead of C(12,10)·10·2.
  ;;
  ;; Framed like the bench frames it (~640mm from the cage). From the CLOSE
  ;; test eye (320mm — ring X seen from 150mm off its own plane) the comb's
  ;; affine argument itself breaks: perspective drifts the anomaly GAPS past a
  ;; quarter tooth. That limit is printed, not asserted — auto-read survives
  ;; it because the baseline enumeration still runs beside the teeth.
  (println "\n=== gabbia: l'intruso sull'ellisse avvelena la ricerca libera, non il pettine ===")
  (let [{:keys [targets intr pose]} (setup (mapv #(* 2.0 %) eye))
        cands (scene nil targets intr pose)
        judge (fn [px _r] (boolean (some (fn [[u v]]
                                           (< (Math/hypot (- u (first px)) (- v (second px))) 4.0))
                                         cands)))
        {:keys [face pts]} (contaminated-ring targets intr pose :x)
        base {:disc-r 1.25 :face-normal (:face-normal face)}
        free (mp/assign-marks pts (:marks face) (:zero-obj face) intr judge base)
        ct (ellipse/comb-teeth pts (vec (range (count pts))) 12)
        ids (vec (sort (keys (:teeth ct))))
        combed (when ct
                 (mp/assign-marks (mapv pts ids) (:marks face) (:zero-obj face) intr judge
                                  (assoc base :teeth (mapv (:teeth ct) ids))))]
    (println (str "  ricerca libera sui 10 punti: "
                  (if free (str "corona " (:crown-hits free) " · zero " (:zero-hit? free))
                      "niente")))
    (println (str "  pettine: " (when ct (str (count (:teeth ct)) " denti, intrusi "
                                              (pr-str (:riders ct))))
                  " · identità coi denti: "
                  (when combed (str "corona " (:crown-hits combed)
                                    " · zero " (:zero-hit? combed)))))
    (is (not (and free (:zero-hit? free) (>= (:crown-hits free) 8)))
        "PREMESSA: con gli intrusi dentro, la ricerca libera non arriva all'asticella")
    (is (some? ct) "il pettine legge l'ipotesi contaminata")
    (when ct
      (is (= 8 (count (:teeth ct))) "otto denti: i punti veri")
      (is (= #{8 9} (set (:riders ct))) "e i due intrusi in panchina"))
    (is (and combed (:zero-hit? combed) (>= (:crown-hits combed) 8))
        "coi denti l'identità chiude sopra l'asticella")
    (when combed
      (let [d (la/v-norm (la/v-sub (cam/camera-center (:pose combed))
                                   (cam/camera-center pose)))]
        (println (str "  camera a " (.toFixed d 2) "mm dalla verità"))
        (is (< d 5.0) (str "e la posa è quella vera (" (.toFixed d 1) "mm)"))))
    ;; the measured limit, on the record: the same ring from the close eye
    (let [{:keys [pose intr targets]} (setup eye)
          {:keys [pts]} (contaminated-ring targets intr pose :x)
          close (ellipse/comb-teeth pts (vec (range (count pts))) 12)]
      (println (str "  dall'occhio ravvicinato (320mm, anello X a 150mm dal suo piano): "
                    (if (and close (= 8 (count (:teeth close)))
                             (= #{8 9} (set (:riders close))))
                      "il pettine REGGE — il limite è caduto, promuovi ad asserzione"
                      (str "il pettine sbanda ("
                           (when close (str (count (:teeth close)) " denti, intrusi "
                                            (pr-str (:riders close))))
                           ") — limite misurato, il fallback enumerativo lo copre")))))))

(deftest auto-read-digests-contaminated-hypotheses
  ;; End-to-end WITH `:teeth?` on (the lever-1 mechanism — gated off in
  ;; production until the twin arbiter exists, see auto-read): every visible
  ;; ring's ellipse carries two riders (so every hypothesis the search
  ;; surfaces is a contaminated superset — the bench's real shape), plus
  ;; scattered junk. Zero clicks. The read must still land on the truth: the
  ;; comb benches the riders before the identity ever sees them.
  (println "\n=== gabbia: lettura senza click su ipotesi contaminate ===")
  (let [{:keys [targets intr pose]} (setup eye)
        base (scene nil targets intr pose)
        riders (vec (for [axis [:x :y :z]
                          :let [face (visible-face targets pose axis)]
                          :when face
                          half [5.5 8.5]
                          :let [px (cam/project intr pose
                                                (cage/turn-about-axis axis
                                                                      (:obj (nth (:marks face) 0))
                                                                      (* half 30.0)))]
                          :when px]
                      px))
        junk [[300.0 300.0] [2500.0 3600.0] [500.0 3500.0] [1600.0 400.0]]
        cands (vec (concat base riders junk))
        judge (fn [px _r] (boolean (some (fn [[u v]]
                                           (< (Math/hypot (- u (first px)) (- v (second px))) 4.0))
                                         cands)))
        rr (mc/auto-read cands targets intr judge marks {:disc-r 1.25 :teeth? true})]
    (println (str "  " (count base) " candidati veri + " (count riders) " intrusi sulle ellissi + "
                  (count junk) " spazzatura · "
                  (if rr (str "seme " (name (:axis (:seed rr)))
                              " · spiega " (:explained rr)
                              " · rms " (.toFixed (:rms-px rr) 2))
                      "RIFIUTATA")))
    (is (some? rr) "la gabbia contaminata si legge lo stesso")
    (when rr
      (let [d (la/v-norm (la/v-sub (cam/camera-center (:pose rr)) (cam/camera-center pose)))]
        (println (str "  camera a " (.toFixed d 2) "mm dalla verità"))
        (is (< d 5.0) (str "e la posa è quella vera (" (.toFixed d 1) "mm)"))
        (is (pos? (:off-ring rr)) "confermata anche fuori dall'anello del seme")))))

;; ── the mounting arbiter (zero-click lever 2) ───────────────────────────────
;;
;; The cage's disc constellation is invariant under reflection through any
;; ring's plane and under 180° turns about any axis; only the six zero-indices
;; are chiral. And on the REAL cage the mounting is per-assembly (rings turn in
;; whole steps and — until the anti-flip key is printed — mount flipped), so a
;; single photograph cannot tell the twin from the truth: the SESSION can,
;; because one session is one mounting and every true pose reads each ring's
;; index at the same pose-absolute (sense, slot). All measured on battiscopa
;; (2026-08-30) before these tests were written.

(defn- flipped-y-targets
  "The truth of a cage whose Y ring is mounted FLIPPED with no extra turn: the
   nominal model with the Y indices moved to the mirror slot (a third of a
   step BEFORE mark 0 instead of after — 2·10° back). Crown discs do not move:
   flips and whole-step turns are invisible on them, which is the disease."
  [targets]
  (mapv (fn [{:keys [id obj] :as t}]
          (if (#{:zero-yp :zero-ym} id)
            (assoc t :obj (cage/turn-about-axis :y obj -20.0))
            t))
        targets))

(deftest the-index-witness-reads-the-mounting
  (println "\n=== gabbia: il testimone legge il montaggio, non il modello ===")
  (let [{:keys [targets intr pose]} (setup eye)
        truth (flipped-y-targets targets)
        cands (scene nil truth intr pose)
        {:keys [obs]} (mc/index-witness targets cands intr pose marks {})
        by-axis (into {} (map (juxt :axis identity) obs))]
    (println (str "  osservazioni: " (pr-str (mapv (juxt :axis :sense :k) obs))))
    (is (= [:rev 0] ((juxt :sense :k) (by-axis :y)))
        "l'anello ribaltato si legge nel senso specchiato, slot 0")
    (is (= [:fwd 0] ((juxt :sense :k) (by-axis :x)))
        "l'anello nominale si legge dritto")
    (is (< (:d (by-axis :y)) 2.0) "e l'osservazione è nitida, non un caso")))

(deftest the-index-witness-folds-to-one-reading-per-ring
  ;; Photo 2 of battiscopa3 (2026-09-02), reproduced: a HEALTHY pose whose X
  ;; ring read its own nominal at 1.4px — zero-xp itself — while a stray
  ;; candidate (no mark within 30px: reflection, stick) sat 7.9px from the
  ;; rev-k2 slot of the same grid. The stray became a second ballot for the
  ;; ring: the session's vote counted junk against an honest reading and the
  ;; ATTENZIONE fired against a ring that had just read correctly. One face
  ;; has ONE index disc — the witness reports the ring's sharpest hit and
  ;; leaves the rest to :faces, which is the bench's business, not the vote's.
  (println "\n=== gabbia: un anello, una lettura — la spazzatura non vota ===")
  (let [{:keys [targets intr pose]} (setup eye)
        zero-x (:obj (first (filter #(= :zero-xp (:id %)) targets)))
        ;; a junk candidate 4px from X's rev-k2 slot, projected exactly the
        ;; way the witness projects it (delta = +10° → rev slots at −20°+k·30°)
        junk-px (mapv + (cam/project intr pose (cage/turn-about-axis :x zero-x 40.0))
                      [4.0 0.0])
        cands (conj (scene nil targets intr pose) junk-px)
        {:keys [obs faces]} (mc/index-witness targets cands intr pose marks {})
        xs (filterv #(= :x (:axis %)) obs)
        x-face (first (filter #(= :x (:axis %)) faces))]
    (println (str "  osservazioni X: " (pr-str (mapv (juxt :sense :k :d) xs))))
    (is (= 1 (count xs)) "una lettura sola per l'anello")
    (is (= [:fwd 0] ((juxt :sense :k) (first xs)))
        "ed è quella nitida dell'indice vero, non lo slot sfiorato dalla spazzatura")
    (is (number? (:zero-d (first xs))) "porta la distanza del posto nominale")
    (is (< (:zero-d (first xs)) 2.0) "che qui è occupato dall'indice stesso")
    (is (= 2 (count (:hits x-face)))
        "il rapporto completo resta in :faces, per il banco")))

;; ── the EYE seed (gizmo pose as hypothesis gate) ─────────────────────────────

(deftest eye-compatible-splits-hand-error-from-twins
  ;; The gate's whole claim in two numbers: a hand alignment is off by tens of
  ;; mm, a twin is off by hundreds (325-764mm measured on the bench). The
  ;; through-plastic twin of an oblique view puts the camera mirrored through
  ;; the ring's plane — for the Y ring under `eye`, at [150 210 190] — which
  ;; is 420mm from the truth at a 320mm working range: no hand is that sloppy.
  (println "\n=== gabbia: l'occhio distingue la mano dal gemello ===")
  (let [truth (cam/look-at-pose eye [0.0 0.0 0.0] [0.0 0.0 1.0])
        hand (cam/look-at-pose [190.0 -180.0 230.0] [0.0 0.0 0.0] [0.0 0.0 1.0])
        twin (cam/look-at-pose [150.0 210.0 190.0] [0.0 0.0 0.0] [0.0 0.0 1.0])]
    (is (mc/eye-compatible? hand truth)
        "una posa a mano grossolana (≈65mm) non uccide la verità")
    (is (not (mc/eye-compatible? hand twin))
        "il gemello specchiato attraverso il piano dell'anello muore")))

(deftest the-eye-gates-the-hypotheses-without-killing-the-truth
  ;; auto-read with the user's coarse alignment: the three faces the eye
  ;; plainly sees the back of are never attempted (the through-plastic twin
  ;; dies before the solve, and the identity budget stops paying for it), and
  ;; the reading that survives is the true one — the gate must never eat the
  ;; truth it exists to protect.
  (println "\n=== gabbia: il seme dell'occhio — gate, non giudice ===")
  (let [{:keys [targets intr pose]} (setup eye)
        cands (scene nil targets intr pose)
        judge (fn [px _r] (boolean (some (fn [[u v]]
                                           (< (Math/hypot (- u (first px))
                                                          (- v (second px))) 4.0))
                                         cands)))
        hand (cam/look-at-pose [190.0 -180.0 230.0] [0.0 0.0 0.0] [0.0 0.0 1.0])
        tr (atom [])
        rr (mc/auto-read cands targets intr judge marks
                         {:disc-r 1.25 :trace tr :eye-pose hand})
        hidden (some :eye-hidden-faces @tr)]
    (println (str "  facce nascoste dall'occhio: " (pr-str hidden)
                  " · " (if rr (str "registra, rms " (.toFixed (:rms-px rr) 2)) "RIFIUTATA")))
    (is (= #{[:x -1] [:y 1] [:z -1]} (set hidden))
        "le tre facce di spalle all'occhio non si tentano nemmeno")
    (is (some? rr) "e la lettura vera passa il gate")
    (when rr
      (let [d (la/v-norm (la/v-sub (cam/camera-center (:pose rr))
                                   (cam/camera-center pose)))]
        (is (< d 5.0) (str "camera a " (.toFixed d 1) "mm dalla verità"))))))

(deftest a-covered-index-still-testifies-but-confesses-the-empty-nominal
  ;; The Z of that same photo: its true index was NOT among the detected discs
  ;; (stick, glare) and the only thing near its slot grid was junk. The lone
  ;; reading STANDS — the vote across photos is the judge, and silencing it
  ;; would blind the twin arbiter — but it carries :zero-d, so the message can
  ;; say 'and the nominal slot is EMPTY: weigh the covered-index suspect'
  ;; instead of asserting a twin on a stray's word alone.
  (println "\n=== gabbia: indice coperto — la lettura resta ma confessa ===")
  (let [{:keys [targets intr pose]} (setup eye)
        zero-x (:obj (first (filter #(= :zero-xp (:id %)) targets)))
        junk-px (mapv + (cam/project intr pose (cage/turn-about-axis :x zero-x 40.0))
                      [4.0 0.0])
        ;; the same scene with X's index gone from the detections
        cands (conj (scene nil (vec (remove #(= :zero-xp (:id %)) targets)) intr pose)
                    junk-px)
        {:keys [obs]} (mc/index-witness targets cands intr pose marks {})
        x-obs (first (filter #(= :x (:axis %)) obs))]
    (println (str "  lettura X: " (pr-str ((juxt :sense :k) x-obs))
                  " · posto nominale a " (some-> (:zero-d x-obs) (.toFixed 0)) "px"))
    (is (some? x-obs) "la spazzatura testimonia — arbitrarla è compito del voto")
    (is (= [:rev 2] ((juxt :sense :k) x-obs)))
    (is (> (:zero-d x-obs) mc/index-obs-px)
        "ma confessa che al posto nominale non c'è nessun dischetto")))

(deftest vote-mounting-majority-beats-sharpness
  ;; foto 1's poisoned hand pose was sharper by 0.05px than four honest ones —
  ;; keep-the-sharpest inverted the whole session. The vote must not.
  (let [obs-seq [[{:axis :y :sense :fwd :k 0 :d 2.0}]   ; the twin, sharpest
                 [{:axis :y :sense :rev :k 11 :d 4.0}]
                 [{:axis :y :sense :rev :k 11 :d 8.0}]
                 [{:axis :y :sense :rev :k 11 :d 11.0}]
                 [{:axis :z :sense :fwd :k 3 :d 5.0}
                  {:axis :x :sense :fwd :k 0 :d 6.0}]
                 [{:axis :z :sense :fwd :k 9 :d 5.0}]]  ; z: dead even, 1-1
        m (mc/vote-mounting obs-seq)]
    (println (str "\n=== gabbia: il voto del montaggio ===\n  " (pr-str m)))
    (is (= {:sense :rev :k 11} (select-keys (:y m) [:sense :k]))
        "la maggioranza batte la nitidezza")
    (is (:contested? (:y m)) "e il dissenso resta scritto: c'è un gemello nella sessione")
    (is (= 3 (:votes (:y m))))
    (is (nil? (:z m)) "un anello in parità non decide niente")
    (is (false? (:contested? (:x m))) "un anello unanime non è conteso")))

(deftest the-session-mounting-convicts-the-machine-twin
  ;; End-to-end, the foto 6/7 mechanism reproduced: the Y ring is mounted
  ;; flipped, so the TRUE reading's model zero lands on empty plastic (its
  ;; luminance judge says no) while the twin's zero lands exactly on the real
  ;; flipped index disc — the twin is the reading that explains the index
  ;; best under the nominal model. Without session context that twin SHIPS
  ;; (the cold-start hole, asserted as the motivating fact); with two photos'
  ;; worth of mounting the twin is vetoed on (sense, k) and the truth —
  ;; endorsed by the same disc, gauge swept, held to the off-ring floor —
  ;; registers instead.
  (println "\n=== gabbia: il montaggio della sessione condanna il gemello ===")
  (let [{:keys [targets intr pose]} (setup eye)
        truth (flipped-y-targets targets)
        cands (scene nil truth intr pose)
        judge (fn [px _r] (boolean (some (fn [[u v]]
                                           (< (Math/hypot (- u (first px)) (- v (second px))) 4.0))
                                         cands)))
        naked (mc/auto-read cands targets intr judge marks {:disc-r 1.25})
        d-of (fn [rr] (la/v-norm (la/v-sub (cam/camera-center (:pose rr))
                                           (cam/camera-center pose))))
        session {:y {:sense :rev :k 0 :votes 2 :d 3.0}}
        rr (mc/auto-read cands targets intr judge marks
                         {:disc-r 1.25 :mounting session})]
    (println (str "  senza contesto: "
                  (if naked (str "REGISTRA a " (.toFixed (d-of naked) 1) "mm — il gemello")
                      "rifiuta")
                  " · col montaggio: "
                  (if rr (str "seme " (name (:axis (:seed rr)))
                              " · spiega " (:explained rr)
                              " · a " (.toFixed (d-of rr) 1) "mm")
                      "RIFIUTATA")))
    ;; the motivating fact: context-free, the twin wins or nothing does —
    ;; never the truth with the wrong zero
    (when naked
      (is (> (d-of naked) 100.0)
          "senza contesto il gemello vince: è il buco che l'arbitro esiste per chiudere"))
    (is (some? rr) "col montaggio della sessione la foto si registra")
    (when rr
      (is (< (d-of rr) 5.0) (str "sulla posa VERA (" (.toFixed (d-of rr) 1) "mm)"))
      (is (some #(and (= :y (:axis %)) (= :rev (:sense %)) (= 0 (:k %)))
                (:index-obs rr))
          "e il risultato porta l'osservazione che alimenta il montaggio della sessione"))))

(deftest the-session-mounting-vetoes-the-hand-twin
  ;; The hand-seeded path gets the same arbiter (found live, foto 5
  ;; battiscopa2: a starved frame elected the flip-face twin of a ring THREE
  ;; photographs had voted fwd(k6), and renamed correct picks). Scene: the
  ;; battiscopa2 shape — Y ring mounted turned 180° (indices moved six steps,
  ;; crowns invariant). The user's names are RIGHT; the session knows the
  ;; mounting; every rereading whose pose puts the seed index off the voted
  ;; slot — the flip-face family AND the whole-step rotation family — dies as
  ;; a fact.
  (println "\n=== gabbia: il montaggio della sessione arbitra anche la mano ===")
  (let [{:keys [targets intr pose]} (setup eye)
        truth (mapv (fn [{:keys [id obj] :as t}]
                      (if (#{:zero-yp :zero-ym} id)
                        (assoc t :obj (cage/turn-about-axis :y obj 180.0))
                        t))
                    targets)
        cands (scene nil truth intr pose)
        picks (misname truth intr pose :y [0 1 2 3 7 9] 0)
        r (mc/read-crown picks targets cands intr marks
                         {:mounting {:y {:sense :fwd :k 6 :votes 3 :d 3.0}}})]
    (println (str "  lettura " (when r (pr-str (:reading r)))
                  " · veto del montaggio " (when r (pr-str (:mounting-veto r)))))
    (is (some? r) "la lettura c'è")
    (when r
      (is (= {:rot 0 :mirror? false :flip-face? false} (:reading r))
          "i nomi giusti restano giusti: il gemello non li rinomina")
      (is (pos? (:killed (:mounting-veto r)))
          "e il montaggio votato ha contraddetto le riletture specchiate/orbitate"))))

(deftest a-declared-face-is-not-re-read
  ;; The user matched the physical cage to the photo and declared «on ring X I
  ;; see face m» (the per-ring toggles, Vincenzo 30/8). A reading that flips
  ;; that ring's picks to face p is answering a question nobody asked — and
  ;; through-plastic it scores identically, so nothing else can refuse it.
  (println "\n=== gabbia: la faccia dichiarata non si rilegge ===")
  (let [{:keys [targets intr pose]} (setup eye)
        cands (scene nil targets intr pose)
        ;; the picks named on the face the camera really sees, but ROTATED —
        ;; a real misnaming the reading must still fix
        picks (misname targets intr pose :x [0 1 2 3 8 9] 3)
        face-of (fn [r] (->> (:picks r) keys (keep cage/mark-parts) (map :sign) set))
        free (mc/read-crown picks targets cands intr marks)
        held (mc/read-crown picks targets cands intr marks {:declared-faces {:x -1}})]
    (println (str "  libera: " (pr-str (:reading free)) " facce " (pr-str (face-of free))
                  " · con faccia dichiarata: " (pr-str (:reading held))
                  " facce " (pr-str (face-of held))))
    (is (some? held) "la lettura c'è")
    (when held
      (is (= #{-1} (face-of held)) "i pick restano sulla faccia che hai dichiarato")
      (is (false? (:flip-face? (:reading held))) "nessuna rilettura la ribalta")
      (is (= 3 (:rot (:reading held))) "e la rotazione sbagliata viene comunque corretta"))))

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

;; ── the zero of the OTHER ring: veto, not vote ──────────────────────────────

(defn- project-id
  "The true pixel of anchor `id` of `targets` under `pose`."
  [targets intr pose id]
  (let [t (first (filter #(= id (:id %)) targets))]
    (cam/project intr pose (:obj t))))

(deftest the-other-rings-zero-arbitrates-the-tie
  ;; The 2026-08-28 pareggio, synthesized. Candidates carry the crown DISCS but
  ;; no zero-indices — exactly the information a real frame offers the vote,
  ;; since every disc of every ring lands on another disc under a half-turn (the
  ;; crowns are symmetric under 6 steps) and only the zeros move. A full,
  ;; correctly-named seed ring then TIES with its twin: candidate counting
  ;; cannot decide, and on the bench it chose the twin. What decides is a FACT:
  ;; the hand-clicked zero-index of ANOTHER ring, which the twin's pose sends
  ;; hundreds of px from its click.
  (println "\n=== gabbia: lo zero dell'ALTRO anello arbitra il pareggio ===")
  (let [{:keys [targets intr pose]} (setup eye)
        mark-targets (filterv #(nil? (cage/index-parts (:id %))) targets)
        cands (scene nil mark-targets intr pose)
        picks (misname targets intr pose :y [0 1 2 3 4 5 6 7] 0)
        zero-z {:axis :z :px (project-id targets intr pose :zero-zp)}
        bare (mc/read-crown picks targets cands intr marks)
        vetoed (mc/read-crown picks targets cands intr marks {:zero-picks [zero-z]})]
    (println (str "  senza zero: "
                  (when bare (str "rot " (:rot (:reading bare))
                                  " · spiega " (:explained bare)
                                  " · in parità " (count (:ties bare))))))
    (println (str "  con lo zero di Z: "
                  (when vetoed (str "rot " (:rot (:reading vetoed))
                                    " · in parità " (count (:ties vetoed))
                                    " · veto " (pr-str (:zero-veto vetoed))))))
    (is (some? bare) "senza zero una lettura c'è comunque")
    (when bare
      (is (seq (:ties bare))
          "e DICHIARA il pareggio: senza gli zero i candidati non possono decidere"))
    (is (some? vetoed) "con lo zero dell'altro anello la lettura c'è")
    (when vetoed
      (is (= {:rot 0 :mirror? false :flip-face? false} (:reading vetoed))
          "e il gemello è morto: vince la lettura vera")
      (is (empty? (:ties vetoed)) "nessun pareggio dichiarato")
      (is (pos? (:killed (:zero-veto vetoed)))
          "il veto ha contraddetto almeno una rilettura")
      (is (empty? (:moot (:zero-veto vetoed))) "e lo zero non è stato accantonato"))
    ;; a zero clicked on GARBAGE (or a ring mounted steps round: same signature)
    ;; contradicts EVERY reading — that is evidence about the ring, not the
    ;; readings, so it is set aside as :moot and never turns into a refusal
    (let [moot (mc/read-crown picks targets cands intr marks
                              {:zero-picks [{:axis :z :px [10.0 10.0]}]})]
      (println (str "  con uno zero-spazzatura: "
                    (when moot (str "rot " (:rot (:reading moot))
                                    " · veto " (pr-str (:zero-veto moot))))))
      (is (some? moot) "uno zero che contraddice tutto non diventa un rifiuto")
      (when moot
        (is (= [:z] (:moot (:zero-veto moot)))
            "ma viene messo agli atti come :moot")))))

(deftest a-mounted-ring-is-measured-from-its-clicked-zero
  ;; The k-step re-reading (rescue-hand-zeros): the cage PHOTOGRAPHED has ring Z
  ;; MOUNTED three steps round — the phases are per-assembly, the cage opens at
  ;; every part change — while the model declares nothing. Every disc of Z still
  ;; lands on a disc position, so the marks fit and the pose is right; only the
  ;; ZERO moved, and the solve, doing its job, throws away the one pick that
  ;; tells the truth. The probe must catch it: find k=3, confirm by re-solving
  ;; with the zero kept, and hand back the mounting the photo just measured.
  ;; This tests the PURE measuring fn; the editor ships only the diagnosis and
  ;; the suggested :phases, never the substitute registration (Vincenzo,
  ;; 2026-08-28: la gabbia deve essere giusta).
  (println "\n=== gabbia: l'anello montato girato si misura dal suo zero ===")
  (let [truth (cage/registration-cage :d 176 :phases {:z 90})
        model (cage/registration-cage :d 176)
        truth-targets (cage-targets truth)
        targets (cage-targets model)
        w 3024 h 4032
        intr (cam/intrinsics-from-fov (cam/equiv-focal->hfov-deg 48.0 (/ w h)) w h)
        pose (cam/look-at-pose eye [0.0 0.0 0.0] [0.0 0.0 1.0])
        ;; marks labelled off the MODEL's predictions — which is what a user or a
        ;; proposal produces, and is positionally RIGHT despite the mounting
        ;; (whole steps map discs onto discs); the zero clicked where it truly is
        mark-ids [:ym00 :ym02 :ym04 :ym06 :ym08 :ym10
                  :xm01 :xm03 :xm05 :xm07 :xm09 :xm11
                  :zm00 :zm03 :zm06 :zm09]
        by-id (into {} (map (juxt :id identity) targets))
        corr (vec (concat
                   (for [id mark-ids]
                     {:ci id :world (:obj (by-id id))
                      :px (cam/project intr pose (:obj (by-id id)))})
                   [{:ci :zero-zm :world (:obj (by-id :zero-zm))
                     :px (project-id truth-targets intr pose :zero-zm)}]))
        sol (pnp/solve-pnp corr intr {})
        zero-out? (some #(= :zero-zm (:ci %)) (:outliers sol))
        axis-of (fn [ci] (when (= ci :zero-zm) :z))
        r (mc/rescue-hand-zeros sol corr intr marks axis-of axis-of)]
    (println (str "  solve cieco: rms " (.toFixed (:rms-px sol) 2)
                  "px · zero scartato? " (boolean zero-out?)))
    (is (some? sol) "il solve c'è")
    (is zero-out? "PREMESSA: senza soccorso lo zero vero viene scartato come outlier")
    (println (str "  soccorso: " (when r (str (pr-str (:phases r))
                                              " · rms " (.toFixed (:rms-px (:sol r)) 2) "px"))))
    (is (some? r) "la rilettura a k passi lo salva")
    (when r
      (is (= 3 (get-in r [:phases :z :steps])) "tre passi")
      (is (< (Math/abs (- 90.0 (get-in r [:phases :z :deg]))) 1e-9) "novanta gradi")
      (is (< (:rms-px (:sol r)) 1.0)
          (str "e il fit che TIENE lo zero chiude stretto ("
               (.toFixed (:rms-px (:sol r)) 2) "px)"))
      (is (not-any? #(= :zero-zm (:ci %)) (:outliers (:sol r)))
          "con lo zero dentro, non più outlier"))
    ;; and on a clean solve the rescue stays silent
    (let [clean-corr (vec (for [id (conj mark-ids :zero-zm)]
                            {:ci id :world (:obj (by-id id))
                             :px (cam/project intr pose (:obj (by-id id)))}))
          clean (pnp/solve-pnp clean-corr intr {})]
      (is (nil? (mc/rescue-hand-zeros clean clean-corr intr marks axis-of axis-of))
          "niente da salvare: il soccorso non tocca un solve pulito"))))

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

(deftest a-ring-clicked-on-both-faces-has-no-possible-pose
  ;; The fact the editor's pre-solve guard rests on, asserted here where the
  ;; geometry lives. The two faces of a ring carry the same discs 3mm apart
  ;; through the plastic and their printed normals point OPPOSITE ways, so a set
  ;; naming `yp03` and `zero-ym` claims the camera was in front of both. It was
  ;; in front of neither, and no relabelling of the RING can help: flipping it
  ;; carries the contradiction along, both members swapping together.
  ;;
  ;; Vincenzo hit it on battiscopa3 grab-04 (2026-08-31) by clicking ⊙ym and THEN
  ;; declaring Yp — the declaration changed what the panel offered and left the
  ;; pick where it was. Of the eight face combinations of his thirteen clicks,
  ;; ZERO were physically possible; with the stale ⊙ym renamed, one was. Until
  ;; this the app answered with a camera-dietro refusal that blamed a click.
  (println "\n=== gabbia: un anello cliccato su DUE facce non ha nessuna posa possibile ===")
  (let [{:keys [proxy targets intr pose]} (setup eye)
        by-id (into {} (map (juxt :id identity)) targets)
        px-of (fn [id] (cam/project intr pose (:obj (by-id id))))
        ;; the faces this vantage actually shows — the rms cannot tell them apart
        ;; (3mm through the plastic) so they must come from the geometry
        sign-of (fn [axis] (let [f (visible-face targets pose axis)]
                             (:sign (or (cage/mark-parts (:id (first (:marks f))))
                                        (cage/index-parts (:id (first (:marks f))))))))
        ys (sign-of :y) zs (sign-of :z)
        ;; a perfectly clean, perfectly named set: two rings, plus both zeros
        honest (into {} (keep (fn [id] (when-let [p (px-of id)] [id p])))
                     (concat (for [i [0 1 3 6 9]] (cage/mark-id :y ys i))
                             (for [i [0 2 6]] (cage/mark-id :z zs i))
                             [(cage/index-id :y ys) (cage/index-id :z zs)]))
        ;; the same clicks, with the Y ring's ZERO filed under the other face —
        ;; exactly one name changed, every pixel identical
        two-faced (-> honest
                      (dissoc (cage/index-id :y ys))
                      (assoc (cage/index-id :y (- ys)) (px-of (cage/index-id :y ys))))
        solve (fn [pm] (pnp/solve-pnp
                        (vec (for [[id p] pm] {:ci id :world (:obj (by-id id)) :px p}))
                        intr {}))
        behind (fn [pm]
                 (when-let [sol (solve pm)]
                   (let [c (cam/camera-center (:pose sol))]
                     (vec (for [[id _] pm
                                :let [t (by-id id) nrm (:normal t)]
                                :when (and nrm (neg? (la/v-dot nrm (la/v-sub c (:obj t)))))]
                            id)))))
        ;; every way the RING can be re-read, face and numbering together
        ring-readings (fn [pm axis]
                        (for [rd (cage/crown-misreadings marks)]
                          (into {} (map (fn [[id p]]
                                          [(if (= axis (cage/anchor-axis id))
                                             (or (cage/relabel id rd marks) id)
                                             id)
                                           p]))
                                pm)))]

    (testing "the honest set is possible, and the solve recovers the pose"
      (let [sol (solve honest)]
        (println (str "  set onesto: rms " (.toFixed (:rms-px sol) 2) "px · camera dietro a "
                      (pr-str (behind honest))))
        (is (some? sol))
        (is (empty? (behind honest))
            "a correctly named set puts the camera in front of every clicked disc")))

    (testing "one name moved to the other face makes EVERY re-reading impossible"
      (let [bad (behind two-faced)
            rescued (remove #(seq (behind %)) (ring-readings two-faced :y))]
        (println (str "  ⊙y sull'altra faccia: camera dietro a " (count bad)
                      " punti · riletture dell'anello Y possibili: "
                      (count rescued) "/" (count (cage/crown-misreadings marks))))
        (is (seq bad)
            "a set naming both faces of one ring cannot put the camera in front of all of them")
        (is (empty? rescued)
            (str "no re-reading of the ring can cure it — the contradiction travels "
                 "with the flip, got " (count rescued) " survivors"))))))
