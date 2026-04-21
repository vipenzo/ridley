;; ── Multiboard Library for Ridley ────────────────────────────────
;; Ported from: Stacked Parametric Multiboard Tiles (OpenSCAD)

;; ── Parametri fondamentali ──────────────────────────────────────
(def cell-size 25)
(def height 6.2) ; STL ufficiale = 6.2mm (OpenSCAD usa 6.4 per stacking)

(def side-l (/ cell-size (+ 1 (* 2 (cos (/ PI 4))))))
(def size-offset (* 0.5 (- cell-size side-l)))

;; ── Forma base della cella (quadrato con angoli tagliati) ───────
(def cell-shape
  (poly (- cell-size size-offset) cell-size
        size-offset cell-size
        0 (- cell-size size-offset)
        0 size-offset
        size-offset 0
        (- cell-size size-offset) 0
        cell-size size-offset
        cell-size (- cell-size size-offset)))

;; Shape estesa: 2 vertici extra per la tacca peg-hole nell'angolo
(def cell-shape-peg
  (poly (- cell-size size-offset) cell-size
        size-offset cell-size
        0 (- cell-size size-offset)
        0 size-offset
        size-offset 0
        (- cell-size size-offset) 0
        cell-size size-offset
        cell-size (- cell-size size-offset)
        (+ cell-size size-offset) cell-size
        cell-size (+ cell-size size-offset)))

;; ── Multihole ───────────────────────────────────────────────────
(def hole-thick 3.6)
(def hole-thin 1.6)
(def hole-thick-height 2.4)

(def hole-thick-size (- cell-size hole-thick))
(def hole-thin-size (- cell-size hole-thin))

(def hole-thick-side-l (/ hole-thick-size (+ 1 (* 2 (cos (/ PI 4))))))
(def hole-thin-side-l (/ hole-thin-size (+ 1 (* 2 (cos (/ PI 4))))))

(def hole-thick-r (/ hole-thick-side-l (* 2 (sin (/ PI 8)))))
(def hole-thin-r (/ hole-thin-side-l (* 2 (sin (/ PI 8)))))

(def h1 (/ (- height hole-thick-height) 2))
(def h2 (/ (+ height hole-thick-height) 2))

(defn lerp [a b t] (+ a (* t (- b a))))

(defn hole-r-at [z]
  (cond
    (< z h1) (lerp hole-thin-r hole-thick-r (/ z h1))
    (< z h2) hole-thick-r
    :else (lerp hole-thick-r hole-thin-r (/ (- z h2) (- height h2)))))

(def multihole-base
  (loft (circle 1 8)
        #(scale (rotate-shape %1 22.5)
                (hole-r-at (* %2 height)))
        (f height)))

;; ── Thread (filettatura trapezoidale a spirale) ─────────────────
;; Costruzione diretta del polyhedron come in OpenSCAD.
;; Asse della spirale lungo +X (direzione heading del multihole).
;; d1/d2 = diametri ext/int, h1/h2 = altezze profilo ext/int,
;; thread-len = lunghezza assiale, pitch = passo, n = segmenti/giro.

(defn trapz-thread [d1 d2 h1 h2 thread-len pitch n x-off]
  (let [profile [[(/ d1 2) (/ h1 -2)]
                 [(/ d1 2) (/ h1 2)]
                 [(/ d2 2) (/ h2 2)]
                 [(/ d2 2) (/ h2 -2)]]
        ppc 4
        n-steps (round (* n (/ thread-len pitch)))
        ;; Vertici: (n-steps+1) sezioni × 4 punti
        verts
        (vec
         (for [i (range (inc n-steps))
               [r hz] profile]
           (let [theta (* i (/ (* 2 PI) n))
                 ax (+ x-off (* i (/ pitch n)))]
             [(+ ax hz)
              (* r (cos theta))
              (* r (sin theta))])))
        ;; Facce laterali: quad [a b c d] → 2 tri (winding invertito per volume positivo)
        sides
        (vec
         (mapcat identity
                 (for [seg (range n-steps)
                       pt (range ppc)]
                   (let [a (+ (* seg ppc) (mod (inc pt) ppc))
                         b (+ a ppc)
                         c (+ (* seg ppc) pt ppc)
                         d (+ (* seg ppc) pt)]
                     [[a c b] [a d c]]))))
        ;; Cap iniziale e finale (invertite)
        start [[0 1 2] [0 2 3]]
        lb (* n-steps ppc)
        end [[(+ lb 2) (+ lb 1) (+ lb 0)]
             [(+ lb 3) (+ lb 2) (+ lb 0)]]]
    {:type :mesh
     :vertices verts
     :faces (vec (concat start sides end))
     :creation-pose {:position [0 0 0] :heading [1 0 0] :up [0 0 1]}}))

