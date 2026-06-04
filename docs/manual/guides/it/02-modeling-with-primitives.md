<!--
=========================================================================
NOTE INTERNE PER L'AUTORE — NON DESTINATE AL LETTORE FINALE
Promemoria delle convenzioni accertate per orientamento delle primitive.

Verità accertate (vedi diagnosi di Code, T-006 chat 2026-05-16):

- box (w h d): w=destra, h=alto, d=avanti (chiamata diretta apply-transform,
  no swap)
- cyl (r h): r=raggio sezione, h=altezza in avanti (wrapper
  transform-mesh-to-turtle-upright applica swap heading↔up)
- cone (r1 r2 h): centrato sulla turtle (come box/sphere), facce a +/-h/2
  lungo l'avanti. r1=raggio della faccia vicino/start (dietro, -heading),
  r2=raggio della faccia lontana (avanti, +heading); h=lunghezza totale
  (stesso wrapper di cyl). Ordine come loft: (cone r1 r2 h) ≈ (loft (circle r1) (circle r2) (f h))
- sphere (r): radialmente simmetrica, orientamento poli invisibile

Tutte le primitive con asse di sviluppo si estendono lungo l'avanti.
Sezione (se presente) nel piano destra-alto. Regolarità con extrude/loft:
  (box w h d) ≈ (extrude (rect w h) (f d))
  (cyl r h)   ≈ (extrude (circle r) (f h))
  (cone r1 r2 h) ≈ (loft (circle r1) (circle r2) (f h))  ; loft, NON extrude; cone centrato vs loft all'inizio

Discrepanze sorgente↔realtà tracciate in dev-docs/code-issues.md.
=========================================================================
-->

# Modellare per primitive

Il modo più intuitivo di modellare un nuovo oggetto in qualsiasi CAD è farlo assemblando oggetti più elementari. Questi, chiamati in genere "primitive", sono forme come cubi, parallelepipedi, cilindri, sfere, coni e simili.

Ridley supporta anche questo modo di lavorare, tra molti altri. Ed è spesso il modo più intuitivo, oltre che il più semplice da usare. Gli strumenti sono le primitive stesse — `box` per cubi e parallelepipedi, `cyl` per cilindri, `cone` per coni e tronchi di cono, `sphere` per le sfere — più alcune funzioni che permettono di spostarle (`attach`), di deformarle (`scale`) e di comporle: `mesh-union` per unirle in un unico oggetto, `mesh-difference` per scavare da un oggetto la forma di un altro, `mesh-intersection` per tenere la parte comune a due oggetti, `mesh-hull` per costruirne l'inviluppo convesso.

Questa è una grammatica minima ma potente: a partire da queste poche operazioni si può costruire una sorprendente varietà di oggetti utili. Lo vedremo concretamente nel corso del capitolo, costruendo via via piccoli oggetti — un portapenne, una barchetta a vela — che ci aiuteranno a fissare le idee.

## Le primitive

Le primitive sono i mattoni elementari di questo modo di lavorare. Ognuna produce una mesh 3D nella posizione corrente della tartaruga, ma non sposta la tartaruga: dopo aver creato una forma, la tartaruga rimane esattamente dov'era.

Tutte le primitive nascono orientate secondo la tartaruga, e quelle che hanno un "asse di sviluppo" (un parallelepipedo lungo, un cilindro, un cono) lo estendono *in avanti* alla tartaruga. La sezione, dove c'è, sta nel piano destra-alto. Questa regolarità ci tornerà utile anche al cap. 4, dove vedremo che `extrude` segue la stessa logica.

### Il cubo e il parallelepipedo

`box` crea un cubo, o un parallelepipedo, della dimensione richiesta.

<!-- example-source: primitive-cube -->
```clojure
(register cube-demo (box 20))
```

Un solo argomento produce un cubo: stesso lato sulle tre dimensioni.

<!-- example-source: primitive-box -->
```clojure
(register box-demo (box 10 20 30))
```

Tre argomenti producono un parallelepipedo: la prima dimensione è verso destra, la seconda verso l'alto, la terza in avanti. Quindi `(box 10 20 30)` è largo 10, alto 20, profondo 30 nella direzione in cui la tartaruga sta guardando. Girando la tartaruga prima del comando, lo stesso codice produce un parallelepipedo orientato diversamente.

