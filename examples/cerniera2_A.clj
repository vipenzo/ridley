;; cerniera2_A — Cerniera parametrica print-in-place per fissare un listello a parete.
;;
;; I due pezzi (C interna con perno integrato + C esterna a muro)
;; sono modellati nelle loro posizioni di mate: la stampa 3D li produce
;; GIA' assemblati e la cerniera ruota attorno al perno orizzontale
;; grazie alla clearance `gap` fra perno e foro. Nessun assemblaggio
;; manuale dopo la stampa.
;;
;; Tutti i numeri sono parametri funzionali o derivati dai parametri:
;; basta cambiare i `def` in cima per riadattare il pezzo.

;; ============================================================
;; 1. PARAMETRI
;; ============================================================

;; --- Geometria del listello (il post di legno che la cerniera afferra) ---
(def listello-w 40)
(def listello-d 60)

;; --- Tolleranze e spessori di parete ---
(def gap                   0.3) ; clearance C-su-C, sia radiale che verticale
(def inner-wall            3)   ; spessore pareti C interna (quella che afferra)
(def panel-mount-thickness 4)   ; spessore pareti C esterna (quella a parete)

;; --- Cerniera vera e propria ---
(def H              80)         ; altezza C interna (estensione lungo asse perno)
(def pin-r          15)         ; raggio del perno (cyl prende raggio, non diametro)
(def pin-clearance  2)          ; sporgenza del perno oltre la C esterna, per lato
(def outer-extra-h  20)         ; di quanto la C esterna risale sopra il pin axis

;; --- Smussi d'imbocco (facilitano l'inserimento del listello nella C interna) ---
(def bevel-base-r    5)
(def bevel-tip-r     2)
(def bevel-overlap   1)         ; quanto i coni "morsicano" la parete laterale
(def bevel-axial-pos 15)        ; posizione lungo l'asse perno
(def bevel-h-extra   2)         ; quota extra del cono oltre inner-wall (per taglio pulito)

;; --- Staffe di fissaggio al pannello ---
(def bracket-leg          listello-w) ; cateto del triangolo della staffa
(def bracket-thickness    22)         ; estrusione (spessore della costola)
(def bracket-axial-offset 18)         ; quanto sporgono dalla C esterna lungo f
(def bracket-clearance    0.5)        ; clearance laterale fra staffa e listello
(def bracket-positions    [10 -80])   ; due coppie lungo l'asse perno

;; --- Foro vite svasato sulla staffa (countersunk) ---
(def screw-cs-r1     25)        ; raggio testa svasata
(def screw-cs-r2     2)         ; raggio gambo
(def screw-cs-depth  40)
(def screw-pos-axial 11)        ; posizione lungo f sulla staffa
(def screw-pos-up    20)        ; posizione lungo u
(def screw-pos-rt    15)        ; posizione lungo rt

;; --- Apertura sul retro della C esterna (per montarla a perno inserito) ---
(def back-opening-extra 2)      ; quanto allargare l'apertura oltre 2*inner-wall

;; ============================================================
;; 2. PARAMETRI DERIVATI
;; ============================================================

(def pin-d         (* 2 pin-r))                              ; diametro perno
(def inner-w       (+ listello-w (* 2 inner-wall)))
(def inner-d       (+ listello-d (* 2 inner-wall)))
(def outer-w       (+ inner-w (* 2 gap) (* 2 panel-mount-thickness)))
(def outer-d       (+ inner-d (* 2 gap) (* 2 panel-mount-thickness)))
(def pin-hole-r    (+ pin-r (* 2 gap)))    ; corrisponde a pin-hole-d originale (passato come raggio)
(def pin-length    (+ outer-w (* 2 pin-clearance)))
(def hplus         (+ H (/ inner-d 2)))                      ; altezza C esterna fino al pin axis
(def outer-h       (+ hplus outer-extra-h))                  ; altezza totale C esterna
(def outer-stagger (/ inner-d 4))                            ; offset f fra centro outer e pin axis

(resolution :n 64)

;; ============================================================
;; 3. SCAFFOLD VISIVO (pannello + listello — solo riferimento)
;; ============================================================

(color 0x888888)
(def pannello (extrude (rect 1000 600) (f -15)))
;(register Pannello pannello)
(def gamba (extrude (rect listello-w listello-d) (f 600)))
(register Gamba (attach gamba (u 190) (f 30.5) (u 20) (tv -50)))

