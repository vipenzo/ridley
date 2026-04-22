;; Pipe clamp for 30mm wooden poles (garden trellis)
;; Square base plate + open C-ring clamp

;; --- Parameters ---
(def wall 3) ; wall thickness
(def base-side-y 35) ; base plate size
(def base-side-z 70) ; base plate size
(def base-h 5) ; base plate height
(def screw-d 5) ; screw hole diameter
(def screw-inset 5) ; hole distance from edge
(def corner-r 4) ; base plate corner radius

;; Derived
(defn neg [x] (* -1 x))

;; --- Base plate ---
(def hole (circle (/ screw-d 2) 16))
(def si-y (- (/ base-side-y 2) screw-inset))
(def si-z (- (/ base-side-z 2) screw-inset))

(def base-shape
  (fillet-shape (rect base-side-y base-side-z) corner-r))

(def base (-> base-shape
              (extrude (f base-h))
              (chamfer :top 1.5)))

(def screw-hole
  (loft (capped hole -1.5 :start false :fraction 0.4) (f (+ base-h 1))))

(def base-mesh
  (mesh-difference
   base
   (concat-meshes
    (for [x [1 -0.3] y [1 -1]]
      (attach screw-hole (u (* si-z x)) (rt (* si-y y)))))))
(def base2 (attach base-mesh (tv 90)))
(def base1 (attach base-mesh (tv 90)))
;; --- Assembly ---
(def d0 8)
(def d1 8)

(def A2 (attach base2
                (f (- (+ (/ base-side-z 2) d1 d0)))
                (u (- (+ (/ base-side-z 2) d1 d0)))
                (tr 180)))
(def A1 (attach base1
                (f (- (+ (/ base-side-z 2) d1 d0)))
                (u (+ (+ (/ base-side-z 2) (- d1) d0)))))

(def le (/ base-side-y 5))
(def l0 (- (+ (/ base-side-y 2) (+ (/ d0 -2) 1))))
(def radius 8)

(defn perno [cut]
  (attach (cyl (/ (if cut (+ radius 0.8) radius) 2)
               (if cut (* 2 base-side-y) base-side-y))
          (tv 90)))
(defn el-c [o cut]
  (let [m (mesh-union
           (attach (cyl radius (- (/ base-side-y 5) 0.5)) (tv 90))
           (attach (box (* 1.5 radius) (dec le) base-h)
                   (f (if o radius (- radius)))
                   (u (- (* base-h 1.1)))))]
    (if cut (mesh-difference m (perno true)) m)))

(def B1 (mesh-union
         (for [i [0 2 4]]
           (attach (el-c true false) (rt (+ l0 (* le i)))))))
(def B2 (mesh-union
         (for [i [1 3]]
           (attach (el-c false true) (rt (+ l0 (* le i)))))))

(def CC1 (mesh-union
          (attach B1
                  (u (- (+ (/ base-side-z 2) d1)))
                  (f d0))
          (attach (perno false)
                  (u (- (+ base-side-y radius)))
                  (f radius))
          A2))

(def CC2 (mesh-union
          (attach B2
                  (u (- (+ (/ base-side-z 2) d1)))
                  (f d0))
          A1))

(register cerniera
          (attach (concat-meshes CC1 CC2)
                  (u (+ base-side-y (* 2 radius)))))

