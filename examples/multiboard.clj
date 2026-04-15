;; ── Multiboard Library for Ridley ────────────────────────────────
;; Ported from: Stacked Parametric Multiboard Tiles (OpenSCAD)

;; ── Parametri fondamentali ──────────────────────────────────────
(def cell-size 25)
(def height 6.2)   ; STL ufficiale = 6.2mm (OpenSCAD usa 6.4 per stacking)

(def side-l (/ cell-size (+ 1 (* 2 (cos (/ PI 4))))))
(def size-offset (* 0.5 (- cell-size side-l)))

;; ── Forma base della cella (quadrato con angoli tagliati) ───────
(def cell-shape
  (poly (- cell-size size-offset) cell-size
        size-offset               cell-size
        0                         (- cell-size size-offset)
        0                         size-offset
        size-offset               0
        (- cell-size size-offset) 0
        cell-size                 size-offset
        cell-size                 (- cell-size size-offset)))

;; Shape estesa: 2 vertici extra per la tacca peg-hole nell'angolo
(def cell-shape-peg
  (poly (- cell-size size-offset) cell-size
        size-offset               cell-size
        0                         (- cell-size size-offset)
        0                         size-offset
        size-offset               0
        (- cell-size size-offset) 0
        cell-size                 size-offset
        cell-size                 (- cell-size size-offset)
        (+ cell-size size-offset) cell-size
        cell-size                 (+ cell-size size-offset)))

;; ── Multihole ───────────────────────────────────────────────────
(def hole-thick      3.6)
(def hole-thin       1.6)
(def hole-thick-height 2.4)

(def hole-thick-size (- cell-size hole-thick))
(def hole-thin-size  (- cell-size hole-thin))

(def hole-thick-side-l (/ hole-thick-size (+ 1 (* 2 (cos (/ PI 4))))))
(def hole-thin-side-l  (/ hole-thin-size  (+ 1 (* 2 (cos (/ PI 4))))))

(def hole-thick-r (/ hole-thick-side-l (* 2 (sin (/ PI 8)))))
(def hole-thin-r  (/ hole-thin-side-l  (* 2 (sin (/ PI 8)))))

(def h1 (/ (- height hole-thick-height) 2))
(def h2 (/ (+ height hole-thick-height) 2))

(defn lerp [a b t] (+ a (* t (- b a))))

(defn hole-r-at [z]
  (cond
    (< z h1) (lerp hole-thin-r hole-thick-r (/ z h1))
    (< z h2) hole-thick-r
    :else    (lerp hole-thick-r hole-thin-r (/ (- z h2) (- height h2)))))

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
                 [(/ d1 2) (/ h1  2)]
                 [(/ d2 2) (/ h2  2)]
                 [(/ d2 2) (/ h2 -2)]]
        ppc     4
        n-steps (round (* n (/ thread-len pitch)))
        ;; Vertici: (n-steps+1) sezioni × 4 punti
        verts
        (vec
         (for [i (range (inc n-steps))
               [r hz] profile]
           (let [theta (* i (/ (* 2 PI) n))
                 ax    (+ x-off (* i (/ pitch n)))]
             [(+ ax hz)
              (* r (cos theta))
              (* r (sin theta))])))
        ;; Facce laterali: quad [a b c d] → 2 tri (winding invertito per volume positivo)
        sides
        (vec
         (mapcat identity
                 (for [seg (range n-steps)
                       pt  (range ppc)]
                   (let [a (+ (* seg ppc) (mod (inc pt) ppc))
                         b (+ a ppc)
                         c (+ (* seg ppc) pt ppc)
                         d (+ (* seg ppc) pt)]
                     [[a c b] [a d c]]))))
        ;; Cap iniziale e finale (invertite)
        start [[0 1 2] [0 2 3]]
        lb    (* n-steps ppc)
        end   [[(+ lb 2) (+ lb 1) (+ lb 0)]
               [(+ lb 3) (+ lb 2) (+ lb 0)]]]
    {:type :mesh
     :vertices verts
     :faces (vec (concat start sides end))
     :creation-pose {:position [0 0 0] :heading [1 0 0] :up [0 0 1]}}))

;; ── Parametri thread ────────────────────────────────────────────
;; Large thread (multihole) — valori da OpenSCAD / specifiche ufficiali
(def lg-d1 22.5)    (def lg-d2 hole-thick-size)
(def lg-h1 0.5)     (def lg-h2 1.583)
(def lg-pitch 2.5)  (def lg-fn 32)

