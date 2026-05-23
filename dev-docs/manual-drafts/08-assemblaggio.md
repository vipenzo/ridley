# 8. Assemblaggio

Fino a qui hai costruito pezzi singoli: una mesh, un'estrusione, una forma modellata con le shape-fn. Questo capitolo spiega come metterli insieme.

Assemblare in Ridley significa posizionare componenti nello spazio usando la tartaruga come strumento di posizionamento. Il principio è lo stesso che hai usato finora per muoverti e costruire: dai comandi alla tartaruga (`f`, `th`, `tv`, `rt`...), e lei si sposta. La differenza è che ora la tartaruga trascina con sé un pezzo già costruito invece di costruirne uno nuovo.

Il capitolo introduce quattro idee che si combinano fra loro: i marcatori come sistema per scoprire e nominare i punti di aggancio di un assemblaggio, la creation-pose come meccanismo per decidere *dove* su un componente cade il punto di aggancio, `attach` come operazione di posizionamento, e lo skeleton come struttura portante che tiene insieme tutto.

## 8.1 I marcatori come strumento di scoperta

Un marcatore (`mark`) è una bandierina che pianti dentro un `path` per dire "questo punto mi interessa, ci tornerò". Al momento della creazione registra la posizione e l'orientamento della tartaruga, e gli dà un nome.

L'uso più semplice è un path che descrive il contorno di un pezzo e piazza marcatori dove andranno montati dei componenti. Immagina una piastra rettangolare con i piedini negli angoli:

```clojure
(def W 80) (def D 50) (def foot-h 8)

;; Skeleton: il rettangolo con un mark per angolo
(def plate-skel
  (path
   (mark :foot-1)
   (f W) (mark :foot-2)
   (th -90) (f D) (mark :foot-3)
   (th -90) (f W) (mark :foot-4)))

;; Piedino
(def foot (cyl 4 foot-h))

;; Piastra
(def plate (extrude (rect W D) (f 3)))

;; Assemblaggio
(register table
  (mesh-union
   plate
   (for [m (keys (anchors plate-skel))]
     (attach foot (move-to plate-skel :at m)))))
```

<!-- example-source: marks-simple-table
(def W 80) (def D 50) (def foot-h 8)
(def plate-skel
  (path
   (mark :foot-1)
   (f W) (mark :foot-2)
   (th -90) (f D) (mark :foot-3)
   (th -90) (f W) (mark :foot-4)))
(def foot (cyl 4 foot-h))
(def plate (extrude (rect W D) (f 3)))
(register table
  (mesh-union
   plate
   (for [m (keys (anchors plate-skel))]
     (attach foot (move-to plate-skel :at m)))))
-->

`(anchors plate-skel)` restituisce una mappa `{nome → posa}` con tutti i marcatori del path. `(keys ...)` dà la lista dei nomi. Il `for` scorre i marcatori e aggancia un piedino a ciascuno con `move-to`. Se cambi le dimensioni `W` e `D`, i piedini seguono automaticamente.

Questo esempio è volutamente elementare: quattro marcatori piazzati a mano, un solo tipo di componente, nessun filtro. Basta per assemblare molte cose. Ma quando i marcatori diventano tanti e hanno ruoli diversi, serve un pattern più strutturato.

### Quando i marcatori diventano tanti

Se il numero di marcatori dipende da un parametro (una lista, un contatore), nominarli a mano è tedioso. Il pattern è: genera i nomi programmaticamente dentro un `path`, poi recuperali con `anchors` e un filtro.

```clojure
(def row-skel
  (path
   (mark :end-post-start)
   (f 20)
   (doseq [i (range 3)]
     (mark (keyword (str "mid-post-" i)))
     (f 20))
   (mark :end-post-finish)))
```

<!-- example-source: marks-gen-row
(def row-skel
  (path
   (mark :end-post-start)
   (f 20)
   (doseq [i (range 3)]
     (mark (keyword (str "mid-post-" i)))
     (f 20))
   (mark :end-post-finish)))
(println (keys (anchors row-skel)))
-->

