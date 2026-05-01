;; cerniera2_B — Variante "veramente assemblata" di cerniera2.
;;
;; A differenza di cerniera2_A (dove i due pezzi della cerniera sono
;; costruiti già co-posizionati nel world frame), qui ognuno è definito
;; nel proprio sistema di riferimento, indipendente. La creation-pose di
;; ciascun pezzo viene calata sull'asse del perno via `cp-f`, esposta
;; come anchor `:pin` con `attach-path`, e l'assemblaggio è uno snap
;; esplicito `(move-to :HingeOuter :at :pin)`.
;;
;; Per visualizzare il "prima/dopo" dell'assemblaggio, la C esterna è
;; volutamente registrata spostata di lato (rt 200): il move-to la
;; ricongiunge alla C interna lungo l'asse del perno.

;; ============================================================
;; 1. PARAMETRI
;; ============================================================

;; --- Geometria del listello (post in legno) ---
(def listello-w 40)
(def listello-d 60)

;; --- Tolleranze e spessori di parete ---
(def gap                   0.3)
(def inner-wall            3)
(def panel-mount-thickness 4)

;; --- Cerniera ---
(def H              80)
(def pin-r          15)
(def pin-clearance  2)
(def outer-extra-h  20)

;; --- Smussi d'imbocco ---
(def bevel-base-r    5)
(def bevel-tip-r     2)
(def bevel-overlap   1)
(def bevel-axial-pos 15)
(def bevel-h-extra   2)

;; --- Staffe ---
(def bracket-leg          listello-w)
(def bracket-thickness    22)
(def bracket-axial-offset 18)
(def bracket-clearance    0.5)
(def bracket-positions    [10 -80])

;; --- Vite svasata ---
(def screw-cs-r1     25)
(def screw-cs-r2     2)
(def screw-cs-depth  40)
(def screw-pos-axial 11)
(def screw-pos-up    20)
(def screw-pos-rt    15)

;; --- Apertura back wall ---
(def back-opening-extra 2)

;; ============================================================
;; 2. PARAMETRI DERIVATI
;; ============================================================

(def pin-d         (* 2 pin-r))
(def inner-w       (+ listello-w (* 2 inner-wall)))
(def inner-d       (+ listello-d (* 2 inner-wall)))
(def outer-w       (+ inner-w (* 2 gap) (* 2 panel-mount-thickness)))
(def outer-d       (+ inner-d (* 2 gap) (* 2 panel-mount-thickness)))
(def pin-hole-r    (+ pin-r (* 2 gap)))
(def pin-length    (+ outer-w (* 2 pin-clearance)))
(def hplus         (+ H (/ inner-d 2)))
(def outer-h       (+ hplus outer-extra-h))
(def outer-stagger (/ inner-d 4))

(resolution :n 64)

;; ============================================================
;; 3. SCAFFOLD VISIVO (pannello + listello — solo riferimento)
;; ============================================================

(color 0x888888)
(def pannello (extrude (rect 1000 600) (f -15)))
(def gamba (extrude (rect listello-w listello-d) (f 600)))
(register Gamba (attach gamba (u 190) (f 30.5) (u 20) (tv -50)))

;; ============================================================
;; 4. STAFFA SINGOLA — costruita nel proprio frame
;; ============================================================
;; Triangolo rettangolo isoscele estruso, con foro svasato per la vite.

(def staffa
  (attach
   (mesh-difference
    (extrude
     (shape (f bracket-leg) (th 90) (f bracket-leg) (th 135))
     (f bracket-thickness))
    (attach (cone screw-cs-r1 screw-cs-r2 screw-cs-depth)
            (f screw-pos-axial)
            (u screw-pos-up)
            (rt screw-pos-rt)
            (tv 90)))
   (u (- (/ outer-d 2)))
   (f bracket-axial-offset)))

