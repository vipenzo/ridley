(ns ridley.photogrammetry.match-cage
  "Identity for a registration CAGE: which mark is which, and therefore where the
   camera is. The cage's answer to what `match-plate` does for a plate — and a
   different answer, because the two targets fail in different ways.

   THE PROBLEM, measured and not supposed. A crown of twelve evenly spaced marks
   is invariant under rotation, and both faces of a ring carry the same discs
   through the plastic, so from the far side the numbering runs backwards. All 48
   re-readings of one crown therefore fit that crown EQUALLY WELL — measured on
   Vincenzo's photograph, 5.3-5.4px for every one of them. A crown cannot say
   which of its marks is mark zero. Neither can the user: the predictions they
   would have to judge by are 150px out, and three clicks in six landed on the
   wrong ring entirely.

   THE ANSWER is not more clicking, and it is not a wider search either. Sampling
   the neighbourhood of the one-ring pose — the obvious plan — cannot work,
   because there is no neighbourhood: LM started from 91 poses spanning ±150°
   about nine axes returns ONE minimum, and jittering around it (900 poses a
   round, six rounds, the ring's own rms held under 8px) moves the known marks of
   the other rings from 92-157px to 97-162px. No pose that fits the clicked ring
   explains the rest of the cage.

   What breaks the symmetry is the REST OF THE CAGE. Score each of the 48 readings
   not by how well it fits the ring it came from — they are indistinguishable
   there, by construction — but by how much of the WHOLE cage its pose puts on
   the marks the detector found. Measured on the same photograph: `rot 3` puts the
   four known inner-ring marks at 17-23px where the user's own reading leaves them
   at 92-157px. The 48 collapse to 4, which are 2 physical poses (a reading and
   the same reading turned 180° about the ring axis are the same crown; the other
   two are that pair read from the far face), and between two faces the arbiter is
   physical, not numerical: those discs were photographed, so the camera was in
   front of each — `bridge/camera-sees-marks?`.

   This is why the detector had to be built first. Before it there was nothing to
   score a reading against.

   Pure: takes candidate pixels, the user's picks, and the proxy's targets, and
   returns a reading, a pose and a full set of correspondences. Knows nothing
   about the editor, the session, or how the candidates were found."
  (:require [ridley.photogrammetry.cage :as cage]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.pnp :as pnp]))

(def default-opts
  "`:tol-px` is the one number worth arguing about, and it is set by the CAGE, not
   by the camera. A reading is scored on marks the clicked ring cannot constrain,
   so the prediction carries the model's whole error: each ring's glued-in rotation
   about its own axis (measured 1-2° on the reference cage) and, on that cage, a
   ring that is not quite planar — Vincenzo, looking at the part, 'di poco', which
   at the framing measured is ~1.2mm, about 20px. A tolerance tighter than that
   scores the RIGHT reading zero and the search returns nothing. It can afford to
   be loose because it is not measuring anything: neighbouring marks are 200-500px
   apart in these frames, so 26px cannot confuse two of them, and every pixel that
   ends up in the pose goes through `assign`'s mutual-nearest match and a full
   re-solve afterwards."
  {:tol-px 26.0        ; a prediction this close to a candidate counts as explained
   :min-picks 4        ; the planar homography's minimum
   :min-explained 6})  ; below this the reading has not been confirmed by anything

;; A cage's marks are HOLES: `xm00` and `xp00` are the same disc seen from the two
;; sides of the same ring, 3mm apart. Both project within a few px of the same
;; candidate, so a score that counted them both would count every disc twice and
;; reward the pose for the face the camera cannot see. Only front-facing targets
;; are ever scored or matched.
(defn- front-facing
  "The targets whose printed face is turned toward the camera under `pose`."
  [targets pose]
  (let [c (cam/camera-center pose)]
    (filterv (fn [{:keys [normal obj]}]
               (or (nil? normal)
                   (pos? (la/v-dot normal (la/v-sub c obj)))))
             targets)))

(defn- nearest-px [pts [u v]]
  (reduce (fn [b [cu cv]] (min b (Math/hypot (- cu u) (- cv v)))) js/Infinity pts))

(defn explained
  "How many of `targets` the pose puts within `tol-px` of a detected candidate —
   the score a reading is judged by. Only front-facing targets count."
  [targets candidates intrinsics pose tol-px]
  (count (for [{:keys [obj]} (front-facing targets pose)
               :let [px (cam/project intrinsics pose obj)]
               :when (and px (< (nearest-px candidates px) tol-px))]
           true)))

(defn- relabel-picks
  "`picks` (anchor-id → pixel) re-read under one of `cage/crown-misreadings`'
   entries, dropping anything the reading cannot map."
  [picks reading marks]
  (into {} (keep (fn [[id px]]
                   (when-let [id2 (cage/relabel id reading marks)] [id2 px]))
                 picks)))

