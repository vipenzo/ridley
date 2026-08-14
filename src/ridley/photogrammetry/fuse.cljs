(ns ridley.photogrammetry.fuse
  "Fusing two shooting sessions of the SAME object into one frame.

   The turntable gives a narrow BAND of angles: no view from above, none from
   below. The cure is to shoot the object again in a different resting pose and
   put the two sessions in one space (dev-docs/brief-session-fusion.md).

   The whole problem is ONE rigid motion per session, because the object is
   rigid and both sessions are in millimetres (the plate sets the scale). What
   ties them is the user's own declaration: marks with the SAME NAME in both
   sessions are the same physical point. No feature matching, no texture — the
   same philosophy that killed the Klein twin.

   Pure: no DOM, no stage, no source. The caller (editor.edit-acquire) supplies
   two mark maps and gets back the motion plus the numbers to judge it by."
  (:require [ridley.math :as m]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.lm :as lm]))

;; ------------------------------------------------------------
;; Applying a rigid motion
;; ------------------------------------------------------------

(defn transform-point [{:keys [R t]} p]
  (la/v-add (la/mat*vec R (vec p)) t))

(defn transform-dir [{:keys [R]} d]
  (m/normalize (la/mat*vec R (vec d))))

(defn transform-pose
  "Carry a pose through the motion. Position moves, directions only rotate —
   which is why a mark transported into the other session's frame is still a
   usable mark and not just a point."
  [rt {:keys [position heading up] :as pose}]
  (cond-> pose
    position (assoc :position (transform-point rt position))
    heading  (assoc :heading (transform-dir rt heading))
    up       (assoc :up (transform-dir rt up))))

;; ------------------------------------------------------------
;; The seed: a closed-form frame-to-frame rotation
;; ------------------------------------------------------------
;;
;; The house pattern is closed-form seed + LM refine (PnP, the turntable fit,
;; the plate). Kabsch would want an SVD; here the rotation is built by composing
;; orthonormal triads out of the data, which needs nothing but cross products.

(defn- triad
  "Right-handed orthonormal triad (as a matrix whose COLUMNS are the axes) from
   a primary direction and a hint. nil when the two are parallel — there is no
   frame to build, and saying so is the point."
  [primary hint]
  (let [u1 (m/normalize primary)
        proj (m/v* u1 (m/dot hint u1))
        rest- (m/v- hint proj)]
    (when (> (la/v-norm rest-) 1e-6)
      (let [u2 (m/normalize rest-)
            u3 (m/cross u1 u2)]
        ;; columns u1 u2 u3
        [[(nth u1 0) (nth u2 0) (nth u3 0)]
         [(nth u1 1) (nth u2 1) (nth u3 1)]
         [(nth u1 2) (nth u2 2) (nth u3 2)]]))))

(defn- centroid [ps]
  (let [n (max 1 (count ps))]
    (m/v* (reduce m/v+ [0.0 0.0 0.0] ps) (/ 1.0 n))))