Cinque marcatori: due con ruolo "end-post" (i montanti d'angolo), tre con ruolo "mid-post" (i montanti intermedi). Il prefisso raggruppa per ruolo, l'indice distingue.

Per montare componenti diversi per ruolo, filtra i nomi:

```clojure
(def end-post (cyl 8 40))
(def mid-post (cyl 4 30))

(register fence
  (concat-meshes
   (for [m (filter #(re-find #"^end-post-" (name %))
                   (keys (anchors row-skel)))]
     (attach end-post (move-to row-skel :at m :align)))
   (for [m (filter #(re-find #"^mid-post-" (name %))
                   (keys (anchors row-skel)))]
     (attach mid-post (move-to row-skel :at m :align)))))
```

<!-- example-source: marks-fence
(def row-skel
  (path
   (mark :end-post-start)
   (f 20)
   (doseq [i (range 3)]
     (mark (keyword (str "mid-post-" i)))
     (f 20))
   (mark :end-post-finish)))
(def end-post (cyl 8 40))
(def mid-post (cyl 4 30))
(register fence
  (concat-meshes
   (for [m (filter #(re-find #"^end-post-" (name %))
                   (keys (anchors row-skel)))]
     (attach end-post (move-to row-skel :at m :align)))
   (for [m (filter #(re-find #"^mid-post-" (name %))
                   (keys (anchors row-skel)))]
     (attach mid-post (move-to row-skel :at m :align)))))
-->

Il filtro è ciò che trasforma `keys` in un selettore consapevole dei ruoli. Senza di esso avresti due strade: elencare i marcatori per nome (vanificando la generazione programmatica) o costruire skeleton separati (frammentando la fonte di verità). Il filtro tiene uno skeleton, due ruoli, due componenti.

### Attenzione ai pattern di naming

Un filtro come `#"bracket"` cattura qualsiasi marcatore con "bracket" nel nome. Se lo skeleton acquisisce in futuro un `:bracket-spec` o un `:end-bracket` per uno scopo diverso, finiscono nel filtro per errore. Usa prefissi distintivi e regex ancorate (`#"^bracket-"`) quando c'è rischio di collisione.

L'ordine di `(keys ...)` è quello di inserimento, ma se i marcatori rappresentano un insieme di pezzi equivalenti, non affidarti all'ordine. Se l'ordine conta (per esempio sinistra prima di destra), codificalo nei nomi (suffisso `-l`/`-r`) e ordina o partiziona esplicitamente.

## 8.2 Creation-pose e creation-pose shifting

Ogni mesh ha un punto di ancoraggio: la posizione e l'orientamento della tartaruga al momento della creazione. È la *creation-pose*. Quando qualcosa si aggancia alla mesh (con `attach` e `move-to`), è lì che atterra.

Il problema è che il punto di ancoraggio di default non è sempre quello che ti serve. Le primitive (`box`, `cyl`, `sphere`, `cone`) hanno la creation-pose al centro della geometria. Le estrusioni e i loft ce l'hanno all'inizio del percorso. Se vuoi agganciare un pezzo in un punto diverso, devi spostare la creation-pose.

### Come funziona

La famiglia `cp-*` scorre la geometria sotto l'ancora (che resta ferma) in modo che un punto scelto venga a coincidere con essa:

`(cp-f n)` riancora al punto `+n` lungo l'heading. La geometria scorre all'indietro di `n`.

`(cp-u n)` riancora al punto `+n` lungo l'up. La geometria scorre verso il basso di `n`.

`(cp-rt n)` riancora al punto `+n` lungo il right. La geometria scorre a sinistra di `n`.

L'effetto si vede al momento dell'assemblaggio:

```clojure
;; Box con creation-pose al centro (default per le primitive)
(def box-default (box 40 40 40))

;; Stessa box, creation-pose spostata al centro della faccia top
(def box-top-anchor
  (attach (box 40 40 40) (cp-u 20)))
```

<!-- example-source: cp-shift-box
(register box-default (box 40 40 40))
(register box-top-anchor (attach (box 40 40 40) (cp-u 20)))
-->

Se agganci qualcosa a `box-default`, atterra al centro del cubo. Se agganci a `box-top-anchor`, atterra sulla faccia superiore. Stessa geometria, diverso punto di aggancio.

### La creation-pose come pivot di rotazione

Una rotazione applicata dentro `attach` ruota il pezzo attorno alla creation-pose. Questo è il motivo principale per cui vale la pena spostare la creation-pose: non solo per posizionare correttamente, ma per ruotare attorno al punto giusto.

```clojure
;; Senza shift: ruota attorno al centro del box
(attach (box 40 40 40) (tv 30))

;; Con shift al pin axis: ruota attorno al pin
(def inner-shell
  (attach (box inner-w inner-d H)
          (cp-f (- (/ H 2)))))
(attach inner-shell (tv 60))   ;; ruota attorno al pin
```

<!-- example-source: cp-shift-rotation
(def H 60)
(register shell-default (attach (box 20 20 60) (tv 30)))
(register shell-shifted
  (attach
    (attach (box 20 20 60) (cp-f -30))
    (tv 30)))
-->

Se una rotazione sembra sbagliata (il pezzo ruota attorno a un punto inaspettato, vola nello spazio), controlla prima la creation-pose. Quasi sempre il problema è lì.

### Quando usare cp-* e quando no

Se il tuo assemblaggio ha tre o quattro pezzi con punti di aggancio ovvi (il centro di un cilindro, l'inizio di un'estrusione), probabilmente non serve spostare la creation-pose. Se stai costruendo un assemblaggio parametrico dove i componenti si agganciano a uno skeleton con `move-to :align`, lo shift della creation-pose è quasi sempre necessario per far atterrare ogni pezzo nel punto giusto.

L'alternativa (lasciare la creation-pose al default e compensare con `(f ...)` o rotazioni al momento dell'assemblaggio) funziona, ma sparge la geometria nel codice. Con la creation-pose spostata nel punto giusto, la chiamata di assemblaggio resta pulita: `(attach component (move-to skel :at :mark :align))` e nient'altro.

