(ns ridley.editor.attach-simplify-test
  "Condensing an edit-attach command list must not move the object: every
   check runs the original and the condensed list through the real attach and
   compares the vertices — exact merge to 1e-6, the minimal form to its 0.01
   rounding."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.editor.attach-simplify :as simp]
            [ridley.editor.impl :as impl]
            [ridley.geometry.primitives :as primitives]))

;; Asymmetric and off the pivot, so a wrong sign or axis can't hide.
(defn- test-mesh []
  (update (primitives/box-mesh 4 2 3) :vertices
          (fn [vs] (mapv (fn [[x y z]] [(+ x 3) (- y 1) (+ z 2)]) vs))))

(defn- ->path [items]
  {:type :path
   :commands (vec (mapcat (fn [[c v]]
                            (if (= c :scale)
                              (let [[a b d] v]
                                [{:cmd :stretch-f :args [a]}
                                 {:cmd :stretch-rt :args [b]}
                                 {:cmd :stretch-u :args [d]}])
                              [{:cmd c :args [v]}]))
                          items))})

(defn- run [items] (impl/attach-impl (test-mesh) (->path items)))

(defn- max-diff [a b]
  (reduce max 0 (map (fn [p q] (reduce max (map #(Math/abs (- %1 %2)) p q)))
                     (:vertices (run a)) (:vertices (run b)))))

(deftest exact-merge-sums-commuting-runs
  (is (= [[:f 13] [:u 2]]
         (simp/merge-exact [[:f 10] [:rt 5] [:f 3] [:u 2] [:rt -5]])))
  (is (= [[:f 10]] (simp/merge-exact [[:f 5] [:th 10] [:th -10] [:f 5]]))
      "a cancelled turn lets the moves it separated merge")
  (is (= [[:f 0.3]] (simp/merge-exact [[:f 0.1] [:f 0.2]])))
  (is (= [] (simp/merge-exact [[:th 180] [:th 180]])))
  (is (= [[:tv 30] [:th 45]] (simp/merge-exact [[:tv 30] [:th 45]]))
      "different turns do not commute"))

(deftest exact-merge-separates-pose-from-geometry
  (is (= [[:f 15] [:cp-f 5]]
         (simp/merge-exact [[:f 10] [:cp-f 5] [:f 5]])))
  (is (= [[:scale [2 1 1.5]]]
         (simp/merge-exact [[:scale [2 1 1]] [:scale [1 1 1.5]]]))))

(deftest exact-merge-keeps-verbatim
  (is (= ["(f 10)" [:f 5]] (simp/merge-exact ["(f 10)" [:f 5]])))
  (is (= [[:f 5] "(mark :a)" [:f 5]] (simp/merge-exact [[:f 5] "(mark :a)" [:f 5]]))))

(deftest simplify-minimal-pose
  (is (= [[:f 10] [:rt -10]]
         (simp/simplify [[:f 10] [:th 90] [:f 10] [:th -90]])))
  (is (= [[:f 18.66] [:rt -5]]
         (simp/simplify [[:f 10] [:th 30] [:f 10] [:th -30]])))
  (is (= [[:f 10] [:th 30] [:f 10]] (simp/simplify [[:f 10] [:th 30] [:f 10]]))
      "no shorter than the exact merge: the round values stay")
  (is (= [[:tv 30] [:th 45]] (simp/simplify [[:tv 30] [:th 45]]))
      "the minimal form needs three angles here: the exact merge is kept")
  (is (= [[:tv 120]] (simp/simplify [[:tv 120]])))
  (is (= [[:f 15]] (simp/simplify ["(f 10)" [:f 5]]))
      "Simplify reads plain numeric verbatim items")
  (is (= ["(mark :a)"] (simp/simplify ["(mark :a)"])))
  (is (= ["(f 10)" [:f 5]] (simp/simplify ["(f 10)" [:f 5]] {:fold-verbatim? false}))
      "on confirm the pre-existing source is left alone"))

(deftest condensed-lists-place-the-object-identically
  (let [cases [[[:f 10] [:th 30] [:f 10] [:tv -20] [:rt 7] [:tr 45] [:u -3]]
               [[:f 10] [:cp-f 4] [:th 90] [:cp-th 30] [:f 5] [:cp-u 2] [:th -90]]
               [[:cp-tv 25] [:cp-f 6] [:cp-tr -40] [:cp-rt 3] [:scale [1.5 1 1]] [:cp-f 2]]
               [[:tv 90] [:th 30] [:tr 10] [:f 12]]
               [[:th 170] [:tv 100] [:tr -170] [:rt 4]]]]
    (doseq [c cases]
      (testing (pr-str c)
        (is (< (max-diff c (simp/merge-exact c)) 1e-6))
        (is (< (max-diff c (simp/simplify c)) 0.1))))))

(deftest random-lists-place-the-object-identically
  (let [cmds [:f :rt :u :th :tv :tr :cp-f :cp-rt :cp-u :cp-th :cp-tv :cp-tr]
        ;; deterministic LCG, so a failure reproduces
        rng (atom 12345)
        rnd (fn [] (swap! rng #(mod (+ (* % 1103515245) 12345) 2147483648))
              (/ @rng 2147483648))]
    (dotimes [_ 60]
      (let [c (vec (repeatedly (+ 2 (int (* 10 (rnd))))
                               (fn [] [(nth cmds (int (* 12 (rnd))))
                                       (- (int (* 120 (rnd))) 60)])))]
        (is (< (max-diff c (simp/merge-exact c)) 1e-6) (pr-str c))
        (is (< (max-diff c (simp/simplify c)) 0.1) (pr-str c))
        (is (<= (count (simp/simplify c)) 12))))))
