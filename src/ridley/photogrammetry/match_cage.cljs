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
            [ridley.photogrammetry.ellipse :as ellipse]
            [ridley.photogrammetry.match-plate :as mp]
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
   :min-explained 6    ; below this the reading has not been confirmed by anything
   ;; A reading whose pose puts a hand-clicked zero-index of ANOTHER ring further
   ;; than this from its click is contradicted by a fact, not outscored — see
   ;; :zero-picks in read-crown. Wide on purpose: the through-plastic twin sends
   ;; that zero to the reflected point of its ring, hundreds of px away, while
   ;; the true reading carries only the model's slop (~20-40px on a seed-ring
   ;; pose). Nothing in between exists to be confused with.
   :zero-veto-px 100.0})

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
   is a genuine ambiguity that another photograph — not a better score — settles.

   `:zero-picks` in opts — [{:axis :z :px [u v]} …], the zero-indices the user
   clicked BY HAND on rings other than the seed — is the arbiter that outranks
   the score. Measured on battiscopa1 (2026-08-28): a FULL ring Y seeded with 13
   clicks, zero included, still declared the tie and shipped the through-plastic
   twin — every disc of the other rings lands on a disc under both readings (the
   crowns are symmetric under half a turn), so candidate-counting cannot decide.
   What the twin cannot do is put the OTHER ring's zero where it was clicked: it
   sends that double disc to the reflected point of its ring, hundreds of px out.
   So each zero-pick VETOES the readings that contradict it (min over the two
   faces zero-…p/zero-…m, since the faces are the same dot through the plastic),
   within `:zero-veto-px`.

   One rule keeps the veto honest: a zero-pick that contradicts EVERY reading is
   evidence about the RING, not about the readings — that ring is mounted a whole
   number of steps round from the model (the phases are per-assembly: the cage
   opens at every part change), and its zero sits k steps from where the model
   looks. Such a pick is set aside (`:moot`) instead of enforced, and the k-step
   probe at solve time (`rescue-hand-zeros`) measures the k it points at.
   Returns `:zero-veto {:killed n :moot [axis …]}` when zero-picks were given."
  ([picks targets candidates intrinsics marks] (read-crown picks targets candidates intrinsics marks nil))
  ([picks targets candidates intrinsics marks opts]
   (let [{:keys [tol-px min-explained zero-veto-px] :as opts} (merge default-opts opts)
         by-id (into {} (map (juxt :id identity) targets))
         all-scored (->> (cage/crown-misreadings marks)
                         (keep (score-reading picks targets by-id candidates
                                              intrinsics marks opts))
                         vec)
         zero-picks (vec (remove #(= (picks-axis picks) (:axis %)) (:zero-picks opts)))
         zero-d (fn [pose {:keys [axis px]}]
                  (reduce min js/Infinity
                          (for [s [1 -1]
                                :let [t (by-id (cage/index-id axis s))
                                      p (when t (cam/project intrinsics pose (:obj t)))]
                                :when p]
                            (Math/hypot (- (nth p 0) (nth px 0))
                                        (- (nth p 1) (nth px 1))))))
         [scored zero-veto]
         (reduce (fn [[sc note] zp]
                   (let [ok (filterv #(<= (zero-d (:pose %) zp) zero-veto-px) sc)]
                     (if (seq ok)
                       [ok (update note :killed + (- (count sc) (count ok)))]
                       ;; contradicts every reading: the ring is the suspect, not
                       ;; the readings — set aside, never turned into a refusal
                       [sc (update note :moot conj (:axis zp))])))
                 [all-scored {:killed 0 :moot []}]
                 zero-picks)]
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
                  :zero-veto (when (seq zero-picks) zero-veto)
                  :ties (mapv #(select-keys % [:reading :explained :rms-px])
                              (remove #(= % result) kept)))))))))

;; ── the k-step re-reading: a hand-clicked zero is never just discarded ───────

(def zero-step-tol-px
  "How close (px) a whole-step turn of a ring must put its zero-index to the
   hand-clicked pixel before the ring is declared MOUNTED k steps round. The
   same figure as `:tol-px`, for the same reason: it only has to be smaller
   than the distance between two candidate positions, and consecutive steps of
   the zero are a whole step of arc apart — hundreds of px in these frames."
  26.0)

(defn rescue-hand-zeros
  "A hand-clicked zero-index is never discarded as an outlier without first
   trying the k-step re-reading of its ring.

   The failure this exists for was measured before it was written (battiscopa1,
   2026-08-27/28): a ring mounted a whole number of steps round from the model —
   and the phases ARE per-assembly, the cage opens at every part change — puts
   every DISC exactly on another disc's position, so the marks fit perfectly and
   the pose is right; the only witness that moved is the ZERO, which now sits k
   steps from where the model looks. The solve, doing its job, threw away the
   one pick that was telling the truth and registered the numbering blind.

   `sol` is a pnp/solve-pnp result over `correspondences` [{:ci :world :px} …].
   `hand-zero-axis` names the hand-clicked zero-indices: (fn [ci] -> ring axis,
   nil for everything else). `index-axis` does the same for EVERY zero-index
   claim, proposals included — a discovered turn moves the ring's zero for every
   claim on it, not only the hand's.

   For each outlier the hand swears by: find the whole-step turn k of its ring
   that puts the zero under the click (judged on the pose the OTHER picks
   agreed on), re-solve with that ring's zero claims turned by k, and accept
   only if the fit stays acceptable and the hand's zero is now an inlier.

   Returns {:sol sol' :corr corr' :phases {axis {:steps k :deg d}}} — the
   re-solve to apply and the mounting the photo just measured, the number
   `:phases` on `registration-cage` wants declared — or nil when no hand zero
   was outliered or no turn explains it."
  [sol correspondences intrinsics marks hand-zero-axis index-axis]
  (let [step (/ 360.0 marks)]
    (loop [sol sol corr correspondences phases {} tried #{}]
      (let [out (first (for [o (:outliers sol)
                             :let [axis (hand-zero-axis (:ci o))]
                             :when (and axis (not (tried axis)))]
                         (assoc o :axis axis)))]
        (if (nil? out)
          (when (seq phases) {:sol sol :corr corr :phases phases})
          (let [{:keys [axis world px]} out
                best (first (sort-by second
                                     (for [k (range 1 marks)
                                           :let [p (cam/project intrinsics (:pose sol)
                                                                (cage/turn-about-axis
                                                                 axis world (* k step)))]
                                           :when p]
                                       [k (Math/hypot (- (nth p 0) (nth px 0))
                                                      (- (nth p 1) (nth px 1)))])))
                k* (first best)
                corr' (when (and best (<= (second best) zero-step-tol-px))
                        (mapv (fn [c]
                                (if (= axis (index-axis (:ci c)))
                                  (assoc c :world (cage/turn-about-axis
                                                   axis (:world c) (* k* step)))
                                  c))
                              corr))
                sol' (when corr' (pnp/solve-pnp corr' intrinsics {}))
                ok? (and sol'
                         ;; the turn must BUY the fit, not talk its way in
                         (<= (:rms-px sol') (max (:rms-px sol) pnp/accept-rms-px))
                         (not-any? #(= axis (hand-zero-axis (:ci %))) (:outliers sol')))]
            (if ok?
              (recur sol' corr'
                     (assoc phases axis {:steps k* :deg (* k* step)})
                     (conj tried axis))
              ;; no whole-step turn explains this zero: leave it to the caller's
              ;; ordinary outlier reporting, and go on to the next ring's
              (recur sol corr phases (conj tried axis)))))))))

;; ── zero-click: the machine produces the seed ────────────────────────────────
;;
;; Vincenzo, at the end of the recovery week (2026-08-27): «se non riusciamo ad
;; avere la registrazione automatica delle foto sarà tutto inutile». He is
;; right, and the pieces already exist: the PLATE finds rings among the
;; detector's candidates with no identity at all (crown-ring-hypotheses) and
;; identifies one crown against its zero-index (assign-marks); the CAGE knows
;; how to take a seed on one ring and let the rest of the cage arbitrate its
;; reading (read-crown). Zero-click is those three in a row — the seed the user
;; used to click is produced by the machine instead.

(defn ring-faces
  "`targets` grouped into the six (axis, face) crown families a cage carries:
   [{:axis :sign :marks [{:id :obj} ×12, index order] :zero-id :zero-obj
     :face-normal}]. Pure regrouping of what pnp-target-points already knows."
  [targets]
  (let [crowns (group-by (fn [t] (let [p (cage/mark-parts (:id t))]
                                   (when p [(:axis p) (:sign p)])))
                         targets)
        indices (into {} (keep (fn [t]
                                 (when-let [p (cage/index-parts (:id t))]
                                   [[(:axis p) (:sign p)] t]))
                               targets))]
    (vec (for [[[axis sign] ts] crowns
               :when (and axis (>= (count ts) 4))
               :let [zero (indices [axis sign])]
               :when zero]
           {:axis axis :sign sign
            :marks (vec (sort-by #(:index (cage/mark-parts (:id %))) ts))
            :zero-id (:id zero)
            :zero-obj (:obj zero)
            :face-normal (:normal zero)}))))

(defn auto-read
  "Read the cage from a frame with NO clicks at all: `candidates` from
   blob-detect, `targets` from pnp-target-points, `judge` a disc-presence test
   ((fn [px r] -> bool), blob/disc-at? over the photo). Returns
   {:pose :rms-px :corr :explained :seed {:axis :sign :crown-hits} :phase-suspect}
   or nil when no ring identifies — which on a frame worth keeping means it
   shows one ring badly or none well, and the remedy is a few degrees off-axis.

   A machine seed does NOT go through read-crown's 48-reading vote. The vote
   exists for HAND labels, which carry no evidence of their own; a machine seed
   is index-pinned and face-judged ON THE PIXELS by assign-marks — better
   evidence than the vote's candidate-counting, and the vote's ties (the
   through-plastic twin, irreducible from candidates on a one-ring frame) were
   measured killing correct seeds. Instead: take the seed's refined pose,
   collect every mark of the WHOLE cage it accounts for (mutual-nearest), solve
   on all of them, and hold the result to the session's own acceptance bar plus
   the physical guard and the glued-ring probe. Junk constellations die at those
   gates: they solve wide of the bar or claim discs the camera cannot see."
  ([candidates targets intrinsics judge marks] (auto-read candidates targets intrinsics judge marks nil))
  ([candidates targets intrinsics judge marks opts]
   (let [{:keys [disc-r min-seed-crown tol-px trace max-identify]
          :or {disc-r 1.25 min-seed-crown 8 max-identify 18
               tol-px (:tol-px default-opts)}} opts
         note! (fn [m] (when trace (swap! trace conj m)) nil)
         ;; Ring hypotheses with a floor of EIGHT inliers — a cost wall, not a
         ;; taste: identifying a k-point ring among 12 marks enumerates C(12,k)
         ;; cyclic candidates, 24 at k=12 but 11088 at k=7, and each face of
         ;; each hypothesis pays it. The first bench run at floor 6 took 40-57
         ;; seconds per frame, almost all of it on junk partial rings.
         hyps (ellipse/fit-inliers-ranked (vec candidates)
                                          {:iters 2000 :thr 0.04
                                           :min-inliers 8 :top-k 6})
         ;; NO competitive-size filter — the plate's rule, and wrong here. On a
         ;; plate the crown is the biggest ring in the picture; on a cage a junk
         ;; conic threading three interleaved crowns gathers MORE inliers than
         ;; any true crown (measured on the synthetic scene: 13 and 16 against
         ;; the crowns' 12, and the ≥biggest−3 filter deleted every true ring
         ;; in plain sight). Regularity ranks, the budget caps.
         ;; …ranked by ANGULAR REGULARITY, not by inlier count. A crown is twelve
         ;; near-evenly spaced points; a junk conic through the stragglers of
         ;; three interleaved rings is not — but it often gathers MORE inliers (a
         ;; conic has five degrees of freedom and the whole frame to spend them
         ;; on), and ranked by count it burned the whole identity budget before
         ;; the true ring was ever tried (measured: the synthetic scene, three
         ;; clean rings in plain sight, REFUSED). The regularity score is the
         ;; identity check's signature in cheap form: coefficient of variation
         ;; of the angular gaps about the inliers' own centroid.
         regularity (fn [hyp]
                      (let [pts (mapv #(nth candidates %) hyp)
                            n (count pts)
                            cx (/ (reduce + (map first pts)) n)
                            cy (/ (reduce + (map second pts)) n)
                            angs (vec (sort (map (fn [[u v]] (Math/atan2 (- v cy) (- u cx))) pts)))
                            gaps (mapv (fn [i]
                                         (let [a (nth angs i)
                                               b (nth angs (mod (inc i) n))
                                               g (- b a)]
                                           (if (neg? g) (+ g (* 2 Math/PI)) g)))
                                       (range n))
                            mean (/ (* 2 Math/PI) n)
                            var (/ (reduce + (map #(let [d (- % mean)] (* d d)) gaps)) n)]
                        (/ (Math/sqrt var) mean)))
         hyps (vec (sort-by regularity hyps))
         faces (ring-faces targets)
         by-id (into {} (map (juxt :id identity) targets))
         budget (volatile! (inc max-identify))]
     (note! {:stage :hyps :sizes (mapv count hyps) :candidates (count candidates)})
     ;; BEST accepted wins — never the first. The same 11 candidate discs
     ;; identify as ring X AND as ring Y (same circle, different radius: the
     ;; pose absorbs the scale into distance), both at clean rms, both past the
     ;; guard — and only how much of the REST of the cage each pose explains
     ;; tells them apart (measured on foto 1: 13 vs 12, and first-wins shipped
     ;; the 12, putting the camera 697mm out). The identity budget caps the
     ;; cost instead: the search stops grinding junk after :max-identify
     ;; attempts, which took refusals from 40-65s to 10-25.
     (->> (for [hyp hyps
                face faces
                :let [pts (mapv #(nth candidates %) (take (count (:marks face)) hyp))
                      res (when (and (>= (count pts) 4)
                                     (pos? (vswap! budget dec)))
                            (mp/assign-marks pts (:marks face) (:zero-obj face)
                                             intrinsics judge
                                             {:disc-r disc-r
                                              :face-normal (:face-normal face)}))
                      _ (note! {:stage :seed :hyp (count hyp)
                                :face [(:axis face) (:sign face)]
                                :crown-hits (:crown-hits res)
                                :zero-hit? (:zero-hit? res)})]
                :when (and res (:zero-hit? res) (>= (:crown-hits res) min-seed-crown))
                :let [corr (assign targets candidates intrinsics (:pose res) tol-px)
                      full (when (>= (count corr) 6)
                             (pnp/solve-pnp corr intrinsics {}))
                      suspect (when full
                                (phase-probe targets candidates intrinsics
                                             (:pose full) (:axis face) marks tol-px))
                      guard-ok? (when full
                                  (sees-its-own-picks?
                                   {:picks (into {} (map (fn [c] [(:ci c) (:px c)]) corr))
                                    :pose (:pose full)}
                                   by-id))
                      _ (note! {:stage :solve :face [(:axis face) (:sign face)]
                                :corr (count corr)
                                :rms (some-> full :rms-px)
                                :guard guard-ok?
                                :suspect (some? suspect)})]
                :when (and full
                           (<= (:rms-px full) pnp/accept-rms-px)
                           guard-ok?)]
            {:pose (:pose full) :rms-px (:rms-px full)
             :corr corr :explained (count corr)
             ;; marks the pose accounts for BEYOND the seed's own ring — the
             ;; only currency that separates the ring-family twins above
             :off-ring (count (remove (fn [{:keys [ci]}]
                                        (= (:axis face)
                                           (:axis (or (cage/mark-parts ci)
                                                      (cage/index-parts ci)))))
                                      corr))
             :phase-suspect (some-> suspect (assoc :axis (:axis face)))
             :seed {:axis (:axis face) :sign (:sign face)
                    :crown-hits (:crown-hits res)}})
          (sort-by (juxt (comp - :explained) (comp - :off-ring) :rms-px))
          first))))