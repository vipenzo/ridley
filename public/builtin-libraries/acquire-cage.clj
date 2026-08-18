;; Ridley Library: acquire-cage

;; ============================================================================
;; Fabbricare la gabbia di registrazione
;; ============================================================================
;;
;; La gabbia è l'alternativa al piatto: invece di appoggiare l'oggetto sopra il
;; riferimento, il riferimento viaggia INTORNO all'oggetto. Tre anelli piatti,
;; ortogonali fra loro, il pezzo ancorato al centro. Da qualunque parte scatti,
;; nell'inquadratura c'è sempre almeno un anello ben visto, e quasi sempre due o
;; tre — così ogni foto si registra da sola, anche quella da dietro, e non ci
;; sono più giradischi, angoli, NOTE.md né sessioni da fondere.
;;
;;   (acquire-cage/save-3mf 176 "~/Downloads")
;;
;; Una riga: costruisce i tre anelli con i dischetti a due colori su ENTRAMBE le
;; facce, ci attacca le sei linguette che li tengono insieme, e salva il 3MF.
;;
;; Come per il piatto, la geometria non è scritta due volte: tutto qui dentro
;; legge le posizioni dei mark dal proxy `registration-cage`. I dischetti che
;; stampi SONO i mark che il programma cercherà.

;; --- Materiale ---------------------------------------------------------------

(def base-color
  "Il colore chiaro degli anelli. Prendilo OPACO: un filamento lucido o silk fa
   riflessi speculari, e il riconoscimento cerca 'una macchia scura e tonda' —
   un riflesso è esattamente il contrario, e gliene regala a decine."
  0xEFE7D8)

(def mark-color
  "Il colore scuro dei dischetti. Più contrasto c'è con la base, più stretta è
   la soglia che il riconoscimento può permettersi."
  0x1A1A1A)

(def inlay
  "Quanto sono profonde le tasche dei mark, in mm. I dischetti stanno INCASSATI
   A FILO: una regione di colore piatta, non un solco né un rilievo. Il confine
   fra due colori è dove è, comunque tu guardi; il bordo di un solco si sposta
   con la luce, e con lui il centro che il programma misura."
  0.6)

;; --- Un cilindro lungo un asse -----------------------------------------------
;; `cyl` nasce con l'asse lungo l'avanti della tartaruga (+X). Le tasche e i
;; dischetti vanno bucati lungo la normale della faccia che li porta, che per
;; ogni anello è il suo asse.

(defn- ax-cyl [axis r h]
  (cond
    (= axis :x) (cyl r h)
    (= axis :y) (rotate (cyl r h) :z 90)
    :else       (rotate (cyl r h) :y 90)))

(defn- axis-of
  "L'asse di un mark, dedotto dalla sua normale: la componente che non è zero."
  [[nx ny nz]]
  (cond (not= 0.0 nx) :x
        (not= 0.0 ny) :y
        :else :z))

(defn- v+ [a b] (mapv + a b))
(defn- v* [v k] (mapv (fn [c] (* c k)) v))

;; --- Un anello ---------------------------------------------------------------

(defn- ring-letter
  "La lettera dell'anello a cui appartiene un mark: `:zp07` → \"z\", e
   `:zero-zp` → \"z\" anche lui, che è il motivo per cui questa funzione esiste
   invece di guardare solo la prima lettera — 'zero' comincia per z."
  [id]
  (let [s (name id)]
    (if (and (> (count s) 5) (= "zero-" (subs s 0 5)))
      (subs s 5 6)
      (subs s 0 1))))

(defn ring-anchors
  "I mark (corona + zero-indice) che stanno sull'anello `axis` della gabbia `c`,
   su entrambe le facce."
  [c axis]
  (filter (fn [[id _]] (= (name axis) (ring-letter id))) (:anchors c)))

