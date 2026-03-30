(ns ridley.io.svg
  "SVG import for Ridley — extracts 2D shapes from SVG path elements.
   Converts SVG path commands (M, L, C, Z) to Ridley shape format."
  (:require [clojure.xml :as xml]
            [clojure.string :as str]))

;; ── SVG Path Parser ─────────────────────────────────────────────

(defn- tokenize-path
  "Split SVG path d attribute into tokens (commands and numbers)."
  [d]
  (re-seq #"[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?" d))

(defn- parse-numbers
  "Parse consecutive number tokens from a token sequence."
  [tokens n]
  (let [nums (take n tokens)]
    [(mapv #(Double/parseDouble %) nums)
     (drop n tokens)]))

(defn- parse-path-commands
  "Parse SVG path d string into a sequence of absolute [x y] points.
   Supports M, L, H, V, C (cubic bezier → sampled), Z."
  [d]
  (loop [tokens (tokenize-path d)
         points []
         cx 0.0 cy 0.0    ;; current point
         sx 0.0 sy 0.0]   ;; start of subpath
    (if (empty? tokens)
      points
      (let [cmd (first tokens)
            rest-tokens (rest tokens)]
        (case cmd
          ;; MoveTo
          "M" (let [[nums rest-t] (parse-numbers rest-tokens 2)
                    x (nth nums 0) y (nth nums 1)]
                (recur rest-t (conj points [x y]) x y x y))
          "m" (let [[nums rest-t] (parse-numbers rest-tokens 2)
                    x (+ cx (nth nums 0)) y (+ cy (nth nums 1))]
                (recur rest-t (conj points [x y]) x y x y))
          ;; LineTo
          "L" (let [[nums rest-t] (parse-numbers rest-tokens 2)
                    x (nth nums 0) y (nth nums 1)]
                (recur rest-t (conj points [x y]) x y sx sy))
          "l" (let [[nums rest-t] (parse-numbers rest-tokens 2)
                    x (+ cx (nth nums 0)) y (+ cy (nth nums 1))]
                (recur rest-t (conj points [x y]) x y sx sy))
          ;; Horizontal
          "H" (let [[nums rest-t] (parse-numbers rest-tokens 1)
                    x (nth nums 0)]
                (recur rest-t (conj points [x cy]) x cy sx sy))
          "h" (let [[nums rest-t] (parse-numbers rest-tokens 1)
                    x (+ cx (nth nums 0))]
                (recur rest-t (conj points [x cy]) x cy sx sy))
          ;; Vertical
          "V" (let [[nums rest-t] (parse-numbers rest-tokens 1)
                    y (nth nums 0)]
                (recur rest-t (conj points [cx y]) cx y sx sy))
          "v" (let [[nums rest-t] (parse-numbers rest-tokens 1)
                    y (+ cy (nth nums 0))]
                (recur rest-t (conj points [cx y]) cx y sx sy))
          ;; Cubic bezier — sample to line segments
          "C" (let [[nums rest-t] (parse-numbers rest-tokens 6)
                    [x1 y1 x2 y2 x y] nums
                    ;; Sample bezier with 8 segments
                    samples (for [i (range 1 9)]
                              (let [t (/ (double i) 8)
                                    t2 (* t t) t3 (* t2 t)
                                    mt (- 1 t) mt2 (* mt mt) mt3 (* mt2 mt)]
                                [(+ (* mt3 cx) (* 3 mt2 t x1) (* 3 mt t2 x2) (* t3 x))
                                 (+ (* mt3 cy) (* 3 mt2 t y1) (* 3 mt t2 y2) (* t3 y))]))]
                (recur rest-t (into points samples) x y sx sy))
          "c" (let [[nums rest-t] (parse-numbers rest-tokens 6)
                    [dx1 dy1 dx2 dy2 dx dy] nums
                    x1 (+ cx dx1) y1 (+ cy dy1)
                    x2 (+ cx dx2) y2 (+ cy dy2)
                    x (+ cx dx) y (+ cy dy)
                    samples (for [i (range 1 9)]
                              (let [t (/ (double i) 8)
                                    t2 (* t t) t3 (* t2 t)
                                    mt (- 1 t) mt2 (* mt mt) mt3 (* mt2 mt)]
                                [(+ (* mt3 cx) (* 3 mt2 t x1) (* 3 mt t2 x2) (* t3 x))
                                 (+ (* mt3 cy) (* 3 mt2 t y1) (* 3 mt t2 y2) (* t3 y))]))]
                (recur rest-t (into points samples) x y sx sy))
          ;; ClosePath
          ("Z" "z") (recur rest-tokens points sx sy sx sy)
          ;; Unknown command — skip and try to continue
          (recur rest-tokens points cx cy sx sy))))))

;; ── SVG File Loading ────────────────────────────────────────────

(defn- extract-paths
  "Recursively extract all path 'd' attributes from an SVG XML tree."
  [node]
  (cond
    (nil? node) []
    (map? node)
    (let [paths (if (and (= :path (:tag node))
                         (get-in node [:attrs :d]))
                  [(get-in node [:attrs :d])]
                  [])
          children (mapcat extract-paths (:content node))]
      (into paths children))
    :else []))

(defn load-svg
  "Load an SVG file and extract all paths as Ridley 2D shapes.
   Returns a vector of shapes (each is {:type :shape :points [[x y] ...]}).
   Y axis is flipped (SVG Y points down, Ridley Y points up)."
  [path]
  (let [tree (xml/parse (java.io.File. path))
        d-strings (extract-paths tree)
        shapes (mapv (fn [d]
                       (let [pts (parse-path-commands d)
                             ;; Flip Y axis
                             flipped (mapv (fn [[x y]] [x (- y)]) pts)]
                         {:type :shape
                          :points flipped
                          :centered? false}))
                     d-strings)]
    (println (str "Loaded " path " (" (count shapes) " shapes)"))
    (if (= 1 (count shapes))
      (first shapes)
      shapes)))
