;; ── Multiboard Native Benchmark ──────────────────────────────────
;; Same as multiboard.clj but uses native Rust Manifold for CSG ops.
;; Run inside Tauri desktop app to compare WASM vs Native performance.

;; ── Parametri fondamentali ──────────────────────────────────────
(def cell-size 25)
(def height 6.2)

(def side-l (/ cell-size (+ 1 (* 2 (cos (/ PI 4))))))
(def size-offset (* 0.5 (- cell-size side-l)))

;; ── Forma base della cella ──────────────────────────────────────
(def cell-shape
  (poly (- cell-size size-offset) cell-size
        size-offset               cell-size
        0                         (- cell-size size-offset)
        0                         size-offset
        size-offset               0
        (- cell-size size-offset) 0
        cell-size                 size-offset
        cell-size                 (- cell-size size-offset)))

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

;; ── Thread ──────────────────────────────────────────────────────
(defn trapz-thread [d1 d2 h1 h2 thread-len pitch n x-off]
  (let [profile [[(/ d1 2) (/ h1 -2)]
                 [(/ d1 2) (/ h1  2)]
                 [(/ d2 2) (/ h2  2)]
                 [(/ d2 2) (/ h2 -2)]]
        ppc     4
        n-steps (round (* n (/ thread-len pitch)))
        verts
        (vec
         (for [i (range (inc n-steps))
               [r hz] profile]
           (let [theta (* i (/ (* 2 PI) n))
                 ax    (+ x-off (* i (/ pitch n)))]
             [(+ ax hz)
              (* r (cos theta))
              (* r (sin theta))])))
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
        start [[0 1 2] [0 2 3]]
        lb    (* n-steps ppc)
        end   [[(+ lb 2) (+ lb 1) (+ lb 0)]
               [(+ lb 3) (+ lb 2) (+ lb 0)]]]
    {:type :mesh
     :vertices verts
     :faces (vec (concat start sides end))
     :creation-pose {:position [0 0 0] :heading [1 0 0] :up [0 0 1]}}))

(def lg-d1 22.5)    (def lg-d2 hole-thick-size)
(def lg-h1 0.5)     (def lg-h2 1.583)
(def lg-pitch 2.5)  (def lg-fn 32)

(def sm-d1 7.025)   (def sm-d2 6.069)
(def sm-h1 0.768)   (def sm-h2 2.5)
(def sm-pitch 3.0)  (def sm-fn 32)

(def multihole-thread
  (trapz-thread lg-d1 lg-d2 lg-h1 lg-h2
                (+ height lg-h2) lg-pitch lg-fn
                (/ lg-h2 -2)))

(def peg-hole-d 6.094)
(def peg-hole-r (/ peg-hole-d 2))

(def peg-hole-base
  (extrude (circle peg-hole-r 32) (f (+ height 0.2))))

(def peg-hole-thread
  (trapz-thread sm-d1 sm-d2 sm-h1 sm-h2
                (+ height sm-h2) sm-pitch sm-fn
                (/ sm-h2 -2)))

;; ── Parametri border slot ───────────────────────────────────────
(def slot-neck-width 6.0)
(def slot-head-width 7.0)
(def slot-neck-depth 2.0)
(def slot-head-depth 3.0)
(def slot-height 3.4)
(def slot-h-center 3.1)

(def channel-r 1.08)

(defn tile-outline [x-cells y-cells]
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

(def WASM false)
;; ── Tile with benchmarking ──────────────────────────────────────
(defn multiboard-bench [x-cells y-cells]
  (let [w (* x-cells cell-size)
        h (* y-cells cell-size)
        ox (/ w 2)
        oy (/ h 2)
        base (extrude (tile-outline x-cells y-cells) (f height))
        multiholes
        (for [i (range x-cells)
              j (range y-cells)]
          (let [cx (- (+ (* i cell-size) (/ cell-size 2)) ox)
                cy (- (+ (* j cell-size) (/ cell-size 2)) oy)]
            [(attach multihole-base   (rt cx) (u cy))
             (attach multihole-thread (rt cx) (u cy))]))
        peg-holes
        (for [i (range 1 x-cells)
              j (range 1 y-cells)]
          (let [px (- (* i cell-size) ox)
                py (- (* j cell-size) oy)]
            [(attach peg-hole-base   (rt px) (u py))
             (attach peg-hole-thread (rt px) (u py))]))
        x-bounds (for [i (range 1 x-cells)] (- (* i cell-size) ox))
        y-bounds (for [j (range 1 y-cells)] (- (* j cell-size) oy))
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
        ch-offset 3.2
        ch-margin (* size-offset 1.5)
        ch-y-len (- h (* 2 ch-margin))
        ch-x-len (- w (* 2 ch-margin))
        ch-right  (attach (cyl channel-r ch-y-len) (f slot-h-center)
                          (rt (- ox ch-offset)) (tv 90))
        ch-left   (attach (cyl channel-r ch-y-len) (f slot-h-center)
                          (rt (- ch-offset ox)) (tv 90))
        ch-top    (attach (cyl channel-r ch-x-len) (f slot-h-center)
                          (u (- oy ch-offset)) (tv -90) (th 90))
        ch-bottom (attach (cyl channel-r ch-x-len) (f slot-h-center)
                          (u (- ch-offset oy)) (tv -90) (th 90))
        corner-w 7.0
        corner-d 0.8
        so2 (* size-offset 0.5)
        hhh (* slot-height 0.7)
        corners
        [(attach (box corner-w corner-d hhh)
                 (f slot-h-center)
                 (rt (- ox so2)) (u (- oy so2)) (tr 45))
         (attach (box corner-w corner-d hhh)
                 (f slot-h-center)
                 (rt (- so2 ox)) (u (- oy so2)) (tr -45))
         (attach (box corner-w corner-d hhh)
                 (f slot-h-center)
                 (rt (- so2 ox)) (u (- so2 oy)) (tr 45))
         (attach (box corner-w corner-d hhh)
                 (f slot-h-center)
                 (rt (- ox so2)) (u (- so2 oy)) (tr -45))]

        all-cutters (vec (concat
                          (mapcat identity multiholes)
                          (mapcat identity peg-holes)
                          right-slots left-slots top-slots bottom-slots
                          [ch-right ch-left ch-top ch-bottom]
                          corners))
        _ (println (str "Cutters: " (count all-cutters) " meshes"))
        _ (println "--- cutter generation done, starting booleans ---")
        ;; ── BENCHMARK ───────────────────────────────────────────
        _ (println (str "Cutters: " (count all-cutters) " meshes"))

        ;; WASM path
        wasm-cutter (if WASM (bench "WASM union"
                                    #(mesh-union all-cutters)))
        wasm-tile   (if WASM (bench "WASM diff"
                                    #(mesh-difference base wasm-cutter)))

        ;; Native path
        native-cutter (if (not WASM) (bench "Native union"
                                            #(native-union all-cutters)))
        native-tile   (if (not WASM) (bench "Native diff"
                                            #(native-difference base native-cutter)))]

    (if WASM
      (do
        (println "---")
        (println (str "WASM result:   " (count (:vertices wasm-tile)) " verts, "
                      (count (:faces wasm-tile)) " faces"))
        (attach wasm-tile (tv 90)))
      (do
        (println (str "Native result: " (count (:vertices native-tile)) " verts, "
                      (count (:faces native-tile)) " faces"))
        (attach native-tile (tv 90))))

    ;; Show native result
    ))

;; ── Run ─────────────────────────────────────────────────────────
(register Tile (bench "Tile 15x15" #(multiboard-bench 15 15)))
(color :Tile 0xffffff)