L'ordine "destra, alto, avanti" è inusuale rispetto a CAD più tradizionali. Una chiave mnemonica utile è la corrispondenza con l'estrusione: `(box 10 20 30)` produce lo stesso solido di `(extrude (rect 10 20) (f 30))`, a parte il punto in cui sono ancorati alla tartaruga (`box` lo prende al centro, `extrude` alla base). I primi due numeri sono la sezione, il terzo la profondità di estrusione.

### Il cilindro

`cyl` crea un cilindro: raggio della base, altezza in avanti.

<!-- example-source: primitive-cyl -->
```clojure
(register cyl-demo (cyl 5 30))
```

`(cyl 5 30)` è un cilindro di raggio 5 lungo 30 nella direzione in cui la tartaruga sta guardando.

### Il cono

`cone` crea un tronco di cono: raggio dell'estremità vicina (start), raggio dell'estremità lontana, lunghezza lungo l'avanti. Come `box` e `sphere`, il cono nasce centrato sulla tartaruga: le due sezioni circolari stanno lungo l'avanti, a metà lunghezza da una parte e dall'altra, e la tartaruga è equidistante dalle due. I due raggi seguono lo stesso ordine di lettura di `loft`: `(cone r1 r2 h)` ≈ `(loft (circle r1) (circle r2) (f h))`.

<!-- example-source: primitive-cone -->
```clojure
(register frustum-demo (cone 10 5 30))
```

