; === Piatto di registrazione per l'acquisizione parametrica (v2) ===
;
; Il "gradino del piatto" promosso a task dopo il gate finale del v1 (lettore
; SD): il giro completo funziona, ma la PRECISIONE è limitata dai landmark del
; pezzo — i raccordi ~1.7 mm valgono ~33 px, "l'angolo" non è un punto, e il
; PnP lavora su bersagli mal definiti (RMS ~14 px). La cura è cambiare il
; bersaglio: non più i vertici del pezzo, ma dei MARKER a coordinate note su un
; piatto che gira col pezzo.
;
; UNICA FONTE DI VERITÀ (il punto di tutto il file): il piatto lo genera
; Ridley, e lo STESSO sorgente produce sia la geometria da stampare sia la
; MAPPA DEI MARK (id -> posa 3D) che il comando `p` (PnP) consuma. Le posizioni
; dei marker si scrivono UNA volta sola (la funzione `mark-centers` qui sotto) e
; alimentano insieme i dischetti stampati e le pose registrate: mai due numeri
; da tenere allineati a mano.
;
; I mark viaggiano CON la mesh, sotto la sua chiave `:anchors`, nella forma
;   {:m00 {:position [x y z] :heading [x y z] :up [x y z]} ...}
; — identica a quella che il flusso `acquire` già usa per `:marks` e che
; `(turtle (:m00 (:marks A)) ...)` sa consumare (posa piena, non solo punto).
; Quando questo piatto è il proxy di una sessione, `p` legge `(:anchors piatto)`
; invece dei vertici del box (estensione della fetta B).
;
; STAMPA a DUE COLORI: i marker sono regioni di colore PIATTE sulla faccia
; superiore (dischetti scuri incassati a filo), non incisi né in rilievo — il
; confine di un colore è invariante alla vista e non fa ombre, mentre un solco
; sposta il "punto" con l'illuminazione. Ogni dischetto è incassato in una
; tasca (niente sovrapposizione di volume col piatto → due materiali puliti),
; ed esce come slot filamento separato nel 3MF multimateriale.
;
; v1 dei marker: corona regolare + uno ZERO asimmetrico (un pallino-indice
; interno accanto a m00). La corona CODIFICATA per l'auto-detect verrà dopo,
; sulla STESSA architettura: cambia il detector, non questo file. Il centro del
; dischetto è il punto di click OGGI e sarà il centroide del blob per il
; detector DOMANI — stesso bersaglio, due modi di trovarlo.

; --- Parametri (mm) -----------------------------------------------------
(def PLATE-D 130)                 ; diametro del piatto (⌀130, come il giradischi)
(def PLATE-R (/ PLATE-D 2))       ; 65
(def PLATE-H 3)                   ; spessore

(def N-MARKS 12)                  ; marker nella corona (8–12); 12 = uno ogni 30°
(def CROWN-R 58)                  ; raggio della corona — a filo del bordo, con
                                  ; margine: l'oggetto occlude il centro, i marker
                                  ; stanno fuori e restano visibili da ogni angolo
(def DISC-D 2.5)                  ; diametro del dischetto (⌀2–3)
(def DISC-R (/ DISC-D 2))         ; 1.25
(def INLAY 0.6)                   ; profondità della regione colorata (alcuni layer)
(def INDEX-R 52)                  ; raggio del pallino-indice interno (lo zero)

(def PLATE-COLOR 0xEFE7D8)        ; chiaro opaco (base)
(def MARK-COLOR 0x1A1A1A)         ; scuro opaco (marker) — scuro-su-chiaro

; --- Helper -------------------------------------------------------------
(def PI2 (* 2 PI))
(defn deg->rad [d] (/ (* d PI) 180.0))
(def TOP-Z (/ PLATE-H 2.0))       ; quota della faccia superiore (1.5)

; Il cilindro di default ha l'asse lungo il heading (+X); qui lo vogliamo
; VERTICALE (+Z = asse del giradischi), così la faccia superiore è il top.
(defn zc [r h] (rotate (cyl r h) :y 90))

(defn crown-xy [r deg]
  (let [a (deg->rad deg)] [(* r (cos a)) (* r (sin a))]))

; --- UNICA FONTE DI VERITÀ: le posizioni dei marker --------------------
(def mark-angles (mapv (fn [i] (* i (/ 360.0 N-MARKS))) (range N-MARKS)))
(def mark-centers (mapv (fn [deg] (crown-xy CROWN-R deg)) mark-angles)) ; [[x y] ...]
(def index-center (crown-xy INDEX-R 0))    ; il pallino-indice, radiale su m00

; La mappa dei mark: id -> posa piena sulla faccia superiore.
;   :heading = normale della faccia (+Z)   :up = asse radiale uscente (nel piano)
; È QUESTA la mappa che `p` userà; nasce dagli stessi `mark-centers` dei dischi.
(def marks
  (into {}
        (map-indexed
         (fn [i deg]
           (let [[x y] (nth mark-centers i)
                 a (deg->rad deg)
                 id (keyword (str "m" (when (< i 10) "0") i))]
             [id {:position [x y TOP-Z]
                  :heading [0 0 1]
                  :up [(cos a) (sin a) 0]}]))
         mark-angles)))

; --- Geometria ----------------------------------------------------------
(def plate-solid (zc PLATE-R PLATE-H))

; Tutti i dischi = i marker della corona + il pallino-indice (lo zero).
(def disc-centers (conj mark-centers index-center))

; Taglierino per la tasca: sporge 2 mm SOPRA la faccia superiore, così taglia
; il top in modo netto (niente facce complanari, la ricetta nota degli artefatti
; CSG); il fondo resta cieco a INLAY sotto il top.
(defn pocket-at [[x y]]
  (mesh-translate (zc DISC-R (+ INLAY 2))
                  [x y (+ TOP-Z (/ (- 2 INLAY) 2.0))]))

