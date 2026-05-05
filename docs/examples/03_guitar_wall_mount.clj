(def r_manico 20)
(def l_manico 350)
(def m_paletta {:h 55
                :w 140
                :l 180
                :ang 14})

(sdf-resolution! 200)

(defn chitarra-sdf []
  ;; sdf-box / sdf-rounded-box prendono argomenti in ordine (right, up, heading) → (Y, Z, X)
  ;; quindi i tre args mappano direttamente alle dimensioni "trasversa, verticale, longitudinale".
  (let [;; --- manico: mezzo cilindro lungo X (heading), parte piatta verso +Z ---
        cilindro (sdf-rotate (sdf-cyl r_manico l_manico) :y 90)
        ;; semispazio z<=0 per tenere solo la metà bassa (= dorso del manico)
        sotto (sdf-move (sdf-box 1000 (* 2 r_manico) 1000)
                        0 0 (- r_manico))
        manico (sdf-intersection cilindro sotto)

        ;; --- paletta: slab arrotondato, ruotato di -ang attorno a Y, in coda al manico ---
        a (to-radians (:ang m_paletta))
        Lp (:l m_paletta)
        Wp (:w m_paletta)
        Hp (:h m_paletta)
        ;; argomenti: (w → right=Y, h → up=Z, l → heading=X)
        slab (sdf-rounded-box Wp Hp Lp 6)
        ;; il centro della paletta lo metto a:
        ;;   x = l_manico/2 + (Lp/2)·cos(a)
        ;;   z = -(Lp/2)·sin(a) - Hp/2  (così il piano superiore coincide col piano della tastiera)
        cx (+ (/ l_manico 2) (* (/ Lp 2) (cos a)) 20)
        cz (- (- (* (/ Lp 2) (sin a))) (/ Hp 2))
        paletta (-> slab
                    (sdf-rotate :y (- (:ang m_paletta)))
                    (sdf-move cx 0 cz))
        body (sdf-blend manico paletta 7)]
    (sdf-move body -170 0 0))) ; k=8 ≈ raggio del raccordo



(defn anelli
  "Tubo X: n anelli paralleli, passo p, R maggiore, r minore."
  [n p R r]
  (let [x0 (- (* (/ (dec n) 2) p))
        anello (fn [i]
                 (let [base (sdf-rotate (sdf-scale (sdf-torus R r) 1 1.5 0.4) :y 90) ; piano YZ
] ; piano XZ
                   (sdf-move base (+ x0 (* i p)) 0 0)))]
    (reduce sdf-union (map anello (range n)))))

(def struttura
  (sdf-blend
   (anelli 6 10 20 12)
   (sdf-move (anelli 1 30 30 15) 40 0 -20)
   10))


(def ellip
  (sdf-move
   (sdf-rotate
    (sdf-scale (sdf-cyl 40 150) 1.6 1.8 3)
    :y 90)
   0 0 95))

(def base
  (sdf-move
   (sdf-rotate
    (sdf-scale
     (sdf-intersection
      (sdf-cyl 35 70)
      (sdf-move (sdf-box 200 200 200) 100 0 100))
     1.5 2 3)
    :y 90)
   50 0 -85))

(def wallmount
  (sdf-difference
   (sdf-blend-difference
    (sdf-blend struttura base 5)
    (sdf-blend
     (sdf-move
      (sdf-offset (chitarra-sdf) 1.5)
      6 0 0)
     ellip
     7)
    2))) ; +1 mm di clearance per infilare la chitarra

(def hole
  (mesh-union
   (cyl 2 5 64)
   (attach (cone 8 2 5 64) (f 2.5))
   (attach (cyl 10 100 64) (f 52.5))))

(register WallMount
          (mesh-intersection
           (mesh-difference
            wallmount
            (concat-meshes
             (attach hole (f -40) (rt 50) (tv 90) (f -83))
             (attach hole (f -40) (rt -50) (tv 90) (f -83))
             (attach hole (f 30) (tv 90) (f -83))))
           (attach (box 180 110 180) (u -30))))

;(register chitarra (chitarra-sdf))
;(color :chitarra 0xffff55)
;(export :WallMount :3mf)  