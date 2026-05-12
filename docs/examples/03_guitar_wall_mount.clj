;(def target :print)
(def target :gif)

(def r_manico 25)
(def l_manico 350)
(def handle-curve [32 -12 32])
(def m_paletta {:h 25
                :w 90
                :l 180
                :ang 12})
(def wall-angle 15)



(sdf-resolution! 200)



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
        slab (sdf-rounded-box Wp Hp Lp 1)
        ;; il centro della paletta lo metto a:
        ;;   x = l_manico/2 + (Lp/2)·cos(a)
        ;;   z = -(Lp/2)·sin(a) - Hp/2  (così il piano superiore coincide col piano della tastiera)
        cx (+ (/ l_manico 2) (* (/ Lp 2) (cos a)) -20)
        cz (- (- (* (/ Lp 2) (sin a))) (/ Hp 2))
        paletta (-> slab
                    (rotate :y (:ang m_paletta))
                    (translate cx 0 cz))
        body (sdf-blend manico paletta 5)
        c1 (attach
            (sdf-cyl 40 150)
            (u -30)
            (f -20)
            (rt 65))
        c2 (attach
            (sdf-cyl 40 150)
            (u -30)
            (f -20)
            (rt -65))]
    (sdf-blend-difference
     (translate body -160 0 -10)
     (sdf-union c1 c2) 2)))






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
        s1 (sdf-sphere (* r 1.3))]
    (sdf-blend
     (reduce sdf-union (map anello (range n)))
     (sdf-union
      (attach s1 (tv 90) (th (+ 180 (/ arc-deg 2))) (f R))
      (attach s1 (tv 90) (th (+ 180 (/ arc-deg -2))) (f R)))
     1)))

(def anello-mobile
  (rotate
   (translate (anelli 1 11 35 5 1 230 0) -21 0 -30)
   :y wall-angle))

(def struttura
  (sdf-blend
   (translate (anelli 1 11 35 5 1 230 0) -7 0 -30)
   (translate (anelli 1 30 40 10 0.8 220 0) 15 0 -20)
   4))

;(register AAA anello-mobile)

(def base
  (translate
   (sdf-blend-difference
    (translate
     (rotate
      (scale
       (turtle (tv -90) (sdf-clip (sdf-cyl 28 30)))
       1.9 2 3)
      :y -90)
     10 0 -105)
    (sdf-blend
     (translate
      (rotate
       (scale
        (turtle (tv -90) (sdf-clip (sdf-cyl 15 30)))
        1.5 2 3)
       :y -90)
      -13 0 -95)
     (translate
      (rotate
       (scale
        (turtle (tv -90) (sdf-clip (sdf-cyl 15 30)))
        1.5 2 3)
       :y -90)
      18 0 -85)
     2)
    3)
   5 0 0))

(def wallmount
  (sdf-difference
   (sdf-blend-difference
    (rotate
     (sdf-blend struttura base 1)
     :y wall-angle)
    (sdf-offset (chitarra-sdf) 1.5)
    2))) ; +1 mm di clearance per infilare la chitarra

(def hole
  (mesh-union
   (cyl 2 5 64)
   (attach (cone 8 2 5 64) (f 2.5))
   (attach (cyl 9 50 64) (f 28.5))))

;; Static support (no anello-mobile) so the moving ring can be animated
;; on its own without being baked into the wallmount mesh.
(def support
  (mesh-intersection
   (mesh-difference
    wallmount
    (mesh-union
     (concat-meshes
      (attach hole (f -40) (rt -40) (tv 90) (f -103))
      (attach hole (f -40) (rt 40) (tv 90) (f -103)))
     (sdf-offset anello-mobile 0.5)))
   (attach (box 180 110 210) (u -50))))


(defn do-export []
  ;; Export-only target: support + ring fused into a single print-in-place
  ;; mesh. `:hidden` keeps it out of the viewport (animations stay on the
  ;; two visible targets above). Uncomment the export form to write it.
  (register WallMountPrint (mesh-union support anello-mobile))
  (export :WallMountPrint :3mf))


(defn do-gif []

  (register chitarra (chitarra-sdf))
  (color :chitarra 0xffff55)
  ;; Visible/animated targets: support and ring registered separately.
  (register WallMount support)
  (register AnelloMobile anello-mobile)


  ;; ============================================================
  ;; ANIMATION
  ;; ============================================================
  ;; Fase 1 (t in [0, insertion-frac]): la chitarra trasla da
  ;;   `insertion-offset` lungo X fino alla sua posa finale (dx = 0).
  ;; Fase 2 (t in [insertion-frac, 1]): anello-mobile ruota attorno al
  ;;   proprio asse (X) attraverso il suo centro (-21, 0, -30).
  ;;
  ;; NOTA performance: `chitarra-sdf` è meshata ogni frame con
  ;; sdf-resolution 100. Se l'animazione risulta scattosa, abbassa la
  ;; risoluzione (es. (sdf-resolution! 60)) prima del blocco animazione.

  (defn ease-in-out [t]
    (if (< t 0.5)
      (* 2 t t)
      (- 1 (* 2 (- 1 t) (- 1 t)))))

  (defn segment01
    "Eased 0->1 as t goes from t0 to t1, clamped outside."
    [t t0 t1]
    (cond
      (<= t t0) 0.0
      (>= t t1) 1.0
      :else (ease-in-out (/ (- t t0) (- t1 t0)))))

  ;; --- knobs (tweak to taste) ---
  (def anim-duration 6.0) ; total seconds
  (def insertion-frac 0.5) ; fraction of timeline used for the whole insertion
  (def descent-frac 0.5) ; within the insertion: first portion is the Z drop
  (def insertion-offset 200) ; how far away the chitarra starts (along +X)
  (def insertion-offset-z 300) ; how far above its final pose the chitarra starts
  (def ring-rotation 60) ; degrees swept by anello-mobile

  ;; Insertion path:
  ;;   t = 0          -> (insertion-offset,  0, insertion-offset-z)   start, up & out
  ;;   t = z-end      -> (insertion-offset,  0, 0)                    after Z descent
  ;;   t = x-end      -> (0,                 0, 0)                    final pose
  (defn chitarra-frame [t]
    (let [z-end (* insertion-frac descent-frac)
          x-end insertion-frac
          uz (segment01 t 0 z-end)
          ux (segment01 t z-end x-end)
          dx (* insertion-offset (- 1 ux))
          dz (* insertion-offset-z (- 1 uz))]
      (sdf-ensure-mesh
       (translate (chitarra-sdf) dx 0 dz))))



  (defn anello-frame [t]
    (let [u (segment01 t insertion-frac 1.0)
          ang (* u ring-rotation)]
      (sdf-ensure-mesh
       (translate
        (rotate
         (rotate
          (anelli 1 11 35 5 1 230 0)
          :x ang)
         :y wall-angle)
        -21 0 -30))))


  (anim-proc! :insert anim-duration :chitarra :linear chitarra-frame)
  (anim-proc! :lock anim-duration :AnelloMobile :linear anello-frame)
  (play! :insert)
  (play! :lock)
  ;; Export both animations into a single GIF. With the multi-anim capture
  ;; patch in animation.cljs, every procedural anim in :playing state is
  ;; driven in lockstep at the same fractional t — so :lock animates too,
  ;; not just the named :insert. :overwrite true lets us re-run freely.
  (anim-export-gif "wallmount.gif"
                   :anim :insert
                   :fps 15
                   :width 720
                   :overwrite true))



(case target
  :print (do-export)
  :gif (do-gif))