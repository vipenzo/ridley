(ns ridley.photogrammetry.cage-test
  "The parametric registration-CAGE proxy: three orthogonal rings that must not
   collide, carry six crowns (both faces of each ring) plus a zero-index per
   face, and — the reason it exists — register from ANY direction, including the
   ones a flat plate cannot see at all, on marks that are NOT coplanar."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.photogrammetry.cage :as cage]
            [ridley.photogrammetry.bridge :as bridge]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.pnp :as pnp]
            [ridley.photogrammetry.synth :as synth]
            [clojure.string :as str]))

(defn- k* [] {:fx (* 4032 (/ 48.0 36.0)) :fy (* 4032 (/ 48.0 36.0))
              :cx 2016.0 :cy 1512.0 :k1 0.0 :k2 0.0})

(defn- norm [v] (Math/sqrt (reduce + (map * v v))))
(defn- v- [a b] (mapv - a b))
(defn- dot [a b] (reduce + (map * a b)))

;; ---------------------------------------------------------------------------

(deftest rings-do-not-collide
  (testing "the radial BANDS are disjoint — the whole reason the three diameters differ"
    (println "\n=== gabbia: i tre anelli, fasce disgiunte ===")
    (doseq [d [140 176 240]]
      (let [rs (mapv #(cage/ring-radii d %) (range cage/ring-count))]
        (doseq [{:keys [axis outer inner crown index]}
                (map #(assoc %2 :axis (cage/ring-axis %1)) (range) rs)]
          (println (str "  ⌀" d " anello " (name axis)
                        ": fascia " (.toFixed inner 1) "-" (.toFixed outer 1)
                        ", corona R" (.toFixed crown 1)
                        ", zero R" (.toFixed index 1))))
        (doseq [k (range (dec cage/ring-count))]
          (let [outer-ring (nth rs k)
                inner-ring (nth rs (inc k))]
            (is (> (:inner outer-ring) (:outer inner-ring))
                (str "⌀" d ": la fascia dell'anello " k " non deve toccare quella del "
                     (inc k) " — anelli che si compenetrano non si montano"))))
        ;; every crown and every zero-index has to live INSIDE its own band, or
        ;; the printed disc hangs off the ring
        (doseq [{:keys [outer inner crown index]} rs]
          (is (and (< crown outer) (> index inner))
              (str "⌀" d ": corona e zero-indice dentro la fascia")))))))

(defn- span
  "[lo hi] of an axis-aligned box along component `i`."
  [{:keys [center size]} i]
  [(- (nth center i) (/ (nth size i) 2.0)) (+ (nth center i) (/ (nth size i) 2.0))])

(defn- overlap? [[a b] [c d]] (and (< a d) (< c b)))

(defn- ring-slab
  "The slab of space a ring's material lives in, as [lo hi] per axis: its own
   thickness across its normal, and ±outer radius on the other two."
  [d h k]
  (let [{:keys [outer]} (cage/ring-radii d k)
        ax (cage/ring-axis k)]
    (mapv (fn [a] (if (= a ax) [(- (/ h 2.0)) (/ h 2.0)] [(- outer) outer]))
          [:x :y :z])))

(deftest tabs-reach-their-partner-and-hit-nothing-else
  (testing "the six lap joints: rooted in their own ring, flush with the partner's
            rim, and clear of the third ring — the three ways a tab can be wrong,
            each of which only shows up after four hours of printing"
    (println "\n=== gabbia: le sei linguette ===")
    (let [d 176.0 h 3.0
          boxes (cage/joint-tabs d h)
          tabs (filterv #(= :lap (:kind %)) boxes)
          axis-i {:x 0 :y 1 :z 2}]
      (is (= 6 (count tabs)) "one pair of tabs per pair of rings")
      (is (= {:z 4 :y 2} (frequencies (map :owner tabs)))
          "four on the smallest ring, two on the middle, none on the largest")
      (doseq [{:keys [owner partner along sign] :as tab} tabs]
        (let [q (cage/ring-radii d (axis-i owner))
              p (cage/ring-radii d (axis-i partner))
              [lo hi] (span tab (axis-i along))
              reach (if (pos? sign) hi (- lo))
              root (if (pos? sign) lo (- hi))
              ;; the glued patch: the tab's height across its own ring × the
              ;; partner's band, which is all of it the partner can touch
              contact (* (nth (:size tab) (axis-i owner)) (- (:outer p) (:inner p)))]
          (println (str "  " (name owner) " → " (name partner)
                        " lungo " (if (pos? sign) "+" "-") (name along)
                        ": da R" (.toFixed root 1) " a R" (.toFixed reach 1)
                        ", incollata su " (.toFixed contact 0) " mm²"))
          ;; la punta: a filo col bordo del partner quando non c'è battuta;
          ;; sotto TUTTA la battuta quando c'è, perché una battuta che sta
          ;; soltanto ACCANTO alla linguetta è un pezzo separato
          (let [stop (first (filter #(and (= :stop (:kind %))
                                          (= owner (:owner %))
                                          (= along (:along %))
                                          (= sign (:sign %)))
                                    boxes))
                atteso (if stop
                         (let [[lo hi] (span stop (axis-i along))]
                           (if (pos? sign) hi (- lo)))
                         (:outer p))]
            (is (< (Math/abs (- reach atteso)) 1e-9)
                (if stop
                  "la linguetta deve arrivare sotto tutta la battuta"
                  "la punta si ferma a filo col bordo del partner")))
          (is (and (< root (:outer q)) (> root (:inner q)))
              "the near end is rooted inside its own ring's band, not past it")
          (is (> contact 80.0)
              "the joint must meet the partner on a FACE — an edge-on stripe of
               3×12 was the first design, and it is a strip of card in torsion")
          (is (not (every? true? (map #(overlap? (span tab %) (nth (ring-slab d h (axis-i partner)) %))
                                      [0 1 2])))
              "a tab that intersects its partner cannot be assembled")
          (let [third (first (remove #{owner partner} [:x :y :z]))]
            (is (not (every? true? (map #(overlap? (span tab %) (nth (ring-slab d h (axis-i third)) %))
                                        [0 1 2])))
                (str "a tab must not run into the " (name third) " ring")))))
      (doseq [[a b] (for [i (range (count boxes)) j (range (inc i) (count boxes))]
                      [(nth boxes i) (nth boxes j)])
              :when (not= [(:owner a) (:partner a) (:along a) (:sign a)]
                          [(:owner b) (:partner b) (:along b) (:sign b)])]
        (is (not (every? true? (map #(overlap? (span a %) (span b %)) [0 1 2])))
            "two joints must not collide")))))

(deftest stops-locate-the-ring-without-locking-it-out
  (testing "the lips are a battuta, not a trap. A stop only stops if it reaches
            across the partner's plane — and the partner is fitted by sliding
            along its own normal, so a lip in that path stops the assembly
            instead. These check both halves: that they engage, and that the ring
            never has to pass through them."
    (println "\n=== gabbia: le battute ===")
    (let [d 176.0 h 3.0
          boxes (cage/joint-tabs d h)
          stops (filterv #(= :stop (:kind %)) boxes)
          axis-i {:x 0 :y 1 :z 2}]
      (is (= 4 (count stops)) "solo sui quattro giunti dell'anello piccolo")
      (is (every? #(= :z (:owner %)) stops))
      (doseq [{:keys [partner along sign] :as st} stops]
        (let [p (cage/ring-radii d (axis-i partner))
              [lo hi] (span st (axis-i along))
              near (if (pos? sign) lo (- hi))
              [plo phi] (span st (axis-i partner))]
          (println (str "  battuta verso " (name partner)
                        " a R" (.toFixed near 1)
                        " (bordo anello R" (.toFixed (:outer p) 1) "), copre "
                        (.toFixed plo 1) "…" (.toFixed phi 1) " mm attraverso il piano"))
          ;; engages: it starts just outside the rim, close enough to catch it…
          (is (< (:outer p) near (+ (:outer p) 1.0))
              "la battuta deve stare appena FUORI dal bordo: dentro non ci entra, lontana non ferma")
          ;; …and reaches across the ring's own thickness, or it blocks nothing
          (is (< plo (- (/ h 2.0)))
              "deve attraversare il piano dell'anello, o non è una battuta")
          ;; never swept: the ring's material never reaches this radius
          (is (> near (:outer p))
              "l'anello non arriva mai fin qui, quindi non ci sbatte entrando")))
      ;; the pair has to RECEIVE the ring: the clear span between the two facing
      ;; lips must exceed its diameter, or they are a vice rather than a seat
      (doseq [[partner ss] (group-by :partner stops)]
        (let [p (cage/ring-radii d (axis-i partner))
              i (axis-i (:along (first ss)))
              near (fn [st] (let [[lo hi] (span st i)]
                              (if (pos? (:sign st)) lo (- hi))))
              gap (reduce + (map near ss))]
          (println (str "  luce fra le due battute verso " (name partner) ": "
                        (.toFixed gap 1) " mm per un anello ⌀"
                        (.toFixed (* 2 (:outer p)) 1)))
          (is (> gap (* 2 (:outer p)))
              "due battute a distanza esatta non ricevono un anello, lo rifiutano"))))))

(deftest no-mark-sits-under-a-tab
  (testing "the crowns are turned half a step so the four directions the tabs run
            along fall BETWEEN marks. Before this existed, seven marks of the
            reference cage — one of them a zero-index — sat exactly under a tab,
            and nothing in the code said so; only the printed part would have."
    (println "\n=== gabbia: nessun mark sotto una linguetta ===")
    (doseq [n [8 10 12]]
      (let [c (cage/registration-cage :d 176 :marks n)
            step (/ 360.0 n)
            ;; each mark's angle in ITS OWN ring frame, where the tabs run at
            ;; 0/90/180/270
            ;; a mark's angle IN ITS OWN RING, recovered by dropping the ring's
            ;; normal component from the position — which is where the tabs'
            ;; 0/90/180/270 live
            angles (for [[id a] (:anchors c)
                         :let [nm (name id)
                               ax (keyword (subs nm (if (bridge/index-anchor? id) 5 0)
                                                 (if (bridge/index-anchor? id) 6 1)))
                               i-n ({:x 0 :y 1 :z 2} ax)
                               [pu pv] (vec (keep-indexed
                                             (fn [i x] (when (not= i i-n) x))
                                             (:position a)))]]
                     (mod (* 180.0 (/ (Math/atan2 pv pu) Math/PI)) 360.0))
            worst (apply min (for [a angles, ax [0.0 90.0 180.0 270.0]]
                               (let [d (Math/abs (- a ax))]
                                 (min d (- 360.0 d)))))]
        (println (str "  " n " mark, passo " (.toFixed step 1)
                      "° → il mark più vicino a un asse sta a " (.toFixed worst 1) "°"))
        (is (> worst 5.0)
            (str n " mark: nessun mark deve cadere sotto una linguetta"))))))

(deftest builds-a-cage-with-six-crowns
  (let [c (cage/registration-cage :d 176)
        a (:anchors c)
        crown (remove (comp bridge/index-anchor? key) a)
        idx (filter (comp bridge/index-anchor? key) a)]
    (testing "six marked faces — three rings × two sides"
      (is (= (* 6 12) (count crown)) "12 marks on each of the six faces")
      (is (= 6 (count idx)) "one zero-index per marked face")
      (is (every? some? (map #(get a %) [:zp00 :zm00 :yp00 :ym00 :xp00 :xm00
                                         :zero-zp :zero-zm :zero-yp :zero-ym
                                         :zero-xp :zero-xm]))
          "the ids name the ring by its normal axis and the face by its sign"))
    (testing "the two faces of a ring share their marks' (u,v) — one disc through the material"
      (let [[px py pz] (:position (:zp07 a))
            [mx my mz] (:position (:zm07 a))]
        (is (and (< (Math/abs (- px mx)) 1e-9) (< (Math/abs (- py my)) 1e-9)))
        (is (< (Math/abs (+ pz mz)) 1e-9) "…and are at ±h/2")))
    (testing "each mark's :heading is its own printed face's outward normal"
      (is (= [0.0 0.0 1.0] (:heading (:zp00 a))))
      (is (= [0.0 0.0 -1.0] (:heading (:zm00 a))))
      (is (= [0.0 1.0 0.0] (:heading (:yp00 a))))
      (is (= [1.0 0.0 0.0] (:heading (:xp00 a)))))
    (testing "renders as a mesh, one face-group per ring"
      (is (seq (:vertices c)))
      (is (seq (:faces c)))
      (is (= #{:ring-x :ring-y :ring-z} (set (keys (:face-groups c))))))
    (testing "carries what fabrication and calibration need"
      (is (= 176 (:cage-d c)))
      (is (= 1.25 (:mark-disc-r c)) "⌀2.5 disc on the reference cage")
      (is (= 88.0 (cage/aperture 176)) "clear opening through the innermost ring")
      (is (:anchor-culling? c) "a cage always turns half its marks away"))
    (testing ":d is required — a wrong scale registers cleanly and lies"
      (is (thrown? js/Error (cage/registration-cage))))))

(deftest a-scaled-cage-is-the-same-cage
  (testing "every proportion is a fraction of :d, so any size images the same"
    (doseq [d [140 176 240]]
      (let [c (cage/registration-cage :d d)
            r0 (cage/ring-radii d 0)]
        (is (< (Math/abs (- (/ (* 2 (:mark-disc-r c)) d) (/ 2.5 176.0))) 1e-12)
            "il dischetto scala con la gabbia")
        (is (< (Math/abs (- (/ (:crown r0) d) (/ 85.5 176.0))) 1e-12)
            "la corona sta alla stessa frazione del diametro")
        (is (= 3.0 (:cage-h c)) "lo spessore NON scala — è rigidezza, non immagine")))))

;; ---------------------------------------------------------------------------
;; The point of the whole thing

(defn- targets-of [c pose] (bridge/pnp-target-points c pose))

(deftest zero-indices-are-not-pickable
  (let [c (cage/registration-cage :d 176)
        ts (targets-of c (:creation-pose c))]
    (is (= 72 (count ts)) "72 crown marks offered, the six zero-indices withheld")
    (is (not-any? #(bridge/index-anchor? (:id %)) ts))
    (is (every? :normal ts) "each target carries its printed face's normal")))

(deftest culling-halves-the-dots-and-never-empties-them
  (testing "from any vantage about half the marks face you — and never zero, which is
            what makes per-mark culling safe here and fatal on a plate"
    (println "\n=== gabbia: mark offerti da varie direzioni ===")
    (let [c (cage/registration-cage :d 176)]
      (doseq [[az el] [[0 0] [45 35] [135 -40] [-90 70] [200 -75] [30 89]]]
        (let [eye [(* 400 (Math/cos (* el (/ Math/PI 180))) (Math/cos (* az (/ Math/PI 180))))
                   (* 400 (Math/cos (* el (/ Math/PI 180))) (Math/sin (* az (/ Math/PI 180))))
                   (* 400 (Math/sin (* el (/ Math/PI 180))))]
              ts (targets-of c {:position eye :heading [0 0 1] :up [0 1 0]})
              vis (count (filter :visible? ts))]
          (println (str "  az " az "° el " el "° → " vis "/72 mark davanti"))
          (is (pos? vis) "una gabbia non gira MAI tutti i mark dall'altra parte")
          (is (< vis (count ts)) "…né li mostra tutti"))))))

(defn- ring-of
  "Which ring a target belongs to, from its id — :zp07 → \"z\"."
  [t] (subs (name (:id t)) 0 1))

(defn- solve-from
  "Everything one synthetic viewpoint yields: the marks it really sees (front-
   facing AND inside the frame), which rings they came from, and the pose the
   solver recovers from them."
  [ts az el]
  (let [k (k*)
        truth (synth/viewpoint az el 420.0)
        c-pos (cam/camera-center truth)
        seen (filterv (fn [t]
                        (and (pos? (dot (:normal t) (v- c-pos (:obj t))))
                             (let [[u v] (cam/project k truth (:obj t))]
                               (and (<= 0 u 4032) (<= 0 v 3024)))))
                      ts)
        corr (mapv (fn [t] {:ci (.indexOf ts t)
                            :world (:obj t)
                            :px (cam/project k truth (:obj t))})
                   seen)
        sol (pnp/solve-pnp corr k {})]
    {:truth truth :seen seen :rings (set (map ring-of seen)) :sol sol
     :err (when sol (norm (v- (cam/camera-center (:pose sol)) c-pos)))}))

(deftest registers-from-any-direction
  (testing "the promise: a pose recovered from the marks a view actually sees, from
            directions where a flat plate is edge-on or facing away entirely"
    (println "\n=== gabbia: posa recuperata da ogni direzione ===")
    (let [c (cage/registration-cage :d 176)
          ts (targets-of c (:creation-pose c))]
      (doseq [[az el] [[0 0]      ; level with the table — a plate is a LINE here
                       [37 22]
                       [128 -55]  ; from BELOW — a plate shows its blank back
                       [-100 8]
                       [214 -3]]]
        (let [{:keys [seen rings sol err]} (solve-from ts az el)]
          (println (str "  az " az "° el " el "° → " (count seen) " mark su "
                        (count rings) " anelli, metodo " (some-> sol :method name)
                        ", rms " (some-> sol :rms-px (.toFixed 2)) "px"
                        ", camera fuori di " (some-> err (.toFixed 3)) "mm"))
          (is (some? sol) (str "az " az " el " el ": deve risolvere"))
          (is (< err 0.5) (str "az " az " el " el ": posa recuperata a meno di mezzo mm"))
          ;; The method is not a preference, it is a consequence: marks from two
          ;; rings are not coplanar and get the general estimator; marks from one
          ;; are, and get the homography. Asserting it keeps the routing honest.
          (is (= (if (> (count rings) 1) :dlt :planar) (:method sol))
              (str "az " az " el " el ": " (count rings)
                   " anelli visti → il solutore giusto")))))))

(deftest down-an-axis-you-get-one-ring
  (testing "the cage's one degenerate family, and it is a KNOWN one: along each of
            the three axes the two rings containing it are edge-on, so their marks
            sit on the far side of 3mm of plastic and only one ring is pickable —
            which is a plate again, mirror twin included. A few degrees off and the
            others come back. edit_acquire's per-mark guard exists for exactly this."
    (println "\n=== gabbia: giù per un asse si vede un anello solo ===")
    (let [c (cage/registration-cage :d 176)
          ts (targets-of c (:creation-pose c))]
      (doseq [[label az el] [["asse X" 0 0] ["asse Y" 90 0] ["asse Z" 0 90]
                             ["10° fuori asse X" 10 6]]]
        (let [{:keys [rings sol]} (solve-from ts az el)]
          (println (str "  " label " → " (count rings) " anelli ("
                        (str/join "," (sort rings)) "), metodo "
                        (some-> sol :method name)))
          (if (= "10° fuori asse X" label)
            (is (> (count rings) 1) "fuori asse tornano gli altri anelli")
            (is (= 1 (count rings)) "sull'asse resta un anello solo")))))))

(deftest a-camera-behind-a-clicked-disc-is-impossible
  (testing "the guard that replaces the plate's mirror test on a proxy with six faces"
    (let [c (cage/registration-cage :d 176)
          ts (targets-of c (:creation-pose c))
          truth (synth/viewpoint 40 30 420.0)
          c-pos (cam/camera-center truth)
          front (filterv (fn [i] (pos? (dot (:normal (nth ts i)) (v- c-pos (:obj (nth ts i))))))
                         (range (count ts)))
          back (filterv (fn [i] (neg? (dot (:normal (nth ts i)) (v- c-pos (:obj (nth ts i))))))
                        (range (count ts)))]
      (is (bridge/camera-sees-marks? ts front truth)
          "the marks that face the camera are all in front of it")
      (is (not (bridge/camera-sees-marks? ts (conj front (first back)) truth))
          "one disc claimed from behind is enough to refuse the pose")
      (is (bridge/camera-sees-marks? ts [] truth) "no picks, nothing to veto"))))

(deftest printable-rings-arrive-lying-down
  (testing "ogni anello esce nel suo frame: piatto in XY, mark sulle facce ±Z,
            linguette verso l'ALTO. Due anelli su tre stanno di taglio nelle
            coordinate della gabbia, e un 3MF scritto così chiede all'utente di
            ruotarlo nello slicer: anello e dischetti sono due oggetti, e
            ruotarne uno solo lascia indietro l'altro. È già successo — un
            anello stampato senza pallini (2026-08-18)."
    (println "\n=== gabbia: anelli in posizione di stampa ===")
    (let [c (cage/registration-cage :d 176)]
      (doseq [k (range cage/ring-count)]
        (let [p (cage/printable-ring c k)
              zs (map #(nth (:position %) 2) (:marks p))
              rs (map #(let [[x y _] (:position %)] (Math/hypot x y)) (:marks p))
              tab-z (mapcat #(let [cz (nth (:center %) 2) sz (nth (:size %) 2)]
                               [(- cz (/ sz 2)) (+ cz (/ sz 2))])
                            (:tabs p))]
          (println (str "  anello " (name (:axis p)) ": " (count (:marks p))
                        " mark su z=±" (.toFixed (apply max (map Math/abs zs)) 2)
                        ", raggi " (.toFixed (apply min rs) 1) "…" (.toFixed (apply max rs) 1)
                        ", " (count (:tabs p)) " linguette"
                        (when (seq tab-z)
                          (str " che salgono a z " (.toFixed (apply min tab-z) 1)
                               "…" (.toFixed (apply max tab-z) 1)))))
          ;; i mark stanno SOLO sulle due facce, cioè a ±h/2
          (is (every? #(< (Math/abs (- (Math/abs %) (/ (:h p) 2.0))) 1e-9) zs)
              "i mark stanno sulle facce, non sparsi nello spessore")
          ;; …e le loro normali puntano lungo ±Z
          (is (every? (fn [m] (< (Math/abs (- (Math/abs (nth (:heading m) 2)) 1.0)) 1e-9))
                      (:marks p))
              "le normali dei mark guardano fuori dalle facce")
          ;; i raggi in XY sono quelli della corona e dello zero-indice
          (is (< (Math/abs (- (apply max rs) (:crown p))) 1e-9))
          (is (< (Math/abs (- (apply min rs) (:index p))) 1e-9))
          ;; le linguette salgono, non scendono: se scendessero l'anello si
          ;; poserebbe SU DI LORO invece che sulla propria faccia
          (when (seq tab-z)
            (is (> (apply max tab-z) (/ (:h p) 2.0))
                "la linguetta deve sporgere sopra la faccia")
            (is (> (apply min tab-z) (- (+ (/ (:h p) 2.0) 1e-9)))
                "e non sotto: un anello appoggiato sulle linguette non è piano")))))))

(deftest every-piece-is-attached-to-its-ring
  (testing "il pezzo stampato dev'essere UN solido. Il primo test controllava che
            le linguette non SBATTESSERO contro niente, e non è la stessa cosa:
            le quattro battute stavano 0.3mm oltre la punta della linguetta,
            cioè staccate, e sono uscite dalla stampante come quattro pezzi
            sciolti che si sono persi togliendo l'anello dal piatto
            (Vincenzo, 2026-08-18). Un pezzo che non tocca niente non si vede
            nel modello: si vede sul piano di stampa."
    (println "\n=== gabbia: ogni pezzo attaccato ===")
    (let [d 176.0 h 3.0
          boxes (cage/joint-tabs d h)
          axis-i {:x 0 :y 1 :z 2}
          touch? (fn [a b] (every? #(overlap? (span a %) (span b %)) [0 1 2]))
          ;; il corpo dell'anello, come scatola nel suo piano — basta per dire
          ;; se una linguetta ci affonda dentro
          ring-box (fn [ax]
                     (let [{:keys [outer]} (cage/ring-radii d (axis-i ax))]
                       {:center [0 0 0]
                        :size (mapv #(if (= % ax) h (* 2 outer)) [:x :y :z])}))]
      (doseq [{:keys [owner kind] :as bx} boxes]
        (let [attached-to-ring? (touch? bx (ring-box owner))
              carrier (first (filter (fn [o] (and (= :lap (:kind o))
                                                  (= owner (:owner o))
                                                  (= (:along bx) (:along o))
                                                  (= (:sign bx) (:sign o))
                                                  (touch? bx o)))
                                     boxes))]
          (println (str "  " (name kind) " " (name owner) "→" (name (:partner bx))
                        " " (if (pos? (:sign bx)) "+" "-") (name (:along bx))
                        ": " (cond attached-to-ring? "affonda nell'anello"
                                   carrier "saldata alla sua linguetta"
                                   :else "STACCATA")))
          (is (or attached-to-ring? (some? carrier))
              (str (name kind) " deve toccare l'anello o la linguetta che la porta")))))))