## 8.3 Attach su mesh e marcatori

`attach` è l'operazione fondamentale di posizionamento. Prende un pezzo già costruito e lo trasforma usando comandi tartaruga: sposta, ruota, scala lungo assi locali. Il risultato è una nuova mesh (o SDF) con la geometria trasformata; l'originale resta invariato.

### La forma base

```clojure
(def b (box 20))

;; Sposta in avanti e ruota
(register b2 (attach b (f 30) (th 45)))

;; Concatena quanti comandi servono
(register b3 (attach b (f 10) (tv 30) (rt 5) (f 20)))
```

<!-- example-source: attach-basic
(register b (box 20))
(register b2 (attach (box 20) (f 30) (th 45)))
-->

I comandi dentro `attach` sono gli stessi della tartaruga: `f`, `th`, `tv`, `tr`, `rt`, `u`, `lt`. L'ordine conta: `(f 10) (th 45)` è diverso da `(th 45) (f 10)`. Il primo avanza e poi ruota, il secondo ruota e poi avanza (in una direzione diversa).

### Attach!: la forma imperativa

`attach!` è la variante che modifica direttamente una mesh registrata:

```clojure
(register b (box 20))
(attach! :b (f 30) (th 45))    ;; modifica :b in posto
```

Il nome è una keyword (`:b`, non `b`). Il pezzo nel registro viene sostituito con la versione trasformata. Comodo nella REPL per spostare un pezzo senza riscrivere tutto.

### Move-to: agganciare a un marcatore

`move-to` è il comando che collega `attach` al sistema di marcatori e skeleton. Dentro un `attach`, `move-to` teletrasporta la tartaruga alla posizione di un marcatore:

```clojure
;; Aggancia disk al marcatore :mid dello skeleton
(register placed (attach disk (move-to skel :at :mid)))

;; Con :align, anche l'orientamento segue il marcatore
(register placed (attach disk (move-to skel :at :mid :align)))
```

