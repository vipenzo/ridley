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

;; --- Il portapezzo: stick ellittici e i loro slot -----------------------------
;;
;; Disegnato, stampato e COLLAUDATO da Vincenzo (2026-08-25): un bastoncino a
;; sezione ellittica scorre in un canale ellittico con gioco; una piccola
;; rotazione e si blocca — il bastoncino fa da camma a se stesso, senza leve né
;; pezzi in più. Quattro o cinque stick, entrando da anelli diversi, ingabbiano
;; il pezzo al centro senza mollette né nastro — che sono esattamente ciò che
;; nella prima sessione reale copriva i dischetti e regalava falsi candidati al
;; riconoscimento.
;;
;; Le SEZIONI sono quelle collaudate, e sono l'unica fonte: stick 4.0×3.6 mm,
;; canale 4.4×4.0. Si infila con le ellissi allineate (0.2 mm di gioco per
;; lato), si ruota di un quarto di giro e l'asse maggiore dello stick (4.0)
;; morde l'asse minore del canale (4.0): frizione piena. Il corpo ATTORNO al
;; canale invece cambia rispetto al pezzo incollabile del collaudo: qui si fonde
;; con l'anello in stampa, come le linguette.

(def stick-section
  "La sezione dello stick: ellisse 4.0×3.6 mm. Quella collaudata."
  (scale-shape (circle 2) 1 0.9))

(def channel-section
  "La sezione del canale: ellisse 4.4×4.0 mm, asse MAGGIORE lungo l'up — che in
   stampa è la verticale, dove il foro orizzontale perde qualche decimo per
   cedimento del ponte: il calo cade sui 0.4 mm di gioco dell'inserimento, non
   sullo zero del bloccaggio, che lavora sull'asse minore stampato in piano."
  (scale-shape (circle 2) 1 1.1))

;; Le MISURE del corpo e del canale non stanno più qui: viaggiano sul proxy,
;; dentro ogni slot (`:body-w :body-len :body-h :body-lift :channel-lift
;; :channel-r`), come già facevano posa, linguette e dischetti. Erano l'ultimo
;; pezzo di gabbia descritto fuori dal modello, e dal 1/9/2026 gli slot si
;; DISEGNANO anche sopra la foto: tre consumatori dello stesso numero sono tre
;; occasioni di divergere, e una divergenza qui non si vede — si stampa.

(defn- to-ring-frame
  "Porta una mesh costruita nel frame PIATTO dell'anello (anello in XY, normale
   +Z — la posa di stampa) nel frame che quell'anello ha nella gabbia. È la
   stessa permutazione ciclica di `place`, scritta come due rotazioni d'asse —
   verificata sulle matrici: per :x, u→y v→z n→x; per :y, u→z v→x n→y."
  [m axis]
  (cond
    (= axis :z) m
    (= axis :x) (rotate (rotate m :x 90) :z 90)
    :else       (rotate (rotate m :z -90) :x -90)))

(defn- slot-pieces
  "[corpi tagli] degli slot di un anello — corpi da UNIRE alla fascia, canali da
   SOTTRARRE. Ogni slot arriva coi suoi dati di posa (da :slots del printable o
   :stick-slots della gabbia); qui se ne usano l'azimut e il raggio, e la
   geometria si costruisce nel frame piatto e si porta in posa con
   `to-ring-frame` — così i numeri vivono in un posto solo, nel proxy.

   Il corpo: un blocco che dal PIATTO DI STAMPA (−h/2) sale sopra la faccia —
   fuso con la fascia dove la copre, in piedi sul piatto dove la sborda verso
   il centro: la ricetta delle linguette, zero sbalzi. Il canale: l'ellisse
   collaudata, asse maggiore verticale, passante e abbondante. Le misure le
   porta ogni slot (`:body-w :body-len :body-h :body-lift :channel-lift
   :channel-r`): vengono dal proxy, non da qui."
  [slots h axis flat?]
  (let [;; ORIENTA PRIMA, TRASLA DOPO: rotate gira attorno alla creation-pose,
        ;; che mesh-translate porta con sé — ruotare dopo la traslazione fa
        ;; girare il blocco su se stesso invece che attorno al centro (trovato
        ;; dal vivo: gli slot finivano tutti ad azimut zero). Quindi il pezzo si
        ;; costruisce centrato all'origine, si ruota lì, e la POSIZIONE arriva
        ;; già pronta dai dati di posa del proxy.
        orient (fn [m azim] (let [r (rotate m :z azim)]
                              (if flat? r (to-ring-frame r axis))))
        one (fn [mk lift]
              (map (fn [sl]
                     (mesh-translate (orient (mk sl) (:azimuth-deg sl))
                                     (v+ (:position sl) (v* (:up sl) (lift sl)))))
                   slots))]
    [(one (fn [sl] (box (:body-w sl) (:body-h sl) (:body-len sl)))
          (fn [sl] (:body-lift sl)))
     (one (fn [sl] (let [[across up] (:channel-r sl)]
                     (scale (cyl across 80) 1 1.0 (/ up across))))
          (fn [sl] (+ (/ h 2.0) (:channel-lift sl))))]))

