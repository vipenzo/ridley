;; ============================================================================
;; STAMPA DELLA GABBIA DI REGISTRAZIONE
;; ============================================================================
;;
;; La gabbia è l'alternativa al piatto: invece di appoggiare l'oggetto sopra il
;; riferimento, il riferimento viaggia INTORNO all'oggetto. Tre anelli piatti,
;; ortogonali fra loro, il pezzo ancorato al centro. Da qualunque parte scatti,
;; nell'inquadratura c'è sempre almeno un anello ben visto — così ogni foto si
;; registra da sola, senza giradischi, angoli, NOTE.md né sessioni da fondere.
;;
;; Questo file È il codice che produce la gabbia — era la libreria builtin
;; `acquire-cage`, ed è diventato un esempio per decisione di Vincenzo (4/9):
;; si usa una tantum (non deve ingombrare la lista delle librerie per sempre)
;; e merita di essere LETTO e MODIFICATO, non nascosto dietro un register.
;;
;; COME SI USA: valuta il file (le definizioni non producono niente da sole),
;; poi scommenta in fondo il comando che ti serve e rivaluta.
;;
;; La geometria non è scritta due volte: tutto qui dentro legge posizioni,
;; linguette, tasche e canali dal proxy `registration-cage`. I dischetti che
;; stampi SONO i mark che il programma cercherà. La DICHIARAZIONE della gabbia
;; (diametro, :gen, :rim-marks?) sta in fondo, in un posto solo.
;;
;; NOTA (4/9): i segmenti sul bordo (:rim-marks?) non sono ancora nella
;; geometria di stampa — il modello li disegna sopra le foto, ma qui i nastri
;; a due colori mancano. Chiedili quando decidi di ristampare.

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

(defn ax-cyl [axis r h]
  (cond
    (= axis :x) (cyl r h)
    (= axis :y) (rotate (cyl r h) :z 90)
    :else       (rotate (cyl r h) :y 90)))

(defn axis-of
  "L'asse di un mark, dedotto dalla sua normale: la componente che non è zero."
  [[nx ny nz]]
  (cond (not= 0.0 nx) :x
        (not= 0.0 ny) :y
        :else :z))

(defn v+ [a b] (mapv + a b))
(defn v* [v k] (mapv (fn [c] (* c k)) v))

;; --- Un anello ---------------------------------------------------------------

