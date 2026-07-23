(ns ridley.photogrammetry.match
  "Correspondence solved per photo from the known dimensions.

   The previous scheme inferred which line was which edge from the ORDER the
   operator clicked them in. It failed twice on real data, in two different
   ways, and both failures were invisible until the whole fit came out wrong.
   The deeper problem is that it asked a person to reproduce a geometric rule
   the code computes — 'start at the nearest edge, go clockwise' — and any
   divergence, even on one photo, poisoned everything.

   Here the order carries no information at all. The part has been measured,
   so its shape is known; the only unknown in a single photo is the camera
   pose. So: sweep coarse poses, and for each one MATCH the clicked lines to
   the projected edges by geometric proximity, keeping whichever pose explains
   the picture best. A wrong match is one that does not fit, and it is
   rejected by evidence rather than by the operator's memory.

   Two consequences beyond correctness. Counts no longer have to match — a
   missed edge just goes unmatched instead of disqualifying the photo. And
   each photo is solved independently, so the recovered poses can be checked
   against the turntable angles afterwards, which is a genuine test rather
   than an assumption."
  (:require [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.box-fit :as bf]
            [ridley.photogrammetry.bootstrap :as boot]
            [ridley.photogrammetry.turntable-fit :as tt]))

;; ---------------------------------------------------------------------------
;; Distance between two lines, as seen in the image

(defn line-distance
  "Symmetric distance between two segments treated as the LINES they lie on.
   Averages how far each one's endpoints sit from the other's line, so it
   measures disagreement in direction and offset while staying indifferent to
   where along the line the endpoints happen to be — which is exactly what an
   edge observation does and does not tell us."
  [[pa pb] [qa qb]]
  (let [lp (bf/line-through pa pb)
        lq (bf/line-through qa qb)]
    (when (and lp lq)
      (* 0.5 (+ (* 0.5 (+ (Math/abs (bf/point-line-distance lp qa))
                          (Math/abs (bf/point-line-distance lp qb))))
                (* 0.5 (+ (Math/abs (bf/point-line-distance lq pa))
                          (Math/abs (bf/point-line-distance lq pb)))))))))

(defn projected-edges
  "Visible edges of `group`, each with the two image points of its projection."
  [dims pose intrinsics group]
  (let [vis (set (bf/visible-edges dims pose))]
    (->> (boot/edges-of-group group)
         (filter vis)
         (keep (fn [k]
                 (let [{:keys [corners]} (bf/edge-geometry dims k)
                       a (cam/project intrinsics pose (first corners))
                       b (cam/project intrinsics pose (second corners))]
                   (when (and a b) [k [a b]]))))
         vec)))

;; ---------------------------------------------------------------------------
;; Optimal injective matching within a group
;;
;; Group sizes are at most four, so the exact best assignment is found by
;; enumeration. No need for the Hungarian algorithm at this size, and
;; enumeration cannot get a subtle detail wrong.

(defn- injections
  "All ordered selections of `m` distinct items from `items`."
  [items m]
  (cond
    (zero? m) [[]]
    (< (count items) m) []
    :else (mapcat (fn [i]
                    (map #(cons (nth items i) %)
                         (injections (concat (take i items) (drop (inc i) items))
                                     (dec m))))
                  (range (count items)))))

(defn match-group
  "Best assignment of clicked lines to projected edges of one group.
   Returns {:pairs [[edge clicked-index]] :cost} or nil when impossible."
  [projected clicked]
  (let [m (count clicked)]
    (when (<= m (count projected))
      (let [best (reduce
                  (fn [best pick]
                    (let [cost (reduce + 0.0
                                       (map-indexed
                                        (fn [i [_ seg]]
                                          (or (line-distance seg (nth clicked i))
                                              1e6))
                                        pick))]
                      (if (or (nil? best) (< cost (:cost best)))
                        {:cost cost
                         :pairs (vec (map-indexed (fn [i [k _]] [k i]) pick))}
                        best)))
                  nil
                  (injections projected m))]
        best))))

