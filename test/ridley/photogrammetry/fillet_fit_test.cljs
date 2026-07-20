(ns ridley.photogrammetry.fillet-fit-test
  "Does fitting the fillet radius actually recover the dimensions?

   EXP 9 showed that a 1.7 mm fillet, fitted with a sharp-box model, biases
   the thin dimension by -0.297 mm and breaks a 0.2 mm gate. Here the radius
   becomes a fitted parameter and the same experiment is re-run.

   Three things have to be true before this counts as a fix, and each gets
   its own test:

   1. it recovers the dimensions AND the radius;
   2. it costs nothing when the part really is sharp (otherwise it is a
      licence to overfit);
   3. it survives a part that does not match the model — the solver fits ONE
      global radius, so a part with different radii on different edges is the
      honest stress case. Generating and fitting with the same function is an
      inverse crime, and this is the cheapest way to break it."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.photogrammetry.box-fit :as bf]
            [ridley.photogrammetry.turntable-fit :as tt]
            [ridley.photogrammetry.synth :as synth]))

(def true-dims [42.2 15.0 80.0])
(def focal-px (* 4032 (/ 48.0 36.0)))
(def distance 280.0)
(def elevation 30.0)
(def n-views 8)
(def n-trials 3)
(def session-thetas [0 -10 -20 -30 -40 -50 -60 -70 -80 -90 -130 180 90 45])
(def true-axis-params {:phi 0.4 :psi 0.03 :a 3.0 :b -2.0})

(defn- intrinsics [] {:fx focal-px :fy focal-px :cx 2016.0 :cy 1512.0 :k1 0.0 :k2 0.0})
(defn- fmt [x n] (.toFixed (js/Number. x) n))
(defn- pad [s w]
  (let [s (str s)] (str s (apply str (repeat (max 0 (- w (count s))) " ")))))
(defn- avg [xs] (/ (reduce + 0.0 xs) (max 1 (count xs))))
(defn- deg->rad [d] (/ (* d Math/PI) 180.0))