<!-- example-source: attach-move-to
(def skel (path (mark :base) (f 30) (mark :mid) (f 30) (mark :top)))
(def disk (cyl 15 4))
(register placed (attach disk (move-to skel :at :mid :align)))
-->

Senza `:align`, `move-to` trasla soltanto: il pezzo si sposta alla posizione del marcatore ma mantiene il suo orientamento originale. Con `:align`, anche l'orientamento del pezzo si allinea a quello del marcatore. La distinzione conta quando il marcatore ha un heading significativo (per esempio un marcatore su uno skeleton il cui heading indica "verso l'esterno").

`move-to` accetta sia un path che il nome di una mesh registrata:

```clojure
;; Da un path
(attach piece (move-to my-path :at :mark-name :align))

;; Da una mesh registrata
(attach piece (move-to :parent-mesh :at :slot :align))
```

### Come i marcatori finiscono su una mesh

Un path ha marcatori perché li hai piazzati con `mark`. Ma `move-to` accetta anche una mesh registrata come sorgente di marcatori. Come ci arrivano?

`attach-path` associa un path (con i suoi marcatori) a una mesh già registrata:

```clojure
(register column (cyl 5 60))
(attach-path :column (path (mark :base) (f 60) (mark :top)))

;; Ora puoi agganciare cose ai marcatori della mesh
(register cap (attach (sphere 8) (move-to :column :at :top)))
```

<!-- example-source: attach-path-mesh
(register column (cyl 5 60))
(attach-path :column (path (mark :base) (f 60) (mark :top)))
(register cap (attach (sphere 8) (move-to :column :at :top)))
-->

I marcatori creati con `mark` dentro un `attach` su SDF sopravvivono automaticamente attraverso le booleane e la materializzazione. Per le mesh, `attach-path` è la via esplicita.

### Play-path: replay di un percorso

Se hai un percorso calcolato e vuoi usarlo per posizionare un pezzo dentro `attach`, `play-path` lo riproduce:

```clojure
(defn branch-path [l]
  (path (tv 90) (f (/ l 8)) (tv -90) (f l)))

(register Y (attach (sphere 3) (play-path (branch-path 30))))
```

<!-- example-source: attach-play-path
(defn branch-path [l]
  (path (tv 90) (f (/ l 8)) (tv -90) (f l)))
(register Y (attach (sphere 3) (play-path (branch-path 30))))
-->

`play-path` risolve un problema tecnico: le funzioni che restituiscono path catturano i binding globali di `f`/`th`/`tv`, non quelli ridefiniti dentro `attach`. `play-path` re-esegue il path nel contesto corretto.

### Stretch: scala lungo assi locali

Dentro `attach` (e solo lì) sono disponibili `stretch-f`, `stretch-rt`, `stretch-u` per scalare il pezzo lungo gli assi locali della tartaruga:

```clojure
(register b (box 20))

;; Raddoppia lungo l'heading
(attach! :b (stretch-f 2))

;; Ruota la tartaruga, poi scala lungo il nuovo heading
(attach! :b (th 90) (stretch-f 2))
```

<!-- example-source: attach-stretch
(register b (box 20))
(register b2 (attach (box 20) (stretch-f 2)))
-->

Il pivot è la posizione della tartaruga al momento della chiamata. Se prima di `stretch-f` muovi la tartaruga con `(f 10)`, il pivot si sposta e la scala diventa asimmetrica.

`stretch-*` è il complemento locale di `scale` (che lavora su assi mondo). Fuori da `attach` non sono disponibili. Se scrivi `(scale ...)` dentro un `attach`, Ridley segnala l'errore e ti indica `stretch-*`.

### Attach su SDF

`attach` funziona identicamente su mesh e SDF. Gli SDF sono il sistema di modellazione alternativo di Ridley, trattato nel capitolo 12. Se non li hai ancora incontrati, puoi saltare questo paragrafo: tutto ciò che serve sapere per ora è che `attach` è polimorfo e funziona allo stesso modo su entrambi i tipi di oggetto.

