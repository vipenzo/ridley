(ns ridley.photogrammetry.curve-test
  "A PLANE from a declared curve (dev-docs/brief-observation-driven-acquire.md,
   gradino 3) — the redirection Vincenzo asked for after using the circle fit on
   his own parts: «la curva da identificare non è mai un cerchio, al massimo un
   segmento … potremmo usare le linee curve per identificare piani».

   What is under test is the claim that makes it worth doing: a plane needs the
   curve to be nothing in particular, so ANY planar curve gives one — while a
   circle fit needs the curve to be a circle and enough of it.

   And the one way it fails, which the tests spend most of their time on because
   it is invisible in the photograph: a nearly straight curve gives points strung
   along a line, and a line lies in infinitely many planes. The fit will happily
   return one of them. What must happen instead is that :width-mm says the set is
   too narrow to have pinned anything — and that declaring a SECOND curve on the
   same face fixes it, which is the whole reason plane-from-curves takes a
   sequence."
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

(defn- declare-curve
  "One curve, declared on the given ring angles: what the walk would have
   collected in each photo. `sigma` displaces the points as a snap would."
  ([pts thetas] (declare-curve pts thetas 0.0 nil))
  ([pts thetas sigma rng]
   (mapv (fn [th]
           (let [pose (ring-pose th) k (k*)]
             {:pose pose :intrinsics k
              :points (vec (keep (fn [p]
                                   (when-let [[u v] (cam/project k pose p)]
                                     [(+ u (if rng (* sigma (synth/gauss rng)) 0.0))
                                      (+ v (if rng (* sigma (synth/gauss rng)) 0.0))]))
                                 pts))}))
         thetas)))

(defn- normal-deg [a b]
  (let [c (Math/abs (max -1.0 (min 1.0 (la/v-dot (unit a) (unit b)))))]
    (* (/ 180.0 Math/PI) (Math/acos c))))

