<!--
=========================================================================
NOTE INTERNE PER L'AUTORE — NON DESTINATE AL LETTORE FINALE

Struttura del capitolo:
3.1  A cosa servono le shape (motivazione + esempio extrude)
3.2  Forme predefinite (circle, rect, polygon, star + stamp)
3.3  Forme personalizzate (poly, shape)
3.4  Profili come valori (concetto: shape è valore nel piano locale turtle)
3.5  Booleane 2D
3.6  Raccordi e smussi 2D (fillet, chamfer, offset)
3.7  Dove si usano le shape (mappa dei consumatori)
3.8  Generare shape da mesh (slice-mesh, project-mesh, face-shape)
3.9  Più forme alla volta (vettori di shape)
3.10 Forme di testo (cenno + rimando cap. 13)
3.11 Forme riutilizzabili (defn che restituisce shape)

Decisione strutturale: la 3.1 apre con un esempio motivazionale
(shape → extrude) per evitare che il capitolo risulti astratto
fino al cap. 4. La 3.7 è la mappa completa dei consumatori,
non un cenno. stamp introdotto nella 3.2 come strumento di
visualizzazione, non in una sezione dedicata.
=========================================================================
-->

# Lavorare con le forme 2D

## A cosa servono le shape

Nel capitolo precedente abbiamo costruito ogni pezzo da una primitiva 3D: `box`, `cyl`, `sphere`, `cone`. Funziona, ma c'è un limite: non puoi scegliere liberamente la sezione di un oggetto. Un cilindro ha sempre sezione circolare, un parallelepipedo sempre rettangolare. Se vuoi un tubo a sezione esagonale, o un profilato a L, le primitive non bastano.

Le **shape** sono profili 2D: cerchi, rettangoli, poligoni, sagome disegnate a mano. Da sole non producono un solido. Diventano un solido quando le dai in pasto a un'operazione che le trasforma in 3D, come `extrude`:

<!-- example-source: shape-motivation -->
```clojure
(register c-channel
  (extrude
    (shape (f 20) (th 90)
      (f 20) (th 90)
      (f 20) (th 90)
      (f 5) (th 90)
      (f 15) (th -90)
      (f 10) (th -90)
      (f 15))
    (f 40)))
```

Il primo argomento di `extrude` è una shape: un profilo a C disegnato con i comandi della tartaruga 2D. Il resto sono comandi di movimento che definiscono il percorso di estrusione, come i comandi turtle che già conosci. `(f 40)` estrude il profilo in avanti di 40. Il risultato è un profilato a C lungo 40, con la sezione esatta che abbiamo disegnato.

Il ponte con il capitolo precedente è diretto: `(box 10 20 30)` produce lo stesso solido di `(extrude (rect 10 20) (f 30))`. Le primitive sono scorciatoie per le sezioni più comuni; le shape sono lo strumento generale.

L'estrusione è solo uno dei consumatori di shape. Ce ne sono altri: `loft` (estrude deformando il profilo lungo il percorso), `revolve` (ruota il profilo attorno a un asse), `stamp` (visualizza il profilo 2D senza produrre un solido). Li vedremo tutti alla fine del capitolo (§3.7). Per adesso il punto è uno solo: le shape sono il *materiale grezzo*, e questo capitolo spiega come crearlo e lavorarlo. I capitoli successivi spiegano come trasformarlo in solidi.

## Forme predefinite

Ridley ha quattro primitive 2D pronte all'uso: `circle`, `rect`, `polygon`, `star`. Sono le shape che usi più spesso, da sole o come punto di partenza per composizioni più complesse.

Per vederle nel viewport senza estrudere, c'è `stamp`: disegna il contorno 2D della shape nella posizione corrente della tartaruga, senza produrre un solido. È lo strumento giusto per ispezionare un profilo prima di darlo in pasto a `extrude`.

<!-- example-source: shape-circle -->
```clojure
(stamp (circle 20))
```

