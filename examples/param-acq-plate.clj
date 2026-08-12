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

; --- L'UNICO numero da cambiare -----------------------------------------
;
; Il diametro. Tutto il resto — raggio della corona, quanti marker, quanto sono
; grandi i dischetti, dov'è il pallino-indice — lo deriva `registration-plate`,
; che è LA STESSA funzione che il flusso di registrazione userà come proxy.
; Prima queste erano cinque costanti scritte a mano qui (CROWN-R 58, INDEX-R 52,
; DISC-D 2.5 …), legate fra loro da relazioni che bisognava conoscere: cambiare
; il diametro voleva dire ricalcolarle tutte, e sbagliarne una avrebbe prodotto
; un piatto stampato che NON coincide col modello — cioè una registrazione
; sbagliata di scala, che non si presenta come un errore ma come una misura
; plausibile. È esattamente il "mai due numeri" che questo file si raccomanda
; da solo qui sopra, portato fino in fondo.
;
; Taglie: ⌀130 (il giradischi) va bene per oggetti piccoli; da 200 a 350 per
; oggetti più grandi. Un piatto più grande è una COPIA IN SCALA: i dischetti
; crescono con lui, così inquadrato pieno mostra al rilevatore la stessa
; geometria. Il numero di marker NON cresce, e la ragione sta in
; `max-default-marks`: una corona più fitta non compra precisione, compra una
; registrazione che smette di funzionare appena l'oggetto ne copre tre.

(def PLATE-D 130)                 ; ← cambia SOLO questo

(def PLATE-H 3)                   ; spessore (stiffness, non lo vede la camera)
(def INLAY 0.6)                   ; profondità della regione colorata (alcuni layer)

(def PLATE-COLOR 0xEFE7D8)        ; chiaro opaco (base)
(def MARK-COLOR 0x1A1A1A)         ; scuro opaco (marker) — scuro-su-chiaro

; --- Tutto il resto DERIVA, leggendolo dal proxy ------------------------
; `registration-plate` porta i mark su :anchors e il raggio del dischetto su
; :mark-disc-r. Le posizioni dei dischi da stampare sono LE STESSE che il
; solutore userà, perché sono lo stesso oggetto — non due elenchi da tenere
; allineati.
(def proxy-plate (registration-plate :d PLATE-D :h PLATE-H))

(def PLATE-R (/ PLATE-D 2))
(def DISC-R (:mark-disc-r proxy-plate))
(def anchors (:anchors proxy-plate))
(def TOP-Z (/ PLATE-H 2.0))       ; quota della faccia superiore

