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

(declare index-witness)

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

(defn explained
  "How many DISTINCT candidates the pose puts a front-facing target on (within
   `tol-px`) — the score a reading is judged by.

   Distinct, and it is a guard, not tidiness: counting TARGETS lets a pose that
   is too far away win the argument — the cage projects smaller, its marks
   bunch up, and MANY targets crowd within tolerance of the SAME few discs.
   Measured (2026-08-29, foto 7 del banco): a through-plastic twin 764mm out
   'explained' 19 targets on 26 candidates and shipped as a registration; the
   true pose explains 13, one disc each. A candidate is one physical disc and
   can confirm one mark."
  [targets candidates intrinsics pose tol-px]
  (count
   (into #{}
         (keep (fn [{:keys [obj]}]
                 (let [px (cam/project intrinsics pose obj)]
                   (when px
                     (let [[d i] (reduce (fn [[bd bi] [j [cu cv]]]
                                           (let [dd (Math/hypot (- cu (nth px 0))
                                                                (- cv (nth px 1)))]
                                             (if (< dd bd) [dd j] [bd bi])))
                                         [js/Infinity nil]
                                         (map-indexed vector candidates))]
                       (when (and i (< d tol-px)) i))))))
         (front-facing targets pose))))

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

(defn- one-face-per-ring
  "Drop the minority face from any ring that ended up matched on BOTH faces.
   A flat ring shows one face; a correspondence set claiming xm and xp
   together puts the camera on two sides of a plane at once, and the solve
   can only answer camera-behind. It HAPPENS because near a ring's edge-on
   line the per-target culling admits stragglers of the far face (their
   normals are almost perpendicular to the view, the dot-product test is a
   coin toss), and each disc matches under whichever name got there first —
   measured live (battiscopa3 foto 2, 2026-08-29): xm00/03/06/09 and
   xp01/02/05/10/11 in one corr, refusal certain before the solve began."
  [corr]
  (let [face-of (fn [{:keys [ci]}]
                  (when-let [p (or (cage/mark-parts ci) (cage/index-parts ci))]
                    [(:axis p) (:sign p)]))]
    (vec (mapcat (fn [[axis cs]]
                   (if (nil? axis)
                     cs
                     (let [tally (frequencies (keep (comp second face-of) cs))]
                       (if (< (count tally) 2)
                         cs
                         (let [win (key (apply max-key val tally))]
                           (filter #(= win (second (face-of %))) cs))))))
                 (group-by (fn [c] (some-> (face-of c) first)) corr)))))

(defn assign
  "Every mark the pose can account for, paired with the candidate it lands on:
   `[{:ci id :world obj :px [u v]} …]`.

   Matching is MUTUAL nearest within `tol-px` — a mark takes a candidate only if
   that candidate's own nearest mark is this one. One-sided nearest-neighbour
   would let two neighbouring marks both claim the one disc that happens to lie
   between them, which is exactly the failure that made the user's own clicks
   unusable near a ring crossing. And each ring answers with ONE face
   (`one-face-per-ring`): the culling is per-target, so at a grazing view the
   far face's stragglers slip in, and a mixed-face corr is camera-behind by
   construction."
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
    (one-face-per-ring
     (vec (keep (fn [{:keys [id obj px] :as p}]
                  (let [[cu cv] px
                        c (when (seq candidates)
                            (apply min-key (fn [[u v]] (Math/hypot (- u cu) (- v cv)))
                                   candidates))]
                    (when (and c (< (Math/hypot (- (first c) cu) (- (second c) cv)) tol-px)
                               ;; …and that candidate's own nearest mark is this one
                               (= id (:id (nearest-of preds c))))
                      {:ci id :world obj :px (vec c)})))
                preds)))))

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
         ;; A face the USER DECLARED (opts :declared-faces {axis sign}) is a fact
         ;; about the photograph — they matched the physical cage to it — and a
         ;; reading that flips it is answering a question nobody asked. Readings
         ;; that move a declared ring's picks to the other face are dropped
         ;; outright (never scored, so they cannot win on candidate counting),
         ;; and never to the point of leaving nothing: if the declaration kills
         ;; every reading it is the declaration that is in doubt, and the caller
         ;; must hear that rather than get silence. Vincenzo, 2026-08-30: «avevo
         ;; messo p perché mi presentava solo quelli» — the panel had offered a
         ;; face that was not in the picture, and three evenings went into
         ;; refusals downstream of that one imposed name.
         declared (:declared-faces opts)
         keeps-faces? (fn [{:keys [picks]}]
                        (every? (fn [id]
                                  (let [p (or (cage/mark-parts id) (cage/index-parts id))
                                        s (get declared (:axis p))]
                                    (or (nil? s) (= s (:sign p)))))
                                (keys picks)))
         all-scored (if (seq declared)
                      (let [ok (filterv keeps-faces? all-scored)]
                        (if (seq ok) ok all-scored))
                      all-scored)
         zero-picks (vec (remove #(= (picks-axis picks) (:axis %)) (:zero-picks opts)))
         zero-d (fn [pose {:keys [axis px]}]
                  (reduce min js/Infinity
                          (for [s [1 -1]
                                :let [t (by-id (cage/index-id axis s))
                                      p (when t (cam/project intrinsics pose (:obj t)))]
                                :when p]
                            (Math/hypot (- (nth p 0) (nth px 0))
                                        (- (nth p 1) (nth px 1))))))
         [scored0 zero-veto]
         (reduce (fn [[sc note] zp]
                   (let [ok (filterv #(<= (zero-d (:pose %) zp) zero-veto-px) sc)]
                     (if (seq ok)
                       [ok (update note :killed + (- (count sc) (count ok)))]
                       ;; contradicts every reading: the ring is the suspect, not
                       ;; the readings — set aside, never turned into a refusal
                       [sc (update note :moot conj (:axis zp))])))
                 [all-scored {:killed 0 :moot []}]
                 zero-picks)
         ;; THE SESSION'S MOUNTING vetoes on the same footing as the clicked
         ;; zeros (opts :mounting, `vote-mounting` output): each reading's own
         ;; pose reads the SEED ring's index in some slot, and a reading that
         ;; contradicts the (sense, k) the session voted — ≥2 poses, and only
         ;; off-nominal, since a nominal pair cannot tell the faces apart (see
         ;; auto-read's seed-nominal?) — is the through-plastic twin, however
         ;; well it scores. Found live before it was wired (foto 5 battiscopa2,
         ;; 2026-08-29): a candidate-starved frame (18 discs) elected the
         ;; flip-face twin of a ring THREE photographs had voted fwd(k6), and
         ;; renamed the user's correct picks with the twin's names before the
         ;; camera-behind guard refused the solve. Only the seed ring's own
         ;; observations judge here: these poses are one-ring solves, and the
         ;; other rings' slots under them carry 90-160px of slop (the
         ;; namespace's founding measurement).
         seed-axis (picks-axis picks)
         known (when seed-axis (get (:mounting opts) seed-axis))
         m-check (when (and known (>= (:votes known 0) 2)
                            (not= [:fwd 0] [(:sense known) (:k known)]))
                   (fn [r]
                     (not-any? (fn [{:keys [sense k]}]
                                 (or (not= sense (:sense known))
                                     (not= k (:k known))))
                               (filter #(= seed-axis (:axis %))
                                       (:obs (index-witness targets candidates
                                                            intrinsics (:pose r)
                                                            marks {}))))))
         [scored mounting-veto]
         (if m-check
           (let [ok (filterv m-check scored0)]
             (if (seq ok)
               [ok {:killed (- (count scored0) (count ok))}]
               ;; contradicts every reading — the same honesty rule as the
               ;; zeros: that is evidence about the session or the frame,
               ;; never a silent refusal
               [scored0 {:killed 0 :moot? true}]))
           [scored0 nil])]
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
             full (when (>= (count corr) 6) (pnp/solve-pnp corr intrinsics {}))
             ;; The bar RISES with the evidence available. min-explained alone is
             ;; nearly free on a busy frame: the seed ring's own clicked discs are
             ;; candidates too, so 4 clicks + noise reach 6 no matter what the
             ;; reading says about the rest of the cage — grab-07 (3/9 notte):
             ;; a flip-face twin explaining 7 of 29 detected discs passed the
             ;; absolute bar, RENAMED four correct hand clicks, and the pose it
             ;; seeded explained 4 of 39 visible marks (a healthy read explains
             ;; 16-24). One candidate in three is the floor a true reading clears
             ;; with room (measured 55-70%); the cap at 12 keeps junk-heavy
             ;; frames from demanding more than any true reading could show, and
             ;; on sparse frames (≤18 candidates) the old absolute bar is
             ;; unchanged.
             bar (max min-explained (min 12 (quot (count candidates) 3)))]
         (when (>= (:explained result) bar)
           (assoc result
                  :corr corr
                  :full (when full (select-keys full [:pose :rms-px]))
                  :guard-rejected (- (count top) (count kept))
                  :phase-suspect suspect
                  :zero-veto (when (seq zero-picks) zero-veto)
                  :mounting-veto mounting-veto
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
   mounting the photo just measured (the number `:phases` on
   `registration-cage` wants declared), with the confirming re-solve as
   EVIDENCE — or nil when no hand zero was outliered or no turn explains it.
   The caller ships the diagnosis, not the substitution (Vincenzo,
   2026-08-28: «la gabbia deve essere giusta» — registering photos as if the
   phase were declared buys work and uncertainty to paper over a mounting
   that the declaration fixes outright)."
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

(defn- ring-azim
  "Azimuth (rad) of an object point in its ring's own (u,v) plane — the angular
   coordinate `cage/turn-about-axis` turns."
  [axis obj]
  (let [[u v _] (cage/unplace axis obj)]
    (Math/atan2 v u)))

(def index-obs-px
  "How close (px) a detected candidate must sit to an index SLOT before it
   testifies about the mounting. Half the matching tolerance, and measured on
   the battiscopa bench before it was chosen: the real (flipped) Y index reads
   4-11px from its slot across five photographs and five different poses,
   while the two junk hits on the whole bench sat at 24 and 25px. The model's
   slop that justifies 26px for MATCHING does not apply here: an observation
   is evidence, and evidence this instrument acts on has to be the sharp
   kind."
  13.0)

(defn index-witness
  "The index discs of a cage, read off the DETECTOR's candidates under `pose` —
   the through-plastic/reflection twin's arbiter for machine seeds (zero-click
   lever 2), and the mounting's measuring instrument.

   WHY THE INDICES AND NOTHING ELSE. The cage's disc constellation is invariant
   under reflection through any ring's plane and under whole-step turns of any
   ring — BY DESIGN everything except the six zero-indices
   (`cage/default-index-phase`): a third of a step past a crown mark, the one
   chiral figure on the object. A twin pose and the true pose therefore
   disagree ONLY about the indices, and an arbiter is built on them or on
   nothing.

   WHY THE SENSE, NOT THE SLOT. An index disc can only sit in one of 24
   azimuthal SLOTS at index radius: a third of a step PAST each of the 12
   crown marks (`:fwd` — slot k=0 is the model's own zero) or a third of a
   step BEFORE each (`:rev`, the mirror family). Which SLOT it occupies is a
   fact about the mounting (rings turn in whole steps and — until the anti-flip
   key is printed — mount flipped, per assembly) COMPOSED with the reading's
   own rotation gauge, so it cannot be checked against the model. But the
   SENSE survives both: a whole-step turn moves the index between `:fwd`
   slots, a reading's rotation shifts k and never the family — while a
   REFLECTION maps `:fwd` to `:rev` exactly. One session = one mounting (the
   cage opens only at part changes), so every true pose of a session reads
   each ring's index in the SAME sense, and a twin pose reads it flipped.
   Measured on battiscopa (2026-08-30) before this was written: the Y index
   reads `:rev` slot 11 at 4-11px on five photographs under the hand-truth
   poses — and the 548mm twin of foto 6 is the reading that claims that same
   disc as its own `:fwd` zero.

   Only candidates no CROWN mark explains may testify (the other rings' discs
   crossing this ring's index ellipse in the image are explained by their own
   marks and excluded); a candidate the pose claims as an INDEX still
   testifies — it is index evidence by definition, and the twin's conviction
   is precisely the disc it claimed as its zero.

   `blobs`   detector candidates, [{:center [u v]} …] or bare [[u v] …]
   `claimed` (opts) pixels already assigned to CROWN marks — pass the accepted
             solution's own corr so witness and solution read one assignment;
             computed via `assign` when absent.

   ONE READING PER RING — the sharpest hit, because one face has one index
   disc and a reading of it is a reading, not a poll of every slot a stray
   candidate wandered near. Until 2026-09-02 `:obs` carried EVERY hit within
   `index-obs-px`, and the extra ones were junk with a vote: on battiscopa3's
   photo 2 (5.9px, double pallini right) the X ring read its own nominal at
   1.4px — `zero-xp` itself — and a candidate with NO mark within 30px of it
   sat 7.9px from the rev-k2 slot, so the session's vote counted one honest
   ballot and one junk ballot for the same ring, the ATTENZIONE fired against
   a ring that had just read correctly, and (worse, upstream) a rereading
   whose pose was true could be vetoed by the same stray in `m-check` and
   auto-read's `contradiction`. The full per-slot report survives in
   `:faces` — it is the bench's business, not the vote's.

   Returns {:obs [{:axis :sense :k :d :zero-d} …]  ≤1 per visible face — its
                  sharpest hit; :zero-d is that face's nominal-slot distance
                  (nil when no free candidate exists), so a consumer can say
                  'and the nominal slot is EMPTY' instead of guessing
            :faces [{:axis :sign :zero-d :hits [{:sense :k :d} …]} …]}
   — `:obs` is what arbitration and mounting bookkeeping consume, `:faces`
   the full per-face report the bench prints."
  [targets blobs intrinsics pose marks {:keys [tol-px claimed]
                                        :or {tol-px (:tol-px default-opts)}}]
  (let [step (/ 360.0 marks)
        centers (mapv (fn [b] (vec (if (map? b) (:center b) b))) blobs)
        crown-claimed (set (keep (fn [{:keys [ci px]}]
                                   (when (cage/mark-parts ci) (vec px)))
                                 (or claimed
                                     (assign targets centers intrinsics pose tol-px))))
        free (vec (remove crown-claimed centers))
        cam-c (cam/camera-center pose)
        nearest (fn [px]
                  (when (and px (seq free))
                    (reduce min js/Infinity
                            (map (fn [[u v]] (Math/hypot (- u (nth px 0))
                                                         (- v (nth px 1))))
                                 free))))
        faces (filterv (fn [{:keys [face-normal zero-obj]}]
                         (or (nil? face-normal)
                             (pos? (la/v-dot face-normal (la/v-sub cam-c zero-obj)))))
                       (ring-faces targets))
        rep (vec (for [{:keys [axis zero-obj] :as face} faces
                       :let [a0 (ring-azim axis (:obj (first (:marks face))))
                             az (ring-azim axis zero-obj)
                             ;; the index's offset from mark 0, read off the
                             ;; geometry itself (not assumed 1/3 step), degrees
                             delta (let [d (- az a0)]
                                     (* (Math/atan2 (Math/sin d) (Math/cos d))
                                        (/ 180.0 Math/PI)))
                             slot-d (fn [deg]
                                      (nearest (cam/project intrinsics pose
                                                            (cage/turn-about-axis
                                                             axis zero-obj deg))))
                             zero-d (slot-d 0.0)
                             slots (vec (for [i (range marks)
                                              [sense deg] [[:fwd (* i step)]
                                                           [:rev (+ (* -2.0 delta)
                                                                    (* i step))]]
                                              ;; at index-phase 1/2 the two
                                              ;; families coincide — keep one
                                              :let [n (let [m (mod deg 360.0)]
                                                        (min m (- 360.0 m)))]
                                              :when (or (= [sense i] [:fwd 0])
                                                        (> n (/ step 6.0)))
                                              :let [d (slot-d deg)]
                                              :when d]
                                          {:sense sense :k i :d d}))]]
                   {:axis axis :sign (:sign face)
                    :zero-d zero-d
                    :hits (filterv #(<= (:d %) index-obs-px) slots)}))]
    {:obs (vec (keep (fn [{:keys [axis zero-d hits]}]
                       (when (seq hits)
                         (assoc (apply min-key :d hits)
                                :axis axis :zero-d zero-d)))
                     rep))
     :faces rep}))

(defn mounting-of
  "One pose's index observations folded into a per-ring reading: {axis {:sense
   :k :d}}, keeping the sharpest observation per ring. Both the sense AND the
   slot k are POSE-ABSOLUTE: the witness measures slots against the model's
   own azimuths under the final pose, no reading gauge involved — so every
   true pose of a session reads the same (sense, k) on a ring, because that
   pair IS the ring's mounting. For the session-level aggregate use
   `vote-mounting`."
  [obs]
  (reduce (fn [m {:keys [axis] :as o}]
            (if (or (nil? (get m axis)) (< (:d o) (:d (get m axis))))
              (assoc m axis (select-keys o [:sense :k :d]))
              m))
          {}
          obs))

(defn vote-mounting
  "The SESSION's mounting from many poses' observations, by MAJORITY VOTE per
   ring over the pair (sense, k) — never by sharpness, and never sense alone.

   Sharpness lost first (2026-08-30): foto 1's hand registration of the
   battiscopa truth session is itself a reflection twin — it reads both
   visible indices in the exact mirror of what five mutually-consistent
   photographs read — and its observations were sharper by 0.05px, so ONE
   poisoned pose inverted the whole session's mounting, vetoed true readings
   and confirmed foto 7's twin. Democracy over acuity: a twin flips every
   ring's sense at once, so it loses the vote as many times as the session
   has honest poses.

   Sense-alone lost the same day: the cage turned 180° ABOUT ANOTHER AXIS is
   an exact symmetry of the disc constellation that PRESERVES every index's
   sense and shifts its slot by half the marks — foto 7 shipped 643mm out,
   explaining 18, index on a sense-correct slot at k5 where every true pose
   of the session reads k11. The slot k is pose-absolute (see `mounting-of`),
   so the session votes on the full pair and that family dies too.

   `obs-seq` is a seq of observation vectors (one per pose, each as
   `index-witness` returns under `:obs`). Returns
   {axis {:sense :fwd|:rev :k n :votes n :against n :d px :contested? bool}} —
   `:votes` for the winning pair, `:against` for every other, `:d` the winning
   side's sharpest. A `:contested?` ring is itself a DIAGNOSIS the caller
   must surface: (sense, k) is a pose-fact, so a session that reads one ring
   two ways contains a twin among its own registrations."
  [obs-seq]
  (let [by-axis (group-by :axis (apply concat obs-seq))]
    (into {}
          (for [[axis os] by-axis
                :let [pairs (group-by (juxt :sense :k) os)
                      ranked (sort-by (fn [[_ v]] [(- (count v))
                                                   (reduce min (map :d v))])
                                      pairs)
                      [[sense k] winners] (first ranked)
                      votes (count winners)
                      against (- (count os) votes)]
                ;; a dead-even ring decides nothing — reported by the caller
                ;; via the per-photo readings, enforced by nobody
                :when (> votes against)]
            [axis {:sense sense :k k
                   :votes votes
                   :against against
                   :d (reduce min (map :d winners))
                   :contested? (pos? against)}]))))

(def eye-gate-frac
  "How far — as a fraction of the eye camera's own distance to the cage
   centre — an accepted reading's camera may land from the EYE-ALIGNED pose
   before the reading dies. The measured twins of this bench shipped their
   camera 325/447/548/643/764mm from the truth at working ranges of
   300-600mm — ratios from ~0.65 up — while a hand alignment of the drawn
   cage is off by tens of mm and degrees. 0.4 splits those populations with
   margin on both sides. The one twin family that can sneak under it is the
   reflection of a NEAR-EDGE-ON ring (camera 2·D·sinθ away, small θ) — and
   at those obliquities the profile guard already refuses to read the face,
   so the index witness stays the arbiter there, as before."
  0.4)

(def eye-face-margin-deg
  "A face the EYE pose sees more than this many degrees below its own horizon
   is not even attempted. The margin is generous on purpose: the eye pose is
   coarse (hand-aligned), so faces near the horizon stay in play — but the
   through-plastic twin is never near the horizon, it is the face the eye
   plainly sees the BACK of (an oblique working view puts it 35-90° under),
   and killing it before the solve is what saves the identity budget."
  20.0)

(def eye-accept-rms-px
  "The eye-seed's OWN acceptance bar, deliberately tighter than
   pnp/accept-rms-px: a hunt reading carries identity evidence (a pinned
   index, a counted crown), so a 12px fit rides on more than its residual —
   an eye-seed carries NOTHING but its fit, and must prove itself on it.
   Measured (battiscopa3, 2026-09-02): true eye-seed registrations sit at
   0.7-4.3px; the one degenerate a sloppier eye produced (8 corr, camera
   32mm out — the quiet lie this channel fears most) passed the general bar
   at 11.8px. Eight splits those populations with margin."
  8.0)

(defn eye-compatible?
  "Is `pose`'s camera where the user's eye-aligned `eye-pose` says the camera
   is — within `eye-gate-frac` of the eye camera's distance to the cage
   centre (the origin of the solver frame)? The gate of the gizmo seed:
   «un seme umano grossolano vale più di quattro click»."
  [eye-pose pose]
  (let [ec (cam/camera-center eye-pose)
        d (la/v-norm (la/v-sub (cam/camera-center pose) ec))]
    (<= d (* eye-gate-frac (la/v-norm ec)))))

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
   gates: they solve wide of the bar or claim discs the camera cannot see.

   opts worth knowing: `:teeth?` turns on the comb-locked identity (zero-click
   lever 1 — contamination-proof, 24 candidates per face instead of C(12,k));
   OFF in production until the through-plastic twin has an arbiter for machine
   seeds — see the gate comment below for the measured reason. `:concentric?`
   swaps in the centre-pinned hypothesis stream; measured adding nothing on
   the bench today (2026-08-30, identical photo-for-photo), kept wired for
   when the selection lever moves (foto 3's ring dies THERE, not at identity).

   `:mounting` is the SESSION's mounting — {axis {:sense :fwd|:rev}} from
   `mounting-of`/`merge-mounting` over the already-accepted registrations —
   and it is lever 2, the twin's arbiter. Two rules, both sense-based
   (gauge-free, see `index-witness`):

   1. VETO: a solution whose index observation contradicts the session's sense
      on any ring is a reflection twin — one session is one mounting — and is
      dropped, whatever its score. Measured need (2026-08-30): foto 6's 548mm
      twin is exactly the reading that claims the flipped Y index as its own
      `:fwd` zero.
   2. REQUIRED CONFIRMATION: when the session knows the seed ring's sense, the
      solution must OBSERVE that ring's index in that sense — the luminance
      zero-judge is no longer allowed to decide alone (it is exactly what the
      twin rode in on: `disc-at?` is any dark patch, the part included). In
      exchange the seed gate relaxes for such rings: a reading whose model
      zero lands on nothing may still be the true one when the ring is
      mounted turned/flipped, so it proceeds to the solve and the observation
      decides. On rings the session knows nothing about, behavior is exactly
      as before — the bar can only move UP.

   `:eye-pose` is the user's HAND-ALIGNED pose (the gizmo's «prendo in mano la
   gabbia e la appaio alla foto»), solver frame — the third lever, and the one
   that needs no session history, so it closes the cold start for undeclared
   cages too. Coarse by construction and used only for what coarseness can
   answer: (1) faces the eye plainly sees the back of are never attempted
   (eye-face-margin-deg keeps near-horizon faces in play), which kills the
   through-plastic twin before the solve; (2) an accepted pose must put the
   camera where the eye put it (eye-compatible?), which kills the turned and
   far twins whatever they explain. It never CHOOSES among survivors — the
   evidence-based ranking stays in charge of that.

   Every result carries `:index-obs` (this pose's observations) so the caller
   can fold them into the session mounting and DIAGNOSE the assembly — never
   compensate it (direttiva 2026-08-28): a `:rev` sense on a ring is the
   software recognizing 'questo anello è montato RIBALTATO', and the remedy
   is the declaration or the remount, not a silent relabel."
  ([candidates targets intrinsics judge marks] (auto-read candidates targets intrinsics judge marks nil))
  ([candidates targets intrinsics judge marks opts]
   (let [{:keys [disc-r min-seed-crown tol-px trace max-identify concentric? teeth?
                 mounting blobs min-off-ring eye-pose]
          :or {disc-r 1.25 min-seed-crown 8 max-identify 18 min-off-ring 2
               tol-px (:tol-px default-opts)}} opts
         note! (fn [m] (when trace (swap! trace conj m)) nil)
         cands (vec candidates)
         ;; Ring hypotheses with a floor of EIGHT inliers — a cost wall, not a
         ;; taste: identifying a k-point ring among 12 marks enumerates C(12,k)
         ;; cyclic candidates, 24 at k=12 but 11088 at k=7, and each face of
         ;; each hypothesis pays it. The first bench run at floor 6 took 40-57
         ;; seconds per frame, almost all of it on junk partial rings.
         ;; `:concentric?` swaps in the centre-pinned family search
         ;; (ellipse/fit-concentric-ranked): the cage's rings share their
         ;; centre, so the junk conics of the free search SEED an exhaustive
         ;; 3-point search that surfaces the sparse rings the 5-point RANSAC
         ;; cannot (audited 2026-08-29: foto 7's nine ym discs complete inside
         ;; a hypothesis). Its hypotheses arrive CONTAMINATED by construction
         ;; (+2…+6 riders in the Sampson band) — which is why it stays behind a
         ;; flag until the bench clears it: the comb-locked identity below
         ;; digests the riders, but the enriched stream also feeds the
         ;; whole-cage REFLECTION twin, and that arbiter is the next slice.
         hyps ((if concentric? ellipse/fit-concentric-ranked ellipse/fit-inliers-ranked)
               cands
               {:iters 2000 :thr 0.04 :min-inliers 8 :top-k 6})
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
                      (let [pts (mapv #(nth cands %) hyp)
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
         ;; THE COMB, once per hypothesis (the ellipse lives in the image — it
         ;; does not depend on the face being tried): each point gets a TOOTH,
         ;; an integer cyclic position on the crown, and the riders that
         ;; poisoned the identity get none. With the subset structure read off
         ;; the teeth, assign-marks tries 24 candidates instead of C(12,k)·k·2
         ;; — so tooth-locked attempts are NOT charged to the identity budget,
         ;; and the budget starvation of 2026-08-29 (the true ring fifth in
         ;; list, never tried) cannot recur.
         ;; GATED (`:teeth?`, off in production) — by the bench, not by doubt
         ;; about the mechanism (measured 2026-08-30): with teeth on, foto 6
         ;; and 7 REGISTER — from the through-plastic twin, camera 548/764mm
         ;; out. The true ring's crown identifies too (foto 6: the 8 yp discs,
         ;; inside the 14-point hypothesis, corona 11), but the crown count
         ;; ties across ALL SIX faces by symmetry and only the zero-index
         ;; pixel-judge decides — and on those frames it passes exactly on the
         ;; twin face (the true face's zero is not visible to `disc-at?`).
         ;; That arbiter — a zero witness that works for machine seeds — is
         ;; the next slice; until it exists, 2/8 with zero false positives
         ;; beats 2 registrations at half a metre.
         seeds (when teeth?
                 (mapv (fn [hyp]
                         (when-let [ct (ellipse/comb-teeth cands hyp marks)]
                           (let [ids (vec (sort (keys (:teeth ct))))]
                             {:pts (mapv #(nth cands %) ids)
                              :teeth (mapv (:teeth ct) ids)
                              :riders (count (:riders ct))})))
                       hyps))
         ;; the EYE's face pre-filter: a face the hand-aligned pose plainly
         ;; sees the back of is never attempted — the through-plastic twin is
         ;; exactly that face, and it dies here for free instead of at the
         ;; solve. Faces within eye-face-margin-deg of the eye's horizon stay
         ;; in play: the eye pose is coarse by construction.
         all-faces (ring-faces targets)
         faces (if eye-pose
                 (let [ec (cam/camera-center eye-pose)
                       lim (- (Math/sin (* eye-face-margin-deg (/ Math/PI 180.0))))]
                   (filterv (fn [{:keys [face-normal zero-obj]}]
                              (or (nil? face-normal)
                                  (let [dir (la/v-sub ec zero-obj)
                                        n (la/v-norm dir)]
                                    (or (zero? n)
                                        (>= (/ (la/v-dot face-normal dir) n) lim)))))
                            all-faces))
                 all-faces)
         by-id (into {} (map (juxt :id identity) targets))
         budget (volatile! (inc max-identify))
         ;; ── the eye as a DIRECT SEED, tried before the ellipse hunt ────────
         ;; The gate alone measured ZERO on battiscopa3 (2026-09-02): those
         ;; 1920×1440 frames die at IDENTIFY — no reading ever reaches the
         ;; gate. But the eye pose doesn't need an identity: assign every mark
         ;; mutual-nearest under it and let the solve snap in (ICP, 60→26px).
         ;; Measured on the same five frames, eye at 17-30px of reprojection
         ;; (a hand alignment ON the image): 5/5, camera 0.7-5.3mm from the
         ;; hand truth, rms 0.7-4.3px, in milliseconds — against the hunt's
         ;; 0/5 in 17-36 SECONDS. Held to the same bars as every reading:
         ;; accept-rms, the physical guard, ≥2 rings, the witness's
         ;; non-contradiction, and the eye's own camera gate (an ICP that
         ;; wandered off is a wrong answer, wherever it started). Plus a floor
         ;; of min-seed-crown correspondences: the one degenerate the bench
         ;; produced (eye at 42px) passed rms at 11.2 on SEVEN corr — a fit
         ;; too small to trust is refused, and the ellipse hunt takes over.
         eye-read
         (when eye-pose
           (let [icp-pose (reduce (fn [pose tol]
                                    (when pose
                                      (let [c (assign targets cands intrinsics pose tol)]
                                        (when (>= (count c) 6)
                                          (:pose (pnp/solve-pnp c intrinsics {}))))))
                                  eye-pose [60.0 26.0])
                 corr (when icp-pose (assign targets cands intrinsics icp-pose tol-px))
                 full (when (>= (count corr) 6) (pnp/solve-pnp corr intrinsics {}))
                 obs (when full
                       (:obs (index-witness targets (or blobs cands) intrinsics
                                            (:pose full) marks {:claimed corr})))
                 contradiction (when full
                                 (first (filter (fn [{:keys [axis] :as o}]
                                                  (when-let [m (get mounting axis)]
                                                    (or (not= (:sense o) (:sense m))
                                                        (not= (:k o) (:k m)))))
                                                obs)))
                 guard-ok? (when full
                             (sees-its-own-picks?
                              {:picks (into {} (map (fn [c] [(:ci c) (:px c)]) corr))
                               :pose (:pose full)}
                              by-id))
                 by-ring (when corr
                           (frequencies (keep #(some-> (or (cage/mark-parts (:ci %))
                                                           (cage/index-parts (:ci %)))
                                                       :axis)
                                              corr)))
                 ok? (and full
                          (<= (:rms-px full) eye-accept-rms-px)
                          (>= (count corr) min-seed-crown)
                          (>= (count by-ring) 2)
                          guard-ok?
                          (nil? contradiction)
                          (eye-compatible? eye-pose (:pose full)))]
             (note! {:stage :eye-seed :corr (count corr)
                     :rms (some-> full :rms-px) :rings (count by-ring)
                     :guard guard-ok? :mounting-veto (some? contradiction)
                     :accepted ok?})
             (when ok?
               {:pose (:pose full) :rms-px (:rms-px full)
                :corr corr :explained (count corr)
                :off-ring (- (count corr) (apply max 0 (vals by-ring)))
                :index-obs obs
                :eye-seed? true
                :seed nil})))]
     (note! {:stage :hyps :sizes (mapv count hyps) :candidates (count cands)
             ;; the sets themselves and the axis votes: the bench compares them
             ;; against the truth pose's per-ring candidates, which is how the
             ;; 2026-08-29 budget starvation was caught — sizes alone could not
             ;; say whether the true ring was IN the list and never tried
             :sets hyps
             :eye-hidden-faces (when eye-pose
                                 (mapv (fn [f] [(:axis f) (:sign f)])
                                       (remove (set faces) all-faces)))
             :teeth (mapv (fn [s] (some-> (:teeth s) count)) (or seeds []))})
     ;; BEST accepted wins — never the first. The same 11 candidate discs
     ;; identify as ring X AND as ring Y (same circle, different radius: the
     ;; pose absorbs the scale into distance), both at clean rms, both past the
     ;; guard — and only how much of the REST of the cage each pose explains
     ;; tells them apart (measured on foto 1: 13 vs 12, and first-wins shipped
     ;; the 12, putting the camera 697mm out). The identity budget caps the
     ;; cost instead: the search stops grinding junk after :max-identify
     ;; attempts, which took refusals from 40-65s to 10-25.
     ;;
     ;; …unless the EYE already read it: an accepted eye-seed IS the answer —
     ;; it stood on every bar the hunt's winners stand on, it agrees with the
     ;; human's own alignment by construction, and the hunt costs 20-35s of
     ;; grinding for readings the gate would then judge against that same eye.
     (or eye-read
         (->> (for [[hi hyp] (map-indexed vector hyps)
                    face faces
                    ;; two attempts per (hypothesis, face), not either-or: the
                    ;; tooth-locked identity is free and digests riders, but at a
                    ;; pathological obliquity the comb itself can misfile (measured:
                    ;; ring X seen from 150mm off its own plane — anomaly gaps past
                    ;; a quarter tooth), so the baseline enumeration keeps running
                    ;; under the SAME budget rules as before. The bench cannot go
                    ;; below baseline by construction; the teeth add wins.
                    :let [{:keys [pts teeth]} (when seeds (nth seeds hi))
                          enum-pts (mapv #(nth cands %) (take marks hyp))]
                    attempt (cond-> []
                              teeth (conj {:pts pts :teeth teeth})
                              (and (>= (count enum-pts) 4) (pos? (vswap! budget dec)))
                              (conj {:pts enum-pts}))
                    :let [res (mp/assign-marks (:pts attempt) (:marks face)
                                               (:zero-obj face) intrinsics judge
                                               {:disc-r disc-r
                                                :face-normal (:face-normal face)
                                                :teeth (:teeth attempt)})
                          known-sense (get-in mounting [(:axis face) :sense])
                          ;; a NOMINALLY-mounted ring (fwd, slot 0) cannot testify
                          ;; about its own seed: the index disc is the same pixel
                          ;; through the plastic, so the true reading and its
                          ;; through-plastic twin read the identical (fwd, 0) —
                          ;; measured (battiscopa2 foto 1, 2026-08-29): the zp
                          ;; twin shipped 447mm out ENDORSED by its own invariant
                          ;; Z observation at 1px. Only an off-nominal pair
                          ;; separates the faces (the twin always reads its seed
                          ;; index at (fwd, 0) — its zero-hit pinned it there —
                          ;; while the true reading reads the mounting: foto 7's
                          ;; twin fwd(k0) vs true rev(k11)). Same lesson as the
                          ;; hand flow: l'arbitro vero è l'indice dell'ALTRO
                          ;; anello — or the seed's own, only when the mounting
                          ;; is off-nominal
                          seed-nominal? (and known-sense
                                             (= [:fwd 0]
                                                [(get-in mounting [(:axis face) :sense])
                                                 (get-in mounting [(:axis face) :k])]))
                          ;; the confirmation is DEMANDED only of a sense the
                          ;; session has seen at least twice (a single observation
                          ;; can be junk) and only where a seed observation CAN
                          ;; discriminate — on a nominal ring it cannot, so the
                          ;; old zero-hit rule stands there unrelaxed
                          demand? (and known-sense
                                       (not seed-nominal?)
                                       (>= (get-in mounting [(:axis face) :votes] 1) 2))
                          _ (note! {:stage :seed :hyp (count hyp) :hyp-i hi
                                    :face [(:axis face) (:sign face)]
                                    :teeth (some-> (:teeth attempt) count)
                                    :crown-hits (:crown-hits res)
                                    :zero-hit? (:zero-hit? res)})]
                    ;; a ring the session knows is mounted turned/flipped puts its
                    ;; index NOWHERE NEAR the model's zero slot: the pixel-judge
                    ;; zero cannot be demanded of the true reading there — the
                    ;; index observation decides after the solve instead. Relaxed
                    ;; only where the observation WILL be demanded, so the bar
                    ;; never drops: it moves from the luminance judge to the
                    ;; detector's discs
                    :when (and res (>= (:crown-hits res) min-seed-crown)
                               (or (:zero-hit? res) demand?))
                    ;; THE GAUGE. Without a trusted zero the seed's ROTATION is
                    ;; arbitrary: the 12 whole-step turns of the crown labels all
                    ;; fit the seed ring exactly, and each puts the camera
                    ;; somewhere else — orbited k steps about the ring's axis.
                    ;; Measured (foto 7, 2026-08-30): the endorsed true-face
                    ;; reading shipped 325mm out because its gauge was two steps
                    ;; round — index on its slot (sense is gauge-free), crown
                    ;; perfect, camera orbited. On a session-known ring EVERY
                    ;; gauge is tried and the off-ring floor + endorsement decide:
                    ;; only the true turn explains marks beyond the seed's ring.
                    ;; (A luminance-pinned gauge is not exempt — on a mounted ring
                    ;; the model-slot zero is junk by construction, which is
                    ;; exactly what elected foto 6's 548mm twin.)
                    gauge (if demand? (range marks) [0])
                    :let [pose-g (if (zero? gauge)
                                   (:pose res)
                                   (let [m (count (:marks face))
                                         corr-g (vec (for [[ci mi] (:assignment res)]
                                                       {:world (:obj (nth (:marks face)
                                                                          (mod (+ mi gauge) m)))
                                                        :px (nth (:pts attempt) ci)}))
                                         seed (pnp/estimate-homography corr-g intrinsics)]
                                     (when seed
                                       (or (:pose (pnp/refine corr-g intrinsics seed
                                                              {:sigma-px 1.0}))
                                           seed))))]
                    :when pose-g
                    :let [corr (assign targets cands intrinsics pose-g tol-px)
                          full (when (>= (count corr) 6)
                                 (pnp/solve-pnp corr intrinsics {}))
                          suspect (when full
                                    (phase-probe targets cands intrinsics
                                                 (:pose full) (:axis face) marks tol-px))
                          guard-ok? (when full
                                      (sees-its-own-picks?
                                       {:picks (into {} (map (fn [c] [(:ci c) (:px c)]) corr))
                                        :pose (:pose full)}
                                       by-id))
                          wit (when full
                                (index-witness targets (or blobs cands) intrinsics
                                               (:pose full) marks {:claimed corr}))
                          obs (:obs wit)
                          ;; every check below matches the FULL pair (sense, k) —
                          ;; both pose-absolute. Sense alone lets the cage turned
                          ;; 180° about another axis through (sense-preserving,
                          ;; k shifted by half the marks; foto 7, 643mm, spiega 18)
                          agrees? (fn [{:keys [axis sense k]}]
                                    (let [m (get mounting axis)]
                                      (and m (= sense (:sense m)) (= k (:k m)))))
                          ;; …and only a DISCRIMINATING agreement counts as
                          ;; evidence FOR: on the seed's own ring a nominal
                          ;; (fwd, 0) observation is twin-invariant — the index is
                          ;; the same pixel through the plastic — so it endorses
                          ;; the reflection exactly as well as the truth (the
                          ;; 447mm case, see seed-nominal? above). Another ring's
                          ;; agreement always discriminates: every twin the seed
                          ;; race can generate mirrors the OTHER rings' senses
                          discriminating? (fn [{:keys [axis] :as o}]
                                            (and (agrees? o)
                                                 (or (not= (:axis face) axis)
                                                     (not= [:fwd 0]
                                                           [(get-in mounting [axis :sense])
                                                            (get-in mounting [axis :k])]))))
                          contradiction (first (filter (fn [{:keys [axis] :as o}]
                                                         (and (get mounting axis)
                                                              (not (agrees? o))))
                                                       obs))
                          confirmed? (or (not demand?)
                                         (boolean (some #(and (= (:axis face) (:axis %))
                                                              (discriminating? %))
                                                        obs)))
                          ;; ENDORSEMENT: the sharpest DISCRIMINATING observation
                          ;; — the ranking currency below. Infinity when nothing
                          ;; endorses
                          endorse-d (reduce min js/Infinity
                                            (keep (fn [o] (when (discriminating? o) (:d o)))
                                                  obs))
                          ;; marks the pose accounts for BEYOND the seed's own
                          ;; ring. Twice a currency: it separates the ring-family
                          ;; twins (same circle, other radius), and it is the
                          ;; ACCEPTANCE floor — the namespace's founding
                          ;; measurement says a pose standing on one ring alone
                          ;; does not generalize to the rest of the cage, and the
                          ;; bench confirmed it for machine seeds (2026-08-30:
                          ;; both far registrations, 325 e 807mm, explained ZERO
                          ;; off-ring marks — the index is coplanar with its
                          ;; crown, so even an endorsed one-ring solve leaves
                          ;; depth and tilt standing on nothing; foto 8's true
                          ;; pose explained 4)
                          off-ring (count (remove (fn [{:keys [ci]}]
                                                    (= (:axis face)
                                                       (:axis (or (cage/mark-parts ci)
                                                                  (cage/index-parts ci)))))
                                                  corr))
                          ;; the EYE's camera gate: a solved pose whose camera is
                          ;; not where the hand-aligned pose put it is a twin or a
                          ;; junk constellation, whatever it explains — the human
                          ;; seed outranks the score («un seme umano grossolano
                          ;; vale più di quattro click»)
                          eye-ok? (or (nil? eye-pose)
                                      (nil? full)
                                      (eye-compatible? eye-pose (:pose full)))
                          _ (note! {:stage :solve :face [(:axis face) (:sign face)]
                                    :gauge gauge
                                    :corr (count corr)
                                    :off-ring off-ring
                                    :rms (some-> full :rms-px)
                                    :guard guard-ok?
                                    :obs obs
                                    :mounting-veto (some? contradiction)
                                    :mounting-confirmed confirmed?
                                    :eye-veto (and eye-pose full (not eye-ok?))
                                    :suspect (some? suspect)})]
                    :when (and full
                               (<= (:rms-px full) pnp/accept-rms-px)
                               guard-ok?
                               (>= off-ring min-off-ring)
                               (nil? contradiction)
                               confirmed?
                               eye-ok?)]
                {:pose (:pose full) :rms-px (:rms-px full)
                 :corr corr :explained (count corr)
                 :off-ring off-ring
                 :index-obs obs
                 :endorse-d endorse-d
                 :phase-suspect (some-> suspect (assoc :axis (:axis face)))
                 :seed {:axis (:axis face) :sign (:sign face)
                        :crown-hits (:crown-hits res)}})
          ;; ENDORSED READINGS OUTRANK EVERYTHING, sharpest endorsement first —
          ;; and only then the old key. Measured reason (foto 7, 2026-08-30):
          ;; with the twins vetoed, the race came down to the TRUE reading
          ;; (index observed on its slot at 1.4px, rms 1.4) against the same
          ;; circle read as the Z ring — a ring-family twin with a
          ;; luminance-only zero, NO index evidence, and 11 explained to the
          ;; truth's 10. `explained` is a measured liar under twins (it
          ;; saturated at 19 for the reflection); a detected index disc ON its
          ;; slot is the evidence class this channel already trusts over dark
          ;; luminance everywhere else (blob-judge vs disc-at?). Within the
          ;; endorsed class the sharper observation wins: the family twin can
          ;; BORROW the same disc through another ring's slots, but the wrong
          ;; interpretation predicts it off by the scale mismatch (11.7px
          ;; against the true reading's 1.4)
              (sort-by (juxt :endorse-d (comp - :explained) (comp - :off-ring) :rms-px))
              first)))))