(defn stick
  "Uno stick da stampare: sezione ellittica collaudata (4.0×3.6), lungo `len`
   (default 60 — quello provato; per arrivare al centro dall'anello grande di
   una ⌀176 servono ~80).

     (register Stick (acquire-cage/stick))
     (register Lungo (acquire-cage/stick 80))

   Si infila nel canale di uno slot con le ellissi allineate, si spinge fino a
   toccare il pezzo, e un quarto di giro lo blocca: lo stick fa da camma a se
   stesso. Va stampato SDRAIATO, e la piccola perdita di rotondità del lato
   ponte cade dove c'è gioco, non dove morde."
  ([] (stick 60))
  ([len] (scale (cyl 2 len) 1 1.0 0.9)))

(defn punta-tricuspide
  "Il piedino a tre punte di Vincenzo (2026-08-25), stampato e provato: tre
   sfere schiacciate fuse a blend che fanno una presa a tre contatti — su una
   superficie convessa tre punti non scivolano e non rotolano. Si monta sulla
   punta dello stick con lo stesso principio dello slot: canale ellittico,
   infili, ruoti, bloccato. Opzionale: per pezzi delicati o lisci; sul resto la
   punta nuda dello stick basta.

   Costruito alla posa corrente della turtle, come ogni pezzo del DSL."
  []
  (mesh-difference
   (mesh-union
    (attach (cyl 3 3) (f 2))
    (sdf-blend
     (sdf-blend
      (attach (scale (sdf-sphere 2) 2 1 1) (cp-f -3) (tv 120))
      (attach (scale (sdf-sphere 2) 2 1 1) (cp-f -3) (tv -0) (th 60) (tv -75) (th 30))
      1.5)
     (attach (scale (sdf-sphere 2) 2 1 1) (cp-f -3) (tv -120) (th -15) (tv -15) (th -30))
     1.5))
   (attach (extrude (scale-shape (circle 2) 1.1 1) (f 60)) (f -20))))

