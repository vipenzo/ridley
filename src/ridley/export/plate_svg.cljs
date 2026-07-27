(ns ridley.export.plate-svg
  "Render a registration plate's marks as a printable SVG at EXACT mm scale —
   the 'paper variant': print at 100%, glue onto a plain plastic plate, so the
   marks get paper's crisp edges instead of the fuzzy top-surface of a two-colour
   FDM print (which blurs the blob-snap centroid and inflates the PnP rms). The
   sheet carries two orthogonal SCALE BARS: measure them with a caliper and feed
   the readings back to scale the mark map per axis, correcting the printer's own
   scaling error (0.1-0.5%, often anisotropic).

   Single source of truth is preserved: the mark POSITIONS are the plate's own
   mark-centers, passed in; this namespace only renders them (positions in one
   place feed both the printed sheet and the registered mark map).

   Coordinate note: plate coordinates are y-up (maths); SVG is y-down, so every y
   is negated on the way out.")

(defn- n [x] (.toFixed x 3))

(defn- disc [[x y] r]
  (str "<circle cx=\"" (n x) "\" cy=\"" (n (- y)) "\" r=\"" (n r)
       "\" fill=\"#111111\" stroke=\"none\"/>"))

(defn- line [x1 y1 x2 y2 w]
  (str "<line x1=\"" (n x1) "\" y1=\"" (n (- y1)) "\" x2=\"" (n x2) "\" y2=\"" (n (- y2))
       "\" stroke=\"#111111\" stroke-width=\"" (n w) "\"/>"))

(defn- text [x y anchor s]
  (str "<text x=\"" (n x) "\" y=\"" (n (- y)) "\" font-family=\"sans-serif\" "
       "font-size=\"4\" fill=\"#111111\" text-anchor=\"" anchor "\">" s "</text>"))

(def ^:private label-fill
  "A FADED slate for the mark numbers — light enough that blob-snap's dark/light
   split (threshold at the midpoint between the window's dark disc and light
   plate) leaves the glyph on the LIGHT side, so it never enters the centroid
   even if a window reaches it. Belt-and-suspenders with the radial placement,
   and it keeps the numbers subordinate to the marks on the sheet."
  "#9aa0ae")

(defn- label-at
  "A mark's index printed RADIALLY OUTWARD from its disc, `disc-r`+gap out — clear
   of the blob-snap window and in a faded colour (see label-fill), so the glyph
   can't pull the centroid, yet readable as that disc's number. nil → nothing."
  [[x y] disc-r s]
  (when s
    (let [len (Math/sqrt (+ (* x x) (* y y)))
          [ux uy] (if (> len 1e-6) [(/ x len) (/ y len)] [1.0 0.0])
          off (+ disc-r 3.5)
          lx (+ x (* ux off)) ly (+ y (* uy off))]
      (str "<text x=\"" (n lx) "\" y=\"" (n (- ly))
           "\" font-family=\"sans-serif\" font-size=\"3\" fill=\"" label-fill "\" "
           "text-anchor=\"middle\" dominant-baseline=\"central\">" s "</text>"))))

(defn- h-scale-bar
  "A horizontal measuring rule of length `len` mm centred at x=cx, at height y,
   with end ticks (the caliper reads tick-to-tick) and a label of its nominal
   length — so the user knows what it SHOULD be and measures what it IS."
  [cx y len]
  (let [x1 (- cx (/ len 2)) x2 (+ cx (/ len 2)) tk 2.0]
    (str (line x1 y x2 y 0.3)
         (line x1 (- y tk) x1 (+ y tk) 0.3)
         (line x2 (- y tk) x2 (+ y tk) 0.3)
         (text cx (- y 4) "middle" (str "scala X — nominale " (n len) " mm")))))

(defn- v-scale-bar
  "A vertical measuring rule of length `len` mm centred at y=cy, at abscissa x."
  [x cy len]
  (let [y1 (- cy (/ len 2)) y2 (+ cy (/ len 2)) tk 2.0]
    (str (line x y1 x y2 0.3)
         (line (- x tk) y1 (+ x tk) y1 0.3)
         (line (- x tk) y2 (+ x tk) y2 0.3)
         ;; label rotated to run along the bar
         (str "<text x=\"" (n (- x 4)) "\" y=\"" (n (- cy))
              "\" font-family=\"sans-serif\" font-size=\"4\" fill=\"#111111\" "
              "text-anchor=\"middle\" transform=\"rotate(-90 " (n (- x 4)) " " (n (- cy)) ")\">"
              "scala Y — nominale " (n len) " mm</text>"))))

(defn marks->svg
  "SVG string of a plate's marks: filled dark discs of radius `disc-r` at each
   `disc-centers` [x y] (plate coords), a light plate-outline circle of radius
   `plate-r` (a cut/align guide), a small centre cross (the turntable axis), and
   two orthogonal `bar-mm`-long scale bars in the bottom/left margin (outside the
   plate, so they can be measured then trimmed off before gluing). width/height
   are in mm with a matching viewBox, so printing at 100% is 1:1."
  [disc-centers {:keys [disc-r plate-r bar-mm labels]
                 :or {disc-r 1.25 plate-r 65 bar-mm 100}}]
  (let [margin 18
        half (+ plate-r margin)
        size (* 2 half)
        bar-y (- (+ plate-r 8))       ; below the plate
        bar-x (- (+ plate-r 8))]      ; left of the plate
    (str
     "<svg xmlns=\"http://www.w3.org/2000/svg\" "
     "width=\"" (n size) "mm\" height=\"" (n size) "mm\" "
     "viewBox=\"" (n (- half)) " " (n (- half)) " " (n size) " " (n size) "\">"
     ;; plate outline (light) + centre cross
     "<circle cx=\"0\" cy=\"0\" r=\"" (n plate-r)
     "\" fill=\"none\" stroke=\"#bbbbbb\" stroke-width=\"0.2\"/>"
     (line -3 0 3 0 0.2) (line 0 -3 0 3 0.2)
     ;; the marks
     (apply str (map #(disc % disc-r) disc-centers))
     ;; optional per-mark numbers (labels parallel to disc-centers; nil skips)
     (apply str (map-indexed (fn [i c] (label-at c disc-r (get labels i))) disc-centers))
     ;; measuring rules
     (h-scale-bar 0 bar-y bar-mm)
     (v-scale-bar bar-x 0 bar-mm)
     "</svg>")))