(defn- corr-for [picks by-id]
  (vec (keep (fn [[id px]]
               (when-let [t (by-id id)] {:ci id :world (:obj t) :px px}))
             picks)))

(defn assign
  "Every mark the pose can account for, paired with the candidate it lands on:
   `[{:ci id :world obj :px [u v]} …]`.

   Matching is MUTUAL nearest within `tol-px` — a mark takes a candidate only if
   that candidate's own nearest mark is this one. One-sided nearest-neighbour
   would let two neighbouring marks both claim the one disc that happens to lie
   between them, which is exactly the failure that made the user's own clicks
   unusable near a ring crossing."
  [targets candidates intrinsics pose tol-px]
  (let [preds (vec (for [{:keys [id obj]} (front-facing targets pose)
                         :let [px (cam/project intrinsics pose obj)]
                         :when px]
                     {:id id :obj obj :px px}))
        nearest-of (fn [pts [u v]]
                     (when (seq pts)
                       (apply min-key (fn [p] (Math/hypot (- (first (:px p)) u)
                                                          (- (second (:px p)) v)))
                              pts)))]
    (vec (keep (fn [{:keys [id obj px] :as p}]
                 (let [[cu cv] px
                       c (when (seq candidates)
                           (apply min-key (fn [[u v]] (Math/hypot (- u cu) (- v cv)))
                                  candidates))]
                   (when (and c (< (Math/hypot (- (first c) cu) (- (second c) cv)) tol-px)
                              ;; …and that candidate's own nearest mark is this one
                              (= id (:id (nearest-of preds c))))
                     {:ci id :world obj :px (vec c)})))
               preds))))

(defn- score-reading
  "One reading tried: relabel, solve from the clicked ring alone, and ask the
   whole cage what it thinks."
  [picks targets by-id candidates intrinsics marks {:keys [tol-px min-picks]}]
  (fn [reading]
    (let [p2 (relabel-picks picks reading marks)
          corr (corr-for p2 by-id)]
      (when (>= (count corr) min-picks)
        (when-let [sol (pnp/solve-pnp corr intrinsics {})]
          {:reading reading
           :picks p2
           :pose (:pose sol)
           :rms-px (:rms-px sol)
           ;; which picks this reading could only fit by THROWING AWAY. Recorded
           ;; because one of them can be the zero-index, and a reading that must
           ;; discard the identity witness to score well is not winning the
           ;; argument — it is changing the subject (see the glued-ring check).
           :dropped (mapv :ci (:outliers sol))
           :explained (explained targets candidates intrinsics (:pose sol) tol-px)})))))

(defn- sees-its-own-picks?
  "The physical guard, applied to the marks the reading claims were clicked: those
   discs were photographed, so the camera was in front of each of them. A reading
   that puts it behind one is not unlikely, it is impossible. This is the arbiter
   between the last two readings, because the residual cannot be — measured on a
   cage, 0.00px with the right labels and 0.00px with the opposite face's, since
   the 3mm between the faces is absorbed by the camera moving 3mm."
  [{:keys [picks pose]} by-id]
  (let [c (cam/camera-center pose)]
    (every? (fn [id]
              (let [{:keys [normal obj]} (by-id id)]
                (or (nil? normal) (pos? (la/v-dot normal (la/v-sub c obj))))))
            (keys picks))))

(defn- picks-axis
  "The ring the picks are on — :x, :y or :z."
  [picks]
  (some (fn [id] (:axis (or (cage/mark-parts id) (cage/index-parts id)))) (keys picks)))

(defn- dropped-index?
  "Did this reading's solve throw away a zero-index pick?"
  [{:keys [dropped]}]
  (boolean (some cage/index-parts dropped)))

(defn- phase-probe
  "The check for a ring GLUED A WHOLE NUMBER OF STEPS from nominal — the one
   assembly error nothing else can see. `cage/phase-from-residuals` measures a
   phase only mod one step (a crown is symmetric under a full step), and the
   reading search absorbs whole steps into the labels; the only witness that
   separates 'labels out by k' from 'ring glued k steps round' is the ZERO-INDEX,
   and only when the caller clicked it.

   Under `pose` (solved on the clicked ring, index kept), count how many targets
   OFF that ring land on a candidate as-is, then again with those targets turned
   about the clicked ring's axis by −k steps for every k — which is where they
   would be if the clicked ring had been glued +k steps round. Returns
   {:steps k :deg d :gain [n0 nk]} when some k beats nominal by a margin that
   cannot be noise, else nil."
  [targets candidates intrinsics pose axis marks tol-px]
  (let [others (filterv #(not= axis (:axis (or (cage/mark-parts (:id %))
                                               (cage/index-parts (:id %)))))
                        targets)
        step-deg (/ 360.0 marks)
        count-at (fn [deg]
                   (explained (mapv #(update % :obj
                                             (fn [o] (cage/turn-about-axis axis o (- deg))))
                                    others)
                              candidates intrinsics pose tol-px))
        n0 (count-at 0.0)
        best (apply max-key second
                    (for [k (range 1 marks)] [k (count-at (* k step-deg))]))]
    (when (and (>= (second best) 6) (>= (- (second best) n0) 4))
      {:steps (first best) :gain [n0 (second best)]})))

