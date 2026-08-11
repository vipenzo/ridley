(ns ridley.photogrammetry.match-plate
  "Identity-free registration of a plate (acquisition roadmap fetta B): the user
   clicks 4+ ARBITRARY crown discs — without declaring which mark id each one is —
   and this recovers the assignment (click → mark id) by an exhaustive search
   whose judge is the photo itself. It removes the last friction of the armed
   flow (counting marks from the zero-index) and works on the plate that already
   exists (no reprint, no numbers to read).

   Why exhaustive, not an angle estimate: the crown's angular spacing is warped by
   perspective, so estimating each click's ring angle to look up its id is fragile
   (the very failure the plate was built to avoid). Instead:

   1. Put the clicks in cyclic image order (angle about their centroid). A non-
      mirror camera preserves cyclic order, so the clicks map to 4 marks that are
      consecutive-in-cyclic-order around the crown — but WHICH 4, and at what
      rotation/handedness, is unknown.
   2. Enumerate every cyclic-order-preserving assignment: each k-subset of the m
      marks (in cyclic order) × k rotations × 2 handednesses (the image angle sense
      may be flipped by v-down pixel coordinates). For k=4, m=12 that is
      C(12,4)·4·2 = 3960 candidates — a few tens of ms.
   3. Score each by solving the plane homography from its 4 correspondences
      (pnp/estimate-homography — the marks are coplanar), reprojecting ALL m marks
      + the zero-index, and counting how many land on a real dark disc (a cheap
      `disc-at?` judge sampling the photo). The correct subset+rotation reprojects
      the whole crown onto discs.

   THE 12-FOLD SYMMETRY. A regular crown of m marks is symmetric under a 30°
   turn: an assignment rotated by one mark yields a pose rotated by 30°, under
   which all m reprojected marks STILL land on discs — so the crown count alone
   ties across all m rotations. Only the asymmetric ZERO-INDEX (a 13th disc
   radially inside one crown mark, `zero-obj`) breaks it: it reprojects onto its
   disc under the correct orientation only, so its hit is worth a large bonus and
   decides the rotation. (The twin planar-pose ambiguity does NOT matter here:
   reprojecting coplanar points uses the homography H, which both twins share, so
   scoring is twin-invariant; the final pose's twin is arbitrated downstream by
   refine+rms, exactly as the armed path already does.)

   Pure: it takes `disc-at?` (a fn [px r] -> bool) rather than reading a backdrop,
   so it is node-testable on a synthetic scene, mirroring blob/snap-to-blob's
   injected `lum-at`. The caller (edit_acquire) decides acceptance from the
   returned :zero-hit?/:crown-hits and hands the assignment to the armed solve."
  (:require [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.ellipse :as ellipse]
            [ridley.photogrammetry.pnp :as pnp]))

(def zero-bonus
  "The zero-index hit is worth more than the whole crown, so the one orientation
   that places it wins outright over the m rotational twins that tie on crown
   count. Any value > m works; kept round."
  100)

(def ring-scale
  "The disc-presence ring is sampled at this multiple of the disc's own imaged
   radius, so it lands on the plate just outside the disc edge (not on the disc's
   own dark pixels, which would read as 'no mark')."
  1.5)

(def ^:private radius-fallback 15.0)

(def min-mark-radius-fraction
  "How small, as a fraction of the radius a mark SHOULD image at here, a detected
   blob may be and still count as that mark. Measured, not chosen: on a real webcam
   frame (2026-08-11) a speck of dirt with radius 4px and area 41 sat 9px from where
   the zero-index belonged, while the plate's real discs on that same frame had
   radius 13px and area 517. The presence test asked only 'is there a blob here',
   so the speck stood in for the covered zero-index, the rotation it implied won the
   search on the zero-bonus, and a frame whose reference was hidden registered
   anyway — the one failure this whole design exists to prevent, since a crown of
   equal marks is 12-fold symmetric and only the zero-index says which way it faces.
   0.45 of the expected radius rejects that speck with room to spare and still
   accepts a disc read small by blur or a grazing angle."
  0.45)

