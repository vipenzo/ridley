(ns ridley.turtle.text-test
  "Tests for text-shape and composite-glyph handling.
   Composite glyphs (i, j, accented letters, umlauts) have multiple
   outer contours that must all be preserved."
  (:require [cljs.test :refer [deftest testing is use-fixtures]]
            [ridley.turtle.text :as text]
            ["opentype.js" :as opentype]
            ["fs" :as fs]))

;; ── Pure helpers (no font needed) ───────────────────────────

(defn- square [cx cy side]
  ;; CCW square (positive area = outer)
  (let [h (/ side 2)]
    [[(- cx h) (- cy h)]
     [(+ cx h) (- cy h)]
     [(+ cx h) (+ cy h)]
     [(- cx h) (+ cy h)]]))

(defn- square-cw [cx cy side]
  ;; CW square (negative area = hole)
  (vec (reverse (square cx cy side))))

(deftest point-in-contour-test
  (testing "ray-casting point-in-polygon"
    (let [poly (square 0 0 10)]
      (is (#'text/point-in-contour? [0 0] poly) "center inside")
      (is (#'text/point-in-contour? [4 4] poly) "near corner inside")
      (is (not (#'text/point-in-contour? [10 0] poly)) "outside on right")
      (is (not (#'text/point-in-contour? [0 -10] poly)) "outside below")
      (is (not (#'text/point-in-contour? [100 100] poly)) "far outside"))))

(deftest assign-holes-simple-test
  (testing "single outer with one hole"
    (let [outer (square 0 0 20)
          hole  (square-cw 0 0 5)
          result (#'text/assign-holes-to-outers [outer] [hole])]
      (is (= 1 (count result)))
      (is (= [hole] (:holes (first result)))))))

(deftest assign-holes-composite-glyph-test
  (testing "two disjoint outers (like lowercase i: stem + tittle)"
    (let [stem  (square 0 0 4)        ; bottom rectangle
          dot   (square 0 10 2)       ; tittle on top
          result (#'text/assign-holes-to-outers [stem dot] [])]
      (is (= 2 (count result)) "Both outers preserved (the stem + the tittle)")
      (is (every? #(empty? (:holes %)) result) "No holes attributed")))

  (testing "two outers + one hole inside one of them (like lowercase ä: a-body with counter, plus 2 dots)"
    (let [body    (square 0 0 20)
          counter (square-cw 0 0 5)   ; hole inside body
          dot     (square 30 0 2)     ; dot far away
          result (#'text/assign-holes-to-outers [body dot] [counter])
          body-entry (first (filter #(= (:outer %) body) result))
          dot-entry  (first (filter #(= (:outer %) dot) result))]
      (is (= 2 (count result)))
      (is (= [counter] (:holes body-entry)) "Hole goes to body, not dot")
      (is (empty? (:holes dot-entry)) "Dot has no holes"))))

;; ── End-to-end: load font from disk and verify text-shape ───

(defonce ^:private font-loaded? (atom false))

(defn- load-roboto! []
  (when-not @font-loaded?
    (let [buf (fs/readFileSync "public/fonts/Roboto-Regular.ttf")
          ;; Node Buffer → ArrayBuffer slice for opentype.parse
          ab  (.-buffer buf)
          ab  (.slice ab (.-byteOffset buf) (+ (.-byteOffset buf) (.-byteLength buf)))
          font (opentype/parse ab)]
      (reset! text/default-font font)
      (reset! font-loaded? true))))

(use-fixtures :once {:before (fn [] (load-roboto!))})

(deftest text-shape-lowercase-i-has-two-shapes
  (testing "lowercase 'i' produces 2 shapes (stem + tittle), not 1"
    (let [shapes (text/text-shape "i" :size 10)]
      (is (= 2 (count shapes))
          (str "Expected 2 shapes for composite 'i', got " (count shapes))))))

(deftest text-shape-uppercase-I-has-one-shape
  (testing "uppercase 'I' produces 1 shape (single contour)"
    (let [shapes (text/text-shape "I" :size 10)]
      (is (= 1 (count shapes))))))

(deftest text-shape-accented-letter
  (testing "accented 'à' produces multiple shapes (letter body + accent)"
    (let [shapes (text/text-shape "à" :size 10)]
      ;; body has 1 outer + 1 counter (hole), accent is separate outer.
      ;; Result: 2 shapes (body-with-hole, accent).
      (is (>= (count shapes) 2)
          (str "Expected >= 2 shapes for 'à', got " (count shapes))))))

(deftest text-shape-umlaut
  (testing "umlaut 'ö' produces multiple shapes (o-body + 2 dots)"
    (let [shapes (text/text-shape "ö" :size 10)]
      (is (>= (count shapes) 3)
          (str "Expected >= 3 shapes for 'ö' (body + 2 dots), got " (count shapes))))))

(deftest text-shape-cinema-stem-preserved
  (testing "the word 'cinema' produces shapes whose total horizontal extent
            covers all letters — regression for the missing 'i' stem."
    (let [shapes (text/text-shape "cinema" :size 10)
          xs (mapcat (fn [s] (map first (:points s))) shapes)
          y-vals (mapcat (fn [s] (map second (:points s))) shapes)]
      ;; 'cinema': c, i (stem+tittle), n, e, m, a → at least 7 contours.
      (is (>= (count shapes) 7)
          (str "Expected >= 7 shapes for 'cinema', got " (count shapes)))
      ;; Some shapes must extend down to baseline (stem of 'i', not just the dot).
      ;; The tittle of 'i' sits high; if only the tittle survived, min-y would be too high.
      (is (some neg? y-vals)
          "Some contour points should be below baseline (i-stem must be present)"))))