`(cone 10 5 30)` è un tronco di cono lungo 30: `r1 = 10` è il raggio dell'estremità vicina (start, dietro), `r2 = 5` quello dell'estremità lontana (in avanti, lungo l'heading) — esattamente la forma di `(loft (circle 10) (circle 5) (f 30))`. La tartaruga sta al centro, a 15 da ciascuna faccia. Per un cono vero e proprio, mettere zero al raggio lontano: `(cone 10 0 30)` (la punta cade sulla faccia in avanti).

### La sfera

`sphere` crea una sfera del raggio richiesto, centrata sulla tartaruga.

<!-- example-source: primitive-sphere -->
```clojure
(register sphere-demo (sphere 10))
```

`(sphere 10)` è una sfera di raggio 10. Per una sfera nessun argomento direzionale ha senso: la forma è simmetrica.

### Spostare e ridimensionare

Le primitive nascono sulla tartaruga, quindi tutte nello stesso posto. Per costruire qualcosa con più di un pezzo servono due operazioni: spostare una mesh, e — quando le proporzioni non bastano — ridimensionarla.

`attach` sposta una mesh seguendo una serie di comandi tartaruga.

<!-- example-source: primitive-attach -->
```clojure
(register shifted-sphere (attach (sphere 5) (f 20)))
```

`scale` ridimensiona una mesh. Con un solo numero la scala uniformemente; con tre numeri la scala in modo non uniforme sui tre assi.

<!-- example-source: primitive-scale-uniform -->
```clojure
(register big-sphere (scale (sphere 10) 2))
```

<!-- example-source: primitive-scale-non-uniform -->
```clojure
(register ellipsoid (scale (sphere 10) 1 0.5 2))
```

Lo scale non uniforme è particolarmente utile in combinazione con le primitive simmetriche: una sfera schiacciata, un cilindro allungato, un cubo che diventa un parallelepipedo. Lo useremo subito nei due mini-progetti.

## Comporre

Le primitive sono oggetti elementari. Per modelli più articolati le combiniamo con quattro operazioni: `mesh-union` le unisce, `mesh-difference` ne scava una da un'altra, `mesh-intersection` tiene la parte comune, `mesh-hull` ne costruisce l'inviluppo convesso.

### `mesh-union`

`mesh-union` fonde due o più mesh in un'unica mesh.

<!-- example-source: union-cross -->
```clojure
(register cross
  (mesh-union
    (box 40 10 10)
    (box 10 10 40)))
```

`(mesh-union (box 40 10 10) (box 10 10 40))` produce una croce: i due parallelepipedi si compenetrano al centro, e dove si sovrappongono diventano un solo volume. Il risultato è una mesh sola, non due mesh sovrapposte: non c'è geometria interna dove i due si incontrano.

Spesso però i pezzi non nascono già nella posizione giusta. Combinando `mesh-union` con `attach` — che abbiamo già visto poco fa — possiamo fondere una primitiva con un'altra spostata.

<!-- example-source: union-t -->
```clojure
(register t-shape
  (mesh-union
    (box 40 10 10)
    (attach (box 10 10 30) (f 15))))
```

`(mesh-union (box 40 10 10) (attach (box 10 10 30) (f 15)))` produce una T: la base trasversale resta sulla tartaruga, la stanga longitudinale viene spostata in avanti di 15 (metà della lunghezza della base, così emerge dal centro), e l'unione fonde le due.

### `mesh-difference`

`mesh-difference` scava una mesh da un'altra: prende il volume della prima e ne sottrae il volume della seconda.

<!-- example-source: difference-drilled-cube -->
```clojure
(register drilled-cube
  (mesh-difference
    (box 30 30 30)
    (cyl 8 30)))
```

`(mesh-difference (box 30 30 30) (cyl 8 30))` produce un cubo attraversato da un foro cilindrico passante: il cubo è la "materia" da cui partiamo, il cilindro è la "forma da rimuovere".

A differenza di `mesh-union`, l'ordine degli operandi conta: invertendoli si ottiene un risultato completamente diverso — non più un cubo forato, ma un cilindro con un cubo sottratto, e quindi quasi tutto il cilindro spazzato via dall'intersezione del cubo molto più grande.

### `mesh-intersection`

`mesh-intersection` tiene solo la parte comune a due mesh: il volume in cui entrambe sono presenti.

<!-- example-source: intersection-half-cyl -->
```clojure
(register half-cyl
  (mesh-intersection
    (cyl 10 20)
    (attach (box 20) (u 10))))
```

`(mesh-intersection (cyl 10 20) (attach (box 20) (u 10)))` produce un mezzo cilindro: il cilindro è la forma di partenza, il cubo spostato in alto di 10 occupa solo la metà superiore dello spazio del cilindro, e l'intersezione tiene esattamente quella metà.

A prima vista può sembrare un giro lungo per dimezzare un cilindro. È il punto: `mesh-intersection` non è (solo) "trovare la regione in comune fra due forme", è un modo per *tagliare* un volume con un piano, definendo il piano come "una box molto grande spostata fin lì". Lo stesso pattern funziona con qualsiasi primitiva: lo useremo subito per costruire una barca, dove serviranno una mezza sfera (scafo) e un triangolo ricavato da un cono (vela).

### `mesh-hull`

`mesh-hull` racchiude due o più mesh nel più piccolo volume convesso che le contiene tutte: l'inviluppo convesso della loro unione.

A differenza delle tre operazioni precedenti, `mesh-hull` non opera "punto per punto" sui volumi — tenere, togliere, intersecare — ma costruisce attorno a tutte le mesh in input una superficie liscia che le racchiude. È utile come strumento generativo: partendo da forme semplici si producono forme che sarebbe scomodo descrivere altrimenti.

<!-- example-source: hull-base-plate -->
```clojure
(register base-plate
  (mesh-hull
    (attach (sphere 4) (rt  30) (f  30))
    (attach (sphere 4) (rt -30) (f  30))
    (attach (sphere 4) (rt  30) (f -30))
    (attach (sphere 4) (rt -30) (f -30))))
```

Quattro sfere ai vertici di un rettangolo orizzontale, e `mesh-hull` produce una basetta con i bordi morbidi: i quattro vertici sono sferici, i bordi cilindrici, i lati piatti.

## Un portapenne

Il pattern più semplice e utile della modellazione per primitive è anche il più antico della scultura: parti da un blocco pieno e togli materia. In Ridley lo fai con `mesh-difference`: un solido pieno meno un solido leggermente più piccolo, spostato verso l'alto, lascia un guscio aperto in cima. Il portapenne è esattamente questo.

<!-- example-source: pen-holder-v1 -->
```clojure
(register pen-holder
  (mesh-difference
    (box 40 40 80)
    (attach (box 36 40 76) (u 2))))
```

`(box 40 40 80)` è il blocco esterno: largo 40, profondo 40, alto 80. `(box 36 40 76)` è la cavità: 4 mm più stretta in larghezza e 4 mm più corta in profondità, stessa altezza. `(attach ... (u 2))` la solleva di 2: così il fondo rimane pieno, spesso 2, e la cima è aperta.

Questo pattern — un volume pieno meno una cavità sollevata — non dipende dalla forma: dipende solo dall'operazione. Lo ritroveremo fra poco nella barca.

### Variante cilindrica

Cambiando primitiva il portapenne cambia aspetto, ma la logica è identica.

<!-- example-source: round-pen-holder -->
```clojure
(register round-pen-holder
  (mesh-difference
    (attach (cyl 20 80) (tv 90))
    (attach (cyl 18 80) (tv 90) (f 2))))
```

Cilindro esterno di raggio 20, alto 80, ruotato in verticale con `(tv 90)`. Cilindro interno di raggio 18 (spessore parete: 2), ruotato allo stesso modo e sollevato di 2 con `(f 2)` per lasciare il fondo. Ricorda che `cyl` estrude in avanti: dopo `(tv 90)` l'avanti diventa l'alto, quindi `(f 2)` solleva il cilindro interno.

Se pensi di stampare il portapenne, l'unico numero che conta davvero è lo spessore delle pareti: 2 mm reggono per la maggior parte dei filamenti, 3 mm se vuoi più solidità. Lo spessore del fondo è il valore passato a `(f ...)` dopo il `(tv 90)`.

<!-- example-source: sturdy-pen-holder -->
```clojure
(def wall 3)

(register sturdy-pen-holder
  (mesh-difference
    (attach (cyl 22 100) (tv 90))
    (attach (cyl (- 22 wall) 100) (tv 90) (f wall))))
```

Qui `def` dà un nome allo spessore, così lo cambi in un posto solo e il fondo e le pareti si aggiornano insieme. Lo vedremo meglio nella sezione su `def`, dove diventa lo strumento centrale per parametrizzare un modello.

### Una barca

Le quattro operazioni messe in cooperazione bastano già a costruire un piccolo oggetto riconoscibile: una barchetta a vela.

Il codice è più lungo di quelli visti finora, ma ogni pezzo usa uno o due strumenti che abbiamo già incontrato. Lo leggiamo dall'alto.

```clojure
(def hull-vol (scale
               (mesh-intersection
                 (sphere 50)
                 (attach (box 100) (u -50)))
               1 0.5 0.5))
```

`def` dà un nome a un pezzo: qui `hull-vol` è il *volume* dello scafo, una mezza sfera schiacciata. L'intersezione fra una sfera di raggio 50 e una box molto grande spostata 50 in basso tiene solo la metà inferiore della sfera. `scale` con tre numeri la deforma in modo non uniforme: lascia la larghezza intatta (`1`), dimezza l'altezza (`0.5`) e la lunghezza (`0.5`) — diventa un'ellissoide allungata, la sagoma di uno scafo. Useremo `def` molto di più nel paragrafo successivo: qui ci serve solo per non scrivere due volte la stessa espressione.

```clojure
(def hull
  (mesh-difference
    hull-vol
    (attach hull-vol (u 2))))
```

Lo scafo vero, scavato. `mesh-difference` sottrae da `hull-vol` una copia di se stesso sollevata di 2: lascia un guscio dallo spessore di 2 sul fondo e sui lati, aperto in alto. È lo stesso pattern del portapenne, applicato a una forma più complessa.

```clojure
(def mast (attach (cyl 3 85) (tv 90) (f 18)))
```

Un cilindro sottile e alto, ruotato di 90° con `tv` per metterlo verticale rispetto allo scafo (la tartaruga partiva guardando avanti, dopo `tv 90` guarda in alto), e spostato in avanti di 18 perché stia verso la prua.

```clojure
(def sail (attach (scale (mesh-intersection
                           (cone 30 0 30)
                           (attach (box 100) (u 50)))
                         1.5 0.1 2)
            (f 26) (u 1)))
```

La vela. L'intersezione fra un cono (raggio 0 davanti, 30 dietro, lungo 30) e una box spostata in alto di 50 tiene solo la metà superiore del cono: un triangolo. `scale 1.5 0.1 2` lo stiracchia in larghezza (`1.5`), lo appiattisce quasi a una lamina (`0.1`), lo allunga in altezza (`2`). Poi `attach` lo posiziona in avanti di 26 e leggermente sollevato di 1.

```clojure
(register boat
  (mesh-union
    hull
    mast
    sail
    (attach (scale sail -1 0.7 0.9) (f -52))))
```

Finalmente unione di tutto. L'ultima riga è la seconda vela: la prima `sail` riscalata con `scale -1 0.7 0.9` — il `-1` la specchia sull'asse `right`, i `0.7` e `0.9` la rendono leggermente diversa per dare un po' di asimmetria — e arretrata di 52 con `(f -52)` per piazzarla verso poppa. `f` accetta valori negativi, e significa "indietro".

Il risultato è una barchetta con scafo emisferico scavato, albero, vela maestra e vela di prua. Più operazioni della 2.2 messe in cooperazione, più i pochi `attach` e `scale` che tengono insieme i pezzi.

Tutto insieme, ecco il codice completo:

<!-- example-source: boat -->
```clojure
(def hull-vol (scale
               (mesh-intersection
                 (sphere 50)
                 (attach (box 100) (u -50)))
               1 0.5 0.5))

(def hull
  (mesh-difference
    hull-vol
    (attach hull-vol (u 2))))

(def mast (attach (cyl 3 85) (tv 90) (f 18)))

(def sail (attach (scale (mesh-intersection
                           (cone 30 0 30)
                           (attach (box 100) (u 50)))
                         1.5 0.1 2)
            (f 26) (u 1)))

(register boat
  (mesh-union
    hull
    mast
    sail
    (attach (scale sail -1 0.7 0.9) (f -52))))
```

## Parametrizzare con def

Negli esempi precedenti le dimensioni sono numeri scritti direttamente nel codice: 40, 80, 2. Funziona, ma appena un modello cresce diventa fragile: se decidi che il portapenne deve essere più largo, devi trovare e aggiornare ogni numero che dipende da quella decisione. Dimenticarne uno produce un modello sbagliato in modo silenzioso: nessun errore, solo una parete più spessa da un lato o una cavità disallineata.

`def` risolve il problema dando un nome a un valore. Lo abbiamo già usato per lo spessore del portapenne robusto; qui lo estendiamo a tutte le dimensioni.

<!-- example-source: parametric-pen-holder -->
```clojure
(def radius 20)
(def height 100)
(def wall 3)

(register pen-holder
  (mesh-difference
    (attach (cyl radius height) (tv 90))
    (attach (cyl (- radius wall) height) (tv 90) (f wall))))
```

Tre `def` in cima, un modello sotto che li usa. Cambiare il raggio da 20 a 25 aggiorna automaticamente sia l'involucro sia la cavità; cambiare lo spessore aggiorna pareti e fondo insieme. I numeri hanno un nome, e quel nome dice *cosa* rappresentano, non solo *quanto* valgono.

Qualsiasi espressione Clojure può stare a destra di un `def`, non solo numeri. È utile quando una dimensione dipende da un'altra.

<!-- example-source: two-compartment-pen-holder -->
```clojure
(def radius 15)
(def height 90)
(def wall 2)
(def spacing (* radius 2))

(def compartment
  (mesh-difference
    (attach (cyl radius height) (tv 90))
    (attach (cyl (- radius wall) height) (tv 90) (f wall))))

(register pen-holder
  (mesh-union
    compartment
    (attach compartment (rt spacing))))
```

`spacing` non è un numero fisso: è `(* radius 2)`, cioè il doppio del raggio. Così i due scomparti si toccano, e `mesh-union` fonde la parete in comune in un unico volume. Se ingrandisci gli scomparti, la distanza cresce di conseguenza.

`compartment` stesso è un `def`: un pezzo intero a cui diamo un nome per poterlo riutilizzare due volte, una nella posizione originale e una spostata a destra con `(rt spacing)`.

Questo è il punto in cui `def` smette di essere solo un modo per evitare ripetizioni e diventa uno strumento di progettazione: le relazioni fra le parti sono esplicite nel codice, e modificare una decisione di design si riduce a cambiare un numero in cima al file.

## Pezzi riutilizzabili con defn

`def` dà un nome a un valore fisso. `defn` dà un nome a una *ricetta* che produce valori diversi ogni volta, a seconda dei parametri che le passi.

Nel portapenne a due scomparti della sezione precedente, entrambi gli scomparti avevano le stesse dimensioni. Con `defn` possiamo scrivere una ricetta "scomparto" che accetta dimensione e altezza, e usarla più volte con parametri diversi.

<!-- example-source: defn-compartment -->
```clojure
(def wall 2)

(defn round-compartment [radius height]
  (mesh-difference
    (attach (cyl radius height) (tv 90) (f (/ height 2)))
    (attach (cyl (- radius wall) height) (tv 90) (f (+ (/ height 2) wall)))))

(defn square-compartment [half-side height]
  (mesh-difference
    (attach (box (* half-side 2) (* half-side 2) height) (tv 90) (f (/ height 2)))
    (attach (box (* (- half-side wall) 2) (* (- half-side wall) 2) height)
      (tv 90)
      (f (+ (/ height 2) wall)))))

(defn compartment [kind size height]
  (if (= kind :round)
    (round-compartment size height)
    (square-compartment size height)))

(register pen-holder
  (mesh-union
    (compartment :round 18 100)
    (attach (compartment :round 10 80) (rt (+ 10 18)))
    (attach (compartment :square 12 70) (u (+ 18 12)))
    (attach (compartment :square 8 60) (u (- (+ 18 8))))))
```

`round-compartment` e `square-compartment` sono due funzioni che fanno esattamente quello che abbiamo fatto a mano nelle sezioni precedenti: un involucro meno una cavità sollevata. L'unica novità è che raggio (o metà lato) e altezza non sono più numeri fissi ma parametri, diversi a ogni chiamata. Il `(tv 90)` e il `(f (/ height 2))` servono a mettere lo scomparto in piedi e con le basi allineate, essendo di altezze diverse.

`compartment` è una terza funzione che sceglie quale delle due chiamare in base a `kind`: `:round` o `:square`. Una funzione può chiamarne un'altra, e qui il vantaggio è pratico: chi usa `compartment` non deve ricordare quale delle due funzioni specifiche serve, passa il tipo e la scelta avviene dentro.

Quattro chiamate con parametri diversi producono quattro scomparti: uno tondo grande per i pennelli, uno tondo piccolo per le penne spostato a destra, e due quadrati spostati in alto e in basso. Le distanze di `attach` sommano i raggi dei due scomparti adiacenti, così si toccano senza compenetrarsi. `mesh-union` fonde tutto in un pezzo solo.

Se decidi che tutti gli scomparti devono avere il fondo più spesso, cambi `wall` e tutte e quattro le chiamate si aggiornano.

## Tante primitive alla volta

Finora abbiamo costruito ogni oggetto con pochi pezzi posizionati a mano. Ma il linguaggio che sta sotto Ridley è un linguaggio di programmazione, e un linguaggio di programmazione sa fare i cicli. Possiamo generare decine di primitive con un'espressione sola.

Prendiamo il portapenne cilindrico della sezione precedente e aggiungiamo una decorazione: una spirale di sferette che avvolge la parete esterna.

<!-- example-source: spiral-vase :warning slow 
```clojure
(def radius 40)
(def h 80)
(def n-spheres 36)

(def vase-outer
  (attach (cyl radius h) (tv 90)))

(def vase-cut
  (attach (cyl (- radius 4) h) (tv 90) (f 2)))

(def decoration
  (mesh-union
    (for [i (range n-spheres)
          :let [angle (* i 20)
                z (- (/ (- h 10) 2)
                     (* i (/ (- h 10) n-spheres)))]]
      (attach (sphere 5) (th angle) (u z) (f radius)))))

(register vase
  (mesh-difference
    (mesh-union vase-outer decoration)
    vase-cut))
```
-->

Lo schema del portapenne è lo stesso: un cilindro esterno meno uno interno sollevato di 2. La novità è `decoration`.

`(for [i (range n-spheres)] ...)` è un ciclo: `i` assume i valori da 0 a 35 (36 valori), e per ciascuno produce una sfera posizionata con `attach`. `:let` introduce due nomi locali che esistono solo dentro il ciclo: `angle` è la rotazione orizzontale (cresce di 20° per ogni sfera, quindi in 36 sfere fa quasi due giri completi), `z` è l'altezza (scende linearmente dall'alto verso il basso). Il risultato è una spirale.

