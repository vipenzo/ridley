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
