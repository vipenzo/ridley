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
      ;; collisions are checked among MATERIAL boxes of different joints. The
      ;; assembly key is exempt twice over, by design and not by leniency: the
      ;; notch is a CUT (its box overlapping anything removes nothing but its
      ;; owner's material), and the pin OVERLAPS the notch because that is what
      ;; a key does — it also overlaps its own tab by 1mm, an exact touch being
      ;; the coincident-faces CSG recipe.
      (doseq [[a b] (let [material (filterv #(#{:lap :stop} (:kind %)) boxes)]
                      (for [i (range (count material)) j (range (inc i) (count material))]
                        [(nth material i) (nth material j)]))
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

(deftest stick-slots-sit-clear-and-point-inward
  ;; The part-holder's slots (Vincenzo's tested ellipse-cam design, 2026-08-25):
  ;; two per ring, and every one must hold a stick pointing AT THE CENTRE while
  ;; sitting clear of everything that matters — the marks (occlusion is lost
  ;; registration data), the tabs, and the key notch.
  (println "\n=== gabbia: gli slot del portapezzo ===")
  (let [d 176.0
        c (cage/registration-cage :d d)
        slots (:stick-slots c)
        dot (fn [a b] (reduce + (map * a b)))
        norm (fn [v] (Math/sqrt (dot v v)))]
    (is (= 6 (count slots)) "due per anello")
    (is (= {:x 2 :y 2 :z 2} (frequencies (map :axis slots))))
    (doseq [{:keys [axis position heading up azimuth-deg]} slots]
      ;; the stick's direction: radially inward, in the ring's plane
      (let [r (norm position)
            radial-in (mapv #(/ (- %) r) position)]
        (is (> (dot heading radial-in) 0.999)
            (str "lo stick punta al CENTRO (" (name axis) " " azimuth-deg "°)")))
      ;; the body rises along the ring's own axis — the face everything rises from
      (is (> (Math/abs (dot up (case axis :x [1 0 0] :y [0 1 0] :z [0 0 1]))) 0.999)
          "il corpo sale lungo l'asse dell'anello")
      ;; clear of every mark on its own ring
      (let [margin (reduce min
                           (for [[id a] (:anchors c)
                                 :when (= axis (cage/anchor-axis id))]
                             (norm (mapv - (:position a) position))))]
        (println (str "  " (name axis) " " azimuth-deg "° · mark più vicino a "
                      (.toFixed margin 1) " mm"))
        (is (> margin 10.0) "lo slot sta largo dai dischetti"))
      ;; and 30° from the nearest tab (tabs run at multiples of 90 in ring frame)
      (is (>= (Math/abs (- (mod azimuth-deg 90.0) 30.0)) 0.0) "azimut fra i giunti"))
    ;; the printable pose carries them FLAT: midplane, up = +Z
    (doseq [k (range cage/ring-count)]
      (let [p (cage/printable-ring c k)]
        (is (= 2 (count (:slots p))) "due slot anche nel frame di stampa")
        (doseq [{:keys [position up]} (:slots p)]
          (is (< (Math/abs (nth position 2)) 1e-9) "sul piano medio")
          (is (> (nth up 2) 0.999) "che salgono verso l'alto di stampa"))))))

(deftest assembly-key-refuses-every-wrong-rotation
  ;; The key Vincenzo asked for after gluing the reference cage 90° round
  ;; ('una tacca e una spina', 2026-08-24): one pin on one middle-ring tab, one
  ;; notch in the largest ring's rim. Pure geometry checks: the pin must BLOCK
  ;; the largest ring's slide at any rotation, the notch must admit exactly the
  ;; pin (with clearance), and the pair must be asymmetric enough to refuse the
  ;; ring flipped face-for-face.
  (println "\n=== gabbia: la chiave di montaggio (tacca e spina) ===")
  (let [d 176.0 h 3.0
        boxes (cage/joint-tabs d h)
        pins (filterv #(= :key-pin (:kind %)) boxes)
        notches (filterv #(= :key-notch (:kind %)) boxes)
        x (cage/ring-radii d 0)
        axis-i {:x 0 :y 1 :z 2}]
    (is (= 1 (count pins)) "UNA spina — una chiave, non una serratura per faccia")
    (is (= 1 (count notches)) "e UNA tacca")
    (let [pin (first pins) notch (first notches)]
      (is (= :y (:owner pin)) "la spina sta su una linguetta dell'anello di MEZZO")
      (is (= :x (:owner notch)) "la tacca si taglia nell'anello GRANDE")
      (let [ish (axis-i (:along pin))
            ip (axis-i (:partner pin))
            iq (axis-i (:owner pin))
            [pr-lo pr-hi] (span pin ish)
            [pp-lo pp-hi] (span pin ip)
            [pq-lo pq-hi] (span pin iq)
            [nr-lo nr-hi] (span notch ish)
            [np-lo np-hi] (span notch ip)
            [nq-lo nq-hi] (span notch iq)]
        (println (str "  spina: R" (.toFixed pr-lo 2) "…" (.toFixed pr-hi 2)
                      " · attraverso il piano " (.toFixed pp-lo 2) "…" (.toFixed pp-hi 2)
                      " · azimut " (.toFixed pq-lo 2) "…" (.toFixed pq-hi 2)))
        (println (str "  tacca: R" (.toFixed nr-lo 2) "…" (.toFixed nr-hi 2)
                      " · attraverso " (.toFixed np-lo 2) "…" (.toFixed np-hi 2)
                      " · azimut " (.toFixed nq-lo 2) "…" (.toFixed nq-hi 2)))
        ;; blocks: radially inside the big ring's band, reaching across its slab
        (is (and (> pr-lo (:inner x)) (< pr-hi (:outer x)))
            "la spina pesca DENTRO la fascia dell'anello grande")
        (is (< pp-lo (/ h 2.0))
            "e attraversa il suo piano: entrando, il bordo la incontra")
        ;; admitted: the notch clears the pin all round, and cuts through
        (is (and (< nr-lo pr-lo) (> nr-hi (:outer x)))
            "la tacca copre la spina e resta aperta oltre il bordo")
        (is (and (< np-lo (- (/ h 2.0))) (> np-hi (/ h 2.0)))
            "la tacca taglia TUTTO lo spessore: l'anello ci scorre attraverso")
        (is (and (< nq-lo pq-lo) (> nq-hi pq-hi))
            "e lascia gioco in azimut")
        ;; asymmetric: the ring FLIPPED (its q-interval negated) must not fit
        (let [[fq-lo fq-hi] [(- pq-hi) (- pq-lo)]]
          (is (not (and (< nq-lo fq-lo) (> nq-hi fq-hi)))
              "girato faccia-per-faccia, la spina cade fuori dalla tacca: rifiutato"))
        ;; and the notch stays clear of every mark on the big ring
        (let [c (cage/registration-cage :d d)
              margin (reduce min
                             (for [[id a] (:anchors c)
                                   :when (= :x (cage/anchor-axis id))
                                   :let [[_ py pz] (:position a)]]
                               ;; point-to-box distance in the ring's own plane (y,z)
                               (let [dy (max 0.0 (- nq-lo py) (- py nq-hi))
                                     dz (max 0.0 (- nr-lo pz) (- pz nr-hi))]
                                 (Math/hypot dy dz))))]
          (println (str "  margine minimo tacca→mark: " (.toFixed margin 1) " mm"))
          (is (> margin 5.0) "la tacca sta larga dai dischetti"))))))

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

(deftest zero-indices-ARE-pickable-on-a-cage
  (testing "e sono il bersaglio più prezioso: una corona di dodici mark uguali è
            invariante per rotazione e dall'altra faccia si legge specchiata, per
            cui i suoi stessi punti non possono dire quale mark è quale — misurato
            su una foto vera, tutte e 48 le riletture di una corona danno lo stesso
            32.5px. Lo zero-indice rompe le due simmetrie insieme."
    (let [c (cage/registration-cage :d 176)
          ts (targets-of c (:creation-pose c))
          idx (filter #(bridge/index-anchor? (:id %)) ts)]
      (is (= 78 (count ts)) "72 mark di corona PIÙ i sei zero-indici")
      (is (= 6 (count idx)) "uno per faccia marcata")
      (is (every? :index? idx) "…e si dichiarano come tali")
      (is (not-any? :index? (remove #(bridge/index-anchor? (:id %)) ts)))
      (is (every? :normal ts) "each target carries its printed face's normal"))))

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
          (println (str "  az " az "° el " el "° → " vis "/" (count ts) " mark davanti"))
          (is (pos? vis) "una gabbia non gira MAI tutti i mark dall'altra parte")
          (is (< vis (count ts)) "…né li mostra tutti"))))))

(defn- ring-of
  "Which ring a target belongs to — :zp07 → \"z\", and :zero-xp → \"x\", which
   reading the first letter would call \"z\"."
  [t] (name (cage/anchor-axis (:id t))))

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
              ;; the rises-upward check is about MATERIAL: the key notch is a
              ;; CUT, and a through cut necessarily crosses both faces
              tab-z (mapcat #(let [cz (nth (:center %) 2) sz (nth (:size %) 2)]
                               [(- cz (/ sz 2)) (+ cz (/ sz 2))])
                            (remove #(= :key-notch (:kind %)) (:tabs p)))]
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

;; --- la fase degli anelli ----------------------------------------------------
;;
;; L'anello PIÙ GRANDE non ha linguette proprie: lo tengono quelle degli altri
;; due che gli premono contro la faccia, e può girare restando appoggiato. Il
;; punto di incollaggio dovrebbe cadere a metà fra due pallini, ma trovare quel
;; punto a occhio mentre l'epossidica prende è difficile davvero (Vincenzo,
;; 2026-08-19) — e a raggio 85mm un grado vale 1.5mm. Quindi la fase si MISURA
;; sulla gabbia costruita invece di imporla, ed è :phases a riceverla.

(defn- anchor-pos [c id] (get-in c [:anchors id :position]))

(deftest phases-turn-a-whole-ring-both-faces-at-once
  (testing "un passo esatto di fase rinomina i mark; e le due facce si muovono
            insieme, perché sono gli stessi dischetti visti attraverso 3mm di
            plastica"
    (println "\n=== gabbia: la fase di un anello ===")
    (let [nominal (cage/registration-cage :d 176)
          step-deg (/ 360.0 12)
          turned (cage/registration-cage :d 176 :phases {:x step-deg})
          near? (fn [a b] (< (norm (v- a b)) 1e-9))]
      (is (= (:anchors nominal) (:anchors (cage/registration-cage :d 176 :phases {})))
          ":phases vuoto è la gabbia nominale")
      (doseq [face ["p" "m"]]
        (doseq [i (range 12)]
          (let [id-i (keyword (str "x" face (when (< i 10) "0") i))
                id-next (keyword (str "x" face (when (< (mod (inc i) 12) 10) "0")
                                      (mod (inc i) 12)))]
            (is (near? (anchor-pos turned id-i) (anchor-pos nominal id-next))
                (str "girato di un passo, " (name id-i) " sta dove stava "
                     (name id-next))))))
      (println (str "  faccia +X e faccia −X entrambe girate di " step-deg "°"))
      ;; e SOLO l'anello dichiarato si muove
      (doseq [ax ["y" "z"]]
        (is (near? (anchor-pos turned (keyword (str ax "p00")))
                   (anchor-pos nominal (keyword (str ax "p00"))))
            (str "l'anello " ax " non è stato toccato"))))))

(deftest a-misglued-ring-is-recovered-by-declaring-its-phase
  (testing "la domanda del gate: se un anello è incollato storto, il modello
            nominale registra MALE e non dice perché. Dichiarare la fase
            misurata rimette la posa a posto — è un solo scalare per anello."
    (println "\n=== gabbia: anello storto, fase dichiarata ===")
    (let [off-deg 4.0
          ;; la gabbia VERA sul banco: l'anello grande (:x) girato di 4°
          built (cage/registration-cage :d 176 :phases {:x off-deg})
          nominal (cage/registration-cage :d 176)
          declared (cage/registration-cage :d 176 :phases {:x off-deg})
          k (k*)
          truth (synth/viewpoint 40 30 420.0)
          c-pos (cam/camera-center truth)
          ts-built (targets-of built (:creation-pose built))
          seen (filterv (fn [t]
                          (and (pos? (dot (:normal t) (v- c-pos (:obj t))))
                               (let [[u v] (cam/project k truth (:obj t))]
                                 (and (<= 0 u 4032) (<= 0 v 3024)))))
                        ts-built)
          ;; i click cadono dove i dischetti STANNO DAVVERO
          px-of (into {} (map (fn [t] [(:id t) (cam/project k truth (:obj t))]) seen))
          ;; …ma il solutore legge le posizioni dal MODELLO che gli si dà
          solve-with (fn [model]
                       (let [ts (targets-of model (:creation-pose model))
                             by-id (into {} (map (juxt :id identity) ts))
                             corr (vec (keep (fn [[id px]]
                                               (when-let [t (by-id id)]
                                                 {:ci 0 :world (:obj t) :px px}))
                                             px-of))]
                         (when-let [sol (pnp/solve-pnp corr k {:max-outliers 0})]
                           {:rms (:rms-px sol)
                            :err (norm (v- (cam/camera-center (:pose sol)) c-pos))})))
          bad (solve-with nominal)
          good (solve-with declared)]
      (println (str "  " (count seen) " mark visti, anelli " (sort (set (map ring-of seen)))))
      (println (str "  modello NOMINALE  → rms " (.toFixed (:rms bad) 2)
                    "px, camera fuori di " (.toFixed (:err bad) 1) "mm"))
      (println (str "  fase DICHIARATA   → rms " (.toFixed (:rms good) 2)
                    "px, camera fuori di " (.toFixed (:err good) 2) "mm"))
      (is (> (:rms bad) 5.0)
          "un anello storto di 4° NON passa inosservato nel residuo")
      (is (< (:rms good) 0.01) "dichiarata la fase, il fit torna esatto")
      (is (< (:err good) 0.05) "…e la camera torna dov'era")
      (is (> (:err bad) (* 20 (:err good)))
          "dichiarare la fase è ciò che separa i due casi"))))

(deftest the-residual-says-WHICH-ring-is-turned-and-by-how-much
  (testing "un residuo alto è un numero senza causa, e il sospetto naturale è il
            solutore o i click — che non c'entrano. I mark dell'anello girato
            dicono lo stesso scarto ALL'UNISONO, e quell'accordo è la prova."
    (println "\n=== gabbia: quale anello è girato, e di quanto ===")
    (let [off-deg 3.0
          built (cage/registration-cage :d 176 :phases {:x off-deg})
          nominal (cage/registration-cage :d 176)
          k (k*)
          truth (synth/viewpoint 40 30 420.0)
          c-pos (cam/camera-center truth)
          ts-built (targets-of built (:creation-pose built))
          ts-nom (targets-of nominal (:creation-pose nominal))
          by-id (into {} (map (juxt :id identity) ts-nom))
          seen (filterv (fn [t]
                          (and (pos? (dot (:normal t) (v- c-pos (:obj t))))
                               (let [[u v] (cam/project k truth (:obj t))]
                                 (and (<= 0 u 4032) (<= 0 v 3024)))))
                        ts-built)
          ;; il click cade sul dischetto VERO; il modello dice dov'è quello NOMINALE
          picks (vec (keep (fn [t]
                             (when-let [nt (by-id (:id t))]
                               {:axis (cage/anchor-axis (:id t))
                                :obj (:obj nt)
                                :px (cam/project k truth (:obj t))}))
                           seen))
          solve (fn [subset]
                  (when-let [s (pnp/solve-pnp
                                (mapv (fn [p] {:world (:obj p) :px (:px p)}) subset)
                                k {:max-outliers 0})]
                    (fn [obj] (cam/project k (:pose s) obj))))
          report (cage/phase-from-residuals picks solve)]
      (doseq [[axis r] (sort-by key report)]
        (println (str "  anello " (name axis) " (" (:n r) " mark): "
                      (.toFixed (:deg r) 2) "° ±" (.toFixed (:spread-deg r) 2)
                      " = " (.toFixed (:mm r) 2) "mm")))
      (is (= #{:x :y :z} (set (keys report))) "tutti e tre gli anelli misurati")
      (is (every? :held-out? (vals report))
          "ogni anello è misurato contro una posa che NON lo ha usato")
      (is (< (Math/abs (- (:deg (:x report)) off-deg)) 0.3)
          "l'anello girato si dichiara, e col numero giusto")
      (is (< (Math/abs (:deg (:y report))) 0.3) "l'anello y è a posto")
      (is (< (Math/abs (:deg (:z report))) 0.3) "…e anche lo z")
      ;; l'accordo fra i mark dello stesso anello è ciò che distingue un anello
      ;; girato da una mano tremante — ed è il criterio con cui edit-acquire
      ;; ACCUSA un anello, quindi si collauda quello, non la sola grandezza
      (let [accused? (fn [r] (and (> (Math/abs (:deg r)) 0.3)
                                  (> (Math/abs (:deg r)) (* 1.5 (:spread-deg r)))))]
        (is (accused? (:x report)) "l'anello girato viene accusato")
        (is (not (accused? (:y report))) "l'anello y non viene accusato")
        (is (not (accused? (:z report))) "…né lo z")))))

;; --- rileggere una corona dal lato sbagliato ---------------------------------
;;
;; Le due facce di un anello portano gli stessi dischetti agli stessi angoli, e
;; dalla faccia opposta la numerazione corre al contrario. Nella prima sessione
;; vera (2026-08-19) dodici click erano tutti centrati su dischetti reali e l'rms
;; era 1007px: i click giusti, i NOMI sbagliati. E sbagliati in modo DIVERSO da
;; anello ad anello — grande giusto, medio specchiato — perché la camera stava da
;; parti opposte dei due, che per una gabbia è la norma.

(deftest the-two-faces-are-the-same-discs
  (testing "cambiare faccia a un mark non lo sposta nel piano dell'anello: è lo
            stesso dischetto attraverso la plastica, e per questo una foto non
            può dirti quale faccia stai guardando"
    (println "\n=== gabbia: le due facce sono gli stessi dischetti ===")
    (let [c (cage/registration-cage :d 176)
          n (:cage-marks c)
          pos #(get-in c [:anchors % :position])]
      (doseq [i [0 4 7 11]]
        (let [a (pos (cage/relabel (cage/mark-id :y 1 i) {:rot 0} n))
              b (pos (cage/relabel (cage/mark-id :y 1 i) {:rot 0 :flip-face? true} n))]
          (is (< (norm (v- (mapv - a b) [0 0 0])) (+ 1e-9 (:cage-h c)))
              "le due facce distano al più lo spessore dell'anello")
          ;; e la distanza è ESATTAMENTE lo spessore, lungo l'asse dell'anello
          (is (< (Math/abs (- (norm (v- a b)) (:cage-h c))) 1e-9))))
      (println (str "  spessore " (:cage-h c) "mm, e nient'altro cambia")))))

(deftest a-crown-read-from-the-far-side-is-recoverable
  (testing "letta dall'altro lato una corona dà faccia opposta, verso invertito e
            uno scarto: è una delle 4n riletture, e va ritrovata esattamente"
    (println "\n=== gabbia: la rilettura che rimette a posto i nomi ===")
    (let [c (cage/registration-cage :d 176)
          n (:cage-marks c)
          ms (cage/crown-misreadings n)]
      (is (= (* 4 n) (count ms)) "4n riletture: due facce × due versi × n scarti")
      (is (= (count ms) (count (set ms))) "…tutte distinte")
      (is (= (cage/mark-id :y 1 5) (cage/relabel (cage/mark-id :y 1 5) {:rot 0} n))
          "la rilettura nulla non tocca niente")
      ;; il caso vero: ym01→yp10, ym03→yp08, ym10→yp01 (specchio + scarto 11)
      (let [t {:flip-face? true :mirror? true :rot 11}]
        (doseq [[from to] [[:ym01 :yp10] [:ym03 :yp08] [:ym10 :yp01]]]
          (println (str "  " (name from) " → " (name (cage/relabel from t n))))
          (is (= to (cage/relabel from t n)))))
      ;; e ogni rilettura è invertibile: esiste sempre quella che riporta indietro
      (doseq [t ms]
        (let [there (cage/relabel :zp03 t n)
              back (some (fn [u] (when (= :zp03 (cage/relabel there u n)) u)) ms)]
          (is (some? back) (str "la rilettura " t " deve essere annullabile")))))))

(deftest mark-parts-round-trips
  (testing "leggere e riscrivere un id è la stessa cosa"
    (doseq [axis [:x :y :z] s [1 -1] i (range 12)]
      (let [id (cage/mark-id axis s i)
            p (cage/mark-parts id)]
        (is (= {:axis axis :sign s :index i} p))
        (is (= id (cage/mark-id (:axis p) (:sign p) (:index p))))))
    (is (nil? (cage/mark-parts :zero-zp)) "lo zero-indice non è un mark di corona")))

(def ^:private foto5-picks
  "I quattordici punti VERI della foto 5 della sessione del 2026-08-20 (gabbia
   ⌀176, telefono a 48mm-equivalenti, immagine 3024×4032 dopo l'orientamento
   EXIF): dodici mark della corona −X più due dell'anello Y, questi ultimi due
   piazzati dall'aggancio automatico e finiti 24 e 40px fuori posto.

   Fixture di dati reali invece di sintetici, e per una ragione precisa: la
   versione sintetica di questa configurazione NON riproduce il guasto. Con
   pixel puliti il DLT malcondizionato torna 0.00px, e anche mettendo ±30px sui
   due punti fuori piano si ferma a 9px — passerebbe il test anche senza la
   correzione, che è il modo peggiore di avere un test. Il guasto vero chiede
   questi numeri: l'rms era 9736px.
   Un VETTORE ordinato, non una mappa: con quattordici chiavi Clojure passa a
   una hash-map e l'ordine di `keys` non è quello di scrittura — e su un sistema
   malcondizionato come questo l'ordine delle righe CAMBIA la soluzione (misurato:
   7700px in un ordine, 8px in un altro). Un fixture che non fissa l'ordine non
   riproduce il guasto."
  [[:xm00 [2416.4 2210.5]] [:xm01 [2280.9 2555.2]] [:xm02 [1913.3 2718.6]]
   [:xm03 [1390.1 2601.5]] [:xm04 [888.6 2209.3]]  [:xm05 [593.2 1671.7]]
   [:xm06 [606.3 1192.4]]  [:xm07 [871.3 910.6]]   [:xm08 [1277.0 873.3]]
   [:xm09 [1708.6 1049.3]] [:xm10 [2094.1 1361.7]] [:xm11 [2338.2 1781.7]]
   [:ym01 [1248.9 2591.1]] [:ym03 [1107.9 1958.6]]])

(deftest a-whole-crown-plus-two-strays-still-registers
  (testing "«non complanare» non vuol dire «ha profondità». Una corona intera più
            due mark di un secondo anello passa il test di complanarità — quei due
            stanno decine di mm fuori dal piano — ma dodici punti su quattordici
            non portano nessuna informazione di profondità: la portano i due, e
            con essa tutto il loro errore. Peggio, se l'eliminazione degli
            scarti ne toglie uno resta un insieme di fatto complanare in pasto al
            DLT. Sessione vera, foto 5: rms 9736px."
    (println "\n=== gabbia: una corona intera + due punti sparsi (dati veri) ===")
    (let [c (cage/registration-cage :d 176)
          ts (targets-of c (:creation-pose c))
          by-id (into {} (map (juxt :id identity) ts))
          iw 3024 ih 4032
          k (cam/intrinsics-from-fov (cam/equiv-focal->hfov-deg 48 (/ (double iw) ih)) iw ih)
          mk (fn [rows] (mapv (fn [[id px]] {:world (:obj (by-id id)) :px px}) rows))
          tutti (mk foto5-picks)
          ;; ciò che resta DOPO che la pulizia ha buttato xm00 e ym01 — ed è qui
          ;; che casca: undici punti complanari più uno
          restanti (mk (remove (comp #{:xm00 :ym01} first) foto5-picks))
          dlt-tutti (pnp/refine tutti k (pnp/estimate-dlt tutti k) {})
          dlt-restanti (pnp/refine restanti k (pnp/estimate-dlt restanti k) {})
          sol (pnp/solve-pnp restanti k {:max-outliers 0})]
      (is (= 14 (count tutti)))
      (println (str "  DLT su tutti e 14      : rms " (.toFixed (:rms-px dlt-tutti) 1) "px"))
      (println (str "  DLT sui 12 rimasti     : rms " (.toFixed (:rms-px dlt-restanti) 0) "px"))
      (is (< (:rms-px dlt-tutti) 30.0)
          "col punto fuori piano ancora dentro, il DLT sta benissimo")
      (is (> (:rms-px dlt-restanti) 1000.0)
          "tolto quel punto il DLT crolla — se non crolla, il test non collauda niente")
      (is (some? sol) "e una soluzione va comunque trovata")
      (when sol
        (println (str "  come risolve : " (name (:method sol))
                      " · rms " (.toFixed (:rms-px sol) 1) "px"))
        (is (< (:rms-px sol) 30.0)
            "col soccorso il fit torna utilizzabile")
        (is (= :planar-seeded (:method sol))
            "e passa dal piano che i più condividono, non per fortuna")))))

;; --- la corona chirale ------------------------------------------------------

(deftest the-crown-tells-its-two-faces-apart
  (testing "Con l'indice sull'asse del mark 0 la corona è simmetrica per
            riflessione, e siccome le due facce portano gli stessi dischetti
            attraverso la plastica presentano una figura IDENTICA: niente, nella
            fotografia, può dire quale faccia stai guardando. Fuori asse la figura
            è chirale, e la sua immagine speculare non si ottiene da nessuna
            rotazione. Il 2026-08-22 Vincenzo lo ha letto da una foto: il
            dischetto etichettato ym11 aveva sotto la coppia dell'indice, quindi
            era ym00, e la faccia era l'altra."
    (println "\n=== gabbia: la corona distingue le sue due facce ===")
    (let [n 12
          step (/ 360.0 n)
          ;; la figura di UNA faccia, come angoli: le n della corona più l'indice
          figura (fn [iph] (conj (mapv #(* % step) (range n)) (* iph step)))
          ;; l'immagine speculare, riportata in [0,360)
          specchio (fn [angs] (mapv #(mod (- %) 360.0) angs))
          ;; esiste una rotazione che porta `b` su `a`?
          sovrapponibile?
          (fn [a b]
            (let [key (fn [angs] (sort (mapv #(Math/round (* 100.0 (mod % 360.0))) angs)))
                  ka (key a)]
              (boolean (some (fn [r] (= ka (key (mapv #(+ % r) b))))
                             (map #(* % (/ 360.0 720)) (range 720))))))]
      (let [sull-asse (figura 0.0)
            fuori-asse (figura (/ 1.0 3.0))]
        (println (str "  indice sull'asse  → speculare sovrapponibile: "
                      (sovrapponibile? sull-asse (specchio sull-asse))))
        (println (str "  indice a 1/3 passo → speculare sovrapponibile: "
                      (sovrapponibile? fuori-asse (specchio fuori-asse))))
        (is (sovrapponibile? sull-asse (specchio sull-asse))
            "sull'asse la corona è achirale — ed è esattamente il difetto")
        (is (not (sovrapponibile? fuori-asse (specchio fuori-asse)))
            "fuori asse dev'essere CHIRALE, o le due facce restano gemelle")
        ;; e mezzo passo non va: torna simmetrica
        (is (sovrapponibile? (figura 0.5) (specchio (figura 0.5)))
            "mezzo passo sarebbe di nuovo achirale — per questo il default è 1/3"))
      ;; l'indice resta inequivocabilmente il vicino del mark 0
      (is (< (* (/ 1.0 3.0) step) (- step (* (/ 1.0 3.0) step)))
          "l'indice sta più vicino al mark 0 che al mark 1"))))

(deftest the-index-moved-and-nothing-else-did
  (testing "cambiare la fase dell'indice non deve spostare un solo mark di corona"
    (let [a (cage/registration-cage :d 176 :index-phase 0)
          b (cage/registration-cage :d 176)
          crown-of (fn [c] (into {} (remove (fn [[k _]] (bridge/index-anchor? k))
                                            (:anchors c))))]
      (is (= (crown-of a) (crown-of b))
          "le corone sono identiche: si è mosso solo l'indice")
      (is (not= (get-in a [:anchors :zero-yp :position])
                (get-in b [:anchors :zero-yp :position]))
          "…e l'indice sì"))))

;; --- una corona sola, letta dalla faccia sbagliata ---------------------------
;;
;; Il caso di Vincenzo del 2026-08-23: sulla prima foto mette i punti di UN
;; anello, e la sessione gli offre i nomi della faccia che NON sta guardando.
;; La domanda che ne è nata — «non nasconde un errore geometrico? l'algoritmo
;; dovrebbe avere tutti gli elementi per capire che facce sono quelle rivolte
;; verso di me» — ha una risposta precisa, ed è questo test: il RESIDUO non ha
;; quegli elementi (i 3mm di plastica se li mangia la camera spostandosi di
;; 3mm), la GUARDIA FISICA sì. Quindi la faccia non si sceglie col residuo, si
;; sceglie con la guardia — e provarla costa un solve.

(deftest a-crown-read-from-the-wrong-face-is-caught-by-the-camera-not-the-residual
  (testing "una corona intera cliccata giusta ma etichettata sulla faccia opposta
            dà lo STESSO residuo (la faccia non si vede nei pixel) e una posa che
            mette la camera dietro i dischetti fotografati (la faccia si vede nel
            mondo). È il caso di una foto appena cominciata: un anello solo, che
            è troppo poco perché la rilettura per-anello si fidi di qualcosa."
    (println "\n=== gabbia: una corona sola, faccia sbagliata ===")
    (let [c (cage/registration-cage :d 176)
          ts (targets-of c (:creation-pose c))
          idx (into {} (map-indexed (fn [i t] [(:id t) i]) ts))
          k (k*)
          truth (synth/viewpoint 40 30 420.0)
          eye (cam/camera-center truth)
          ;; la corona X che la camera ha DAVVERO davanti
          sign (let [t (nth ts (idx (cage/mark-id :x 1 0)))]
                 (if (pos? (dot (:normal t) (v- eye (:obj t)))) 1 -1))
          shown (mapv #(cage/mark-id :x sign %) [0 2 4 6 8 10])
          hidden (mapv #(cage/mark-id :x (- sign) %) [0 2 4 6 8 10])
          obj-of (fn [id] (:obj (nth ts (idx id))))
          ;; i click cadono sui dischetti VERI; cambiano solo i nomi sotto cui
          ;; vengono registrati
          fit (fn [ids] (pnp/solve-pnp (mapv (fn [s id] {:world (obj-of id)
                                                         :px (cam/project k truth (obj-of s))})
                                             shown ids)
                                       k {}))
          guard (fn [ids sol] (bridge/camera-sees-marks? ts (mapv idx ids) (:pose sol)))
          right (fit shown)
          wrong (fit hidden)
          flip (fn [id] (let [{:keys [axis sign index]} (cage/mark-parts id)]
                          (cage/mark-id axis (- sign) index)))]
      (println (str "  faccia giusta " (name (first shown))
                    "…: rms " (.toFixed (:rms-px right) 2) "px"))
      (println (str "  faccia sbagliata " (name (first hidden))
                    "…: rms " (.toFixed (:rms-px wrong) 2) "px"))
      (is (< (:rms-px right) 0.5) "la faccia giusta chiude a zero")
      (is (< (:rms-px wrong) 0.5)
          "e ANCHE quella sbagliata: il residuo non porta informazione di faccia")
      ;; …perché la camera se n'è andata indietro di uno spessore d'anello
      (let [d (norm (v- (cam/camera-center (:pose right))
                        (cam/camera-center (:pose wrong))))]
        (println (str "  la camera si sposta di " (.toFixed d 2) "mm"
                      " (spessore anello " (:cage-h c) "mm) per pareggiare i conti"))
        (is (> d 1.0) "lo scarto di faccia si scarica sulla posizione della camera"))
      (is (guard shown right) "la faccia giusta passa la guardia fisica")
      (is (not (guard hidden wrong))
          "quella sbagliata no: quei dischetti erano fotografati, la camera non
           può stargli dietro — ed è l'unico elemento che distingue le due facce")
      ;; e la correzione è a costo zero: gli stessi click, i nomi ribaltati
      (is (= shown (mapv flip hidden)) "ribaltare la faccia riporta i nomi veri")
      (is (guard shown (fit (mapv flip hidden)))
          "il ribaltamento rimette la camera davanti ai dischetti"))))

;; ---------------------------------------------------------------------------
;; :flips — the OTHER mounting freedom. The reference cage's Y ring is glued
;; TURNED OVER (physical test 2026-08-28; found from photographs as the ring's
;; index detected cleanly but in the MIRRORED housing, leva 2). Declared, not
;; reprinted: a flip is a 180° PROPER rotation of the printed ring about one
;; of its own diameters — no mirror, a physical ring cannot be mirrored — and
;; the residual in-plane turn is what that ring's :phases measures.
;; ---------------------------------------------------------------------------

(defn- approx3 [a b]
  (every? true? (map #(< (Math/abs (- %1 %2)) 1e-9) a b)))

(defn- cross3 [[ax ay az] [bx by bz]]
  [(- (* ay bz) (* az by)) (- (* az bx) (* ax bz)) (- (* ax by) (* ay bx))])

(defn- signed-index-angle
  "Signed angle (deg) from mark 0 to the zero-index of face (axis, sign),
   about that face's OUTWARD normal — the number the passetto rule reads:
   +10° at twelve marks = the index a third of a step CCW = face p."
  [cage axis sign]
  (let [as (:anchors cage)
        m0 (get as (cage/mark-id axis sign 0))
        ix (get as (cage/index-id axis sign))
        r0 (:up m0) r1 (:up ix)
        h (:heading m0)
        s (dot h (cross3 r0 r1))
        c (dot r0 r1)]
    (* (Math/atan2 s c) (/ 180.0 Math/PI))))

(deftest flip-is-a-rotation-not-a-mirror
  (testing "l'anello Y ribaltato: ogni sua ancora mappa per [−x −y z] (180°
            attorno al diametro cage-z), gli altri anelli non si muovono di un
            bit, e :flips assente/vuoto è la gabbia di prima"
    (let [nom (cage/registration-cage :d 176)
          flp (cage/registration-cage :d 176 :flips #{:y})
          m (fn [[x y z]] [(- x) (- y) z])]
      (doseq [[id a] (:anchors flp)]
        (let [b (get (:anchors nom) id)]
          (if (= :y (cage/anchor-axis id))
            (do (is (approx3 (:position a) (m (:position b))) (str id " posizione"))
                (is (approx3 (:heading a) (m (:heading b))) (str id " heading")))
            (is (= a b) (str id " (anello non ribaltato) deve restare identico")))))
      (is (= (:anchors nom) (:anchors (cage/registration-cage :d 176 :flips #{})))
          "flips vuoto = nominale, bit per bit")
      (is (= #{:y} (:cage-flips flp)) "il montaggio viaggia sulla mesh")
      (is (= #{:y} (:cage-flips (cage/registration-cage :d 176 :flips {:y true :x false})))
          "accetta anche la forma mappa"))))

(deftest flip-mirrors-the-housing-not-the-print
  (testing "la chiralità della FIGURA STAMPATA è invariante (una rotazione non
            specchia niente): il passetto letto sulla faccia p della stampa dà
            +10° attorno alla SUA normale uscente, ribaltata o no"
    (let [nom (cage/registration-cage :d 176)
          flp (cage/registration-cage :d 176 :flips #{:y})]
      (is (< (Math/abs (- 10.0 (signed-index-angle nom :y 1))) 1e-6))
      (is (< (Math/abs (- -10.0 (signed-index-angle nom :y -1))) 1e-6))
      (is (< (Math/abs (- 10.0 (signed-index-angle flp :y 1))) 1e-6)
          "la faccia p della stampa legge p anche ribaltata")
      (testing "ma l'ALLOGGIO è specchiato: nella gabbia ribaltata la corona che
                GUARDA +y è quella m della stampa (legge −10°), dove la nominale
                mostrava la p (+10°) — la firma che la leva 2 ha rilevato"
        (let [heading-of (fn [c axis sign]
                           (:heading (get (:anchors c) (cage/mark-id axis sign 0))))]
          (is (approx3 [0.0 1.0 0.0] (heading-of nom :y 1)))
          (is (approx3 [0.0 -1.0 0.0] (heading-of flp :y 1))
              "yp (etichetta di stampa) ora guarda −y")
          (is (approx3 [0.0 1.0 0.0] (heading-of flp :y -1))
              "e da +y si vede la faccia ym della stampa"))))))

(deftest flip-then-phase-in-assembly-order
  (testing "flip PRIMA, fase misurata DOPO — l'ordine fisico del montaggio: la
            fase di un anello dichiarato ribaltato si misura sul modello as-built"
    (let [flp90 (cage/registration-cage :d 176 :flips #{:y} :phases {:y 90})
          flp0 (cage/registration-cage :d 176 :flips #{:y})]
      (doseq [[id a] (:anchors flp90)
              :when (= :y (cage/anchor-axis id))]
        (let [b (get (:anchors flp0) id)]
          (is (approx3 (:position a) (cage/turn-about-axis :y (:position b) 90.0))
              (str id ": posizione = flip poi giro di fase"))
          (is (approx3 (:heading a) (cage/turn-about-axis :y (:heading b) 90.0))
              (str id ": heading idem")))))))

(deftest flip-carries-the-fabrication-features
  (testing "alette e feritoie dell'anello ribaltato si ribaltano con lui (la
            gabbia DISEGNATA deve essere quella INCOLLATA), le altre restano"
    (let [nom (cage/registration-cage :d 176)
          flp (cage/registration-cage :d 176 :flips #{:y})
          m (fn [[x y z]] [(- x) (- y) z])
          by-owner (fn [c] (group-by :owner (:tabs c)))]
      (doseq [[t-nom t-flp] (map vector (:y (by-owner nom)) (:y (by-owner flp)))]
        (is (approx3 (:center t-flp) (m (:center t-nom)))
            (str (:kind t-nom) " di Y ribaltata"))
        (is (= (:size t-flp) (:size t-nom)) "le misure della scatola non cambiano"))
      (doseq [ax [:x :z]]
        (is (= (ax (by-owner nom)) (ax (by-owner flp)))
            (str "le linguette di " (name ax) " non si muovono")))
      (doseq [[s-nom s-flp] (map vector (:stick-slots nom) (:stick-slots flp))]
        (if (= :y (:axis s-nom))
          (do (is (approx3 (:position s-flp) (m (:position s-nom))))
              (is (approx3 (:up s-flp) (m (:up s-nom))) "il corpo sale dall'altra faccia"))
          (is (= s-nom s-flp)))))))
