(ns ridley.photogrammetry.note
  "Parsing of a session's NOTE.md — the single source of truth for a session:
   the photo/angle table and the caliper dimensions.

   Deliberately PURE (a string in, a map out): no filesystem, no browser, no
   `js/require`. Both the Node CLI (cli.cljs, via fs) and the in-app editor
   (edit_acquire.cljs, via the desktop file server) parse the SAME NOTE the
   SAME way by calling `parse-note-text` — so a photo that the CLI includes is
   a photo the tool includes, and the two can never disagree about angles.

   Deriving these from the operator's own notes beats hardcoding them or
   inferring angles from filename order. The angle of a shot is a fact only
   the notes record, and a wrong one is INVISIBLE to every check downstream —
   the residual report cannot see it, because a consistent set of edges fitted
   at the wrong angle just moves the camera."
  (:require [clojure.string :as str]))

;; A table row: | IMG_1234.jpeg | 90 | note… |
;; The θ cell is captured loosely (anything up to the next pipe) so a
;; non-numeric entry like `libera` is recognised as an OUT-OF-RING photo
;; rather than silently dropped: the top-down / free shots have no turntable
;; angle, register only via PnP, and MUST be kept out of the turntable model.
;; The filename group (…\.(jpe?g|png)) is what excludes the header row
;; (`| file | θ | note |`) and the `|-----|` separator from ever matching.
(def ^:private row-re
  #"(?i)^\s*\|\s*([\w.\-]+\.(?:jpe?g|png))\s*\|\s*([^|]*?)\s*\|(.*)$")

(def ^:private int-re #"^-?\d+$")

(defn- parse-theta
  "The θ cell → an integer (in degrees), or nil when the cell is empty or a
   word like `libera` (an out-of-ring photo)."
  [cell]
  (let [c (str/trim cell)]
    (when (re-find int-re c)
      (js/parseInt c 10))))

(defn parse-note-text
  "Parse a NOTE.md's text into {:photos [{:image :theta-deg :free? :star?} …]
   :caliper {:x :y :z}}. :theta-deg is nil for an out-of-ring (`libera`) photo,
   and :free? marks it as such. :caliper is nil when the X/Y/Z lines are absent."
  [txt]
  (let [rows (->> (str/split txt #"\n")
                  (keep (fn [l]
                          (when-let [m (re-find row-re l)]
                            (let [theta (parse-theta (nth m 2))]
                              {:image (nth m 1)
                               :theta-deg theta
                               :free? (nil? theta)
                               :star? (boolean (re-find #"★" (nth m 3)))}))))
                  vec)
        dim (fn [k]
              (when-let [m (re-find (re-pattern (str "(?m)^\\s*-\\s*" k "\\s*:\\s*_*([0-9]+(?:\\.[0-9]+)?)"))
                                    txt)]
                (js/parseFloat (nth m 1))))]
    {:photos rows
     :caliper (let [x (dim "X") y (dim "Y") z (dim "Z")]
                (when (and x y z) {:x x :y y :z z}))}))