(defn read-crown
  "Read the crown the user clicked, arbitrated by the rest of the cage.

   `picks`    anchor-id → [u v], the marks of ONE ring as the user named them
   `targets`  bridge/pnp-target-points for the cage ({:id :obj :normal …})
   `candidates` [[u v] …] from blob-detect/detect-blobs — the whole frame
   `marks`    marks per crown (12)

   Returns nil when nothing can be fit, otherwise

     {:reading {:rot :mirror? :flip-face?}   how the user's names were out
      :picks   {id px …}                     the same clicks, correctly named
      :pose :rms-px                          from the clicked ring alone
      :explained n                           marks of the WHOLE cage confirmed
      :corr [{:ci :world :px} …]             every mark the pose accounts for
      :full  {:pose :rms-px}                 re-solved over :corr — the answer
      :ties  [{:reading :explained} …]}      readings that scored the same

   `:ties` is not a wart to be hidden. A reading and the same reading turned 180°
   about the ring's axis put the camera in two different places and explain the
   cage equally well; the physical guard removes those it can, and what survives
   is a genuine ambiguity that another photograph — not a better score — settles."
  ([picks targets candidates intrinsics marks] (read-crown picks targets candidates intrinsics marks nil))
  ([picks targets candidates intrinsics marks opts]
   (let [{:keys [tol-px min-explained] :as opts} (merge default-opts opts)
         by-id (into {} (map (juxt :id identity) targets))
         scored (->> (cage/crown-misreadings marks)
                     (keep (score-reading picks targets by-id candidates
                                          intrinsics marks opts))
                     vec)]
     (when (seq scored)
       (let [best (apply max (map :explained scored))
             top (filterv #(= best (:explained %)) scored)
             ;; the physical guard, but never to the point of leaving nothing: if
             ;; it rejects every top reading the geometry is telling us something
             ;; the caller must hear, not something to paper over
             kept (let [k (filterv #(sees-its-own-picks? % by-id) top)]
                    (if (seq k) k top))
             winner (first (sort-by :rms-px kept))
             ;; THE ZERO-INDEX CANNOT BE OUTVOTED. A candidate-scored winner that
             ;; could only fit by throwing the clicked index away has not read the
             ;; crown better — it has silently deleted the one disc that pins the
             ;; numbering. When an index-keeping reading exists, IT is the answer,
             ;; and the winner's higher score becomes the diagnosis: the clicked
             ;; ring is GLUED that many steps round from nominal, which puts the
             ;; other rings exactly where the discarding reading claimed and the
             ;; index exactly where the keeping one does. Measured on Vincenzo's
             ;; cage (2026-08-24): candidates said rot 3, his clicked zero said
             ;; rot 0, and the part said `:phases {:x 90}` — both were right.
             keeper (when (dropped-index? winner)
                      (->> scored
                           (remove dropped-index?)
                           (filter #(some cage/index-parts (keys (:picks %))))
                           (sort-by (comp - :explained))
                           first))
             result (or keeper winner)
             axis (picks-axis picks)
             ;; k steps and (marks − k) steps are the same quarter turn seen from
             ;; the two senses — candidates cannot tell them apart (each crown is
             ;; symmetric under half a turn), only the FACES can. So the suspect is
             ;; reported canonically (the smaller of the two) and the sign is the
             ;; caller's to try — which the message says.
             canon (fn [k] (let [k (mod k marks)] (min k (- marks k))))
             suspect (some-> (if keeper
                               {:steps (- (:rot (:reading winner)) (:rot (:reading keeper)))
                                :gain [(:explained keeper) (:explained winner)]}
                               ;; index kept (or never clicked): probe for the glued
                               ;; ring directly — with few picks the discarding
                               ;; reading's pose is too loose to outscore, and the
                               ;; failure would stay silent
                               (when (and axis (some cage/index-parts (keys picks)))
                                 (phase-probe targets candidates intrinsics (:pose winner)
                                              axis marks tol-px)))
                             (as-> ps (let [k (canon (:steps ps))]
                                        (assoc ps :axis axis :steps k
                                               :deg (* k (/ 360.0 marks))))))
             corr (assign targets candidates intrinsics (:pose result) tol-px)
             full (when (>= (count corr) 6) (pnp/solve-pnp corr intrinsics {}))]
         (when (>= (:explained result) min-explained)
           (assoc result
                  :corr corr
                  :full (when full (select-keys full [:pose :rms-px]))
                  :guard-rejected (- (count top) (count kept))
                  :phase-suspect suspect
                  :ties (mapv #(select-keys % [:reading :explained :rms-px])
                              (remove #(= % result) kept)))))))))
