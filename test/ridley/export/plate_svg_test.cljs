(ns ridley.export.plate-svg-test
  "The registration-plate paper sheet: marks->svg must render one dark disc per
   mark centre at its exact (y-flipped) mm position, plus the two orthogonal
   scale bars and a 1:1 mm viewBox — the printable artefact the paper variant
   rests on."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [ridley.export.plate-svg :as plate-svg]))

(defn- count-occ [s sub] (count (re-seq (re-pattern (str/replace sub #"([\\\.\*\+\?\(\)\[\]\{\}\|\^\$])" "\\\\$1")) s)))

(deftest renders-one-dark-disc-per-mark
  (let [centres [[58.0 0.0] [0.0 58.0] [-58.0 0.0] [0.0 -58.0] [52.0 0.0]]
        svg (plate-svg/marks->svg centres {:disc-r 1.25 :plate-r 65 :bar-mm 100})]
    (testing "well-formed SVG with mm units and a matching viewBox"
      (is (str/starts-with? svg "<svg"))
      (is (str/ends-with? svg "</svg>"))
      (is (str/includes? svg "width=\"166.000mm\""))   ; 2*(65+18)
      (is (str/includes? svg "viewBox=\"-83.000 -83.000 166.000 166.000\"")))
    (testing "one dark disc per mark centre, at its exact y-flipped position"
      (is (= (count centres) (count-occ svg "fill=\"#111111\" stroke=\"none\"")))
      (is (str/includes? svg "cx=\"58.000\" cy=\"0.000\" r=\"1.250\""))   ; [58 0]
      (is (str/includes? svg "cx=\"0.000\" cy=\"-58.000\" r=\"1.250\"")))  ; [0 58] → y flipped
    (testing "the two orthogonal scale bars, labelled with their nominal length"
      (is (str/includes? svg "scala X — nominale 100.000 mm"))
      (is (str/includes? svg "scala Y — nominale 100.000 mm")))
    (testing "a light plate-outline circle (align/cut guide), not counted as a mark"
      (is (str/includes? svg "r=\"65.000\" fill=\"none\" stroke=\"#bbbbbb\"")))))

(deftest renders-per-mark-labels-radially-outward
  (testing "labels parallel to disc-centers are drawn (nil entries skipped)"
    (let [centres [[58.0 0.0] [0.0 58.0] [52.0 0.0]]
          svg (plate-svg/marks->svg centres {:disc-r 1.25 :plate-r 65
                                             :labels ["0" "1" nil]})]
      ;; the two non-nil labels appear as <text> glyphs …
      (is (str/includes? svg ">0</text>"))
      (is (str/includes? svg ">1</text>"))
      ;; … and the label for [58 0] sits radially OUTWARD (x > the disc's 58)
      (is (re-find #"<text x=\"6[0-9]\.[0-9]+\" y=\"0.000\"[^>]*>0</text>" svg))))
  (testing "no labels option → no per-mark <text> beyond the two scale-bar labels"
    (let [svg (plate-svg/marks->svg [[58.0 0.0]] {:disc-r 1.25 :plate-r 65})]
      (is (= 2 (count (re-seq #"</text>" svg))) "only the two scale-bar labels"))))

(deftest scales-the-sheet-with-plate-and-bar
  (testing "viewBox and disc radius follow the plate/disc parameters"
    (let [svg (plate-svg/marks->svg [[30.0 0.0]] {:disc-r 2.0 :plate-r 50 :bar-mm 80})]
      (is (str/includes? svg "width=\"136.000mm\""))              ; 2*(50+18)
      (is (str/includes? svg "cx=\"30.000\" cy=\"0.000\" r=\"2.000\""))
      (is (str/includes? svg "nominale 80.000 mm")))))
