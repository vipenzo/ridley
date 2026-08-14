(ns ridley.photogrammetry.plate-calib
  "Where the plate's marks REALLY are, measured from a session instead of assumed
   from the model.

   A registration plate is supposed to be flat, round and true. A printed one is
   not: a 300mm FDM disc warps as it cools, and the marks on a paper sheet land
   where the printer put them, not where the file says. Measured on a real plate
   (2026-08-13) three of twelve marks sat 1.2 to 1.6 mm OUT OF THE PLANE while
   the rest were within a quarter of a millimetre.

   That error is not absorbed by anything. A rigid one would be — a tilted,
   off-centre or wobbling plate is just a different camera pose, and every photo
   solves its own — but a plate whose marks are not where the model says is a
   ruler with the wrong numbers on it. It shows up as residuals that swing with
   the plate's rotation, because a mark that sits proud of the plane projects
   differently depending on which way the camera is looking across it.

   The remedy is not a better printer. Vincenzo's argument, and it is the right
   one: 'stampare un piatto perfetto è difficilissimo, per me e per chiunque
   provasse a utilizzare questa feature' — so the difficulty moves from making
   the plate to MEASURING it, which is what this channel does with everything
   else.

   THE METHOD. Alternate two things that are each easy:

     1. with the poses held, every mark is triangulated from all the views that
        saw it — its rays meet where it really is;
     2. with the marks held, every pose is re-solved by PnP against them.

   Each step is a plain least-squares problem, and the pair converges in two or
   three rounds because the corrections are millimetres against a plate hundreds
   of millimetres across.

   THE GAUGE, which is the part that needs care. Marks and poses can drift
   together without changing a single pixel: scale the plate by s, move every
   camera s times further away, and every photograph is identical. Left alone the
   alternation would wander along that freedom. So after each triangulation the
   measured marks are put back onto the model's own frame — levelled, centred,
   turned and SCALED so their mean crown radius is the nominal one. What survives
   is the plate's SHAPE, which images can measure; what does not is its overall
   SIZE, which they cannot. Size still comes from `:d`, and a caliper is what
   corrects it."
  (:require [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.pnp :as pnp]))

(def default-iterations
  "Rounds of triangulate-then-re-solve. Two already carries most of it; the third
   is there for the case where the starting poses were solved against a plate
   wrong enough to bias the first triangulation."
  3)

(def max-deviation-frac
  "The most a mark may move, as a fraction of the crown radius, before the run is
   refused instead of reported.

   A registration plate is a manufactured object: it warps, it prints a little
   off, but it does not have a mark four percent of its own radius away from
   where the drawing says. A result that large is not a measured plate, it is the
   symptom of something upstream — a view whose pose is the planar homography's
   twin, a mislabelled crown, a session where half the photographs disagree — and
   ADOPTING it would be the worst possible response, because from then on every
   measurement is referred to an invented ruler and nothing downstream can tell.

   3% is 4mm on the reference ⌀300 plate. The worst real mark measured on one
   (2026-08-13) moved 1.63mm — 1.2% — so this leaves a factor of two and still
   catches the 5%-and-up nonsense that a bad pose produces."
  0.03)

;; ---------------------------------------------------------------------------
;; Triangulation: where do these rays meet?

