;; Gridfinity bin — versione parametrica
;;
;; (make-bin r c h cavity)
;;   r       righe (cells lungo y, profondità sulla baseplate)
;;   c       colonne (cells lungo x)
;;   h       altezza in unità Gridfinity (1u = 7mm + 4.4mm di overhead = 11.4mm)
;;   cavity  true → bin svuotato col lip standard; false → solido (utile per
;;           ricavarsi cavità custom con mesh-difference da forme arbitrarie)
;;
;; Esempi:
;;   (register bin1   (make-bin 1 1 2 true))   ; classico 1×1×2u
;;   (register bin2   (make-bin 2 3 3 true))   ; 2 righe × 3 colonne, 3u alto
;;   (register blank  (make-bin 1 2 2 false))  ; solido 1×2 per cavità custom

(defn make-bin [r c h cavity]
  (when-not (and (integer? r) (pos? r))
    (throw (ex-info (str "make-bin: r (righe) deve essere intero positivo, ricevuto " (pr-str r)) {:r r})))
  (when-not (and (integer? c) (pos? c))
    (throw (ex-info (str "make-bin: c (colonne) deve essere intero positivo, ricevuto " (pr-str c)) {:c c})))
  (when-not (and (integer? h) (pos? h))
    (throw (ex-info (str "make-bin: h (unità altezza) deve essere intero positivo, ricevuto " (pr-str h)) {:h h})))
  (let [grid     42.0
        outer-w  (- (* c grid) 0.5)
        outer-d  (- (* r grid) 0.5)

        wall-thick  1.0
        floor-thick 1.0   ; piastra continua sopra i socket che lega le celle

        ;; Base socket profile (per spec — "Enlarged Design / Profile")
        base-cham-1 0.8
        base-vert   1.8
        base-cham-2 2.15
        base-h      (+ base-cham-1 base-vert base-cham-2)        ; 4.75

        ;; Lip cavity profile (per spec — "Enlarged Stacking Lip")
        lip-vert-low 1.9
        lip-cham     1.8
        lip-vert-top 0.7
        rim-thick    0.25
        lip-total-h  (+ lip-vert-low lip-cham lip-vert-top)      ; 4.4

        ;; Altezze
        body-h        (- (+ (* 7 h) 4.4) base-h)
        floor-h       (+ base-h floor-thick)
        main-cavity-h (- body-h floor-thick lip-total-h)

        big-r 3.75

        ;; --- Shapes del socket di base (per-cella, sempre 1u = 41.5mm) ---
        unit-outer 41.5
        u-w-narrow (- unit-outer (* 2 (+ base-cham-1 base-cham-2)))   ; 35.6
        u-w-mid    (+ u-w-narrow (* 2 base-cham-1))                   ; 37.2
        u-mid-r    (- big-r base-cham-2)                              ; 1.6
        u-small-r  (- u-mid-r base-cham-1)                            ; 0.8

        unit-bottom-shape (fillet-shape (rect u-w-narrow u-w-narrow) u-small-r)
        unit-mid-shape    (fillet-shape (rect u-w-mid    u-w-mid)    u-mid-r)
        unit-full-shape   (fillet-shape (rect unit-outer unit-outer) big-r)

        ;; --- Shape del corpo (footprint esterno completo) ---
        body-shape (fillet-shape (rect outer-w outer-d) big-r)

        ;; --- Shapes della cavità (non-quadrata in generale) ---
        w-lip-top-x    (- outer-w (* 2 rim-thick))
        w-lip-top-y    (- outer-d (* 2 rim-thick))
        w-lip-narrow-x (- w-lip-top-x (* 2 lip-cham))
        w-lip-narrow-y (- w-lip-top-y (* 2 lip-cham))
        w-main-x       (- outer-w (* 2 wall-thick))
        w-main-y       (- outer-d (* 2 wall-thick))

        main-r       (- big-r wall-thick)
        lip-narrow-r (- big-r rim-thick lip-cham)
        lip-top-r    (- big-r rim-thick)

        main-cavity-shape (fillet-shape (rect w-main-x       w-main-y)       main-r)
        lip-narrow-shape  (fillet-shape (rect w-lip-narrow-x w-lip-narrow-y) lip-narrow-r)
        lip-top-shape     (fillet-shape (rect w-lip-top-x    w-lip-top-y)    lip-top-r)

        ;; Quote (lungo heading = "alto" del bin)
        z1  base-cham-1
        z2  (+ z1 base-vert)
        z3  (+ z2 base-cham-2)
        cz0 main-cavity-h                ; quota del dente lip↔main
        cz1 (+ cz0 lip-vert-low)
        cz2 (+ cz1 lip-cham)

        ;; Socket di base costruito all'origine, poi traslato a ogni cella
        unit-base (mesh-union
                   (loft unit-bottom-shape unit-mid-shape (f base-cham-1))
                   (attach (extrude unit-mid-shape (f base-vert)) (f z1))
                   (attach (loft unit-mid-shape unit-full-shape (f base-cham-2)) (f z2)))

        ;; Posizioni centri-cella (bin centrato sull'origine)
        cell-positions (for [col (range c)
                             row (range r)]
                         [(* grid (- col (/ (- c 1) 2.0)))
                          (* grid (- row (/ (- r 1) 2.0)))])

        ;; Tutti i socket nelle rispettive celle (reduce perché mesh-union è macro)
        bases (reduce (fn [a b] (mesh-union a b))
                      (for [[cx cy] cell-positions]
                        (attach unit-base (rt cx) (u cy))))

        ;; Corpo sopra la base
        body (attach (extrude body-shape (f body-h)) (f z3))

        shell (mesh-union bases body)

        cavity-mesh
        (mesh-union
         (extrude main-cavity-shape (f main-cavity-h))
         (attach (extrude lip-narrow-shape (f lip-vert-low)) (f cz0))
         (attach (loft lip-narrow-shape lip-top-shape (f lip-cham)) (f cz1))
         (attach (extrude lip-top-shape (f lip-vert-top)) (f cz2)))]

    (if cavity
      (mesh-difference shell (attach cavity-mesh (f floor-h)))
      shell)))

(register bin (make-bin 1 1 2 true))