;; Small thread (peg hole)
(def sm-d1 7.025)   (def sm-d2 6.069)
(def sm-h1 0.768)   (def sm-h2 2.5)
(def sm-pitch 3.0)  (def sm-fn 32)

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
  (let [base   (extrude (if with-peg-hole cell-shape-peg cell-shape) (f height))
        center-rt (/ cell-size 2)
        center-u  (/ cell-size 2)]
    (if with-peg-hole
      (mesh-difference base
                       (attach multihole-base   (rt center-rt) (u center-u))
                       (attach multihole-thread (rt center-rt) (u center-u))
                       (attach peg-hole-base    (rt cell-size) (u cell-size))
                       (attach peg-hole-thread  (rt cell-size) (u cell-size)))
      (mesh-difference base
                       (attach multihole-base   (rt center-rt) (u center-u))
                       (attach multihole-thread (rt center-rt) (u center-u))))))

;; ── Tile v2: contorno solido + sottrai fori ─────────────────────
;; Approccio: un blocco ottagonale unico, poi sottrai tutti i fori.
;; Elimina i gap tra celle al bordo.

;; Parametri border slot (da analisi STL ufficiale)
;; Profilo a T: collo stretto + camera interna più larga
(def slot-neck-width 6.0)   ; larghezza collo (stretto)
(def slot-head-width 7.0)   ; larghezza camera interna (larga)
(def slot-neck-depth 2.0)   ; profondità collo (dal bordo esterno)
(def slot-head-depth 3.0)   ; profondità camera interna
(def slot-height 3.4)       ; altezza (z 1.4→4.8 nell'STL)
(def slot-h-center 3.1)     ; centro altezza slot
(def slot-total-depth (+ slot-neck-depth slot-head-depth))

;; Parametri canale cilindrico
(def channel-r 1.08)      ; raggio ~1.08mm

(defn tile-outline
  "Contorno ottagonale del tile NxM celle, centrato all'origine."
  [x-cells y-cells]
  (let [w (* x-cells cell-size)
        h (* y-cells cell-size)
        ox (/ w 2)
        oy (/ h 2)]
    (poly (- (- w size-offset) ox) (- h oy)
          (- size-offset ox)       (- h oy)
          (- 0 ox)                 (- (- h size-offset) oy)
          (- 0 ox)                 (- size-offset oy)
          (- size-offset ox)       (- 0 oy)
          (- (- w size-offset) ox) (- 0 oy)
          (- w ox)                 (- size-offset oy)
          (- w ox)                 (- (- h size-offset) oy))))

(defn multiboard-single-tile
  "Genera un tile Multiboard NxM centrato all'origine.
   Blocco solido con multihole, peg hole, border slot e canali."
  [x-cells y-cells]
  (let [w (* x-cells cell-size)
        h (* y-cells cell-size)
        ox (/ w 2)
        oy (/ h 2)
        ;; 1. Blocco solido
        base (extrude (tile-outline x-cells y-cells) (f height))
        ;; 2. Multihole + thread per ogni cella
        multiholes
        (for [i (range x-cells)
              j (range y-cells)]
          (let [cx (- (+ (* i cell-size) (/ cell-size 2)) ox)
                cy (- (+ (* j cell-size) (/ cell-size 2)) oy)]
            [(attach multihole-base   (rt cx) (u cy))
             (attach multihole-thread (rt cx) (u cy))]))
        ;; 3. Peg holes solo ai vertici interni
        peg-holes
        (for [i (range 1 x-cells)
              j (range 1 y-cells)]
          (let [px (- (* i cell-size) ox)
                py (- (* j cell-size) oy)]
            [(attach peg-hole-base   (rt px) (u py))
             (attach peg-hole-thread (rt px) (u py))]))
        ;; 4. Border slots a T: collo stretto + camera larga
        ;;    box(sx sy sz) → sx=right(Y), sy=up(Z), sz=heading(X)
        x-bounds (for [i (range 1 x-cells)] (- (* i cell-size) ox))
        y-bounds (for [j (range 1 y-cells)] (- (* j cell-size) oy))
        ;; Per ogni slot: 2 box (collo + camera) uniti
        ;; Right edge slots
        right-slots
        (mapcat identity
                (for [yb y-bounds]
                  [(attach (box slot-neck-depth slot-neck-width slot-height)
                           (f slot-h-center)
                           (rt (- ox (/ slot-neck-depth 2)))
                           (u yb))
                   (attach (box slot-head-depth slot-head-width slot-height)
                           (f slot-h-center)
                           (rt (- ox slot-neck-depth (/ slot-head-depth 2)))
                           (u yb))]))
        ;; Left edge slots
        left-slots
        (mapcat identity
                (for [yb y-bounds]
                  [(attach (box slot-neck-depth slot-neck-width slot-height)
                           (f slot-h-center)
                           (rt (- (/ slot-neck-depth 2) ox))
                           (u yb))
                   (attach (box slot-head-depth slot-head-width slot-height)
                           (f slot-h-center)
                           (rt (+ (- ox) slot-neck-depth (/ slot-head-depth 2)))
                           (u yb))]))
        ;; Top edge slots
        top-slots
        (mapcat identity
                (for [xb x-bounds]
                  [(attach (box slot-neck-width slot-neck-depth slot-height)
                           (f slot-h-center)
                           (rt xb)
                           (u (- oy (/ slot-neck-depth 2))))
                   (attach (box slot-head-width slot-head-depth slot-height)
                           (f slot-h-center)
                           (rt xb)
                           (u (- oy slot-neck-depth (/ slot-head-depth 2))))]))
        ;; Bottom edge slots
        bottom-slots
        (mapcat identity
                (for [xb x-bounds]
                  [(attach (box slot-neck-width slot-neck-depth slot-height)
                           (f slot-h-center)
                           (rt xb)
                           (u (- (/ slot-neck-depth 2) oy)))
                   (attach (box slot-head-width slot-head-depth slot-height)
                           (f slot-h-center)
                           (rt xb)
                           (u (+ (- oy) slot-neck-depth (/ slot-head-depth 2))))]))
        ;; 5. Canali cilindrici (1 per lato, tra primo e ultimo slot)
        ;;    Posizionato a ~3.2mm dal bordo esterno
        ch-offset 3.2           ; distanza dal bordo
        ch-margin (* size-offset 1.5)  ; margine dagli angoli
        ch-y-len (- h (* 2 ch-margin))  ; più corto: si ferma prima della diagonale
        ch-x-len (- w (* 2 ch-margin))
        ch-right  (attach (cyl channel-r ch-y-len) (f slot-h-center)
                          (rt (- ox ch-offset)) (tv 90))
        ch-left   (attach (cyl channel-r ch-y-len) (f slot-h-center)
                          (rt (- ch-offset ox)) (tv 90))
        ch-top    (attach (cyl channel-r ch-x-len) (f slot-h-center)
                          (u (- oy ch-offset)) (tv -90) (th 90))
        ch-bottom (attach (cyl channel-r ch-x-len) (f slot-h-center)
                          (u (- ch-offset oy)) (tv -90) (th 90))
        ;; 6. Corner slots (meno profondi, sulla faccia diagonale dell'ottagono)
        ;;    Ruotati di 45° per allinearsi alla diagonale
        corner-w 7.0           ; lunghezza lungo la diagonale
        corner-d 0.8 ; profondità (appena una tacca, ~diametro canale in larghezza)
        so2 (* size-offset 0.5) ; metà offset per centrare sulla diagonale
        hhh (* slot-height 0.7)
        corners
        [;; Top-right diagonal
         (attach (box corner-w corner-d hhh)
                 (f slot-h-center)
                 (rt (- ox so2)) (u (- oy so2)) (tr 45))
         ;; Top-left diagonal
         (attach (box corner-w corner-d hhh)
                 (f slot-h-center)
                 (rt (- so2 ox)) (u (- oy so2)) (tr -45))
         ;; Bottom-left diagonal
         (attach (box corner-w corner-d hhh)
                 (f slot-h-center)
                 (rt (- so2 ox)) (u (- so2 oy)) (tr 45))
         ;; Bottom-right diagonal
         (attach (box corner-w corner-d hhh)
                 (f slot-h-center)
                 (rt (- ox so2)) (u (- so2 oy)) (tr -45))]
        ;; Unisci tutti i cutter
        all-cutters (vec (concat
                          (mapcat identity multiholes)
                          (mapcat identity peg-holes)
                          right-slots left-slots top-slots bottom-slots
                          [ch-right ch-left ch-top ch-bottom]
                          corners))
        cutter (mesh-union all-cutters)]
    (attach (mesh-difference base cutter) (tv 90))))

(defn multiboard-tile
  "Genera uno stack di tile Multiboard per la stampa.
   (multiboard-tile 4 2)           → singolo tile
   (multiboard-tile 4 2 3)         → 3 tile impilati (gap 0.2mm)
   (multiboard-tile 4 2 3 0.3)     → 3 tile impilati (gap 0.3mm)"
  ([x-cells y-cells]
   (multiboard-single-tile x-cells y-cells))
  ([x-cells y-cells n-layers]
   (multiboard-tile x-cells y-cells n-layers 0.2))
  ([x-cells y-cells n-layers separation]
   (let [tile (multiboard-single-tile x-cells y-cells)
         step (+ height separation)  ; altezza tile + gap
         tiles (for [i (range n-layers)]
                 (if (zero? i)
                   tile
                   (attach tile (f (* i step)))))]
     (concat-meshes (vec tiles)))))

;; ── Preview ─────────────────────────────────────────────────────
;; Singola cella (per test veloci)
;; (register Cell (multiboard-cell true))

(register Tile (bench "Wasm" (multiboard-tile 4 4)))
(color :Tile 0xffffff)