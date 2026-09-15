;; Ridley Library: acquire-plate

;; ============================================================================
;; Fabbricare il piatto di registrazione
;; ============================================================================
;;
;; Il piatto è il disco con la corona di dischetti su cui appoggi l'oggetto da
;; acquisire. Serve perché la registrazione ha bisogno di punti VERI: su un
;; oggetto reale "lo spigolo" non è un punto, è un raccordo largo un millimetro
;; o due, che alla scala della foto sono decine di pixel di ambiguità.
;;
;; Questa libreria fa la META' FABBRICAZIONE: il foglio da stampare e il piatto
;; da stampare in 3D. L'altra metà — il proxy che il solutore userà — è già una
;; funzione di serie, `registration-plate`, e le due NON si scrivono due volte:
;; tutto qui dentro legge le posizioni dei mark dal proxy stesso. I dischetti
;; che stampi sono, letteralmente, i mark che il programma cercherà.
;;
;;   (acquire-plate/save-sheet 300 "~/Downloads")
;;
;; Una riga: costruisce il piatto, decide se il foglio sta su una pagina o va in
;; due metà, salva quel che serve e ti dice cosa ha fatto.

;; --- Il perno del giradischi -------------------------------------------------

(def spindle-d
  "Diametro del perno del giradischi, in mm. Lo standard del 33/45 giri è
   7.1-7.3; misura il TUO col calibro se non entra o balla."
  7.5)

(def spindle-h
  "Quanto il perno sporge sopra il piatto del giradischi, in mm. Conta perché il
   piatto di registrazione deve essere almeno così spesso: se è più sottile, il
   perno esce dalla faccia superiore proprio dove appoggi l'oggetto, e l'oggetto
   balla su un dente invece di stare piatto."
  8.0)

;; --- Il foglio ---------------------------------------------------------------

(defn disc-centers
  "I centri [x y] di tutti i dischi da stampare per un piatto ⌀`d`: la corona in
   ordine (m00, m01, …) e lo zero-indice per ultimo. Letti dagli :anchors del
   proxy, che è ciò che rende impossibile che stampato e modello si sfasino."
  [d]
  (let [a (:anchors (registration-plate :d d))
        crown (sort (remove (fn [k] (= :zero k)) (keys a)))
        xy (fn [id] (let [[x y _] (:position (get a id))] [x y]))]
    (conj (mapv xy crown) (xy :zero))))

(defn sheet-opts
  "Le opzioni del foglio per un piatto ⌀`d`. `bar` è la lunghezza nominale delle
   barre di scala (mm) e `spindle` il diametro del perno (0 = nessun foro)."
  [d bar spindle]
  (let [p (registration-plate :d d)
        n (count (remove (fn [k] (= :zero k)) (keys (:anchors p))))]
    {:disc-r (:mark-disc-r p)
     :plate-r (/ d 2)
     :bar-mm bar
     :n-crown n
     :spindle-d spindle
     ;; il NUMERO di ogni mark, stampato radialmente FUORI dal dischetto: sai
     ;; quale stai guardando senza contare dallo zero. L'ultimo disco è lo
     ;; zero-indice e non si numera.
     :labels (conj (mapv str (range n)) nil)}))

(defn sheet
  "Il foglio intero come stringa SVG, a scala esatta in mm."
  [d]
  (marks->svg (disc-centers d) (sheet-opts d 100 spindle-d)))

(defn sheet-halves
  "Il foglio tagliato in due metà lungo un diametro, [A B]. Serve quando il
   piatto non entra in una pagina: la cucitura passa FRA due dischetti, ogni
   metà porta le sue barre di scala, e due croci sulla cucitura le rimettono
   insieme."
  [d]
  (marks->svg-halves (disc-centers d) (sheet-opts d 100 spindle-d)))

;; --- La funzione che userai --------------------------------------------------

