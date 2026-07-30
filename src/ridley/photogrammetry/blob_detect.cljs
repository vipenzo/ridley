(ns ridley.photogrammetry.blob-detect
  "GLOBAL dark-disc detector for a plate photo (acquisition roadmap fetta C): find
   the centroids of ALL the plate's dark marks in a whole frame WITHOUT a pose or a
   prediction — the zero-click front end that feeds match-plate/fit-crown (which
   then selects the crown subset, identifies it with the zero-index, and solves the
   pose). blob/snap-to-blob refines a KNOWN pixel in a small window; this scans the
   ENTIRE frame with nothing to key on but 'a dark, roundish, disc-sized region on a
   lighter plate'.

   Method (a classic blob detector, tuned on the real param-plate photos — recall
   9-12/12 crown marks per well-framed shot, ~15-40 candidates total):

     1. DOWNSAMPLE the frame ~3× (block-average) — a 4032px phone frame → ~1344px.
        The disc (~15-40px imaged radius) survives; per-pixel noise and thin printed
        text (the paper variant's numbers) shrink toward nothing, and every later
        pass is ~9× cheaper.
     2. ADAPTIVE THRESHOLD (dark := below the local mean by `:margin`, the mean taken
        over a `:win`-radius box via an integral image). Local, not global, so it
        ignores the illumination gradient across a real frame and the arbitrary
        background outside the plate — and, crucially, the box is far LARGER than a
        disc so a disc darkens against its own plate surround (a box smaller than the
        disc would see only disc inside a big mark and threshold nothing).
     3. CONNECTED COMPONENTS of the dark mask (8-connectivity, explicit-stack flood
        fill), each reduced to centroid + bbox + area.
     4. FILTER to disc-shaped components — area in a band, bbox aspect near 1, high
        fill (area/bbox) — then keep the top `:max-blobs` by area·fill. This drops the
        big dark PART (over-area), thin text/edges (low fill / high aspect) and frame
        noise, and BOUNDS the candidate count fit-crown's search runs over.

   Deliberately pure and node-testable: it takes the SAME `lum-at` sampler the rest
   of the pipeline injects (fn [x y] -> 0-255 | nil off-image) plus the frame `size`,
   so a synthetic scene of discs validates it exactly as blob-snap's tests do. It
   does NOT identify or de-duplicate the zero-index / part / crown — that is
   fit-crown's geometric job; the detector only needs high recall of the crown and a
   bounded, mostly-disc candidate set.

   Scale note: the pixel-unit defaults assume the phone-photo regime the whole
   acquire flow targets (~4000px frames, disc ~15-40px radius, EXIF ~48mm). The
   downsample factor tracks the frame's long edge so those bands stay meaningful; the
   bands themselves are expressed in DOWNSAMPLED pixels."
  (:require [ridley.photogrammetry.blob :as blob]))

(def default-opts
  {:target-long-edge 1350 ; downsample so the long edge is ~this many px (D = round(long/this), ≥1)
   :win 24               ; local-mean half-window in DOWNSAMPLED px (≫ a disc, ≪ mark spacing)
   :margin 8.0           ; dark := below the local mean by this many luminance levels
   :min-area 25          ; component area (downsampled px) — drops specks / thin text
   :max-area 950         ; — drops the big dark part and merged blobs
   :max-aspect 2.8       ; bbox long/short ratio — a disc images to a near-round ellipse
   :min-fill 0.5         ; area / bbox-area — a filled disc fills its box; a stroke/edge does not
   :max-blobs 40         ; keep the best this-many by area·fill (bounds fit-crown's search)
   :snap? false})        ; sub-px refine each centroid with blob/snap-to-blob (caller usually re-snaps)

(defn- downsample
  "Block-average `lum-at` over D×D blocks into a Float32Array of size sw×sh (row-
   major). Off-image samples (nil) are skipped in the block mean; a fully off-image
   block (never happens for an interior grid) falls back to 0. The fallback path for
   tests/pure callers; production hands the raw RGBA array to downsample-rgba, which
   is ~10× faster (no per-pixel closure call)."
  [lum-at w h d]
  (let [sw (quot w d) sh (quot h d)
        out (js/Float32Array. (* sw sh))]
    (dotimes [sy sh]
      (dotimes [sx sw]
        (let [x0 (* sx d) y0 (* sy d)]
          (loop [j 0 s 0.0 n 0]
            (if (< j d)
              (let [[s n] (loop [i 0 s s n n]
                            (if (< i d)
                              (let [l (lum-at (+ x0 i) (+ y0 j))]
                                (recur (inc i) (if l (+ s l) s) (if l (inc n) n)))
                              [s n]))]
                (recur (inc j) s n))
              (aset out (+ sx (* sy sw)) (if (pos? n) (/ s n) 0.0)))))))
    {:data out :sw sw :sh sh}))

(defn- downsample-rgba
  "Block-average luminance (0.299R+0.587G+0.114B, matching lum-at) over D×D blocks of
   a raw RGBA byte array `rgba` (4 bytes/px, row-major, as canvas getImageData gives)
   into a Float32Array sw×sh. The whole reduction is ONE tight loop with inline array
   reads — no per-pixel function call — so a 12-Mpx frame downsamples in ~100ms
   instead of the seconds ~12M lum-at closure calls take (the batch would otherwise
   block the main thread long enough to drop the dev-server connection)."
  [rgba w h d]
  (let [sw (quot w d) sh (quot h d)
        out (js/Float32Array. (* sw sh))
        inv (/ 1.0 (* d d))]
    (dotimes [sy sh]
      (dotimes [sx sw]
        (let [x0 (* sx d) y0 (* sy d)]
          (aset out (+ sx (* sy sw))
                (* inv
                   (loop [j 0 s 0.0]
                     (if (< j d)
                       (recur (inc j)
                              (loop [i 0 s s]
                                (if (< i d)
                                  (let [o (* 4 (+ x0 i (* (+ y0 j) w)))]
                                    (recur (inc i)
                                           (+ s (* 0.299 (aget rgba o))
                                              (* 0.587 (aget rgba (+ o 1)))
                                              (* 0.114 (aget rgba (+ o 2))))))
                                  s)))
                       s)))))))
    {:data out :sw sw :sh sh}))

(defn- integral-image
  "Summed-area table of `small` (sw×sh), as a Float64Array of (sw+1)×(sh+1) so the
   box sum over any rectangle is 4 lookups."
  [small sw sh]
  (let [iw (inc sw)
        integ (js/Float64Array. (* iw (inc sh)))]
    (dotimes [y sh]
      (let [row (atom 0.0)]
        (dotimes [x sw]
          (swap! row + (aget small (+ x (* y sw))))
          (aset integ (+ (inc x) (* (inc y) iw))
                (+ (aget integ (+ (inc x) (* y iw))) @row)))))
    integ))

(defn- box-mean
  "Mean of `small` over the [x±win,y±win] box (clamped to the image) via `integ`."
  [integ sw sh win x y]
  (let [iw (inc sw)
        x0 (max 0 (- x win)) y0 (max 0 (- y win))
        x1 (min (dec sw) (+ x win)) y1 (min (dec sh) (+ y win))
        a (aget integ (+ x0 (* y0 iw)))
        b (aget integ (+ (inc x1) (* y0 iw)))
        c (aget integ (+ x0 (* (inc y1) iw)))
        dd (aget integ (+ (inc x1) (* (inc y1) iw)))
        n (* (- (inc x1) x0) (- (inc y1) y0))]
    (/ (+ (- dd b c) a) n)))

(defn- dark-mask
  "Uint8Array (sw×sh): 1 where `small` is below its local box mean by `margin`."
  [small sw sh integ win margin]
  (let [mask (js/Uint8Array. (* sw sh))]
    (dotimes [y sh]
      (dotimes [x sw]
        (when (< (aget small (+ x (* y sw)))
                 (- (box-mean integ sw sh win x y) margin))
          (aset mask (+ x (* y sw)) 1))))
    mask))

(defn- components
  "8-connectivity connected components of the dark `mask` (sw×sh), each
   {:cx :cy :area :bw :bh} (centroid + bbox extents, in downsampled px). Explicit-
   stack flood fill; `label` (Int32Array) marks visited (component id, 1-based)."
  [mask sw sh]
  (let [n-px (* sw sh)
        label (js/Int32Array. n-px)
        stack (js/Array.)]
    ;; comps is threaded through the loop — a transient's conj! may return a NEW
    ;; reference, so the accumulator must be carried, not mutated in place
    (loop [start 0 cur 0 comps (transient [])]
      (if (>= start n-px)
        (persistent! comps)
        (if (and (pos? (aget mask start)) (zero? (aget label start)))
          (let [cur (inc cur)]
            (set! (.-length stack) 0)
            (.push stack start)
            (aset label start cur)
            (let [comp (loop [sx 0.0 sy 0.0 n 0 mnx sw mxx -1 mny sh mxy -1]
                         (if (zero? (.-length stack))
                           {:cx (/ sx n) :cy (/ sy n) :area n
                            :bw (inc (- mxx mnx)) :bh (inc (- mxy mny))}
                           (let [p (.pop stack)
                                 cx (mod p sw) cy (quot p sw)]
                             ;; push 8-neighbours that are dark and unlabelled
                             (loop [dy -1]
                               (when (<= dy 1)
                                 (loop [dx -1]
                                   (when (<= dx 1)
                                     (let [nx (+ cx dx) ny (+ cy dy)]
                                       (when (and (>= nx 0) (>= ny 0) (< nx sw) (< ny sh))
                                         (let [q (+ nx (* ny sw))]
                                           (when (and (pos? (aget mask q)) (zero? (aget label q)))
                                             (aset label q cur)
                                             (.push stack q)))))
                                     (recur (inc dx))))
                                 (recur (inc dy))))
                             (recur (+ sx cx) (+ sy cy) (inc n)
                                    (min mnx cx) (max mxx cx) (min mny cy) (max mxy cy)))))]
              (recur (inc start) cur (conj! comps comp))))
          (recur (inc start) cur comps))))))

(defn detect-blobs
  "Detect dark disc candidates in the whole frame. `lum-at` (fn [x y] -> 0-255 | nil)
   samples the photo, `[w h]` its pixel size. `opts` overrides `default-opts`; pass
   `:rgba` (the raw RGBA byte array, 4/px) to take the FAST downsample path (one tight
   loop, no per-pixel closure call — production always does this).

   Returns a vector `[{:center [u v] :radius-px r :area a :fill f} …]` in FULL-
   resolution pixels, best (largest, roundest) first, at most `:max-blobs`. Empty
   when the frame holds no disc-shaped dark region. The centroids land within a few
   px of the true disc centres (comfortably inside blob/snap-to-blob's window), so
   the caller snaps them for sub-px correspondences after fit-crown assigns ids."
  ([lum-at size] (detect-blobs lum-at size nil))
  ([lum-at [w h] opts]
   (let [{:keys [target-long-edge win margin min-area max-area max-aspect
                 min-fill max-blobs snap? rgba]} (merge default-opts opts)
         d (max 1 (Math/round (/ (max w h) (double target-long-edge))))
         {:keys [data sw sh]} (if rgba (downsample-rgba rgba w h d) (downsample lum-at w h d))
         integ (integral-image data sw sh)
         mask (dark-mask data sw sh integ win margin)
         comps (components mask sw sh)
         half (/ d 2.0)
         kept (->> comps
                   (keep (fn [{:keys [cx cy area bw bh]}]
                           (let [aspect (/ (max bw bh) (double (max 1 (min bw bh))))
                                 fill (/ area (double (max 1 (* bw bh))))]
                             (when (and (>= area min-area) (<= area max-area)
                                        (<= aspect max-aspect) (>= fill min-fill))
                               {:center [(+ (* cx d) half) (+ (* cy d) half)]
                                :radius-px (* d (Math/sqrt (/ area Math/PI)))
                                :area area :fill fill :score (* area fill)}))))
                   (sort-by :score >)
                   (take max-blobs)
                   vec)]
     (mapv (fn [{:keys [center radius-px] :as b}]
             (let [c (if snap?
                       (or (:center (blob/snap-to-blob lum-at center (max 12 (Math/round radius-px)))) center)
                       center)]
               (-> b (assoc :center c) (dissoc :score))))
           kept))))