(defn- setup []
  (let [axis (tt/axis-from-params (:phi true-axis-params) (:psi true-axis-params)
                                  (:a true-axis-params) (:b true-axis-params))
        base (synth/viewpoint 0.0 elevation distance)
        thetas (mapv deg->rad (take n-views session-thetas))]
    {:axis axis :base base :thetas thetas
     :poses (mapv #(tt/pose-at-angle base axis %) thetas)}))

(defn- run
  "Generate with fillet `r-true` (number or per-edge fn), then fit.
   `fit-fillet?` may be false, true (one shared radius) or :per-axis
   (one radius per edge-direction axis)."
  [{:keys [seed r-true fit-fillet? sigma-px] :or {sigma-px 0.5}}]
  (let [g (synth/rng seed)
        {:keys [base thetas poses]} (setup)
        k (intrinsics)
        ks (vec (repeat (count poses) k))
        obs (synth/observations-rounded true-dims r-true poses ks sigma-px g)
        scale-c {:axis 2 :value (nth true-dims 2) :sigma 0.02}
        init-dims (synth/perturb-dims true-dims 0.20 g)
        init-pose (synth/perturb-pose base 5.0 10.0 g)
        init-axis {:phi (+ (:phi true-axis-params) (* 0.1 (synth/gauss g)))
                   :psi (+ (:psi true-axis-params) (* 0.1 (synth/gauss g)))
                   :a (+ (:a true-axis-params) (* 3.0 (synth/gauss g)))
                   :b (+ (:b true-axis-params) (* 3.0 (synth/gauss g)))}
        res (tt/fit obs thetas k init-dims init-pose init-axis
                    (cond-> {:sigma-px sigma-px :scale-constraint scale-c
                             :angle-prior-sigma-deg 1.0
                             :lm {:max-iterations 250}}
                      (= true fit-fillet?) (assoc :fillet-init 0.5)
                      (= :per-axis fit-fillet?) (assoc :fillet-init [0.5 0.5 0.5])))
        d (:dims res)]
    {:ex (- (nth d 0) (nth true-dims 0))
     :ey (- (nth d 1) (nth true-dims 1))
     :fillet (:fillet res)
     :rms-px (bf/rms-reprojection-px res obs {:sigma-px sigma-px})}))

(defn- trials [opts]
  (mapv #(run (assoc opts :seed (+ 900 (* 41 %)))) (range n-trials)))

;; ---------------------------------------------------------------------------

(deftest exp-11-fillet-as-a-fitted-parameter
  (println "\n=== EXP 11 — fitting the fillet radius ===")
  (println "Session geometry, 8 views. Same data fitted BOTH ways.")
  (println)
  (println (pad "true r" 9) (pad "sharp: errX" 12) (pad "errY" 10)
           (pad "| fillet: errX" 15) (pad "errY" 10) (pad "r fitted" 10) "reproj")
  (let [rows (mapv (fn [r]
                     (let [s (trials {:r-true r :fit-fillet? false})
                           f (trials {:r-true r :fit-fillet? true})]
                       {:r r
                        :sx (avg (map :ex s)) :sy (avg (map :ey s))
                        :fx (avg (map :ex f)) :fy (avg (map :ey f))
                        :rf (avg (map #(first (:fillet %)) f))
                        :px (avg (map :rms-px f))}))
                   [0.0 1.0 1.7 2.5])]
    (doseq [{:keys [r sx sy fx fy rf px]} rows]
      (println (pad (str (fmt r 1) "mm") 9)
               (pad (str (if (pos? sx) "+" "") (fmt sx 3)) 12)
               (pad (str (if (pos? sy) "+" "") (fmt sy 3)) 10)
               (pad (str "| " (if (pos? fx) "+" "") (fmt fx 3)) 15)
               (pad (str (if (pos? fy) "+" "") (fmt fy 3)) 10)
               (pad (str (fmt rf 3) "mm") 10)
               (fmt px 2)))
    (println)
    (println "  The radius is identifiable because its effect is ANGLE-DEPENDENT:")
    (println "  zero looking square at a face, maximal at 45 degrees. That")
    (println "  signature is what separates it from simply a smaller box — and")
    (println "  it is why this needs several viewpoints to work at all.")

    (testing "the 1.7mm case comes back inside the 0.2mm gate"
      (let [r (first (filter #(= 1.7 (:r %)) rows))]
        (is (< (Math/abs (:fy r)) 0.2)
            (str "thin dimension was " (fmt (:sy r) 3)
                 " sharp, now " (fmt (:fy r) 3) " with the fillet fitted"))
        (is (< (Math/abs (:fx r)) 0.2) (str "X error " (fmt (:fx r) 3)))))

    (testing "the fitted radius matches the truth"
      (doseq [{:keys [r rf]} (filter #(pos? (:r %)) rows)]
        (is (< (Math/abs (- rf r)) 0.25)
            (str "true r " r " recovered as " (fmt rf 3)))))

    (testing "fitting the fillet repairs the reprojection residual"
      ;; EXP 9 had this at 10.66 px with the sharp model — the fit could not
      ;; close. If the model is now right it should drop back to the noise.
      (let [r (first (filter #(= 1.7 (:r %)) rows))]
        (is (< (:px r) 1.5)
            (str "reprojection with the fillet fitted: " (fmt (:px r) 2) " px"))))))

(deftest exp-12-cost-on-a-genuinely-sharp-part
  (println "\n=== EXP 12 — does the extra parameter cost anything when r=0? ===")
  (let [s (trials {:r-true 0.0 :fit-fillet? false})
        f (trials {:r-true 0.0 :fit-fillet? true})
        se (max (Math/abs (avg (map :ex s))) (Math/abs (avg (map :ey s))))
        fe (max (Math/abs (avg (map :ex f))) (Math/abs (avg (map :ey f))))
        rf (avg (map #(first (:fillet %)) f))]
    (println (str "  sharp model : worst dim error " (fmt se 4) " mm"))
    (println (str "  fillet model: worst dim error " (fmt fe 4) " mm, r fitted "
                  (fmt rf 4) " mm"))
    (println "  -> the radius collapses toward zero on its own; the extra")
    (println "     freedom is not a licence to overfit.")
    (testing "a sharp part is still solved accurately"
      (is (< fe 0.1) (str "fillet-model error on a sharp part: " (fmt fe 4))))
    (testing "the fitted radius stays near zero"
      (is (< (Math/abs rf) 0.3) (str "spurious radius " (fmt rf 4) " mm")))))

(deftest exp-13-model-mismatch
  (println "\n=== EXP 13 — a part whose edges do NOT share one radius ===")
  (println "Generated with 1.7mm on the vertical edges and 0.8mm elsewhere;")
  (println "the solver still fits a SINGLE global radius. This is the case")
  (println "the inverse crime hides.")
  (let [vertical? #{0 5 8 11}
        r-fn (fn [k] (if (vertical? k) 1.7 0.8))
        s (trials {:r-true r-fn :fit-fillet? false})
        f (trials {:r-true r-fn :fit-fillet? true})
        a (trials {:r-true r-fn :fit-fillet? :per-axis})
        stat (fn [ts] {:x (avg (map :ex ts)) :y (avg (map :ey ts))
                       :px (avg (map :rms-px ts))
                       :worst (max (Math/abs (avg (map :ex ts)))
                                   (Math/abs (avg (map :ey ts))))})
        S (stat s) F (stat f) A (stat a)
        radii (mapv #(avg (map (fn [t] (nth (:fillet t) %)) a)) (range 3))]
    (println (str "  sharp        : errX " (fmt (:x S) 3) "  errY " (fmt (:y S) 3)
                  "  reproj " (fmt (:px S) 2) " px"))
    (println (str "  one radius   : errX " (fmt (:x F) 3) "  errY " (fmt (:y F) 3)
                  "  reproj " (fmt (:px F) 2) " px"))
    (println (str "  per-axis     : errX " (fmt (:x A) 3) "  errY " (fmt (:y A) 3)
                  "  reproj " (fmt (:px A) 2) " px"))
    (println (str "  per-axis radii fitted: [" (fmt (nth radii 0) 3) " "
                  (fmt (nth radii 1) 3) " " (fmt (nth radii 2) 3)
                  "]  (true: 0.8 along X and Y, 1.7 along Z)"))
    (println)
    (println "  One global radius cannot represent two, so it lands on a")
    (println "  compromise: it rescues the thin dimension but can leave the")
    (println "  long one no better. Three radii — one per edge direction —")
    (println "  match how parts are actually made, and the residual says so.")

    (testing "one shared radius rescues the thin dimension"
      (is (< (Math/abs (:y F)) (Math/abs (:y S)))
          (str "thin dim: sharp " (fmt (:y S) 3) " -> one radius " (fmt (:y F) 3))))

    (testing "per-axis radii beat both, on the worst dimension"
      ;; The claim that failed for a single shared radius. Three radii is the
      ;; model that actually matches a part with different rounds per
      ;; direction, so this is where the improvement has to show up.
      (is (< (:worst A) (:worst S))
          (str "per-axis worst " (fmt (:worst A) 3)
               " vs sharp worst " (fmt (:worst S) 3)))
      (is (< (:worst A) (:worst F))
          (str "per-axis worst " (fmt (:worst A) 3)
               " vs one-radius worst " (fmt (:worst F) 3))))

    (testing "per-axis recovers the two distinct radii"
      (is (< (Math/abs (- (nth radii 2) 1.7)) 0.4)
          (str "Z-direction radius should approach 1.7, got " (fmt (nth radii 2) 3)))
      (is (< (Math/abs (- (nth radii 0) 0.8)) 0.4)
          (str "X-direction radius should approach 0.8, got " (fmt (nth radii 0) 3))))))

;; Per-edge radii that NO per-axis model can represent: within each direction
;; group the four edges differ. Fixed table, so the experiment is reproducible.
(def ^:private jittered-radii
  [1.45 0.62 0.95 0.71 1.02 1.93 0.88 1.15 2.05 0.79 1.24 1.61])

(deftest exp-14-genuine-model-mismatch
  (println "\n=== EXP 14 — radii that vary edge BY EDGE (true mismatch) ===")
  (println "EXP 13 became an inverse crime once the model gained per-axis")
  (println "radii: its 1.7/0.8 split is exactly representable. Here every one")
  (println "of the 12 edges has its own radius (0.62 .. 2.05 mm), which")
  (println "neither model can express. This is the realistic case — real parts")
  (println "do not have three tidy radii.")
  (let [r-fn (fn [k] (nth jittered-radii k))
        mean-r (/ (reduce + jittered-radii) 12.0)
        stat (fn [ts] {:x (avg (map :ex ts)) :y (avg (map :ey ts))
                       :px (avg (map :rms-px ts))
                       :worst (max (Math/abs (avg (map :ex ts)))
                                   (Math/abs (avg (map :ey ts))))})
        S (stat (trials {:r-true r-fn :fit-fillet? false}))
        F (stat (trials {:r-true r-fn :fit-fillet? true}))
        A (stat (trials {:r-true r-fn :fit-fillet? :per-axis}))]
    (println (str "  mean true radius: " (fmt mean-r 3) " mm"))
    (println (str "  sharp     : errX " (fmt (:x S) 3) "  errY " (fmt (:y S) 3)
                  "  reproj " (fmt (:px S) 2) " px"))
    (println (str "  one radius: errX " (fmt (:x F) 3) "  errY " (fmt (:y F) 3)
                  "  reproj " (fmt (:px F) 2) " px"))
    (println (str "  per-axis  : errX " (fmt (:x A) 3) "  errY " (fmt (:y A) 3)
                  "  reproj " (fmt (:px A) 2) " px"))
    (println)
    (println "  Neither model can fit this exactly, and the reprojection says")
    (println "  so — it does not fall back to the noise floor. The question is")
    (println "  only whether modelling an APPROXIMATE fillet still beats")
    (println "  pretending the part is knife-edged.")
    (testing "per-axis helps substantially even when the radii do not match it"
      (is (< (:worst A) (* 0.6 (:worst S)))
          (str "per-axis worst " (fmt (:worst A) 3)
               " vs sharp worst " (fmt (:worst S) 3))))

    (testing "ONE shared radius is not reliably an improvement"
      ;; Measured twice now (EXP 13 and here): a single radius rescues the
      ;; thin dimension but drags the long one further out, so the worst-case
      ;; error can get worse than doing nothing. Recorded as a fact rather
      ;; than hidden — it is the reason per-axis is the right granularity.
      (is (< (Math/abs (:y F)) (Math/abs (:y S)))
          "the thin dimension does improve")
      (is (> (:worst F) (* 0.9 (:worst S)))
          (str "one-radius worst " (fmt (:worst F) 3)
               " is not better than sharp " (fmt (:worst S) 3))))

    (testing "an irregular part still misses a 0.2mm gate"
      ;; Do not oversell the fix: modelling fillets removes most of the bias,
      ;; not all of it, when the part is genuinely irregular.
      (is (> (:worst A) 0.2)
          (str "expected residual bias above the gate; got "
               (fmt (:worst A) 3))))
    (testing "the residual still reports that the model is imperfect"
      (is (> (:px A) 0.6)
          (str "reprojection should stay above the noise floor when the part "
               "does not match the model; got " (fmt (:px A) 2) " px")))))
