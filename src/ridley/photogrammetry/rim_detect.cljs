(ns ridley.photogrammetry.rim-detect
  "Reading the rim segments — Vincenzo's trattini — from a photograph, GUIDED
   by a pose.

   The rim turns ON exactly where the face discs go blind: a ring seen edge-on
   shows no disc worth detecting (grab-09, and battiscopa5 foto 2: two rings
   edge-on, 13 discs on the whole frame), but its rim paints a long band across
   the image, and the twelve dark dashes on it are each ~10× the area of a
   disc. This module does NOT search the image for dashes — it takes a pose
   (the user's eye alignment, or a partial registration), projects where every
   dash MUST be, and measures how far along the rim the dark bar actually sits.
   Guided-only is a design decision, not a shortcut: an unguided dash detector
   would re-fight the crown's symmetry war (which dash is k?), while under a
   seed pose identity comes free and the measurement is a 1-D problem.

   The measured centres come back as PnP correspondences ({:world :px}): the
   dash centre in cage coordinates against where its bar was found in the
   image. They combine with disc picks in any solve; on an edge-on ring they
   are the only constraints there are.

   Pure: pixels come in through `lum-at` (same contract as blob-detect), so
   the node bench and the browser share the code path."
  (:require [ridley.photogrammetry.cage :as cage]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.linalg :as la]))

(defn- axis-unit [axis]
  (case axis :x [1.0 0.0 0.0] :y [0.0 1.0 0.0] :z [0.0 0.0 1.0]))

(defn- fmt1 [x] (/ (Math/round (* 10.0 x)) 10.0))

(defn- unit [v]
  (let [n (la/v-norm v)]
    (when (> n 1e-12) (la/v-scale v (/ 1.0 n)))))

