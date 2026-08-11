(ns ridley.photogrammetry.edge-snap-test
  "ONE CLICK on an edge (dev-docs/brief-observation-driven-acquire.md, gradino 3):
   the direction and the extent come from the image, not from a second click.

   Everything here runs against SYNTHETIC photographs — `lum-at` is a function,
   so a test can hand it a smooth step edge, a corner, a flat wall or the rim of
   a disc and know exactly what the right answer is. That is the whole reason
   edge-snap takes a sampler instead of a canvas.

   What is under test is not only 'does it find the edge' but the four ways it
   must REFUSE, because each of them is a wrong answer the user would otherwise
   receive with confidence: a corner has two directions and averaging them gives
   a third belonging to neither; a flat area has none; a stub is shorter than the
   hand that clicked it; and an arc is not a line, however well a chord fits its
   middle."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.photogrammetry.edge-snap :as es]))

(defn- fmt [x n] (.toFixed (js/Number. x) n))

(def ^:private W 800)
(def ^:private H 600)

(defn- bounded
  "Wrap a luminance function so it reads nil off the image, the way a real photo
   does — which is also what stops the walk at the frame's border."
  ([f] (bounded f W H))
  ([f w h]
   (fn [x y]
     (when (and (>= x 0) (>= y 0) (< x w) (< y h))
       (f x y)))))

(defn- noise-at
  "Deterministic per-pixel noise: `lum-at` is called many times at the same
   coordinates, and a random one would make the peak fit chase its own tail."
  [amp x y]
  (let [h (bit-xor (js/Math.imul (int (Math/round x)) 374761393)
                   (js/Math.imul (int (Math/round y)) 668265263))
        h (js/Math.imul (bit-xor h (unsigned-bit-shift-right h 13)) 1274126177)]
    (* amp (- (/ (bit-and h 0xffff) 32768.0) 1.0))))

(defn- step-edge
  "A straight edge through `p0` running along unit `d`: dark one side, bright the
   other, blurred over ~`w` pixels the way a lens blurs a real one."
  ([p0 d] (step-edge p0 d {}))
  ([[px py] [dx dy] {:keys [w noise] :or {w 1.5 noise 0.0}}]
   (bounded (fn [x y]
              (let [s (+ (* (- x px) (- dy)) (* (- y py) dx))]
                (+ 130.0 (* 70.0 (Math/tanh (/ s w)))
                   (if (pos? noise) (noise-at noise x y) 0.0)))))))

(defn- unit [a] [(Math/cos a) (Math/sin a)])

(defn- angle-between-deg
  "Angle between two undirected directions, in [0°,90°] — a line has no arrow,
   so a recovered direction that points the other way is the SAME direction."
  [[ax ay] [bx by]]
  (let [c (Math/abs (max -1.0 (min 1.0 (+ (* ax bx) (* ay by)))))]
    (* (/ 180.0 Math/PI) (Math/acos c))))

(defn- dir-of [{:keys [p1 p2]}]
  (let [dx (- (nth p2 0) (nth p1 0)) dy (- (nth p2 1) (nth p1 1))
        n (Math/hypot dx dy)]
    [(/ dx n) (/ dy n)]))

;; ---------------------------------------------------------------------------

(deftest one-click-finds-the-edges-direction-and-its-whole-length
  (println "\n=== Un click: direzione ed estensione, senza il secondo click ===")
  (doseq [deg [0 17 45 63 90 118 155]]
    (let [d (unit (* deg (/ Math/PI 180.0)))
          lum (step-edge [400 300] d)
          ;; the click lands 3px OFF the edge, as a hand does
          r (es/edge-at-point lum (+ 400 (* 3 (- (second d)))) (+ 300 (* 3 (first d))))]
      (println (str "  bordo a " deg "° → " (if (:ok? r) "trovato" (str "RIFIUTATO " (:reason r)))
                    (when (:ok? r)
                      (str ", errore " (fmt (angle-between-deg d (dir-of r)) 3)
                           "°, lungo " (fmt (:length-px r) 0) " px, scarto "
                           (fmt (:rms r) 3) " px, coerenza " (fmt (:coherence r) 2)))))
      (testing (str deg "°")
        (is (:ok? r) "a clean straight edge must be found from one click")
        (is (< (angle-between-deg d (dir-of r)) 0.5)
            "and its direction recovered to well under a degree")
        (is (< (:rms r) 0.5) "with the walked points sitting on the line")
        (is (> (:length-px r) 300)
            "the walk must run the edge, not stop at the click")))))

(deftest the-walk-beats-what-a-hand-would-trace
  (println "\n=== Un click cammina più lungo di quanto traccereste a mano ===")
  (let [d (unit 0.6)
        lum (step-edge [400 300] d)
        auto (es/edge-at-point lum 400 300)
        ;; the same edge declared by hand, a 120px stroke — a generous one
        hand (es/snap-segment lum
                              [(- 400 (* 60 (first d))) (- 300 (* 60 (second d)))]
                              [(+ 400 (* 60 (first d))) (+ 300 (* 60 (second d)))])]
    (println (str "  un click → " (fmt (:length-px auto) 0) " px · a mano (tratto di 120 px) → 120 px"))
    (is (:ok? auto))
    (is (some? hand))
    (is (> (:length-px auto) 400)
        "a longer baseline is a better-conditioned edge, not just less clicking")))

(deftest noise-does-not-break-it-and-shows-up-in-the-numbers
  (println "\n=== Un click su un bordo rumoroso ===")
  (let [d (unit 0.35)
        lum (step-edge [400 300] d {:noise 12.0})
        r (es/edge-at-point lum 400 300)]
    (println (str "  rumore ±12 livelli → " (if (:ok? r) "trovato" (str "RIFIUTATO " (:reason r)))
                  (when (:ok? r) (str ", errore " (fmt (angle-between-deg d (dir-of r)) 3)
                                      "°, scarto " (fmt (:rms r) 3) " px"))))
    (is (:ok? r) "sensor noise is not a reason to give up")
    (is (< (angle-between-deg d (dir-of r)) 1.0))))

;; ---------------------------------------------------------------------------
;; The four refusals

(deftest a-corner-is-refused-instead-of-averaged
  (println "\n=== Un click su un ANGOLO: due direzioni, nessuna media ===")
  ;; two edges meeting at (400,300): the tensor sees both, and their average is a
  ;; direction belonging to neither — the silent wrong answer this guards against
  (let [lum (bounded (fn [x y]
                       (if (and (< x 400) (< y 300)) 40.0 200.0)))
        r (es/edge-at-point lum 400 300)]
    (println (str "  → " (:reason r) " (coerenza " (fmt (or (:coherence r) 0) 2)
                  ", soglia " es/min-coherence ")"))
    (is (not (:ok? r)) "a corner must not come back as a confident edge")
    (is (= :ambiguous (:reason r)) "and it must say WHY, so the user can click elsewhere")))

(deftest a-flat-area-is-refused
  (println "\n=== Un click sul nulla ===")
  (let [r (es/edge-at-point (bounded (fn [_ _] 128.0)) 400 300)]
    (println (str "  → " (:reason r)))
    (is (= :flat (:reason r)) "no contrast, no edge — and it says so")))

(deftest a-stub-too-short-to-mean-anything-is-refused
  (println "\n=== Un click su un bordo lungo pochi pixel ===")
  ;; A clean horizontal edge in an image only 18px wide: the walk runs out of
  ;; photograph before it has a length worth having. Cutting the edge SHORT
  ;; inside a wide image would not test this — the cut ends are themselves edges,
  ;; and the neighbourhood would (rightly) come back :ambiguous instead.
  (let [lum (bounded (fn [_ y] (+ 130.0 (* 70.0 (Math/tanh (/ (- y 300) 1.5)))))
                     18 600)
        r (es/edge-at-point lum 9 300)]
    (println (str "  → " (:reason r) " (" (fmt (or (:length-px r) 0) 0) " px, minimo 20)"))
    (is (not (:ok? r)))
    (is (= :short (:reason r))
        "a stub's direction is worth less than the hand that clicked it")))

(deftest an-arc-says-it-is-curved-instead-of-fitting-a-chord
  (println "\n=== Un click sul bordo di un disco: è CURVO, non una corda ===")
  (let [r0 120.0
        lum (bounded (fn [x y]
                       (let [d (- (Math/hypot (- x 400) (- y 300)) r0)]
                         (+ 130.0 (* 70.0 (Math/tanh (/ d 1.5)))))))
        r (es/edge-at-point lum (+ 400 r0) 300)]
    (println (str "  cerchio r=" r0 " → " (:reason r)
                  " (scarto " (fmt (or (:rms r) 0) 2) " px su "
                  (fmt (or (:length-px r) 0) 0) " px, soglia "
                  es/max-straight-rms-px " px)"))
    (is (= :curved (:reason r))
        "the walk follows the rim, and the line fit is what notices it bends")))

;; ---------------------------------------------------------------------------

(deftest a-painted-zone-stops-the-walk-before-the-corner
  (println "\n=== Pennarello: 'cerca la linea QUI' ferma il cammino all'angolo ===")
  ;; The failure Vincenzo photographed (2026-08-07): «la cattura della linea ha
  ;; preso troppo: insegue tratti non complanari». The walk stops when CONTRAST
  ;; dies — but a real edge does not die at a corner, it turns into another edge,
  ;; and the walk follows it round onto a face that lies on a different plane.
  ;; Which edge is meant is knowledge the program does not have, so the user
  ;; paints a band and the walk stays in it.
  ;;
  ;; Here: an L, dark in the quarter x<400 ∧ y<300. Its boundary is a vertical
  ;; arm and a horizontal one meeting at (400,300).
  ;;
  ;; NOTE what this test does and does not show. A SHARP synthetic corner already
  ;; stops the free walk on its own (the perpendicular peak jumps and vanishes),
  ;; so the overshoot itself is not reproduced here — the evidence for that is on
  ;; the real photographs, where corners are rounded and the walk sails through
  ;; them: on Vincenzo's clip, 48 places where a walk ran 250+ px past what was
  ;; straight, and one where painting the band turned 692 px of refusal into an
  ;; accepted edge. What IS under test here is the mechanism: the band bounds the
  ;; walk to exactly what was painted, and what is inside it stays measurable.
  (let [dist-to-L (fn [x y]
                    (let [d1 (if (<= y 300) (Math/abs (- x 400)) (Math/hypot (- x 400) (- y 300)))
                          d2 (if (<= x 400) (Math/abs (- y 300)) (Math/hypot (- x 400) (- y 300)))
                          d (min d1 d2)]
                      (if (and (< x 400) (< y 300)) (- d) d)))
        lum (bounded (fn [x y] (+ 130.0 (* 70.0 (Math/tanh (/ (dist-to-L x y) 1.5))))))
        free (es/edge-at-point lum 400 150)
        ;; the user paints the vertical arm only, from y=60 to y=240
        in-zone? (fn [x y]
                   (and (< (Math/abs (- x 400)) 18.0) (<= 60.0 y 240.0)))
        zoned (es/edge-at-point lum 400 150 {:in-zone? in-zone? :zoned? true})]
    (println (str "  libero    → " (if (:ok? free) "dritto" (str "RIFIUTATO " (:reason free)))
                  ", camminato " (fmt (or (:walked-px free) 0) 0)
                  " px, tenuto dritto " (fmt (or (:length-px free) 0) 0) " px"))
    (println (str "  con zona  → " (if (:ok? zoned) "dritto" (str "RIFIUTATO " (:reason zoned)))
                  ", camminato " (fmt (or (:walked-px zoned) 0) 0)
                  " px, tenuto " (fmt (or (:length-px zoned) 0) 0)
                  " px, scarto " (fmt (or (:rms zoned) 0) 2) " px"))
    (is (< (:walked-px zoned) (:walked-px free))
        "the painted band must actually stop the walk short of the free one")
    (is (:ok? zoned) "and what is left inside it is a clean straight edge")
    (is (< (angle-between-deg (dir-of zoned) [0.0 1.0]) 1.0)
        "running down the arm that was painted, not round the corner")
    (is (> (:length-px zoned) 100)
        "with the whole painted arm kept, since (count band) covers 180 px")))

(deftest the-tensor-reads-orientation-and-how-sure-it-is
  (println "\n=== Il tensore di struttura: direzione + quanto è sicuro ===")
  (doseq [[label lum expect-coherent?]
          [["bordo dritto" (step-edge [400 300] (unit 0.4)) true]
           ["angolo" (bounded (fn [x y] (if (and (< x 400) (< y 300)) 40.0 200.0))) false]
           ["piatto" (bounded (fn [_ _] 128.0)) false]]]
    (let [t (es/structure-tensor lum 400 300 12)]
      (println (str "  " label ": coerenza " (fmt (:coherence t) 2)
                    ", forza " (fmt (:strength t) 1) " livelli/px"))
      (testing label
        (is (= expect-coherent? (>= (:coherence t) es/min-coherence))
            "coherence is the one number that separates an edge from a corner")))))