I comandi tartaruga vengono eseguiti uno per uno e ogni comando trasforma il nodo SDF direttamente. I marcatori registrati con `mark` dentro un `attach` su SDF sopravvivono attraverso le booleane SDF e attraverso la materializzazione (SDF → mesh).

## 8.4 Skeleton-driven assembly

Lo skeleton-driven assembly è il pattern completo per assemblare componenti multipli lungo una struttura portante. Combina le tre idee viste finora: marcatori (8.1), creation-pose (8.2), e attach con move-to (8.3).

### Il pattern

**1. Costruisci uno skeleton.** Un `path` che cammina lungo la struttura dell'assemblaggio, piazzando marcatori in ogni punto rilevante:

```clojure
(def axle-skel
  (path
   (mark :base)
   (f 30) (mark :mid)
   (f 30) (mark :top)))
```

**2. Modella ogni componente in modo indipendente.** Ogni pezzo nasce nel suo frame locale, con l'origine nel punto in cui si aggancerà allo skeleton. Non pre-posizionare i componenti: vanifica il pattern.

```clojure
(def disk (cyl 15 4))
```

**3. Assembla agganciando allo skeleton.** Puoi usare `attach` + `move-to`, ripetendo lo skeleton per ogni pezzo:

```clojure
(register stack
  (mesh-union
   (attach disk (move-to axle-skel :at :base))
   (attach disk (move-to axle-skel :at :mid))
   (attach disk (move-to axle-skel :at :top))))
```

Oppure puoi usare `with-path` per entrare nello skeleton una volta sola e navigarlo dall'interno:

```clojure
(register stack
  (with-path axle-skel
    (mesh-union
     (attach disk (move-to axle-skel :at :base))
     (attach disk (move-to axle-skel :at :mid))
     (attach disk (move-to axle-skel :at :top)))))
```

<!-- example-source: skeleton-axle
(def axle-skel
  (path (mark :base) (f 30) (mark :mid) (f 30) (mark :top)))
(def disk (cyl 15 4))
(register stack
  (mesh-union
   (attach disk (move-to axle-skel :at :base))
   (attach disk (move-to axle-skel :at :mid))
   (attach disk (move-to axle-skel :at :top))))
-->

Lo skeleton è la fonte di verità dell'assemblaggio. Sposta un marcatore, e ogni componente agganciato lo segue.

### Marcatori proporzionali

Invece di distanze fisse nel path, esprimi le posizioni come frazioni di una lunghezza di riferimento:

```clojure
(def H 60)

(def axle-skel
  (path
   (mark :base)
   (f (* 0.5 H)) (mark :mid)
   (f (* 0.5 H)) (mark :top)))
```

Ora cambiare `H` riscala l'intero assemblaggio. Utile quando lo stesso design deve esistere in più taglie, o quando una singola dimensione guida molti offset derivati.

### Sub-path e side-trip

Gli skeleton complessi si costruiscono componendo sub-path con `follow` e `side-trip`:

```clojure
(defn arm [side depth mname]
  (path
   (side-trip
    (th (if (pos? side) 90 -90))
    (f (abs side))
    (u (- depth))
    (mark mname))))

(def hinge-skel
  (path
   (mark :pin-axis)
   (doseq [[i k] (map-indexed vector bracket-axials)]
     (follow (bracket-pair i k)))))
```

`side-trip` esegue una deviazione e torna al punto di partenza. `follow` inserisce un sub-path nello skeleton. I marcatori piazzati dentro un sub-path sono visibili dall'intero skeleton.

### Assemblaggio gerarchico con with-path e goto

`with-path` e `goto` sono l'alternativa a `attach` + `move-to` quando i componenti sono estrusioni che seguono segmenti dello skeleton. Invece di costruire i pezzi altrove e poi agganciarli, li costruisci direttamente nello scope dello skeleton:

```clojure
(def arm-sk (path (mark :shoulder) (f 15) (mark :elbow) (f 12) (mark :wrist)))

(register upper
  (with-path arm-sk
    (extrude (circle 1.5) (path-to :elbow))))

(register lower
  (with-path arm-sk
    (goto :elbow)
    (extrude (circle 1.2) (path-to :wrist))))
```

