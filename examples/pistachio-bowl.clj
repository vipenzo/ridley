; === Porta Pistacchi ===
;
; A two-piece pistachio bowl with rounded organic profiles.
;
; Both pieces share a single silhouette path (bezier-smoothed) that
; defines the curved profile — wider at the top, slightly narrower
; at the base.
;
; Both bowl and tray use revolve + stroke-shape on the same silhouette.
; This ensures uniform wall thickness and a constant gap between pieces:
;   gap = tray-offset - wall/2 - tray-wall/2 = tray-gap (everywhere)
;
; - Bowl: stroke-shape of full silhouette + revolve.
; - Tray outer wall: subpath-y to clip height range, offset-x to
;   inset, stroke-shape + revolve.
; - Tray funnel: separate path + bezier-as + stroke-shape + revolve.
; - Tray floor: flat ring (poly + revolve) connecting wall to funnel.
;
; Dimensions: ~240mm diameter, ~180mm height.

; =====================
; Shared dimensions
; =====================
(def tray-gap 0.4)
(def RBOTTOM 100) ; outer bowl bottom radius 
(def RTOP 150) ; outer bowl top radius (rim radius after fit)
(def H 120) ; outer bowl height
(def wall 3) ; wall thickness
(def tray-wall 1.8) ; tray wall thickness
(def tray-h 100) ; tray height
(def leap-w (/ (+ wall tray-wall tray-gap) 2))
(def leap-h 2)
; =====================
; Shared silhouette
; =====================
; One path defines the profile for both bowl and tray.
; Goes from origin → base radius → curved wall → rim.
(def silhouette
  (path (f RBOTTOM) (mark :CORNER) (th -60) (f (* H 0.3)) (th -5) (f (* H 0.5)) (th 8) (f (* H 0.2))))
(def smooth-sil (fit (path (bezier-as silhouette)) :x RTOP :y H))
;(stamp (path-to-shape smooth-sil) :color 0xff00ff)
;(stamp (poly 0 0 RTOP 0 RTOP H 0 H) :color 0x00ff00)
;(stamp (poly 0 0 RBOTTOM 0 RBOTTOM H 0 H) :color 0xfffff)



(resolution :n 128)




; =====================
; Inner tray (vassoio)
; =====================
; Ring-shaped tray with central funnel for shell disposal.

; Offset from silhouette centerline to tray centerline.
; Ensures constant gap = tray-gap between bowl inner surface and tray outer surface.
(def tray-offset (+ (/ wall 2) tray-gap (/ tray-wall 2))) ; = 2.8

; Funnel dimensions
(def funnel-r-top 55) ; funnel rim radius
(def funnel-r-bot 30) ; funnel base radius
(def funnel-handle 15)
(def funnel-wall 1.8) ; funnel wall thickness
(def funnel-h (+ tray-h 50)) ; funnel height (match tray wall)
(def hole-r (- funnel-r-bot funnel-wall)) ; hole through floor (~28.2)


; --- Tray outer wall ---
; Same silhouette, clipped to tray height and inset by tray-offset.
(def tray-silhouette
  (fit
   (stroke-shape
    (-> smooth-sil
        (subpath-y (- H tray-h) H)
        (offset-x (- tray-offset)))
    tray-wall
    :start-cap :flat :end-cap :round)
   :y tray-h))

(def tray-outer
  (revolve tray-silhouette))


; Leap: ledge/groove for tray-bowl interlock at the rim.
; Bowl groove (bowl coords: Y up to H) — offset by 1mm for clearance.
; Tray flange (tray coords: Y up to tray-h) — protrudes from tray wall.
(def leap-path
  (let [p1 (- (:width (bounds tray-silhouette)) (/ tray-wall 2))
        h (:y-max (bounds tray-silhouette))]
    (poly p1 h p1 (- h leap-h) (+ p1 leap-w) (- h leap-h) (+ p1 leap-w) h)))

(def bowl-silhouette (stroke-shape smooth-sil wall
                                   :start-cap :round :end-cap :round))

(def bowl-leap
  (attach
   (revolve
    (shape-offset leap-path tray-gap))
   (u (- (:y-max (bounds bowl-silhouette))
         (:y-max (bounds leap-path))))))


; =====================
; Outer bowl (ciotola)
; =====================
; Bowl wall = revolve of stroke-shape around the silhouette.
; Uniform wall thickness everywhere, base included.

(register bowl
          (mesh-difference
    ;(revolve bowl-silhouette)
           (revolve (displaced bowl-silhouette
                               (fn [p t] (* 3.5 (pow (sin (* t 12 PI)) 2)))))
           bowl-leap))


(def tray-leap
  (revolve leap-path))



; --- Funnel ---
; Slight outward taper from base to rim, bezier-smoothed.
(def funnel-path
  (poly 0 0
        funnel-r-bot 0
        funnel-r-top funnel-h
        0 funnel-h))


(def tray-funnel
  (revolve
   (path-to-shape
    (path
     (bezier-as
      (let [w (+ funnel-r-bot wall)]
        (poly-path-closed 0 0
                          w 0
                          w (* funnel-h 0.3)
                          w (* funnel-h 0.4)
                          w (* funnel-h 0.4)
                          (+ funnel-r-top funnel-handle) (* funnel-h 0.8)
                          0 funnel-h)))))))



; --- Floor ---
; Flat ring connecting outer wall base to funnel base.
(def tray-floor
  (revolve
   (poly
    hole-r 0
    (mark-x smooth-sil :CORNER) 0
    (mark-x smooth-sil :CORNER) tray-wall
    hole-r tray-wall)))

; Hole through the floor for shells to fall into the bowl below.
(def floor-hole
  (revolve
   (path-to-shape
    (path
     (bezier-as
      (let [w (+ funnel-r-bot)
            h (+ funnel-h 5)]
        (poly-path-closed 0 0
                          w 0
                          w (* h 0.3)
                          w (* h 0.4)
                          w (* h 0.4)
                          (+ funnel-r-bot funnel-handle 20) (* h 0.8)
                          0 (+ h 30))))))))

; Assemble tray
(def tray
  (-> (mesh-union tray-outer tray-floor tray-leap)
      (mesh-union (attach tray-funnel (d 1.5)))
      (mesh-difference (attach floor-hole (d 5)))))

; =====================
; Register tray
; =====================
; Move tray up so its base sits inside the bowl at the correct height.
; The bowl base inner surface is at ~wall/2 from Y=0.
; The tray spans tray-h, positioned at Y = (H - tray-h).

(register tray (attach tray (u (- (get-in (bounds bowl) [:max 2])
                                  (get-in (bounds tray-leap) [:max 2])))))
;(hide :bowl)
;(hide :tray)
;(def bowl-slice (slice-mesh (get-mesh :bowl)))
;(def tray-slice (slice-mesh (get-mesh :tray)))
;(stamp bowl-slice)
;(stamp tray-slice)

(color :bowl 0xeeefee)
;(material :bowl :opacity 0.5)
(color :tray 0x88ff80)




