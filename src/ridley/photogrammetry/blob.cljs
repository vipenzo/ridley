(ns ridley.photogrammetry.blob
  "Sub-pixel localisation of a plate mark in a photo: the centroid of the dark
   disc (a flat colour region — dark on the light plate) inside a local window
   around a PREDICTED pixel. This is the 'blob-snap' of the acquisition roadmap
   (brief §1, fette A/B/C): once a coarse pose registers a few marks, the rest
   reproject to known-ish pixels and each is snapped to its actual disc centre
   without a click — a KNOWN target (a filled disc of expected polarity), so a
   generic edge-snap is the wrong tool (it has no closed region to lock onto).

   Deliberately pure: it takes a `lum-at` sampler (fn [x y] -> 0-255 luminance,
   or nil off-image) rather than reading the backdrop itself, so it is
   node-testable on a synthetic disc and reusable by the future automatic
   detector (fetta C), which runs the SAME centroid over blobs it found by
   scanning the whole frame rather than in a caller-supplied window.

   Method: sample the window, split dark/light by a midpoint threshold between
   the window's own min and max luminance (robust to exposure — no absolute
   cutoff), take the darkness-weighted centroid of the dark pixels, and
   RECENTRE the window on it a few times (mean-shift): a prediction that lands
   off-centre would otherwise bias the centroid toward the window, and a couple
   of recentres walk it onto the true disc centre. Rejects (nil) a window with
   too little contrast (no disc — flat plate) or an implausible dark fraction
   (shadow/occluding part, not a crisp mark).")

(def default-opts
  {:polarity :dark        ; the mark is darker than its surround (dark disc on light plate)
   :threshold-frac 0.5    ; split at the midpoint of the window's [min,max] luminance
   :min-contrast 25.0     ; a window flatter than this (max-min) has no mark to lock onto
   :min-samples 25        ; too little of the window on-image to trust a centroid
   :min-dark-frac 0.03    ; below this the "blob" is noise, not a disc
   :max-dark-frac 0.75    ; above this the window is mostly dark (shadow/part), not a crisp mark
   :max-iters 4           ; mean-shift recentres
   :step 1})              ; window sampling stride in px (2 to trade accuracy for speed)

(defn- window-stats
  "One pass over the window [cu±r, cv±r] (stride `step`): {:n :lo :hi} over the
   sampled luminances, skipping off-image pixels (nil). `polarity` :light
   inverts luminance (255-l) so the rest of the pipeline always treats a mark
   as the LOW values."
  [lum-at cu cv r step polarity]
  (let [invert? (= polarity :light)]
    (loop [dy (- r) n 0 lo js/Infinity hi (- js/Infinity)]
      (if (> dy r)
        {:n n :lo lo :hi hi}
        (let [[n lo hi]
              (loop [dx (- r) n n lo lo hi hi]
                (if (> dx r)
                  [n lo hi]
                  (let [raw (lum-at (+ cu dx) (+ cv dy))]
                    (if (nil? raw)
                      (recur (+ dx step) n lo hi)
                      (let [l (if invert? (- 255.0 raw) raw)]
                        (recur (+ dx step) (inc n) (min lo l) (max hi l)))))))]
          (recur (+ dy step) n lo hi))))))

(defn- dark-centroid
  "Darkness-weighted centroid of the pixels below `threshold` in the window:
   weight = threshold - l (how far below the split), so a crisp disc dominates a
   faint shadow. Returns {:cu :cv :n :dark} (n sampled, dark below threshold) or
   nil if nothing is below threshold."
  [lum-at cu cv r step polarity threshold]
  (let [invert? (= polarity :light)]
    (loop [dy (- r) sw 0.0 swx 0.0 swy 0.0 n 0 dark 0]
      (if (> dy r)
        (when (pos? sw) {:cu (/ swx sw) :cv (/ swy sw) :n n :dark dark})
        (let [[sw swx swy n dark]
              (loop [dx (- r) sw sw swx swx swy swy n n dark dark]
                (if (> dx r)
                  [sw swx swy n dark]
                  (let [x (+ cu dx) y (+ cv dy)
                        raw (lum-at x y)]
                    (if (nil? raw)
                      (recur (+ dx step) sw swx swy n dark)
                      (let [l (if invert? (- 255.0 raw) raw)]
                        (if (< l threshold)
                          (let [w (- threshold l)]
                            (recur (+ dx step) (+ sw w) (+ swx (* w x)) (+ swy (* w y))
                                   (inc n) (inc dark)))
                          (recur (+ dx step) sw swx swy (inc n) dark)))))))]
          (recur (+ dy step) sw swx swy n dark))))))

(defn snap-to-blob
  "Refine a predicted pixel `[cu cv]` to the centroid of the dark mark within a
   window of half-size `radius` px, sampling via `lum-at` (fn [x y] -> 0-255 or
   nil off-image). Returns {:center [u v] :contrast :dark-frac :iters} or nil
   when the window holds no confident mark (too flat, too little/much dark). See
   the ns docstring for the method. `opts` overrides default-opts (e.g.
   :polarity :light, :threshold-frac, the accept gates)."
  ([lum-at center radius] (snap-to-blob lum-at center radius nil))
  ([lum-at [cu0 cv0] radius opts]
   (let [{:keys [polarity threshold-frac min-contrast min-samples
                 min-dark-frac max-dark-frac max-iters step]}
         (merge default-opts opts)
         r (max 1 (int radius))]
     (loop [cu (double cu0) cv (double cv0) iter 0]
       (let [ci (Math/round cu) cj (Math/round cv)
             {:keys [n lo hi]} (window-stats lum-at ci cj r step polarity)]
         (cond
           (< n min-samples) nil                 ; too little of the window on-image
           (< (- hi lo) min-contrast) nil        ; flat — no disc against the plate
           :else
           (let [threshold (+ lo (* threshold-frac (- hi lo)))
                 c (dark-centroid lum-at ci cj r step polarity threshold)]
             (when c
               (let [dark-frac (/ (:dark c) (max 1 (:n c)))]
                 (when (and (>= dark-frac min-dark-frac) (<= dark-frac max-dark-frac))
                   (let [ncu (:cu c) ncv (:cv c)
                         moved (+ (Math/abs (- ncu cu)) (Math/abs (- ncv cv)))]
                     (if (or (>= iter (dec max-iters)) (< moved 0.5))
                       {:center [ncu ncv] :contrast (- hi lo) :dark-frac dark-frac :iters (inc iter)}
                       (recur ncu ncv (inc iter))))))))))))))