`mesh-union` riceve la lista di 36 sfere prodotte dal `for` e le fonde in un'unica mesh. Poi la decorazione viene unita al cilindro esterno, e dalla loro unione viene sottratta la cavità interna. Le sfere che sporgono verso l'interno vengono tagliate via dalla differenza, lasciando le mezze sfere a rilievo sulla parete esterna.

`for` e `range` sono Clojure, non Ridley. Ma è questo il vantaggio di avere un linguaggio di programmazione sotto: qualsiasi pattern geometrico che riesci a descrivere con una formula diventa un ciclo che genera primitive. Cambiando `n-spheres`, il raggio delle sfere, o la formula dell'angolo, la stessa struttura produce spirali più fitte, più rade, a doppia elica, o a griglia. Vale la pena sperimentare.

## Per chi viene da un CAD tradizionale

Se hai usato OpenSCAD, Tinkercad, o un qualsiasi modellatore che lavora con `translate`, `rotate`, `scale`, in Ridley li trovi tutti e tre. Funzionano in coordinate mondo: `translate` sposta lungo gli assi X/Y/Z del mondo, `scale` scala lungo quegli stessi assi con pivot sulla creation-pose della mesh, `rotate` ruota attorno alla creation-pose lungo un asse mondo.

<!-- example-source: cad-translate-rotate -->
```clojure
(register shelf-bracket
  (mesh-difference
    (translate (box 40 5 30) 15 20 0)
    (translate (cyl 3 25) 10 20 1)))
```