<!-- example-source: skeleton-with-path
(def arm-sk (path (mark :shoulder) (f 15) (mark :elbow) (f 12) (mark :wrist)))
(register upper
  (with-path arm-sk
    (extrude (circle 1.5) (path-to :elbow))))
(register lower
  (with-path arm-sk
    (goto :elbow)
    (extrude (circle 1.2) (path-to :wrist))))
-->

`goto` posiziona la tartaruga a un marcatore (posizione, heading, up), poi `path-to` estrude fino al marcatore successivo. I pezzi nascono direttamente nelle coordinate mondo, senza bisogno di snapping post-hoc.

Questo approccio è naturale quando i componenti sono estrusioni che seguono segmenti dello skeleton. Il pattern `attach` + `move-to` è più adatto quando i componenti sono pezzi già costruiti indipendentemente.

### Quando NON usare uno skeleton

Se l'assemblaggio è due pezzi incollati insieme, o se non c'è una "spina dorsale" che collega le parti, uno skeleton è eccessivo. Una coppia di `attach` innestati basta. Usa lo skeleton quando i componenti condividono un asse, una catena, o una struttura portante, e quando le posizioni relative contano più di quelle assolute.

## 8.5 Stack di stato e branching

Quando costruisci un assemblaggio, ogni comando tartaruga modifica lo stato globale: posizione, heading, up. Se vuoi piazzare due componenti che partono dallo stesso punto ma vanno in direzioni diverse, hai bisogno di un modo per "salvare" lo stato, esplorare un ramo, e tornare indietro.

### Side-trip

`side-trip` è il meccanismo principale. All'interno di un `path`, esegue una sequenza di comandi e poi ripristina lo stato della tartaruga al punto di partenza:

```clojure
(def skel
  (path
   (mark :root)
   (side-trip
    (th 30) (f 20) (mark :branch-a))
   (side-trip
    (th -30) (f 20) (mark :branch-b))
   (f 40) (mark :tip)))
```

<!-- example-source: side-trip-branching
(def skel
  (path
   (mark :root)
   (side-trip (th 30) (f 20) (mark :branch-a))
   (side-trip (th -30) (f 20) (mark :branch-b))
   (f 40) (mark :tip)))
(follow-path skel)
-->

Dopo la prima `side-trip`, la tartaruga è di nuovo a `:root`. La seconda `side-trip` parte dallo stesso punto. Il `(f 40)` finale avanza dalla posizione originale, non da un ramo.

Il pattern classico è lo skeleton a Y: una spina che si biforca. Ogni ramo è un `side-trip` che piazza marcatori, poi lo spine continua dritto.

### Branching annidato

`side-trip` si annida:

```clojure
(def tree-skel
  (path
   (mark :trunk)
   (f 30)
   (side-trip
    (th 30)
    (f 15)
    (mark :branch-1)
    (side-trip
      (th 20) (f 10) (mark :twig-1a))
    (side-trip
      (th -20) (f 10) (mark :twig-1b)))
   (f 30)
   (mark :top)))
```

Ogni livello di annidamento salva e ripristina il suo stato indipendentemente. Utile per strutture ad albero, grafi di parentela, o qualsiasi topologia che non è una linea retta.

### With-path come scope

`with-path` è uno scope che lega la tartaruga a un path registrato. All'interno, `goto` e `path-to` navigano fra i marcatori. All'uscita, lo stato della tartaruga torna a quello precedente l'ingresso:

```clojure
;; Lo stato della tartaruga qui è X
(with-path my-skeleton
  ;; Dentro: la tartaruga parte dall'inizio del path
  (goto :some-mark)
  ;; ... costruisci qualcosa ...
  )
;; Lo stato della tartaruga qui è di nuovo X
```

Questo lo rende un meccanismo di branching implicito: entri nel path, fai quello che devi, esci, e sei dove eri prima. Combinato con più `with-path` in sequenza, puoi navigare skeleton diversi senza che si influenzino.
