(ns ridley.photogrammetry.note-test
  "The shared NOTE.md parser (ridley.photogrammetry.note) — the single source of
   truth both the CLI (init-session) and the in-app editor read a session from.

   The behaviour this guards (2026-07-26): a photo whose θ cell is a word like
   `libera` (an out-of-ring / top-down shot) must be KEPT with :theta-deg nil
   and :free? true — never silently dropped. Dropping it (the old numeric-only
   regex did) meant the top view never entered the session and couldn't be
   PnP-registered; and a nil angle that leaked into the turntable fit would move
   the whole model. The header and separator rows must NOT parse as photos."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.photogrammetry.note :as note]))

(def ^:private note-md
  (str "# Sessione\n"
       "\n"
       "## Quote di calibro\n"
       "- X: _15.3_ mm (escursione 0.1)\n"
       "- Y: __42.1__ mm\n"
       "- Z: __80.05__ mm\n"
       "\n"
       "## Foto\n"
       "| file | θ (gradi) | note |\n"
       "|------|-----------|------|\n"
       "| IMG_8917.jpeg | 0 | |\n"
       "| IMG_8922.jpeg | 180| ★ |\n"
       "| IMG_8924.jpeg | -30 | |\n"
       "| IMG_8926.jpeg | libera| vista dall'alto |\n"))

(deftest parses-caliper
  (is (= {:x 15.3 :y 42.1 :z 80.05} (:caliper (note/parse-note-text note-md)))))

(deftest keeps-every-photo-row-and-no-others
  (let [photos (:photos (note/parse-note-text note-md))]
    (is (= 4 (count photos)) "header + separator rows must not parse as photos")
    (is (= ["IMG_8917.jpeg" "IMG_8922.jpeg" "IMG_8924.jpeg" "IMG_8926.jpeg"]
           (mapv :image photos)))))

(deftest ring-photos-carry-their-angle
  (let [by-image (into {} (map (juxt :image identity)
                               (:photos (note/parse-note-text note-md))))]
    (testing "θ=0 is a real angle, not a missing one"
      (is (= 0 (get-in by-image ["IMG_8917.jpeg" :theta-deg])))
      (is (false? (get-in by-image ["IMG_8917.jpeg" :free?]))))
    (testing "positive and negative angles both parse"
      (is (= 180 (get-in by-image ["IMG_8922.jpeg" :theta-deg])))
      (is (= -30 (get-in by-image ["IMG_8924.jpeg" :theta-deg]))))
    (testing "★ marks a bootstrap photo"
      (is (true? (get-in by-image ["IMG_8922.jpeg" :star?]))))))

(deftest out-of-ring-photo-kept-with-nil-theta
  (let [free (->> (:photos (note/parse-note-text note-md))
                  (filter #(= "IMG_8926.jpeg" (:image %)))
                  first)]
    (is (some? free) "the `libera` photo must be present, not dropped")
    (is (nil? (:theta-deg free)) "an out-of-ring photo has no turntable angle")
    (is (true? (:free? free)))))

(deftest missing-caliper-is-nil-not-an-error
  (is (nil? (:caliper (note/parse-note-text "| IMG_1.jpeg | 0 | |\n")))))