(defn- build-ring
  "L'anello `axis` di `c` come [base dischetti]. Con `flat?` vero l'anello esce
   nel SUO frame — piatto in XY, mark sulle facce ±Z, linguette verso l'alto —
   che è la posa in cui si stampa; con `flat?` falso esce dov'è nella gabbia."
  [c axis flat?]
  (let [p (cage-printable-ring c axis)
        h (:h p)
        disc-r (:mark-disc-r c)
        r (first (filter (fn [x] (= axis (:axis x))) (:rings c)))
        ax (if flat? :z axis)
        ;; nella posa piatta le coordinate sono già quelle giuste; in quella
        ;; della gabbia si leggono dagli anchor, che sono la stessa cosa vista
        ;; dall'altra parte di `unplace`
        marks (if flat?
                (:marks p)
                (map (fn [[_ a]] {:position (:position a) :heading (:heading a)})
                     (ring-anchors c axis)))
        tabs-boxes (if flat?
                     (:tabs p)
                     (filter (fn [t] (= axis (:owner t))) (:tabs c)))
        slots (if flat?
                (:slots p)
                (filter (fn [sl] (= axis (:axis sl))) (:stick-slots c)))
        [slot-bodies slot-cuts] (slot-pieces slots h axis flat?)
        ;; il taglierino sporge 2 mm sopra la faccia: taglia netto, senza facce
        ;; complanari (la ricetta nota degli artefatti CSG)
        over 2.0
        pocket (fn [m]
                 (let [n (:heading m)]
                   (mesh-translate (ax-cyl (axis-of n) disc-r (+ inlay over))
                                   (v+ (:position m) (v* n (/ (- over inlay) 2.0))))))
        disc (fn [m]
               (let [n (:heading m)]
                 (mesh-translate (ax-cyl (axis-of n) disc-r inlay)
                                 (v+ (:position m) (v* n (- (/ inlay 2.0)))))))
        annulus (mesh-difference (ax-cyl ax (:outer r) h)
                                 (ax-cyl ax (:inner r) (+ h 2)))
        ;; `box` prende (destra, su, avanti) — la convenzione della tartaruga —
        ;; e alla posa di partenza quelle sono (y, z, x). Le linguette arrivano
        ;; come ingombri di mondo [dx dy dz], quindi vanno rimesse in
        ;; quest'ordine. Passarle così com'erano dava linguette girate di 90°, e
        ;; la prova non è stata leggere il codice: è stato misurare l'ingombro
        ;; del pezzo uscito.
        as-box (fn [t]
                 (let [sz (:size t)]
                   (mesh-translate (box (nth sz 1) (nth sz 2) (nth sz 0))
                                   (:center t))))
        ;; una scatola è o materiale dell'anello (linguette, battute, la SPINA
        ;; della chiave di montaggio) o un TAGLIO (la tacca che la riceve):
        ;; lo dice il suo :kind, e la tacca è l'unico taglio della famiglia
        cuts (concat (map as-box (filter (fn [t] (= :key-notch (:kind t))) tabs-boxes))
                     slot-cuts)
        tabs (concat (map as-box (remove (fn [t] (= :key-notch (:kind t))) tabs-boxes))
                     slot-bodies)
        solid (as-> annulus m
                (if (empty? tabs) m (mesh-union (cons m tabs)))
                (if (empty? cuts) m (mesh-difference (cons m cuts))))]
    ;; :export-group lega le due mesh in UN oggetto con due parti. Senza,
    ;; lo slicer le tratta come corpi indipendenti: appoggia ciascuno sul
    ;; piatto per conto suo e mette i supporti sotto i dischetti della faccia
    ;; superiore, che da soli galleggiano.
    (let [g (str "anello-" (name axis))]
      [(-> (mesh-difference (cons solid (map pocket marks)))
           (color base-color)
           (assoc :export-group g :export-name "anello"))
       (-> (mesh-union (map disc marks))
           (color mark-color)
           (assoc :export-group g :export-name "dischetti"))])))

(defn ring-part
  "L'anello `axis` della gabbia `c` come [base dischetti], NELLA POSA DELLA
   GABBIA: l'anello chiaro con le tasche su tutte e due le facce e le sue
   linguette, e i dischetti scuri che le riempiono a filo."
  [c axis]
  (build-ring c (:axis (first (filter (fn [x] (= axis (:axis x))) (:rings c))))
              false))

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
                     (build-ring c (:axis r) true)])
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

(defn make-print-ring
  "Le mesh di UN anello nella posa di STAMPA — piatto, linguette in su, pronto da
   esportare senza ruotare niente:

     (register Grande (acquire-cage/make-print-ring 176 :big))
     (export :Grande :3mf)

   La differenza con `make-cage-ring` è solo l'orientamento, non la geometria:
   quello ti dà l'anello dov'è nella gabbia (giusto per guardarla montata, due
   anelli su tre in piedi), questo te lo dà steso sul piano.

   Conta perché nello slicer anello e dischetti sono due oggetti: ruotarne uno
   e non l'altro lascia i dischetti per aria, e il pezzo si stampa lo stesso —
   senza mark. Se il file arriva già disteso, non c'è niente da ruotare."
  [d which]
  (let [c (registration-cage :d d)
        k (first (keep-indexed (fn [i kk] (when (= kk which) i)) ring-keys))]
    (when (nil? k)
      (throw (js/Error. (str "make-print-ring: l'anello si chiede con :big, "
                             ":medium o :small — non " which "."))))
    (vec (build-ring c (:axis (nth (:rings c) k)) true))))

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
