(ns ridley.photogrammetry.bootstrap
  "Turning ordered clicks into labelled observations, without asking a human
   to name signed axes.

   The correspondence problem — which of the twelve edges is this line? — is
   the one place where a mistake corrupts the fit SILENTLY. Asking the
   operator to decide it directly, across fourteen rotations, is asking for
   exactly that mistake. So the work is split the other way round:

   - the operator clicks edges in GROUPS (vertical / top / bottom), in plain
     left-to-right image order, which needs no reasoning about axes;
   - the solver searches the small discrete set of ways those groups can map
     onto the box, and scores each by how well it actually fits.

   A wrong assignment does not fit. That is the whole idea: the labelling is
   decided by evidence, not by the operator's memory of a convention.

   Once a coarse fit exists it is reprojected onto every remaining photo, so
   the operator confirms proposals instead of originating them."
  (:require [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.box-fit :as bf]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.turntable-fit :as tt]))

;; ---------------------------------------------------------------------------
;; Edge groups

(def vertical-edges [0 5 8 11])
(def top-edges [3 4 7 10])
(def bottom-edges [1 2 6 9])

(defn group-of
  "Which clickable group edge k belongs to."
  [k]
  (cond (some #{k} vertical-edges) :vertical
        (some #{k} top-edges) :top
        :else :bottom))

(defn edges-of-group [g]
  (case g :vertical vertical-edges :top top-edges :bottom bottom-edges))

;; ---------------------------------------------------------------------------
;; Predicting what a photo should show

(defn- edge-midpoint [dims k]
  (let [{:keys [corners]} (bf/edge-geometry dims k)]
    (mapv #(* 0.5 (+ %1 %2)) (first corners) (second corners))))

(defn- cyclic-from-front
  "Order edge midpoints by going around their centroid, starting from the one
   lowest in the image (nearest the viewer) and turning clockwise.

   Sorting horizontal edges by image x looks reasonable and is NOT stable: the
   top face projects to a quadrilateral whose edge midpoints can swap x-order
   under a few degrees of yaw, so the same physical edge changes position in
   the list between one photo and the next. Cyclic order around the face is
   stable under rotation, and 'start at the front edge, go clockwise' is also
   something a person can actually carry out."
  [items]
  ;; NO special case for small groups. An earlier version sorted groups of two
  ;; left-to-right, on the theory that it was simpler to follow — which made
  ;; the implemented rule disagree with the documented one exactly when a
  ;; group had two edges. That is the common case for the bottom group, and it
  ;; mislabelled roughly half of them. One rule, always.
  (if (empty? items)
    []
    (let [pts (map second items)
          cx (/ (reduce + (map first pts)) (count pts))
          cy (/ (reduce + (map second pts)) (count pts))
          with-ang (map (fn [[k [x y]]]
                          [k [x y] (Math/atan2 (- y cy) (- x cx))])
                        items)
          ;; image y grows downward, so increasing atan2 is clockwise on screen
          sorted (vec (sort-by #(nth % 2) with-ang))
          start (reduce (fn [bi i]
                          (if (> (second (nth (nth sorted i) 1))
                                 (second (nth (nth sorted bi) 1)))
                            i bi))
                        0 (range (count sorted)))]
      (mapv first (map #(nth sorted (mod (+ start %) (count sorted)))
                       (range (count sorted)))))))

(defn predicted-order
  "The edges of group `g` visible from `pose`, in the order the operator is
   asked to click them: left to right for the verticals, and around the face
   from the front edge clockwise for the top and bottom groups."
  [dims pose intrinsics g]
  (let [vis (set (bf/visible-edges dims pose))
        items (->> (edges-of-group g)
                   (filter vis)
                   (keep (fn [k]
                           (when-let [p (cam/project intrinsics pose
                                                     (edge-midpoint dims k))]
                             [k p])))
                   vec)]
    (if (= g :vertical)
      (mapv first (sort-by (comp first second) items))
      (cyclic-from-front items))))

;; ---------------------------------------------------------------------------
;; Hypotheses
;;
;; The only thing genuinely unknown at bootstrap time is the object's yaw when
;; theta = 0: which face was pointing at the camera on the first shot. That is
;; one angle, and a coarse scan over it is enough — the fit refines it. The
;; turntable's sense of rotation is the second unknown, and it is binary.

(defn hypotheses
  "Discrete starting points for the search.

   Yaw and rotation sense are the two genuinely unknown discrete quantities.
   The axis OFFSET is continuous and fitted, but it is seeded coarsely too:
   the part is rarely centred on the plate, and starting every attempt from
   'axis through the middle of the box' leaves LM to discover a 30 mm offset
   on its own, which it often will not from a bad pose. Seeding costs a few
   more fits and removes a silent failure that looks like bad clicking."
  ([] (hypotheses 8))
  ([n-yaw] (hypotheses n-yaw [[0.0 0.0]]))
  ([n-yaw offsets]
   (for [i (range n-yaw)
         sense [1.0 -1.0]
         [a b] offsets]
     {:yaw (* 2.0 Math/PI (/ (double i) n-yaw)) :sense sense :a a :b b})))

(defn- hypothesis-poses [base axis thetas {:keys [yaw sense]}]
  (mapv #(tt/pose-at-angle base axis (+ yaw (* sense %))) thetas))

(defn assign
  "Match a photo's ordered clicks to edge indices under one hypothesis.

   Returns observations, or nil when the photo does not corroborate the
   hypothesis at all — which happens when the predicted number of visible
   edges in a group differs from the number clicked. That mismatch is itself
   evidence, and refusing to guess through it is what keeps a wrong
   hypothesis from quietly scoring well."
  [dims pose intrinsics view picks]
  (reduce
   (fn [acc [g clicks]]
     (if (nil? acc)
       nil
       (let [pred (predicted-order dims pose intrinsics g)]
         (if (not= (count pred) (count clicks))
           nil
           (into acc (map (fn [k c]
                            {:view view :edge k
                             :line (bf/line-through (:p1 c) (:p2 c))})
                          pred clicks))))))
   []
   picks))

(defn- median [xs]
  (let [s (vec (sort xs)) n (count s)]
    (if (zero? n) ##Inf (nth s (quot n 2)))))

(defn score-hypothesis
  "Assign labels under one hypothesis, fit, and report how well it worked.
   Returns nil when too few photos corroborate it to be worth fitting."
  [{:keys [picks thetas intrinsics dims base axis-params scale-constraint sigma-px
           fillet-init lm min-photos]
    :or {sigma-px 1.0 min-photos 2
         axis-params {:phi 0.0 :psi 0.0 :a 0.0 :b 0.0}}}
   hyp]
  (let [ap (merge axis-params (select-keys hyp [:a :b]))
        axis (tt/axis-from-params (:phi ap) (:psi ap) (:a ap) (:b ap))
        poses (hypothesis-poses base axis thetas hyp)
        per-photo (map-indexed
                   (fn [i p] (assign dims (nth poses i) intrinsics i p))
                   picks)
        ok (keep identity per-photo)
        obs (vec (apply concat ok))]
    (when (and (>= (count ok) min-photos) (seq obs))
      (let [res (tt/fit obs
                        (mapv #(+ (:yaw hyp) (* (:sense hyp) %)) thetas)
                        intrinsics dims base ap
                        (cond-> {:sigma-px sigma-px
                                 :scale-constraint scale-constraint
                                 :angle-prior-sigma-deg 1.0
                                 :lm (or lm {:max-iterations 120})}
                          fillet-init (assoc :fillet-init fillet-init)))
            px (bf/rms-reprojection-px res obs {:sigma-px sigma-px})]
        {:hypothesis hyp
         :fit res
         :observations obs
         :photos-used (count ok)
         :rms-px px
         ;; median is the ranking statistic, not the mean: one mislabelled
         ;; edge produces a huge residual that would otherwise decide the
         ;; comparison on its own
         :median-px (median (map #(Math/abs %)
                                 (take (* 2 (count obs)) (:residuals res))))}))))

(defn bootstrap
  "Search the hypotheses and return them ranked, best first.

   Ranked by COVERAGE FIRST, residual second. Sorting on residual alone is a
   trap: a hypothesis that only manages to explain two of the four photos has
   fewer constraints to satisfy and therefore scores a better RMS than one
   that explains all four. Left unchecked it wins, half the operator's clicks
   are silently discarded, and the reported error is computed from the subset
   that happened to be easiest to fit."
  [opts]
  (->> (hypotheses (or (:n-yaw opts) 8)
                   (or (:axis-offsets opts) [[0.0 0.0]]))
       (keep #(score-hypothesis opts %))
       (sort-by (juxt #(- (:photos-used %)) :rms-px))
       vec))

(defn consensus
  "Do the leading hypotheses agree on the dimensions?

   Ranking by residual alone is the wrong test, because a box is symmetric
   under a 180-degree yaw: that pair of hypotheses relabels which face is
   which but describes the SAME solid, fits equally well, and yields the same
   measurements. Demanding a unique winner would reject a perfectly good
   result.

   What actually matters is whether every hypothesis that fits well agrees on
   the answer. If they do, the relabelling is a symmetry and can be ignored.
   If they do not, the labelling is genuinely undecided and the proposals
   must not be trusted — more clicks, not a lower bar."
  ([ranked] (consensus ranked {}))
  ([ranked {:keys [score-factor tol-mm] :or {score-factor 1.5 tol-mm 1.0}}]
   (when (seq ranked)
     (let [best (first ranked)
           ;; compare like with like: a hypothesis explaining fewer photos is
           ;; not a rival, it is a different and smaller problem
           same-coverage (filter #(= (:photos-used %) (:photos-used best)) ranked)
           leading (filter #(<= (:rms-px %) (* score-factor (:rms-px best)))
                           same-coverage)
           dims (map #(:dims (:fit %)) leading)
           spread (fn [i] (let [vs (map #(nth % i) dims)]
                            (- (apply max vs) (apply min vs))))
           spreads (mapv spread (range 3))]
       {:agree? (every? #(< % tol-mm) spreads)
        :dims (:dims (:fit best))
        :n-leading (count leading)
        :dim-spread spreads
        :rms-px (:rms-px best)}))))

;; ---------------------------------------------------------------------------
;; Reprojection: turning a coarse fit into confirmable proposals

(defn reproject
  "Project every edge of the fitted box onto every photo.

   Returns [{:view :theta :edges [{:edge :p1 :p2 :group}]}], the proposals the
   operator confirms or corrects instead of originating."
  [{:keys [dims fillet]} base-pose axis thetas intrinsics]
  (vec (for [[i theta] (map-indexed vector thetas)]
         (let [pose (tt/pose-at-angle base-pose axis theta)
               vis (set (bf/visible-edges dims pose))]
           {:view i :theta theta
            :edges (vec (for [k (range 12)
                              :when (vis k)
                              :let [c (cam/camera-center pose)
                                    [a b] (bf/edge-silhouette-points
                                           dims (or (bf/radius-for-edge fillet k) 0.0)
                                           c k)
                                    pa (cam/project intrinsics pose a)
                                    pb (cam/project intrinsics pose b)]
                              :when (and pa pb)]
                          {:edge k :group (group-of k) :p1 pa :p2 pb}))}))))

;; ---------------------------------------------------------------------------
;; Registration quality, measured apart from the dimensions
;;
;; "Are the dimensions right?" and "are the cameras registered?" are different
;; questions with different answers, and the design doc's product idea — click
;; a photo, the viewport camera goes to that photo's pose — depends on the
;; SECOND one. A systematically shrunken box can still register beautifully:
;; the cameras can sit correctly relative to each other while every dimension
;; is biased. So the registration number must not be derived from the same
;; edges the dimensions came from.
;;
;; Two independent measures, neither of which the fit is allowed to see:
;;
;;   marks   — fiducial pockets at coordinates known from the printed model,
;;             excluded from the fit. Reprojecting them through the estimated
;;             cameras and comparing with where they were clicked gives an
;;             ABSOLUTE registration error in pixels.
;;   holdout — refit without one photo, then measure that photo's own edges
;;             against the result. Needs no fiducials and answers "how well
;;             does this generalise to a view it has never seen", which is
;;             exactly what happens when the operator opens photo 11.

(def target-marks
  "Fiducials of examples/param-acq-target.clj. Each sits on a face, so its
   position along the face normal follows the FITTED dimension rather than the
   nominal one — the print shrinks, and that shrink is part of what is being
   measured. The two in-plane offsets are fixed by the model and are accurate
   to the printer's XY precision."
  [{:id :x+ :axis 0 :sign  1 :in-plane {1 0.0  2 15.0}}
   {:id :x- :axis 0 :sign -1 :in-plane {1 0.0  2 -15.0}}
   {:id :y+ :axis 1 :sign  1 :in-plane {0 -18.0 2 0.0}}
   {:id :y- :axis 1 :sign -1 :in-plane {0 18.0  2 0.0}}
   {:id :z+ :axis 2 :sign  1 :in-plane {0 18.0  1 10.0}}])

(defn mark-position
  "3D position of a fiducial, given the fitted box dimensions."
  [dims {:keys [axis sign in-plane]}]
  (reduce-kv (fn [p ax v] (assoc p ax v))
             (assoc [0.0 0.0 0.0] axis (* sign 0.5 (nth dims axis)))
             in-plane))

(defn pixel-ray
  "World-space ray {:o :d} through a pixel, for a camera pose."
  [intrinsics pose [px py]]
  (let [{:keys [fx fy cx cy]} intrinsics
        dc [(/ (- px cx) fx) (/ (- py cy) fy) 1.0]
        [[a b cc] [d e f] [g h i]] (cam/rodrigues (:rvec pose))
        ;; R^T * d_cam : camera frame back into world
        dw [(+ (* a (nth dc 0)) (* d (nth dc 1)) (* g (nth dc 2)))
            (+ (* b (nth dc 0)) (* e (nth dc 1)) (* h (nth dc 2)))
            (+ (* cc (nth dc 0)) (* f (nth dc 1)) (* i (nth dc 2)))]
        m (Math/sqrt (reduce + 0.0 (map * dw dw)))]
    {:o (cam/camera-center pose) :d (mapv #(/ % m) dw)}))

(defn triangulate
  "Least-squares 3D point closest to a bundle of rays.
   Minimises the sum of squared perpendicular distances, which has the closed
   form (sum of I - d d^T) x = sum of (I - d d^T) o."
  [rays]
  (when (> (count rays) 1)
    (let [acc (reduce (fn [[A b] {:keys [o d]}]
                        (let [P (mapv (fn [i]
                                        (mapv (fn [j]
                                                (- (if (= i j) 1.0 0.0)
                                                   (* (nth d i) (nth d j))))
                                              (range 3)))
                                      (range 3))]
                          [(mapv #(mapv + %1 %2) A P)
                           (mapv + b (la/mat*vec P o))]))
                      [(la/mat-zeros 3 3) [0.0 0.0 0.0]]
                      rays)]
      (la/solve (first acc) (second acc)))))

(defn registration-report
  "Registration quality as MULTI-VIEW CONSISTENCY, with no ground truth.

   `clicks` is [{:view n :id :x+ :px [x y]}]. For each fiducial, the rays from
   every view that sees it are triangulated with the estimated cameras, and
   the triangulated point is reprojected back into each view. The residual is
   how far the views disagree with each other about where that point is.

   This deliberately does NOT compare against the marks' modelled positions.
   An earlier version did, anchoring them to the FITTED box faces — and the
   test showed a 2 mm dimension error producing 14.22 px against a 1 degree
   camera error's 5.82 px. That metric was dominated by exactly the quantity
   it was supposed to be independent of. Consistency between views involves
   no dimension at all, so a shrunken box cannot flatter or spoil it: it
   answers only 'do the cameras agree', which is the question the viewport
   feature depends on."
  [_fit base-pose axis thetas intrinsics clicks]
  (let [poses (mapv #(tt/pose-at-angle base-pose axis %) thetas)
        by-mark (group-by :id clicks)
        rows (mapcat
              (fn [[id cs]]
                (let [rays (mapv #(pixel-ray intrinsics (nth poses (:view %)) (:px %)) cs)
                      p3 (triangulate rays)]
                  (when p3
                    (keep (fn [{:keys [view px]}]
                            (when-let [pp (cam/project intrinsics (nth poses view) p3)]
                              {:view view :id id
                               :err-px (Math/sqrt (+ (Math/pow (- (first pp) (first px)) 2)
                                                     (Math/pow (- (second pp) (second px)) 2)))}))
                          cs))))
              by-mark)
        errs (map :err-px rows)]
    (when (seq errs)
      {:n (count errs)
       :n-marks (count by-mark)
       :rms-px (Math/sqrt (/ (reduce + 0.0 (map #(* % %) errs)) (count errs)))
       :median-px (median errs)
       :max-px (apply max errs)
       :rows (vec (sort-by (comp - :err-px) rows))})))

(defn holdout-report
  "Refit without each photo in turn, then score THAT photo's own edges against
   the result.

   Note this is not the same as the training residual of the reduced fit,
   which is what a naive leave-one-out prints and which always looks good:
   dropping data can only make the remaining fit tighter. The number that
   means something is how the excluded view fares under a fit that never saw
   it, because that is the situation the operator is in on photo 11."
  [obs thetas intrinsics dims base axis-params opts]
  (let [sigma (or (:sigma-px opts) 1.0)
        n-fillet (count (bf/fillet-seq (:fillet-init opts)))
        views (sort (distinct (map :view obs)))]
    (vec
     (for [v views]
       (let [train (vec (remove #(= v (:view %)) obs))
             test (vec (filter #(= v (:view %)) obs))]
         (when (and (seq train) (seq test))
           (let [r (tt/fit train thetas intrinsics dims base axis-params opts)
                 ;; score the held-out view at the trained parameters, with no
                 ;; scale constraint so the number is purely reprojection
                 rfn (tt/make-residual-fn test thetas intrinsics
                                          (-> opts
                                              (dissoc :scale-constraint)
                                              (assoc :n-fillet n-fillet)))
                 rs (rfn (:params r))
                 px (map #(Math/abs (* sigma %)) rs)]
             {:view v
              :train-px (bf/rms-reprojection-px r train {:sigma-px sigma})
              :holdout-px (Math/sqrt (/ (reduce + 0.0 (map #(* % %) px))
                                        (max 1 (count px))))
              :dims (:dims r)})))))))

;; ---------------------------------------------------------------------------
;; The mislabel alarm

(defn residual-report
  "Per-observation reprojection residual, worst first.

   A correctly labelled edge sits at the noise floor. A MISLABELLED one is
   geometrically incompatible with everything else and cannot be absorbed by
   any pose, so it stands out by an order of magnitude. That separation is
   what makes this a usable alarm rather than a table of numbers: the flag
   fires on observations far above the median, not above an absolute
   threshold, so it adapts to how good the extraction actually was."
  [result observations {:keys [sigma-px flag-factor] :or {sigma-px 1.0 flag-factor 4.0}}]
  (let [rs (vec (take (* 2 (count observations)) (:residuals result)))
        per (map-indexed
             (fn [i o]
               (let [a (Math/abs (* sigma-px (nth rs (* 2 i))))
                     b (Math/abs (* sigma-px (nth rs (inc (* 2 i)))))]
                 (assoc o :residual-px (max a b))))
             observations)
        med (median (map :residual-px per))
        limit (* flag-factor (max med 1e-6))]
    {:median-px med
     :flag-threshold-px limit
     :observations (vec (sort-by (comp - :residual-px)
                                 (map #(assoc % :suspect? (> (:residual-px %) limit))
                                      per)))
     :n-suspect (count (filter #(> (:residual-px %) limit) per))}))
