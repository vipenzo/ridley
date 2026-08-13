(ns ridley.photogrammetry.plane-evidence-study-test
  "What is a plane worth, per gesture?

   Vincenzo, 2026-08-13: 'Un segmento e un punto fuori dal segmento dovrebbero
   bastare e si dovrebbe fare più in fretta che disegnare due segmenti, no?'

   Geometrically, yes — a line and a point off it determine a plane exactly. The
   question this study answers is the other half: whether it is FASTER, and what
   it costs in accuracy. Both halves have to be measured rather than argued,
   because the gesture counts are not what they look like and the accuracy
   difference is large.

   THE GESTURE COUNT. A point has no position until it is seen from two
   viewpoints, exactly like an edge — `triangulate` refuses below two
   observations, and so does `triangulate-edge`. So an edge plus a point is two
   strokes plus two clicks: FOUR gestures, which is what two edges cost. The
   saving Vincenzo is reaching for is not there.

   THE ACCURACY, measured rather than guessed — and milder than the argument
   above predicts. On a 40mm face, two photos 90° apart, 2px of hand tremor:

     due spigoli (4 tratti)        mediana 0.97°
     spigolo + punto a  5mm        mediana 3.83°
     spigolo + punto a 15mm        mediana 1.50°
     spigolo + punto a 30mm        mediana 1.10°

   Two edges win everywhere, but by 50% at a sensible distance and by almost
   nothing at 30mm — not by the factor the mechanism suggests. The reason the
   prediction overshot is that a stroke is not as privileged as it sounds: this
   edge images only ~104px long once foreshortened, so its direction comes back
   at 0.93°, not at a tenth of a degree.

   What the study DOES establish is the shape of the penalty: it is governed
   almost entirely by how far the point sits from the edge. At 5mm it is four
   times worse than at 30mm. A gesture offering this would have to say so,
   because a point clicked near the edge is nearly worthless and does not look
   it.

   THE PAIRING, which is the part no number here captures, and the real
   objection. Two photos of an edge need not show the SAME STRETCH of it —
   nothing in `triangulate-edge` ever pairs a point with a point. A clicked point
   must be the same physical feature in both photos, which is the correspondence
   problem this whole channel is built to avoid, and it is hardest exactly where
   a plane is hardest: a featureless surface.

   So the case for the gesture is not economy — there is none — and not accuracy.
   It is a face that shows only ONE usable edge, where the alternative to a
   clicked point is not a second edge but nothing at all."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.curve :as pcurve]
            [ridley.photogrammetry.edge :as edge]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.synth :as synth]
            [ridley.photogrammetry.triangulate :as tri]))

;; ---------------------------------------------------------------------------
;; A face, photographed

(def ^:private img-w 1920)
(def ^:private img-h 1440)
(def ^:private k* (cam/intrinsics-from-fov 39.0 img-w img-h))

(defn- cross3 [[a b c] [d e f]]
  [(- (* b f) (* c e)) (- (* c d) (* a f)) (- (* a e) (* b d))])

(defn- unit [v] (la/v-scale v (/ 1.0 (la/v-norm v))))

;; A face of a part standing on the turntable: 40mm wide, tilted 20° off
;; vertical, its lower edge 20mm above the plate.
(def ^:private face-o [0.0 0.0 35.0])
(def ^:private face-e1 [1.0 0.0 0.0])
(def ^:private face-e2 (unit [0.0 -0.342 0.9397]))
(def ^:private face-n (unit (cross3 face-e1 face-e2)))

(defn- on-face [s t]
  (la/v-add face-o (la/v-add (la/v-scale face-e1 s) (la/v-scale face-e2 t))))

;; edge A — the one edge we assume is always available
(def ^:private edge-a [(on-face -20.0 -15.0) (on-face 20.0 -15.0)])
;; edge B — the second edge, perpendicular to A, for the baseline
(def ^:private edge-b [(on-face -20.0 -15.0) (on-face -20.0 15.0)])

(defn- ring-poses [n elev dist]
  (let [e (* (/ elev 180.0) Math/PI)]
    (mapv (fn [i]
            (let [a (* 2.0 Math/PI (/ (double i) n))]
              (cam/look-at-pose [(* dist (Math/cos e) (Math/cos a))
                                 (* dist (Math/cos e) (Math/sin a))
                                 (* dist (Math/sin e))]
                                [0.0 0.0 30.0] [0.0 0.0 1.0])))
          (range n))))

(def ^:private poses (ring-poses 8 30.0 500.0))

(defn- jitter [rng sigma [u v]]
  [(+ u (* sigma (synth/gauss rng))) (+ v (* sigma (synth/gauss rng)))])

