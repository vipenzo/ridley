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
  (and (:from-dir a) (:to-dir a) (not (:point? a))))

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

(defn- seed-rt
  "The seed, planes first: if the plane marks alone determine the motion, the
   origins are never consulted — which is the whole point of preferring planes."
  [anchors]
  (or (seed-from-planes anchors)
      (when (>= (count anchors) 2) (seed-from-points anchors))))

;; ------------------------------------------------------------
;; The refinement
;; ------------------------------------------------------------

(def ^:private plane-normal-arm-mm
  "Lever arm that turns a normal mismatch into millimetres for a PLANE anchor,
   where the normal is a primary constraint and not a hint. 20 mm is the order
   of the objects this channel handles, so 1° of tilt weighs like the 0.35 mm it
   actually costs at the far edge of a piece that size."
  20.0)

(def ^:private point-normal-arm-mm
  "The same for a POINT anchor, where the position carries the constraint and the
   normal only nudges: a tenth of the plane arm."
  5.0)

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

   A POINT anchor contributes the full three-component displacement, because
   there the origin IS the claim.

   Constant length either way (4 numbers), because lm/solve needs it: a plane's
   in-plane freedom is expressed as a zero, not as a missing residual."
  [rt {:keys [from-pos to-pos from-dir to-dir] :as a}]
  (let [moved (transform-point rt from-pos)
        d (la/v-sub moved to-pos)]
    (if (plane-anchor? a)
      (let [n (m/normalize to-dir)
            dn (la/v-scale (la/v-sub (transform-dir rt from-dir) n) plane-normal-arm-mm)]
        (into [(m/dot d n)] dn))
      (let [dn (if (and from-dir to-dir)
                 (la/v-scale (la/v-sub (transform-dir rt from-dir) (m/normalize to-dir))
                             point-normal-arm-mm)
                 [0.0 0.0 0.0])]
        [(nth d 0) (nth d 1) (nth d 2) (la/v-norm dn)]))))

(defn- residual-fn [anchors sigma-mm]
  (fn [p]
    (let [rt (rt-of-params p)]
      (vec (mapcat (fn [a] (mapv #(/ % sigma-mm) (anchor-residuals rt a))) anchors)))))

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
                ang (when (and from-dir to-dir)
                      (let [c (max -1.0 (min 1.0 (m/dot (transform-dir rt from-dir)
                                                        (m/normalize to-dir))))]
                        (* (/ 180.0 Math/PI) (Math/acos c))))]
            {:name name :kind (if plane? :piano :punto) :residual-mm d :normal-deg ang}))
        anchors))

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
  ([anchors {:keys [sigma-mm] :or {sigma-mm 0.2}}]
   (let [planes (filterv plane-anchor? anchors)
         points (filterv (complement plane-anchor?) anchors)
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

       :else
       (if-let [seed (seed-rt anchors)]
         (let [rfn (residual-fn anchors sigma-mm)
               p0 (vec (concat (cam/rot-mat->rodrigues (:R seed)) (:t seed)))
               res (lm/solve rfn p0 {:max-iterations 120})
               rt (rt-of-params (:params res))
               pa (per-anchor rt anchors)
               ds (mapv :residual-mm pa)]
           (if (underdetermined? rfn (:params res))
             {:error (str "questi agganci non fissano tutto il movimento: "
                          (if (and (>= (count planes) 2) (empty? points))
                            (str "due piani lasciano libero lo scorrimento lungo la loro "
                                 "intersezione. Aggiungi un TERZO piano con la normale in "
                                 "un'altra direzione, oppure un mark su un punto vero")
                            (str "gli agganci sono allineati o le normali sono tutte "
                                 "parallele fra loro. Serve un aggancio fuori da quella "
                                 "direzione")))}
             (assoc rt
                    :rvec (vec (take 3 (:params res)))
                    :n (count anchors)
                    :planes (count planes)
                    :points (count points)
                    :per-anchor pa
                    :rms-mm (Math/sqrt (/ (reduce + 0.0 (map #(* % %) ds)) (count ds)))
                    :max-mm (reduce max 0.0 ds))))
         {:error (str "i mark di aggancio non determinano una rotazione: sono allineati, "
                      "oppure le loro normali sono parallele fra loro. "
                      "Serve un aggancio che guardi in un'altra direzione")})))))

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

(defn resolve-ref
  "`:A/piano-1` against `sessions` ([[label marks] …]) → [label mark-name pose],
   or {:missing <human sentence>} naming exactly what could not be found."
  [sessions ref]
  (let [lbl (some-> (namespace ref) keyword)
        nm (keyword (name ref))
        marks (some (fn [[l ms]] (when (= l lbl) ms)) sessions)]
    (cond
      (nil? lbl) {:missing (str ref " non dice a quale sessione appartiene: "
                                "scrivilo come :etichetta/nome-del-mark")}
      (nil? marks) {:missing (str "l'etichetta :" (name lbl) " di " ref
                                  " non è fra le sessioni passate")}
      (nil? (get marks nm)) {:missing (str "il mark " ref " non esiste in quella sessione")}
      :else [lbl nm (get marks nm)])))

(defn declared-anchors
  "Correspondences between the reference session and the one labelled `lbl`,
   from the declared pair list. `sessions` is [[label marks] …].
   Returns [anchors errors]; an anchor is named after its SOURCE side
   (`:B/piano-1`), so the residual table says which mark of which session cost
   what."
  [sessions pairs ref-lbl lbl]
  (let [resolved (mapv (fn [pair] (mapv #(resolve-ref sessions %) pair)) pairs)
        errs (->> resolved (mapcat identity) (keep :missing) distinct vec)
        pick (fn [refs l] (some (fn [r] (when (and (vector? r) (= l (first r))) r)) refs))
        anchors (->> resolved
                     (keep (fn [refs]
                             (let [to (pick refs ref-lbl) from (pick refs lbl)]
                               (when (and to from)
                                 (assoc (anchor-of (keyword (name (first from)) (name (second from)))
                                                   (nth to 2) (nth from 2))
                                        ;; the zone's name on the REFERENCE side: what
                                        ;; the fused value calls the zone itself
                                        :ref-name (second to))))))
                     vec)]
    [anchors errs]))

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

   Only :marks — `:faces` are generated per proxy, so a shared `:top` would be a
   false correspondence between two different boxes and the fit would believe it."
  [ref-marks marks]
  (->> (keys marks)
       (keep (fn [nm]
               (some-> (anchor-of nm (get ref-marks nm) (get marks nm))
                       (assoc :ref-name nm))))
       (sort-by :name)
       vec))

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
