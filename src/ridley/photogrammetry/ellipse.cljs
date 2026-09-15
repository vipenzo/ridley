(ns ridley.photogrammetry.ellipse
  "Robust ELLIPSE (conic) fit over a 2-D point set by RANSAC — fetta-C's tool for
   SELECTING the plate's crown discs out of the detector's superset. The 12 crown
   marks lie on a circle, so in the image they lie on an ELLIPSE; the zero-index
   (radially inside) and the frame noise do NOT, so the best-fitting ellipse's
   INLIERS are exactly the crown. This turns fit-crown from a blind quartet search
   (thousands of homographies per photo) into: fit one ellipse → hand the ~12 clean
   crown points to a single identity solve.

   A general conic is a·x²+b·xy+c·y²+d·x+e·y+f = 0. We fit it from 5 points with the
   constant pinned (f=1) and la/solve — the same scale-pin trick estimate-homography
   uses for h33, valid because a centred ellipse's constant term is non-zero (and we
   Hartley-normalise first, so the crown ellipse IS centred near the origin). Pixel
   coordinates make x²~10⁷: without normalisation the 5×5 is hopelessly ill-
   conditioned, so normalisation is not optional.

   Deterministic (a seeded LCG, never Math/random), so it is node-testable and gives
   the same answer every run — no flaky registration."
  (:require [ridley.photogrammetry.linalg :as la]))