(defn- stroke
  "One photo's declaration of a 3D segment: its two ends projected and jittered.

   This models the gesture WITHOUT edge-snap, which is how Vincenzo is drawing
   them now — the two ends of the drag are the whole measurement. With snap on,
   the line is fitted to the image gradient and is better than this; the study
   therefore understates the edge's advantage rather than flattering it."
  [rng sigma pose [p q]]
  {:pose pose :seg [(jitter rng sigma (cam/project k* pose p))
                    (jitter rng sigma (cam/project k* pose q))]})

(defn- click [rng sigma pose p]
  {:pose pose :px (jitter rng sigma (cam/project k* pose p))})

(defn- angle-deg [a b]
  (let [c (Math/abs (la/v-dot (unit a) (unit b)))]
    (* (/ 180.0 Math/PI) (Math/acos (max -1.0 (min 1.0 c))))))

(defn- pct [xs p]
  (let [s (vec (sort xs))]
    (nth s (min (dec (count s)) (int (* p (count s)))))))

;; ---------------------------------------------------------------------------
;; The two ways to get a plane

(defn- plane-from-two-edges
  "The current gesture: two edges, each declared on two photos, pooled and fitted."
  [rng sigma views]
  (let [ea (edge/triangulate-edge k* (mapv #(stroke rng sigma (nth poses %) edge-a) views))
        eb (edge/triangulate-edge k* (mapv #(stroke rng sigma (nth poses %) edge-b) views))]
    (when (and ea eb)
      (:heading (tri/fit-plane-mark (into (pcurve/edge-points ea) (pcurve/edge-points eb))
                                    {:toward [0.0 -500.0 300.0] :up-hints [[0.0 0.0 1.0]]})))))

(defn- plane-from-edge-and-point
  "The proposed gesture: one edge plus one triangulated point `dist` mm off it.

   The plane is built EXACTLY — normal = dir × (P − point-on-line) — not by
   pooling the point in with the line's samples. Pooling would be wrong and
   quietly so: `edge-points` gives a line twenty samples and the point one, so
   least squares would weight the line twenty to one and return, near enough,
   any plane through the line at all."
  [rng sigma views dist]
  (let [p (on-face 0.0 (+ -15.0 dist))
        ea (edge/triangulate-edge k* (mapv #(stroke rng sigma (nth poses %) edge-a) views))
        tp (tri/triangulate k* (mapv #(click rng sigma (nth poses %) p) views))]
    (when (and ea tp)
      (let [v (la/v-sub (:point tp) (:point ea))
            n (cross3 (:dir ea) v)]
        (when (> (la/v-norm n) 1e-9) (unit n))))))

;; ---------------------------------------------------------------------------

(def ^:private trials 200)
(def ^:private sigma 2.0)
(def ^:private views [0 2])

(deftest an-edge-and-a-point-is-not-fewer-gestures
  (testing "a point needs two photos to have a position at all, exactly like an edge"
    ;; This is the whole arithmetic of Vincenzo's question, and it is in the
    ;; refusals rather than in any comment: neither primitive exists from one
    ;; photograph, so edge+point is 2 strokes + 2 clicks = 4, and two edges is
    ;; 2 + 2 = 4. There is no gesture to save.
    (let [rng (synth/rng 1)
          one-photo-point (tri/triangulate k* [(click rng 0.0 (nth poses 0) (on-face 0.0 0.0))])
          one-photo-edge (edge/triangulate-edge k* [(stroke rng 0.0 (nth poses 0) edge-a)])]
      (is (nil? one-photo-point) "a point clicked on one photo has no position")
      (is (nil? one-photo-edge) "and neither has an edge drawn on one photo"))))

(deftest what-each-kind-of-evidence-is-worth
  (println "\n=== Un piano, per gesto: due spigoli contro spigolo+punto ===")
  (println (str "  " trials " prove, rumore di mano σ=" sigma "px su 1920, due foto a 90°"))
  (let [rng (synth/rng 7)
        two-edges (vec (keep (fn [_] (when-let [n (plane-from-two-edges rng sigma views)]
                                       (angle-deg n face-n)))
                             (range trials)))
        by-dist (into {} (for [d [5.0 15.0 30.0]]
                           [d (vec (keep (fn [_]
                                           (when-let [n (plane-from-edge-and-point rng sigma views d)]
                                             (angle-deg n face-n)))
                                         (range trials)))]))]
    (println (str "  due spigoli (4 tratti)          → mediana "
                  (.toFixed (pct two-edges 0.5) 3) "°, 90° perc. "
                  (.toFixed (pct two-edges 0.9) 3) "°"))
    (doseq [d [5.0 15.0 30.0]]
      (println (str "  spigolo + punto a " (.toFixed d 0) "mm (2+2)    → mediana "
                    (.toFixed (pct (by-dist d) 0.5) 3) "°, 90° perc. "
                    (.toFixed (pct (by-dist d) 0.9) 3) "°")))

    (testing "two edges are the more accurate plane, at every distance"
      ;; I predicted a factor here and asserted it before measuring. The
      ;; measurement said 1.5× at 15mm and 1.13× at 30mm — two edges win, but a
      ;; point placed well is closer to them than the mechanism suggests. What is
      ;; pinned is the direction of the inequality, which is the part that
      ;; decides whether the gesture is an upgrade (it is not) rather than a
      ;; fallback (it is).
      (doseq [d [5.0 15.0 30.0]]
        (is (< (pct two-edges 0.5) (pct (by-dist d) 0.5))
            (str "at " d "mm: two edges " (.toFixed (pct two-edges 0.5) 3)
                 "° vs edge+point " (.toFixed (pct (by-dist d) 0.5) 3) "°"))))

    (testing "and the penalty is governed by the point's distance from the edge"
      ;; This is the number the gesture would have to put in front of the user,
      ;; because it dominates everything else here: the plane's tilt error is the
      ;; point's error divided by how far it sits from the line, so a point
      ;; clicked NEAR the edge is nearly worthless and does not look it.
      (is (< (pct (by-dist 30.0) 0.5) (pct (by-dist 5.0) 0.5))
          "a point far from the edge must beat one close to it")
      (is (> (/ (pct (by-dist 5.0) 0.5) (pct (by-dist 30.0) 0.5)) 2.5)
          (str "and steeply: " (.toFixed (pct (by-dist 5.0) 0.5) 3) "° at 5mm vs "
               (.toFixed (pct (by-dist 30.0) 0.5) 3) "° at 30mm")))))

(deftest an-edge-is-better-measured-than-a-point-and-here-is-why
  (testing "a stroke's ends are far apart in the image; a click is one measurement

   The mechanism behind the study's numbers, isolated. Both primitives get the
   same hand tremor from the same two viewpoints. The edge converts it into an
   angular error of roughly σ/L, L being how long the edge images; the point
   converts it into a position error at full σ, which the plane then divides by
   the point's distance from the line.

   It is also where the prediction was checked and came back smaller than
   claimed. σ/L is only a strong advantage when L is large, and a 40mm edge on a
   part this size images barely a hundred pixels once foreshortened — so the edge
   comes back at ~0.9°, not at a tenth of a degree. The advantage is real and it
   is roughly a factor of two per gesture; it is not the order of magnitude the
   formula suggests when L is imagined generously."
    (let [rng (synth/rng 11)
          seg-px (fn [pose] (let [[p q] edge-a]
                              (la/v-norm (la/v-sub (cam/project k* pose p)
                                                   (cam/project k* pose q)))))
          dir-errs (vec (keep (fn [_]
                                (when-let [e (edge/triangulate-edge
                                              k* (mapv #(stroke rng sigma (nth poses %) edge-a) views))]
                                  (angle-deg (:dir e) (la/v-sub (second edge-a) (first edge-a)))))
                              (range trials)))
          pt-errs (vec (keep (fn [_]
                               (when-let [t (tri/triangulate
                                             k* (mapv #(click rng sigma (nth poses %) (on-face 0.0 0.0)) views))]
                                 (la/v-norm (la/v-sub (:point t) (on-face 0.0 0.0)))))
                             (range trials)))]
      (println (str "\n  lo spigolo images a ~" (.toFixed (seg-px (nth poses 0)) 0)
                    "px → direzione mediana " (.toFixed (pct dir-errs 0.5) 3) "°"))
      (println (str "  il punto cliccato → errore mediano "
                    (.toFixed (pct pt-errs 0.5) 3) "mm, cioè "
                    (.toFixed (* (/ 180.0 Math/PI) (Math/atan (/ (pct pt-errs 0.5) 15.0))) 3)
                    "° di piano se sta a 15mm dallo spigolo"))
      (is (> (seg-px (nth poses 0)) 100.0)
          "the premise: an edge worth drawing images over a hundred pixels long")
      ;; The comparison that matters is per GESTURE, and both are ~1-2°: what one
      ;; stroke buys against what one click buys, once the click's millimetres are
      ;; turned into degrees of plane at a realistic 15mm lever.
      (let [pt-deg (* (/ 180.0 Math/PI) (Math/atan (/ (pct pt-errs 0.5) 15.0)))]
        (is (< (pct dir-errs 0.5) pt-deg)
            (str "a stroke must be worth more than a click: " (.toFixed (pct dir-errs 0.5) 3)
                 "° vs " (.toFixed pt-deg 3) "°"))
        (is (> (/ pt-deg (pct dir-errs 0.5)) 1.5)
            "and by enough that the choice is not a coin toss")))))
