(def L 90)
(def H 22)
(def D 10)
(def Da 3)
(def La 10)



(def base-shape
  (-> (rect L H)
      (fillet-shape 5 :indices [2 3])))

(def base (attach (extrude base-shape (f D)) (cp-f (/ D 2))))

(def center-p (+ (/ La 2) (/ L 2)))

(def aletta
  (mesh-difference
   (box La Da D)
   (attach (mesh-union
            (attach (cone 5 2 3) (f 2))
            (cyl 1 4))
           (tv 90))))


(def sign
  (->
   "Nuovo Cinema UNI3"
   (text-shape :size 9)
   (extrude (f 0.6))
   (attach (th 180) (u -1) (rt (- 5 (/ L 2))) (f (/ D -2)))))

(def base (mesh-union
           (mesh-difference
            base
            sign)
           (attach aletta (u (- (/ Da 2) (/ H 2))) (rt (- center-p 0.1)))
           (attach aletta (u (- (/ Da 2) (/ H 2))) (rt (- (- center-p 0.1))))))

(register A base)

(register Scritta sign)

