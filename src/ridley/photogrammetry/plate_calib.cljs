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
  ([views nominal {:keys [iterations] :or {iterations default-iterations}}]
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
                 ;; 2. back onto the model's frame — see regauge
                 fixed (or (regauge tri nominal) tri)
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
