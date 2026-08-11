(ns ridley.photogrammetry.edge-test
  "Declared edges (dev-docs/brief-observation-driven-acquire.md, gradino 3): the
   same physical edge drawn on several photos becomes a measured 3D segment.

   The property that carries the whole design is the FIRST test here: the
   stretches declared on the different photos are deliberately DIFFERENT — photo
   A gets the top third, photo B the bottom half, and the clicks are placed at
   unrelated fractions along the image line — and the edge must come back exactly
   the same. That is what 'an edge needs no point identity' means, stated as a
   test rather than as a claim; if it ever regresses, the gesture has silently
   gone back to asking the user for the hardest thing.

   The rest are the guards the gesture rests on: two photos are EXACT and must
   say so, noise must degrade in millimetres and be reported in pixels, an edge
   seen from two nearly identical directions must report its parallax as small
   rather than answer confidently, a segment too short to be a direction must be
   refused, and — with enough photos — the mis-drawn one must be NAMED instead of
   having its error spread over the innocent ones."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.edge :as edge]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.synth :as synth]))

(defn- k* []
  {:fx (* 4032 (/ 48.0 36.0)) :fy (* 4032 (/ 48.0 36.0))
   :cx 2016.0 :cy 1512.0 :k1 0.0 :k2 0.0})

(defn- fmt [x n] (.toFixed (js/Number. x) n))

(defn- ring-pose
  "A camera on a ~260mm ring at 150mm height looking at the object — the
   acquisition geometry the plate sessions actually use."
  [theta-deg]
  (let [a (* theta-deg (/ Math/PI 180.0))]
    (cam/look-at-pose [(* 260.0 (Math/cos a)) (* 260.0 (Math/sin a)) 150.0]
                      [0.0 0.0 20.0] [0.0 0.0 1.0])))

(defn- declare-on
  "What the USER does on one photo: draw two points somewhere along the edge's
   image. `f1`/`f2` are fractions of the visible image segment — deliberately
   arbitrary and different per photo, because any two points along the line are
   an equally valid declaration.

   `sigma` displaces each click PERPENDICULAR to the image line, which is the
   only direction in which an edge click carries information (sliding along the
   edge is free by construction — that is the point of the whole gesture)."
  ([a b theta f1 f2] (declare-on a b theta f1 f2 0.0 nil))
  ([a b theta f1 f2 sigma rng]
   (let [pose (ring-pose theta)
         [ua va] (cam/project (k*) pose a)
         [ub vb] (cam/project (k*) pose b)
         du (- ub ua) dv (- vb va)
         len (Math/hypot du dv)
         nx (/ (- dv) len) ny (/ du len)
         at (fn [f]
              (let [e (if rng (* sigma (synth/gauss rng)) 0.0)]
                [(+ ua (* du f) (* nx e)) (+ va (* dv f) (* ny e))]))]
     {:pose pose :seg [(at f1) (at f2)]})))

(defn- dist [a b] (la/v-norm (la/v-sub a b)))

(defn- point-line-dist
  "Distance from world point `q` to the infinite line (p,d)."
  [{:keys [point dir]} q]
  (let [w (la/v-sub q point)]
    (la/v-norm (la/v-sub w (la/v-scale dir (la/v-dot w dir))))))

;; ---------------------------------------------------------------------------
;; The property the design rests on

(deftest different-stretches-of-the-same-edge-give-the-same-line
  (println "\n=== Spigolo: tratti DIVERSI su foto diverse → stessa retta ===")
  (doseq [[label a b] [["verticale"  [30.0 -10.0 5.0]  [30.0 -10.0 55.0]]
                       ["orizzontale" [-20.0 25.0 40.0] [35.0 25.0 40.0]]
                       ["obliquo"    [-15.0 -15.0 8.0] [20.0 18.0 46.0]]]]
    (let [;; nothing here pairs up points across photos: each photo declares its
          ;; own unrelated stretch, and two of them are drawn BACKWARDS.
          obs [(declare-on a b 0   0.05 0.35)
               (declare-on a b 95  0.90 0.40)
               (declare-on a b 200 0.60 0.15)]
          r (edge/triangulate-edge (k*) obs)]
      (println (str "  " label ": " (count obs) " foto → scarto dai capi "
                    (fmt (point-line-dist r a) 5) " / " (fmt (point-line-dist r b) 5)
                    " mm · lunghezza " (fmt (:length-mm r) 3)
                    " (vera " (fmt (dist a b) 3) ") · riproiezione "
                    (fmt (:rms-px r) 4) " px · piani " (fmt (:angle-deg r) 1) "°"))
      (testing label
        (is (some? r) "must solve")
        (is (< (point-line-dist r a) 1e-3)
            "the true edge's first end lies ON the recovered line")
        (is (< (point-line-dist r b) 1e-3) "and so does the second")
        (is (< (:rms-px r) 1e-3) "exact clicks → zero reprojection")
        (is (> (:angle-deg r) edge/min-plane-angle-deg) "well conditioned")))))

(deftest two-photos-are-exact-and-say-so
  (println "\n=== Spigolo: due foto bastano, e non provano niente ===")
  (let [a [28.0 -6.0 4.0] b [28.0 -6.0 50.0]
        r (edge/triangulate-edge (k*) [(declare-on a b 0 0.1 0.9)
                                       (declare-on a b 110 0.2 0.7)])]
    (println (str "  2 foto → scarto capi " (fmt (point-line-dist r a) 5) " mm"
                  " · esatto? " (:exact? r) " · rms " (fmt (:rms-px r) 5) " px"))
    (is (< (point-line-dist r a) 1e-3) "two planes meet in the edge, in closed form")
    (is (:exact? r) "and the answer must declare that its zero residual is free")
    (is (nil? (:worst-obs r)) "nobody can be blamed with two views")))

(deftest click-noise-degrades-gracefully-and-is-reported
  (println "\n=== Spigolo: rumore di 2px sui click → errore mm + residuo px ===")
  (let [rng (synth/rng 41)
        a [30.0 -10.0 5.0] b [30.0 -10.0 55.0]
        trials (vec (for [_ (range 40)]
                      (edge/triangulate-edge
                       (k*) [(declare-on a b 0   0.05 0.45 2.0 rng)
                             (declare-on a b 95  0.90 0.30 2.0 rng)
                             (declare-on a b 200 0.60 0.10 2.0 rng)])))
        errs (mapv #(max (point-line-dist % a) (point-line-dist % b)) trials)
        mean (/ (reduce + errs) (count errs))
        worst (reduce max errs)
        rms (/ (reduce + (map :rms-px trials)) (count trials))]
    (println (str "  40 prove: errore medio " (fmt mean 3) " mm, peggiore "
                  (fmt worst 3) " mm · residuo medio " (fmt rms 2) " px"))
    (is (every? some? trials) "noise must not break the solve")
    (is (< mean 1.5) "2px of hand jitter is worth well under a millimetre here")
    (is (pos? rms) "and the residual must SHOW the noise instead of hiding it")))

(deftest a-nearly-degenerate-pair-reports-its-parallax
  (println "\n=== Spigolo: due viste quasi uguali → parallasse piccola ===")
  (let [a [30.0 -10.0 5.0] b [30.0 -10.0 55.0]
        near (edge/triangulate-edge (k*) [(declare-on a b 0 0.1 0.9)
                                          (declare-on a b 3 0.1 0.9)])
        far (edge/triangulate-edge (k*) [(declare-on a b 0 0.1 0.9)
                                         (declare-on a b 90 0.1 0.9)])]
    (println (str "  3° di distanza fra gli scatti → piani a " (fmt (:angle-deg near) 2)
                  "° · 90° → piani a " (fmt (:angle-deg far) 1) "°"))
    (is (< (:angle-deg near) edge/min-plane-angle-deg)
        "two adjacent turntable shots must be REPORTED as a degenerate pair")
    (is (> (:angle-deg far) edge/min-plane-angle-deg)
        "and a wide pair as a good one")))

(deftest the-angle-is-the-turn-around-the-edge
  (println "\n=== Spigolo: la parallasse è il giro INTORNO allo spigolo ===")
  ;; What :angle-deg measures, checked against an independent computation rather
  ;; than asserted in a docstring. Every interpretation plane's normal is ⊥ to the
  ;; edge, so the angle between two normals is the angle between the two cameras
  ;; PROJECTED onto the plane across the edge — FOLDED into [0°,90°], since a
  ;; camera on the far side of the edge lies in the same plane. The 'obliquo' row
  ;; is the one that shows the fold, and with it the fact that matters: at half a
  ;; turn the planes coincide again and the edge is unmeasurable, so opposite
  ;; shots are as blind as adjacent ones.
  (doseq [[label a b thetas]
          [["verticale"   [30.0 -10.0 5.0]  [30.0 -10.0 55.0]  [0 70]]
           ["orizzontale" [-20.0 25.0 40.0] [35.0 25.0 40.0]   [20 150]]
           ["obliquo"     [-15.0 -15.0 8.0] [20.0 18.0 46.0]   [40 260]]]]
    (let [r (edge/triangulate-edge (k*) (mapv #(declare-on a b % 0.1 0.9) thetas))
          d (la/v-scale (la/v-sub b a) (/ 1.0 (dist a b)))
          across (fn [theta]
                   (let [c (cam/camera-center (ring-pose theta))
                         w (la/v-sub c a)]
                     (la/v-sub w (la/v-scale d (la/v-dot w d)))))
          [v1 v2] (mapv across thetas)
          turn (* (/ 180.0 Math/PI)
                  (Math/acos (max -1.0 (min 1.0 (/ (la/v-dot v1 v2)
                                                   (* (la/v-norm v1) (la/v-norm v2)))))))
          want (min turn (- 180.0 turn))]
      (println (str "  " label ", scatti a " (clj->js thetas) "° → :angle-deg "
                    (fmt (:angle-deg r) 2) "°, giro intorno allo spigolo "
                    (fmt turn 2) "° (ripiegato " (fmt want 2) "°)"))
      (testing label
        (is (< (Math/abs (- (:angle-deg r) want)) 0.01)
            "the reported parallax IS the turn the cameras make around the edge")))))

(deftest a-segment-too-short-to-be-a-direction-is-refused
  (println "\n=== Spigolo: due click quasi sovrapposti → rifiutato ===")
  (let [a [30.0 -10.0 5.0] b [30.0 -10.0 55.0]
        tiny (update (declare-on a b 0 0.50 0.501) :seg identity)
        r (edge/triangulate-edge (k*) [tiny (declare-on a b 95 0.1 0.9)])]
    (println (str "  segmento di " (fmt (edge/segment-length-px (:seg tiny)) 1)
                  " px (minimo " edge/min-segment-px ") → " (pr-str r)))
    (is (nil? r) "a click's worth of jitter is not a declared line")))

(deftest the-mis-drawn-photo-is-named
  (println "\n=== Spigolo: chi ha sbagliato viene NOMINATO ===")
  (let [a [30.0 -10.0 5.0] b [30.0 -10.0 55.0]
        good (mapv #(declare-on a b % 0.1 0.9) [0 60 130 200 260])
        ;; photo 2's line is drawn along something else — a neighbouring edge, a
        ;; shadow — which is exactly how this goes wrong in practice.
        bad (assoc good 2 (let [{:keys [pose seg]} (nth good 2)
                                [[u1 v1] [u2 v2]] seg
                                mx (* 0.5 (+ u1 u2)) my (* 0.5 (+ v1 v2))
                                th (* 6.0 (/ Math/PI 180.0))
                                rot (fn [[u v]]
                                      (let [du (- u mx) dv (- v my)]
                                        [(+ mx (- (* du (Math/cos th)) (* dv (Math/sin th))))
                                         (+ my (* du (Math/sin th)) (* dv (Math/cos th)))]))]
                            {:pose pose :seg (mapv rot seg)}))
        r (edge/triangulate-edge (k*) bad)]
    (println (str "  5 foto, la n.2 ruotata di 6° → rms " (fmt (:rms-px r) 1)
                  " px, colpevole indicato: " (:worst-obs r)))
    (println (str "    residuo per foto: "
                  (clj->js (mapv #(fmt (:rms-px %) 1) (:per-obs r)))))
    (is (= 2 (:worst-obs r))
        "leave-one-out must name the photo that was drawn wrong")))

;; ---------------------------------------------------------------------------
;; What comes out the other end

(deftest the-declared-stretch-becomes-the-segment
  (println "\n=== Spigolo: i capi sono l'unione di quello che le foto hanno visto ===")
  (let [a [30.0 -10.0 5.0] b [30.0 -10.0 55.0]
        ;; nobody declares the whole edge: 0.1→0.5 on one photo, 0.4→0.85 on the
        ;; other. The union is 0.1→0.85 of a 50mm edge = 37.5mm, and the ends sit
        ;; where those fractions fall.
        r (edge/triangulate-edge (k*) [(declare-on a b 0 0.10 0.50)
                                       (declare-on a b 95 0.85 0.40)])
        want-a (la/v-add a (la/v-scale (la/v-sub b a) 0.10))
        want-b (la/v-add a (la/v-scale (la/v-sub b a) 0.85))]
    (println (str "  visto 0.10→0.50 e 0.40→0.85 → lunghezza " (fmt (:length-mm r) 2)
                  " mm (attesa 37.50) · capi a " (fmt (dist (:a r) want-a) 3)
                  " / " (fmt (dist (:b r) want-b) 3) " mm dai punti attesi"))
    (is (< (Math/abs (- (:length-mm r) 37.5)) 0.5)
        "the segment spans what was declared, not the infinite line")
    (is (< (dist (:a r) want-a) 0.5) "its first end is the outermost click one way")
    (is (< (dist (:b r) want-b) 0.5) "and its second end the outermost click the other")
    (is (every? #(= 2 (count (:span %))) (:per-obs r))
        "every photo reports WHICH stretch it saw")))

(deftest the-edge-runs-the-way-it-was-first-drawn
  (println "\n=== Spigolo: il verso è quello del primo tratto disegnato ===")
  ;; The intersection of two planes has no intrinsic direction, so without this
  ;; the emitted mark's heading would flip with the accident of photo order — and
  ;; the (f …) written against it would run off the object half the time.
  (let [a [30.0 -10.0 5.0] b [30.0 -10.0 55.0]
        along (la/v-scale (la/v-sub b a) (/ 1.0 (dist a b)))
        at (fn [f] (la/v-add a (la/v-scale (la/v-sub b a) f)))
        up (edge/triangulate-edge (k*) [(declare-on a b 0 0.1 0.9)     ; drawn a→b
                                        (declare-on a b 95 0.8 0.2)])
        down (edge/triangulate-edge (k*) [(declare-on a b 0 0.9 0.1)   ; drawn b→a
                                          (declare-on a b 95 0.2 0.8)])]
    (println (str "  disegnato dal basso → heading·(b−a) " (fmt (la/v-dot (:dir up) along) 4)
                  ", parte a " (fmt (dist (:a up) (at 0.1)) 3) " mm dal 10% dello spigolo"
                  " · disegnato dall'alto → " (fmt (la/v-dot (:dir down) along) 4)
                  ", parte a " (fmt (dist (:a down) (at 0.9)) 3) " mm dal 90%"))
    (is (> (la/v-dot (:dir up) along) 0.99) "drawn from a, the edge heads toward b")
    (is (< (la/v-dot (:dir down) along) -0.99) "drawn from b, it heads back toward a")
    ;; and the origin is the START of the run, not whichever end the cross
    ;; product happened to pick: the outermost click in the heading's own sense.
    (is (< (dist (:a up) (at 0.1)) 0.5) "its first end is where the run begins")
    (is (< (dist (:a down) (at 0.9)) 0.5) "and it begins at the other end when reversed")))

(deftest the-mark-runs-along-the-edge
  (println "\n=== Spigolo: il mark cammina lungo lo spigolo ===")
  (let [a [30.0 -10.0 5.0] b [30.0 -10.0 55.0]
        m (edge/edge-mark a b [[0.0 0.0 1.0] [0.0 1.0 0.0]])
        travelled (la/v-add (:position m) (la/v-scale (:heading m) (dist a b)))]
    (println (str "  posizione " (clj->js (mapv #(fmt % 1) (:position m)))
                  " heading " (clj->js (mapv #(fmt % 3) (:heading m)))
                  " up " (clj->js (mapv #(fmt % 3) (:up m)))))
    (is (< (dist (:position m) a) 1e-9) "the mark starts at the edge's first end")
    (is (< (dist travelled b) 1e-6)
        "(f length) from the mark lands exactly on the other end")
    (is (< (Math/abs (la/v-dot (:heading m) (:up m))) 1e-9) "up ⊥ heading")
    ;; the first hint is the edge's own direction here, so it must be SKIPPED as
    ;; noise rather than used to build a degenerate frame
    (is (< (Math/abs (- 1.0 (Math/abs (la/v-dot (:up m) [0.0 1.0 0.0])))) 1e-6)
        "a hint parallel to the edge is skipped in favour of the next one")))
