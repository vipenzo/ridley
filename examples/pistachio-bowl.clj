; === Porta Pistacchi ===
;
; A two-piece pistachio bowl with rounded organic profiles.
;
; The outer bowl has a fluted twisted exterior. Both pieces share a
; single silhouette path (bezier-smoothed) that defines the curved
; profile — wider at the top, slightly narrower at the base.
;
; - Bowl exterior: loft-n + profile shape-fn (applies silhouette as
;   radial scale), fluted + twisted decorations.
; - Bowl cavity: loft-n + profile (same silhouette, smaller radius).
; - Tray outer wall: subpath-y to clip height range, offset-x to
;   inset, stroke-shape + revolve for smooth rounded profile.
; - Tray funnel: separate path + bezier-as + stroke-shape + revolve.
; - Tray floor: flat ring (poly + revolve) connecting wall to funnel.
;
; Dimensions: ~300mm diameter, ~180mm height.

; =====================
; Shared dimensions
; =====================
(def R 150)             ; outer bowl radius
(def H 180)             ; outer bowl height
(def wall 1.8)          ; wall thickness
(def tray-wall 1.8)     ; tray wall thickness
(def base-t 2)          ; base thickness

; =====================
; Shared silhouette
; =====================
; One path defines the profile for both bowl and tray.
; Goes from origin → base radius → curved wall → rim.
(def silhouette
  (path (f R) (th -60) (f (* H 0.3)) (th -5) (f (* H 0.5)) (th 8) (f (* H 0.2))))
(def smooth-sil (path (bezier-as silhouette)))

; =====================
; Outer bowl (ciotola)
; =====================
; Fluted + twisted exterior, cavity hollowed out.
; profile converts the silhouette into a shape-fn that scales
; the cross-section radius along the loft path.

(register bowl
  (attach
    (let [b (loft-n 96
              (-> (circle R 64)
                (fluted :flutes 24 :depth 0.12)
                (twisted :angle 40)
                (profile smooth-sil))
              (f H))
          hole (attach (loft-n 96
                  (-> (circle (- R wall) 64)
                    (profile smooth-sil))
                  (f (- H base-t))) (f base-t))]
      (mesh-difference b hole))
    (tv 90)))

; =====================
; Inner tray (vassoio)
; =====================
; Ring-shaped tray with central funnel for shell disposal.

(def tray-gap 0.4)                       ; gap between bowl and tray
(def tray-r (- R wall tray-gap))         ; tray outer radius (~147.8)
(def tray-h 130)                         ; tray height

; Funnel dimensions
(def funnel-r-top 45)                    ; funnel rim radius
(def funnel-r-bot 30)                    ; funnel base radius
(def funnel-wall 1.8)                    ; funnel wall thickness
(def funnel-h 90)                        ; funnel height
(def hole-r (- funnel-r-bot funnel-wall)) ; hole through floor (~28.2)

; --- Tray outer wall ---
; Same silhouette, clipped to tray height and inset to fit inside bowl.
(def tray-outer
  (revolve
    (stroke-shape
      (-> smooth-sil
        (subpath-y (+ base-t tray-gap) tray-h)
        (offset-x (- (+ wall tray-gap))))
      tray-wall
      :start-cap :flat :end-cap :round)))

; --- Funnel ---
; Slight outward taper from base to rim, bezier-smoothed.
(def funnel-path
  (path
    (f funnel-r-bot)
    (th -85)
    (f (* funnel-h 0.5))
    (th -5)
    (f (* funnel-h 0.5))))

(def tray-funnel
  (revolve
    (stroke-shape
      (path (bezier-as funnel-path))
      funnel-wall
      :start-cap :flat :end-cap :round)))

; --- Floor ---
; Flat ring connecting outer wall base to funnel base.
(def tray-floor
  (revolve
    (poly
      hole-r 0
      tray-r 0
      tray-r tray-wall
      hole-r tray-wall)))

; Hole through the floor for shells to fall into the bowl below.
(def floor-hole
  (revolve (poly 0 0  hole-r 0  hole-r tray-wall  0 tray-wall)))

; Assemble tray
(def tray
  (-> (mesh-union tray-outer tray-floor)
      (mesh-union tray-funnel)
      (mesh-difference floor-hole)))

; =====================
; Register both pieces
; =====================
(color 0x555555)
(register bowl bowl)

(color 0x88ddbb)
(register tray
  (attach tray (f (- H tray-h))))