(defn- prng
  "A tiny deterministic LCG yielding [0,1) — reproducible RANSAC sampling."
  [seed]
  (let [s (atom (bit-and (bit-or (int seed) 1) 0x7fffffff))]
    (fn []
      (/ (unsigned-bit-shift-right
          (swap! s #(bit-and (+ (* % 1103515245) 12345) 0x7fffffff)) 0)
         2147483648.0))))

(defn- norm-of
  "Hartley normalisation with its transform kept: translate `pts` to their
   centroid and scale so the mean distance to the origin is √2. Returns
   {:np <normalised points> :cx :cy :s} — the transform matters to callers that
   need to carry a point (an ellipse CENTRE) back to raw pixels."
  [pts]
  (let [n (count pts)
        cx (/ (reduce + 0.0 (map first pts)) n)
        cy (/ (reduce + 0.0 (map second pts)) n)
        md (/ (reduce + 0.0 (map (fn [[x y]] (Math/hypot (- x cx) (- y cy))) pts)) n)
        s (/ (Math/sqrt 2.0) (max 1e-9 md))]
    {:np (mapv (fn [[x y]] [(* s (- x cx)) (* s (- y cy))]) pts)
     :cx cx :cy cy :s s}))

(defn- fit-conic-5
  "Conic [a b c d e f] through the 5 points, f pinned to 1. nil if singular."
  [pts5]
  (let [A (mapv (fn [[x y]] [(* x x) (* x y) (* y y) x y]) pts5)
        b (vec (repeat 5 -1.0))]
    (when-let [sol (la/solve A b)]
      (conj (vec sol) 1.0))))

(defn- ellipse?
  "Is the conic an ellipse (not a parabola/hyperbola)? b² − 4ac < 0."
  [[a b c _ _ _]]
  (neg? (- (* b b) (* 4.0 a c))))

(defn- sampson-sq
  "Squared Sampson distance of `[x y]` to the conic — |Q|²/|∇Q|², the first-order
   geometric residual (≈ squared distance to the curve). ~0 on the conic."
  [[a b c d e f] [x y]]
  (let [q (+ (* a x x) (* b x y) (* c y y) (* d x) (* e y) f)
        gx (+ (* 2.0 a x) (* b y) d)
        gy (+ (* b x) (* 2.0 c y) e)]
    (/ (* q q) (max 1e-12 (+ (* gx gx) (* gy gy))))))

(defn- pick5
  "5 distinct indices in [0,n) from the generator `r`."
  [r n]
  (loop [acc #{}]
    (if (>= (count acc) 5)
      (vec acc)
      (recur (conj acc (min (dec n) (int (* (r) n))))))))

(defn- distinct-rings
  "Greedy over descending support, keeping only rings that are genuinely NEW: a
   candidate sharing more than `max-overlap` of the smaller set with one already
   kept is the same ring seen again. `hyps` is {inlier-vec conic}; returns
   [[inl conic] …], at most `top-k`."
  [hyps top-k max-overlap]
  (loop [remaining (sort-by (fn [[inl _]] (- (count inl))) hyps)
         kept []]
    (if (or (empty? remaining) (>= (count kept) top-k))
      kept
      (let [[inl conic] (first remaining)
            s (set inl)
            same-ring? (some (fn [[kept-inl _]]
                               (let [shared (count (filter s kept-inl))]
                                 (> shared (* max-overlap (min (count inl) (count kept-inl))))))
                             kept)]
        (recur (rest remaining) (if same-ring? kept (conj kept [inl conic])))))))

(defn- ransac-kept
  "The independent-conic RANSAC over NORMALISED points `np`: every 5-point
   sample that fits an ellipse gathering `:min-inliers`, keyed by its inlier
   SET so the many samples that rediscover the same ellipse count once, then
   distinct-rings. Returns [[inl conic] …] in the normalised frame — the
   shared engine of fit-inliers-ranked and fit-concentric-ranked (which needs
   the CONICS too, for their centres)."
  [np {:keys [iters thr min-inliers seed top-k max-overlap]
       :or {iters 150 thr 0.03 min-inliers 6 seed 1 top-k 1 max-overlap 0.5}}]
  (let [n (count np)]
    (if (< n 5)
      []
      (let [r (prng seed)
            thr2 (* thr thr)
            hyps (loop [i 0 acc {}]
                   (if (>= i iters)
                     acc
                     (let [conic (fit-conic-5 (mapv #(nth np %) (pick5 r n)))]
                       (if (and conic (ellipse? conic))
                         (let [inl (filterv #(< (sampson-sq conic (nth np %)) thr2) (range n))]
                           (recur (inc i)
                                  (if (and (>= (count inl) min-inliers) (not (contains? acc inl)))
                                    (assoc acc inl conic)
                                    acc)))
                         (recur (inc i) acc)))))]
        (distinct-rings hyps top-k max-overlap)))))

(defn fit-inliers-ranked
  "The RANSAC's top `:top-k` DISTINCT ellipse hypotheses through `pts`, each as its
   inlier INDICES sorted by fit (closest first), the largest inlier set first.

   Why more than one. On a clean scene the best-supported ellipse IS the answer and
   the first entry is all anyone needs. On a cluttered one it can lose by a point:
   measured on a real webcam frame (2026-08-11), a conic through dirt on the cutting
   mat and the hex holes of a tool in the background gathered 12 points while the
   plate's own crown gathered 11 — so the single best hypothesis was junk, and
   whatever consumed it never got to see the crown at all. Inlier count is a weak
   judge; the CALLER usually has a much stronger one (for the crown: are these 12
   points equally spaced, and is the zero-index where it should be?). Handing it
   several hypotheses lets the strong judge decide instead of the weak one.

   opts:
     :iters       RANSAC samples (default 150)
     :thr         inlier band — Sampson distance in NORMALISED units (default 0.03)
     :min-inliers give up below this many (default 6)
     :seed        LCG seed (default 1) — determinism
     :top-k       how many hypotheses to return (default 1)
     :max-overlap two hypotheses count as the SAME ring above this fraction of
                  shared inliers (default 0.5)

   The hypotheses must be DIFFERENT rings, not merely different index sets. A strong
   ellipse is rediscovered by many random samples, and each near-miss — the same ring
   minus one straggler — is a distinct set: without this, `top-k` 5 buys five
   variations of one wrong answer and the true crown never reaches the caller. That
   is not hypothetical, it is what the first version of this did.

   Returns [] when fewer than 5 points or no ellipse gathers :min-inliers."
  [pts {:keys [top-k] :or {top-k 1} :as opts}]
  (let [{:keys [np]} (norm-of pts)]
    (->> (ransac-kept np (assoc opts :top-k top-k))
         (mapv (fn [[inl conic]]
                 (vec (sort-by #(sampson-sq conic (nth np %)) inl)))))))

(defn fit-inliers
  "The single best-supported ellipse's inlier INDICES through `pts`, sorted by fit.
   [] when fewer than 5 points or no ellipse gathers :min-inliers. See
   fit-inliers-ranked for the options — and for why a caller that has a stronger
   judge than inlier count should ask for several hypotheses instead of this one."
  [pts opts]
  (or (first (fit-inliers-ranked pts (assoc opts :top-k 1))) []))

;; ── the concentric family: the cage's rings share their centre ───────────────
;;
;; Lever 1 of the zero-click frontier (2026-08-29). Six of eight bench refusals
;; die because the true ring never emerges from the independent RANSAC above:
;; with ~25 candidates a ring showing eight clean discs is found by a 5-point
;; sample with probability (8/25)⁵ ≈ 0.3% per draw, and junk conics threading
;; the stragglers of three interleaved crowns gather more support than any true
;; ring. But the cage GIVES AWAY the constraint the search is missing: its three
;; rings share one centre, so every true ring — and every junk conic threading
;; three true crowns — is centred on (nearly) the same image point. Pin the
;; centre and an ellipse has THREE unknowns, not five: 3-point samples, cheap
;; enough to try EVERY triple deterministically, and a ring with eight discs
;; among thirty candidates cannot hide from an exhaustive search.
;;
;; "Nearly": the projected centre of a circle is not the centre of its image
;; ellipse, and the three rings' projected centres spread a little with
;; obliquity — which is why the pinned fit is only the DISCOVERY tool (its
;; threshold a shade wider), and everything it finds still faces the caller's
;; regularity ranking, the identity solve and the physical guards.

(defn- conic-center
  "Centre of conic [a b c d e f] — where the gradient vanishes; nil when
   degenerate (a parabola has none)."
  [[a b c d e _]]
  (let [det (- (* 4.0 a c) (* b b))]
    (when (> (Math/abs det) 1e-12)
      [(/ (- (* b e) (* 2.0 c d)) det)
       (/ (- (* b d) (* 2.0 a e)) det)])))

(defn- central-fit-3
  "[A B C] of the central conic A·u² + B·uv + C·v² = 1 through three CENTRED
   points; nil when singular (near-collinear sample)."
  [[[u1 v1] [u2 v2] [u3 v3]]]
  (when-let [sol (la/solve [[(* u1 u1) (* u1 v1) (* v1 v1)]
                            [(* u2 u2) (* u2 v2) (* v2 v2)]
                            [(* u3 u3) (* u3 v3) (* v3 v3)]]
                           [1.0 1.0 1.0])]
    (vec sol)))

(defn- central-ellipse?
  "Is A·u² + B·uv + C·v² = 1 an ellipse? Its matrix must be positive definite."
  [[A B C]]
  (and (pos? A) (pos? (- (* A C) (* 0.25 B B)))))

(defn- triples
  "Every 3-subset of [0,n) when there are at most `cap` of them — deterministic
   and exhaustive, the point of pinning the centre — else `cap` LCG-sampled
   ones (n(n−1)(n−2)/6 passes cap around n≈45 at the default; a frame with that
   many candidates has bigger problems than sampling luck)."
  [n cap seed]
  (let [total (quot (* n (dec n) (- n 2)) 6)]
    (if (<= total cap)
      (for [i (range n) j (range (inc i) n) k (range (inc j) n)] [i j k])
      (let [r (prng seed)
            pick3 (fn [] (loop [acc #{}]
                           (if (>= (count acc) 3)
                             (vec (sort acc))
                             (recur (conj acc (min (dec n) (int (* (r) n))))))))]
        (into #{} (repeatedly cap pick3))))))

(defn sisters-about
  "Ellipse hypotheses CENTRED (to first order) on `center` — each a vector of
   inlier indices into `pts`, sorted by fit, largest set first. The pinned form
   has 3 unknowns, so every 3-point triple is tried (see `triples`); a sampled
   5-point search needs luck a sparse ring cannot afford.

   opts: :thr (Sampson, normalised units — default 0.05, wider than the free
   search because the pin is approximate), :min-inliers (default 8, the
   identity solve's cost wall, not an ellipse-quality bar), :top-k (default 4),
   :max-overlap (default 0.5), :max-triples (default 15000), :seed."
  [pts [cx cy] {:keys [thr min-inliers top-k max-overlap max-triples seed]
                :or {thr 0.05 min-inliers 8 top-k 4 max-overlap 0.5
                     max-triples 15000 seed 1}}]
  (let [n (count pts)]
    (if (< n min-inliers)
      []
      (let [cu (mapv (fn [[x y]] [(- x cx) (- y cy)]) pts)
            md (/ (reduce + 0.0 (map (fn [[u v]] (Math/hypot u v)) cu)) n)
            s (/ (Math/sqrt 2.0) (max 1e-9 md))
            np (mapv (fn [[u v]] [(* s u) (* s v)]) cu)
            thr2 (* thr thr)
            hyps (reduce (fn [acc [i j k]]
                           (let [abc (central-fit-3 [(nth np i) (nth np j) (nth np k)])]
                             (if (and abc (central-ellipse? abc))
                               (let [conic [(nth abc 0) (nth abc 1) (nth abc 2) 0.0 0.0 -1.0]
                                     inl (filterv #(< (sampson-sq conic (nth np %)) thr2)
                                                  (range n))]
                                 (if (and (>= (count inl) min-inliers)
                                          (not (contains? acc inl)))
                                   (assoc acc inl conic)
                                   acc))
                               acc)))
                         {}
                         (triples n max-triples seed))]
        (->> (distinct-rings hyps top-k max-overlap)
             (mapv (fn [[inl conic]]
                     (vec (sort-by #(sampson-sq conic (nth np %)) inl)))))))))

(defn fit-concentric-ranked
  "fit-inliers-ranked plus the CONCENTRIC SISTERS of everything it found.

   Centre seeds are the centres of every stage-1 hypothesis — JUNK INCLUDED,
   and that is the trick: a junk conic threading the stragglers of three
   interleaved crowns is worthless as a ring but still CENTRED on the cage, so
   it hands the pinned search exactly the point it needs — plus the candidate
   cloud's centroid (marks surround the cage centre from every vantage). For
   each seed, `sisters-about` re-searches in centre-pinned form.

   Returns stage-1 and sister hypotheses together, deduped by inlier overlap,
   largest first, capped at twice `:top-k` — the same shape fit-inliers-ranked
   returns (vectors of point indices), so a caller swaps it in place and its
   own stronger judges (regularity, identity, the physical guards) still
   decide. Extra opts over fit-inliers-ranked: :sister-thr (default 1.25×
   :thr), :max-triples, :center-merge-px (seeds closer than this are one seed,
   default 25)."
  [pts {:keys [thr top-k max-overlap sister-thr max-triples seed center-merge-px]
        :or {thr 0.03 top-k 1 max-overlap 0.5 seed 1 center-merge-px 25.0}
        :as opts}]
  (let [n (count pts)]
    (if (< n 5)
      []
      (let [{:keys [np cx cy s]} (norm-of pts)
            stage1 (ransac-kept np opts)
            unnorm (fn [[x y]] [(+ (/ x s) cx) (+ (/ y s) cy)])
            centroid [(/ (reduce + 0.0 (map first pts)) n)
                      (/ (reduce + 0.0 (map second pts)) n)]
            seeds (reduce (fn [acc c]
                            (if (some (fn [[ax ay]]
                                        (< (Math/hypot (- (nth c 0) ax) (- (nth c 1) ay))
                                           center-merge-px))
                                      acc)
                              acc
                              (conj acc c)))
                          []
                          (concat (keep (fn [[_ conic]]
                                          (some-> (conic-center conic) unnorm))
                                        stage1)
                                  [centroid]))
            stage1-idx (mapv (fn [[inl conic]]
                               (vec (sort-by #(sampson-sq conic (nth np %)) inl)))
                             stage1)
            sisters (into []
                          (mapcat #(sisters-about pts %
                                                  (assoc opts
                                                         :thr (or sister-thr (* 1.25 thr))
                                                         :top-k top-k
                                                         :max-triples (or max-triples 15000))))
                          seeds)
            ;; STAGE-1 HAS PRIORITY: sisters may only ADD rings, never displace
            ;; a free-fit hypothesis. The pinned fit's wider band gathers
            ;; supersets — the true ring plus a straggler — and judged by size
            ;; alone such a superset would win the dedupe and DELETE the clean
            ;; set (measured: the synthetic scene, previously read at 39/39,
            ;; REFUSED on the first draft of this merge). The free fit is the
            ;; unbiased witness; the sisters exist for the rings it missed.
            merged (loop [remaining (sort-by (comp - count) sisters)
                          kept (vec stage1-idx)]
                     (if (or (empty? remaining) (>= (count kept) (* 2 top-k)))
                       kept
                       (let [inl (first remaining)
                             is (set inl)
                             same? (some (fn [k] (let [shared (count (filter is k))]
                                                   (> shared (* max-overlap
                                                                (min (count inl) (count k))))))
                                         kept)]
                         (recur (rest remaining) (if same? kept (conj kept inl))))))]
        merged))))

;; ── the comb: a crown is equally spaced, and contaminants are not ────────────
;;
;; The audit of 2026-08-29 (bench, coverage-report!): the concentric search DOES
;; surface the true rings — foto 7's nine ym discs sat complete inside a
;; hypothesis — but always inside a CONTAMINATED superset, two to six stray
;; points riding within the Sampson band, and the identity solve cannot digest
;; them: it fits a pose to the mixture and lands nowhere. Worse, the strays
;; inflate the max chord, so the size-ratio hint votes for the wrong ring
;; (foto 6: eight true discs inside a 14-point set, hinted :x, never tried :y).
;;
;; What separates crown points from riders is not distance to the ellipse — the
;; riders are ON it — but SPACING: twelve discs sit equally spaced on the
;; circle, and an affine image of a circle keeps them equally spaced in the
;; ellipse's ECCENTRIC ANOMALY (perspective's non-affine residue at this
;; framing is a few degrees, absorbed by the tooth tolerance). So: fit the
;; hypothesis's own ellipse, read each point's anomaly, align an N-tooth comb,
;; and keep what sits on teeth.

(defn- conic-lsq
  "Least-squares conic (f pinned to 1) over ≥5 points, via normal equations —
   fit-conic-5's big sibling. nil when singular."
  [pts]
  (let [rows (mapv (fn [[x y]] [(* x x) (* x y) (* y y) x y]) pts)
        ata (vec (for [i (range 5)]
                   (vec (for [j (range 5)]
                          (reduce + 0.0 (map #(* (nth % i) (nth % j)) rows))))))
        atb (vec (for [i (range 5)]
                   (reduce + 0.0 (map #(- (nth % i)) rows))))]
    (when-let [sol (la/solve ata atb)]
      (conj (vec sol) 1.0))))

(defn- ellipse-params
  "conic → {:c [cx cy] :theta :a :b} (semi-axes, axis angle); nil when not a
   real ellipse."
  [[a b c d e f :as conic]]
  (when-let [[cx cy] (conic-center conic)]
    (let [;; Q at the centre: u^T M u = −Q(c0) on the translated conic
          q0 (+ (* a cx cx) (* b cx cy) (* c cy cy) (* d cx) (* e cy) f)
          tr (+ a c)
          det-root (Math/sqrt (+ (* (- a c) (- a c)) (* b b)))
          l1 (/ (+ tr det-root) 2.0)
          l2 (/ (- tr det-root) 2.0)
          k (- q0)]
      (when (and (pos? (* l1 k)) (pos? (* l2 k)))
        {:c [cx cy]
         :theta (* 0.5 (Math/atan2 b (- a c)))
         :a (Math/sqrt (/ k l1))
         :b (Math/sqrt (/ k l2))}))))

(defn- anomaly
  "The eccentric anomaly of `[x y]` on the ellipse `params` — the angle that is
   EQUALLY SPACED for equally spaced points on the pre-image circle."
  [{:keys [c theta a b]} [x y]]
  (let [ux (- x (nth c 0)) uy (- y (nth c 1))
        ct (Math/cos (- theta)) st (Math/sin (- theta))
        rx (- (* ct ux) (* st uy))
        ry (+ (* st ux) (* ct uy))]
    (Math/atan2 (/ ry (max 1e-9 b)) (/ rx (max 1e-9 a)))))

(defn comb-select
  "The indices in `idxs` (into `pts`) that sit on the teeth of an N-tooth comb
   in eccentric anomaly — the hypothesis stripped of its riders. Falls back to
   `idxs` unchanged when the purified set would drop below `keep-floor` (a
   too-aggressive comb must not delete a ring the identity solve could still
   read) or when no ellipse fits.

   `tooth-frac` is the tolerance as a fraction of one tooth spacing (default
   0.25 — ±7.5° of the 30° at twelve marks, room for the perspective residue
   the affine argument ignores)."
  ([pts idxs n] (comb-select pts idxs n nil))
  ([pts idxs n {:keys [tooth-frac keep-floor] :or {tooth-frac 0.25 keep-floor 8}}]
   (let [pass (fn [fit-idxs]
                ;; comb built on `fit-idxs`' own ellipse, applied to ALL of
                ;; `idxs` — so a refined fit can also RECLAIM a true point the
                ;; polluted first fit had misplaced
                (let [sub (mapv #(nth pts %) fit-idxs)]
                  (when (>= (count sub) 5)
                    (let [{:keys [np cx cy s]} (norm-of sub)
                          params (some-> (conic-lsq np) ellipse-params)]
                      (when params
                        (let [t-of (fn [idx]
                                     (let [[x y] (nth pts idx)]
                                       (anomaly params [(* s (- x cx)) (* s (- y cy))])))
                              ts (mapv t-of idxs)
                              sins (reduce + 0.0 (map #(Math/sin (* n %)) ts))
                              coss (reduce + 0.0 (map #(Math/cos (* n %)) ts))
                              phase (/ (Math/atan2 sins coss) n)
                              spacing (/ (* 2.0 Math/PI) n)
                              on-tooth? (fn [t]
                                          (let [d (mod (- t phase) spacing)
                                                d (min d (- spacing d))]
                                            (< d (* tooth-frac spacing))))]
                          (vec (keep-indexed (fn [k idx] (when (on-tooth? (nth ts k)) idx))
                                             idxs))))))))
         ;; two passes: the first fit is over the MIXTURE and its riders drag
         ;; the ellipse, misreading anomalies near them (measured: a 14-point
         ;; hypothesis with six riders combed down to 5 of its 8 true discs);
         ;; refitting on the first selection and re-combing the FULL set
         ;; classifies against a cleaner curve
         sel1 (pass idxs)
         sel2 (when (and sel1 (>= (count sel1) 5) (< (count sel1) (count idxs)))
                (pass sel1))
         best (or (when (and sel2 (>= (count sel2) keep-floor)) sel2)
                  (when (and sel1 (>= (count sel1) keep-floor)) sel1))]
     (or best idxs))))

(def ^:private long-gap-frac
  "Off-integer tolerance for a gap spanning TWO OR MORE steps, as a fraction of
   one step. The perspective drift of the eccentric anomaly ACCUMULATES with
   the steps a gap crosses (measured on the projected cage, bench framing
   ~640mm: one-step gaps land within ±0.17 of integer while a three-step gap
   reads 2.70), so a single quarter-tooth tolerance either strangles long gaps
   or lets riders through short ones — it must scale with the span. 0.45 is
   the ceiling that keeps rounding itself meaningful (at 0.5 the nearest
   integer is undefined), and an exact half-step rider gap (1.5, 2.5, …) sits
   AT 0.5, so it is flagged at any span. Long gaps lean on the sum check
   below for the rest."
  0.45)

(defn- gap-classify
  "Cyclic integer GAP snapping: `entries` [{:idx :t} …] (t = eccentric
   anomaly) → the on-tooth subset with RELATIVE tooth positions
   [{:idx :t :tooth} …], or nil when it cannot converge above `min-teeth`.

   Gaps, not absolute positions, and it is measured, not taste: perspective's
   residue over the affine argument is a SMOOTH drift δ(t) of each point's
   anomaly, and at a close framing (the cage test view, 320mm from a 176mm
   cage) it grows past a quarter tooth — a global comb phase misfiled two true
   points and promoted a rider. The drift's DIFFERENCES stay small where its
   accumulation does not: consecutive true marks sit a near-integer number of
   steps apart even when neither sits near an absolute tooth, and a rider
   betrays itself by splitting one integer gap into two half-integer ones.

   Greedy: while any gap is off-integer (beyond `tooth-frac` of a step for a
   one-step gap, `long-gap-frac` for a longer span) or zero (two claimants on
   one tooth — the through-plastic double, measured proposing both faces onto
   one pixel), drop the point whose adjacent gaps are jointly worst and
   re-close the cycle. On convergence the rounded gaps must sum to exactly n
   — the angles sum to a full turn, so the roundings must too; when they do
   not and nothing is over tolerance, the most fractional point is the
   suspect and is dropped (a rider's half-steps are where the missing count
   hides). A set that never closes the count is refused, not patched: the
   caller has an enumeration fallback, and a guessed rounding would hand the
   identity a poisoned correspondence dressed as a clean one."
  [entries n tooth-frac min-teeth]
  (let [spacing (/ (* 2.0 Math/PI) n)
        ferr (fn [r] (Math/abs (- r (Math/round r))))]
    (loop [es (vec (sort-by :t entries))]
      (let [k (count es)]
        (when (>= k min-teeth)
          (let [rs (mapv (fn [i]
                           (let [g (- (:t (nth es (mod (inc i) k))) (:t (nth es i)))
                                 g (if (neg? g) (+ g (* 2.0 Math/PI)) g)]
                             (/ g spacing)))
                         (range k))
                gap-bad? (fn [i]
                           (let [r (nth rs i)
                                 m (Math/round r)]
                             (or (zero? m)
                                 (> (ferr r) (if (>= m 2) long-gap-frac tooth-frac)))))
                badness (fn [j]
                          (let [pre (mod (dec j) k)]
                            (+ (ferr (nth rs pre)) (ferr (nth rs j))
                               (if (zero? (Math/round (nth rs j))) 0.5 0.0)
                               (if (zero? (Math/round (nth rs pre))) 0.5 0.0))))
                drop-worst (fn []
                             (let [worst (apply max-key badness (range k))]
                               (vec (concat (subvec es 0 worst)
                                            (subvec es (inc worst) k)))))]
            (cond
              (some gap-bad? (range k))
              (recur (drop-worst))

              (= n (reduce + (map #(Math/round %) rs)))
              (loop [i 0 tooth 0 out []]
                (if (>= i k)
                  out
                  (recur (inc i) (+ tooth (Math/round (nth rs i)))
                         (conj out (assoc (nth es i) :tooth tooth)))))

              :else
              (recur (drop-worst)))))))))

(defn comb-teeth
  "The comb as the IDENTITY's opening move (zero-click lever 1, 2026-08-29
   audit): each index in `idxs` classified onto a TOOTH of the N-tooth comb in
   eccentric anomaly — an integer cyclic position on the crown, relative, the
   rotation being the identity search's to enumerate — or set aside as a
   rider. Returns {:teeth {idx tooth} :riders [idx …]}, or nil when no ellipse
   fits or fewer than `:min-teeth` points survive `gap-classify`.

   Why this exists when comb-select already strips riders: identification was
   still paying C(m,k) to rediscover WHICH crown positions the survivors are —
   and one rider that slipped through poisoned every homography of the search
   (assign-marks must explain EVERY click as a mark; foto 3/6/7 of the bench
   die exactly there). The tooth index answers the subset question outright:
   the assignment search collapses to m rotations × 2 handednesses, and a
   rider is never in it because it never earned a tooth.

   Two passes, same reason as comb-select: the first ellipse is fit on the
   mixture and its riders drag it, so the second refits on the survivors and
   reclassifies the FULL set against the cleaner curve — reclaiming a true
   point the polluted fit had misplaced."
  ([pts idxs n] (comb-teeth pts idxs n nil))
  ([pts idxs n {:keys [tooth-frac min-teeth] :or {tooth-frac 0.25 min-teeth 6}}]
   (let [classify
         (fn [fit-idxs]
           (let [sub (mapv #(nth pts %) fit-idxs)]
             (when (>= (count sub) 5)
               (let [{:keys [np cx cy s]} (norm-of sub)
                     params (some-> (conic-lsq np) ellipse-params)]
                 (when params
                   (let [entries (mapv (fn [idx]
                                         (let [[x y] (nth pts idx)]
                                           {:idx idx
                                            :t (anomaly params [(* s (- x cx))
                                                                (* s (- y cy))])}))
                                       idxs)]
                     (gap-classify entries n tooth-frac min-teeth)))))))
         pass1 (classify idxs)
         pass2 (when (and pass1 (>= (count pass1) 5) (< (count pass1) (count idxs)))
                 (classify (mapv :idx pass1)))
         kept (or pass2 pass1)]
     (when kept
       (let [teeth (into {} (map (juxt :idx :tooth)) kept)]
         {:teeth teeth
          :riders (vec (remove #(contains? teeth %) idxs))})))))