(defn save-sheet
  "Salva il foglio da stampare per un piatto ⌀`d` mm nella cartella `dir`.

     (acquire-plate/save-sheet 300 \"~/Downloads\")

   Decide da sola quanti file servono: un foglio solo se il piatto entra in una
   pagina A4 al 100%, due metà da accostare se non ci entra. Non ti chiede di
   scegliere, perché la risposta dipende dal diametro e la sa già.

   STAMPA SEMPRE AL 100% — voce \"dimensione effettiva\", mai \"adatta alla
   pagina\". Adattare riscalerebbe in silenzio proprio la cosa che deve essere
   esatta, e il risultato non sembrerebbe un errore: sembrerebbe una
   registrazione riuscita, con la scala sbagliata."
  [d dir]
  (let [base (str dir "/piatto-" (round d))]
    (if (sheet-fits-a4? (/ d 2))
      (do (save-text-at (sheet d) (str base ".svg"))
          (println (str "Piatto ⌀" (round d) ": un foglio solo → " base ".svg"
                        " — stampalo al 100%.")))
      (let [[a b] (sheet-halves d)]
        (save-text-at a (str base "-A.svg"))
        (save-text-at b (str base "-B.svg"))
        (println (str "Piatto ⌀" (round d) ": non entra in un A4, due metà → "
                      base "-A.svg e " base "-B.svg"
                      " — stampale al 100%, poi accostale facendo coincidere le "
                      "due croci sulla cucitura."))))))

;; --- Dopo la stampa: la correzione col calibro -------------------------------

(defn measured
  "Il proxy di un piatto ⌀`d` con la mappa dei mark corretta su quanto è uscito
   DAVVERO dalla stampante.

   Una stampante sbaglia la scala dello 0.1-0.5%, spesso in modo diverso sui due
   assi. Il foglio porta due barre nominalmente da 100 mm: misurale col calibro e
   passa le due letture qui. Senza questa correzione il modello e il foglio
   incollato non combaciano, e la differenza non si presenta come un errore —
   si presenta come misure plausibili e sbagliate.

     (acquire-plate/measured 300 100.2 99.8)

   Da usare come :proxy della sessione. Su due metà misura le barre di ENTRAMBI
   i fogli: se differiscono, i due fogli sono usciti a scale diverse e una
   correzione sola non basta."
  [d mx my]
  (let [p (registration-plate :d d)
        sx (/ mx 100.0)
        sy (/ my 100.0)]
    ;; si scalano le POSIZIONI, non heading/up, che sono direzioni. Lo zero-indice
    ;; viene con gli altri: è una voce di :anchors come tutte.
    (assoc p :anchors
           (into {}
                 (map (fn [[id a]]
                        (let [[x y z] (:position a)]
                          [id (assoc a :position [(* x sx) (* y sy) z])]))
                      (:anchors p))))))

;; --- Variante stampata in 3D a due colori ------------------------------------
;;
;; Alternativa alla carta. I marker sono regioni di colore PIATTE sulla faccia
;; superiore — dischetti scuri incassati a filo, non incisi né in rilievo: il
;; confine di un colore è invariante alla vista e non fa ombre, mentre un solco
;; sposta il "punto" con l'illuminazione.
;;
;; Nota dal collaudo (2026-07-27): stampalo COI DISCHETTI VERSO IL PIANO. La
;; superficie contro il piatto di stampa è la più netta in FDM; al contrario i
;; bordi vengono sfumati, il centroide impreciso e il residuo sale.

(defn- zc [r h] (rotate (cyl r h) :y 90))

(defn plate-thickness
  "Lo spessore da dare al piatto stampato di ⌀`d` con un perno da `sp` mm: se c'è
   un perno, almeno quanto basta a inghiottirlo; e comunque abbastanza da non
   flettere quando il piatto è grande. Un disco di ⌀300 spesso 3 mm si imbarca da
   solo, e un piatto imbarcato non è più PIANO — che è proprio la proprietà su cui
   la registrazione poggia (i mark complanari si risolvono con l'omografia)."
  [d sp]
  (max (if (> sp 0) (+ spindle-h 1.0) 4.0)
       (* 0.03 d)))

(defn plate-3mf-parts
  "[base dischetti] per la stampa a due materiali di un piatto ⌀`d` spesso `h` mm
   con un foro da `sp` mm al centro (0 = nessun foro): la base chiara con le
   tasche dei mark, e i dischi scuri che le riempiono a filo."
  [d sp h]
  (let [p (registration-plate :d d)
        disc-r (:mark-disc-r p)
        inlay 0.6
        top-z (/ h 2.0)
        centers (disc-centers d)
        ;; il taglierino sporge 2 mm sopra la faccia: taglia netto, senza facce
        ;; complanari (la ricetta nota degli artefatti CSG)
        pocket (fn [[x y]] (mesh-translate (zc disc-r (+ inlay 2))
                                           [x y (+ top-z (/ (- 2 inlay) 2.0))]))
        ;; il foro del perno, passante e sporgente da entrambe le facce per la
        ;; stessa ragione: niente facce complanari col solido
        bore (if (> sp 0) [(zc (/ sp 2.0) (+ h 4))] [])
        disc (fn [[x y]] (-> (zc disc-r inlay)
                             (mesh-translate [x y (- top-z (/ inlay 2.0))])
                             (color 0x1A1A1A)))
        base (-> (mesh-difference-impl
                  (into (into [(zc (/ d 2) h)] bore) (map pocket centers)))
                 (color 0xEFE7D8)
                 (assoc :anchors (:anchors p) :mark-disc-r disc-r))]
    [base (mapv disc centers)]))

(defn save-3mf-plate
  "Salva il piatto ⌀`d` come 3MF a due materiali nella cartella `dir`: base
   chiara, dischetti scuri incassati a filo, foro centrale per il perno.

     (acquire-plate/save-3mf-plate 300 \"~/Downloads\")

   È l'alternativa alla carta: più solida e senza niente da incollare, ma i bordi
   dei dischetti sono meno definiti — in FDM i confini sui layer alti vengono
   sfumati, e un centroide sfumato è un residuo più alto. Se vuoi entrambe le
   cose, stampa questo e incollaci sopra la corona di carta.

   Lo spessore di default inghiotte il perno (`spindle-h` + 1 di franco). Puoi
   forzarlo: `(save-3mf-plate 300 dir 7.5 8)`.

   STAMPALO COI DISCHETTI VERSO IL PIANO: la superficie contro il piatto di
   stampa è la più netta che l'FDM sappia fare. Al contrario i bordi vengono
   sfumati — collaudato, e si vede nel residuo."
  ([d dir] (save-3mf-plate d dir spindle-d))
  ([d dir sp] (save-3mf-plate d dir sp (plate-thickness d sp)))
  ([d dir sp h]
   (let [[base discs] (plate-3mf-parts d sp h)
         path (str dir "/piatto-" (round d) ".3mf")
         vol (/ (* PI (* (/ d 2) (/ d 2)) h) 1000.0)]
     (save-3mf-at (into [base] discs) path)
     (println (str "Piatto ⌀" (round d) " spesso " (/ (round (* 10 h)) 10.0) " mm"
                   (if (> sp 0) (str ", foro ⌀" sp " per il perno") ", senza foro")
                   " → " path))
     (when (and (> sp 0) (< h spindle-h))
       (println (str "  ATTENZIONE: il perno sporge " spindle-h " mm e il piatto è "
                     "spesso " (/ (round (* 10 h)) 10.0) " — il perno uscirà dalla "
                     "faccia di sopra di " (/ (round (* 10 (- spindle-h h))) 10.0)
                     " mm, proprio dove appoggia l'oggetto.")))
     ;; Il modello è PIENO, e va lasciato tale: il vuoto lo fa lo slicer con
     ;; l'infill, che è la leva giusta. Un modello svuotato a mano intrappola
     ;; cavità senza sfiato e, soprattutto, toglie sostegno alla faccia superiore
     ;; — che è quella che deve restare PIANA, perché è sulla planarità che la
     ;; registrazione poggia (i mark complanari si risolvono con l'omografia).
     (println (str "  " (round vol) " cm³ da pieno: stampalo con infill basso "
                   "(10-15%) e 4-5 layer solidi sopra. Non svuotare il modello — "
                   "il vuoto è compito dello slicer, e una faccia superiore senza "
                   "sostegno smette di essere piana.")))))