(defn blob-judge
  "A `disc-at?` for a detector's output: is there a blob at this pixel that is BIG
   ENOUGH to be a mark? `blobs` is [{:center [u v] :radius-px r} …].

   Size is not a refinement here, it is the whole point. `r`, the radius the caller
   passes, is `ring-scale` times the radius a mark should image at under the pose
   being tested — so the expected mark radius is `r / ring-scale`, and a blob far
   under it is dirt, not a mark. Without that test any dark speck can stand in for
   the zero-index and hand back a plate rotated by a multiple of 30°, with a
   reprojection residual that looks perfect because the crown is symmetric."
  [blobs]
  (fn [[px py] r]
    (let [r2 (* r r)
          min-radius (* min-mark-radius-fraction (/ r ring-scale))]
      (boolean (some (fn [{[bx by] :center rad :radius-px}]
                       (and (<= (+ (* (- bx px) (- bx px)) (* (- by py) (- by py))) r2)
                            (or (nil? rad) (>= rad min-radius))))
                     blobs)))))

;; ---------------------------------------------------------------------------
;; Geometry helpers

(defn- cross3 [[a b c] [d e f]]
  [(- (* b f) (* c e)) (- (* c d) (* a f)) (- (* a e) (* b d))])

(defn- centroid-2d [pts]
  (let [n (double (count pts))]
    [(/ (reduce + 0.0 (map first pts)) n) (/ (reduce + 0.0 (map second pts)) n)]))

(defn- centroid-3d [pts]
  (la/v-scale (reduce la/v-add [0.0 0.0 0.0] pts) (/ 1.0 (count pts))))

(defn- dist-px [[ax ay] [bx by]]
  (Math/sqrt (+ (* (- ax bx) (- ax bx)) (* (- ay by) (- ay by)))))