(defn ring-part
  "L'anello `axis` della gabbia `c` pronto da stampare, come [base dischetti]:
   l'anello chiaro con le tasche su tutte e due le facce e le sue linguette, e i
   dischetti scuri che riempiono le tasche a filo."
  [c axis]
  (let [h (:cage-h c)
        disc-r (:mark-disc-r c)
        r (first (filter (fn [x] (= axis (:axis x))) (:rings c)))
        marks (ring-anchors c axis)
        ;; il taglierino sporge 2 mm sopra la faccia: taglia netto, senza facce
        ;; complanari (la ricetta nota degli artefatti CSG)
        over 2.0
        pocket (fn [[_ a]]
                 (let [n (:heading a)]
                   (mesh-translate (ax-cyl (axis-of n) disc-r (+ inlay over))
                                   (v+ (:position a) (v* n (/ (- over inlay) 2.0))))))
        disc (fn [[_ a]]
               (let [n (:heading a)]
                 (mesh-translate (ax-cyl (axis-of n) disc-r inlay)
                                 (v+ (:position a) (v* n (- (/ inlay 2.0)))))))
        annulus (mesh-difference (ax-cyl axis (:outer r) h)
                                 (ax-cyl axis (:inner r) (+ h 2)))
        ;; `box` prende (destra, su, avanti) — la convenzione della tartaruga —
        ;; e alla posa di partenza quelle sono (y, z, x). Le linguette arrivano
        ;; qui come ingombri di mondo [dx dy dz], quindi vanno rimesse in
        ;; quest'ordine. Passarle così com'erano dava linguette girate di 90°,
        ;; e la prova non è stata leggere il codice: è stato misurare
        ;; l'ingombro del pezzo uscito.
        tabs (map (fn [t]
                    (let [sz (:size t)]
                      (mesh-translate (box (nth sz 1) (nth sz 2) (nth sz 0))
                                      (:center t))))
                  (filter (fn [t] (= axis (:owner t))) (:tabs c)))
        ;; linguette E battute: sono tutte scatole, e appartengono all'anello
        ;; che le porta stampate addosso
        solid (if (empty? tabs) annulus (mesh-union (cons annulus tabs)))]
    [(-> (mesh-difference (cons solid (map pocket marks)))
         (color base-color))
     (-> (mesh-union (map disc marks))
         (color mark-color))]))

;; --- La gabbia intera --------------------------------------------------------

(def ring-keys
  "I tre anelli, dal più grande al più piccolo. Si chiamano per DIMENSIONE e non
   per asse, perché è così che li distingui quando ce li hai in mano."
  [:big :medium :small])

(defn files
  "I tre file da stampare per una gabbia ⌀`d`, come coppie [nome pezzi]: UN
   ANELLO PER FILE, con le sue due corone di dischetti.

   Non un file solo con sei oggetti dentro, che pure era la cosa naturale da
   scrivere. In uno slicer un anello e i suoi dischetti restano due oggetti
   distinti, e distinti vuol dire trascinabili uno senza l'altro: spostare un
   anello per sistemare il piatto e lasciargli indietro i dischetti produce un
   pezzo che si affetta, si stampa, e va buttato — perché i mark del modello non
   sono più dove sono quelli stampati, che è l'unica cosa su cui poggia tutta la
   registrazione. Sei oggetti sciolti sono sei occasioni; due sono due, e stanno
   in un file che si apre da solo con dentro un anello e basta."
  [d]
  (let [c (registration-cage :d d)]
    (mapv (fn [k r] [(str "gabbia-" (round d) "-" (name k) ".3mf")
                     (ring-part c (:axis r))])
          ring-keys (:rings c))))