(defn ring-letter
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
;; il pezzo al centro senza mollette né nastro.
;;
;; Le sezioni vivono nel PROXY (`:channel-r` di ogni slot) e dal 4/9 SCALANO
;; con la gabbia — (d/176)^0.75, pavimento 1: stick più lunghi troppo fini si
;; spezzano — con i GIOCHI da 0.4mm assoluti (tolleranza di stampante, non
;; fisica: scalarli romperebbe il quarto di giro). A ⌀176 i numeri sono quelli
;; collaudati: stick 4.0×3.6, canale 4.4×4.0.

(defn stick
  "Uno stick da stampare per la gabbia `c`, lungo `len` (default 60; per
   arrivare al centro dall'anello grande di una ⌀176 servono ~80). La sezione
   la detta il CANALE della gabbia — il morso del quarto di giro è garantito a
   ogni diametro. Va stampato SDRAIATO: la piccola perdita di rotondità del
   lato ponte cade dove c'è gioco, non dove morde."
  ([c] (stick c 60))
  ([c len]
   (let [[across _up] (:channel-r (first (:stick-slots c)))
         maj across                ; semiasse maggiore = morso del canale
         mn (- maj 0.2)]           ; semiasse minore: −0.4 sul diametro
     (scale (cyl maj len) 1 1.0 (/ mn maj)))))

(defn punta-tricuspide
  "Il piedino a tre punte di Vincenzo (2026-08-25), stampato e provato: tre
   sfere schiacciate fuse a blend che fanno una presa a tre contatti — su una
   superficie convessa tre punti non scivolano e non rotolano. Si monta sulla
   punta dello stick con lo stesso principio dello slot: canale ellittico,
   infili, ruoti, bloccato. Opzionale: per pezzi delicati o lisci; sul resto la
   punta nuda dello stick basta.

   Costruito alla posa corrente della turtle, come ogni pezzo del DSL.
   Attenzione: il canale interno è quello della ⌀176 — per una gabbia scalata
   va allargato a mano (la punta è un pezzo di collaudo, non parametrico)."
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

;; --- Dal frame piatto a quello della gabbia -----------------------------------

(defn to-ring-frame
  "Porta una mesh costruita nel frame PIATTO dell'anello (anello in XY, normale
   +Z — la posa di stampa) nel frame che quell'anello ha nella gabbia. È la
   stessa permutazione ciclica di `place`, scritta come due rotazioni d'asse —
   verificata sulle matrici: per :x, u→y v→z n→x; per :y, u→z v→x n→y."
  [m axis]
  (cond
    (= axis :z) m
    (= axis :x) (rotate (rotate m :x 90) :z 90)
    :else       (rotate (rotate m :z -90) :x -90)))

(defn slot-pieces
  "[corpi tagli] degli slot di un anello — corpi da UNIRE alla fascia, canali da
   SOTTRARRE. Ogni slot arriva coi suoi dati di posa (da :slots del printable o
   :stick-slots della gabbia); la geometria si costruisce nel frame piatto e si
   porta in posa con `to-ring-frame` — i numeri vivono in un posto solo, nel
   proxy."
  [slots h axis flat?]
  (let [;; ORIENTA PRIMA, TRASLA DOPO: rotate gira attorno alla creation-pose,
        ;; che mesh-translate porta con sé — ruotare dopo la traslazione fa
        ;; girare il blocco su se stesso invece che attorno al centro (trovato
        ;; dal vivo: gli slot finivano tutti ad azimut zero).
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

;; --- Un anello, base + dischetti ----------------------------------------------

(defn build-ring
  "L'anello `axis` di `c` come [base dischetti]. Con `flat?` vero l'anello esce
   nel SUO frame — piatto in XY, mark sulle facce ±Z, linguette verso l'alto —
   che è la posa in cui si stampa; con `flat?` falso esce dov'è nella gabbia."
  [c axis flat?]
  (let [p (cage-printable-ring c axis)
        h (:h p)
        disc-r (:mark-disc-r c)
        r (first (filter (fn [x] (= axis (:axis x))) (:rings c)))
        ax (if flat? :z axis)
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
        ;; quest'ordine.
        as-box (fn [t]
                 (let [sz (:size t)]
                   (mesh-translate (box (nth sz 1) (nth sz 2) (nth sz 0))
                                   (:center t))))
        ;; una scatola è o materiale dell'anello (linguette, battute, le spine
        ;; delle chiavi) o un TAGLIO nel suo proprietario: le tacche che
        ;; ricevono le spine e le TASCHE d'invito (gen 2) in cui le linguette
        ;; del partner affondano
        cut? (fn [t] (contains? #{:key-notch :seat} (:kind t)))
        cuts (concat (map as-box (filter cut? tabs-boxes))
                     slot-cuts)
        tabs (concat (map as-box (remove cut? tabs-boxes))
                     slot-bodies)
        solid (as-> annulus m
                (if (empty? tabs) m (mesh-union (cons m tabs)))
                (if (empty? cuts) m (mesh-difference (cons m cuts))))]
    ;; :export-group lega le due mesh in UN oggetto con due parti. Senza,
    ;; lo slicer le tratta come corpi indipendenti.
    (let [g (str "anello-" (name axis))]
      [(-> (mesh-difference (cons solid (map pocket marks)))
           (color base-color)
           (assoc :export-group g :export-name "anello"))
       (-> (mesh-union (map disc marks))
           (color mark-color)
           (assoc :export-group g :export-name "dischetti"))])))

(defn ring-part
  "L'anello `axis` della gabbia `c` come [base dischetti], NELLA POSA DELLA
   GABBIA: per guardarla montata."
  [c axis]
  (build-ring c axis false))

;; --- La gabbia intera ---------------------------------------------------------

(def ring-keys
  "I tre anelli, dal più grande al più piccolo. Si chiamano per DIMENSIONE e non
   per asse, perché è così che li distingui quando ce li hai in mano."
  [:big :medium :small])

(defn ring-axis-of
  "L'asse dell'anello `which` (:big/:medium/:small) della gabbia `c`."
  [c which]
  (let [i (first (keep-indexed (fn [i kk] (when (= kk which) i)) ring-keys))]
    (when (nil? i)
      (throw (js/Error. (str "l'anello si chiede con :big, :medium o :small — non "
                             which "."))))
    (:axis (nth (:rings c) i))))

(defn files
  "I tre file da stampare per la gabbia `c`, come coppie [nome pezzi]: UN
   ANELLO PER FILE, con le sue due corone di dischetti — nello slicer un anello
   e i suoi dischetti sono due oggetti, e spostarne uno senza l'altro produce
   un pezzo che si stampa benissimo e non serve a niente."
  [c]
  (let [d (:cage-d c)]
    (mapv (fn [k r] [(str "gabbia-" (round d) "-" (name k) ".3mf")
                     (build-ring c (:axis r) true)])
          ring-keys (:rings c))))

(defn make-cage-ring
  "Le mesh di un anello della gabbia `c`, da registrare tu — :big/:medium/
   :small oppure :all (il default). Torna un VETTORE di mesh (anello chiaro +
   dischetti scuri), e `register` i vettori li accetta."
  ([c] (make-cage-ring c :all))
  ([c which]
   (if (= :all which)
     (vec (apply concat (map (fn [k] (ring-part c (ring-axis-of c k))) ring-keys)))
     (vec (ring-part c (ring-axis-of c which))))))

(defn make-print-ring
  "Le mesh di UN anello nella posa di STAMPA — piatto, linguette in su, pronto
   da esportare senza ruotare niente. La differenza con `make-cage-ring` è solo
   l'orientamento: quello serve a guardare la gabbia montata, questo a
   stampare."
  [c which]
  (vec (build-ring c (ring-axis-of c which) true)))

(defn save-3mf
  "Salva la gabbia `c` come TRE file 3MF a due colori nella cartella `dir` —
   un anello per file, con le sue due corone.

   COME STAMPARLA: un anello per volta, o tutti e tre sullo stesso piatto (i
   cambi colore avvengono per QUOTA: dischetti sotto, corpo, dischetti sopra).
   BRIM sempre: un anello sottile e largo si arriccia raffreddandosi. Filamenti
   OPACHI. Se sposti un anello nello slicer, sposta anche i suoi dischetti.

   COME MONTARLA (gen 2): ogni linguetta CADE nella sua tasca — l'azimut è
   quello, non si cerca a occhio — e le due chiavi rifiutano rotazioni e
   ribaltamenti sbagliati: se un anello non si appoggia, è girato. Sei giunti,
   colla epossidica; prima i due grandi, poi il pezzo al centro, l'anello
   piccolo per ultimo."
  [c dir]
  (let [fs (files c)]
    (save-3mf-set-at fs dir)
    (println (str "Gabbia ⌀" (round (:cage-d c)) " (gen " (or (:cage-gen c) 1) "): tre anelli ⌀"
                  (apply str (interpose " ⌀" (map (fn [r] (round (* 2 (:outer r))))
                                                  (:rings c))))
                  ", " (:cage-marks c) " mark per faccia su sei facce."))
    (println "  Tre file, un anello per ciascuno — scegli la CARTELLA nel dialogo:")
    (doseq [f fs] (println (str "    " (first f))))
    (println (str "  Apertura libera ⌀" (round (:aperture c))
                  ": è il pezzo più grande che riesci a portare al centro."))
    (println (str "  Dischetti ⌀" (* 2 (:mark-disc-r c))
                  " incassati a filo, spessore anelli " (:cage-h c) " mm."))
    (when (:rim-marks? c)
      (println "  ATTENZIONE: i segmenti sul bordo NON sono ancora nella geometria di stampa."))
    (println "  Stampa col BRIM e filamenti OPACHI.")))

;; --- La culla -----------------------------------------------------------------

(defn cradle
  "Il nido su cui appoggiare la gabbia ⌀`d` mentre scatti: un anello svasato in
   cui la gabbia si posa in QUALUNQUE orientamento senza rotolare. Non è una
   base: una gabbia imbullonata a un supporto torna a fotografare mezza sfera,
   che è quel che il piatto faceva già."
  [d]
  (let [r-out (* 0.42 d)
        r-wide (* 0.34 d)
        r-narrow (* 0.18 d)
        h (* 0.07 d)]
    (-> (mesh-difference (rotate (cyl r-out h) :y 90)
                         (rotate (cone r-narrow r-wide (+ h 2)) :y 90)
                         (rotate (cone r-wide r-narrow (+ h 2)) :y 90))
        (color base-color))))

(defn save-cradle
  "Salva la culla per una gabbia ⌀`d` nella cartella `dir`. Stampala col foro
   in su, senza supporti: la svasatura si regge da sola."
  [d dir]
  (save-3mf-at (cradle d) (str dir "/culla-" (round d) ".3mf"))
  (println (str "Culla per la gabbia ⌀" (round d)
                ": scegli la cartella nel dialogo che si apre.")))

;; --- Dopo la stampa: la correzione col calibro ---------------------------------

(defn measured
  "Il proxy della gabbia `c` con i mark corretti su quanto è uscito DAVVERO
   dalla stampante: `mis` è il diametro che il calibro legge attraverso
   l'anello più grande, da bordo a bordo esterno. Da usare come :proxy della
   sessione — una stampante sbaglia la scala di qualche decimo di percento, e
   quell'errore non si presenta: la registrazione riesce con residui ottimi e
   tutte le misure escono scalate. Un numero solo, perché tre anelli stampati
   dalla stessa macchina nello stesso verso condividono un fattore solo (se
   sbagliasse diversamente sui due assi, gli anelli uscirebbero ovali — e un
   anello ovale si vede)."
  [c mis]
  (let [s (/ mis (* 1.0 (:cage-d c)))]
    (assoc c :anchors
           (into {}
                 (map (fn [[id a]]
                        ;; si scalano le POSIZIONI, non heading/up (direzioni)
                        [id (assoc a :position (mapv (fn [x] (* x s)) (:position a)))])
                      (:anchors c))))))

;; ============================================================================
;; COMANDI OPERATIVI — scommenta quello che serve e rivaluta il file
;; ============================================================================

(def diametro 176)

(def gabbia
  "LA dichiarazione, in un posto solo: la prossima stampa è gen 2 (tasche
   d'invito + seconda chiave: niente più fasi a occhio, niente più flip
   possibili) con i segmenti sul bordo. La gabbia GIÀ INCOLLATA di battiscopa
   resta (registration-cage :d 176 :flips #{:y :z}) — dichiarala com'è, non
   come vorresti che fosse."
  (registration-cage :d diametro :gen 2 :rim-marks? true))

;; Guardala montata (tre anelli in posa, due colori):
;; (register Gabbia (make-cage-ring gabbia))

;; Un anello in posa di stampa, poi esporta:
;; (register Grande (make-print-ring gabbia :big))
;; (export :Grande :3mf)

;; Tutti e tre i 3MF in una cartella (un dialogo solo):
;; (save-3mf gabbia "~/Downloads")

;; La culla su cui appoggiarla mentre scatti:
;; (save-cradle diametro "~/Downloads")

;; Uno stick (sezione presa dal canale della gabbia: il morso è garantito):
;; (register Stick (stick gabbia 80))
;; Il piedino a tre punte, se il pezzo è delicato:
;; (register Punta (punta-tricuspide))

;; Dopo la stampa, col calibro (esempio: letti 175.4 sull'anello grande):
;; (measured gabbia 175.4)   ; da passare come :proxy della sessione