;; ============================================================
;; 4. PEZZO 1 — C interna con perno integrato
;; ============================================================
;; Box pieno scavato (slot per il listello, smussi d'imbocco, cupola di
;; raccordo) con un perno orizzontale all'estremità inferiore. Il perno
;; è l'asse di rotazione attorno al quale la C interna pivota dentro la
;; C esterna.

(color 0x00ff00)
(def hinge-inner-body
  (mesh-union
   ;; Perno orizzontale, asse di rotazione
   (attach (cyl pin-r pin-length)
           (th 90)
           (rt (- (/ H 2))))

   ;; Corpo a U: box pieno meno cavità centrale + smussi d'imbocco
   (mesh-difference
    (box inner-w inner-d H)
    (mesh-union
     ;; Slot principale: cavità per il listello, aperta in alto, fondo di inner-wall
     (attach (box (- inner-w (* 2 inner-wall)) inner-d H)
             (u inner-wall))
     ;; Smussi d'imbocco anteriori — lato sinistro
     (attach (cone bevel-base-r bevel-tip-r (+ inner-wall bevel-h-extra))
             (rt (- (- (/ inner-w 2) bevel-overlap)))
             (f bevel-axial-pos)
             (th 90))
     ;; ... e lato destro
     (attach (cone bevel-base-r bevel-tip-r (+ inner-wall bevel-h-extra))
             (rt (- (/ inner-w 2) bevel-overlap))
             (f bevel-axial-pos)
             (th -90))))

   ;; Cupola di raccordo sotto il perno (smaltisce concentrazioni di stress)
   (attach (cyl (/ inner-d 2) inner-w)
           (th 90)
           (rt (- (/ H 2))))))

;; ============================================================
;; 5. PEZZO 2 — Staffa singola (cella della pattern dei brackets)
;; ============================================================

(def staffa
  (attach
   (mesh-difference
    (extrude
     ;; Triangolo rettangolo isoscele (lato bracket-leg), si chiude da solo
     (shape (f bracket-leg) (th 90) (f bracket-leg) (th 135))
     (f bracket-thickness))
    ;; Foro svasato per vite a legno
    (attach (cone screw-cs-r1 screw-cs-r2 screw-cs-depth)
            (f screw-pos-axial)
            (u screw-pos-up)
            (rt screw-pos-rt)
            (tv 90)))
   ;; Posizionamento della staffa rispetto alla parete della C esterna
   (u (- (/ outer-d 2)))
   (f bracket-axial-offset)))

;; Le quattro staffe disposte due-per-lato lungo l'asse del perno.
;; Per ogni z scelto, generiamo una coppia simmetrica destra/sinistra.
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
;; 6. PEZZO 3 — C esterna (foro perno + staffe integrate)
;; ============================================================
;; Box pieno di outer-w x outer-d x outer-h, scavato per: aprire la
;; parete posteriore (back wall), alloggiare la C interna con clearance,
;; passare il perno orizzontalmente. Lo shift `(f -outer-stagger)`
;; finale allinea il foro perno con l'asse della C interna.

(def hinge-outer-body
  (attach
   (mesh-union
    staffe
    (mesh-difference
     (box outer-w outer-d outer-h)
     (mesh-union
      ;; Apertura back wall (per consentire il movimento di rotazione della C interna)
      (attach (box outer-w (+ (* 2 inner-wall) back-opening-extra) outer-h)
              (u (/ outer-d 2)))
      ;; Cavità per la C interna (con clearance gap su entrambi gli assi)
      (box (+ inner-w (* 2 gap))
           (+ inner-d (* 2 gap))
           outer-h)
      ;; Foro perno: passante orizzontale, a quota dell'asse di rotazione.
      ;; rt-totale = outer-h/2 - outer-extra-h/2 - H = inner-d/4 - H/2
      (attach (cyl pin-hole-r pin-length)
              (th 90)
              (rt (/ outer-h 2))
              (rt (- (/ outer-extra-h 2)))
              (rt (- H))))))
   ;; Sposta la C esterna allineandone l'asse al pin della C interna
   (f (- outer-stagger))))

;; ============================================================
;; 7. ASSEMBLY print-in-place
;; ============================================================
;; Le due C sono già nelle loro posizioni di mate (per costruzione
;; condividono l'asse del perno): l'unione produce un singolo modello
;; stampabile in-place. La rotazione della cerniera dopo la stampa è
;; resa possibile dalla clearance `gap` fra perno e foro e dal taglio
;; `back-opening-extra` sulla parete posteriore.

(register Hinge (mesh-union hinge-outer-body hinge-inner-body))

;; --- Per stampare i due pezzi separati (export STL singoli), scommenta ---
;(register A hinge-outer-body)
;(register B hinge-inner-body)