(defn make-cage-ring
  "Le mesh di un anello della gabbia ⌀`d`, da registrare tu:

     (register Grande (acquire-cage/make-cage-ring 176 :big))
     (register Gabbia (acquire-cage/make-cage-ring 176))        ; :all

   `which` è :big, :medium, :small oppure :all (il default). Torna sempre un
   VETTORE di mesh — l'anello chiaro e i suoi dischetti scuri, che sono due mesh
   perché sono due colori — e `register` i vettori di mesh li accetta, quindi la
   forma qui sopra funziona così com'è.

   Un anello per volta è la forma buona quando poi esporti: quel che esporti è la
   SCENA, quindi con un anello solo registrato il file contiene quello e i suoi
   dischetti, e non c'è nient'altro da spostare per sbaglio."
  ([d] (make-cage-ring d :all))
  ([d which]
   (let [c (registration-cage :d d)
         one (fn [k]
               (let [i (first (keep-indexed (fn [i kk] (when (= kk k) i)) ring-keys))]
                 (when (nil? i)
                   (throw (js/Error. (str "make-cage-ring: l'anello si chiede con "
                                          ":big, :medium, :small o :all — non " which "."))))
                 (ring-part c (:axis (nth (:rings c) i)))))]
     (if (= :all which)
       (vec (apply concat (map one ring-keys)))
       (vec (one which))))))

