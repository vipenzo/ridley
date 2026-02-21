; === Porta Pistacchi — Procedural ===
;
; A two-piece pistachio bowl with rounded organic profiles.
; Fully parametric: each function captures one aspect of the design.
;
; Pipeline:
;   measures          → compute all derived dimensions
;   make-profile      → shared curved silhouette path
;   make-tray-profile → tray wall cross-section
;   make-leap         → interlock ledge between bowl and tray
;   make-bowl         → outer bowl with groove
;   make-tray         → inner tray (wall + funnel + floor)
;   pistachio-bowl    → assemble + position both pieces

; =====================
; Parameters
; =====================

(def default-essential-parameters
  {:rtop 100 ; bowl rim radius (overall size)
   :h 60 ; bowl height
   :wall 2; bowl wall thickness
   :tray-gap 0.4 ; gap between bowl and tray (print tolerance)
   :min-hole 13 ; minumum size of center hole
   })

(def default-proportions
  {:bottom-ratio 0.67 ; base radius / rim radius
   :tray-ratio 0.683 ; tray height / bowl height
   :funnel-ratio 0.55 ; funnel top radius / base radius
   :taper-ratio 0.535 ; funnel bottom / funnel top
   :funnel-height-ratio 0.162 ;tray-height / funnel height
   :funne-handle-ratio 0.127 ; funnel top / handle top
   })

; =====================
; Derived measures
; =====================
; Computes all derived dimensions from essential parameters and proportions.

(defn measures [essential proportions]
  (let [m (merge essential proportions)
        rbottom (* (:rtop m) (:bottom-ratio m))
        tray-h (* (:h m) (:tray-ratio m))
        tray-wall (* (:wall m) 0.6)
        tray-offset (+ (/ (:wall m) 2) (:tray-gap m) (/ tray-wall 2))
        funnel-r-top (* rbottom (:funnel-ratio m))
        funnel-wall tray-wall
        funnel-r-bot (max (+ (:min-hole m) funnel-wall) (* funnel-r-top (:taper-ratio m)))
        funnel-handle (* funnel-r-top (:funne-handle-ratio m))
        funnel-h (+ tray-h (* (:h m) (:funnel-height-ratio m)))
        hole-r (- funnel-r-bot funnel-wall)
        leap-w (/ (+ (:wall m) tray-wall (:tray-gap m)) 2)
        leap-h 1.2
        r-tacco (* rbottom 1.044)
        h-tacco (* (:h m) 0.05)
        d-tacco (* (:h m) -0.050)]
    (merge m
           {:rbottom rbottom
            :tray-h tray-h
            :tray-wall tray-wall
            :tray-offset tray-offset
            :funnel-r-top funnel-r-top
            :funnel-r-bot funnel-r-bot
            :funnel-wall funnel-wall
            :funnel-handle funnel-handle
            :funnel-h funnel-h
            :hole-r hole-r
            :leap-w leap-w
            :leap-h leap-h
            :r-tacco r-tacco
            :h-tacco h-tacco
            :d-tacco d-tacco})))

; =====================
; Profile
; =====================
; The shared curved silhouette. Both bowl and tray derive from this.
; Goes from origin -> base radius -> curved wall -> rim.

(defn make-profile [m]
  (let [sil (path (f (:rbottom m))
                  (mark :CORNER)
                  (th -60) (f (* (:h m) 0.3))
                  (th -5) (f (* (:h m) 0.5))
                  (th 8) (f (* (:h m) 0.2)))]
    (fit (path (bezier-as sil)) :x (:rtop m) :y (:h m))))

; =====================
; Tray wall profile
; =====================
; Same silhouette, clipped to tray height and inset by tray-offset.
; Ensures constant gap between bowl inner surface and tray outer surface.

(defn make-tray-profile [m smooth-sil]
  (fit
   (stroke-shape
    (-> smooth-sil
        (subpath-y (- (:h m) (:tray-h m)) (:h m))
        (offset-x (- (:tray-offset m))))
    (:tray-wall m)
    :start-cap :flat :end-cap :round)
   :y (:tray-h m)))

; =====================
; Leap — interlock ledge
; =====================
; Small ledge at the rim for tray-bowl alignment.
; Returns a shape (2D cross-section) to be revolved.

(defn make-leap [m tray-sil]
  (let [p1 (- (get-in (bounds tray-sil) [:size 0])
              (/ (:tray-wall m) 2))
        h (get-in (bounds tray-sil) [:max 1])]
    (poly p1 h
          p1 (- h (:leap-h m))
          (+ p1 (:leap-w m)) (- h (:leap-h m))
          (+ p1 (:leap-w m)) h)))

; =====================
; Bowl
; =====================
; Revolve of stroke-shape with optional decorative displacement.
; Groove carved for tray interlock.