;; ── Parametri thread ────────────────────────────────────────────
;; Large thread (multihole) — valori da OpenSCAD / specifiche ufficiali
(def lg-d1 22.5) (def lg-d2 hole-thick-size)
(def lg-h1 0.5) (def lg-h2 1.583)
(def lg-pitch 2.5) (def lg-fn 32)

;; Small thread (peg hole)
(def sm-d1 7.025) (def sm-d2 6.069)
(def sm-h1 0.768) (def sm-h2 2.5)
(def sm-pitch 3.0) (def sm-fn 32)

;; ── Thread mesh ─────────────────────────────────────────────────
(def multihole-thread
  (trapz-thread lg-d1 lg-d2 lg-h1 lg-h2
                (+ height lg-h2) lg-pitch lg-fn
                (/ lg-h2 -2)))

;; ── Peg hole ────────────────────────────────────────────────────
(def peg-hole-d 6.094)
(def peg-hole-r (/ peg-hole-d 2))

(def peg-hole-base
  (extrude (circle peg-hole-r 32) (f (+ height 0.2))))

(def peg-hole-thread
  (trapz-thread sm-d1 sm-d2 sm-h1 sm-h2
                (+ height sm-h2) sm-pitch sm-fn
                (/ sm-h2 -2)))

;; ── Cella singola ───────────────────────────────────────────────
;; Sottrae ogni pezzo separatamente — evita mesh-union su thread raw
(defn multiboard-cell [with-peg-hole]
  (let [base (extrude (if with-peg-hole cell-shape-peg cell-shape) (f height))
        center-rt (/ cell-size 2)
        center-u (/ cell-size 2)]
    (if with-peg-hole
      (mesh-difference base
                       (attach multihole-base (rt center-rt) (u center-u))
                       (attach multihole-thread (rt center-rt) (u center-u))
                       (attach peg-hole-base (rt cell-size) (u cell-size))
                       (attach peg-hole-thread (rt cell-size) (u cell-size)))
      (mesh-difference base
                       (attach multihole-base (rt center-rt) (u center-u))
                       (attach multihole-thread (rt center-rt) (u center-u))))))

;; ── Tile v2: contorno solido + sottrai fori ─────────────────────
;; Approccio: un blocco ottagonale unico, poi sottrai tutti i fori.
;; Elimina i gap tra celle al bordo.