; Il dischetto che riempie la tasca, a filo del top, scuro.
(defn disc-at [[x y]]
  (-> (zc DISC-R INLAY)
      (mesh-translate [x y (- TOP-Z (/ INLAY 2.0))])
      (color MARK-COLOR)))

; La base: piatto meno tutte le tasche, colore chiaro, con la mappa mark
; attaccata sotto :anchors (viaggia con la mesh → è il proxy per `p`).
(def piatto
  (-> (mesh-difference-impl (into [plate-solid] (map pocket-at disc-centers)))
      (color PLATE-COLOR)
      (assoc :anchors marks)))

(def discs (mapv disc-at disc-centers))

; --- Scena --------------------------------------------------------------
; Il proxy (chiaro, coi mark) + i dischetti scuri, per vedere la corona.
(register piatto piatto)
(doseq [[i d] (map-indexed vector discs)]
  (register-mesh! (keyword (str "disc-" i)) d))

(println (str "Piatto ⌀" PLATE-D " mm, " N-MARKS " marker (corona R" CROWN-R
              ") + zero-indice. La mappa mark è (:anchors piatto)."))

; --- Variante carta (definizione migliore della stampa 3D bicolore) -----
;
; Invece di affidare i marker alla faccia superiore di una stampa 3D bicolore
; (bordi sfumati sui layer alti → centroide impreciso → RMS del PnP che sale), si
; stampano su CARTA a dimensione effettiva e si incollano su un piatto plastico
; liscio (substrato per rigidità e innesti). Le posizioni restano QUESTE (unica
; fonte): la carta dà solo la definizione.
;
; La stampante sbaglia la scala dello 0.1-0.5%, spesso in modo ANISOTROPO: il
; foglio porta due barre di scala ortogonali (100 mm nominali). Le misuri col
; calibro e scrivi i valori qui sotto → la mappa dei mark si scala per-asse, così
; il modello combacia coi marker DAVVERO stampati.

(def SHEET-BAR 100.0)   ; lunghezza nominale delle barre di scala nel foglio (mm)
(def MEASURED-X 100.0)  ; ← misura la barra ORIZZONTALE col calibro e scrivi qui
(def MEASURED-Y 100.0)  ; ← misura la barra VERTICALE e scrivi qui
(def sx (/ MEASURED-X SHEET-BAR))
(def sy (/ MEASURED-Y SHEET-BAR))

; La mappa mark scalata per-asse (solo le posizioni; heading/up sono direzioni).
(def marks-carta
  (into {}
        (map-indexed
         (fn [i deg]
           (let [[x y] (nth mark-centers i)
                 a (deg->rad deg)
                 id (keyword (str "m" (when (< i 10) "0") i))]
             [id {:position [(* x sx) (* y sy) TOP-Z]
                  :heading [0 0 1]
                  :up [(cos a) (sin a) 0]}]))
         mark-angles)))

; Il proxy per la variante carta: RIUSA la mesh di `piatto` (stesso frame di
; costruzione, già collaudato) con la mappa mark scalata al posto della nominale.
; NB: costruirlo da `plate-solid` grezzo gli darebbe un creation-pose diverso
; (dal rotate del cilindro), che ribalta i mark lontano dalla camera → nessun
; mark "visibile" da armare. Le tasche nel wireframe sono solo estetiche (il PnP
; usa :anchors). Uso: (edit-acquire "dir" {:proxy piatto-carta}).
(def piatto-carta (assoc piatto :anchors marks-carta))

; Il foglio da stampare: dischi scuri a scala esatta + le due barre di scala +
; il NUMERO di ogni marker (0..11) stampato radialmente FUORI dal dischetto, così
; sai quale stai cliccando senza contare dallo zero-indice. Il numero sta lontano
; dal centro (oltre la finestrella del blob-snap) per non spostare il centroide.
; L'ultimo disco (lo zero-indice interno) non è numerato.
(def sheet-labels (conj (mapv str (range N-MARKS)) nil))
(def sheet-svg (marks->svg disc-centers {:disc-r DISC-R :plate-r PLATE-R
                                         :bar-mm SHEET-BAR :labels sheet-labels}))
; Salvalo e stampalo al 100% ("dimensione effettiva", NON "adatta alla pagina"):
;   (save-svg sheet-svg "param-acq-plate-sheet.svg")

; --- Stampa e uso -------------------------------------------------------
;
; Stampa (3MF a due materiali: base = slot 1 chiaro, dischetti = slot 2 scuro):
;   (save-3mf (into [piatto] discs) "param-acq-plate.3mf")
;
; Orientamento di stampa: LATO DISCHETTI VERSO IL PIANO DI STAMPA (faccia coi
; marker in basso). La superficie contro il piatto di stampa è la più liscia e
; netta in FDM: i confini dei dischetti restano definiti. Stampandolo al
; contrario (dischetti in alto, sui layer superiori) i bordi vengono un po'
; SFUMATI — collaudo del piatto v2 (2026-07-27): dischetti sfumati ⇒ centroidi
; imprecisi ⇒ RMS del PnP che sale su alcune foto (il blob-snap è a posto: sulle
; foto coi dischetti netti fa 1.8 px, meglio del click a mano). Nessun supporto
; in nessuno dei due versi (i dischetti sono a filo).
;
; Uso in registrazione (fetta B): il piatto è il proxy della sessione; `p` legge
; le pose da (:anchors piatto) invece dei vertici del box. La corona gira col
; pezzo, quindi registra qualunque forma — anche cilindrica o senza spigoli.
