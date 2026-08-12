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

(def lp-spindle-d
  "A record player's spindle, in mm. Worth having as a named default because the
   turntable this channel is built around is an actual turntable: punch the sheet
   here and drop it over the spindle, and the plate is centred and COAXIAL with
   the rotation for free — which is the one thing the ring machinery assumes and
   otherwise has to discover."
  7.1)

(defn- spindle-hole
  "A cut guide for the centre hole. Drawn as a light circle, not a filled dot: it
   is something to cut, and a dark disc there would be read by the detector as one
   more mark."
  [d]
  (when (and d (pos? d))
    ;; stroke-width deliberately NOT 0.25: that is the alignment crosses' width,
    ;; and the sheet tests count them by it
    (str "<circle cx=\"0\" cy=\"0\" r=\"" (n (/ d 2.0))
         "\" fill=\"none\" stroke=\"#bbbbbb\" stroke-width=\"0.3\" "
         "stroke-dasharray=\"1.5 1\"/>")))

(defn marks->svg
  "SVG string of a plate's marks: filled dark discs of radius `disc-r` at each
   `disc-centers` [x y] (plate coords), a light plate-outline circle of radius
   `plate-r` (a cut/align guide), a small centre cross (the turntable axis), and
   two orthogonal `bar-mm`-long scale bars in the bottom/left margin (outside the
   plate, so they can be measured then trimmed off before gluing). width/height
   are in mm with a matching viewBox, so printing at 100% is 1:1."
  [disc-centers {:keys [disc-r plate-r bar-mm labels spindle-d]
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
     (spindle-hole spindle-d)
     (line -3 0 3 0 0.2) (line 0 -3 0 3 0.2)
     ;; the marks
     (apply str (map #(disc % disc-r) disc-centers))
     ;; optional per-mark numbers (labels parallel to disc-centers; nil skips)
     (apply str (map-indexed (fn [i c] (label-at c disc-r (get labels i))) disc-centers))
     ;; measuring rules
     (h-scale-bar 0 bar-y bar-mm)
     (v-scale-bar bar-x 0 bar-mm)
     "</svg>")))

;; ---------------------------------------------------------------------------
;; Plates too big for one sheet

(def a4-printable-mm
  "What an A4 page can actually hold at 100%: 210×297 less a ~10mm printer margin
   per side. A plate's sheet is (diameter + 2·18mm) square, so ⌀154 is about the
   largest that fits — every plate worth calling 'big' needs either a larger page
   or two halves."
  [190.0 277.0])

(defn fits-on-a4?
  "Can this plate's sheet be printed 1:1 on a single A4?"
  [plate-r]
  (let [size (* 2 (+ plate-r 18))
        [w h] a4-printable-mm]
    (and (<= size w) (<= size h))))

(defn- seam-angle-deg
  "The angle to cut along, chosen to pass BETWEEN two marks rather than through
   one: halfway between the first two crown angles. A seam through a disc would
   split the very thing whose centroid is the measurement."
  [n-crown]
  (if (and n-crown (pos? n-crown)) (/ 180.0 n-crown) 15.0))

(defn- align-cross
  "A registration cross at [x y], drawn on BOTH halves. Gluing is done by making
   these coincide: two of them, a plate-diameter apart, pin translation and
   rotation together — align them to 0.3mm over 300mm and the angular error is
   0.06°, which is below what the printer's own scaling contributes."
  [[x y] r]
  (str (line (- x r) y (+ x r) y 0.25)
       (line x (- y r) x (+ y r) 0.25)
       "<circle cx=\"" (n x) "\" cy=\"" (n (- y)) "\" r=\"" (n (* 0.55 r))
       "\" fill=\"none\" stroke=\"#111111\" stroke-width=\"0.25\"/>"))

(defn marks->svg-halves
  "The same sheet as `marks->svg`, cut along a diameter into TWO overlapping-free
   halves, each small enough for a smaller page. Returns [svg-a svg-b].

   For a plate bigger than about ⌀150 the sheet no longer fits an A4 at 100%, and
   printing it 'to fit the page' would silently rescale the one thing that must be
   exact. Two halves, printed 1:1 and butted along the seam, keep the scale
   honest — at the price of an alignment the user performs, which is why:

   - the seam runs BETWEEN two marks (`seam-angle-deg`), never through a disc;
   - each half carries the FULL pair of scale bars, so the two prints can be
     checked independently — a printer's scale error is per-page, and assuming
     both sheets came out identical is the kind of assumption this channel keeps
     being punished for;
   - both halves carry the SAME two alignment crosses, on the seam line at the
     plate's rim: making them coincide pins translation and rotation at once.

   `opts` is `marks->svg`'s, plus `:n-crown` (how many marks are the regular
   crown, so the seam can dodge them; the zero-index is not one of them)."
  [disc-centers {:keys [disc-r plate-r bar-mm labels n-crown spindle-d]
                 :or {disc-r 1.25 plate-r 65 bar-mm 100}}]
  (let [margin 18
        half-w (+ plate-r margin)
        half-h (+ plate-r margin)
        theta (* (seam-angle-deg n-crown) (/ Math/PI 180.0))
        ;; Everything is emitted ROTATED so the seam lies on the x-axis. Without
        ;; that, half a disc cut at an angle has a bounding box nearly as large as
        ;; the whole one, and each "half" would still need the page the whole sheet
        ;; needed — which is the entire point of cutting it. Both halves get the
        ;; SAME rotation, so butting them back together reconstitutes the plate;
        ;; that the plate ends up turned by a few degrees means nothing, because
        ;; the zero-index is what says which way it faces.
        ct (Math/cos (- theta)) st (Math/sin (- theta))
        rot (fn [[x y]] [(- (* x ct) (* y st)) (+ (* x st) (* y ct))])
        rotated (mapv rot disc-centers)
        ;; a vertical rule that stays inside its own half
        v-bar (min bar-mm (* 1.6 plate-r))
        sheet (fn [upper?]
                (let [idx (keep-indexed (fn [i [_ y]] (when (if upper? (>= y 0) (< y 0)) i))
                                        rotated)
                      pts (mapv #(nth rotated %) idx)
                      labs (mapv #(get labels %) idx)
                      w (* 2 half-w)
                      ;; plate y runs [0, half-h] above the seam and [-half-h, 0]
                      ;; below; SVG negates y, so the viewBox origin flips with it
                      vb-y (if upper? (- half-h) 0)
                      bar-y (if upper? (+ plate-r 8) (- (+ plate-r 8)))
                      v-cy (if upper? (/ half-h 2.0) (- (/ half-h 2.0)))]
                  (str
                   "<svg xmlns=\"http://www.w3.org/2000/svg\" "
                   "width=\"" (n w) "mm\" height=\"" (n half-h) "mm\" "
                   "viewBox=\"" (n (- half-w)) " " (n vb-y) " " (n w) " " (n half-h) "\">"
                   "<circle cx=\"0\" cy=\"0\" r=\"" (n plate-r)
                   "\" fill=\"none\" stroke=\"#bbbbbb\" stroke-width=\"0.2\"/>"
                   (spindle-hole spindle-d)
                   ;; the seam itself, to cut along
                   (line (- half-w) 0 half-w 0 0.15)
                   (apply str (map #(disc % disc-r) pts))
                   (apply str (map-indexed (fn [i c] (label-at c disc-r (get labs i))) pts))
                   ;; the crosses sit ON the seam, so BOTH halves carry BOTH of
                   ;; them: making the two pairs coincide pins translation and
                   ;; rotation together
                   (align-cross [plate-r 0] 4.0)
                   (align-cross [(- plate-r) 0] 4.0)
                   (h-scale-bar 0 bar-y bar-mm)
                   (v-scale-bar (- (+ plate-r 8)) v-cy v-bar)
                   "</svg>")))]
    [(sheet true) (sheet false)]))