(defn save-3mf
  "Salva la gabbia ⌀`d` mm come TRE file 3MF a due colori nella cartella `dir` —
   un anello per file, con le sue due corone.

     (acquire-cage/save-3mf 176 \"~/Downloads\")

   `d` è il diametro dell'anello PIÙ GRANDE — quello che misuri col calibro
   attraverso la gabbia montata. Gli altri due vengono di conseguenza.

   Nel browser ti viene chiesta la CARTELLA una volta sola, non il nome tre
   volte: i nomi li sa già, e chiedere tre volte non funzionerebbe comunque (il
   browser concede un dialogo solo, poi smette).

   COME STAMPARLA. Un anello per volta, oppure tutti e tre sullo stesso piatto
   se ci stanno: nel secondo caso i cambi colore avvengono per QUOTA e non per
   pezzo — due soli, con una torre di spurgo minima. Sotto i dischetti della
   faccia inferiore (scuro), poi il corpo degli anelli (chiaro), poi i dischetti
   della faccia superiore (scuro). Usa il BRIM: un anello sottile e largo si
   arriccia raffreddandosi, e un anello imbarcato non è più piano.

   Se metti più anelli sullo stesso piatto, sposta sempre l'anello E i suoi
   dischetti INSIEME: sono due oggetti, e uno spostato senza l'altro dà un pezzo
   che si stampa benissimo e non serve a niente.

   La faccia contro il piatto di stampa verrà più netta dell'altra — è la
   superficie migliore che l'FDM sappia fare, e non c'è modo di averle entrambe
   in una stampata sola. Va bene così: quel che la stampa sbaglia si misura dopo,
   non si insegue prima.

   COME MONTARLA. Gli anelli non si incastrano: si accostano e si incollano. Ogni
   linguetta va infilata finché la sua PUNTA non è a filo col bordo esterno
   dell'anello che tocca — è quello l'allineamento. Sei giunti, colla epossidica.
   Monta prima i due anelli grandi, poi ancora il pezzo al centro, e chiudi con
   l'anello piccolo per ultimo: è quello che porta quattro linguette su sei."
  [d dir]
  (let [c (registration-cage :d d)
        fs (files d)]
    ;; Il salvataggio finisce DOPO questa valutazione (nel browser apre un
    ;; dialogo, poi zippa), quindi nessuna riga qui sotto può dire che i file
    ;; sono stati scritti — e non lo dice. Agganciarci un println asincrono
    ;; sarebbe peggio che tacere: il buffer di stampa viene letto a fine
    ;; valutazione, e il messaggio ricomparirebbe dentro l'esecuzione
    ;; SUCCESSIVA. Il 2026-08-18 una riga sicura di sé ha annunciato un file che
    ;; non era da nessuna parte.
    (save-3mf-set-at fs dir)
    (println (str "Gabbia ⌀" (round d) ": tre anelli ⌀"
                  (apply str (interpose " ⌀" (map (fn [r] (round (* 2 (:outer r))))
                                                  (:rings c))))
                  ", " (:cage-marks c) " mark per faccia su sei facce."))
    (println "  Tre file, un anello per ciascuno — scegli la CARTELLA nel dialogo:")
    (doseq [f fs] (println (str "    " (first f))))
    (println (str "  Apertura libera ⌀" (round (:aperture c))
                  ": è il pezzo più grande che riesci a portare al centro."))
    (println (str "  Dischetti ⌀" (* 2 (:mark-disc-r c))
                  " incassati a filo, spessore anelli " (:cage-h c) " mm."))
    (println "  Stampa col BRIM e filamenti OPACHI.")))

;; --- La culla ----------------------------------------------------------------

(defn cradle
  "Il nido su cui appoggiare la gabbia ⌀`d` mentre scatti: un anello svasato in cui
   la gabbia si posa in QUALUNQUE orientamento senza rotolare.

   Non è una base: la gabbia non ci va fissata. È la differenza che conta, perché
   una gabbia imbullonata a un supporto perde la sua ragione d'essere — la giri, la
   capovolgi, e ogni foto resta nello stesso riferimento. Se la blocchi, torni a
   fotografare mezza sfera, che è quel che il piatto faceva già."
  [d]
  (let [r-out (* 0.42 d)
        r-wide (* 0.34 d)
        r-narrow (* 0.18 d)
        h (* 0.07 d)]
    ;; Il foro è svasato verso ENTRAMBE le facce, sottraendo due coni opposti:
    ;; così la culla funziona da qualunque lato la si posi, e non c'è un verso
    ;; giusto da indovinare — né qui né nello slicer.
    (-> (mesh-difference (rotate (cyl r-out h) :y 90)
                         (rotate (cone r-narrow r-wide (+ h 2)) :y 90)
                         (rotate (cone r-wide r-narrow (+ h 2)) :y 90))
        (color base-color))))

(defn save-cradle
  "Salva la culla per una gabbia ⌀`d` nella cartella `dir`.

     (acquire-cage/save-cradle 176 \"~/Downloads\")

   Stampala col foro in su, senza supporti: la svasatura è abbastanza ripida da
   reggersi da sola."
  [d dir]
  (let [path (str dir "/culla-" (round d) ".3mf")]
    (save-3mf-at (cradle d) path)
    (println (str "Culla per la gabbia ⌀" (round d)
                  ": scegli la cartella nel dialogo che si apre."))))

;; --- Dopo la stampa: la correzione col calibro -------------------------------

(defn measured
  "Il proxy di una gabbia nominalmente ⌀`d` con i mark corretti su quanto è uscito
   DAVVERO dalla stampante: `mis` è il diametro che il calibro legge attraverso
   l'anello più grande, da bordo a bordo esterno.

     (acquire-cage/measured 176 175.4)

   Da usare come :proxy della sessione. Una stampante sbaglia la scala di qualche
   decimo di percento, e quell'errore non si presenta come un errore: la
   registrazione riesce con residui ottimi e tutte le misure escono scalate.

   Qui basta UN numero, mentre il piatto ne voleva due, e non è una semplificazione:
   il piatto è un foglio, e un foglio può uscire a scale diverse sui due assi; la
   gabbia sono tre anelli stampati dalla stessa macchina nello stesso verso, e quel
   che li lega è un fattore solo. Se la macchina sbagliasse in modo diverso sui due
   assi, gli anelli uscirebbero ovali, e un anello ovale si vede."
  [d mis]
  (let [c (registration-cage :d d)
        s (/ mis (* 1.0 d))]
    (assoc c :anchors
           (into {}
                 (map (fn [[id a]]
                        ;; si scalano le POSIZIONI, non heading/up, che sono direzioni
                        [id (assoc a :position (mapv (fn [x] (* x s)) (:position a)))])
                      (:anchors c))))))
