;; --- parametri funzionali ---
(def listello-w 40)
(def listello-d 60)
(def panel-mount-thickness 4) ; pareti C esterna
(def inner-wall 3) ; pareti C interna
(def gap 0.3) ; tolleranza C-su-C, sia radiale che verticale
(def H 80)

;; --- parametri derivati ---
(def inner-w (+ listello-w (* 2 inner-wall)))
(def inner-d (+ listello-d (* 2 inner-wall)))
(def outer-w (+ listello-w (* 2 inner-wall) (* 2 gap) (* 2 panel-mount-thickness)))
(def outer-d (+ listello-d (* 2 inner-wall) (* 2 gap) (* 2 panel-mount-thickness)))
(def outer-h H)
(def pin-d 15)
(def pin-hole-d (+ pin-d (* 2 gap)))

(resolution :n 64)

;; --- scaffold
(color 0x888888)
(def pannello (extrude (rect 1000 600) (f -15)))
;(register Pannello pannello)
(def gamba (extrude (rect listello-w listello-d) (f 600)))
(register Gamba (attach gamba (u 190) (f 30.5) (u 20) (tv -50)))

(color 0x00ff00)
(def hinge-inner-body
  (attach
   (mesh-union
    (attach (cyl pin-d (+ 4 outer-w))
            (th 90)
            (rt (- (/ H 2))))
    (mesh-difference
     (box inner-w inner-d H)
     (mesh-union
      (attach (box (- inner-w (* 2 inner-wall)) inner-d H) (u inner-wall))
      (attach (cone 5 2 (+ inner-wall 2))
              (rt (- (+ (/ inner-w 2) -1)))
              (f 15)
              (th 90))
      (attach (cone 5 2 (+ inner-wall 2))
              (rt (+ (/ inner-w 2) -1))
              (f 15)
              (th -90))))
    (attach (cyl (/ inner-d 2) inner-w 64)
            (th 90)
            (rt (- (/ H 2)))))
   (cp-f (- (/ H 2)))))
;; --- pezzi ---
(def staffa
  (attach
   (mesh-difference
    (extrude
     (shape (f listello-w) (th 90) (f listello-w) (th 135))
     (f 22))
    (attach (cone 25 2 40)
            (f 11)
            (u 20)
            (rt 15)
            (tv 90)))
   (u (- (/ outer-d 2)))
   (f 18)))





(def staffe (concat-meshes
             (for [x [10 -80] y [true false]]
               (attach staffa
                       (f (+ x (if y 22 0)))
                       (rt (* (if y 1 -1) (+ listello-w (/ outer-w 2) -0.5)))
                       (th (if y 180 0))))))


(def hplus (+ H (/ inner-d 2)))
(def hplusplus (+ hplus 20))
(def hinge-outer-body
  (attach
   (mesh-union
    staffe
    (mesh-difference
     (box outer-w outer-d hplusplus)
     (mesh-union
      (attach (box outer-w (+ (* 2 inner-wall) 2) hplusplus) (u (/ outer-d 2)))
      (box (+ listello-w (* 2 inner-wall) (* 2 gap)) (+ listello-d (* 2 inner-wall) (* 2 gap)) hplusplus))
     (attach (cyl pin-hole-d (+ 4 outer-w))
             (th 90)
             (rt (/ hplusplus 2))
             (rt (- (/ (- hplusplus (+ H (/ inner-d 2))) 2)))
             (rt (- H)))))

   (f (- (/ inner-d 4))))) ; C esterna con perno integrato e fori per viti pannello


(defn hinge []
  (mesh-union
   hinge-outer-body
   hinge-inner-body)) ; assembly in-place: outer + inner posizionata in riposo


(register Hinge (hinge))
;(register A hinge-outer-body)
;(register B hinge-inner-body)
;(register C (mesh-intersection hinge-outer-body hinge-inner-body))