;; Pattern di 4 staffe (due per lato lungo l'asse del perno).
;; Le staffe sono unite alla C esterna; non hanno una vita propria
;; come pezzo registrato — qui sono pure geometria di supporto.
(def staffe
  (concat-meshes
   (for [z      bracket-positions
         right? [true false]]
     (attach staffa
             (f (+ z (if right? bracket-thickness 0)))
             (rt (* (if right? 1 -1)
                    (- (+ listello-w (/ outer-w 2)) bracket-clearance)))
             (th (if right? 180 0))))))

;; ============================================================
;; 5. PEZZO 1 — C interna con perno integrato
;; ============================================================
;; Geometria identica a cerniera2_A, ma la creation-pose viene calata
;; sull'asse del perno via `(cp-f -H/2)`. Così l'anchor `:pin` (a zero
;; offset dalla creation-pose) coincide col centro del perno.

(color 0x00ff00)
(def hinge-inner-body
  (attach
   (mesh-union
    ;; Perno orizzontale
    (attach (cyl pin-r pin-length)
            (th 90)
            (rt (- (/ H 2))))
    ;; Corpo a U: box pieno meno cavità + smussi d'imbocco
    (mesh-difference
     (box inner-w inner-d H)
     (mesh-union
      ;; Slot per il listello
      (attach (box (- inner-w (* 2 inner-wall)) inner-d H)
              (u inner-wall))
      ;; Smusso laterale sinistro
      (attach (cone bevel-base-r bevel-tip-r (+ inner-wall bevel-h-extra))
              (rt (- (- (/ inner-w 2) bevel-overlap)))
              (f bevel-axial-pos)
              (th 90))
      ;; Smusso laterale destro
      (attach (cone bevel-base-r bevel-tip-r (+ inner-wall bevel-h-extra))
              (rt (- (/ inner-w 2) bevel-overlap))
              (f bevel-axial-pos)
              (th -90))))
    ;; Cupola di raccordo sotto il perno
    (attach (cyl (/ inner-d 2) inner-w)
            (th 90)
            (rt (- (/ H 2)))))
   ;; Creation-pose → asse del perno
   (cp-f (- (/ H 2)))))

;; ============================================================
;; 6. PEZZO 2 — C esterna (con foro perno + staffe integrate)
;; ============================================================
;; Stesso schema: la creation-pose viene portata sul foro del perno.
;; La geometria interna ha lo shift `(f -outer-stagger)` (necessario
;; perché le staffe siano nella posizione giusta rispetto al box
;; esterno) — il `cp-f` sul totale compensa per metterci sull'asse del
;; perno (a -H/2 dal centro del box, dopo lo stagger).

(def hinge-outer-body
  (attach
   (mesh-union
    staffe
    (mesh-difference
     (box outer-w outer-d outer-h)
     (mesh-union
      ;; Apertura back wall
      (attach (box outer-w (+ (* 2 inner-wall) back-opening-extra) outer-h)
              (u (/ outer-d 2)))
      ;; Cavità per la C interna (con clearance)
      (box (+ inner-w (* 2 gap))
           (+ inner-d (* 2 gap))
           outer-h)
      ;; Foro perno
      (attach (cyl pin-hole-r pin-length)
              (th 90)
              (rt (/ outer-h 2))
              (rt (- (/ outer-extra-h 2)))
              (rt (- H))))))
   ;; Shift per allineare le staffe (preserva la geometria di cerniera2)
   (f (- outer-stagger))
   ;; Creation-pose → foro del perno
   ;; (= box-center post-shift + offset al pin hole = -outer-stagger + (outer-stagger - H/2))
   (cp-f (- outer-stagger (/ H 2)))))

;; ============================================================
;; 7. ASSEMBLY via marks + move-to
;; ============================================================
;; Definiamo l'anchor `:pin` su entrambi i pezzi alla rispettiva
;; creation-pose. Poi spostiamo la C esterna lateralmente (come se
;; fosse un STL caricato a parte): `move-to :at :pin` la riassembla
;; sulla C interna, snappando il foro del perno sull'asse perno.

(def pin-anchor (path (mark :pin)))

(register HingeOuter hinge-outer-body)
(attach-path :HingeOuter pin-anchor)

;; Sposta la C esterna in disparte: simula l'arrivo di un pezzo separato.
;; Commenta questa riga per vedere la cerniera direttamente assemblata.
(attach! :HingeOuter (rt 200))

(register HingeInner hinge-inner-body)
(attach-path :HingeInner pin-anchor)

;; SNAP: la C interna si attacca al perno della C esterna.
;; Geometricamente: la creation-pose della inner (= centro perno) viene
;; spostata sull'anchor :pin della outer (= centro foro perno), che è a
;; sua volta sulla creation-pose della outer.
(attach! :HingeInner (move-to :HingeOuter :at :pin))

;; --- Per stampare i due pezzi separati, scommenta ---
;(register A hinge-outer-body)
;(register B hinge-inner-body)
