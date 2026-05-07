(def r_manico 25)
(def l_manico 350)
(def m_paletta {:h 21
                :w 100
                :l 180
                :ang 14})

(sdf-resolution! 100)

(defn chitarra-sdf []
  ;; sdf-box / sdf-rounded-box prendono argomenti in ordine (right, up, heading) → (Y, Z, X)
  ;; quindi i tre args mappano direttamente alle dimensioni "trasversa, verticale, longitudinale".
  (let [;; --- manico: mezzo cilindro lungo X (heading), parte piatta verso +Z ---
        cilindro (rotate (sdf-cyl r_manico l_manico) :y 90)
        ;; semispazio z<=0 per tenere solo la metà bassa (= dorso del manico)
        sotto (turtle (tv 90) (sdf-half-space))
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
                    (rotate :y (- (:ang m_paletta)))
                    (translate cx 0 cz))
        body (sdf-blend manico paletta 7)]
    (translate body -170 0 0))) ; k=8 ≈ raggio del raccordo



(defn torus-arc
  "Arco di toro: arc-deg in [0°, 360°], centrato su +X, asse Z."
  [R r arc-deg arc-rot]
  (let [half (/ arc-deg 2)
        h1 (turtle (th (+ 90 half)) (sdf-half-space))
        h2 (turtle (th (- (+ 90 half))) (sdf-half-space))]
    (rotate
     (sdf-intersection
      (sdf-torus R r)
      (if (<= arc-deg 180)
        (sdf-intersection h1 h2) ; cuneo "stretto"
        (sdf-union h1 h2))) ; complemento del cuneo "stretto"
     :z arc-rot)))

(defn anelli [n p R r sz arc-deg arc-rot]
  (let [x0 (- (* (/ (dec n) 2) p))
        anello (fn [i]
                 (let [base (rotate (scale (torus-arc R r arc-deg arc-rot) sz 1 1) :y 90)]
                   (translate base (+ x0 (* i p)) 0 0)))
        s1 (sdf-sphere r)]
    (reduce sdf-union (map anello (range n)))))

(defn anelliA [n p R r sz arc-deg arc-rot]
  (let [x0 (- (* (/ (dec n) 2) p))
        anello (fn [i]
                 (let [base (rotate (scale (torus-arc R r arc-deg arc-rot) sz 1 1) :y 90)]
                   (translate base (+ x0 (* i p)) 0 0)))
        s1 (sdf-sphere r)]
    (sdf-blend
     (reduce sdf-union (map anello (range n)))
     (attach s1 (tv 90) (th (/ arc-deg -2)) (f -30))

     1)))


(def anello-mobile
  (translate (anelliA 1 11 45 5 1 216 180) -13 0 -20))

(def struttura
  (sdf-blend
   (translate (anelli 1 11 45 5 1 270 180) -3 0 -20)
   (translate (anelli 1 30 50 15 0.8 270 180) 15 0 -20)
   1))

(register AAA anello-mobile)

(def ellip
  (translate
   (rotate
    (scale (sdf-cyl 40 100) 1.1 1 1)
    :y 90)
   0 0 30))


(def base
  (translate
   (rotate
    (scale
     (turtle (tv -90) (sdf-clip (sdf-cyl 35 70)))
     1.5 2 3)
    :y 90)
   50 0 -85))

(def wallmount
  (sdf-difference
   (sdf-blend-difference
    (sdf-blend struttura base 1)
    (sdf-blend
     (sdf-offset (chitarra-sdf) 1.5)
     ellip
     1.1)
    1))) ; +1 mm di clearance per infilare la chitarra

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

(register chitarra (chitarra-sdf))
(color :chitarra 0xffff55)
;(export :WallMount :3mf)      