(defn- mount-fn
  "The as-built map for ring `axis` of `proxy` — flip first, then the measured
   phase, exactly as the anchors and rim-segments take it."
  [proxy axis]
  (let [po (or (get (:cage-phases proxy) axis) 0.0)
        flip? (contains? (or (:cage-flips proxy) #{}) axis)]
    (fn [p]
      (cage/turn-about-axis axis (if flip? (cage/flip-in-ring axis p) p) po))))

(defn- rim-point
  "Cage-coordinate point of ring `axis` at azimuth `a` (rad, ring frame),
   height `z` (ring frame, 0 = mid-plane, +h/2 = the p edge), radius `r`."
  [mount axis r a z]
  (mount (cage/place axis [(* r (Math/cos a)) (* r (Math/sin a)) z])))

(def facing-min
  "How squarely the rim's outer surface must face the camera before its dash
   is looked for: cos of the grazing angle. A face-on ring shows its rim
   edge-on (cos ≈ 0) and its dash as a sliver nobody can centre; 0.25 keeps
   everything up to ~75° off square, which on the bench photos is every dash
   a human would call readable."
  0.25)

(def min-contrast
  "Least flank−dark luminance for a run to count as a dash. The printed
   dashes measure 60-120 on the bench frames; 18 refuses the rim's own
   shading without touching them."
  18.0)

(defn- profile-run
  "The dark run in profile `ps` ([[s lum] …], s in px along the rim) nearest
   s=0: threshold halfway between the darkest sample and the flank level
   (median of the outer third), edges by linear interpolation. Returns
   EVERY dark run, each {:center :length :contrast} — the CALLER filters by
   dash length first and only then picks the nearest, because picking by
   nearness first let a dark blip at the centre beat the true dash 40px out
   (battiscopa5 foto 2: contrast 65-73, all thrown away on :length). A run's
   contrast is its own darkest sample against the flank; the length is the
   honesty check — a shadow or an occluding ring makes runs of the wrong
   size, and a run that is not dash-sized is not a dash, however dark."
  [ps]
  (let [n (count ps)
        lums (mapv second ps)
        third (max 2 (quot n 6))
        flank-samples (concat (take third lums) (take-last third lums))
        flank (nth (vec (sort flank-samples)) (quot (count flank-samples) 2))
        dark (reduce min lums)
        thr (/ (+ flank dark) 2.0)
        below? (mapv #(< % thr) lums)
        runs (loop [i 0 start nil acc []]
               (if (>= i n)
                 (if start (conj acc [start (dec n)]) acc)
                 (recur (inc i)
                        (cond (and (nth below? i) (nil? start)) i
                              (nth below? i) start
                              :else nil)
                        (if (and start (not (nth below? i)))
                          (conj acc [start (dec i)])
                          acc))))
        ;; subpixel edge: linear interpolation of the threshold crossing just
        ;; outside the run (when the run touches the profile edge there is no
        ;; crossing — keep the sample position, the length filter will judge)
        edge (fn [i j]
               (let [[si li] (nth ps i) [sj lj] (nth ps j)]
                 (if (= li lj) si (+ si (* (- sj si) (/ (- thr li) (- lj li)))))))
        run->m (fn [[i j]]
                 (let [e1 (if (pos? i) (edge i (dec i)) (first (nth ps i)))
                       e2 (if (< j (dec n)) (edge j (inc j)) (first (nth ps j)))
                       lo (min e1 e2) hi (max e1 e2)
                       run-dark (reduce min (subvec lums i (inc j)))]
                   {:center (/ (+ lo hi) 2.0) :length (- hi lo)
                    :contrast (- flank run-dark)}))]
    (mapv run->m runs)))

(defn- best-run
  "The dash among `runs`: filtered to plausible length and contrast FIRST,
   then the one nearest the predicted centre. `nil` with a :why when none
   survives — the caller's trace."
  [runs len-px search-px]
  (let [sized (filterv #(< 0.55 (/ (:length %) len-px) 1.45) runs)
        strong (filterv #(> (:contrast %) min-contrast) sized)
        in (filterv #(< (Math/abs (:center %)) search-px) strong)]
    (if (seq in)
      {:run (apply min-key #(Math/abs (:center %)) in)}
      {:why (cond (empty? runs) :no-profile
                  (empty? sized) :length
                  (empty? strong) :contrast
                  :else :out-of-search)
       :best (when (seq runs)
               (apply max-key :contrast runs))})))

(defn read-rim
  "Every rim-segment piece of `proxy` measured in the image under `pose`:
   project the piece, walk the rim ±`search-deg` of azimuth around it, find
   the dash-sized dark run. Returns

     {:hits [{:axis :k :zero? :world :px :predicted-px :offset-px :cross-px
              :contrast :len-ratio} …]
      :tried n
      :misses […]}   ; only with :trace? — each refused piece and its why

   :world is the dash centre in cage coordinates and :px where its bar was
   measured — a PnP correspondence; :offset-px how far the bar sat from the
   pose's prediction (the number the bench reads); :len-ratio measured/model
   dash length (accepted 0.55–1.45).

   nil when `proxy` does not declare rim marks: a cage without the plastic
   must not be read by it."
  [lum-at proxy intrinsics pose
   & [{:keys [search-deg trace? cross-reach-px] :or {search-deg 12.0}}]]
  (when (:rim-marks? proxy)
    (let [h (:cage-h proxy)
          cam-c (cam/camera-center pose)
          crown (cage/rim-spans (:cage-marks proxy))
          hits (atom [])
          tried (atom 0)
          trace (when trace? (atom []))]
      (doseq [{:keys [axis outer]} (:rings proxy)
              {:keys [k zero? spans]} crown
              [a0 a1] spans]
        (let [mount (mount-fn proxy axis)
              ac (/ (+ a0 a1) 2.0)
              hw (/ (- a1 a0) 2.0)
              zc (/ h 4.0)
              C (rim-point mount axis outer ac zc)
              ;; the rim's outward normal is radial in cage coords whatever
              ;; the mounting: flip and phase both map the axis line onto
              ;; itself
              radial (unit (la/v-sub C (la/v-scale (axis-unit axis)
                                                   (la/v-dot C (axis-unit axis)))))
              view (unit (la/v-sub cam-c C))
              facing (when (and radial view) (la/v-dot radial view))]
          (when (and facing (> facing facing-min))
            (let [p-at (fn [a z] (cam/project intrinsics pose
                                              (rim-point mount axis outer a z)))
                  pc (p-at ac zc)
                  p0 (p-at a0 zc)
                  p1 (p-at a1 zc)
                  plo (p-at ac 0.0)
                  phi (p-at ac (/ h 2.0))]
              (when (and pc p0 p1 plo phi)
                (let [len-px (Math/hypot (- (nth p1 0) (nth p0 0))
                                         (- (nth p1 1) (nth p0 1)))
                      band-px (Math/hypot (- (nth phi 0) (nth plo 0))
                                          (- (nth phi 1) (nth plo 1)))
                      ;; px per radian of azimuth, at this piece, this pose
                      px-per-rad (/ len-px (max 1e-9 (* 2.0 hw)))]
                  (when (and (> len-px 10.0) (> band-px 1.2))
                    (swap! tried inc)
                    (let [S (* search-deg (/ Math/PI 180.0))
                          span (+ hw S)
                          step (/ 1.0 px-per-rad)   ; ~1px steps
                          ;; CROSS pre-centring: the band is 1.5mm tall, and a
                          ;; hand-aligned pose is off vertically by more than
                          ;; that before it is off in azimuth — sample ACROSS
                          ;; the band at the predicted centre, find the
                          ;; dash-height dark run, and carry its image-space
                          ;; shift into every later sample. Measured need
                          ;; (battiscopa5 foto 2): without it the tangential
                          ;; profile walks beside the dashes and reads nothing.
                          n2d (unit (la/v-sub (vec phi) (vec plo)))
                          cross-at (fn [pc*]
                                     (let [reach (max (or cross-reach-px 25.0)
                                                      (* 4.0 band-px))
                                           ps (vec (for [i (range (Math/ceil (* 2 reach)))
                                                         :let [c (- i reach)
                                                               u (+ (nth pc* 0) (* c (nth n2d 0)))
                                                               v (+ (nth pc* 1) (* c (nth n2d 1)))
                                                               l (lum-at u v)]
                                                         :when l]
                                                     [c l]))]
                                       (when (> (count ps) 8)
                                         (let [rs (filterv (fn [r]
                                                             (and (> (:contrast r) min-contrast)
                                                                  (< 0.4 (/ (:length r) band-px) 2.4)))
                                                           (profile-run ps))]
                                           (when (seq rs)
                                             (:center (apply min-key #(Math/abs (:center %)) rs)))))))
                          ;; the RESCUE cross (caller sets :cross-reach-px) also
                          ;; tries the profile one dash-width to either side:
                          ;; the cross centres on a dash only when the predicted
                          ;; azimuth lands ON one, and a pose beyond the basin
                          ;; is off in both axes at once (battiscopa5 foto 5:
                          ;; run corte ad alto contrasto = i bordi della barra,
                          ;; con cross nullo su 7 pezzi su 9). Preference order
                          ;; keeps the predicted azimuth first; the tangential
                          ;; profile re-finds the azimuth precisely afterwards.
                          cross (when n2d
                                  (some identity
                                        (for [off (if cross-reach-px
                                                    [0.0 (* 1.2 hw) (* -1.2 hw)]
                                                    [0.0])
                                              ;; == not `zero?`: the crown
                                              ;; piece's own :zero? flag
                                              ;; shadows the core fn here
                                              :let [pc* (if (== 0.0 off)
                                                          pc
                                                          (p-at (+ ac off) zc))]
                                              :when pc*]
                                          (cross-at pc*))))
                          vshift (or cross 0.0)
                          samp (fn [a z]
                                 (when-let [[u v] (p-at a z)]
                                   (lum-at (+ u (* vshift (nth n2d 0)))
                                           (+ v (* vshift (nth n2d 1))))))
                          ;; cross-average over three heights inside the band
                          lum3 (fn [a]
                                 (let [ls (keep #(samp a %)
                                                [(* h 0.15) zc (* h 0.35)])]
                                   (when (= 3 (count ls)) (/ (reduce + ls) 3.0))))
                          ps (vec (for [i (range (Math/ceil (/ (* 2 span) step)))
                                        :let [da (- (* i step) span)
                                              l (lum3 (+ ac da))]
                                        :when l]
                                    [(* da px-per-rad) l]))
                          {:keys [run why best]} (best-run
                                                  (if (> (count ps) 12) (profile-run ps) [])
                                                  len-px (* S px-per-rad))]
                      (if-not run
                        (when trace
                          (swap! trace conj
                                 {:axis axis :k k
                                  :why why
                                  :predicted-px (vec pc)
                                  :cross (when cross (fmt1 cross))
                                  :contrast (some-> best :contrast fmt1)
                                  :len-ratio (some-> best :length (/ len-px) fmt1)
                                  :center-px (some-> best :center fmt1)}))
                        ;; measured image point: the model centre slid along
                        ;; the rim by the run's offset, plus the cross shift
                        (let [da (/ (:center run) px-per-rad)
                              [mu mv] (p-at (+ ac da) zc)]
                          (swap! hits conj
                                 {:axis axis :k k :zero? zero?
                                  :world C
                                  :px [(+ mu (* vshift (nth n2d 0)))
                                       (+ mv (* vshift (nth n2d 1)))]
                                  :predicted-px (vec pc)
                                  :offset-px (:center run)
                                  :cross-px vshift
                                  :contrast (:contrast run)
                                  :len-ratio (/ (:length run) len-px)})))))))))))
      {:hits @hits :tried @tried :misses (some-> trace deref)})))
