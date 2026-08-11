(ns ridley.photogrammetry.curve
  "Il PIANO che spiega un insieme di punti misurati, con la robustezza che serve
   quando le prove vengono da bordi diversi.

   Era nato attorno alla misura di una CURVA — raggi di due foto che si
   incontrano, appaiati fra loro. Quella metà è stata tolta (2026-08-10):
   appaiare punti fra le foto è precisamente ciò che questo canale è nato per non
   fare, ed è da lì che venivano i fantasmi. Quello che resta è la parte che non
   appaia niente e che regge `plane-from-edges`:

   - `edge-points` — i punti con cui un bordo GIÀ misurato fa da prova;
   - `ransac-plane` + `plane-from-points` — il fit robusto e i numeri che dicono
     se quella zona è davvero piana (`:width-mm`, `:flatness-mm`,
     `:tilt-per-mm-deg`), con gli outlier scartati interi;
   - `subsample` — assottigliare una nuvola senza cambiare quello che dice.

   Pure: millimetri dentro, millimetri fuori."
  (:require [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.triangulate :as tri]))

(def min-width-mm
  "How wide the recovered points must be ACROSS their own plane before that plane
   is worth having.

   This is the one way a curve can fail to give a plane, and it is not obvious
   from looking at the photo: points strung along a nearly straight line lie on
   infinitely many planes, all of them containing that line, and the fit picks
   one of them for reasons that have nothing to do with the object. A gentle arc
   is exactly that case — which is why the answer to 'this curve is too straight'
   is a SECOND curve on the same face, not a better fit.

   4 mm on a part measured from a quarter of a metre away: below it, one
   millimetre of error on a recovered point swings the normal by more than 14°
   (tri/fit-plane-mark reports that swing per millimetre, and it is the number to
   read rather than this threshold)."
  4.0)

(def plane-outlier-mm
  "A recovered point further than this from the plane most of them agree on did
   not come from the curve — it is a ray pairing that happened to pass close."
  1.5)

(defn- cross3 [[a b c] [d e f]]
  [(- (* b f) (* c e)) (- (* c d) (* a f)) (- (* a e) (* b d))])

(defn- unit [v]
  (let [n (la/v-norm v)]
    (when (> n 1e-12) (la/v-scale v (/ 1.0 n)))))

(defn subsample
  "At most `n` points, evenly spaced along the list. The walk lays a station every
   couple of pixels, so a curve arrives hundreds of points long and almost all of
   them say the same thing; thinning is what keeps the fit interactive."
  [pts n]
  (let [c (count pts)]
    (if (<= c n)
      (vec pts)
      (mapv #(nth pts (Math/round (* (/ (double %) (dec n)) (dec c)))) (range n)))))

(defn- prng
  "Deterministic LCG — a RANSAC that answers differently each run is not
   testable, and a measurement that moves when nothing changed is not
   trustworthy."
  [seed]
  (let [s (atom (bit-and (bit-or (int seed) 1) 0x7fffffff))]
    (fn [] (/ (unsigned-bit-shift-right
               (swap! s #(bit-and (+ (* % 1103515245) 12345) 0x7fffffff)) 0)
              2147483648.0))))

(defn- plane-through-3
  "Plane [point normal] through three points, or nil when they are collinear."
  [p q r]
  (when-let [n (unit (cross3 (la/v-sub q p) (la/v-sub r p)))]
    [p n]))

(defn- ransac-plane
  "The plane most of the recovered points agree on, by trying triples — the
   ghosts do not lie on it, because a ghost is the meeting of two rays that each
   touch the true plane at ONE point, and their crossing is somewhere off it.

   Ties on inlier count are broken by tightness. Returns the inlier points."
  [pts tol iters seed]
  (let [n (count pts)
        r (prng seed)
        pick (fn [] (min (dec n) (int (* (r) n))))]
    (if (< n 6)
      (vec pts)
      (loop [i 0, best [], best-score 1e9]
        (if (>= i iters)
          (if (>= (count best) 3) best (vec pts))
          (let [a (pick) b (pick) c (pick)]
            (if (or (= a b) (= b c) (= a c))
              (recur (inc i) best best-score)
              (if-let [[o nn] (plane-through-3 (nth pts a) (nth pts b) (nth pts c))]
                (let [d (fn [p] (Math/abs (la/v-dot (la/v-sub p o) nn)))
                      in (filterv #(< (d %) tol) pts)
                      s (/ (reduce + 0.0 (map d in)) (max 1 (count in)))]
                  (if (or (> (count in) (count best))
                          (and (= (count in) (count best)) (< s best-score)))
                    (recur (inc i) in s)
                    (recur (inc i) best best-score)))
                (recur (inc i) best best-score)))))))))

(def line-samples
  "How many points to spread along a measured straight edge when it stands as
   evidence for a plane. A line is a line however finely it is sampled; twenty is
   enough for the robust fit to chew on without letting one long edge outvote
   everything else."
  20)

(defn edge-points
  "The 3D points a MEASURED EDGE contributes as evidence for a plane, whichever
   of the two kinds it is: a straight edge is sampled along itself, a curve
   already in the source gives its own points.

   Both are honest evidence for a plane and neither needs pairing any point with
   any other — the reason this channel prefers them to clicked points. Anything
   else gives nothing rather than guessing.

   A curve can no longer be MEASURED (2026-08-10), but the points of one measured
   before are still evidence, so they are still read."
  [e]
  (cond
    (not (map? e)) []
    (seq (:points e)) (mapv vec (:points e))
    (and (:a e) (:b e))
    (let [a (mapv double (:a e)) b (mapv double (:b e))
          d (mapv - b a)]
      (mapv (fn [i] (let [t (/ (double i) (dec line-samples))]
                      (mapv (fn [ai di] (+ ai (* di t))) a d)))
            (range line-samples)))
    :else []))

(defn plane-from-points
  "The plane a set of ALREADY MEASURED 3D points lies in, as an ordinary Ridley
   mark — the robust fit and nothing else.

   Split out from plane-from-curves because the points can come from anywhere:
   from a curve's ray meetings, or from a straight edge that was measured by
   intersecting the planes its image lines span. That second source is the
   valuable one (Vincenzo, 2026-08-06: «se accumulassimo semplicemente segmenti
   che restano visualizzati e l'utente può selezionare per dire questi stanno
   sullo stesso piano»), because a straight edge is recovered WITHOUT pairing any
   points at all — so it carries none of the ghost trouble a curve does. Two
   non-parallel edges of a face pin its plane exactly.

   `opts` takes :toward and :up-hints, as triangulate/fit-plane-mark, plus the
   RANSAC knobs. Returns the mark with :points :n :dropped, or nil."
  ([pts] (plane-from-points pts {}))
  ([pts {:keys [toward up-hints ransac-iters seed]
         :or {ransac-iters 200 seed 7}}]
   (let [pts (vec pts)]
     (when (>= (count pts) 3)
       (let [inliers (ransac-plane pts plane-outlier-mm ransac-iters seed)
             inliers (if (>= (count inliers) 3) inliers pts)]
         (when-let [mark (tri/fit-plane-mark inliers {:toward toward :up-hints up-hints})]
           (assoc mark
                  :points inliers
                  :n (count inliers)
                  :dropped (- (count pts) (count inliers)))))))))
