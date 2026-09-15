(ns ridley.photogrammetry.cage-detect-study
  "Bench for the CAGE detector, run against Vincenzo's real photographs.

       npx shadow-cljs compile cage-study && node out/cage-study.js <dir-or-file>…

   Not a test — a measuring instrument. It prints, per photo, how many candidates
   the detector returns and how many of the KNOWN marks it recovered, and writes
   `<out>/cands-<name>.json` so the candidates can be drawn on the photograph and
   looked at (scratchpad `overlay.js`). The handover's rule, paid for three times
   over: draw the points on the image before reasoning about them.

   The truth below was read off the photograph at 4× zoom, not taken on faith from
   the session file, and three of its fourteen picks did not survive that reading:
   28 and 30 sit on the black background several pixels from any mark, and 7 —
   which the handover reprints as ground truth — is 143px from the mark it names,
   on blank band. All three are `proposed?` predictions the session kept, not
   clicks; the handover's own first finding (the clicks are impeccable) still
   stands, because these are not clicks."
  (:require [ridley.photogrammetry.blob-detect :as bd]))

(def fs (js/require "fs"))
(def path (js/require "path"))
(def sharp (js/require "sharp"))

;; ── truth ────────────────────────────────────────────────────────────────────
;; IMG_9014, DISPLAY coordinates (3024×4032, EXIF Orientation 6 applied).
(def truth-9014
  {:outer [[494.0 1005.8] [992.3 578.5] [1631.4 422.0] [2261.8 573.9] [2728.4 1022.3]
           [2648.0 2362.9] [2071.0 2853.0] [304.6 2235.1]]
   :zero  [[693.9 890.8]]
   :inner [[2274.1 2116.5] [1898.9 2047.2] [1021.4 1711.0] [661.1 1491.7]]})

(defn- all-truth [t] (vec (concat (:outer t) (:zero t) (:inner t))))

(defn- rgba
  "Decode a JPEG to a raw RGBA buffer with the EXIF orientation APPLIED — the pixel
   grid every pick, prediction and reprojection in the session lives in."
  [file]
  (let [^js img (sharp file)
        ^js pipe (.raw (.ensureAlpha (.rotate img)))]
    (.toBuffer pipe #js {:resolveWithObject true})))

(defn- sampler [^js res]
  (let [data (.-data res) info (.-info res)
        w (.-width info) h (.-height info)]
    {:data data :w w :h h
     :lum-at (fn [x y]
               (let [xi (Math/round x) yi (Math/round y)]
                 (when (and (>= xi 0) (>= yi 0) (< xi w) (< yi h))
                   (let [o (* 4 (+ xi (* yi w)))]
                     (+ (* 0.299 (aget data o)) (* 0.587 (aget data (+ o 1)))
                        (* 0.114 (aget data (+ o 2))))))))}))

(defn- nearest [cands [u v]]
  (reduce (fn [best {[cu cv] :center}]
            (min best (Math/hypot (- cu u) (- cv v)))) js/Infinity cands))

(defn- fmt [x n] (.toFixed (js/Number. x) n))

(defn- report [name cands truth ms]
  (println (str "  " name ": " (count cands) " candidati · " (fmt ms 0) "ms"))
  (when truth
    (doseq [[label pts] [["corona" (:outer truth)] ["zero" (:zero truth)]
                         ["interni" (:inner truth)]]]
      (let [ds (mapv #(nearest cands %) pts)
            hit (count (filter #(< % 14.0) ds))]
        (println (str "    " label " " hit "/" (count pts)
                      " · distanze " (mapv #(fmt % 1) ds)))))))

(def wide-open
  "cage-opts with every SHAPE filter removed — what the threshold alone produced.
   Running the truth against this says which knob dropped a mark, instead of
   leaving it to be guessed."
  {:min-area 1 :max-area 1e9 :max-aspect 1e9 :min-fill 0.0
   :enclose-frac nil :max-blobs 100000})

(defn- diagnose
  "For every known mark, the nearest RAW component and its statistics — so a miss
   names the filter that dropped it."
  [lum-at [w h] data opts truth]
  (let [raw (bd/detect-blobs lum-at [w h] (merge opts wide-open {:rgba data}))]
    (println (str "    -- componenti grezze: " (count raw)))
    (doseq [[label pts] [["corona" (:outer truth)] ["zero" (:zero truth)]
                         ["interni" (:inner truth)]]
            [i [u v]] (map-indexed vector pts)]
      (let [c (reduce (fn [best {[cu cv] :center :as b}]
                        (let [d (Math/hypot (- cu u) (- cv v))]
                          (if (< d (:d best js/Infinity)) (assoc b :d d) best)))
                      {} raw)]
        (println (str "    " label "#" i " (" (fmt u 0) "," (fmt v 0) ") -> d="
                      (fmt (:d c js/Infinity) 1)
                      " area=" (:area c) " fill=" (fmt (:fill c 0) 2)
                      " r=" (fmt (:radius-px c 0) 1)
                      " halflen=" (fmt (:half-len c 0) 1)
                      " encl@[45,55,65,75]="
                      (let [[cu cv] (:center c [0 0])]
                        (str (mapv #(fmt (bd/enclosed-frac lum-at cu cv
                                                           (max 14 (* 1.7 (:half-len c 0)))
                                                           % 16) 2)
                                   [45.0 55.0 65.0 75.0])))))))))

(defn- jpegs [p]
  (if (.isDirectory (.statSync fs p))
    (->> (.readdirSync fs p) (filter #(re-find #"(?i)\.jpe?g$" %)) sort
         (mapv #(.join path p %)))
    [p]))

(defn ^:export main [& args]
  (let [files (vec (mapcat jpegs (if (seq args) args
                                     ["/Users/vipenzo/Pictures/RidleyScan/Presa"])))
        out-dir (or (aget (.-env js/process) "CAGE_STUDY_OUT") "/tmp")
        opts (if-let [j (aget (.-env js/process) "CAGE_OPTS")]
               (merge bd/cage-opts (js->clj (js/JSON.parse j) :keywordize-keys true))
               bd/cage-opts)]
    (println (str "\n=== detector gabbia · opts " (pr-str opts) " ==="))
    (letfn [(step [queue]
              (when (seq queue)
                (let [file (first queue)
                      base (.basename path file)]
                  (-> (rgba file)
                      (.then (fn [res]
                               (let [{:keys [data w h lum-at]} (sampler res)
                                     t0 (.now js/Date)
                                     cands (bd/detect-blobs lum-at [w h] (assoc opts :rgba data))
                                     ms (- (.now js/Date) t0)]
                                 (report (str base " " w "x" h) cands
                                         (when (re-find #"9014" base) truth-9014) ms)
                                 (when (and (aget (.-env js/process) "CAGE_DIAG")
                                            (re-find #"9014" base))
                                   (diagnose lum-at [w h] data opts truth-9014))
                                 (.writeFileSync
                                  ^js fs (.join path out-dir (str "cands-" base ".json"))
                                  (js/JSON.stringify
                                   (clj->js (mapv (fn [{[u v] :center :keys [area fill enclosed]}]
                                                    {:x (Math/round u) :y (Math/round v)
                                                     :area area :fill (js/Number (fmt fill 2))
                                                     :enclosed (when enclosed (js/Number (fmt enclosed 2)))})
                                                  cands))))
                                 (step (rest queue)))))
                      (.catch (fn [e] (println "  ERRORE" base (str e)) (step (rest queue))))))))]
      (step files))))
