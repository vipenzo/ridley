(ns ridley.export.plate-sheet-test
  "The printable mark sheet, and the two halves a plate too big for one page is
   cut into. What has to hold is not aesthetic: the sheet is a METROLOGY target
   printed at 1:1, so a mark that goes missing, gets duplicated, or lands on the
   cut is a measurement error that will present itself later as a plausible wrong
   number rather than as a fault."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.export.plate-svg :as psvg]
            [ridley.photogrammetry.plate :as plate]))

(defn- centers-of
  "Disc centres [x y] for a plate, crown first and the zero-index last — the order
   the example feeds the sheet."
  [p]
  (let [a (:anchors p)
        crown (sort (remove #(= :zero %) (keys a)))]
    (conj (mapv #(vec (take 2 (:position (get a %)))) crown)
          (vec (take 2 (:position (:zero a)))))))

(defn- disc-count
  "How many filled mark discs a sheet draws. The plate outline and the alignment
   crosses are circles too, so counting `<circle` would over-count: the marks are
   the ones filled dark."
  [svg]
  (count (re-seq #"fill=\"#111111\" stroke=\"none\"" svg)))

(deftest a-whole-sheet-carries-every-mark
  (let [p (plate/registration-plate)
        cs (centers-of p)
        svg (psvg/marks->svg cs {:disc-r (:mark-disc-r p) :plate-r 65})]
    (is (= (count cs) (disc-count svg)) "ogni mark, e lo zero, sono sul foglio")
    (is (re-find #"width=\"166\.000mm\"" svg) "⌀130 + 2×18 di margine = 166mm")))

(deftest a4-tells-the-truth-about-what-fits
  (println "\n=== foglio: cosa entra in un A4 al 100% ===")
  (doseq [d [130 150 200 300 350]]
    (println (str "  ⌀" d " → foglio " (+ d 36) "mm · A4: "
                  (if (psvg/fits-on-a4? (/ d 2)) "sì" "NO, due metà"))))
  (is (psvg/fits-on-a4? 65) "⌀130 entra")
  (is (not (psvg/fits-on-a4? 100)) "⌀200 non entra")
  (is (not (psvg/fits-on-a4? 175)) "⌀350 non entra"))

(deftest the-two-halves-partition-the-marks
  ;; The property that matters: every disc is on EXACTLY ONE half. A mark lost
  ;; between the sheets is a mark the solver will look for and not find; a mark on
  ;; both is a mark printed twice,
  ;; and either way the plate no longer matches the model it is supposed to be.
  (println "\n=== foglio: le due metà si dividono i mark, nessuno perso o doppio ===")
  (doseq [d [200 300 350]]
    (let [p (plate/registration-plate :d d)
          cs (centers-of p)
          n-crown (dec (count cs))
          [a b] (psvg/marks->svg-halves cs {:disc-r (:mark-disc-r p)
                                            :plate-r (/ d 2)
                                            :n-crown n-crown})
          na (disc-count a) nb (disc-count b)]
      (println (str "  ⌀" d " → " (count cs) " dischi = " na " + " nb))
      (is (= (count cs) (+ na nb))
          (str "⌀" d ": nessun disco perso e nessuno stampato due volte"))
      (is (and (pos? na) (pos? nb)) (str "⌀" d ": entrambe le metà portano qualcosa"))
      (testing "ogni metà si può misurare da sola"
        ;; a printer's scale error is PER PAGE: assuming two sheets came out
        ;; identical is exactly the assumption this channel keeps paying for
        (doseq [[nome svg] [["A" a] ["B" b]]]
          (is (re-find #"scala X" svg) (str "metà " nome " ha la barra X"))
          (is (re-find #"scala Y" svg) (str "metà " nome " ha la barra Y"))))
      (testing "entrambe portano le STESSE croci di allineamento"
        ;; two crosses, a diameter apart, pin translation and rotation together
        (let [crosses-a (count (re-seq #"stroke-width=\"0\.25\"" a))
              crosses-b (count (re-seq #"stroke-width=\"0\.25\"" b))]
          (is (= crosses-a crosses-b) "le croci sono identiche sulle due metà")
          (is (pos? crosses-a) "…e ci sono"))))))

(defn- svg-size
  "[w h] in mm, as the sheet declares itself."
  [svg]
  (let [[_ w] (re-find #"width=\"([0-9.]+)mm\"" svg)
        [_ h] (re-find #"height=\"([0-9.]+)mm\"" svg)]
    [(js/parseFloat w) (js/parseFloat h)]))

(defn- fits? [[w h] [pw ph]]
  (or (and (<= w pw) (<= h ph)) (and (<= w ph) (<= h pw))))

(deftest a-half-actually-fits-a-smaller-page
  ;; The half has to be SMALLER, not merely half-empty. The first version of this
  ;; drew each half on the full-size canvas with only its own discs on it, which
  ;; looks right and buys nothing: the printer still sees the page the whole sheet
  ;; needed. That is why the halves are rotated so the seam is horizontal — half a
  ;; disc cut at an angle has almost the bounding box of the whole disc.
  (println "\n=== foglio: una metà deve stare su una pagina più piccola ===")
  (let [a4 [190.0 277.0]
        a3 [277.0 400.0]]
    (doseq [d [200 250 300 350]]
      (let [p (plate/registration-plate :d d)
            cs (centers-of p)
            [a _] (psvg/marks->svg-halves cs {:disc-r (:mark-disc-r p)
                                              :plate-r (/ d 2)
                                              :n-crown (dec (count cs))})
            sz (svg-size a)
            whole (svg-size (psvg/marks->svg cs {:disc-r (:mark-disc-r p) :plate-r (/ d 2)}))]
        (println (str "  ⌀" d " → intero " (first whole) "×" (second whole)
                      " · metà " (first sz) "×" (second sz)
                      " · A4 " (if (fits? sz a4) "sì" "no")
                      " · A3 " (if (fits? sz a3) "sì" "no")))
        (is (< (second sz) (second whole))
            (str "⌀" d ": la metà deve essere più bassa del foglio intero"))
        (is (fits? sz a3)
            (str "⌀" d ": una metà deve stare almeno su un A3 — è la taglia di un "
                 "giradischi da 12 pollici"))))))

(deftest the-seam-never-cuts-a-disc
  ;; A seam through a mark would split the very thing whose centroid IS the
  ;; measurement. The cut runs between two marks by construction; this checks it
  ;; on the geometry rather than trusting the construction.
  (println "\n=== foglio: la cucitura passa FRA i dischetti ===")
  (doseq [d [130 200 300 350]]
    (let [p (plate/registration-plate :d d)
          a (:anchors p)
          crown (sort (remove #(= :zero %) (keys a)))
          n (count crown)
          ;; the seam angle marks->svg-halves uses: halfway between marks
          theta (* (/ 180.0 n) (/ Math/PI 180.0))
          [sx sy] [(Math/cos theta) (Math/sin theta)]
          disc-r (:mark-disc-r p)
          ;; distance of every disc centre from the seam line through the origin
          dists (mapv (fn [k]
                        (let [[x y _] (:position (get a k))]
                          (Math/abs (- (* x sy) (* y sx)))))
                      (conj (vec crown) :zero))
          closest (reduce min dists)]
      (println (str "  ⌀" d " → il disco più vicino alla cucitura è a "
                    (.toFixed closest 1) "mm (raggio disco " (.toFixed disc-r 2) ")"))
      (is (> closest (* 3.0 disc-r))
          (str "⌀" d ": la cucitura passa larga da ogni dischetto")))))