; I mark della corona in ordine (m00, m01, …), lo zero-indice a parte: la
; chiave :zero è riservata e `bridge/pnp-target-points` la esclude dalla corona
; pickabile — non è un mark da cliccare, è il rompi-simmetria del detector.
; Senza di lui la corona regolare è simmetrica e nessuno può dire come è girata.
(def crown-ids (sort (remove #(= :zero %) (keys anchors))))
(def N-MARKS (count crown-ids))
(defn xy-of [id] (let [[x y _] (:position (get anchors id))] [x y]))
(def mark-centers (mapv xy-of crown-ids))
(def index-center (xy-of :zero))
(def CROWN-R (let [[x y] (first mark-centers)] (sqrt (+ (* x x) (* y y)))))

; --- Helper -------------------------------------------------------------
; Il cilindro di default ha l'asse lungo il heading (+X); qui lo vogliamo
; VERTICALE (+Z = asse del giradischi), così la faccia superiore è il top.
(defn zc [r h] (rotate (cyl r h) :y 90))

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

; La base: piatto meno tutte le tasche, colore chiaro, con la mappa mark (+ lo
; zero-indice sotto :zero) attaccata sotto :anchors (viaggia con la mesh → è il
; proxy per `p`) e il raggio-disco fisico sotto :mark-disc-r (per il detector).
(def piatto
  (-> (mesh-difference-impl (into [plate-solid] (map pocket-at disc-centers)))
      (color PLATE-COLOR)
      (assoc :anchors anchors :mark-disc-r DISC-R)))

(def discs (mapv disc-at disc-centers))

; --- Scena --------------------------------------------------------------
; Il proxy (chiaro, coi mark) + i dischetti scuri, per vedere la corona.
(register piatto piatto)
(doseq [[i d] (map-indexed vector discs)]
  (register-mesh! (keyword (str "disc-" i)) d))

(println (str "Piatto ⌀" PLATE-D " mm, " N-MARKS " marker (corona R"
              (/ (round (* 10 CROWN-R)) 10.0) ", dischetti ⌀"
              (/ (round (* 200 DISC-R)) 100.0)
              ") + zero-indice. La mappa mark è (:anchors piatto)."))
(println (if (sheet-fits-a4? PLATE-R)
           "Il foglio sta su un A4: stampalo al 100% da sheet-svg."
           (str "Il foglio è ⌀" PLATE-D "+36 mm e NON sta su un A4: stampa le due "
                "metà di sheet-halves al 100% e accostale sulle croci.")))

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

; La mappa mark scalata per-asse: si scalano solo le POSIZIONI, non heading/up,
; che sono direzioni. Lo zero-indice viene con gli altri perché è una voce di
; :anchors come tutte — prima era un caso a parte da ricordare.
(def anchors-carta
  (into {}
        (map (fn [[id a]]
               (let [[x y z] (:position a)]
                 [id (assoc a :position [(* x sx) (* y sy) z])]))
             anchors)))

; Il proxy per la variante carta: RIUSA la mesh di `piatto` (stesso frame di
; costruzione, già collaudato) con la mappa mark scalata al posto della nominale.
; NB: costruirlo da `plate-solid` grezzo gli darebbe un creation-pose diverso
; (dal rotate del cilindro), che ribalta i mark lontano dalla camera → nessun
; mark "visibile" da armare. Le tasche nel wireframe sono solo estetiche (il PnP
; usa :anchors). :mark-disc-r è ereditato da `piatto`. Uso:
; (edit-acquire "dir" {:proxy piatto-carta}).
(def piatto-carta (assoc piatto :anchors anchors-carta))

; Il foglio da stampare: dischi scuri a scala esatta + le due barre di scala +
; il NUMERO di ogni marker (0..11) stampato radialmente FUORI dal dischetto, così
; sai quale stai cliccando senza contare dallo zero-indice. Il numero sta lontano
; dal centro (oltre la finestrella del blob-snap) per non spostare il centroide.
; L'ultimo disco (lo zero-indice interno) non è numerato.
(def sheet-labels (conj (mapv str (range N-MARKS)) nil))
; :spindle-d disegna la guida per il foro centrale. Su un GIRADISCHI vero è la
; cosa che centra tutto: infili il foglio sul perno e il piatto è centrato E
; coassiale all'asse di rotazione — che è l'unica cosa che la macchina
; dell'anello dà per scontata e che altrimenti deve scoprire dai dati. Sulle due
; metà il foro è tagliato a metà dalla cucitura, e diventa un terzo riferimento
; di allineamento oltre alle due croci. Metti 0 (o togli la chiave) se il piatto
; non va su un perno.
(def SPINDLE-D 7.1)               ; perno di un giradischi (33/45 giri)
(def sheet-opts {:disc-r DISC-R :plate-r PLATE-R :bar-mm SHEET-BAR
                 :labels sheet-labels :n-crown N-MARKS :spindle-d SPINDLE-D})
(def sheet-svg (marks->svg disc-centers sheet-opts))
; Salvalo e stampalo al 100% ("dimensione effettiva", NON "adatta alla pagina"):
;   (save-svg sheet-svg "param-acq-plate-sheet.svg")

; --- Piatti grandi: due metà da incollare -------------------------------
;
; Il foglio è largo quanto il piatto più 36 mm di margine, quindi da ⌀150 in su
; non entra in un A4 al 100%. Stamparlo "adattato alla pagina" sarebbe il
; peggiore degli errori possibili: riscalerebbe in silenzio proprio la cosa che
; deve essere esatta, e il risultato non sembrerebbe un errore — sembrerebbe una
; registrazione riuscita, con la scala sbagliata.
;
; Le due metà si stampano al 100% e si accostano lungo la cucitura. Tre cose le
; rendono affidabili: la cucitura passa FRA due dischetti e mai sopra uno; ogni
; metà porta le SUE barre di scala, perché l'errore di scala di una stampante è
; per-pagina e dare per scontato che due fogli siano usciti identici è
; esattamente il tipo di assunzione che questo canale continua a pagare; e le
; due croci di allineamento, ai capi della cucitura, fissano insieme traslazione
; e rotazione (allinearle a 0.3 mm su 300 fa 0.06°, sotto l'errore della stampa).
(def sheet-halves (marks->svg-halves disc-centers sheet-opts))
; Per un piatto grande, salva ed accosta:
;   (save-svg (first sheet-halves) "sheet-A.svg")
;   (save-svg (second sheet-halves) "sheet-B.svg")

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