`(circle 20)` è un cerchio di raggio 20. Con un secondo argomento puoi controllare il numero di segmenti: `(circle 20 128)` per un cerchio più liscio, `(circle 20 6)` per un esagono regolare (anche se per quello c'è `polygon`, più espressivo).

<!-- example-source: shape-rect -->
```clojure
(stamp (rect 30 15))
```

`(rect 30 15)` è un rettangolo largo 30 (verso destra) e alto 15 (verso l'alto), centrato sulla tartaruga. I due argomenti seguono la stessa convenzione di `box`: il primo è la dimensione verso destra, il secondo verso l'alto. `(rect 30 15)` e `(box 30 15 ...)` hanno la stessa sezione.

<!-- example-source: shape-polygon -->
```clojure
(stamp (polygon 6 12))
```

`(polygon 6 12)` è un esagono regolare inscritto in un cerchio di raggio 12. Il primo argomento è il numero di lati, il secondo il raggio. Funziona per qualsiasi poligono regolare: `(polygon 3 10)` per un triangolo equilatero, `(polygon 8 15)` per un ottagono.

<!-- example-source: shape-star -->
```clojure
(stamp (star 5 20 10))
```

`(star 5 20 10)` è una stella a cinque punte con raggio esterno 20 e raggio interno 10. Il rapporto fra i due raggi controlla quanto sono pronunciate le punte: se il raggio interno si avvicina a quello esterno la stella diventa quasi un decagono, se si avvicina a zero le punte diventano aghi.

Tutte le primitive 2D sono centrate sulla tartaruga. `stamp` è comodo per verificarle a colpo d'occhio, e lo puoi attivare o disattivare anche dal bottone "Stamps" nella toolbar del viewport, oppure con `(show-stamps)` / `(hide-stamps)`.

## Forme personalizzate

Le primitive coprono cerchi, rettangoli, poligoni regolari e stelle. Per qualsiasi altro profilo ci sono due costruttori: `poly` per chi ragiona in coordinate, `shape` per chi ragiona in lunghezze e angoli.

### `poly`: coordinate esplicite

`poly` costruisce un poligono da coppie di coordinate X/Y. X è la direzione "destra" della tartaruga, Y è "alto".

<!-- example-source: poly-triangle -->
```clojure
(stamp (poly 0 0  40 0  0 30))
```

Un triangolo rettangolo: tre vertici, sei numeri. I vertici si leggono a coppie: `(0,0)`, `(40,0)`, `(0,30)`. Il contorno si chiude automaticamente dall'ultimo vertice al primo.

Le coordinate possono essere passate anche come vettore, utile quando i punti vengono da un calcolo o da una variabile:

<!-- example-source: poly-diamond -->
```clojure
(def diamond-pts [0 5  5 0  0 -5  -5 0])
(stamp (poly diamond-pts))
```

`poly` è lo strumento giusto quando hai già le coordinate: vertici misurati da un disegno, punti generati da una formula, oppure profili semplici come triangoli e trapezi dove scrivere le coppie X/Y è più diretto che ragionare in angoli.

Una cosa a cui fare attenzione: `poly` preserva l'ordine di avvolgimento che gli dai. Se i vertici sono in senso orario il profilo viene invertito, e le facce estruse guardano nella direzione sbagliata. Le booleane si comportano in modo strano, la mesh sembra intatta ma i risultati sono sbagliati. La regola è: vertici in senso antiorario nel piano destra-alto. Le primitive e `shape` correggono automaticamente l'avvolgimento; `poly` no. Se te ne accorgi a cose fatte, `reverse-shape` inverte l'avvolgimento di una shape (o di un vettore di shape) e raddrizza le normali.

Per i casi in cui nemmeno `poly` basta, perché i punti vengono da un calcolo, servono dei buchi, o serve controllo esplicito sull'ancoraggio, c'è il costruttore di basso livello `make-shape`: prende un vettore di punti `[x y]` (contorno esterno antiorario, eventuali buchi orari) e li avvolge in una shape. Per tutto il resto i costruttori visti sopra sono più comodi, perché impostano da soli avvolgimento e ancoraggio.

### `shape`: disegnare con la tartaruga

`shape` descrive un contorno muovendo una tartaruga 2D. Ha solo due comandi: `f` (avanti) e `th` (gira). La tartaruga parte dall'origine guardando verso destra (+X), e il contorno si chiude automaticamente dall'ultimo punto al primo.

<!-- example-source: shape-l-profile -->
```clojure
(stamp (shape (f 10) (th 90)
              (f 5)  (th 90)
              (f 5)  (th -90)
              (f 5)  (th 90)
              (f 5)))
```

Un profilo a L. La tartaruga 2D parte dall'origine e guarda verso destra (+X), quindi il primo `(f 10)` traccia un segmento orizzontale verso destra. `(th 90)` gira a sinistra di 90°, e da lì la tartaruga guarda verso l'alto (+Y), quindi il successivo `(f 5)` sale. Poi ancora a sinistra, avanti 5, a destra di 90° (per il gradino), avanti 5, a sinistra, avanti 5. Il segmento di chiusura viene aggiunto da `shape`.

`shape` è più naturale di `poly` quando il profilo è fatto di segmenti rettilinei con angoli noti: profili a C, a T, a U, dentature, guide. Non serve calcolare le coordinate dei vertici: si ragiona in "quanto è lungo questo tratto" e "di quanto giro".

<!-- example-source: shape-right-tri -->
```clojure
(def right-tri (shape (f 4) (th 90) (f 3)))
(stamp right-tri)
```

Un triangolo rettangolo con cateti 4 e 3. Due segmenti, un angolo, e il segmento di chiusura fa l'ipotenusa. Lo stesso triangolo con `poly` sarebbe `(poly 0 0  4 0  4 3)`: qui serve sapere le coordinate del terzo vertice, con `shape` basta sapere le lunghezze.

### Quando usare quale

La scelta è quasi sempre ovvia. Se hai le coordinate (misurate, calcolate, copiate da un altro programma), usa `poly`. Se pensi in termini di "vai avanti, gira, vai avanti", usa `shape`. Per profili simmetrici o standard come cerchi, rettangoli e poligoni regolari, le primitive della sezione precedente sono più espressive di entrambi.

Una regola pratica: se stai per scrivere `poly` con un mucchio di coordinate calcolate a mano usando seni e coseni, probabilmente `shape` con i giusti angoli è più leggibile. Se stai per scrivere `shape` con angoli calcolati con `atan2` per raggiungere un punto specifico, probabilmente `poly` con le coordinate dirette è più semplice.

C'è poi un caso in cui `shape` non ha rivali: la generazione procedurale, in stile Logo. Quando il profilo è un motivo che si ripete, un ciclo che emette comandi tartaruga è molto più diretto del calcolare i vertici a uno a uno. Questo sole nasce da diciotto ripetizioni dello stesso dente:

<!-- example-source: shape-sun -->
```clojure
(def sun
  (shape
    (dotimes [_ 18]
      (f 2)
      (th 80) (f 5) (th -160) (f 5) (th 80)
      (f 2)
      (th -20))))

(stamp sun)
```

Con `poly` avresti dovuto calcolare con seno e coseno ogni vertice delle punte e degli incavi; con `shape` descrivi un dente una volta sola e lo ripeti con `dotimes`. È lo stesso principio della tartaruga di Logo: la forma emerge dal movimento, non dalle coordinate.

### Importare da SVG

Una shape può anche nascere da SVG: `svg-shapes` legge una stringa SVG e restituisce un vettore di shape (una per elemento geometrico), `svg-shape` ne prende una sola per `:id` o `:index`. È utile soprattutto per riusare arte vettoriale già esistente. C'è però un limite pratico da conoscere: la stringa passata a `(svg ...)` non può contenere il carattere `"`, perché Clojure non ha le raw string e quel carattere chiuderebbe la stringa. L'SVG scritto a mano lo aggira usando l'apice singolo negli attributi; gli SVG esportati da Inkscape o Illustrator usano i doppi apici e quindi non si incollano direttamente, vanno caricati come file. Il caricamento da file, che è la via pratica, è nel cap. 9 (Workspaces e Librerie).

## Profili come valori

Se vieni da un CAD con sketch (Fusion, SolidWorks, FreeCAD), sei abituato a pensare ai profili 2D come entità ancorate a un piano: scegli una faccia, disegni sopra, lo sketch resta lì e ci torni a modificarlo. In Ridley non funziona così.

Una shape è un valore. Ha coordinate nel piano 2D della tartaruga (destra e alto), ma non ha una posizione nel mondo 3D. Un `(def L (shape ...))` non piazza niente nel viewport: produce un profilo in memoria, con i suoi punti nel piano locale. La shape acquista una posizione e un orientamento nel mondo solo quando qualcosa la consuma: `extrude`, `loft`, `revolve`, `stamp`. Il consumatore legge la posa della tartaruga in quel momento e proietta il profilo sul piano ortogonale alla direzione in cui la tartaruga sta guardando.

Tre conseguenze pratiche.

La prima: la stessa shape, consumata in pose diverse, produce mesh diverse.

<!-- example-source: same-shape-two-poses -->
```clojure
(def L
  (shape (f 10) (th 90)
         (f 5)  (th 90)
         (f 5)  (th -90)
         (f 5)  (th 90)
         (f 5)))

(register a (extrude L (f 8)))
(rt 30)
(register b (extrude L (f 8)))
```

Due profilati a L identici, sfalsati di 30 verso destra. Il profilo è lo stesso valore `L` consumato due volte; la tartaruga si è spostata fra le due chiamate, quindi le due mesh finiscono in posizioni diverse. Il profilo non sa dove andrà a finire; la tartaruga decide.

La seconda: la stessa shape, consumata con la tartaruga ruotata, produce mesh orientate diversamente.

<!-- example-source: same-shape-tilted -->
```clojure
(register C (extrude (star 5 6 10) (f 4)))
(rt 25) (tv 40)
(register D (extrude (star 5 6 10) (f 4)))
```

Il secondo profilato è inclinato perché la tartaruga ha ruotato prima dell'estrusione. Il profilo non è cambiato: è il piano su cui viene proiettato a essere diverso.

La terza: un profilo parametrico è semplicemente una funzione che restituisce una shape.

<!-- example-source: parametric-profile -->
```clojure
(defn channel [w d wall]
  (shape-difference
    (rect w d)
    (rect (- w (* wall 2)) (- d wall))))

(register small (extrude (channel 10 8 2) (f 30)))
(rt 25)
(register large (extrude (channel 20 15 3) (f 30)))
```

`channel` non è una shape: è una funzione che ne produce una diversa ogni volta, in base a larghezza, profondità e spessore delle pareti. Due chiamate con parametri diversi, due profili diversi, due mesh diverse. Nessun "dialogo di modifica sketch" necessario.

Questo modo di pensare ai profili, come valori da passare in giro e non come entità che vivono su un piano, è la differenza concettuale più grossa rispetto al CAD a sketch. Una volta interiorizzata, il resto del capitolo scorre: ogni operazione sulle shape (booleane, raccordi, offset) produce un nuovo valore che si può dare in pasto a qualsiasi consumatore.

## Booleane 2D

Le shape si combinano con le stesse operazioni logiche delle mesh 3D: unione, differenza, intersezione, or esclusivo. Il risultato è una nuova shape, pronta per essere estrusa o lavorata ulteriormente.

<!-- example-source: shape-washer -->
```clojure
(def washer (shape-difference (circle 20) (circle 14)))
(stamp washer)
```

`shape-difference` sottrae un cerchio più piccolo da uno più grande: il risultato è un anello. Estruso, diventa un tubo cavo. È lo stesso pattern "pieno meno cavità" del portapenne del cap. 2, ma applicato nel piano 2D prima dell'estrusione invece che nel volume 3D dopo.

<!-- example-source: shape-l-tube -->
```clojure
(def outer (shape-union (rect 20 40) (rect 40 20)))
(def inner (shape-offset outer -3))
(def l-tube (shape-difference outer inner))
(stamp l-tube)
```

`shape-union` fonde due rettangoli sovrapposti in un profilo a L. `shape-offset` con delta negativo contrae il contorno di 3 verso l'interno, producendo una copia più piccola. La differenza fra le due lascia un guscio a L di spessore uniforme 3. Qui `shape-offset` compare in anticipo perché è il modo più naturale di costruire il profilo interno; lo vedremo meglio nella sezione successiva.

Le quattro operazioni:

`shape-union` fonde due o più shape nel contorno che le comprende entrambe. Dove si sovrappongono, il contorno esterno diventa uno solo.

`shape-difference` sottrae la seconda shape dalla prima. Utile per creare fori, cavità, profili cavi. L'ordine conta: `(shape-difference A B)` è A con B scavato dentro, non il contrario.

`shape-intersection` tiene solo la regione in cui entrambe le shape sono presenti. Utile per ritagliare un profilo con una forma di taglio.

`shape-xor` tiene le regioni dove è presente una sola delle due shape, escludendo la sovrapposizione. A differenza delle altre tre, `shape-xor` restituisce un vettore di shape (perché il risultato può essere fatto di più pezzi disconnessi). Il vettore è accettato direttamente da `extrude`, `loft`, `revolve` e `stamp`.

<!-- example-source: shape-boolean-xor -->
```clojure
(def a (circle 15))
(def b (translate-shape (circle 15) 12 0))
(stamp (shape-xor a b))
```

Due cerchi sovrapposti: `shape-xor` produce due mezzelune, una per lato.

Una cosa da sapere: le booleane 2D gestiscono i buchi. Se una `shape-difference` crea un buco nel profilo (un'isola interna che non tocca il bordo esterno), il buco viene preservato nella shape risultante, e le operazioni a valle (`extrude`, `loft`, `revolve`) lo rispettano. Non serve fare niente di speciale: i buchi sono automatici.

## Raccordi e smussi 2D

I profili costruiti con segmenti rettilinei hanno spigoli vivi. Tre operazioni li ammorbidiscono o li modificano prima dell'estrusione: `fillet-shape` arrotonda gli angoli, `chamfer-shape` li taglia a 45°, `shape-offset` espande o contrae il contorno.

<!-- example-source: fillet-rect -->
```clojure
(stamp (fillet-shape (rect 40 20) 5))
```

`fillet-shape` sostituisce ogni angolo del rettangolo con un arco circolare di raggio 5. Il risultato è un rettangolo con gli angoli arrotondati, spesso chiamato "pill shape" quando il raggio è metà del lato corto.

<!-- example-source: chamfer-hex -->
```clojure
(stamp (chamfer-shape (polygon 6 20) 3))
```

`chamfer-shape` taglia ogni angolo dell'esagono con un segmento rettilineo a distanza 3 dal vertice. Il risultato è un dodecagono con facce alternate lunghe e corte.

Entrambe accettano un'opzione `:indices` per lavorare solo su alcuni vertici:

<!-- example-source: fillet-selective -->
```clojure
(stamp (fillet-shape (rect 30 15) 4 :indices [2 3]))
```

Solo i vertici 2 e 3 del rettangolo vengono arrotondati: il risultato è una linguetta con due angoli retti e due arrotondati. Gli indici partono da 0 e seguono l'ordine dei vertici della shape.

Per archi più lisci, `fillet-shape` accetta `:segments` (default 32, derivato dalla risoluzione corrente): `(fillet-shape (rect 40 20) 5 :segments 64)` produce curve più morbide.

### Offset

`shape-offset` espande o contrae un contorno di una distanza data.

<!-- example-source: offset-expand -->
```clojure
(stamp (shape-offset (rect 30 20) 3))
```

Delta positivo: il rettangolo cresce di 3 su tutti i lati. Il risultato è un rettangolo più grande con gli angoli arrotondati, perché l'offset di default usa giunzioni `:round`. È il modo più veloce per ottenere un rettangolo con angoli tondi senza dover scegliere un raggio di raccordo.

<!-- example-source: offset-contract -->
```clojure
(stamp (shape-offset (rect 30 20) -3))
```

Delta negativo: il rettangolo si contrae di 3 su tutti i lati. Utile per generare il profilo interno di un guscio, come nell'esempio del tubo a L della sezione precedente.

Il tipo di giunzione agli angoli si controlla con `:join-type`:

- `:round` (default): angoli arrotondati
- `:square`: angoli squadrati, estesi
- `:miter`: angoli vivi, estesi a punta

<!-- example-source: offset-miter -->
```clojure
(stamp (shape-offset (rect 30 20) 3 :join-type :miter))
```

Con `:miter` il rettangolo espanso resta un rettangolo, senza arrotondamenti.

`shape-offset` è spesso il complemento naturale delle booleane: espandi il profilo esterno, contrai per il profilo interno, differenza per il guscio. Lo abbiamo già visto nella 3.5 con il tubo a L, ed è un pattern che si ripete di frequente.

### Operatori generativi

Un'ultima famiglia di operatori non modifica un profilo esistente ma ne deriva uno nuovo per via algoritmica, di solito perforandolo. Producono shape con buchi, che si estrudono e si compongono con le shape-fn come qualsiasi altra.

`voronoi-shell` perfora un profilo con un motivo a celle di Voronoi: i bordi delle celle diventano materiale, gli interni diventano fori.

<!-- example-source: voronoi-shell-tube -->
```clojure
(register voro-tube
  (extrude (voronoi-shell (circle 20) :cells 40 :wall 1.5) (f 50)))
```

Un tubo traforato: la parete del cilindro è spezzata in 40 celle separate da nervature di 1.5. `:cells` e `:wall` controllano densità e spessore delle celle, `:seed` rende il motivo riproducibile.

`pattern-tile` perfora invece con un motivo ripetuto su griglia: tassella una shape sul bounding box di un'altra e sottrae le copie che si sovrappongono.

<!-- example-source: pattern-tile-grid -->
```clojure
(register grid-plate
  (extrude (pattern-tile (rect 60 40) (circle 2 16) :spacing [6 6]) (f 3)))
```

Una piastra con una griglia regolare 6×6 di fori circolari. Il motivo può essere qualsiasi shape, non solo un cerchio; `:spacing` controlla il passo della griglia, `:inset` restringe ogni copia prima di sottrarla.

Sono i primi di una famiglia che può crescere: operatori che generano geometria 2D da una regola invece che da coordinate o comandi tartaruga.

## Dove si usano le shape

Abbiamo aperto il capitolo mostrando `extrude` come motivazione: le shape servono perché diventano solidi. Ma `extrude` non è l'unico consumatore. Ecco la mappa completa dei posti in cui una shape viene accettata come input.

### extrude

Trascina il profilo lungo un percorso rettilineo o curvo. La sezione resta costante da un'estremità all'altra. Il risultato è una mesh.

<!-- example-source: where-extrude -->
```clojure
(register tube (extrude (circle 10) (f 30) (th 45) (f 20)))
```

Un tubo circolare che piega di 45° a metà percorso. Il profilo è un cerchio, il percorso è definito dai comandi tartaruga dopo la shape. Tutto il cap. 4 è dedicato all'estrusione.

### loft

Come `extrude`, ma il profilo può cambiare lungo il percorso: restringersi, torcersi, deformarsi. Il cambiamento è descritto da una shape-fn (una funzione che mappa `t ∈ [0,1]` a una shape) o da due shape di partenza e arrivo.

<!-- example-source: where-loft -->
```clojure
(register tapered-tube (loft (tapered (circle 15) :to 0) (f 30)))
```

Un cono: il cerchio si riduce a zero lungo il percorso. `tapered` è una shape-fn built-in; ce ne sono molte altre (`twisted`, `fluted`, `morphed`, `shell`, ecc.) e si compongono con `->`. Il cap. 6 le tratta in dettaglio.

### revolve

Ruota il profilo attorno all'asse della tartaruga, producendo un solido di rivoluzione. Il profilo sta nel piano destra-alto, l'asse di rivoluzione è la direzione avanti.

<!-- example-source: where-revolve -->
```clojure
(register bowl
  (revolve (shape (f 20) (th -90) (f 30) (th -90) (f 15))))
```

Una ciotola: il profilo (una specie di U aperta) viene ruotato di 360° attorno all'asse up. Anche `revolve` accetta shape-fn, quindi il profilo può variare durante la rotazione.

### stamp

Visualizza il profilo come contorno 2D nella posa corrente della tartaruga, senza produrre un solido. L'abbiamo già usato in tutto il capitolo per ispezionare le shape.

<!-- example-source: where-stamp -->
```clojure
(stamp (fillet-shape (rect 30 20) 5))
```

### Operatori 2D

Le shape sono anche input di altri operatori 2D: le booleane (`shape-union`, `shape-difference`, `shape-intersection`, `shape-xor`), i modificatori (`shape-offset`, `fillet-shape`, `chamfer-shape`, `shape-hull`, `shape-bridge`), le trasformazioni (`scale`, `rotate`, `translate`, `morph-shape`, `resample-shape`), e gli operatori generativi (`voronoi-shell`, `pattern-tile`). Il risultato è sempre un'altra shape, pronta per essere data in pasto a uno dei consumatori qui sopra. Li abbiamo visti nelle sezioni 3.5 e 3.6.

### Riepilogo

| Consumatore | Cosa produce | Capitolo |
|---|---|---|
| `extrude` | mesh (sezione costante) | 4 |
| `loft` | mesh (sezione variabile) | 6 |
| `revolve` | mesh (solido di rivoluzione) | 4 |
| `stamp` | preview 2D (nessun solido) | — |
| operatori 2D | altra shape | 3 |

Il flusso tipico è: costruisci la shape (3.1-3.4), lavorala con booleane e modificatori (3.5-3.6), poi dalla in pasto a un consumatore. La shape è il materiale grezzo, il consumatore è lo strumento che ne fa un solido.

## Generare shape da mesh

Finora abbiamo costruito le shape da zero: coordinate, comandi tartaruga, primitive, composizioni. Ma a volte il profilo che serve esiste già, nascosto dentro una mesh 3D: la sezione di un vaso a una certa altezza, la sagoma di un pezzo vista dall'alto, il contorno di una faccia. Tre operazioni lo estraggono.

### slice-mesh

`slice-mesh` taglia una mesh con il piano della tartaruga e restituisce il contorno della sezione come vettore di shape.

<!-- example-source: slice-mesh-bowl -->
```clojure
(register bowl
  (revolve (shape (f 20) (th 90) (f 30) (th 90) (f 15))))

(tv 90) (f 15)
(def shp (slice-mesh :bowl))
(f 20)
(stamp shp)
```

Il primo blocco costruisce una ciotola per rivoluzione. Poi la tartaruga ruota di 90° verso l'alto (così guarda verso +Z) e avanza di 15: ora il piano della tartaruga è un piano orizzontale a quota 15. `slice-mesh` taglia la ciotola con quel piano e restituisce il contorno della sezione. La tartaruga avanza di altri 20 per uscire dalla mesh, così lo `stamp` della sezione è visibile nel viewport.

Il risultato è un vettore perché la sezione può avere più contorni disconnessi (una mesh con buchi, o più corpi separati che intersecano lo stesso piano). Ogni contorno è una shape standard, pronta per `extrude`, `loft`, o qualsiasi operatore 2D.

`slice-mesh` accetta un nome registrato (`:bowl`), una mesh, o un nodo SDF. Il piano è sempre quello della tartaruga: la posizione decide dove taglia, la direzione avanti decide la normale del piano.

Per tagliare con un piano che non dipende dalla tartaruga, c'è `slice-at-plane`:

```clojure
(slice-at-plane :bowl [0 0 1] [0 0 15])
```

Stessa operazione, ma il piano è definito da una normale e un punto in coordinate mondo.

### project-mesh

`project-mesh` proietta la mesh sul piano della tartaruga e restituisce la sagoma (silhouette) come vettore di shape. Dove `slice-mesh` dà la sezione *al* piano, `project-mesh` dà l'ombra *sul* piano.

<!-- example-source: project-mesh-silhouette -->
```clojure
(register B (box 10 20 30))
(tv 15) (th 40) (f 40)
(stamp (project-mesh :B))
```

Una box vista da un'angolazione obliqua: la tartaruga si sposta e ruota, poi `project-mesh` proietta la box sul piano corrente della tartaruga e restituisce la sagoma come vettore di shape. Il risultato è un esagono irregolare — la silhouette di un parallelepipedo visto di sbieco. Utile per ricavare un footprint 2D da un pezzo 3D, ad esempio per estrudere una tasca di clearance leggermente più grande della sagoma.

### face-shape

`face-shape` estrae il contorno di una faccia specifica di una mesh come shape 2D, insieme alla posa (posizione e orientamento) della faccia.

```clojure
(def top (face-shape my-mesh face-id))

(turtle (:pose top)
  (extrude (:shape top) (f 20)))
```

L'argomento `face-id` è l'identificativo di una faccia, ottenuto con le funzioni di selezione facce come `find-faces` e `largest-face` (cap. 10). Il risultato è una mappa con `:shape` (la shape 2D) e `:pose` (posizione e orientamento della faccia nel mondo). Passando la posa a `turtle`, l'estrusione parte esattamente dalla faccia estratta, nella direzione giusta. È il modo più preciso per "continuare" una mesh da una delle sue facce.

### Il flusso inverso

Queste tre operazioni chiudono un cerchio: le sezioni 3.1-3.6 costruiscono shape per farle diventare mesh (via extrude, loft, revolve); questa sezione estrae shape da mesh esistenti per riusarle come input di nuove operazioni. Il pezzo che hai già costruito diventa materia prima per il pezzo successivo.

## Più forme alla volta

Alcune operazioni restituiscono non una shape singola ma un vettore di shape: `shape-xor` (che può produrre regioni disconnesse), `slice-mesh` (che può tagliare più contorni), `text-shape` (che produce una shape per lettera o per glifo composito). Non è un caso speciale da gestire: i consumatori principali (`extrude`, `loft`, `revolve`, `stamp`) accettano direttamente un vettore di shape e lo trattano come un profilo unico con più contorni.

<!-- example-source: vector-of-shapes-text -->
```clojure
(register hello (extrude (text-shape "Hello" :size 20) (f 3)))
```

`text-shape` restituisce un vettore di shape (una per lettera, alcune con buchi come la "e" e la "o"). `extrude` le estrude tutte lungo lo stesso percorso e fonde il risultato in un'unica mesh. Non serve `mesh-union` né `concat-meshes`: il vettore viene gestito internamente.

Lo stesso vale per `slice-mesh`:

<!-- example-source: vector-of-shapes-slice -->
```clojure
(register B (attach (box 10 20 30) (tv 30) (th 15)))
(register ring (extrude (slice-mesh :B) (f 30)))
```
L'esempio crea una shape dall'intersezione del box, leggermente ruotato, con il piano XY (la tartaruga è all'origine, voltata verso +X) ed estrude quella shape in avanti.

Se la sezione contiene più contorni, vengono estrusi tutti insieme. Se ne contiene uno solo, funziona comunque: un vettore con un elemento è gestito come una shape singola.

Anche gli operatori 2D accettano vettori: `shape-offset`, `fillet-shape`, `chamfer-shape` applicano l'operazione a ogni shape del vettore e restituiscono un vettore. Le booleane prendono shape singole come argomenti, ma il risultato di `shape-xor` (un vettore) può essere dato direttamente a un consumatore senza scompattarlo.

La regola è semplice: dove accetti una shape, accetti anche un vettore di shape. Non serve pensarci a meno che non serva lavorare su un singolo contorno del vettore, nel qual caso basta indicizzarlo con `(nth shapes 0)` o `(first shapes)`.

## Forme di testo

`text-shape` converte una stringa di testo in un vettore di shape 2D, usando il parsing dei font OpenType. Le lettere diventano contorni con buchi (la "o", la "e", la "B"), pronti per l'estrusione.

<!-- example-source: text-shape-basic -->
```clojure
(register title (extrude (text-shape "Ridley" :size 15) (f 2)))
```

Il font di default è Roboto. `:size` controlla l'altezza delle lettere. Il risultato è un vettore di shape (una per glifo), che `extrude` gestisce direttamente come visto nella sezione precedente.

Il testo in Ridley va oltre la semplice estrusione di lettere: il cap. 13 copre font custom, testo che segue una curva con `text-on-path`, e misurazione dell'ingombro con `text-width`. Qui il punto è solo che le shape di testo sono shape normali: si possono trasformare, combinare con booleane, dare in pasto a `loft` o `revolve` esattamente come qualsiasi altra shape.

<!-- example-source: text-shape-difference -->
```clojure
(register stamp-block
  (mesh-difference
    (box 80 20 5)
    (attach (extrude (text-shape "HELLO" :size 12) (f 30)) (f -10))))
```

Testo inciso in un blocco: le lettere estruse vengono spostate indietro con `attach` in modo che attraversino la faccia frontale, e poi sottratte dalla mesh con `mesh-difference`.

## Forme riutilizzabili

Nel cap. 2 abbiamo visto `defn` per creare pezzi 3D riutilizzabili. Lo stesso vale per le shape: una funzione che restituisce una shape è un profilo parametrico.

<!-- example-source: reusable-t-profile -->
```clojure
(defn t-profile [w h flange web]
  (shape-union
    (rect w flange)
    (translate-shape (rect web (- h flange)) 0 (/ (- h flange) -2))))

(register small (extrude (t-profile 20 30 4 3) (f 40)))
(rt 30)
(register large (extrude (t-profile 40 50 6 4) (f 60)))
```

`t-profile` è una funzione che produce un profilo a T: una flangia orizzontale (il rettangolo largo) e un'anima verticale (il rettangolo stretto, spostato verso il basso con `translate-shape`). Quattro parametri: larghezza totale, altezza totale, spessore della flangia, spessore dell'anima. Due chiamate con parametri diversi, due profilati diversi.

La composizione va oltre i parametri numerici. Una funzione può accettare una shape come argomento e modificarla:

<!-- example-source: reusable-hollow -->
```clojure
(defn hollow [shape wall]
  (shape-difference shape (shape-offset shape (- wall))))

(register hex-tube
  (extrude (hollow (polygon 6 20) 3) (f 40)))

(rt 50)
(register round-tube
  (extrude (hollow (circle 15) 2) (f 40)))
```

`hollow` prende una shape qualsiasi e ne fa una versione cava, contraendo il contorno di `wall` e sottraendolo dall'originale. Funziona con cerchi, poligoni, rettangoli, profili custom: il pattern "pieno meno contrazione" non dipende dalla forma, dipende solo dall'operazione.

Questo è lo stesso principio visto nella 3.4: una shape è un valore, una funzione che restituisce una shape è una fabbrica di valori. Combinando `defn` con gli operatori 2D di questo capitolo, si costruisce una libreria personale di profili parametrici che si riusano in tutto il modello.
