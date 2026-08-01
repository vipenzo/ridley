(ns ridley.turtle.backward-extrude-test
  "Extruding BACKWARDS — `(extrude shape (f -h))`.

   It is the natural way to grow a solid out of the other side of a plane mark
   (the mark's heading points away from the surface, so the far side is simply
   negative f), and Vincenzo reached for it first. It was rejected by the corner
   realizability guard with a message about a corner that is not there:

     'The turn here needs about 0.00 of straight run … but the segment is only
      -1.00 long'

   `need` is 0 because there is no turn at all; the guard fired purely on the
   SIGN of the travel. These tests fix the meaning: the guard is about a miter
   eating a segment, so what it must compare against is how much run the segment
   HAS — its length — not which way it points. And they check the thing that
   matters more: that a backward extrusion is a well-formed solid, mirrored onto
   the other side, and not an inside-out one."
  (:require [cljs.test :refer [deftest is testing]]
            [ridley.editor.sci-harness :as h]
            [ridley.editor.operations :as ops]
            [ridley.turtle.extrusion :as extrusion]
            [ridley.turtle.shape :as shape]))

(defn- signed-volume
  "6× the signed volume of a closed mesh. Positive with outward-facing winding;
   a solid built inside-out comes back negative."
  [{:keys [vertices faces]}]
  (reduce (fn [acc [a b c]]
            (let [[ax ay az] (nth vertices a)
                  [bx by bz] (nth vertices b)
                  [cx cy cz] (nth vertices c)]
              (+ acc (- (+ (* ax (- (* by cz) (* bz cy)))
                           (* ay (- (* bz cx) (* bx cz)))
                           (* az (- (* bx cy) (* by cx))))))))
          0.0 faces))

(defn- run-range
  "Extent along the SWEEP axis. The turtle's default heading is +X, so that is
   the axis the extrusion travels — z here is a profile axis, not the run."
  [{:keys [vertices]}]
  (let [xs (map #(nth % 0) vertices)]
    [(apply min xs) (apply max xs)]))

(defn- rail [code] (:result (h/eval-dsl code)))
(defn- swept [code] (ops/pure-extrude-path (shape/circle-shape 5 24) (rail code)))

(def ^:private fwd "(path (f 4))")
(def ^:private back "(path (f -4))")

(defn- fmt [x] (.toFixed (js/Number. x) 2))

(deftest the-guard-does-not-fire-where-there-is-no-corner
  (testing "a single straight segment, whichever way it runs"
    (is (nil? (extrusion/validate-corner-realizability!
               [{:dist 4 :shorten-start 0 :shorten-end 0}]))
        "forward: nothing to reject")
    (is (nil? (extrusion/validate-corner-realizability!
               [{:dist -4 :shorten-start 0 :shorten-end 0}]))
        "backward: also nothing to reject — `need` is 0, there is no turn")))

(deftest the-guard-still-catches-a-real-fold
  (testing "a miter longer than the run it has to work with"
    (is (thrown? js/Error (extrusion/validate-corner-realizability!
                           [{:dist 2 :shorten-start 3 :shorten-end 0}]))
        "forward and genuinely folded")
    (is (thrown? js/Error (extrusion/validate-corner-realizability!
                           [{:dist -2 :shorten-start 3 :shorten-end 0}]))
        "and the same run travelled backwards is just as folded — the sign of
         travel must not launder a corner that does not fit")))

(deftest a-backward-extrusion-is-a-proper-solid-on-the-other-side
  (println "\n=== Estrusione all'indietro ===")
  (let [f (swept fwd)
        b (swept back)
        vf (signed-volume f)
        vb (signed-volume b)]
    (println (str "  (f 4):  corsa x " (mapv fmt (run-range f)) "  volume·6 " (fmt vf)))
    (println (str "  (f -4): corsa x " (mapv fmt (run-range b)) "  volume·6 " (fmt vb)))
    (testing "it builds at all"
      (is (seq (:vertices b)) "no exception, and geometry came out"))
    (testing "it is the SAME solid, mirrored onto the other side"
      (is (< (js/Math.abs (- (js/Math.abs vf) (js/Math.abs vb))) 1e-6)
          "same volume")
      (is (= (mapv #(- %) (reverse (run-range f))) (run-range b))
          "and it occupies the mirrored span along the sweep axis"))
    (testing "and it is NOT inside out"
      (is (pos? (* vf vb))
          "both windings agree — a backward sweep that came back negative would
           be a solid turned inside out, which no mesh check would report"))))