(defn- combinations
  "Lazy seq of every k-subset of `coll`, each preserving coll's order."
  [coll k]
  (cond
    (zero? k) (list ())
    (empty? coll) ()
    :else (concat (map #(cons (first coll) %) (combinations (rest coll) (dec k)))
                  (combinations (rest coll) k))))

(defn- cyclic-order-2d
  "Indices of `pts` sorted by angle about their centroid — the clicks' cyclic
   image order."
  [pts]
  (let [[cx cy] (centroid-2d pts)]
    (vec (sort-by (fn [i] (let [[x y] (nth pts i)] (Math/atan2 (- y cy) (- x cx))))
                  (range (count pts))))))

(defn- plane-basis
  "In-plane orthonormal frame {:o :u :v} for the coplanar mark objects: o the
   centroid, u along (p0-o), v = n×u with n = (p0-o)×(p1-o). The v direction is
   arbitrary (fixed by the p0,p1 choice) — the search tries both cyclic
   handednesses, so this need only be consistent, not oriented."
  [objs]
  (let [o (centroid-3d objs)
        d0 (la/v-sub (nth objs 0) o)
        d1 (la/v-sub (nth objs 1) o)
        n (cross3 d0 d1)
        u (la/v-scale d0 (/ 1.0 (max 1e-12 (la/v-norm d0))))
        nn (la/v-scale n (/ 1.0 (max 1e-12 (la/v-norm n))))
        v (cross3 nn u)]
    {:o o :u u :v v :n nn}))

(defn- cyclic-order-3d
  "Indices of the mark objects sorted by their angle in the plane frame — the
   crown's cyclic object order."
  [objs {:keys [o u v]}]
  (vec (sort-by (fn [i] (let [d (la/v-sub (nth objs i) o)]
                          (Math/atan2 (la/v-dot d v) (la/v-dot d u))))
                (range (count objs)))))

;; ---------------------------------------------------------------------------
;; Candidate enumeration + scoring

(defn- candidate-assignments
  "Every cyclic-order-preserving map {click-idx -> mark-idx}: for each k-subset of
   the marks (in cyclic order) × k rotations × 2 handednesses, align the clicks
   (in cyclic image order) onto the subset. For fit-crown's clean crown (k≈m) this is
   a single subset × m rotations × 2 ≈ 24 candidates; for fetta-B's 4 clicks it is the
   full C(m,4)·4·2 search."
  [click-order mark-order]
  (let [k (count click-order)]
    (for [positions (combinations (vec (range (count mark-order))) k)
          :let [marks-cyc (mapv #(nth mark-order %) positions)]
          dir [1 -1]
          rot (range k)]
      (into {} (map (fn [j]
                      [(nth click-order j)
                       (nth marks-cyc (mod (+ rot (* dir j)) k))])
                    (range k))))))

(defn- disc-px-radius
  "The disc's approximate imaged radius (px) at object point `obj` under `pose`:
   the larger pixel displacement of a `disc-r`-mm offset along the two in-plane
   axes (conservative under foreshortening). `radius-fallback` when an offset
   projects behind the camera."
  [intrinsics pose obj u v disc-r]
  (let [p0 (cam/project intrinsics pose obj)
        pu (cam/project intrinsics pose (la/v-add obj (la/v-scale u disc-r)))
        pv (cam/project intrinsics pose (la/v-add obj (la/v-scale v disc-r)))]
    (if (and p0 pu pv)
      (max (dist-px p0 pu) (dist-px p0 pv))
      radius-fallback)))

(defn- front-facing?
  "The plate's marked face — outward normal `n-obj` (object frame, oriented toward
   the camera side) — points TOWARD the camera under `pose`: a physically-possible,
   non-mirror view. This is the constraint that rejects the reflection twin: the
   crown+zero pattern is mirror-symmetric about the zero-index's radial axis, so a
   reflected assignment reprojects the whole pattern onto discs and ties on score
   (crown 12 + zero) — only the camera-side (non-mirror) test separates it from the
   true one. n_cam = R·n_obj; a face toward the camera has n_cam.z < 0 (the camera
   looks down +z, so its outward normal points back toward the lens)."
  [pose n-obj]
  (let [[_ _ row2] (cam/rodrigues (:rvec pose))]
    (neg? (la/v-dot row2 n-obj))))

(def max-refine
  "How many top coarse candidates to LM-refine in the fine pass. The true subset's
   ~m rotations + the mirror family are all that reproject the crown near the
   discs, so a few tens comfortably covers them; refining only these keeps the fine
   pass cheap (LM is far dearer than a seed homography)."
  40)

(defn- disc-hits
  "Reproject `objs` (+ `zero-obj`) under `pose` and count disc hits. TIGHT
   (coarse? false): the reprojection must land ON a disc (disc-at? at the point).
   COARSE (coarse? true): a hit also counts if a disc sits within ~2 ring-radii of
   the reprojection (sampled on the 4 axes) — this tolerates the seed homography's
   reprojection error (tens of px on real, lens-distorted data, comparable to the
   disc size), so the true candidate survives the coarse shortlist even though its
   UNREFINED seed misses the discs; the fine pass then LM-refines it onto them."
  [pose objs zero-obj intrinsics disc-at? disc-r {:keys [u v]} coarse?]
  (let [hit? (fn [obj]
               (when-let [[pu pv] (cam/project intrinsics pose obj)]
                 (let [r (* ring-scale (disc-px-radius intrinsics pose obj u v disc-r))]
                   (or (disc-at? [pu pv] r)
                       (when coarse?
                         (let [s (* 2.0 r)]
                           (some (fn [[dx dy]] (disc-at? [(+ pu dx) (+ pv dy)] r))
                                 [[s 0] [(- s) 0] [0 s] [0 (- s)]])))))))]
    {:crown (count (filter hit? objs))
     :zero? (boolean (hit? zero-obj))}))

(defn assign-marks
  "Recover which crown mark each of `clicks` is, identity-free (fetta B). Returns
   the best-scoring candidate or nil.

   clicks     [[u v] …]                 ≥4 blob-snapped disc centroids, any order
   marks      [{:id :obj [x y z]} …]    the m crown marks, object/solver frame
   zero-obj   [x y z]                    the asymmetric zero-index, same frame
   intrinsics {…}                        camera intrinsics
   disc-at?   (fn [px r] -> bool)        cheap dark-disc presence judge
   opts       {:disc-r mm                physical disc radius (default 1.25)
               :face-normal [x y z]}     marked-face outward normal (object frame,
                                         toward the camera) — rejects the mirror
                                         twin; omit only in tests that ignore it

   Two passes, because the seedless homography from k clicks reprojects the rest of
   the crown only coarsely (tens of px on real data, comparable to the disc size —
   too imprecise for the on-disc judge, and the zero-index tiebreak needs a tight
   pose): a COARSE pass seed-scores every cyclic-order-preserving candidate with a
   tolerant hit test and shortlists the top `max-refine`; a FINE pass LM-refines
   each shortlisted pose against its declared correspondences (→ sub-10px), rejects
   the back-facing mirror twin, and scores tightly (on-disc crown + zero bonus).

   Result: {:assignment {click-idx -> mark-idx}   mark-idx = index into `marks`
            :pose {:rvec :t}                        the winning candidate's REFINED pose
            :crown-hits n :zero-hit? bool :score s}
   The CALLER decides acceptance (require :zero-hit? — it fixes the rotation — and
   a crown-hits threshold), then feeds the assignment to the armed solve. Returns
   nil for <4 clicks, fewer marks than clicks, a missing zero-index, or when no
   candidate produced a valid (front-facing) homography at all."
  [clicks marks zero-obj intrinsics disc-at? {:keys [disc-r face-normal] :or {disc-r 1.25}}]
  (when (and (>= (count clicks) 4) (>= (count marks) (count clicks)) zero-obj)
    (let [click-order (cyclic-order-2d clicks)
          objs (mapv :obj marks)
          plane (plane-basis objs)
          mark-order (cyclic-order-3d objs plane)
          ;; COARSE: seed each candidate, tolerant hit count
          coarse (keep (fn [assignment]
                         (let [corr (mapv (fn [[ci mi]]
                                            {:world (:obj (nth marks mi)) :px (nth clicks ci)})
                                          assignment)]
                           (when-let [seed (pnp/estimate-homography corr intrinsics)]
                             {:assignment assignment :corr corr :seed seed
                              :coarse (:crown (disc-hits seed objs zero-obj intrinsics
                                                         disc-at? disc-r plane true))})))
                       (candidate-assignments click-order mark-order))
          shortlist (take max-refine (sort-by :coarse > coarse))
          ;; FINE: refine each shortlisted pose, drop the back-facing mirror, score tight
          fine (keep (fn [{:keys [assignment corr seed]}]
                       (let [pose (or (:pose (pnp/refine corr intrinsics seed {:sigma-px 1.0})) seed)]
                         (when (or (nil? face-normal) (front-facing? pose face-normal))
                           (let [{:keys [crown zero?]} (disc-hits pose objs zero-obj intrinsics
                                                                  disc-at? disc-r plane false)]
                             {:assignment assignment :pose pose
                              :crown-hits crown :zero-hit? zero?
                              :score (+ crown (if zero? zero-bonus 0))}))))
                     shortlist)]
      (when (seq fine)
        (apply max-key :score fine)))))

;; ---------------------------------------------------------------------------
;; Fit-crown — SELECT the crown from a detector's superset, then identify (fetta C)

(def ^:private fit-crown-defaults
  {:min-crown 8         ; accept only if ≥ this many crown marks reproject onto discs (+ zero)
   ;; Conic RANSAC samples. 250 was enough when the candidates were mostly crown; on
   ;; a cluttered frame it is not, and the arithmetic says so rather than the mood:
   ;; a sample is useful only when all FIVE of its points are crown, which for 11
   ;; crown among 24 candidates is C(11,5)/C(24,5) = 1.1%. Over 250 draws that is
   ;; ~2.7 expected useful samples — often zero, and then the crown is never even a
   ;; hypothesis. 1200 draws put the chance of missing it under 1 in 10⁵. Each draw
   ;; is a 5-point linear solve plus one Sampson pass, so this costs milliseconds:
   ;; the old number was not buying speed that anyone could feel.
   :ellipse-iters 1200
   :ellipse-thr 0.04    ; conic inlier band (Sampson, normalised units) — loose enough to
                        ; gather all 12 crown discs on an oblique/noisy frame
   :ellipse-hypotheses 5}) ; how many DIFFERENT rings stage 2 may arbitrate between

(def ^:private ring-slack
  "How many points smaller than the BEST ring a candidate ring may be and still be
   worth a stage-2 solve. Two: the crown is the biggest ring in a plate photo, or
   within a mark or two of it when the object on the plate hides some. Below that a
   ring is junk or a subset of one already kept — and it is also where the cost
   lives, since identifying a k-point ring among m marks enumerates C(m,k) subsets."
  2)

(defn crown-ring-hypotheses
  "Stage 1 on its own: the candidate RINGS among `blob-pts`, best-supported first,
   as inlier index vectors. Reads pixels only — no intrinsics, no pose — so the
   answer is the same at every focal, which is exactly why a caller that tries a
   ladder of seed focals should compute this ONCE and hand it back through
   `:rings`. (Not doing so was the difference between a grab that fails in a moment
   and one that seems to hang.)"
  [blob-pts opts]
  (let [{:keys [ellipse-iters ellipse-thr ellipse-hypotheses]}
        (merge fit-crown-defaults
               (select-keys opts [:ellipse-iters :ellipse-thr :ellipse-hypotheses]))]
    (ellipse/fit-inliers-ranked (vec blob-pts) {:iters ellipse-iters
                                                :thr ellipse-thr
                                                :min-inliers 6
                                                :top-k ellipse-hypotheses})))

(defn fit-crown-explained
  "`fit-crown`, but it says WHY when it doesn't work. Returns the same success map,
   or {:reason kw :message text :crown-hits n}. The reasons are not decoration: they
   have OPPOSITE remedies, and one message for all of them sends the user to fix the
   wrong thing.

     :too-few-blobs    the detector found almost nothing — the plate is out of
                       frame, out of focus, or too small in it
     :no-zero-index    the PROXY carries no zero-index (a source problem, not a
                       photo one)
     :crown-not-found  no ellipse gathered enough points to be a crown
     :not-identified   points on an ellipse, but no cyclic assignment explains them
                       as this crown — usually they were never the crown
     :zero-not-visible **the crown IS recognised and the zero-index is COVERED.**
                       The remedy is two seconds long — turn the plate — and it is
                       the ordinary case, not a rare one: anything big enough to sit
                       on the plate is big enough to hide a mark 6mm inside the
                       crown. Distinguishing this from :crown-not-found is most of
                       the value of this function (2026-08-11: a printer cartridge
                       sat on the zero-index and the message said 'crown not
                       recognised', which is true and useless)
     :too-few-crown    identified, zero visible, but fewer than :min-crown marks
                       land on real discs

   STAGE 1 offers several hypotheses, not one. The ellipse RANSAC judges by inlier
   count, which is weak: on a real cluttered frame a conic through mat dirt and a
   tool's hex holes gathered 12 points against the crown's 11 and won. Stage 2 — is
   this ring equally spaced, and is the zero-index where it must be? — is a far
   stronger judge, so it arbitrates between the top `:ellipse-hypotheses` instead of
   inheriting stage 1's guess. The first hypothesis that passes wins, so a clean
   frame costs exactly what it did before and only a hard one pays for more."
  [blob-pts marks zero-obj intrinsics disc-at? opts]
  (let [{:keys [min-crown ellipse-iters ellipse-thr ellipse-hypotheses]}
        (merge fit-crown-defaults
               (select-keys opts [:min-crown :ellipse-iters :ellipse-thr :ellipse-hypotheses]))]
    (cond
      (not (seq marks))
      {:reason :no-marks :message "this proxy carries no crown marks"}

      (nil? zero-obj)
      {:reason :no-zero-index
       :message (str "this proxy has no zero-index, so nothing can fix the crown's "
                     "rotation — a crown of equal marks is symmetric")}

      (< (count blob-pts) 5)
      {:reason :too-few-blobs
       :message (str "only " (count blob-pts) " dark blobs in the whole frame — "
                     "the plate is out of frame, out of focus, or too small in it")}

      :else
      (let [pts (vec blob-pts)
            m (count marks)
            ;; STAGE 1 — the crown discs lie on an ellipse; take the best few
            ;; candidate ellipses, not just the best one (see the docstring).
            ;; `:rings` lets a caller that tries SEVERAL focals compute these once:
            ;; stage 1 reads only pixels, so it is the same answer at every focal,
            ;; and recomputing it per rung was the whole cost of a failed grab.
            all-rings (or (:rings opts)
                          (ellipse/fit-inliers-ranked pts {:iters ellipse-iters
                                                           :thr ellipse-thr
                                                           :min-inliers 6
                                                           :top-k ellipse-hypotheses}))
            ;; Only rings CLOSE IN SIZE to the best one earn a stage-2 solve, and the
            ;; reason is cost, measured: identifying a PARTIAL ring of k points among m
            ;; marks enumerates C(m,k) subsets — 24 candidates at k=12, 264 at k=11,
            ;; but 11088 at k=7, each scoring a homography against every blob. A frame
            ;; whose rings were 12, 11, 8 and 7 points spent EIGHT SECONDS in stage 2,
            ;; almost all of it on the two small rings, and the user saw a grab that
            ;; seemed to hang (2026-08-11).
            ;; It is also the right rule, not only the cheap one: the crown is the
            ;; biggest ring in the picture or within a mark or two of it — a ring three
            ;; points smaller is junk, or a subset of a bigger one that was already
            ;; kept. `ring-slack` is how many marks of the crown may be hidden by the
            ;; object on the plate and still leave the ring competitive.
            biggest (count (first all-rings))
            hypotheses (filterv #(>= (count %) (max 6 (- biggest ring-slack))) all-rings)
            ;; How ACTIONABLE each failure is. When several hypotheses all fail, the
            ;; user hears about the one they can do something about — a covered
            ;; zero-index ("turn the plate") beats "no ring found" every time, even
            ;; though the latter came from the better-supported ellipse.
            rank {:crown-not-found 0 :not-identified 1 :too-few-crown 2 :zero-not-visible 3}
            better (fn [a b]
                     (cond (> (rank (:reason b) 0) (rank (:reason a) 0)) b
                           (< (rank (:reason b) 0) (rank (:reason a) 0)) a
                           :else (if (> (or (:crown-hits b) 0) (or (:crown-hits a) 0)) b a)))
            failed (fn [res]
                     (cond
                       (nil? res)
                       {:reason :not-identified
                        :message (str "a ring of blobs was found but no assignment explains "
                                      "it as this crown — those points were probably never "
                                      "the plate's marks")}

                       ;; ORDER MATTERS: the crown threshold is checked FIRST, because
                       ;; a decoy ring also fails the zero-index test, and saying "the
                       ;; crown is recognised but the reference is covered" about a
                       ;; ring that matched nothing would send the user to turn a plate
                       ;; the program never found.
                       (< (:crown-hits res) min-crown)
                       {:reason :too-few-crown
                        :crown-hits (:crown-hits res)
                        :message (str "only " (:crown-hits res) " of " m " crown marks land on a "
                                      "real disc (at least " min-crown " are needed)")}

                       (not (:zero-hit? res))
                       {:reason :zero-not-visible
                        :crown-hits (:crown-hits res)
                        :message (str "the crown is recognised (" (:crown-hits res) "/" m
                                      " marks) but the zero-index is not visible — something on "
                                      "the plate is covering it. Turn the plate.")}

                       :else
                       {:reason :too-few-crown
                        :crown-hits (:crown-hits res)
                        :message (str "only " (:crown-hits res) " of " m " crown marks land on a "
                                      "real disc (at least " min-crown " are needed)")}))]
        ;; STAGE 2, per hypothesis, stopping at the FIRST that passes — so a clean
        ;; frame costs exactly one identity solve, as it always did.
        (loop [hs hypotheses
               best {:reason :crown-not-found
                     :message (str "no ring of marks found among the " (count pts)
                                   " blobs — frame the whole plate, and keep it in focus")}]
          (if (empty? hs)
            best
            (let [crown (mapv #(nth pts %) (take m (first hs)))
                  res (when (>= (count crown) 6)
                        (assign-marks crown marks zero-obj intrinsics disc-at?
                                      (select-keys opts [:disc-r :face-normal])))]
              (if (and res (:zero-hit? res) (>= (:crown-hits res) min-crown))
                {:pose (:pose res) :crown-hits (:crown-hits res)
                 :zero-hit? (:zero-hit? res) :score (:score res)
                 :pixels (into {} (keep-indexed
                                   (fn [i mrk]
                                     (when-let [px (cam/project intrinsics (:pose res) (:obj mrk))]
                                       [i px]))
                                   marks))}
                (recur (rest hs)
                       (if (< (count crown) 6) best (better best (failed res))))))))))))

(defn fit-crown
  "Zero-click crown registration (fetta C): given the WHOLE frame's detected dark
   blobs — a superset of the 12 crown marks with the zero-index, the part and noise
   mixed in — recover which blobs are the crown, their identities, and the pose.

   blob-pts   [[u v] …]                 blob-detect/detect-blobs centres (≥5)
   marks      [{:id :obj} …]            the m crown marks, object/solver frame
   zero-obj   [x y z]                    the asymmetric zero-index, same frame
   intrinsics {…}                        camera intrinsics
   disc-at?   (fn [px r] -> bool)        dark-disc / detected-blob presence judge
   opts       {:disc-r :face-normal      as assign-marks
               :min-crown :ellipse-iters :ellipse-thr}

   Two clean stages, not a blind search: (1) the 12 crown marks lie on a CIRCLE, so in
   the image they lie on an ELLIPSE — `ellipse/fit-inliers` RANSAC-fits that ellipse
   and returns the inliers, which ARE the crown (the zero-index sits inside it, the
   noise off it). (2) hand those ~12 clean crown points to `assign-marks`, which now
   has almost no combinatorics to do (12 clicks → 12 marks is a single subset × 12
   rotations × 2 handednesses ≈ 24 candidates, not thousands) — it fixes the rotation
   with the zero-index, rejects the mirror twin, and LM-refines the pose. So a photo
   costs one ellipse fit + one identity solve instead of a per-quartet homography
   storm — the fetta-C speed+robustness fix.

   Returns {:pose :crown-hits :zero-hit? :score
            :pixels {mark-idx [u v]}}   (all marks reprojected under the pose, to
   blob-snap + PnP-solve like find-ring-pose's output) or nil when the ellipse gathers
   too few crown points, or the identity solve misses the zero-index / crown threshold
   — the fail-safe that leaves a hard photo to the ring ('f') or a manual 'p'."
  [blob-pts marks zero-obj intrinsics disc-at? opts]
  (let [r (fit-crown-explained blob-pts marks zero-obj intrinsics disc-at? opts)]
    (when-not (:reason r) r)))

;; ---------------------------------------------------------------------------
;; Sizing the blob-snap window to the picture, not to the camera

(def snap-window-discs
  "How many imaged disc RADII wide the blob-snap window should be. `blob/snap-to-blob`
   splits its window at the midpoint of the window's own [min,max] luminance and then
   requires the dark part to be between `:min-dark-frac` (3%) and `:max-dark-frac`
   (75%) of it — so the window must be scaled to the DISC, not fixed in pixels. A
   window k disc-radii wide is π/(4k²) dark: k=3 gives 8.7%, comfortably inside the
   band, while k=5 falls under the 3% floor and the snap returns nil on a perfectly
   good mark."
  3.0)

(def snap-window-limits
  "[min max] half-size in px for the snap window. The max is the historical fixed
   value, which is what a phone photo's ~23px disc lands on anyway; the min keeps a
   tiny far-away disc's window above `blob/default-opts`'s :min-samples."
  [8 40])

(defn crown-image-scale
  "Image scale (px per mm) on the plate's face, read off the CROWN itself: the median
   ratio of adjacent marks' pixel separation to their true separation. `pixels` is
   fit-crown's {mark-idx [u v]}, `marks` the crown in object order. nil with fewer
   than two.

   Read off the crown rather than derived from intrinsics and depth on purpose: where
   this is used the pose is still a seed, possibly solved at a wrong focal, while the
   crown's imaged size is a fact of the picture either way. Local and
   foreshortening-tolerant enough to SIZE A WINDOW, which is all it is for — nothing
   measured depends on it."
  [pixels marks]
  (let [m (count marks)
        ratios (keep (fn [[mi px]]
                       (let [mj (mod (inc mi) m)]
                         (when-let [qx (get pixels mj)]
                           (let [dpx (dist-px px qx)
                                 dmm (la/v-norm (la/v-sub (:obj (nth marks mi)) (:obj (nth marks mj))))]
                             (when (> dmm 1e-6) (/ dpx dmm))))))
                     pixels)
        sorted (vec (sort ratios))]
    (when (seq sorted) (nth sorted (quot (count sorted) 2)))))

(defn snap-window-radius
  "The blob-snap window (px) this frame's crown deserves: `snap-window-discs` imaged
   disc radii, clamped to `snap-window-limits`. Falls back to the widest window when
   the crown's scale can't be read.

   On a 4032px phone photo a plate disc images at ~23px, 3× clamps back to 40 and the
   existing path is unchanged. On a 1920px LIVE frame the same disc is ~8px, and the
   fixed 40 was 5 radii — under the dark-fraction floor, so every mark's snap refused
   and a good frame looked unregistrable."
  [pixels marks disc-r]
  (let [[lo hi] snap-window-limits]
    (if-let [s (crown-image-scale pixels marks)]
      (max lo (min hi (Math/round (* snap-window-discs s (or disc-r 1.25)))))
      hi)))

;; ---------------------------------------------------------------------------
;; Turntable ring — register a photo from an already-registered one (fetta B+)

(defn- rotate-about
  "Rodrigues rotation of vector `v` about the UNIT axis `k` by `angle` rad."
  [v k angle]
  (let [c (Math/cos angle) s (Math/sin angle)]
    (la/v-add (la/v-add (la/v-scale v c) (la/v-scale (cross3 k v) s))
              (la/v-scale k (* (la/v-dot k v) (- 1.0 c))))))

(defn find-ring-pose
  "Register a plate photo with ZERO clicks by exploiting a turntable ring: given
   the solver pose `ref-pose` of an ALREADY-registered photo, and knowing the
   plate spins about its own axis (the marks' plane normal, through their
   centroid), find the plate rotation θ whose reprojection lands the crown +
   zero-index on discs — the plate rotation of ANOTHER photo of the same ring (the
   camera fixed on the ring, the plate turned by θ from the reference).

   Reprojecting a mark at angle θ = project(ref-pose, spin(mark, θ)): spinning the
   OBJECT under the fixed reference view is exactly equivalent to the camera
   orbiting the axis, so one registered photo generates the whole ring. The crown
   is 12-fold symmetric, so 12 angles score the crown alike; the asymmetric
   zero-index elects the true one (as in assign-marks). The disc test is COARSE
   (the ring is only approximate — hand-held cameras scatter off it — and the disc
   size absorbs it); the caller blob-snaps the returned pixels and runs a full PnP
   to get the exact pose. `disc-at?` samples THIS photo's luminance.

   The plate spins about its own normal (the marks' plane) through their centre.
   That is accurate for a NEAR reference — a hand-held ring is not a perfect
   circle, so extrapolating a far-away photo drifts — hence the caller predicts
   each photo from its NEAREST registered reference (max score over all).

   Returns {:theta rad :crown-hits n :zero-hit? bool :score s
            :pixels {mark-idx [u v]}} (pixels = the predicted mark pixels to
   blob-snap + solve on) or nil when no angle lands the crown. The caller requires
   :zero-hit? and a crown threshold before trusting it."
  [ref-pose marks zero-obj intrinsics disc-at? {:keys [disc-r step-deg]
                                                :or {disc-r 1.25 step-deg 1.0}}]
  (when (and ref-pose zero-obj (>= (count marks) 4))
    (let [objs (mapv :obj marks)
          {:keys [o n] :as plane} (plane-basis objs)
          axis (la/v-scale n (/ 1.0 (max 1e-12 (la/v-norm n))))
          spin (fn [p a] (la/v-add o (rotate-about (la/v-sub p o) axis a)))
          step (* step-deg (/ Math/PI 180.0))
          n-steps (max 1 (int (/ (* 2.0 Math/PI) step)))
          scored (keep (fn [k]
                         (let [a (* k step)
                               spun (mapv #(spin % a) objs)
                               ;; TIGHT (not coarse): a clean ring pair reprojects
                               ;; WITHIN the disc, and only the tight test keeps the
                               ;; zero-index tiebreak sharp — a coarse ±2-radius test
                               ;; lets the zero "hit" at several rotations and a
                               ;; wrong θ wins (the 1115px failure on a 9° pair).
                               {:keys [crown zero?]} (disc-hits ref-pose spun (spin zero-obj a)
                                                                intrinsics disc-at? disc-r plane false)]
                           (when (pos? crown)
                             {:theta a :crown-hits crown :zero-hit? zero?
                              :score (+ crown (if zero? zero-bonus 0))})))
                       (range n-steps))]
      (when (seq scored)
        (let [best (apply max-key :score scored)]
          (assoc best :pixels
                 (into {} (keep-indexed
                           (fn [i m]
                             (when-let [px (cam/project intrinsics ref-pose (spin (:obj m) (:theta best)))]
                               [i px]))
                           marks))))))))
