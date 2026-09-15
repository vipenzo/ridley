(ns ridley.photogrammetry.plate-focal
  "The focal length of a lens NOBODY declared, read off the registration plate.

   Every photo the channel has registered so far arrived with its focal already
   known: an EXIF tag on a phone still, or the session's own fitted value once
   `bundle/refine-session` had two views to separate the lens from the distances.
   A LIVE frame — a webcam, a phone in Continuity Camera — carries neither. It has
   no EXIF at all, and on the very first frame of a session there is no second view
   to fit against. Without a focal to start from there is nothing to register: the
   crown's identity solve tolerates a wrong focal (see below), but the pose it
   hands back does not, and a residual computed at the wrong focal is a LENS error
   wearing a pose's clothes.

   The plate answers the question by itself, in closed form, because it is FLAT.
   A plane images through a homography H = λ·K·[r1 r2 t], and r1 ⊥ r2 together
   with ‖r1‖ = ‖r2‖ are two constraints that survive into H — the classic Zhang
   calibration constraints. With the principal point at the image centre and square
   pixels (what `camera/intrinsics-from-fov` builds, and therefore what the whole
   channel already assumes), the only unknown left in K is f, and each constraint
   gives it outright:

       h̃ᵢ = K⁻¹hᵢ = (aᵢ/f, bᵢ/f, cᵢ)
       h̃1·h̃2 = 0      →  f² = −(a1a2 + b1b2) / (c1c2)
       ‖h̃1‖ = ‖h̃2‖    →  f² = ((a1²+b1²) − (a2²+b2²)) / (c2² − c1²)

   Two INDEPENDENT estimates from ONE frame, which is the whole reason this beats
   guessing: they must agree, and when they disagree the frame is saying it cannot
   answer. Both denominators are the perspective terms c — how much the plate's
   image is a perspective picture of it rather than merely an affine one. A plate
   shot FRONTO-PARALLEL has c1 = c2 = 0 and both estimates collapse together: a
   head-on ring of discs looks the same at every focal, the distance absorbing the
   difference. That is not noise to be averaged away, it is a blind spot with a
   remedy — tilt the plate, or move off its axis — so it is reported by name
   instead of answered with a number nobody should trust.

   WHY THE IDENTITY SOLVE CAN RUN FIRST, at a focal that is merely plausible.
   `match-plate/assign-marks` scores a candidate by reprojecting the crown and the
   zero-index and asking whether each lands on a real disc — and those points are
   all ON THE PLATE, i.e. coplanar. Coplanar reprojection goes through H, which the
   focal does not change: what the focal changes is how H is split into a pose. So
   the identities (which blob is mark 7) come back right from a seed focal that is
   only in the right ballpark, and this namespace turns those identities into the
   true focal. The caller still tries a short ladder of seeds, because
   `assign-marks` also runs a pose-space LM refine and a front-facing test, and
   neither of those is perfectly focal-blind.

   Pure: correspondences and an image size in, a focal out. No photo, no session."
  (:require [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.pnp :as pnp]))

(def plausible-band-mm
  "The 35mm-equivalent focals a live frame is allowed to claim. A webcam or a
   phone in Continuity Camera sits around 25-35mm-equivalent; a long lens on a
   plate is unusual but not impossible. Outside this band the closed form has not
   measured a lens, it has fitted noise — most often because the crown identities
   were wrong, which is exactly the failure a plausibility band is for."
  [10.0 150.0])

(def max-spread
  "How far the two independent constraints may disagree, as a fraction of the
   larger. They are two different functions of the same homography, so on a clean
   oblique frame they land within a few percent; a wide split means the plate's
   image is not well explained by ONE plane-to-image homography at all (mistaken
   identities, a badly snapped disc, or a view too close to fronto-parallel for
   either denominator to mean anything)."
  0.30)

;; ---------------------------------------------------------------------------

(defn- normal-equations
  "(AᵀA, Aᵀb) over `rows` [[coeffs rhs] …] for an `n`-unknown least squares.
   pnp has its own private twin; duplicated rather than made public because the
   two are 12 lines of accumulator and sharing them would tie a calibration
   detail to the pose solver's internals."
  [n rows]
  (let [ata (mapv (fn [_] (double-array n)) (range n))
        atb (double-array n)]
    (doseq [[a b] rows]
      (dotimes [i n]
        (let [ai (nth a i)]
          (aset atb i (+ (aget atb i) (* ai b)))
          (dotimes [j n]
            (let [^doubles row (nth ata i)]
              (aset row j (+ (aget row j) (* ai (nth a j)))))))))
    [(mapv vec ata) (vec atb)]))

