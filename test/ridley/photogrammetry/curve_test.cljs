(ns ridley.photogrammetry.curve-test
  "Il PIANO che spiega dei bordi già misurati: quanto di ciascuno serve, e cosa
   dicono i suoi numeri quando le prove non bastano.

   La misura di una CURVA — raggi appaiati fra due foto — è stata tolta il
   2026-08-10 insieme ai suoi test: appaiare punti è ciò che questo canale è nato
   per non fare, e due spigoli dritti non paralleli danno lo stesso piano senza
   appaiare niente."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.curve :as pcurve]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.synth :as synth]))

(defn- k* []
  {:fx (* 4032 (/ 48.0 36.0)) :fy (* 4032 (/ 48.0 36.0))
   :cx 2016.0 :cy 1512.0 :k1 0.0 :k2 0.0})

(defn- fmt [x n] (.toFixed (js/Number. x) n))

(defn- ring-pose [theta-deg]
  (let [a (* theta-deg (/ Math/PI 180.0))]
    (cam/look-at-pose [(* 260.0 (Math/cos a)) (* 260.0 (Math/sin a)) 150.0]
                      [0.0 0.0 20.0] [0.0 0.0 1.0])))

(defn- unit [v] (la/v-scale v (/ 1.0 (la/v-norm v))))

(defn- cross3 [[a b c] [d e f]]
  [(- (* b f) (* c e)) (- (* c d) (* a f)) (- (* a e) (* b d))])

(defn- plane-frame-of
  "An in-plane basis for a plane through `o` with unit normal `n`."
  [o n]
  (let [u (unit (cross3 n (if (> (Math/abs (nth n 2)) 0.9) [1.0 0.0 0.0] [0.0 0.0 1.0])))]
    [o u (cross3 n u)]))

(defn- arc-on-plane
  "Points of an arc of radius `r`, from `from`° to `to`°, lying ON the plane
   (o, n) — the shape of a real feature: a rounded corner, a moulding's outline."
  [o n r from to n-pts]
  (let [[o u v] (plane-frame-of o (unit n))]
    (mapv (fn [i]
            (let [a (* (/ Math/PI 180.0) (+ from (* (- to from) (/ (double i) (dec n-pts)))))]
              (la/v-add o (la/v-add (la/v-scale u (* r (Math/cos a)))
                                    (la/v-scale v (* r (Math/sin a)))))))
          (range n-pts))))

(deftest a-measured-edge-becomes-evidence-whatever-kind-it-is
  (testing "uno spigolo dritto viene campionato lungo sé stesso"
    (let [pts (pcurve/edge-points {:a [0 0 10] :b [30 0 10]})]
      (is (= pcurve/line-samples (count pts)))
      (is (= [0.0 0.0 10.0] (first pts)))
      (is (every? #(< (Math/abs (- 10.0 (nth % 2))) 1e-9) pts) "restano sul loro piano")
      (is (< (Math/abs (- 30.0 (la/v-norm (la/v-sub (peek pts) (first pts))))) 1e-9)
          "e coprono tutto il segmento, capo compreso")))
  (testing "una curva porta i punti che ha"
    (is (= [[1.0 2.0 3.0] [4.0 5.0 6.0]]
           (mapv #(mapv double %) (pcurve/edge-points {:points [[1 2 3] [4 5 6]]})))))
  (testing "tutto il resto non dà niente, invece di indovinare"
    (is (= [] (pcurve/edge-points {:position [0 0 0] :radius 12.0})) "nemmeno un cerchio")
    (is (= [] (pcurve/edge-points {:position [0 0 0]})))
    (is (= [] (pcurve/edge-points nil)))))

(deftest the-plane-follows-its-evidence
  ;; La ragione per cui il piano è diventato una formula: correggi uno spigolo e
  ;; il piano lo segue. Due bordi incrociati sulla stessa faccia la fissano.
  (println "\n=== Il piano segue le prove che nomina ===")
  (let [plane-at (fn [z]
                   (pcurve/plane-from-points
                    (vec (mapcat pcurve/edge-points
                                 [{:a [-15 -10 z] :b [15 -10 z]}
                                  {:a [-15 10 z] :b [15 12 z]}]))
                    {:up-hints [[0.0 1.0 0.0]]}))
        a (plane-at 10.0)
        b (plane-at 25.0)]
    (println (str "  z=10 → " (fmt (nth (:position a) 2) 3)
                  " mm · z=25 → " (fmt (nth (:position b) 2) 3) " mm"))
    (is (< (Math/abs (- 10.0 (nth (:position a) 2))) 1e-6))
    (is (< (Math/abs (- 25.0 (nth (:position b) 2))) 1e-6)
        "spostata la prova, il piano si sposta con lei")
    (is (> (Math/abs (nth (:heading a) 2)) 0.999) "e la normale è quella della faccia"))
  (testing "due bordi PARALLELI e vicini non fissano niente, e il numero lo dice"
    (let [pl (pcurve/plane-from-points
              (vec (mapcat pcurve/edge-points
                           [{:a [0 0 0] :b [0 0 40]}
                            {:a [2 0 0] :b [2 0 40]}]))
              {:up-hints [[0.0 1.0 0.0]]})]
      (is (< (:width-mm pl) pcurve/min-width-mm)
          "2 mm di larghezza stanno sotto la soglia che rifiuta una FILA"))))

(deftest a-third-edge-counts-whole-or-not-at-all
  ;; «Se a plane-from-edges passo più di due segmenti, del terzo prende solo il
  ;; centro?» (Vincenzo, 2026-08-10). No: il fit è robusto, non parziale. Un
  ;; terzo bordo complanare conta tutto; uno che sta oltre la soglia dal piano su
  ;; cui gli altri sono d'accordo viene scartato TUTTO, e il piano non si
  ;; inclina. Non esiste un "ne prende un pezzo" — se non fosse così, un bordo di
  ;; un'altra faccia potrebbe piegare il piano un po', che è il modo peggiore di
  ;; sbagliare.
  (println "\n=== Un terzo bordo: o conta tutto, o non conta ===")
  (let [due [{:a [-15 -10 10] :b [15 -10 10]} {:a [-15 10 10] :b [15 12 10]}]
        fit (fn [es] (pcurve/plane-from-points
                      (vec (mapcat pcurve/edge-points es))
                      {:up-hints [[0.0 1.0 0.0]]}))
        complanare (fit (conj (vec due) {:a [-10 0 10] :b [10 5 10]}))
        fuori (fit (conj (vec due) {:a [-10 0 13] :b [10 5 13]}))]
    (println (str "  complanare: " (:n complanare) " punti tenuti, "
                  (:dropped complanare) " scartati · fuori di 3 mm: "
                  (:n fuori) " tenuti, " (:dropped fuori) " scartati"))
    (is (= 60 (:n complanare)) "tutti e tre i bordi contano")
    (is (= 0 (:dropped complanare)))
    (is (= 20 (:dropped fuori)) "il terzo fuori piano viene scartato INTERO")
    (is (< (Math/abs (- 10.0 (nth (:position fuori) 2))) 1e-6)
        "e il piano resta dov'era, senza farsi inclinare un po'")))

(deftest three-edges-can-disagree-and-the-angle-says-so
  ;; I bordi veri di Vincenzo (2026-08-10, :tip-plane): tre spigoli della stessa
  ;; punta. Il fit li usa TUTTI — nessuno viene scartato, il peggiore sta a 0.7 mm
  ;; dal piano — eppure togliendone uno il piano ruota di nove gradi.
  ;;
  ;; È il motivo per cui la planarità in MILLIMETRI non basta come allarme: 0.7 mm
  ;; su una nuvola larga 8 mm sono 9°, e la soglia assoluta (1 mm) taceva. Il
  ;; numero che conta è di quanto ruota il piano se togli una prova.
  (println "\n=== Tre bordi possono non essere d'accordo ===")
  (let [E {:tip-alto {:a [-18.4881 6.5654 17.2531] :b [-15.6568 -9.2204 17.8076]}
           :tip-sx   {:a [-17.279 -10.8086 10.0274] :b [-17.2255 -10.7144 14.9319]}
           :tip-dx   {:a [-18.9323 8.0703 10.2653] :b [-18.8867 8.0049 15.6393]}}
        fit (fn [ks] (pcurve/plane-from-points
                      (vec (mapcat #(pcurve/edge-points (E %)) ks))
                      {:up-hints [[0.0 0.0 1.0]]}))
        deg (fn [u v] (* (/ 180.0 Math/PI)
                         (Math/acos (min 1.0 (Math/abs (la/v-dot u v))))))
        all (fit [:tip-sx :tip-dx :tip-alto])
        swing (fn [k] (deg (:heading all) (:heading (fit (remove #{k} [:tip-sx :tip-dx :tip-alto])))))]
    (println (str "  tenuti " (:n all) "/" (+ (:n all) (:dropped all))
                  " punti · planarità " (fmt (:flatness-mm all) 3)
                  " mm · larghezza " (fmt (:width-mm all) 2) " mm"))
    (println (str "  senza :tip-sx " (fmt (swing :tip-sx) 2)
                  "° · senza :tip-alto " (fmt (swing :tip-alto) 2)
                  "° · senza :tip-dx " (fmt (swing :tip-dx) 2) "°"))
    (is (zero? (:dropped all)) "nessun bordo viene scartato: contano tutti e tre")
    (is (< (:flatness-mm all) 1.0) "e la planarità in mm sta sotto la soglia d'allarme")
    (is (> (swing :tip-alto) 5.0)
        "eppure togliere il terzo bordo ruota il piano di parecchi gradi")))