(defn- meet
  "The point closest to all the rays {:origin :dir}, in the least-squares sense:
   solve (Σ I − dᵢdᵢᵀ) P = Σ (I − dᵢdᵢᵀ) Cᵢ. Each I − ddᵀ projects onto the plane
   across a ray, so the quantity being minimised is the perpendicular distance to
   every ray at once. nil for fewer than two rays, or when they are parallel
   enough to leave the system singular."
  [rays]
  (when (>= (count rays) 2)
    (let [outer (fn [[x y z]] [[(* x x) (* x y) (* x z)]
                               [(* y x) (* y y) (* y z)]
                               [(* z x) (* z y) (* z z)]])
          m- (fn [a b] (mapv #(mapv - %1 %2) a b))
          m+ (fn [a b] (mapv #(mapv + %1 %2) a b))
          I [[1.0 0.0 0.0] [0.0 1.0 0.0] [0.0 0.0 1.0]]
          Ms (mapv (fn [r] (m- I (outer (:dir r)))) rays)
          A (reduce m+ [[0.0 0.0 0.0] [0.0 0.0 0.0] [0.0 0.0 0.0]] Ms)
          b (reduce (fn [acc [M r]] (mapv + acc (la/mat*vec M (:origin r))))
                    [0.0 0.0 0.0]
                    (map vector Ms rays))]
      (la/solve A b))))

;; ---------------------------------------------------------------------------
;; The gauge: put the measured marks back on the model's frame

(defn- rot-taking
  "Rotation matrix carrying unit vector `a` onto unit vector `b` by the shortest
   turn. Identity when they already agree; a half-turn about any perpendicular
   when they oppose."
  [a b]
  (let [c (la/v-dot a b)
        axis [(- (* (a 1) (b 2)) (* (a 2) (b 1)))
              (- (* (a 2) (b 0)) (* (a 0) (b 2)))
              (- (* (a 0) (b 1)) (* (a 1) (b 0)))]
        s (la/v-norm axis)]
    (cond
      (< s 1e-12) (if (pos? c)
                    [[1.0 0.0 0.0] [0.0 1.0 0.0] [0.0 0.0 1.0]]
                    [[1.0 0.0 0.0] [0.0 -1.0 0.0] [0.0 0.0 -1.0]])
      :else (cam/rodrigues (la/v-scale axis (/ (Math/atan2 s c) s))))))

(defn- rot-z [ang]
  (let [c (Math/cos ang) s (Math/sin ang)]
    [[c (- s) 0.0] [s c 0.0] [0.0 0.0 1.0]]))

(defn- mean-radius [pts]
  (/ (reduce + 0.0 (map (fn [[x y _]] (Math/hypot x y)) pts)) (count pts)))

(defn regauge
  "Put `measured` back on `nominal`'s frame: level its best-fit plane onto the
   nominal one, centre it, turn it so the marks sit at the nominal angles, and
   scale it so the mean crown radius matches.

   This is what keeps the alternation from wandering. Scale, position and
   orientation are FREEDOMS of the pair (marks, poses) — no photograph can see
   them — so they are taken from the model and only the shape is taken from the
   measurement."
  [measured nominal]
  (let [{n-m :n o-m :o} (pnp/plane-frame measured)
        {n-n :n o-n :o} (pnp/plane-frame nominal)]
    (when (and n-m n-n)
      ;; a plane's normal has no sign: point the measured one the same way as the
      ;; nominal, or levelling would flip the plate over
      (let [n-m (if (neg? (la/v-dot n-m n-n)) (la/v-scale n-m -1.0) n-m)
            Rlevel (rot-taking n-m n-n)
            lev (mapv (fn [p] (la/mat*vec Rlevel (la/v-sub p o-m))) measured)
            nom (mapv (fn [p] (la/v-sub p o-n)) nominal)
            s (/ (mean-radius nom) (max 1e-9 (mean-radius lev)))
            scaled (mapv #(la/v-scale % s) lev)
            ;; the turn: average the angle each mark must rotate through to reach
            ;; its nominal one, via the mean of the unit rotations (no wrap bug)
            dsum (reduce (fn [[sx sy] [p q]]
                           (let [a (Math/atan2 (p 1) (p 0))
                                 b (Math/atan2 (q 1) (q 0))
                                 d (- b a)]
                             [(+ sx (Math/cos d)) (+ sy (Math/sin d))]))
                         [0.0 0.0]
                         (map vector scaled nom))
            Rspin (rot-z (Math/atan2 (second dsum) (first dsum)))]
        (mapv (fn [p] (la/v-add (la/mat*vec Rspin p) o-n)) scaled)))))

;; ---------------------------------------------------------------------------

(defn- view-rms
  "Reprojection rms (px) of `marks` against one view's picks."
  [{:keys [pose intrinsics picks]} marks]
  (let [sq (for [[ci px] picks
                 :let [p (cam/project intrinsics pose (nth marks ci))]
                 :when p]
             (+ (* (- (p 0) (px 0)) (- (p 0) (px 0)))
                (* (- (p 1) (px 1)) (- (p 1) (px 1)))))
        n (count sq)]
    (when (pos? n) (Math/sqrt (/ (reduce + 0.0 sq) n)))))

(def default-mode
  "What the plate is allowed to be wrong about. `:out-of-plane` — the default —
   lets a mark move only PERPENDICULAR to the plate; `:free` lets it move
   anywhere.

   This is a physical claim before it is a numerical one. A crown is printed, and
   a printer places ink to about a tenth of a percent: 0.13mm on a 133mm radius.
   Nothing in the making of a plate moves a mark a millimetre sideways. What does
   move by millimetres is the surface — a 300mm disc warps as it cools, paper
   lifts where the glue is thin — and that displacement is perpendicular.

   Measured on Vincenzo's twelve-view ⌀300 session, 2026-08-14, and this is why
   the default is not `:free`. Turned loose, the fit reported 1.97mm at the worst
   mark, of which 1.60 out of plane and 1.11 radial, and its own residual fell
   from 2.18 to 1.78px — convincing, and wrong. Held out, one photograph at a
   time, the free plate made EIGHT of twelve photographs worse and the pooled
   held-out residual barely moved (2.12 → 2.05). The split said what had
   happened: every grazing view improved and every face-on view degraded two to
   four times. A face-on camera cannot see an out-of-plane error at all — the
   displacement is along its line of sight — but it sees an in-plane one at full
   strength. So the in-plane millimetre was not on the plate; it was the fit
   spending freedom it should not have had, and paying for it in the views that
   could see the difference.

   Constrained to the perpendicular, the same data holds up under the same test.
   `:free` is kept because a plate can be genuinely mis-printed, but it should be
   asked for, and its result should be cross-validated before it is believed."
  :out-of-plane)

(defn- flatten-in-plane
  "Keep only each mark's PERPENDICULAR deviation, then take the piston and tilt
   out of what remains.

   The de-trending is not tidiness: a uniform lift of the whole crown, or a
   uniform tilt of it, is exactly what a camera pose absorbs — move the camera
   and the picture is the same. Left in, those three degrees of freedom would
   wander between the plate and the poses from one round to the next instead of
   settling, and would be reported as a warp the plate does not have."
  [measured nominal]
  (let [{:keys [n o u v]} (pnp/plane-frame nominal)]
    (if-not n
      measured
      (let [uv (mapv (fn [p] (let [d (la/v-sub p o)] [(la/v-dot d u) (la/v-dot d v)])) nominal)
            dz (mapv (fn [m nm] (la/v-dot (la/v-sub m nm) n)) measured nominal)
            ;; least squares dz ≈ a + b·u + c·v, by the 3×3 normal equations
            rows (mapv (fn [[uu vv]] [1.0 uu vv]) uv)
            ata (vec (for [i (range 3)]
                       (vec (for [j (range 3)]
                              (reduce + 0.0 (map (fn [r] (* (r i) (r j))) rows))))))
            atb (vec (for [i (range 3)]
                       (reduce + 0.0 (map (fn [r d] (* (r i) d)) rows dz))))
            coef (or (la/solve ata atb) [0.0 0.0 0.0])
            detrended (mapv (fn [[uu vv] d]
                              (- d (+ (coef 0) (* (coef 1) uu) (* (coef 2) vv))))
                            uv dz)]
        (mapv (fn [nm d] (la/v-add nm (la/v-scale n d))) nominal detrended)))))

(defn- reproject-rms
  "Reprojection rms (px) of `marks` over EVERY view's picks pooled — not the mean
   of the per-view numbers, which would weight a view with four picks like one
   with twelve."
  [views marks]
  (let [sq (for [{:keys [pose intrinsics picks]} views
                 [ci px] picks
                 :let [p (cam/project intrinsics pose (nth marks ci))]
                 :when p]
             (+ (* (- (p 0) (px 0)) (- (p 0) (px 0)))
                (* (- (p 1) (px 1)) (- (p 1) (px 1)))))
        n (count sq)]
    (when (pos? n) (Math/sqrt (/ (reduce + 0.0 sq) n)))))

(defn- fit-view-rms
  "Solve `view`'s pose freshly against `marks` and report the rms it achieves.

   The pose must be re-solved, not reused: a pose fitted against the model plate
   is not a fair thing to judge a measured plate with, and vice versa. What is
   being asked is 'how well can this photograph be explained by this plate, at
   its best', which means letting the camera go where the plate says it should."
  [{:keys [pose intrinsics picks]} marks]
  (let [corr (vec (for [[ci px] picks] {:world (nth marks ci) :px px}))
        sol (pnp/solve-pnp corr intrinsics {:seed pose :method :seeded :max-outliers 0})]
    (when sol (view-rms {:pose (:pose sol) :intrinsics intrinsics :picks picks} marks))))

(declare calibrate)

(defn cross-validate
  "Does the measured plate explain photographs it has never seen?

   The residual a calibration reports about ITSELF always falls — it was chosen
   to make it fall. That number cannot distinguish a plate that is really warped
   from a fit that has quietly absorbed the noise of the views it was given, and
   the two have opposite consequences: the first makes every future session
   better, the second makes every future session worse in a way nothing will
   report.

   So: hold each view out, calibrate on the rest, and ask the held-out
   photograph — which had no say in the answer — whether the measured plate
   suits it better than the model does. Both sides get a freshly solved pose, so
   what is compared is the PLATE and not the registration.

   Returns {:per-view [{:idx :nominal-px :measured-px}…]
            :nominal-px :measured-px   pooled over the held-out views
            :better n :worse n
            :verdict :confirmed | :noise | :mixed}
   or {:error …} when there are too few views for a hold-out to leave anything.

   Expensive on purpose: N calibrations of N−1 views. It is the difference
   between believing a measurement and having checked it."
  ([views nominal] (cross-validate views nominal {}))
  ([views nominal opts]
   (if (< (count views) 4)
     {:error (str "per la verifica servono almeno 4 viste (ne ho " (count views)
                  "): togliendone una devono restarne tre, che è il minimo per "
                  "misurare il piatto")}
     (let [per (vec (keep-indexed
                     (fn [i held]
                       (let [rest-views (vec (concat (subvec (vec views) 0 i)
                                                     (subvec (vec views) (inc i))))
                             r (calibrate rest-views nominal opts)]
                         (when-not (:error r)
                           (let [a (fit-view-rms held nominal)
                                 b (fit-view-rms held (:marks r))]
                             (when (and a b)
                               {:idx (:idx held) :nominal-px a :measured-px b})))))
                     views))]
       (if (empty? per)
         {:error "nessuna vista ha potuto essere tenuta fuori e rimisurata"}
         (let [pool (fn [k] (Math/sqrt (/ (reduce + 0.0 (map #(let [x (k %)] (* x x)) per))
                                          (count per))))
               nom (pool :nominal-px)
               mea (pool :measured-px)
               better (count (filter #(< (:measured-px %) (:nominal-px %)) per))
               worse (- (count per) better)]
           {:per-view per
            :nominal-px nom
            :measured-px mea
            :better better
            :worse worse
            :verdict (cond
                       ;; a real plate defect helps a photograph that had no hand
                       ;; in measuring it, and helps most of them
                       (and (< mea (* 0.9 nom)) (> better worse)) :confirmed
                       (> mea nom) :noise
                       :else :mixed)}))))))

(defn calibrate
  "Measure where this plate's marks actually are.

   `views` is [{:pose {:rvec :t} :intrinsics {…} :picks {mark-idx [u v]}} …] — a
   registered session. `nominal` is the model's mark positions, [[x y z] …], in
   the same order the picks are keyed by.

   Returns {:marks [[x y z] …]           the measured positions, on the model's frame
            :poses [{:rvec :t} …]        the re-solved poses, in view order
            :deviation-mm [d …]          how far each moved
            :out-of-plane-mm [d …]       …of which, perpendicular to the plate
            :radial-mm [d …]             …and along its radius (signed, + = outward)
            :worst-mm d
            :rms-before :rms-after       reprojection, px
            :views n :iterations n}
   or {:error text} when there is not enough to measure — a mark needs at least
   two views, and the whole thing needs at least three or the marks and the poses
   have nothing to disagree about — or when what comes out is too far from a
   plate to be one (see `max-deviation-frac`)."
  ([views nominal] (calibrate views nominal {}))
  ([views nominal {:keys [iterations mode]
                   :or {iterations default-iterations mode default-mode}}]
   (cond
     (< (count views) 3)
     {:error (str "la calibrazione del piatto ha bisogno di almeno TRE viste "
                  "registrate (ne ho " (count views) "): con due, i mark e le pose "
                  "si accordano su qualunque cosa e non resta niente da misurare")}

     (some (fn [j] (< (count (filter #(get (:picks %) j) views)) 2))
           (range (count nominal)))
     {:error (str "almeno un mark è visto da meno di due viste: senza due raggi "
                  "non ha una posizione")}

     :else
     (let [before (reproject-rms views nominal)]
       (loop [it 0
              vs views
              marks (vec nominal)]
         (if (>= it iterations)
           (let [dev (mapv (fn [m n] (la/v-norm (la/v-sub m n))) marks nominal)
                 worst (reduce max dev)
                 ;; split each move into the two components that mean different
                 ;; things: out of the plate's plane (it is warped) and along its
                 ;; radius (the crown is not a true circle). This is how the
                 ;; report is read — a warped plate and a mis-scaled print look
                 ;; nothing alike once the deviation is taken apart.
                 {:keys [n o]} (pnp/plane-frame nominal)
                 radial (mapv (fn [m nm]
                                (let [d (la/v-sub m nm)
                                      r (la/v-sub nm o)
                                      rl (la/v-norm r)]
                                  (if (> rl 1e-9)
                                    (la/v-dot d (la/v-scale r (/ 1.0 rl)))
                                    0.0)))
                              marks nominal)
                 out-of-plane (mapv (fn [m nm] (la/v-dot (la/v-sub m nm) n)) marks nominal)
                 ;; The third component, and it is NOT decoration: without it the
                 ;; two reported numbers do not add up to the headline, and a user
                 ;; checking the arithmetic finds a discrepancy with no name (found
                 ;; on the first real run, 2026-08-13: m00 reported -1.60 and -1.11,
                 ;; while the worst deviation said 2.01 — the missing 0.51mm was
                 ;; this). A mark off TANGENTIALLY is at the wrong angle around the
                 ;; crown: not a warp and not a scale, a placement error.
                 tangential (mapv (fn [m nm]
                                    (let [d (la/v-sub m nm)
                                          r (la/v-sub nm o)
                                          rl (la/v-norm r)]
                                      (if (> rl 1e-9)
                                        (let [rhat (la/v-scale r (/ 1.0 rl))
                                              that [(- (* (n 1) (rhat 2)) (* (n 2) (rhat 1)))
                                                    (- (* (n 2) (rhat 0)) (* (n 0) (rhat 2)))
                                                    (- (* (n 0) (rhat 1)) (* (n 1) (rhat 0)))]]
                                          (la/v-dot d that))
                                        0.0)))
                                  marks nominal)
                 gauge (/ (reduce + 0.0 (map (fn [p] (la/v-norm (la/v-sub p o))) nominal))
                          (count nominal))]
             (if (> worst (* max-deviation-frac gauge))
               {:error (str "il risultato non descrive un piatto: il mark più spostato "
                            "si allontana di " (.toFixed worst 1) " mm dal modello "
                            "(il limite è " (.toFixed (* max-deviation-frac gauge) 1)
                            " mm). Non è un piatto storto, è una registrazione "
                            "sbagliata a monte: controlla le foto con il residuo più "
                            "alto e rifai la rifinitura congiunta prima di ricalibrare.")
                :worst-mm worst}
               {:marks marks
                :poses (mapv :pose vs)
                :deviation-mm dev
                :out-of-plane-mm out-of-plane
                :radial-mm radial
                :tangential-mm tangential
                :worst-mm worst
                :rms-before before
                :rms-after (reproject-rms vs marks)
                ;; per view, so the caller can show WHICH photographs the measured
                ;; plate helped — an aggregate that moves from 2.13 to 1.79 says
                ;; something happened and refuses to say to whom
                :per-view-before (mapv #(view-rms % nominal) views)
                :per-view (mapv #(view-rms % marks) vs)
                :view-idx (mapv :idx views)
                :views (count views)
                :iterations iterations}))
           ;; 1. every mark, from every ray that saw it
           (let [tri (mapv (fn [j]
                             (let [rays (vec (keep (fn [{:keys [pose intrinsics picks]}]
                                                     (when-let [px (get picks j)]
                                                       (cam/pixel-ray intrinsics pose px)))
                                                   vs))]
                               (or (meet rays) (nth marks j))))
                           (range (count nominal)))
                 ;; 2. back onto the model's frame — see regauge — and then, by
                 ;; default, back onto the perpendicular: see default-mode for
                 ;; why a plate is allowed to be warped but not mis-printed
                 regauged (or (regauge tri nominal) tri)
                 fixed (if (= mode :free)
                         regauged
                         (flatten-in-plane regauged nominal))
                 ;; 3. every pose again, now against the measured plate
                 vs' (mapv (fn [{:keys [pose intrinsics picks] :as v}]
                             (let [corr (vec (for [[ci px] picks]
                                               {:world (nth fixed ci) :px px}))
                                   sol (pnp/solve-pnp corr intrinsics
                                                      {:seed pose :method :seeded
                                                       :max-outliers 0})]
                               (if sol (assoc v :pose (:pose sol)) v)))
                           vs)]
             (recur (inc it) vs' fixed))))))))