Funziona, ed è l'approccio giusto quando pensi in termini di "questa cosa va a X=20, Y=0, Z=15". Ma in Ridley c'è un secondo vocabolario che spesso è più naturale: i comandi turtle dentro `attach`.

<!-- example-source: cad-vs-turtle -->
```clojure
; same bracket, local vocabulary
(register shelf-bracket
  (mesh-difference
    (attach (box 40 5 30) (f 15) (rt 20))
    (attach (cyl 3 25) (f 10) (rt 20) (u 1))))
```

`(f 15)` significa "15 in avanti", `(rt 20)` significa "20 a destra". Se la turtle è nella posa di default, destra è X e alto è Z, quindi il risultato è identico. La differenza emerge quando componi pezzi ruotati: i comandi turtle seguono la rotazione, le coordinate mondo no.

Dentro `attach` hai lo stesso set completo che hai al top level, ma in coordinate locali:

- **Movimento**: `f`, `rt`, `u` (e i loro negativi `b`, `lt`, `d`)
- **Rotazione**: `th`, `tv`, `tr`
- **Scala**: `stretch-f`, `stretch-rt`, `stretch-u`

`stretch-f` scala la mesh lungo la direzione in cui la turtle sta guardando in quel momento. Se prima hai fatto `(th 45)`, l'asse di scala è ruotato di 45°.

<!-- example-source: stretch-local -->
```clojure
(register stretched-box
  (attach (box 20) (th 45) (stretch-f 2)))
```

Al top level, `translate`, `scale`, `rotate` lavorano sempre in coordinate mondo. Dentro `attach`, tutto lavora in coordinate locali. Una regola sola, nessuna eccezione.

Il pivot di `scale` e `rotate` è la creation-pose della mesh, cioè la posizione della turtle nel momento in cui la primitiva è stata creata. Per primitive appena costruite è al centro della geometria, quindi non ci pensi. Quando lavori con pezzi composti o boolean il pivot può non coincidere con il centro visivo: il capitolo 8 (Assemblaggio) spiega come controllarlo con `cp-*`.