(defn plane-anchor?
  "Is this anchor to be believed as a PLANE (normal + distance) rather than as a
   point?

   It matters more than it looks (Vincenzo 2026-08-05: 'spero conti solo il
   piano, perché è riscontrabile facilmente nelle diverse sessioni, mentre un
   punto specifico è molto più difficile trovarlo'). He is right, and the
   geometry agrees: the origin of a plane mark is the centroid of wherever the
   user happened to click, or wherever they placed it by hand — it is NOT a
   reproducible feature of the object, while the plane IS. Believing the origin
   would import the click noise of two sessions into the fusion for nothing.

   So a mark with a normal counts as a plane by default. A mark whose origin
   really is a physical point — a corner, a printed dot — has to say so with
   `:point? true`, and then its full position is used."
  [a]
  (and (:from-dir a) (:to-dir a) (not (:point? a)) (not (:edge? a))))

(defn edge-anchor?
  "Is this anchor a measured EDGE — a line, not a plane and not a point?

   An edge is the strongest anchor this channel can produce, and for a while it
   was the one the fusion could not read. It pins four degrees of freedom to a
   plane's three; it is measured along its whole length rather than at one spot;
   and — the part that matters most here — declaring it needs no point paired
   with any other point, which is the thing that makes plane marks easy and
   clicked points hard.

   It arrived because a real object refused the alternatives (Vincenzo,
   2026-08-14): a coffee grinder whose only two flat zones are PARALLEL. Two
   parallel planes are one direction, no amount of care makes them two, and the
   remaining advice — a mark on a real point — named a gesture that does not
   exist. Meanwhile four measured edges per session sat in `:edges`, unused.

   Like a plane, an edge has NO SIDE: a stroke painted from either end describes
   the same line, so only the line is believed, never the arrow. And like a
   plane's origin, WHERE along it you happened to paint is not a feature — so
   sliding along the edge costs nothing."
  [a]
  (and (:edge? a) (:from-dir a) (:to-dir a)))

(defn- independent-normals?
  "Do these normals span 3D — i.e. do the planes pin the translation from every
   side? Three planes with independent normals determine a rigid motion outright;
   two leave the slide along their line of intersection free, and no amount of
   least squares invents it."
  [normals]
  (boolean
   (when (>= (count normals) 3)
     (some (fn [[a b c]]
             (> (js/Math.abs (m/dot (m/cross a b) c)) 0.05)) ; ~ sin of the solid angle
           (for [i (range (count normals))
                 j (range (inc i) (count normals))
                 k (range (inc j) (count normals))]
             [(m/normalize (nth normals i))
              (m/normalize (nth normals j))
              (m/normalize (nth normals k))])))))

(defn- seed-from-planes
  "Closed-form (R, t) from plane correspondences ALONE.

   Rotation: two non-parallel normals already fix it — a triad from each side and
   compose. Translation: with three independent normals, `t` is the solution of
   the 3x3 system that puts each transported plane back on its twin,
   `t · n = (p_to − R·p_from) · n`. No origin is ever compared to an origin."
  [anchors]
  (let [ps (filterv plane-anchor? anchors)]
    (when (and (>= (count ps) 3)
               (independent-normals? (mapv :to-dir ps)))
      (let [nf (mapv #(m/normalize (:from-dir %)) ps)
            nt (mapv #(m/normalize (:to-dir %)) ps)
            ;; the two most nearly perpendicular normals make the sturdiest triad
            [i j] (first (sort-by (fn [[i j]] (js/Math.abs (m/dot (nth nt i) (nth nt j))))
                                  (for [i (range (count ps))
                                        j (range (inc i) (count ps))] [i j])))
            Ff (triad (nth nf i) (nth nf j))
            Ft (triad (nth nt i) (nth nt j))]
        (when (and Ff Ft)
          (let [R (la/mat*mat Ft (la/transpose Ff))
                ;; rows of the system: one per plane, n · t = (p_to − R·p_from) · n
                rows (mapv vec nt)
                rhs (mapv (fn [a n] (m/dot (la/v-sub (:to-pos a) (la/mat*vec R (:from-pos a))) n))
                          ps nt)
                ;; least squares through the normal equations: works with 3 planes
                ;; and improves with more
                A (la/transpose rows)
                t (la/solve (la/mat*mat A rows) (la/mat*vec A rhs))]
            (when t {:R R :t t})))))))

(defn- seed-from-points
  "Closed-form (R, t) taking the `from` anchors onto the `to` anchors as POINTS.

   With three or more anchors the origins alone give the frame. With exactly
   two, the baseline gives one direction and the first anchor's normal gives the
   second. Returns nil when the geometry does not determine a frame."
  [anchors]
  (let [pf (mapv :from-pos anchors)
        pt (mapv :to-pos anchors)
        base-f (m/v- (nth pf 1) (nth pf 0))
        base-t (m/v- (nth pt 1) (nth pt 0))
        [hint-f hint-t] (if (>= (count anchors) 3)
                          [(m/v- (nth pf 2) (nth pf 0)) (m/v- (nth pt 2) (nth pt 0))]
                          [(:from-dir (first anchors)) (:to-dir (first anchors))])
        ;; two anchors and no normals: nothing supplies the second direction,
        ;; and the rotation about the baseline stays free. Same answer as
        ;; "they are collinear" — no frame, say so.
        Ff (when hint-f (triad base-f hint-f))
        Ft (when hint-t (triad base-t hint-t))]
    (when (and Ff Ft)
      (let [R (la/mat*mat Ft (la/transpose Ff))
            t (la/v-sub (centroid pt) (la/mat*vec R (centroid pf)))]
        {:R R :t t}))))

(defn- seed-once
  "The seed for ONE sign assignment: planes first, so that when the plane marks
   alone determine the motion the origins are never consulted."
  [anchors]
  (or (seed-from-planes anchors)
      (when (>= (count anchors) 2) (seed-from-points anchors))))

(defn- flip-planes
  "The anchors with the plane normals selected by `mask` turned around."
  [anchors mask]
  (let [idx (into {} (map-indexed (fn [i a] [(:name a) i]) (filterv plane-anchor? anchors)))]
    (mapv (fn [a] (if (and (plane-anchor? a) (bit-test mask (get idx (:name a) 0)))
                    (update a :from-dir #(m/v* (vec %) -1.0))
                    a))
          anchors)))

;; ------------------------------------------------------------
;; The refinement
;; ------------------------------------------------------------

(def ^:private plane-normal-arm-mm
  "Lever arm that turns a normal mismatch into millimetres for a PLANE anchor,
   where the normal is a primary constraint and not a hint. 20 mm is the order
   of the objects this channel handles, so 1° of tilt weighs like the 0.35 mm it
   actually costs at the far edge of a piece that size."
  20.0)

;; `up` stays OUT of the cost entirely, for both kinds — it is projected from the
;; object's own pose, which differs BY CONSTRUCTION between two sessions.

(defn- rt-of-params [p]
  {:R (cam/rodrigues [(nth p 0) (nth p 1) (nth p 2)])
   :t [(nth p 3) (nth p 4) (nth p 5)]})

(defn- anchor-residuals
  "The residuals of ONE anchor, in millimetres, before weighting.

   A PLANE anchor contributes what a plane actually knows: how far its
   transported origin sits OFF the twin plane (one number, along the normal —
   sliding within the plane costs nothing, because a plane mark's origin is not
   a reproducible feature) plus the misalignment of the normals.

   An EDGE anchor contributes the distance between the two LINES (the part of
   the displacement across the edge — sliding along it is free, for the same
   reason a plane's origin is free) plus the misalignment of the directions,
   taken without a sign because a stroke painted from either end is the same
   line.

   A POINT anchor contributes the full three-component displacement, because
   there the origin IS the claim.

   Each KIND has a fixed length — 4 for a plane or a point, 6 for an edge — which
   is what lm/solve needs: the residual vector must not change length between
   iterations, and since an anchor never changes kind, it does not. Within a
   plane, the in-plane freedom is expressed as a zero rather than as a missing
   residual, for the same reason."
  [rt {:keys [from-pos to-pos from-dir to-dir] :as a}]
  (let [moved (transform-point rt from-pos)
        d (la/v-sub moved to-pos)]
    (cond
      (edge-anchor? a)
      (let [t (m/normalize to-dir)
            moved-d (transform-dir rt from-dir)
            ;; no side: compare the LINES, not the arrows (see edge-anchor?)
            s (if (neg? (m/dot moved-d t)) -1.0 1.0)
            ;; only the part of the displacement ACROSS the edge; along it is free
            perp (la/v-sub d (la/v-scale t (m/dot d t)))
            dd (la/v-scale (la/v-sub (la/v-scale moved-d s) t) plane-normal-arm-mm)]
        (into (vec perp) dd))

      (plane-anchor? a)
      (let [n (m/normalize to-dir)
            moved-n (transform-dir rt from-dir)
            ;; A PLANE HAS NO SIDE. The stage points a fitted normal 'toward the
            ;; cameras', which on a turntable means toward their MEAN — and with
            ;; shots taken all the way round, the mean sits near the axis, so
            ;; which side of the plane it falls on is very nearly a coin toss
            ;; (Vincenzo's own three sessions, 2026-08-06: the same three zones
            ;; came out with the two handednesses, which no rotation can
            ;; reconcile). So the sign is not information: take whichever of ±n
            ;; is closer and compare the LINES, not the rays.
            s (if (neg? (m/dot moved-n n)) -1.0 1.0)
            dn (la/v-scale (la/v-sub (la/v-scale moved-n s) n) plane-normal-arm-mm)]
        (into [(m/dot d n)] dn))

      ;; A POINT contributes its position and NOTHING ELSE. Declaring `:point?`
      ;; says 'believe where this is'; it says nothing about a direction, and the
      ;; :heading such a mark carries is whatever the plane gesture happened to
      ;; leave there — [0 0 1] for the one-click plate-parallel case. Weighing it
      ;; wrecked the very fit the point was added to rescue (Vincenzo 2026-08-06:
      ;; the notch reported 84° and 170° of 'normal' error, and dragged the
      ;; rotation with it at a 5 mm arm).
      :else
      [(nth d 0) (nth d 1) (nth d 2) 0.0])))

(defn- residual-fn [anchors sigma-mm]
  (fn [p]
    (let [rt (rt-of-params p)]
      (vec (mapcat (fn [a] (mapv #(/ % sigma-mm) (anchor-residuals rt a))) anchors)))))

(defn- origin-spread-mm
  "How far the transported origins land from their twins, in millimetres.

   NOT a constraint — a plane mark's origin is wherever the clicking happened,
   and pinning it was the whole thing this design got rid of. It is a TIE-BREAK,
   and it is needed because sign-free normals leave a discrete ambiguity that
   nothing else can settle: three planes taken as unsigned admit four rotations
   (the identity and the three half-turns that map the triple onto itself), and
   all four fit the planes exactly — same zero millimetres, same fraction of a
   degree. They place the object in four very different attitudes, though, tens
   of millimetres apart, and about THAT the origins are entirely trustworthy.
   So: the planes decide the geometry, the origins only say which branch.

   Without it the fusion picked a branch per session pair and the loop did not
   close — 33 mm around A→B→C→A on Vincenzo's own three sessions (2026-08-06),
   with every pairwise report claiming a perfect fit."
  [rt anchors]
  (Math/sqrt (/ (reduce + 0.0
                        (map (fn [{:keys [from-pos to-pos]}]
                               (let [d (la/v-sub (transform-point rt from-pos) to-pos)]
                                 (m/dot d d)))
                             anchors))
                (max 1 (count anchors)))))

(defn- seed-rt
  "The seed, searched over the SIGNS of the plane normals.

   The residual no longer cares which way a plane's normal points, but the
   closed-form seed does: it composes triads out of those very vectors, and a
   normal pointing the other way is a different branch entirely. The signs are
   few and discrete (2^k over the planes), so they are enumerated rather than
   guessed: build the seed for each assignment, keep those that fit the planes
   as well as the best one does, and among THOSE take the one that puts the
   object where the origins say it is."
  [anchors sigma-mm]
  (let [k (count (filterv plane-anchor? anchors))
        masks (if (<= 1 k 6) (range (bit-shift-left 1 k)) [0])
        rfn (residual-fn anchors sigma-mm)
        cost-of (fn [s] (lm/cost (rfn (vec (concat (cam/rot-mat->rodrigues (:R s)) (:t s))))))
        cands (mapv (fn [s] {:seed s :cost (cost-of s) :spread (origin-spread-mm s anchors)})
                    (keep (fn [m] (seed-once (flip-planes anchors m))) masks))]
    (when (seq cands)
      (let [best (reduce min (map :cost cands))
            ;; 'as well as the best one' with room for the difference between a
            ;; seed and its refinement; the branches this must separate are not
            ;; close calls.
            tied (filter #(<= (:cost %) (+ (* 1.05 best) 1.0)) cands)]
        (:seed (apply min-key :spread tied))))))

(def ^:private branch-gap-mm
  "How far apart two placements must be before they count as different answers
   rather than the same one twice."
  2.0)

(defn- outward-votes
  "How many of the anchors' recorded normals this placement agrees with.

   Taken as unsigned, three planes are satisfied by four placements — the
   identity and the half-turns that map the triple onto itself — and on a nearly
   symmetric object those are mirror images of one another. Nothing in the plane
   geometry separates them, which is what Vincenzo saw across nine photos
   ('specchiato', session by session, 2026-08-06).

   But the sign is not nothing. The stage points a fitted normal toward the
   cameras that SAW the zone, and a face is only visible from outside it — so
   the recorded direction is usually the outward one, just not reliably enough
   to be believed one mark at a time. Believed by MAJORITY it is exactly right:
   the correct placement agrees with most of them, and when the vote is tied the
   honest answer is that the anchors do not say."
  [rt anchors]
  (->> anchors
       (filter plane-anchor?)
       (map (fn [{:keys [from-dir to-dir]}]
              (if (pos? (m/dot (transform-dir rt from-dir) (m/normalize to-dir))) 1 0)))
       (reduce + 0)))

(defn- probe-point
  "A point out at the object's own scale, where two placements that differ show
   the difference in millimetres."
  [anchors]
  (let [c (centroid (mapv :from-pos anchors))
        r (reduce max 1.0 (map #(la/v-norm (la/v-sub (:from-pos %) c)) anchors))]
    (la/v-add c [r r r])))

(defn- branches
  "Every placement these anchors admit, refined, with its cost and its vote."
  [anchors sigma-mm]
  (let [k (count (filterv plane-anchor? anchors))
        masks (if (<= 1 k 6) (range (bit-shift-left 1 k)) [0])
        rfn (residual-fn anchors sigma-mm)]
    (->> masks
         (keep (fn [m] (seed-once (flip-planes anchors m))))
         (mapv (fn [s]
                 (let [res (lm/solve rfn (vec (concat (cam/rot-mat->rodrigues (:R s)) (:t s)))
                                     {:max-iterations 120})
                       rt (rt-of-params (:params res))]
                   {:rt rt :params (:params res) :cost (:cost res)
                    :votes (outward-votes rt anchors)}))))))

(defn- per-anchor
  "What each anchor costs after the fit. For a plane: its distance from the twin
   PLANE (mm) and how far the normal is turned (degrees) — deliberately NOT how
   far the two origins ended up from each other, which is not an error. For a
   point: the full distance between the origins.

   This is the table that lets a wrong twin be found instead of averaged in."
  [rt anchors]
  (mapv (fn [{:keys [name from-pos to-pos from-dir to-dir] :as a}]
          (let [moved (transform-point rt from-pos)
                plane? (plane-anchor? a)
                d (if plane?
                    (js/Math.abs (m/dot (la/v-sub moved to-pos) (m/normalize to-dir)))
                    (la/v-norm (la/v-sub moved to-pos)))
                ;; only a plane has an orientation to be wrong about; a point's
                ;; :heading is not part of what it claims
                ang (when (and plane? from-dir to-dir)
                      ;; between LINES, not rays: a plane has no side
                      (let [c (max -1.0 (min 1.0 (js/Math.abs
                                                  (m/dot (transform-dir rt from-dir)
                                                         (m/normalize to-dir)))))]
                        (* (/ 180.0 Math/PI) (Math/acos c))))]
            {:name name :kind (if plane? :piano :punto) :residual-mm d :normal-deg ang}))
        anchors))

(defn- angle-deg [a b]
  (let [c (max -1.0 (min 1.0 (m/dot (m/normalize a) (m/normalize b))))]
    (* (/ 180.0 Math/PI) (Math/acos c))))

(def ^:private angle-tol-deg
  "How far the angle between two anchor normals may differ between the two
   sessions before they cannot be the same pair of zones. Fitting a plane
   through hand-clicked points on a small face is worth a few degrees; ten is
   well past that and well short of the tens of degrees a wrong correspondence
   produces."
  10.0)

(defn normal-consistency-error
  "A check that needs NO fit: the angles BETWEEN the anchor normals are the same
   in both sessions, because a rigid motion does not change angles. If two
   anchors are 116° apart in one session and 66° in the other, they are not the
   same pair of zones, and no rotation will make them so.

   This is the check that was missing when the fusion happily returned a motion
   with normals 152° out and an rms of zero (Vincenzo 2026-08-06): the distance
   residuals alone can ALWAYS be driven to zero — three planes, six unknowns —
   so the numbers looked perfect while the answer was rubbish.

   Returns a human sentence, or nil when the anchors are mutually consistent."
  [anchors]
  ;; Edges belong in this check as much as planes do: the angle between two
  ;; measured lines is just as invariant under a rigid motion as the angle
  ;; between two normals, and a mis-paired edge is exactly as fatal. Only points
  ;; stay out — a point carries no direction to take an angle with.
  (let [ps (filterv #(or (plane-anchor? %) (edge-anchor? %)) anchors)
        ;; ACUTE angles: neither a plane nor an edge has a side
        ;; (anchor-residuals), so 116° and 64° between the same two are the same
        ;; statement.
        acute (fn [a b] (let [x (angle-deg a b)] (min x (- 180.0 x))))
        pairs (for [i (range (count ps)) j (range (inc i) (count ps))]
                (let [a (nth ps i) b (nth ps j)
                      af (acute (:from-dir a) (:from-dir b))
                      at (acute (:to-dir a) (:to-dir b))]
                  {:a (:name a) :b (:name b) :from af :to at
                   :delta (js/Math.abs (- af at))}))
        bad (filter #(> (:delta %) angle-tol-deg) pairs)]
    (when (seq bad)
      (let [w (apply max-key :delta bad)]
        (str "gli agganci non possono essere le stesse zone: fra " (:a w) " e " (:b w)
             " le facce formano " (js/Math.round (:from w)) "° in una sessione e "
             (js/Math.round (:to w)) "° nell'altra, e l'angolo fra due facce non "
             "cambia muovendo l'oggetto. Controlla di aver marcato le stesse zone "
             "in tutte e due — su un pezzo con facce parallele è facile prendere "
             "quella sbagliata")))))

(def min-baseline-mm
  "POINT anchors closer together than this do not span the object: the rotation
   they determine is as noisy as the clicks, amplified by the ratio of the
   object's size to the baseline. Planes are exempt — their constraint is the
   normal, and two parallel planes are refused by rank, not by distance."
  5.0)

(def ^:private rank-floor
  "Smallest/largest eigenvalue of JᵀJ below which the anchors leave a direction
   of the motion FREE. A genuine rank deficiency lands near 1e-16; ordinary
   ill-conditioning stays orders of magnitude above 1e-6. Guarding on the rank of
   the actual system, rather than on a checklist of cases, is what makes 'two
   planes plus one point' work without anyone having enumerated it."
  1e-6)

(defn- underdetermined?
  "Does the fitted system leave a degree of freedom unconstrained? Asked of the
   Jacobian at the solution — the same object `lm/covariance-spectrum` exists
   for."
  [rfn params]
  (let [ev (lm/covariance-spectrum rfn params)
        hi (reduce max 0.0 (map js/Math.abs ev))
        lo (reduce min js/Number.MAX_VALUE (map js/Math.abs ev))]
    (or (< (count ev) 6) (< hi 1e-12) (< (/ lo hi) rank-floor))))

(defn fit-rigid
  "The rigid motion carrying `anchors`' :from poses onto their :to poses.

   Each anchor is {:name :from-pos :to-pos :from-dir :to-dir [:point? bool]}.
   With a normal and no `:point?` it is believed as a PLANE: its origin is free
   to slide within the plane, because a plane mark's origin is not a
   reproducible feature of the object (see plane-anchor?). Three planes with
   independent normals determine the motion outright; two leave the slide along
   their intersection free, and that is refused rather than guessed.

   Returns {:R :t :rvec :rms-mm :max-mm :per-anchor :n :planes :points} or
   {:error <human sentence>}."
  ([anchors] (fit-rigid anchors {}))
  ([anchors {:keys [sigma-mm no-loo?] :or {sigma-mm 0.2}}]
   (let [planes (filterv plane-anchor? anchors)
         edges (filterv edge-anchor? anchors)
         ;; Edges are not points, and lumping them in here was a real fault: an
         ;; edge's :position is WHEREVER the painting started, free to slide along
         ;; the line, so two edges whose stored positions happen to fall close
         ;; together would have been refused for a "short baseline" that means
         ;; nothing about them.
         points (filterv #(and (not (plane-anchor? %)) (not (edge-anchor? %))) anchors)
         short-baseline? (and (>= (count points) 2)
                              (< (la/v-norm (la/v-sub (:from-pos (second points))
                                                      (:from-pos (first points))))
                                 min-baseline-mm))]
     (cond
       (< (count anchors) 2)
       {:error (str "servono almeno DUE agganci fra le due sessioni (ne ho trovato "
                    (count anchors) "). Con i piani ne servono TRE, con le normali "
                    "che guardano in direzioni diverse")}

       short-baseline?
       {:error (str "i mark-punto di aggancio sono troppo vicini fra loro (meno di "
                    min-baseline-mm " mm): la rotazione che determinano è rumore. "
                    "Prendine due lontani, agli estremi dell'oggetto")}

       ;; BEFORE fitting: the angles between the normals must already agree.
       ;; Fitting first and judging after does not work here — the distance part
       ;; alone is always satisfiable, so a wrong correspondence comes back
       ;; wearing a perfect residual.
       (normal-consistency-error anchors)
       {:error (normal-consistency-error anchors)}

       :else
       (let [rfn (residual-fn anchors sigma-mm)
             cands (branches anchors sigma-mm)
             best-cost (reduce min js/Number.MAX_VALUE (map :cost cands))
             ;; every placement that fits the PLANES as well as the best one:
             ;; between these, the plane geometry has nothing more to say
             tied (filterv #(<= (:cost %) (+ (* 1.2 best-cost) 1.0)) cands)
             probe (probe-point anchors)
             ;; The recorded normals only choose the wording of the refusal, never
             ;; the answer. Believing them by majority was tried and measured on
             ;; Vincenzo's three sessions (2026-08-06): it picked branches that
             ;; contradicted each other, the loop closing at 80 mm while every
             ;; pairwise report claimed a fraction of a degree. Believing them
             ;; when unanimous was tried too, and turning the one dissenter round
             ;; by hand left the loop at 33 mm — because unanimity among signs
             ;; that are individually unreliable is not evidence either.
             ;;
             ;; So: if a rival placement fits, REFUSE. What makes a fusion
             ;; unambiguous is not a tiebreak, it is anchors that are themselves
             ;; asymmetric — a point, or planes enough that no half-turn maps the
             ;; set onto itself. Then no rival fits and nothing has to be chosen.
             ;; COST first, votes only to break a tie: what the anchors measure
             ;; outranks what their normals claim.
             winner (first (sort-by (juxt :cost #(- (:votes %))) tied))
             rival (first (filter (fn [c]
                                    (> (la/v-norm (la/v-sub (transform-point (:rt c) probe)
                                                            (transform-point (:rt winner) probe)))
                                       branch-gap-mm))
                                  tied))
             dissenting (when rival
                          (->> (filter plane-anchor? anchors)
                               (filter (fn [{:keys [from-dir to-dir]}]
                                         (neg? (m/dot (transform-dir (:rt winner) from-dir)
                                                      (m/normalize to-dir)))))
                               (mapv :name)))]
         (if (empty? cands)
           {:error (str "i mark di aggancio non determinano una rotazione: sono allineati, "
                        "oppure le loro normali sono parallele fra loro. "
                        "Serve un aggancio che guardi in un'altra direzione")}
           (let [res {:params (:params winner) :cost (:cost winner)}
                 rt (:rt winner)
                 pa (per-anchor rt anchors)
                 ds (mapv :residual-mm pa)]
             ;; 'not enough constraints' comes BEFORE 'more than one answer':
             ;; when both are true, the first is the more useful thing to be told.
             (if (underdetermined? rfn (:params res))
               {:error (str "questi agganci non fissano tutto il movimento: "
                            (if (and (>= (count planes) 2) (empty? points))
                              ;; Naming the point-mark first was writing a cheque the
                              ;; app cannot cash: nothing in the UI produces a mark
                              ;; whose ORIGIN is a physical feature. The plane gesture's
                              ;; origin is the centroid of wherever you clicked, so
                              ;; declaring `:point? true` on one would be declaring
                              ;; something untrue (Vincenzo 2026-08-14: «come faccio a
                              ;; inserirlo?» — he could not, and there was no way to
                              ;; know that from here). So the reachable remedy goes
                              ;; first, and the point is named as what it is: something
                              ;; you can only write by hand, if you have the numbers.
                              (str "due piani lasciano libero lo scorrimento lungo la loro "
                                   "intersezione. Il modo più semplice di chiuderlo è uno "
                                   "SPIGOLO: misuralo in tutte e due le sessioni e "
                                   "chiamalo con lo stesso nome fra gli :edges — vale come "
                                   "aggancio da solo, e non chiede di appaiare nessun punto "
                                   "fra le foto. In alternativa un terzo piano con la "
                                   "normale in un'altra direzione")
                              (str "gli agganci sono allineati o le normali sono tutte "
                                   "parallele fra loro. Serve un aggancio fuori da quella "
                                   "direzione")))}
               (if rival
                 {:error (str "questi agganci ammettono DUE sistemazioni lontane "
                              (js/Math.round (la/v-norm (la/v-sub (transform-point (:rt rival) probe)
                                                                  (transform-point rt probe))))
                              " mm l'una dall'altra, e combaciano ugualmente bene: su un "
                              "pezzo quasi simmetrico sono l'una lo specchio dell'altra. "
                              "Tre facce da sole non bastano MAI a distinguerle: una mezza "
                              "rotazione attorno a una qualunque delle tre normali riporta "
                              "le tre facce su se stesse. Non è una questione di "
                              "precisione, e non posso sceglierne una a caso. Serve un "
                              "aggancio ASIMMETRICO: un dettaglio che esista da una parte "
                              "sola — uno spigolo, un rilievo, una tacca — marcato in "
                              "entrambe le sessioni e dichiarato punto vero con "
                              "`(plane-mark {… :point? true})`. In alternativa una quarta "
                              "faccia messa di traverso"
                              (when (seq dissenting)
                                (str ". Per inciso: il verso di "
                                     (apply str (interpose " e " (map str dissenting)))
                                     " contraddice quello degli altri mark, quindi quello "
                                     "è comunque da rivedere")))}
                 (assoc rt
                        :rvec (vec (take 3 (:params res)))
                        :n (count anchors)
                        :planes (count planes)
                        :points (count points)
                        :edges (count edges)
                        :per-anchor pa
                    ;; reported NEXT TO the distance rms, never instead of it: with
                    ;; three planes the distances alone can always be zeroed, so a
                    ;; distance-only verdict says 'perfect' about anything.
                        :max-normal-deg (reduce max 0.0 (keep :normal-deg pa))
                    ;; Can the DISTANCES testify? Each plane pins the translation
                    ;; along one direction, each point along three. With exactly
                    ;; three planes that is three equations in three unknowns:
                    ;; the millimetres come out at zero whatever was marked, and
                    ;; only the normals carry information (they are 2 constraints
                    ;; each against 3 rotational unknowns, so they are checked
                    ;; from the third plane on).
                        :distances-testify? (> (+ (count planes) (* 3 (count points))) 3)
                        :rms-mm (Math/sqrt (/ (reduce + 0.0 (map #(* % %) ds)) (count ds)))
                        :max-mm (reduce max 0.0 ds)
                        ;; LEAVE-ONE-OUT, the same move the PnP makes on a
                        ;; mislabelled corner: least squares spreads the damage,
                        ;; so a single wrong anchor does NOT show up as one big
                        ;; residual — it shows up as everything being a bit off.
                        ;; Dropping each in turn is what tells them apart. On
                        ;; Vincenzo's session B (2026-08-06) the full fit read
                        ;; 2.5 mm and every anchor looked mediocre; without
                        ;; :flank it read 0.07 mm, and :flank was indeed the
                        ;; opposite face of a piece that has two alike.
                        :suspect
                        (when-not no-loo?
                          (let [full (Math/sqrt (/ (reduce + 0.0 (map #(* % %) ds)) (count ds)))]
                            (when (> full 0.4)
                              (->> anchors
                                   (keep (fn [drop-me]
                                           (let [rest- (filterv #(not= (:name %) (:name drop-me)) anchors)
                                                 f (fit-rigid rest- {:sigma-mm sigma-mm :no-loo? true})]
                                             ;; …and only when what is LEFT can still be
                                             ;; contradicted. Drop the point from three
                                             ;; planes plus a point and the residual falls
                                             ;; to zero by construction, which would accuse
                                             ;; the one anchor that was doing its job.
                                             (when (and (nil? (:error f))
                                                        (:distances-testify? f)
                                                        (< (:rms-mm f) (/ full 4.0)))
                                               {:name (:name drop-me) :rms-without (:rms-mm f)}))))
                                   (sort-by :rms-without)
                                   first))))))))))))))

;; Homonymous marks are NOT averaged into one: with plane semantics the two
;; origins are legitimately different points ON THE SAME PLANE, so their mean is
;; a third arbitrary point and no better than either. The reference session's
;; mark is kept as it is — one rule, and the one the user can predict.

;; ------------------------------------------------------------
;; Declaring the correspondences
;; ------------------------------------------------------------
;;
;; Name equality stops being a good enough declaration the moment a mark is
;; believed as a PLANE (Vincenzo 2026-08-05): two marks called `:piano-1` then
;; agree about the plane and disagree about where the origin sits on it, both
;; legitimately — so which one survives cannot be decided by the fuser, and both
;; are worth keeping. Hence labelled sessions and an explicit list of pairs:
;;
;;   (acquire-union [[:A a] [:B b]] [[:A/piano-1 :B/piano-1] [:A/becco :B/piano-2]])

(defn anchor-of
  "One correspondence, as fit-rigid wants it: `to` is the reference session's
   mark, `from` the one to be carried onto it. nil when either is unusable."
  [nm to from]
  (when (and (:position to) (:position from))
    {:name nm
     :from-pos (vec (:position from)) :to-pos (vec (:position to))
     :from-dir (some-> (:heading from) vec) :to-dir (some-> (:heading to) vec)
     ;; opt-in: this mark's ORIGIN is a real physical point (a corner, a printed
     ;; dot), so use its full position and not only the plane it lies on.
     ;; Declared in the source — `(plane-mark {… :point? true})` — because only
     ;; the person who clicked it knows whether it is reproducible.
     :point? (boolean (or (:point? to) (:point? from)))}))

(defn edge-anchor-of
  "One correspondence between two MEASURED EDGES — the same physical edge seen in
   two sessions. `to` is the reference session's edge, `from` the one to be
   carried onto it; both are `edge-mark` values, so :position is one end and
   :heading runs along the edge.

   Neither the end you started painting from nor the direction you painted in is
   a claim about the object: only the LINE is (see edge-anchor?). nil when either
   is unusable."
  [nm to from]
  (when (and (:position to) (:position from) (:heading to) (:heading from))
    {:name nm
     :edge? true
     :from-pos (vec (:position from)) :to-pos (vec (:position to))
     :from-dir (vec (:heading from)) :to-dir (vec (:heading to))}))

(defn resolve-ref
  "`:A/piano-1` against `sessions` ([[label marks edges] …]) → [label name value
   kind], kind being :mark or :edge — or {:missing <human sentence>} naming
   exactly what could not be found.

   Marks are looked up first and edges second, so a name that exists as both
   resolves to the mark. That is not arbitrary: everywhere else in this channel a
   mark wins a name clash over an edge and says so, and the escape hatch must not
   quietly disagree with the implicit rule it exists to override."
  [sessions ref]
  (let [lbl (some-> (namespace ref) keyword)
        nm (keyword (name ref))
        entry (some (fn [e] (when (= lbl (first e)) e)) sessions)
        marks (second entry)
        edges (nth entry 2 nil)]
    (cond
      (nil? lbl) {:missing (str ref " non dice a quale sessione appartiene: "
                                "scrivilo come :etichetta/nome-del-mark")}
      (nil? entry) {:missing (str "l'etichetta :" (name lbl) " di " ref
                                  " non è fra le sessioni passate")}
      (get marks nm) [lbl nm (get marks nm) :mark]
      (get edges nm) [lbl nm (get edges nm) :edge]
      :else {:missing (str "il mark " ref " non esiste in quella sessione "
                           "(né fra i :marks né fra gli :edges)")})))

(defn declared-anchors
  "Correspondences between the reference session and the one labelled `lbl`,
   from the declared pair list. `sessions` is [[label marks edges] …].
   Returns [anchors errors]; an anchor is named after its SOURCE side
   (`:B/piano-1`), so the residual table says which mark of which session cost
   what."
  [sessions pairs ref-lbl lbl]
  (let [resolved (mapv (fn [pair] (mapv #(resolve-ref sessions %) pair)) pairs)
        errs (->> resolved (mapcat identity) (keep :missing) distinct vec)
        pick (fn [refs l] (some (fn [r] (when (and (vector? r) (= l (first r))) r)) refs))
        mismatch (atom [])
        anchors (->> resolved
                     (keep (fn [refs]
                             (let [to (pick refs ref-lbl) from (pick refs lbl)]
                               (when (and to from)
                                 (let [nm (keyword (name (first from)) (name (second from)))]
                                   ;; A plane paired with an edge is not a
                                   ;; correspondence — the two say different kinds
                                   ;; of thing about the object, and fitting one to
                                   ;; the other would be believing a normal is a
                                   ;; direction along an edge. Refuse by name.
                                   (if (not= (nth to 3) (nth from 3))
                                     (do (swap! mismatch conj
                                                (str "l'aggancio fra " (second to) " e " nm
                                                     " mette insieme un piano e uno spigolo: "
                                                     "sono due cose diverse e non si "
                                                     "corrispondono"))
                                         nil)
                                     (assoc ((if (= :edge (nth to 3)) edge-anchor-of anchor-of)
                                             nm (nth to 2) (nth from 2))
                                            ;; the zone's name on the REFERENCE side: what
                                            ;; the fused value calls the zone itself
                                            :ref-name (second to))))))))
                     vec)]
    [anchors (vec (distinct (concat errs @mismatch)))]))

(defn shared-name-anchors
  "Correspondences by EQUAL NAME between the reference session's marks and
   another's — the implicit declaration.

   Implicit matching was a hazard while the stage's auto-names (`:piano-1`,
   `:piano-2`, …) were the only names around: two sessions collided by counting,
   not by meaning. It stops being a hazard once each session's marks stay
   addressable as `:label/name` (Vincenzo 2026-08-05): then giving the same zone
   the same name in two sessions is a DELIBERATE act, and the deliberate act is
   exactly what a declaration is. Renaming a mark is a text edit in the source,
   done while you still remember which zone it was.

   Marks AND edges, never `:faces` — the faces are generated per proxy, so a
   shared `:top` would be a false correspondence between two different boxes and
   the fit would believe it. Marks and edges are kept apart everywhere else in
   this channel precisely because they mean different things, and that is why
   they can share this rule safely: a name is matched against its own kind, so a
   mark called `:becco` and an edge called `:becco` never pair with each other."
  ([ref-marks marks] (shared-name-anchors ref-marks marks nil nil))
  ([ref-marks marks ref-edges edges]
   (->> (concat
         (keep (fn [nm]
                 (some-> (anchor-of nm (get ref-marks nm) (get marks nm))
                         (assoc :ref-name nm)))
               (keys marks))
         (keep (fn [nm]
                 (some-> (edge-anchor-of nm (get ref-edges nm) (get edges nm))
                         (assoc :ref-name nm)))
               (keys edges)))
        (sort-by :name)
        vec)))

(defn worst-anchor
  "The anchor whose residual stands out from the others — a candidate for a
   misnamed or mis-clicked twin — or nil when they all cost about the same.
   Leave-one-out is not needed to point the finger when one residual is several
   times the median; it would be, to prove it."
  [per-anchor-rows]
  (when (>= (count per-anchor-rows) 3)
    (let [ds (sort (mapv :residual-mm per-anchor-rows))
          med (nth ds (quot (count ds) 2))
          worst (apply max-key :residual-mm per-anchor-rows)]
      (when (> (:residual-mm worst) (max 0.5 (* 3.0 med)))
        worst))))
