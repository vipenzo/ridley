<!--
=========================================================================
NOTE INTERNE PER L'AUTORE — NON DESTINATE AL LETTORE FINALE

Struttura del capitolo:
5.1  Cos'è un path
5.2  Visualizzare un path (follow-path)
5.3  Marks: tag dentro un path
5.4  Side-trip: rami che tornano alla spina
5.5  Comporre path: follow e splicing
5.6  Path da coordinate (poly-path, quick-path)
5.7  Path come embrioni di forma (path-to-shape, stroke-shape)
5.8  Cose che accettano un path (mappa dei consumatori)

Decisioni:
- move-to è body-only (dentro attach/attach!)
- with-path/goto/path-to aggiunti nella 5.8
- goto e look-at non hanno scheda Reference (da fare batch futuro)
=========================================================================
-->

# Path: registrare il movimento

## Cos'è un path

Nel cap. 4 abbiamo scritto comandi tartaruga direttamente dentro `extrude`: `(extrude (circle 10) (f 20) (th 45) (f 20))`. Il percorso è definito sul posto e usato immediatamente. Ma a volte serve lo stesso percorso in più punti: come traiettoria di un'estrusione, come posizione di componenti lungo uno scheletro, come curva da visualizzare nel viewport. Riscriverlo ogni volta è fragile e noioso.

`path` registra una sequenza di comandi tartaruga come dato riutilizzabile, senza eseguirli sulla tartaruga principale.

<!-- example-source: path-basic -->
```clojure
(def square-path
  (path
    (dotimes [_ 4]
      (f 20) (th 90))))

(register tube-a (extrude (circle 5) square-path))
(f 40)
(register tube-b (extrude (rect 8 4) square-path))
```

`path` cattura i comandi dentro il suo corpo (quattro ripetizioni di "avanti 20, gira 90°") e restituisce un dato. `def` gli dà un nome. Lo stesso `square-path` viene poi usato in due estrusioni diverse, con due shape diverse, in due posizioni diverse. Il percorso è scritto una volta sola.

Dentro `path` puoi usare qualsiasi codice Clojure: `dotimes` per i cicli, `if` per le scelte, `let` per variabili locali. Il codice Clojure viene eseguito al momento della creazione del path, ma nel path finiscono solo i comandi tartaruga che quel codice produce. Il `(dotimes [_ 4] (f 20) (th 90))` viene eseguito durante il recording: il `dotimes` gira quattro volte, e nel path finiscono 4 `f` e 4 `th`, non un loop. Il path non sa che quei comandi sono stati generati da un ciclo.

Un path non modifica la tartaruga principale. Quando scrivi `(def p (path (f 100)))`, la tartaruga resta dov'era. Il path è solo un dato, una sequenza di istruzioni registrate. Diventano movimento reale solo quando un consumatore le interpreta: `extrude`, `loft`, `follow-path`, `text-on-path`.

## Visualizzare un path

Un path è un dato invisibile: registra comandi, non produce geometria. Per vederlo nel viewport si usa `follow-path`.

`follow-path` esegue i comandi del path sulla tartaruga corrente. Se la penna è accesa (default), la tartaruga traccia le linee del percorso nel viewport.

<!-- example-source: follow-path-basic -->
```clojure
(def my-path (path (f 20) (arc-h 15 90) (f 20)))
(follow-path my-path)
```

La tartaruga percorre il path e disegna il tracciato come linea di costruzione. Attenzione: `follow-path` muove davvero la tartaruga. Dopo la chiamata, la tartaruga è alla fine del percorso. Se non vuoi che si sposti, avvolgilo in un `turtle` scope:

```clojure
(turtle (follow-path my-path))
;; la tartaruga esterna non si è mossa
```

`follow-path` è anche utile come strumento compositivo: un path definito altrove può essere "replayato" sulla tartaruga per posizionarla in un punto preciso prima di costruire qualcosa.

