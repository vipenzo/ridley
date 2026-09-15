(ns ridley.photogrammetry.blob-detect
  "GLOBAL dark-disc detector for a registration plate or CAGE photo (acquisition
   roadmap fetta C, and the cage's fetta 1): find the centroids of ALL the marks in
   a whole frame WITHOUT a pose or a prediction — the zero-click front end that
   feeds match-plate/fit-crown (which then selects the crown subset, identifies it
   with the zero-index, and solves the pose). blob/snap-to-blob refines a KNOWN pixel in a small window; this scans the
   ENTIRE frame with nothing to key on but 'a dark, roundish, disc-sized region on a
   lighter plate'.

   Method (a classic blob detector, tuned on the real param-plate photos — recall
   9-12/12 crown marks per well-framed shot, ~15-40 candidates total):

     1. DOWNSAMPLE the frame ~3× (block-average) — a 4032px phone frame → ~1344px.
        The disc (~15-40px imaged radius) survives; per-pixel noise and thin printed
        text (the paper variant's numbers) shrink toward nothing, and every later
        pass is ~9× cheaper.
     2. ADAPTIVE THRESHOLD (dark := below a local BRIGHT REFERENCE by `:margin`; for
        a plate that reference is the mean over a `:win`-radius box, via an integral
        image). Local, not global, so it ignores the illumination gradient across a
        real frame and the arbitrary
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
   bands themselves are expressed in DOWNSAMPLED pixels.

   THE CAGE (`cage-opts`) is the same four steps with two of them changed, because
   its scene is the plate's turned inside out: thin bright bands over a dark
   background instead of one wide white field. Step 2's reference becomes the local
   MAX rather than the mean (a mean over a window wide enough to clear a mark is
   mostly background, and sinks below the marks themselves), and step 4 gains a
   FULL-RESOLUTION shape test — `enclosed-frac`, which asks the original pixels
   whether the dark spot has the band's white around it. Step 1 stops downsampling
   at all: at a third of the resolution an inner ring's mark is four pixels across
   and a band's edge, blurred, is indistinguishable from a mark. Measured on
   Vincenzo's photographs (2026-08-23 → 08-24): the plate settings found the outer
   ring and NOTHING on the two inner ones, and the first cage attempt found 10 true
   marks among 89 candidates; this finds ~29 among 32, on all three rings."
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
   :reference :mean      ; local bright reference the dark threshold is taken from: :mean | :max
   ;; the full-resolution shape test (enclosed-frac) — OFF unless :enclose-frac is
   ;; set; the rest are its calibration, measured on the cage (see cage-opts)
   :enclose-frac nil     ; nil = off; else min fraction of full-res rays that must reach bright
   :enclose-contrast 55.0 ; a ray's pixel is 'bright' this many levels above the blob's centre
   :enclose-span 1.7     ; ray length = this × the blob's LONG half-axis, in full-res px
   :enclose-min-len 14   ; …but never shorter than this many full-res px
   :enclose-dirs 16      ; how many directions the rays go
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

(defn- max-line!
  "Sliding-window maximum along ONE axis of a 2-D array: for every position it
   writes max(src over [i-r, i+r]) into `dst`. `n` values per line, `lines` lines,
   `stride` between neighbours in a line, `line-stride` between lines — so the same
   code does the horizontal pass (stride 1) and the vertical one (stride sw).

   O(1) per pixel via a monotonic deque of indices whose values decrease: pushing i
   pops every smaller value behind it (they can never be the max again while i is in
   the window), so the front is always the window's maximum. Without it a (2r+1)²
   dilation at the cage's r=26 would cost 53 comparisons per pixel per axis — over a
   billion on a 12-Mpx frame, paid on a keypress."
  [^js src ^js dst n lines stride line-stride r]
  (let [dq (js/Int32Array. n)]
    (dotimes [l lines]
      (let [base (* l line-stride)
            at (fn [i] (aget src (+ base (* i stride))))]
        (loop [i 0 head 0 tail 0]
          (when (< i (+ n r))
            (let [tail (if (< i n)
                         (let [v (at i)
                               t (loop [t tail]
                                   (if (and (> t head) (<= (at (aget dq (dec t))) v))
                                     (recur (dec t)) t))]
                           (aset dq t i)
                           (inc t))
                         tail)
                  o (- i r)
                  ;; drop what has fallen out of the window behind o
                  head (loop [hd head]
                         (if (and (< hd tail) (< (aget dq hd) (- o r))) (recur (inc hd)) hd))]
              (when (>= o 0)
                (aset dst (+ base (* o stride)) (at (aget dq head))))
              (recur (inc i) head tail))))))))

(defn- dilate-max
  "Local MAXIMUM of `small` over a (2r+1)² square, as a Float32Array sw×sh.
   Separable: a horizontal max-line! then a vertical one over the result."
  [^js small sw sh r]
  (let [tmp (js/Float32Array. (* sw sh))
        out (js/Float32Array. (* sw sh))]
    (max-line! small tmp sw sh 1 sw r)
    (max-line! tmp out sh sw sw 1 r)
    out))

(defn- dark-mask
  "Uint8Array (sw×sh): 1 where `small` is below the local BRIGHT REFERENCE by
   `margin`. Which reference is the difference between a plate and a cage:

   `:mean` (a plate) — the box mean over a window ≫ a disc. A plate IS one wide
   white field, so its mean is the white the disc must be darker than.

   `:max` (a cage) — the local MAXIMUM over the same window, i.e. a morphological
   top-hat. A cage's marks sit on thin bright bands over a dark background, and a
   window wide enough to clear a mark also swallows the background: the mean goes
   dark and a real mark is no longer below it. Measured on a real photograph
   (2026-08-23): the big ring gave every mark, the two inner rings gave NONE.
   The MAX cannot be dragged down that way — a window centred on a mark still
   touches the band it is printed on, so the reference stays the band's white
   however dark the surroundings. What the max lets in instead is a RIBBON of
   background within `win` of every band edge; that ribbon is one enormous
   connected region, which the area filter throws away in a single piece — where
   a narrowed mean fragmented the same background into hundreds of specks."
  [small sw sh {:keys [reference win margin]}]
  (let [n (* sw sh)
        mask (js/Uint8Array. n)]
    (if (= reference :max)
      (let [ref (dilate-max small sw sh win)]
        (dotimes [i n]
          (when (< (aget small i) (- (aget ref i) margin)) (aset mask i 1))))
      (let [integ (integral-image small sw sh)]
        (dotimes [y sh]
          (dotimes [x sw]
            (when (< (aget small (+ x (* y sw)))
                     (- (box-mean integ sw sh win x y) margin))
              (aset mask (+ x (* y sw)) 1))))))
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

(defn enclosed-frac
  "Fraction of `dirs` rays leaving (cx,cy) that meet a pixel at least `contrast`
   luminance levels brighter than the blob's centre within `len` px — sampled at
   FULL resolution, on the original photo, one pixel at a time.

   This is the SHAPE criterion, and it is what the downsampled passes above cannot
   supply: a mark is a dark spot with the band's white all round it, while the
   background beside a band is a dark spot with white on ONE side only. At reduced
   resolution the two look alike — a band edge blurs into something disc-sized —
   so the question has to be put to the real pixels.

   Rays, rather than a fixed-radius annulus, because the cage's marks are seen at
   every obliquity: a mark on an inner ring images as an ellipse three times longer
   than it is wide, and an annulus wide enough to clear its long axis leaves the
   band entirely along its short one. A ray stops at the FIRST bright pixel it
   meets, so each direction asks only 'is there white this way, before the dark
   starts again' — which the short direction answers at 4px and the long one at 16.

   Rays that leave the frame without deciding are not counted either way; if every
   ray does, the answer is 0.0 (nothing is known about what surrounds it)."
  [lum-at cx cy len contrast dirs]
  (let [centre (let [vs (for [dy [-1 0 1] dx [-1 0 1]
                              :let [l (lum-at (+ cx dx) (+ cy dy))] :when l] l)]
                 (when (seq vs) (/ (reduce + vs) (count vs))))]
    (if (nil? centre)
      0.0
      (let [need (+ centre contrast)
            step (/ (* 2.0 Math/PI) dirs)]
        (loop [i 0 bright 0 judged 0]
          (if (>= i dirs)
            (if (zero? judged) 0.0 (/ bright (double judged)))
            (let [a (* i step) ux (Math/cos a) uy (Math/sin a)
                  r (loop [t 1.0]
                      (if (> t len)
                        :dark
                        (let [l (lum-at (+ cx (* ux t)) (+ cy (* uy t)))]
                          (cond (nil? l) :off
                                (>= l need) :bright
                                :else (recur (+ t 1.0))))))]
              (recur (inc i)
                     (if (= r :bright) (inc bright) bright)
                     (if (= r :off) judged (inc judged))))))))))

(def cage-opts
  "detect-blobs options for a registration CAGE, as opposed to a plate. Every
   difference comes from one fact — a cage's marks live on thin bright bands over
   a dark background, not on one wide white field:

   - `:reference :max` — the dark threshold is measured from the local MAXIMUM, not
     the local mean, which a dark background drags down until the inner rings'
     marks stop being dark at all (see dark-mask).
   - `:target-long-edge` at the frame's own size, i.e. NO downsampling. It is not a
     refinement: the marks on an inner ring sit 4-6px from their band's edge, and
     one round of block-averaging closes that gap so the mark's dark pixels join the
     background and are carried off inside one enormous component. Measured: at half
     resolution 2 of the 4 known inner marks vanish, at full resolution none do.
   - `:enclose-frac` — the full-resolution ray test (see enclosed-frac). At 0.35 it
     is not asking for white ALL round: a mark on a band's edge only ever gets ~0.5,
     and demanding more throws it away. What earns its keep is the CONTRAST the rays
     must find (`:enclose-contrast`, 55 levels): the leather background's grain is
     full of little enclosed pits, and on a well-lit frame they contributed 50 of 80
     candidates until the rays were made to insist on real white.
   - a bigger `:max-aspect`, because the rings are seen at every obliquity and a
     mark on an inner one images as an ellipse three times longer than wide.

   The numbers are the middle of the measured plateau, not the edge of a cliff. On
   IMG_9014 recall is complete for `:margin` 55-60 (below, marks merge into the
   background; above, they shrink under `:min-area`) and for `:enclose-frac` up to
   0.44 — the score of the single hardest mark, one lying against a band edge."
  {:reference :max :win 26 :margin 58
   :target-long-edge 4032
   :min-area 20 :max-area 2600 :max-aspect 6.0 :min-fill 0.35
   :enclose-frac 0.35
   :max-blobs 200})

(defn detect-blobs
  "Detect dark disc candidates in the whole frame. `lum-at` (fn [x y] -> 0-255 | nil)
   samples the photo, `[w h]` its pixel size. `opts` overrides `default-opts`; pass
   `:rgba` (the raw RGBA byte array, 4/px) to take the FAST downsample path (one tight
   loop, no per-pixel closure call — production always does this).

   Returns a vector `[{:center [u v] :radius-px r :area a :fill f :half-len l} …]`
   in FULL-resolution pixels (plus `:enclosed`, the shape test's score, when it ran),
   best (largest, roundest) first, at most `:max-blobs`. Empty when the frame holds
   no disc-shaped dark region. The centroids land within a few
   px of the true disc centres (comfortably inside blob/snap-to-blob's window), so
   the caller snaps them for sub-px correspondences after fit-crown assigns ids."
  ([lum-at size] (detect-blobs lum-at size nil))
  ([lum-at [w h] opts]
   (let [{:keys [target-long-edge win margin min-area max-area max-aspect
                 min-fill max-blobs snap? rgba
                 reference enclose-frac enclose-contrast enclose-span
                 enclose-min-len enclose-dirs]}
         (merge default-opts opts)
         d (max 1 (Math/round (/ (max w h) (double target-long-edge))))
         {:keys [data sw sh]} (if rgba (downsample-rgba rgba w h d) (downsample lum-at w h d))
         mask (dark-mask data sw sh {:reference reference :win win :margin margin})
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
                                :area area :fill fill :score (* area fill)
                                ;; the LONG half-axis in full-res px — how far a ray
                                ;; must travel before the mark itself stops being dark
                                :half-len (* d 0.5 (max bw bh))}))))
                   ;; the full-resolution shape test runs LAST, on the handful that
                   ;; survived the cheap downsampled filters — it costs real pixel
                   ;; reads, and there is no point paying them for the background
                   (map (fn [{[cu cv] :center :keys [half-len] :as b}]
                          (cond-> b
                            enclose-frac
                            (assoc :enclosed
                                   (enclosed-frac lum-at cu cv
                                                  (max enclose-min-len (* enclose-span half-len))
                                                  enclose-contrast enclose-dirs)))))
                   (filter (fn [b] (or (nil? enclose-frac) (>= (:enclosed b) enclose-frac))))
                   (sort-by :score >)
                   (take max-blobs)
                   vec)]
     (mapv (fn [{:keys [center radius-px] :as b}]
             (let [c (if snap?
                       (or (:center (blob/snap-to-blob lum-at center (max 12 (Math/round radius-px)))) center)
                       center)]
               (-> b (assoc :center c) (dissoc :score))))
           kept))))