(defn match-photo
  "Match every group of one photo against a candidate pose.
   Returns {:obs [...] :cost :matched} — cost is the mean line distance in
   pixels, which is directly comparable between candidate poses."
  [dims pose intrinsics picks view]
  (let [per (for [[g clicked] picks
                  :when (seq clicked)]
              (let [segs (mapv (fn [c] [(:p1 c) (:p2 c)]) clicked)
                    proj (projected-edges dims pose intrinsics g)
                    m (match-group proj segs)]
                (when m
                  {:group g
                   :cost (:cost m)
                   :n (count segs)
                   :obs (mapv (fn [[k i]]
                                {:view view :edge k
                                 :line (apply bf/line-through (nth segs i))})
                              (:pairs m))})))]
    (when (every? some? per)
      (let [n (reduce + 0 (map :n per))]
        {:obs (vec (mapcat :obs per))
         :matched n
         :cost (if (zero? n) ##Inf (/ (reduce + 0.0 (map :cost per)) n))}))))

;; ---------------------------------------------------------------------------
;; Solving one photo

(defn azimuth-of
  "Camera azimuth around the part's vertical axis, in degrees. Solved per
   photo without reference to the turntable, so comparing successive values
   against the recorded angles is an independent check that the session is
   what the notes say it is."
  [pose]
  (let [[x y _] (cam/camera-center pose)]
    (/ (* 180.0 (Math/atan2 y x)) Math/PI)))

(defn coarse-poses
  "A sweep of viewpoints around the part. The camera looks at the box centre;
   the residual freedom (off-centre aim, roll) is left to the refinement."
  [{:keys [azimuths elevations distances]
    :or {azimuths (range 0 360 10)
         elevations [10 20 30 40 50]
         distances [220 280 340]}}]
  (for [az azimuths el elevations d distances]
    (let [a (/ (* az Math/PI) 180.0) e (/ (* el Math/PI) 180.0)]
      (cam/look-at-pose [(* d (Math/cos e) (Math/cos a))
                         (* d (Math/cos e) (Math/sin a))
                         (* d (Math/sin e))]
                        [0.0 0.0 0.0] [0.0 0.0 1.0]))))

(defn- refine
  "Least-squares refinement of a single photo's pose with the dimensions held
   at their measured values."
  [dims intrinsics obs pose sigma-px]
  ;; the observations carry the SESSION's photo index; a single-view fit has
  ;; exactly one camera, so they are renumbered to 0 for the refinement
  (bf/fit (mapv #(assoc % :view 0) obs) [intrinsics] dims [pose]
          {:sigma-px sigma-px
           ;; all three dimensions pinned: the part is measured, only the
           ;; camera is unknown
           :scale-constraint (mapv (fn [i] {:axis i :value (nth dims i) :sigma 0.001})
                                   (range 3))
           :lm {:max-iterations 80}}))

(defn solve-photo
  "Recover one photo's pose and its edge correspondence, using nothing but the
   known dimensions and the clicked lines — no click order, no angle."
  [dims intrinsics picks view
   {:keys [sigma-px n-seeds grid min-edges] :or {sigma-px 1.0 n-seeds 6 min-edges 5}}]
  (let [all (->> (coarse-poses (or grid {}))
                 (keep (fn [p]
                         (when-let [m (match-photo dims p intrinsics picks view)]
                           (assoc m :pose p)))))
        ;; Two seed sets, unioned. Top-by-cost keeps the cheap candidates, but
        ;; on its own it drops the true pose when a wrong orientation scores a
        ;; cheaper (wrong) assignment — the coverage bug that landed IMG_8907
        ;; at 152px. Best-per-azimuth-sector guarantees the true azimuth is
        ;; always refined, at 15-degree resolution, whatever its raw cost rank.
        cheap (take n-seeds (sort-by :cost all))
        per-sector (->> all
                        (group-by #(Math/floor (/ (+ 180.0 (azimuth-of (:pose %))) 15.0)))
                        vals
                        (map #(apply min-key :cost %)))
        scored (distinct (concat cheap per-sector))
        refined (keep (fn [cand]
                        ;; refine, then RE-MATCH at the improved pose and
                        ;; refine again: the first match comes from a coarse
                        ;; pose and can pair an edge wrongly, which a second
                        ;; pass fixes once the wireframe is roughly in place
                        (let [r1 (refine dims intrinsics (:obs cand) (:pose cand) sigma-px)
                              p1 (first (:poses r1))]
                          (when-let [m2 (match-photo dims p1 intrinsics picks view)]
                            (let [r2 (refine dims intrinsics (:obs m2) p1 sigma-px)]
                              {:pose (first (:poses r2))
                               :obs (:obs m2)
                               :matched (:matched m2)
                               :rms-px (bf/rms-reprojection-px r2 (:obs m2)
                                                               {:sigma-px sigma-px})}))))
                      scored)]
    ;; A pose has six degrees of freedom; a "solution" resting on one or two
    ;; matched edges fits at ~0 px and means nothing. Requiring several edges
    ;; rejects those degenerate photos instead of letting them poison the joint
    ;; fit and the turntable.
    (first (sort-by :rms-px
                    (filter #(>= (:matched %) min-edges) refined)))))

(defn twin-seed-pose
  "The pose 180 degrees of turntable rotation away from `pose`, as a look-at
   from the camera centre mirrored through the vertical axis. This is how a
   photo's Klein twin (theta +/- 180) seeds it: the two view the same box from
   opposite sides, so the twin's solved pose, mirrored, lands near the target."
  [pose]
  (let [[x y z] (cam/camera-center pose)]
    (cam/look-at-pose [(- x) (- y) z] [0.0 0.0 0.0] [0.0 0.0 1.0])))

(defn refine-from-pose
  "Refine a single photo's pose from a GIVEN seed (dims pinned), re-matching
   once. Bypasses the coarse-pose sweep entirely, so it isolates whether the
   clicks fit a known-good pose from whether the search can find that pose."
  [dims intrinsics picks seed-pose {:keys [sigma-px] :or {sigma-px 1.0}}]
  (when-let [m (match-photo dims seed-pose intrinsics picks 0)]
    (let [r1 (refine dims intrinsics (:obs m) seed-pose sigma-px)
          p1 (first (:poses r1))]
      (when-let [m2 (match-photo dims p1 intrinsics picks 0)]
        (let [r2 (refine dims intrinsics (:obs m2) p1 sigma-px)]
          {:pose (first (:poses r2))
           :obs (:obs m2)
           :matched (:matched m2)
           :rms-px (bf/rms-reprojection-px r2 (:obs m2) {:sigma-px sigma-px})})))))

(defn nearest-edge-residuals
  "For a candidate pose, match each clicked line to its NEAREST projected box
   edge and return per-line residuals, worst first. Group-agnostic on purpose:
   an intruder click (a line that fits no edge) stands out no matter which
   group it was filed under, and no count has to line up."
  [dims intrinsics pose picks]
  (let [proj (vec (for [k (range 12)
                        :let [{:keys [corners]} (bf/edge-geometry dims k)
                              a (cam/project intrinsics pose (first corners))
                              b (cam/project intrinsics pose (second corners))]
                        :when (and a b)]
                    [k [a b]]))]
    (->> (for [[g clicks] picks
               [ci c] (map-indexed vector clicks)
               :let [seg [(:p1 c) (:p2 c)]
                     scored (keep (fn [[k p]] (when-let [d (line-distance seg p)] [k d])) proj)]
               :when (seq scored)
               :let [[bk bd] (apply min-key second scored)]]
           {:group g :index ci :edge bk :residual-px bd})
         (sort-by (comp - :residual-px))
         vec)))

(defn solve-session
  "Solve every photo independently. Returns one entry per photo."
  [dims intrinsics photos opts]
  (vec (map-indexed
        (fn [i p]
          (when-let [s (solve-photo dims intrinsics (:picks p) i opts)]
            (assoc s :image (:image p) :theta-deg (:theta-deg p))))
        photos)))

;; ---------------------------------------------------------------------------
;; A turntable from the solved photos, for predicting the un-clicked ones
;;
;; Per-photo solving gives correspondence but not a way to place a camera at an
;; angle nobody clicked. A turntable does, and now that the labels are correct
;; it can be fitted — its earlier failure was entirely the order-based
;; labelling, not the model. The subtlety is the box's 180-degree symmetry:
;; different photos may have been solved in different symmetry frames, so the
;; labels are NOT trusted across photos. Instead each turntable hypothesis
;; RE-MATCHES every photo against its own predicted geometry, which resolves
;; the frame per hypothesis for free.

(defn- deg->rad [d] (/ (* d Math/PI) 180.0))

(defn base-from-pose
  "A turntable base pose — camera looking at the origin with azimuth zeroed —
   at the same standoff and elevation as a solved pose. Seeds the search from
   the real camera geometry instead of a nominal guess."
  [pose]
  (let [[x y z] (cam/camera-center pose)
        dist (Math/sqrt (+ (* x x) (* y y) (* z z)))
        elev (Math/asin (/ z (max 1e-9 dist)))]
    (cam/look-at-pose [(* dist (Math/cos elev)) 0.0 (* dist (Math/sin elev))]
                      [0.0 0.0 0.0] [0.0 0.0 1.0])))

(defn- turntable-base-seed
  "Camera geometry to seed the search from — the real standoff/elevation of
   whichever photo solves independently, or a nominal fallback."
  [dims intrinsics photos sigma-px]
  (let [seed (some (fn [p] (solve-photo dims intrinsics (:picks p) 0 {:sigma-px sigma-px}))
                   photos)]
    (if seed (base-from-pose (:pose seed))
        (cam/look-at-pose [280.0 0.0 160.0] [0.0 0.0 0.0] [0.0 0.0 1.0]))))

(defn- eval-turntable-candidate
  "One (yaw,sense,a,b) hypothesis: re-match every photo's picks under it and,
   only if at least 2 photos corroborate (too little evidence otherwise),
   run the full LM refinement with theta as a soft prior. Returns nil or
   {:base :fit :hyp :used :rms} — shared by fit-turntable's blind grid scan
   and fit-turntable-seeded's narrow one, so the two never compute a
   candidate's cost differently."
  [dims intrinsics picks thetas base sigma-px yaw sense a b]
  (let [axis (tt/axis-from-params 0.0 0.0 a b)
        poses (mapv #(tt/pose-at-angle base axis (+ yaw (* sense %))) thetas)
        per (keep-indexed
             (fn [i p] (match-photo dims (nth poses i) intrinsics p i))
             picks)]
    (when (>= (count per) 2)
      (let [obs (vec (mapcat :obs per))
            angles (mapv #(+ yaw (* sense %)) thetas)
            res (tt/fit obs angles intrinsics dims base
                        {:phi 0.0 :psi 0.0 :a a :b b}
                        {:sigma-px sigma-px
                         :scale-constraint {:axis 2 :value (nth dims 2) :sigma 0.02}
                         :angle-prior-sigma-deg 1.0
                         :lm {:max-iterations 80}})]
        {:base (:pose res)
         :fit res
         :hyp {:yaw yaw :sense sense}
         :used (count per)
         :rms (bf/rms-reprojection-px res obs {:sigma-px sigma-px})}))))

(defn fit-turntable
  "Recover a turntable (base pose, axis, yaw, sense) that explains every
   clicked photo, by scanning discrete yaw/sense/offset seeds and re-matching
   under each. Returns the best {:base :fit :hyp :rms :used} or nil.

   `photos` are the clicked photos ({:picks :theta-deg}). The dimensions are
   held at the caliper values, so a good fit is a pure registration.

   Blind 24×2×5×5 = 1200-candidate scan — a CLI-grade batch operation (see
   fit-turntable-seeded for the interactive case, which narrows this down
   using an already-known yaw/sense estimate instead)."
  [dims intrinsics photos {:keys [sigma-px] :or {sigma-px 1.0}}]
  (let [picks (mapv :picks photos)
        thetas (mapv #(deg->rad (:theta-deg %)) photos)
        base (turntable-base-seed dims intrinsics photos sigma-px)
        candidates
        (for [yi (range 24)
              sense [1.0 -1.0]
              a [-40.0 -20.0 0.0 20.0 40.0]
              b [-40.0 -20.0 0.0 20.0 40.0]]
          (eval-turntable-candidate dims intrinsics picks thetas base sigma-px
                                    (* 2.0 Math/PI (/ (double yi) 24)) sense a b))]
    (->> candidates
         (keep identity)
         (sort-by (juxt #(- (:used %)) :rms))
         first)))

(defn fit-turntable-seeded
  "Like fit-turntable, but without the 5×5 axis-offset (a,b) grid — for
   interactive use (edit-acquire's 'f'), where a global search over BOTH
   yaw and axis offset is too slow (measured 2026-07-22: ~165s blind on
   just 2 photos). Axis offset is fixed at a=b=0 (axis through the
   origin/pivot) — the same approximation edit-acquire's own per-photo
   seeding already makes — which alone cuts the candidate count 25-fold
   (1200 -> 48 at the default resolution).

   An earlier version of this function also narrowed the YAW range around
   an estimate from turntable-consistency (run on the photos' own
   independently-solved poses), reasoning that photos already solved
   individually should already know roughly where they are. Measured false
   2026-07-22: with only 2 confirming photos, that estimate was off by
   ~34° while reporting an internal disagreement of only ~12° — nowhere
   near self-diagnosing, and the narrowed search silently 'succeeded' at
   167px (a real candidate, just not the right one) instead of failing
   loudly. A full 360° yaw sweep is what actually stayed correct, so that
   is the default here — narrowing is opt-in (`yaw0-deg`/`yaw-window-deg`)
   for a caller with a better-trusted estimate, not the default path.

   `sense` (the turntable's rotation sense) is far more reliable than yaw
   from just 2 photos — it is a single either/or choice, not a continuous
   value — but even so, when omitted, BOTH are tried and the better result
   wins, exactly like fit-turntable's own grid."
  [dims intrinsics photos
   {:keys [sigma-px yaw0-deg sense yaw-window-deg yaw-steps]
    :or {sigma-px 1.0 yaw0-deg 0.0 yaw-window-deg 360.0 yaw-steps 24}}]
  (let [picks (mapv :picks photos)
        thetas (mapv #(deg->rad (:theta-deg %)) photos)
        base (turntable-base-seed dims intrinsics photos sigma-px)
        half (/ (* yaw-window-deg Math/PI) (* 180.0 2.0))
        yaw0 (deg->rad yaw0-deg)
        offsets (if (> yaw-steps 1)
                  (mapv #(+ (- half) (* % (/ (* 2.0 half) (dec yaw-steps)))) (range yaw-steps))
                  [0.0])
        senses (if sense [sense] [1.0 -1.0])
        candidates (for [s senses off offsets]
                     (eval-turntable-candidate dims intrinsics picks thetas base sigma-px
                                               (+ yaw0 off) s 0.0 0.0))]
    (->> candidates
         (keep identity)
         (sort-by (juxt #(- (:used %)) :rms))
         first)))

(defn reproject-turntable
  "Project the fitted box through the turntable onto each of `session-photos`
   ({:image :theta-deg}) at its recorded angle. These are the confirmable
   proposals for photos that were never clicked."
  [intrinsics tt session-photos]
  (let [{:keys [base fit hyp]} tt
        ap (:axis-params fit)
        axis (tt/axis-from-params (:phi ap) (:psi ap) (:a ap) (:b ap))
        dims (:dims fit)]
    (mapv (fn [p]
            (let [theta (deg->rad (:theta-deg p))
                  pose (tt/pose-at-angle base axis (+ (:yaw hyp) (* (:sense hyp) theta)))]
              {:image (:image p) :theta-deg (:theta-deg p)
               :edges (vec (keep (fn [k]
                                   (let [{:keys [corners]} (bf/edge-geometry dims k)
                                         a (cam/project intrinsics pose (first corners))
                                         b (cam/project intrinsics pose (second corners))]
                                     (when (and a b)
                                       {:edge k :group (name (boot/group-of k))
                                        :p1 a :p2 b})))
                                 (bf/visible-edges dims pose)))}))
          session-photos)))

;; ---------------------------------------------------------------------------
;; Cross-checking against the turntable

(defn turntable-consistency
  "Compare the per-photo azimuth steps with the recorded angle steps.
   Reports the residual after removing the common offset, which is gauge."
  [solved]
  (let [ok (filter some? solved)]
    (when (> (count ok) 1)
      (let [rel (mapv (fn [s] {:image (:image s)
                               :theta (:theta-deg s)
                               :az (azimuth-of (:pose s))})
                      ok)
            ;; Collapse the box's 180-degree symmetry. Each photo is solved
            ;; independently and may land on either member of the pair, so the
            ;; azimuths come out scattered by 180 even when the turntable is
            ;; perfectly consistent. Wrapping into a half turn removes the
            ;; ambiguity instead of reporting it as error.
            wrap (fn [d] (- (mod (+ d 90.0) 180.0) 90.0))
            ;; azimuth should track -theta (object turning one way looks like
            ;; the camera going the other); the sense is discovered, not assumed
            best (apply min-key
                        (fn [sense]
                          (let [ds (map (fn [r] (wrap (- (:az r) (* sense (:theta r))))) rel)
                                m (/ (reduce + 0.0 ds) (count ds))]
                            (reduce + 0.0 (map #(Math/abs (wrap (- % m))) ds))))
                        [1.0 -1.0])
            ds (map (fn [r] (wrap (- (:az r) (* best (:theta r))))) rel)
            m (/ (reduce + 0.0 ds) (count ds))
            errs (map (fn [r d] (assoc r :err-deg (wrap (- d m)))) rel ds)]
        {:sense best
         :offset-deg m
         :rows (vec errs)
         :max-err-deg (apply max (map #(Math/abs (:err-deg %)) errs))}))))