Le linee tracciate dalla tartaruga (sia via `follow-path` sia da movimenti diretti) si possono mostrare o nascondere con il bottone "Lines" nella toolbar del viewport, oppure con `(show-lines)` / `(hide-lines)` da codice.

## Marks: tag dentro un path

Un path non è solo una traiettoria: è anche un posto dove lasciare dei segnaposto. `mark` registra la posa della tartaruga (posizione, direzione, orientamento) in un punto del percorso, con un nome.

<!-- example-source: mark-basic -->
```clojure
(def arm-path
  (path
    (f 30)
    (mark :elbow)
    (th 45)
    (f 20)
    (mark :hand)))

(register arm (extrude (circle 3) arm-path))

(register joint
  (attach (sphere 5)
    (move-to arm-path :at :elbow :align)))

(register tip
  (attach (cone 4 0 8)
    (move-to arm-path :at :hand :align)))
```

Due mark: `:elbow` a 30 in avanti (prima della curva), `:hand` alla fine del percorso (dopo la curva). I mark non producono geometria e non influenzano il percorso: sono solo etichette attaccate a una posa.

`move-to` è disponibile dentro `attach` e `attach!`: porta la tartaruga alla posa del mark indicato, posizionando la mesh che si sta attaccando. `:align` allinea anche la direzione, non solo la posizione. Così la sfera finisce esattamente al gomito, e il cono esattamente alla mano, orientato nella direzione del percorso in quel punto.

Questo è il pattern fondamentale dello scheletro: un path con mark definisce *dove* stanno le cose, le shape e le primitive definiscono *cosa* sono le cose, e `move-to` le collega. Il cap. 8 (Assemblaggio) costruisce interi assiemi su questo principio. Qui ci interessa il meccanismo di base.

`anchors` restituisce la mappa completa di tutti i mark di un path:

```clojure
(anchors arm-path)
;; => {:elbow {:pos [...] :heading [...] :up [...]}
;;     :hand  {:pos [...] :heading [...] :up [...]}}
```

Per interrogare un singolo mark senza passare da `move-to`:

```clojure
(mark-pos arm-path :elbow)   ; => [30 0 0]
(mark-x arm-path :elbow)     ; => 30
(mark-z arm-path :elbow)     ; => 0
```

`mark-pos` restituisce le coordinate 3D del mark. `mark-x`, `mark-y`, `mark-z` restituiscono le singole componenti. Sono utili quando serve la posizione numerica, non lo spostamento della tartaruga.

## Side-trip: rami che tornano alla spina