;; Parametri border slot (da analisi STL ufficiale)
;; Profilo a T: collo stretto + camera interna più larga
(def slot-neck-width 6.0) ; larghezza collo (stretto)
(def slot-head-width 7.0) ; larghezza camera interna (larga)
(def slot-neck-depth 2.0) ; profondità collo (dal bordo esterno)
(def slot-head-depth 3.0) ; profondità camera interna
(def slot-height 3.4) ; altezza (z 1.4→4.8 nell'STL)
(def slot-h-center 3.1) ; centro altezza slot
(def slot-total-depth (+ slot-neck-depth slot-head-depth))

;; Parametri canale cilindrico
(def channel-r 1.08) ; raggio ~1.08mm

(defn tile-outline
  "Contorno del tile NxM celle, centrato all'origine.
   edges: set of border edges #{:top :bottom :left :right}.
   Corners are clipped (octagonal) only where both adjacent edges have borders.
   Sides without borders extend to the full rectangle."
  ([x-cells y-cells] (tile-outline x-cells y-cells #{:top :bottom :left :right}))
  ([x-cells y-cells edges]
   (let [w (* x-cells cell-size)
         h (* y-cells cell-size)
         ox (/ w 2)
         oy (/ h 2)
         so size-offset
         ;; Corner clips: only if BOTH adjacent edges have borders
         tr? (and (:top edges) (:right edges))
         tl? (and (:top edges) (:left edges))
         bl? (and (:bottom edges) (:left edges))
         br? (and (:bottom edges) (:right edges))
         ;; Build polygon CW from top-right (same winding as original octagon).
         ;; Top → Left → Bottom → Right, with corner clips where both
         ;; adjacent edges have borders.
         pts (vec (concat
                   ;; Top edge (right to left)
                   (if tr? [[(- (- w so) ox) (- h oy)]]
                       [[(- w ox) (- h oy)]])
                   (if tl? [[(- so ox) (- h oy)]]
                       [[(- 0 ox) (- h oy)]])
                   ;; Left edge (top to bottom)
                   (if tl? [[(- 0 ox) (- (- h so) oy)]]
                       [])
                   (if bl? [[(- 0 ox) (- so oy)]]
                       [[(- 0 ox) (- 0 oy)]])
                   ;; Bottom edge (left to right)
                   (if bl? [[(- so ox) (- 0 oy)]]
                       [])
                   (if br? [[(- (- w so) ox) (- 0 oy)]]
                       [[(- w ox) (- 0 oy)]])
                   ;; Right edge (bottom to top)
                   (if br? [[(- w ox) (- so oy)]]
                       [])
                   (if tr? [[(- w ox) (- (- h so) oy)]]
                       [])))]
     (apply poly (mapcat identity pts)))))

(defn multiboard-single-tile
  "Genera un tile Multiboard NxM centrato all'origine.
   Blocco solido con multihole, peg hole, border slot e canali.
   edges: set of border edges #{:top :bottom :left :right} (default: all 4).
   Edges without borders get no slots, channels, or corner clips —
   the tile extends to the full rectangle for seamless joining.

   Tile types:
   - #{:top :bottom :left :right} — standalone tile (default)
   - #{:top :left}                — top-left corner
   - #{:top}                      — top edge (middle)
   - #{}                          — center tile (no borders)"
  ([x-cells y-cells] (multiboard-single-tile x-cells y-cells #{:top :bottom :left :right}))
  ([x-cells y-cells edges]
   (let [edges (set edges)
         w (* x-cells cell-size)
         h (* y-cells cell-size)
         ox (/ w 2)
         oy (/ h 2)
         ;; 1. Blocco solido (outline depends on which edges have borders)
         base (extrude (tile-outline x-cells y-cells edges) (f height))
         ;; 2. Multihole + thread per ogni cella
         multiholes
         (for [i (range x-cells)
               j (range y-cells)]
           (let [cx (- (+ (* i cell-size) (/ cell-size 2)) ox)
                 cy (- (+ (* j cell-size) (/ cell-size 2)) oy)]
             [(attach multihole-base (rt cx) (u cy))
              (attach multihole-thread (rt cx) (u cy))]))
         ;; 3. Peg holes
         ;; Internal vertices: full-depth peg holes
         ;; Edge vertices on open borders: half-height diamond protrusion
         ;; with full-depth peg hole drilled through
         half-h (/ height 2)
         peg-diamond (poly (- size-offset) 0
                           0 (- size-offset)
                           size-offset 0
                           0 size-offset)
         ;; Internal peg holes (same as before)
         peg-holes
         (for [i (range 1 x-cells)
               j (range 1 y-cells)]
           (let [px (- (* i cell-size) ox)
                 py (- (* j cell-size) oy)]
             [(attach peg-hole-base (rt px) (u py))
              (attach peg-hole-thread (rt px) (u py))]))
         ;; Edge peg holes: vertices on open borders
         edge-peg-positions
         (vec (concat
               (when (not (:bottom edges))
                 (for [i (range 1 x-cells)]
                   [(- (* i cell-size) ox) (- oy)]))
               (when (not (:top edges))
                 (for [i (range 1 x-cells)]
                   [(- (* i cell-size) ox) (- h oy)]))
               (when (not (:left edges))
                 (for [j (range 1 y-cells)]
                   [(- ox) (- (* j cell-size) oy)]))
               (when (not (:right edges))
                 (for [j (range 1 y-cells)]
                   [(- w ox) (- (* j cell-size) oy)]))))
         ;; Edge peg cutters:
         ;; - Full peg hole + thread through the bottom half (the half we keep)
         ;; - Full diamond volume in top half (empty space for the adjacent
         ;;   flipped tile's diamond to slot into)
         peg-holes-half
         (for [[px py] edge-peg-positions]
           [(attach peg-hole-base (rt px) (u py))
            (attach peg-hole-thread (rt px) (u py))
            ;; Cut the diamond volume in the top half (clear space for neighbor)
            (attach (extrude peg-diamond (f (+ half-h 0.1))) (f half-h) (rt px) (u py))])
         ;; Diamond protrusions at half height (the bottom half we add)
         peg-protrusions
         (for [[px py] edge-peg-positions]
           (attach (extrude peg-diamond (f half-h)) (rt px) (u py)))
         ;; 4. Border slots a T (only on edges with borders)
         x-bounds (for [i (range 1 x-cells)] (- (* i cell-size) ox))
         y-bounds (for [j (range 1 y-cells)] (- (* j cell-size) oy))
         ;; box(f, rt, u): first arg along heading, second along right, third along up.
         ;; After final (tv 90): f→Z(vert), rt→Y, u→X.
         ;; Right/left edges run along X (u axis). Width along u, depth along rt.
         right-slots
         (when (:right edges)
           (mapcat identity
                   (for [yb y-bounds]
                     [(attach (box slot-height slot-neck-depth slot-neck-width)
                              (f slot-h-center)
                              (rt (- ox (/ slot-neck-depth 2)))
                              (u yb)
                              (tv 90))
                      (attach (box slot-height slot-head-depth slot-head-width)
                              (f slot-h-center)
                              (rt (- ox slot-neck-depth (/ slot-head-depth 2)))
                              (u yb)
                              (tv 90))])))
         left-slots
         (when (:left edges)
           (mapcat identity
                   (for [yb y-bounds]
                     [(attach (box slot-height slot-neck-depth slot-neck-width)
                              (f slot-h-center)
                              (rt (- (/ slot-neck-depth 2) ox))
                              (u yb)
                              (tv 90))
                      (attach (box slot-height slot-head-depth slot-head-width)
                              (f slot-h-center)
                              (rt (+ (- ox) slot-neck-depth (/ slot-head-depth 2)))
                              (u yb)
                              (tv 90))])))
         ;; Top/bottom edges run along Y (rt axis). Width along rt, depth along u.
         top-slots
         (when (:top edges)
           (mapcat identity
                   (for [xb x-bounds]
                     [(attach (box slot-height slot-neck-width slot-neck-depth)
                              (f slot-h-center)
                              (rt xb)
                              (u (- oy (/ slot-neck-depth 2)))
                              (tr 90))
                      (attach (box slot-height slot-head-width slot-head-depth)
                              (f slot-h-center)
                              (rt xb)
                              (u (- oy slot-neck-depth (/ slot-head-depth 2)))
                              (tr 90))])))
         bottom-slots
         (when (:bottom edges)
           (mapcat identity
                   (for [xb x-bounds]
                     [(attach (box slot-height slot-neck-width slot-neck-depth)
                              (f slot-h-center)
                              (rt xb)
                              (u (- (/ slot-neck-depth 2) oy))
                              (tr 90))
                      (attach (box slot-height slot-head-width slot-head-depth)
                              (f slot-h-center)
                              (rt xb)
                              (u (+ (- oy) slot-neck-depth (/ slot-head-depth 2)))
                              (tr 90))])))
         ;; 5. Channels (only on edges with borders)
         ch-offset 3.2
         ch-margin (* size-offset 1.5)
         ch-y-len (- h (* 2 ch-margin))
         ch-x-len (- w (* 2 ch-margin))
         channels
         (vec (concat
               (when (:right edges)
                 [(attach (cyl channel-r ch-y-len) (f slot-h-center)
                          (rt (- ox ch-offset)) (tv 90))])
               (when (:left edges)
                 [(attach (cyl channel-r ch-y-len) (f slot-h-center)
                          (rt (- ch-offset ox)) (tv 90))])
               (when (:top edges)
                 [(attach (cyl channel-r ch-x-len) (f slot-h-center)
                          (u (- oy ch-offset)) (tv -90) (th 90))])
               (when (:bottom edges)
                 [(attach (cyl channel-r ch-x-len) (f slot-h-center)
                          (u (- ch-offset oy)) (tv -90) (th 90))])))
         ;; Add diamond protrusions to base for half peg holes
         protrusion-list (vec (filter some? peg-protrusions))
         base-with-protrusions
         (if (seq protrusion-list)
           (mesh-union (into [base] protrusion-list))
           base)
         ;; Unisci tutti i cutter
         half-peg-cutters (vec (mapcat identity (filter some? peg-holes-half)))
         all-cutters (vec (concat
                           (mapcat identity multiholes)
                           (mapcat identity peg-holes)
                           half-peg-cutters
                           right-slots left-slots top-slots bottom-slots
                           channels))
         cutter (mesh-union all-cutters)]
     (attach (mesh-difference base-with-protrusions cutter) (tv 90)))))

(defn multiboard-tile
  "Genera uno stack di tile Multiboard per la stampa.
   edges: set of border sides (default all 4).
   (multiboard-tile 4 2)                              → standalone tile
   (multiboard-tile 4 2 #{:top :left})                → corner tile
   (multiboard-tile 4 2 #{:top})                      → edge tile
   (multiboard-tile 4 2 #{})                           → center tile (no borders)
   (multiboard-tile 4 2 #{:top :left} 3)              → 3 stacked corner tiles
   (multiboard-tile 4 2 #{:top :left} 3 0.3)          → stacked with 0.3mm gap"
  ([x-cells y-cells]
   (multiboard-single-tile x-cells y-cells))
  ([x-cells y-cells edges]
   (multiboard-single-tile x-cells y-cells edges))
  ([x-cells y-cells edges n-layers]
   (multiboard-tile x-cells y-cells edges n-layers 0.2))
  ([x-cells y-cells edges n-layers separation]
   (let [tile (multiboard-single-tile x-cells y-cells edges)
         step (+ height separation)
         tiles (for [i (range n-layers)]
                 (if (zero? i)
                   tile
                   (attach tile (f (* i step)))))]
     (concat-meshes (vec tiles)))))

;; ── Preview ─────────────────────────────────────────────────────
;; Standalone tile (all borders)
(register Tile (bench "tile" (multiboard-tile 10 10 #{:top :left})))
(color :Tile 0xffffff)

;; Examples of edge/corner/center tiles:
;; (register Corner  (multiboard-tile 4 4 #{:top :left}))        ; corner
;; (register Edge    (multiboard-tile 4 4 #{:top}))              ; edge
;; (register Center  (multiboard-tile 4 4 #{}))                  ; center (no borders)

(comment
  (register Tile (bench "tile" (multiboard-tile 10 10 #{:top :left})))
  (register Tile2 (attach (bench "tile" (multiboard-tile 4 4 #{:top :left}))
                          (rt (* 4 25))
                          (th 180)
                          (f -6.2))))