(defn- plane-error-mm
  "Largest distance from the TRUE plane's points to the FITTED plane — the error
   in the units that matter, rather than in the parameters."
  [mark pts]
  (reduce max 0.0
          (map #(Math/abs (la/v-dot (la/v-sub % (:position mark)) (:heading mark))) pts)))

;; ---------------------------------------------------------------------------

(deftest a-curve-gives-the-plane-it-lies-on
  (println "\n=== Curva → PIANO: due click, nessuna corrispondenza ===")
  (doseq [[label o n r from to]
          [["orizzontale" [0.0 0.0 40.0] [0.0 0.0 1.0] 25.0 0 300]
           ["verticale"   [4.0 -6.0 35.0] [1.0 0.0 0.0] 20.0 0 260]
           ["obliqua"     [-5.0 3.0 30.0] [0.4 0.5 0.77] 22.0 20 300]]]
    (let [pts (arc-on-plane o n r from to 60)
          mark (pcurve/plane-from-curves [(declare-curve pts [0 100])]
                                         {:toward [0.0 0.0 400.0]
                                          :up-hints [[0.0 0.0 1.0] [0.0 1.0 0.0]]})]
      (println (str "  " label ": normale " (fmt (normal-deg (:heading mark) n) 3)
                    "° dal vero · i punti veri stanno a " (fmt (plane-error-mm mark pts) 4)
                    " mm dal piano trovato · larghezza " (fmt (:width-mm mark) 1)
                    " mm · punti " (:n mark) " (scartati " (:dropped mark) ")"))
      (testing label
        (is (some? mark) "one curve on two photos must give a plane")
        (is (< (normal-deg (:heading mark) n) 0.5) "the normal to half a degree")
        (is (< (plane-error-mm mark pts) 0.1)
            "and every point of the true curve lies on the plane found")
        (is (> (:width-mm mark) pcurve/min-width-mm) "well conditioned")))))

(deftest the-normal-faces-the-cameras
  (println "\n=== Curva → piano: la normale guarda le camere ===")
  ;; A fitted normal's sign is arbitrary; a mark's is not, or an extrusion built
  ;; on it goes INTO the object half the time.
  (let [pts (arc-on-plane [0.0 0.0 40.0] [0.0 0.0 1.0] 25.0 0 300 60)
        up (pcurve/plane-from-curves [(declare-curve pts [0 100])]
                                     {:toward [0.0 0.0 400.0] :up-hints [[0.0 1.0 0.0]]})
        down (pcurve/plane-from-curves [(declare-curve pts [0 100])]
                                       {:toward [0.0 0.0 -400.0] :up-hints [[0.0 1.0 0.0]]})]
    (println (str "  verso l'alto → heading " (clj->js (mapv #(fmt % 2) (:heading up)))
                  " · verso il basso → " (clj->js (mapv #(fmt % 2) (:heading down)))))
    (is (> (la/v-dot (:heading up) [0.0 0.0 1.0]) 0.99))
    (is (< (la/v-dot (:heading down) [0.0 0.0 1.0]) -0.99))))

(deftest a-nearly-straight-curve-does-not-pin-a-plane-and-says-so
  (println "\n=== Curva quasi dritta: NON definisce un piano ===")
  ;; The failure that is invisible in the photograph. A gentle arc gives points
  ;; along what is almost a line, and a line lies in infinitely many planes — the
  ;; fit returns one of them with no sign of trouble except this number.
  (let [o [0.0 0.0 40.0] n [0.0 0.0 1.0]
        gentle (arc-on-plane o n 200.0 0 20 60)   ; sagitta ~3 mm over 70 mm
        mark (pcurve/plane-from-curves [(declare-curve gentle [0 100])]
                                       {:toward [0.0 0.0 400.0] :up-hints [[0.0 1.0 0.0]]})]
    (println (str "  arco molto aperto → larghezza " (fmt (:width-mm mark) 2)
                  " mm (minimo " pcurve/min-width-mm ") · 1 mm di errore su un punto "
                  "inclina la normale di " (fmt (:tilt-per-mm-deg mark) 1) "°"))
    (is (some? mark) "it still returns a plane — the caller must be the one to refuse")
    (is (< (:width-mm mark) pcurve/min-width-mm)
        "and the width must be the thing that says not to trust it")))

(deftest a-second-curve-on-the-same-face-fixes-it
  (println "\n=== …e la cura è una SECONDA curva sulla stessa faccia ===")
  (let [o [0.0 0.0 40.0] n [0.0 0.0 1.0]
        a (arc-on-plane o n 200.0 0 20 60)
        ;; another gentle arc on the same plane, elsewhere and turned: alone it
        ;; is just as narrow, together they span the face
        b (arc-on-plane [10.0 -30.0 40.0] n 200.0 100 120 60)
        one (pcurve/plane-from-curves [(declare-curve a [0 100])]
                                      {:toward [0.0 0.0 400.0] :up-hints [[0.0 1.0 0.0]]})
        two (pcurve/plane-from-curves [(declare-curve a [0 100]) (declare-curve b [0 100])]
                                      {:toward [0.0 0.0 400.0] :up-hints [[0.0 1.0 0.0]]})]
    (println (str "  una curva → larghezza " (fmt (:width-mm one) 2) " mm, normale a "
                  (fmt (normal-deg (:heading one) n) 3) "° · due curve → larghezza "
                  (fmt (:width-mm two) 1) " mm, normale a "
                  (fmt (normal-deg (:heading two) n) 3) "°"))
    (is (> (:width-mm two) pcurve/min-width-mm) "two curves span the face")
    (is (> (:width-mm two) (* 3 (:width-mm one))) "and it is the SPREAD that grew")
    (is (< (normal-deg (:heading two) n) 0.5) "the plane is right")))

(deftest non-overlapping-stretches-give-a-plane-through-the-cameras-and-it-is-caught
  (println "\n=== Tratti che NON si sovrappongono: il piano falso ma convincente ===")
  ;; The failure found on real photographs (2026-08-06), and the reason
  ;; min-elevation-deg exists. Two photos declared stretches of the same rim that
  ;; did not overlap, so no ray pairing was ever right — and the ghosts are not
  ;; scattered: they pile up on the plane CONTAINING BOTH CAMERA CENTRES. The fit
  ;; came back tidy (0.53 mm of flatness, nothing dropped) and stood vertical
  ;; where the truth is horizontal.
  ;;
  ;; That plane refutes itself: seen edge-on from both cameras, a curve lying in
  ;; it would image as a straight line — and a curve is what was declared.
  (let [o [0.0 0.0 40.0] n [0.0 0.0 1.0]
        a (arc-on-plane o n 25.0 0 90 60)        ; photo 0 sees this stretch…
        b (arc-on-plane o n 25.0 200 290 60)     ; …photo 1 a different one
        mark (pcurve/plane-from-curves
              [[(first (declare-curve a [0])) (first (declare-curve b [100]))]]
              {:toward [0.0 0.0 400.0] :up-hints [[0.0 1.0 0.0]]})]
    (if (nil? mark)
      (println "  → nessun piano (le corrispondenze non reggono)")
      (println (str "  → piano a " (fmt (normal-deg (:heading mark) n) 0)
                    "° dal vero · accordo fra le due foto "
                    (fmt (* 100 (:agreement mark)) 0) "% (minimo "
                    (fmt (* 100 pcurve/min-agreement) 0) "%) · camera più bassa a "
                    (fmt (:min-elevation-deg mark) 1) "°")))
    (is (or (nil? mark)
            (< (:agreement mark) pcurve/min-agreement)
            (< (normal-deg (:heading mark) n) 5.0))
        "either it gets the plane, or the two photos are SEEN not to agree on it")))

(deftest on-the-true-plane-the-two-photos-agree
  (println "\n=== …e sul piano vero le due foto si corroborano ===")
  (let [o [0.0 0.0 40.0] n [0.0 0.0 1.0]
        pts (arc-on-plane o n 25.0 0 300 60)
        mark (pcurve/plane-from-curves [(declare-curve pts [0 100])]
                                       {:toward [0.0 0.0 400.0] :up-hints [[0.0 1.0 0.0]]})]
    (println (str "  accordo " (fmt (* 100 (:agreement mark)) 0) "% · camera più bassa a "
                  (fmt (:min-elevation-deg mark) 1) "° sopra il piano"))
    (is (> (:agreement mark) pcurve/min-agreement)
        "the guard must not reject the honest case it was written to protect")
    (is (> (:min-elevation-deg mark) pcurve/min-elevation-deg))))

(deftest click-noise-degrades-gracefully
  (println "\n=== Curva → piano: rumore di 2px ===")
  (let [rng (synth/rng 31)
        o [0.0 0.0 40.0] n [0.0 0.0 1.0]
        pts (arc-on-plane o n 25.0 0 300 60)
        trials (vec (keep (fn [i]
                            (pcurve/plane-from-curves
                             [(declare-curve pts [(* i 5) (+ 100 (* i 5))] 2.0 rng)]
                             {:toward [0.0 0.0 400.0] :up-hints [[0.0 1.0 0.0]]}))
                          (range 12)))
        degs (mapv #(normal-deg (:heading %) n) trials)
        flat (mapv :flatness-mm trials)]
    (println (str "  " (count trials) " prove: normale media "
                  (fmt (/ (reduce + degs) (count degs)) 2) "°, peggiore "
                  (fmt (reduce max degs) 2) "° · planarità media "
                  (fmt (/ (reduce + flat) (count flat)) 2) " mm"))
    (is (= 12 (count trials)) "noise must not break it")
    (is (< (/ (reduce + degs) (count degs)) 2.0)
        "2px of snap noise is worth a couple of degrees at most")))

(deftest the-recovered-points-are-the-evidence-and-are-clean
  (println "\n=== Curva → piano: la nuvola tenuta è pulita ===")
  (let [o [0.0 0.0 40.0] n [0.0 0.0 1.0]
        pts (arc-on-plane o n 25.0 0 300 60)
        mark (pcurve/plane-from-curves [(declare-curve pts [0 100])]
                                       {:toward [0.0 0.0 400.0] :up-hints [[0.0 1.0 0.0]]})
        off (mapv #(Math/abs (la/v-dot (la/v-sub % (:position mark)) (:heading mark)))
                  (:points mark))]
    (println (str "  " (:n mark) " punti tenuti, " (:dropped mark)
                  " scartati · il peggiore sta a " (fmt (reduce max off) 4) " mm dal piano"))
    (is (> (:n mark) 10) "enough evidence must survive")
    (is (< (reduce max off) 0.5) "and all of it must actually be on the plane")))

;; ---------------------------------------------------------------------------
;; edge-points: un bordo MISURATO come prova per un piano
;;
;; È il pezzo su cui poggia `(plane-from-edges :bordo-alto :bordo-basso)`, cioè
;; il piano-come-formula: si rifà a ogni Run dalle prove che nomina, invece di
;; conservare i numeri di un calcolo fatto una volta.

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
  (testing "un cerchio porta il suo anello, che sta nel suo piano"
    (let [pts (pcurve/edge-points {:position [0 0 5] :heading [0 0 1] :radius 12.0})]
      (is (> (count pts) 8))
      (is (every? #(< (Math/abs (- 5.0 (nth % 2))) 1e-6) pts))
      (is (every? #(< (Math/abs (- 12.0 (Math/hypot (nth % 0) (nth % 1)))) 1e-6) pts))))
  (testing "tutto il resto non dà niente, invece di indovinare"
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