Spesso un path ha una spina principale (l'asse lungo cui si sviluppa la struttura) e dei rami laterali dove si lasciano mark per componenti accessori. Senza un meccanismo dedicato, ogni ramo dovrebbe disfare manualmente i propri movimenti per riportare la tartaruga sulla spina: gira a destra, avanti 20, lascia un mark, indietro 20, gira a sinistra. Fragile e noioso.

`side-trip` risolve il problema: esegue il suo corpo come sotto-percorso, registra tutto (mark compresi), e poi riporta automaticamente la tartaruga alla posizione in cui era prima di entrare.

<!-- example-source: side-trip-basic -->
```clojure
(def skel
  (path
    (mark :start)
    (f 40)
    (side-trip
      (th 60)
      (mark :start-branch)
      (f 27)
      (mark :end-branch))
    (mark :after)
    (f 20)
    (mark :end)))

(register tree
  (mesh-union
    (extrude (circle 5) skel)
    (attach (cyl 10 2) (move-to skel :at :start))
    (attach (cyl 12 2) (move-to skel :at :after))
    (attach (cyl 15 2) (move-to skel :at :end))
    (turtle (:start-branch skel)
      (extrude (circle 2) (f 27)))))
```

La spina va da `:start` a `:end` passando per `:after`. Il `side-trip` si stacca dalla spina a 40 in avanti, gira di 60°, e lascia due mark (`:start-branch` e `:end-branch`). Poi la tartaruga torna automaticamente a dove era prima del `side-trip` (a 40 sulla spina), e il path continua con `:after` e `:end`.

Tre dischi piazzati con `move-to` segnano i punti sulla spina. Il ramo laterale viene costruito con una sintassi diversa: `(turtle (:start-branch skel) ...)`. Un path si comporta come una mappa dei suoi anchor, quindi `(:start-branch skel)` restituisce la posa del mark `:start-branch`, e `turtle` apre uno scope posizionato su quella posa. Dentro lo scope, `(extrude (circle 2) (f 27))` costruisce il ramo.

Questa sintassi è il modo più diretto per costruire geometria a partire da un mark. La differenza con `move-to` (che funziona solo dentro `attach`): `turtle` con un anchor apre un intero scope in cui si possono fare più operazioni, non solo posizionare una mesh.

`turtle` accetta anchor in tre forme equivalenti:

```clojure
(turtle (:anchor-name my-path) ...)    ; accessor implicito (la più comune)
(turtle my-path :at :anchor-name ...)  ; sugar :at
(turtle :pose some-pose ...)           ; pose esplicita da qualsiasi sorgente
```

La prima forma è la più compatta e la userete quasi sempre. La seconda rende esplicito che si sta navigando a un anchor di un path. La terza accetta qualsiasi posa (non solo da path), utile quando la posa viene da un calcolo o da `(:end-face result)`.

### Pattern: helper con side-trip

Un altro pattern frequente: avvolgere il side-trip in una funzione helper che parametrizza la direzione e l'orientamento del ramo.

<!-- example-source: side-trip-helper -->
```clojure
(defn arm [side rot mname]
  (path
    (side-trip
      (th (if (pos? side) 90 -90))
      (tr rot)
      (f (abs side))
      (mark mname))))

(def skel
  (path
    (f 50)
    (follow (arm  27 15 :a-1))
    (follow (arm -27 30 :a-2))
    (f 30)
    (follow (arm  27 45 :a-3))
    (follow (arm -27 60 :a-4))))

(register stuff
  (mesh-union
    (extrude (circle 3) skel)
    (concat-meshes (for [k (keys (anchors skel))]
      (attach (box 10 5 2) (move-to skel :at k :align))))))
```

`arm` crea un sotto-path che si stacca dalla spina, va lateralmente di `side` (positivo = destra, negativo = sinistra), ruota con `tr` per variare l'orientamento, e lascia un mark. `follow` lo innesta nella spina. Quattro chiamate con parametri diversi piazzano quattro mark lungo la spina, ciascuno con un'angolazione diversa.

La parte finale itera su tutti i mark con `for` e piazza una box su ciascuno, allineata alla posa del mark. `concat-meshes` raccoglie le mesh prodotte dal `for` in una lista che `mesh-union` può fondere con il tubo estruso.

Il `side-trip` annida senza problemi: un side-trip può contenere un `follow` che contiene un altro side-trip. Ogni livello salva e ripristina la sua posa.

## Path da coordinate

Finora abbiamo costruito path con comandi tartaruga: avanti, gira, arco. Ma a volte il percorso è definito come una lista di punti (coordinate misurate da un disegno, punti importati, waypoint calcolati). Due costruttori creano path direttamente da coordinate.

### poly-path

`poly-path` crea un path da coppie di coordinate, come `poly` per le shape.

<!-- example-source: poly-path-basic -->
```clojure
(def profile-path (poly-path 0 0  20 0  20 30  10 40  0 30))
(register rail (extrude (circle 3) profile-path))
```

Cinque punti, un percorso aperto che li collega con segmenti rettilinei. Le coordinate sono coppie X/Y nel piano della tartaruga. `poly-path` accetta anche un vettore: `(poly-path [0 0 20 0 20 30 10 40 0 30])`.

`poly-path-closed` fa la stessa cosa ma chiude il percorso tornando al primo punto:

<!-- example-source: poly-path-closed -->
```clojure
(def frame-path (poly-path-closed 0 0  40 0  40 30  0 30))
(register frame (extrude-closed (circle 2) frame-path))
```

Un rettangolo chiuso, utile come percorso per `extrude-closed`.

### quick-path

`quick-path` (alias `qp`) è il costruttore compatto che abbiamo già visto nel cap. 4: numeri e angoli si alternano.

<!-- example-source: quick-path-zigzag -->
```clojure
(def zigzag (qp 20 60 20 -120 20 60 20))
(register bar (extrude (rect 5 3) zigzag))
```

`qp` è comodo per percorsi rettilinei con angoli noti. `poly-path` è comodo quando hai le coordinate. `path` con comandi tartaruga è il più flessibile: archi, bezier, side-trip, mark.

## Path come embrioni di forma

Un path descrive un percorso. Una shape descrive un contorno. Ma i due concetti si toccano: un percorso chiuso nel piano è anche un contorno, e un contorno con uno spessore è anche un solido. Due funzioni attraversano il confine.

### path-to-shape

`path-to-shape` converte un path in una shape 2D, proiettando i waypoint sul piano destra-alto.

<!-- example-source: path-to-shape-basic -->
```clojure
(def rim
  (path
    (mark :foot-1) (f 30) (th 60)
    (mark :foot-2) (f 30) (th 60)
    (mark :foot-3) (f 30) (th 60)
    (mark :foot-4) (f 30) (th 60)
    (mark :foot-5) (f 30) (th 60)
    (mark :foot-6) (f 30)))

(register plate (extrude (path-to-shape rim) (f 4)))
```

Lo stesso path `rim` serve due scopi: come shape (via `path-to-shape`) per estrudere il piatto, e come scheletro (via i mark) per piazzare i piedi. Un unico dato, due consumatori, nessuna possibilità che divergano.

Questo è il caso d'uso principale di `path-to-shape`: quando il profilo e lo scheletro sono la stessa curva, non serve descriverli due volte.

Una limitazione attuale: `path-to-shape` funziona solo su path confinati nel piano destra-alto (comandi `f` e `th`). Se il path contiene `tv` o `tr` (movimenti fuori dal piano), vengono ignorati silenziosamente. Una proiezione 3D→2D vera è in Roadmap.

### stroke-shape

`stroke-shape` prende un path e gli dà uno spessore, producendo una shape 2D con un contorno che segue il percorso a una distanza fissa.

<!-- example-source: stroke-shape-basic -->
```clojure
(def curve (path (f 20) (arc-h 10 90) (f 15)))
(register ribbon (extrude (stroke-shape curve 3) (f 2)))
```

Il path `curve` diventa una shape larga 3 (1.5 per lato) che segue il percorso, ed estrusa produce un nastro piatto. È come disegnare il percorso con un pennarello di larghezza fissa e poi estrudere il tratto.

`stroke-shape` accetta opzioni per i terminali e le giunzioni:

```clojure
(stroke-shape curve 3
  :start-cap :round    ; terminale iniziale arrotondato
  :end-cap :flat       ; terminale finale piatto
  :join :miter)        ; giunzioni a punta
```

I cap disponibili sono `:round` e `:flat`. Le giunzioni sono `:round` (default), `:miter` (a punta), `:square`.

## Cose che accettano un path

I path sono dati. Diventano utili quando qualcosa li consuma. Ecco la mappa completa dei consumatori.

### extrude, loft, extrude-closed

Il consumatore principale. Trascinano una shape lungo il percorso del path. Li abbiamo visti in tutto il cap. 4.

<!-- example-source: where-extrude-loft -->
```clojure
(def my-path (path (f 20) (arc-h 15 90) (f 20)))
(register tube (extrude (circle 5) my-path))
(register vase (loft (tapered (circle 15) :to 0.5) my-path))
```

### follow-path

Esegue i comandi del path sulla tartaruga corrente, muovendola e tracciando le linee. Lo abbiamo visto nella 5.2.

### text-on-path

Dispone testo 3D lungo la curva del path. Ogni lettera viene posizionata e orientata seguendo il percorso.

<!-- example-source: where-text-on-path -->
```clojure
(def curve (path (dotimes [_ 40] (f 2) (th 3))))
(register title (text-on-path "Hello Ridley" curve :size 15 :depth 3))
```

Il cap. 13 (Testo) tratta `text-on-path` in dettaglio.

### move-to (dentro attach)

Porta la tartaruga alla posa di un mark dentro il path. Disponibile solo nel corpo di `attach` e `attach!`. Lo abbiamo visto nella 5.3.

```clojure
(attach (sphere 5) (move-to my-path :at :elbow :align))
```

### with-path, path-to, turtle

`with-path` apre uno scope in cui i mark di un path diventano anchor navigabili. Dentro lo scope, `turtle` con il nome di un anchor posiziona la tartaruga su quel mark, e `path-to` costruisce un path dalla posizione corrente a un anchor target.

<!-- example-source: where-with-path -->
```clojure
(def skel (path (mark :base) (f 15) (mark :mid) (f 12) (mark :top)))

(with-path skel
  (register lower
    (turtle :base (extrude (circle 2.5) (path-to :mid)))))

(with-path skel
  (register upper
    (turtle :mid (extrude (circle 1.2) (path-to :top)))))
```

`with-path` è il pattern principale per costruire più pezzi lungo lo stesso scheletro. Ogni pezzo viene estruso da un mark al successivo, e i pezzi sono automaticamente allineati perché condividono lo stesso scheletro. Il cap. 8 (Assemblaggio) usa questo pattern in modo estensivo.

### turtle con anchor

Apre uno scope posizionato sulla posa di un mark. A differenza di `move-to` (che funziona solo dentro `attach`), `turtle` permette di eseguire più operazioni dalla posa del mark: estrusioni, registrazioni, sotto-assemblaggi. Lo abbiamo visto nella 5.4.

```clojure
(turtle (:elbow my-path)
  (register joint (sphere 5)))
```

### anchors

Restituisce la mappa mark→posa di un path. Usato per iterare su tutti i mark, tipicamente in un `for` che piazza componenti.

```clojure
(for [m (keys (anchors my-path))]
  (attach (cyl 3 10) (move-to my-path :at m :align)))
```

### path-to-shape, stroke-shape

Convertono un path in una shape 2D. Li abbiamo visti nella 5.7.

### bezier-as

Prende un path esistente e lo percorre come curva di Bezier liscia, smussando gli spigoli. Si usa dentro un altro `path` o dentro `extrude`:

```clojure
(register smooth-tube
  (extrude (circle 4)
    (path (bezier-as my-angular-path))))
```

### fit

Scala un path a una dimensione target su uno o entrambi gli assi:

```clojure
(def tall-path (fit my-path :y 180))
(register column (revolve (path-to-shape tall-path)))
```

### Riepilogo

| Consumatore | Cosa fa col path | Capitolo |
|---|---|---|
| `extrude`, `loft`, `extrude-closed` | traiettoria di estrusione | 4 |
| `follow-path` | muove la tartaruga e traccia linee | 5.2 |
| `text-on-path` | posiziona lettere lungo la curva | 13 |
| `move-to` (dentro attach) | snap della tartaruga a un mark | 5.3 |
| `turtle` con anchor | scope posizionato su un mark | 5.4 |
| `with-path` + `turtle` + `path-to` | assemblaggio path-driven | 8 |
| `anchors` | mappa mark→posa | 5.3 |
| `path-to-shape` | converte in shape 2D | 5.7 |
| `stroke-shape` | contorno con spessore | 5.7 |
| `bezier-as` | smussatura come bezier | 4.2 |
| `fit` | scala a dimensione target | 3 (Reference) |

Il path è il dato più versatile di Ridley dopo la mesh: descrive un percorso, marca posizioni, e si presta a consumatori diversi senza cambiare. Lo stesso path può essere la traiettoria di un tubo, lo scheletro di un assemblaggio, e il profilo di un piatto, tutto nello stesso script.