(defn plane-homography
  "The homography of the plate's own plane, in the ONE parametrisation the focal
   constraints need: an ORTHONORMAL in-plane basis on the object side (so H's
   first two columns really are K·r1 and K·r2, which is what makes ‖r1‖=‖r2‖ and
   r1⊥r2 readable off them) and CENTRED, half-width-normalised pixels on the image
   side (so the recovered f comes out in half-widths, and the normal equations are
   conditioned no matter the megapixels).

   Returns the 8-vector [h11 h12 h13 h21 h22 h23 h31 h32] with h33 pinned to 1 —
   the plate origin is in front of the camera, so its depth is positive and the pin
   fixes scale AND sign at once (estimate-homography's own argument). nil for fewer
   than 4 correspondences, collinear world points, or a singular system.

   `correspondences` is [{:world [x y z] :px [u v]} …], all :world coplanar."
  [correspondences [iw ih]]
  (when (>= (count correspondences) 4)
    (when-let [{:keys [o u v]} (pnp/plane-frame (mapv :world correspondences))]
      (let [cx (/ iw 2.0)
            cy (/ ih 2.0)
            s (/ iw 2.0)
            ab (mapv (fn [{:keys [world]}]
                       (let [q (la/v-sub world o)]
                         [(la/v-dot q u) (la/v-dot q v)]))
                     correspondences)
            ;; Scaling the plane coordinates multiplies h1 and h2 by the SAME
            ;; factor, so both constraints are invariant to it — but the normal
            ;; equations are not, and the plate's coordinates are tens of mm
            ;; against pixels normalised to ~1.
            extent (reduce max 1e-9 (mapcat (fn [[a b]] [(Math/abs a) (Math/abs b)]) ab))
            k (/ 1.0 extent)
            rows (mapcat (fn [{:keys [px]} [a0 b0]]
                           (let [a (* a0 k)
                                 b (* b0 k)
                                 xn (/ (- (first px) cx) s)
                                 yn (/ (- (second px) cy) s)]
                             [[[a b 1.0 0.0 0.0 0.0 (- (* xn a)) (- (* xn b))] xn]
                              [[0.0 0.0 0.0 a b 1.0 (- (* yn a)) (- (* yn b))] yn]]))
                         correspondences ab)
            [ata atb] (normal-equations 8 rows)]
        (la/solve ata atb)))))

(def ^:private min-perspective
  "How much perspective a constraint's denominator must carry, relative to the
   homography's own scale, before it is allowed to divide by it. The denominators
   ARE the perspective terms; below this they are the numerical residue of a
   fronto-parallel view and the quotient is noise amplified without limit."
  1e-4)

(defn focal-from-homography
  "f (in HALF-WIDTHS of the image, the unit plane-homography works in) from the
   two Zhang constraints, or a named refusal.

   Returns {:f-norm f :perpendicular f₁ :equal-norm f₂ :spread d}. The two
   estimates are kept separate on purpose: their disagreement is the only
   self-check a SINGLE frame has, and it is worth more than their average.

   ONE of the two can legitimately vanish on its own, and it happens in an
   ordinary way: a camera that orbits the plate while staying level leaves one of
   the plate's own axes parallel to the image plane, so that column has no
   perspective term (c₂ = 0), the perpendicularity constraint divides by zero — and
   the equal-norm one is perfectly well posed. Refusing there would refuse a good
   frame for the shape of the operator's arm. So a lone constraint is USED, and
   flagged `:single-constraint? true`: what is missing is not the measurement but
   the second opinion, and `:spread` is nil rather than a reassuring zero.

   {:reason :fronto-parallel} when NEITHER denominator carries perspective — the
   frame genuinely cannot see the focal — and {:reason :impossible} when they do
   but no real f comes out of either."
  [h]
  (let [a1 (nth h 0) b1 (nth h 3) c1 (nth h 6)
        a2 (nth h 1) b2 (nth h 4) c2 (nth h 7)
        ;; scale-free floor: the columns' own magnitude, so the test means "this
        ;; denominator is perspective" and not "this image is big"
        s2 (max 1e-30 (* (+ (* a1 a1) (* b1 b1) (* c1 c1))
                         (+ (* a2 a2) (* b2 b2) (* c2 c2))))
        floor (* min-perspective (Math/sqrt s2))
        d-perp (* c1 c2)
        d-eq (- (* c2 c2) (* c1 c1))
        f2-perp (when (> (Math/abs d-perp) floor)
                  (/ (- (+ (* a1 a2) (* b1 b2))) d-perp))
        f2-eq (when (> (Math/abs d-eq) floor)
                (/ (- (+ (* a1 a1) (* b1 b1)) (+ (* a2 a2) (* b2 b2))) d-eq))
        f-perp (when (and f2-perp (pos? f2-perp)) (Math/sqrt f2-perp))
        f-eq (when (and f2-eq (pos? f2-eq)) (Math/sqrt f2-eq))
        fs (filterv some? [f-perp f-eq])]
    (cond
      (and (nil? f2-perp) (nil? f2-eq))
      {:reason :fronto-parallel}

      (empty? fs)
      ;; a usable denominator but no positive f²: no real focal explains this
      ;; homography — these correspondences are not one view of one plane
      {:reason :impossible}

      :else
      {:f-norm (/ (reduce + fs) (count fs))
       :perpendicular f-perp
       :equal-norm f-eq
       :single-constraint? (= 1 (count fs))
       :spread (when (= 2 (count fs))
                 (let [hi (reduce max fs) lo (reduce min fs)]
                   (/ (- hi lo) hi)))})))

(defn f-norm->equiv-mm
  "Half-widths → 35mm-EQUIVALENT mm, the unit the whole channel carries a focal in
   (`camera/equiv-focal->hfov-deg`'s diagonal convention, not the 36mm-width one).
   f_px = f_norm·(w/2) and f_px = w·focal_eq/eff_width, so focal_eq = f_px·eff_width/w."
  [f-norm [iw ih]]
  (let [aspect (/ iw ih)
        eff-width (* cam/frame-35mm-diagonal-mm
                     (/ aspect (Math/sqrt (+ (* aspect aspect) 1.0))))
        f-px (* f-norm (/ iw 2.0))]
    (/ (* f-px eff-width) iw)))

(defn estimate-focal
  "The lens of a frame that declares nothing, from ≥4 identified plate marks.

   `correspondences` is [{:world [x y z] :px [u v]} …] — the crown marks in the
   object frame against the pixels they were snapped to, i.e. exactly what
   `pnp/solve-pnp` is about to be handed. `image-size` is [w h] in pixels.

   Returns {:focal-mm f :f-px :spread :perpendicular :equal-norm} on success, or
   {:reason kw :message text} when the frame cannot answer. `:focal-mm` is present
   ONLY on success — a refusal that also carried it would be read as an answer by
   the first caller that forgot to check — so `(:focal-mm result)` is a safe test
   for 'did this frame measure its lens'. Refusals:

     :too-few          fewer than 4 identified marks
     :degenerate       the marks are collinear, or the system is singular
     :fronto-parallel  the plate is too square-on: at this angle EVERY focal
                       explains the image, with the distance taking up the slack
     :impossible       no real focal fits — the correspondences are not one
                       consistent view of one plane (usually wrong identities)
     :inconsistent     the two constraints disagree by more than `max-spread`
     :out-of-band      a real answer, but outside `plausible-band-mm`

   The refusals carry the number that caused them, because 'tilt the plate' and
   'your identities are wrong' are different actions and the caller must be able
   to say which one it is."
  [correspondences image-size]
  (if (< (count correspondences) 4)
    {:reason :too-few
     :message (str "the focal needs at least 4 identified marks, this frame has "
                   (count correspondences))}
    (if-let [h (plane-homography correspondences image-size)]
      (let [res (focal-from-homography h)]
        (case (:reason res)
          :fronto-parallel
          {:reason :fronto-parallel
           :message (str "the plate is too square-on to measure the lens: at this angle "
                         "every focal explains the image and the distance absorbs the "
                         "difference. Tilt the plate, or shoot it from further off its axis.")}

          :impossible
          {:reason :impossible
           :message (str "no real focal explains these marks — they are not one "
                         "consistent view of one plane, so the identities are the "
                         "first thing to doubt.")}

          (let [{:keys [f-norm spread perpendicular equal-norm single-constraint?]} res
                focal-mm (f-norm->equiv-mm f-norm image-size)
                mm #(when % (f-norm->equiv-mm % image-size))
                [lo hi] plausible-band-mm]
            (cond
              (and spread (> spread max-spread))
              {:reason :inconsistent
               :spread spread
               :message (str "the two independent focal constraints disagree by "
                             (.toFixed (* 100 spread) 0) "% ("
                             (.toFixed (mm perpendicular) 1) "mm vs "
                             (.toFixed (mm equal-norm) 1)
                             "mm): this frame is not a clean view of the plate.")}

              (or (< focal-mm lo) (> focal-mm hi))
              ;; the number goes back under a DIFFERENT key: a refusal that also
              ;; carries :focal-mm is a refusal every caller will eventually read as
              ;; an answer
              {:reason :out-of-band
               :measured-mm focal-mm
               :message (str "the measured focal is " (.toFixed focal-mm 1)
                             "mm-equivalent, outside the plausible " lo "-" hi
                             "mm band — the marks were probably identified wrongly.")}

              :else
              {:focal-mm focal-mm
               :f-px (* f-norm (/ (first image-size) 2.0))
               :spread spread
               :single-constraint? single-constraint?
               :perpendicular (mm perpendicular)
               :equal-norm (mm equal-norm)}))))
      {:reason :degenerate
       :message "the identified marks don't define a plane (collinear, or a singular system)"})))