(defn make-bowl
  ([m smooth-sil leap-path]
   (make-bowl m smooth-sil leap-path
              (fn [p t] (* 3.5 (pow (sin (* t 12 PI)) 2)))))
  ([m smooth-sil leap-path displace-fn]
   (let [bowl-sil (stroke-shape smooth-sil (:wall m)
                                :start-cap :round :end-cap :round)
         bowl-leap (attach
                    (revolve (shape-offset leap-path (:tray-gap m)))
                    (u (- (get-in (bounds bowl-sil) [:max 1])
                          (get-in (bounds leap-path) [:max 1]))))
         bowl-body (if displace-fn
                     (revolve (displaced bowl-sil displace-fn))
                     (revolve bowl-sil))]
     (mesh-union
      (mesh-difference bowl-body bowl-leap)
      (attach (cyl (:r-tacco m) (:h-tacco m)) (tv 90) (f (:d-tacco m)))))))

; =====================
; Tray
; =====================
; Ring-shaped tray with central funnel for shell disposal.
; Returns {:tray mesh :tray-leap mesh} — leap needed for positioning.

(defn make-funnel-shape [m]
  (let [p (path-to-shape
           (path
            (bezier-as
             (let [w-in (:funnel-r-bot m)
                   w (+ w-in (:wall m))
                   x-max (+ (:funnel-r-top m) (:funnel-handle m))
                   x-in-max (- x-max (:wall m))]
               (poly-path-closed
                w-in 0
                w 0
                w (* (:funnel-h m) 0.3)
                w (* (:funnel-h m) 0.4)
                w (* (:funnel-h m) 0.7)
                x-max (* (:funnel-h m) 0.96)
                (+ w-in (:wall m)) (* (:funnel-h m) 0.8)
                w-in (* (:funnel-h m) 0.7)
                w-in (* (:funnel-h m) 0.69))))))]
    p))

(defn make-floor-hole-profile [m]
  (path-to-shape
   (path
    (let [w (:funnel-r-bot m)
          h (* 4 (:wall m))]
      (poly-path-closed 0 0
                        w 0
                        w h
                        0 h)))))

(defn make-tray [m smooth-sil tray-sil leap-path]
  (let [tray-outer (revolve tray-sil)
        tray-leap (revolve leap-path)
        ; Funnel: slight outward taper, bezier-smoothed
        tray-funnel (revolve (make-funnel-shape m))
        ; Floor: flat ring connecting outer wall base to funnel base
        tray-floor (revolve
                    (poly
                     (:hole-r m) 0
                     (mark-x smooth-sil :CORNER) 0
                     (mark-x smooth-sil :CORNER) (:tray-wall m)
                     (:hole-r m) (:tray-wall m)))
        ; Hole: carved through the floor for shells to fall into the bowl
        floor-hole (revolve (make-floor-hole-profile m))
        ; Assemble tray
        tray (-> (mesh-union tray-outer tray-floor tray-leap (attach tray-funnel (d 0.5)))
                 (mesh-difference (attach floor-hole (d 5))))]
    {:tray tray :tray-leap tray-leap}))

; =====================
; Assembly
; =====================
; Builds both pieces, positions tray inside bowl.
; Overrides merge into both essential and proportions
; (e.g. :rtop 80 :h 60 :bottom-ratio 0.7).

(defn pistachio-bowl
  ([] (pistachio-bowl {}))
  ([overrides]
   (let [m (measures (merge default-essential-parameters overrides)
                     (merge default-proportions overrides))
         smooth-sil (make-profile m)
         tray-sil (make-tray-profile m smooth-sil)
         leap-path (make-leap m tray-sil)
         bowl (make-bowl m smooth-sil leap-path)
         {:keys [tray tray-leap]} (make-tray m smooth-sil tray-sil leap-path)
         tray (attach tray
                      (u (- (get-in (bounds bowl) [:max 2])
                            (get-in (bounds tray-leap) [:max 2]) 2.7)))]
     {:bowl bowl :tray tray})))

; =====================
; Build and register
; =====================

(resolution :n 128)

(def debug false)
(if debug
  (do
    (resolution :n 32)
    (let [params (measures default-essential-parameters default-proportions)]
      (case debug
        :profile (tweak :all (make-profile params))
        :funnel (tweak :all (make-funnel-shape params))
        :hole (make-floor-hole-profile params)
        :slices (let [{:keys [bowl tray]} (pistachio-bowl)]
                  (stamp (slice-mesh tray))
                  (stamp (slice-mesh bowl)))
        (tweak :all (concat-meshes (vals (pistachio-bowl
                                          params)))))))
  (let [{:keys [bowl tray]} (pistachio-bowl)]
    (register bowl bowl)
    (register tray tray)
    (color :bowl 0xeeefee)
    ;(material :bowl :opacity 0.5)
    (color :tray 0xeeff80)))



; =====================
; Animation
; =====================
(def animation true)
(when animation
  (let [{:keys [rtop h]} default-essential-parameters]
    (anim! :tray-out 6.5 :tray :loop-bounce
           (span 0.20 :ease-out (u (* h 1.2)))
           (span 0.15 :in-out (rt (* rtop 2.5)))
           (span 0.15 :in-out (tr 100))
           (span 0.15 :in-out (tr -100))
           (span 0.35 :ease-in (d (* h 1.8))))
    (play! :tray-out)))
