<!--
=========================================================================
NOTE INTERNE PER L'AUTORE — NON DESTINATE AL LETTORE FINALE

Struttura del capitolo:
6.1  Cosa sono le shape-fn
6.2  Trasformazioni geometriche: tapered, twisted, fluted, rugged
6.3  Displacement: noisy, displaced
6.4  morphed: interpolare tra due profili
6.5  profile: il profilo come silhouette
6.6  heightmap: displacement da mappa di altezze
6.7  mesh-to-heightmap e heightmap tileabili
6.8  Shell, woven shell e embroid (loft + shell/embroid shape-fn)
6.9  Raccordi sui cap (capped)
6.10 Comporre shape-fn
6.11 Thickness-fn: controllare lo spessore delle pareti
6.12 Scrivere una shape-fn propria

Prerequisiti: cap. 3 (shape 2D), cap. 4 (extrude, loft), cap. 5 (path).
Il cap. 4 introduce extrude/loft e chiude con una sezione-ponte sulle shape-fn.
Shell, woven-shell, capped ed embroid vivono qui (spostati dal cap. 4 il
2026-06-10). Qui si copre l'intero meccanismo delle shape-fn.

Vincoli:
- shell deve essere l'ultima nella catena di composizione
- embroid non si compone in catena: stampa le proprie facce (vedi la sezione embroid), eccezione più forte di shell
- loft-n controlla i passi longitudinali (resolution non influenza loft)
- shell/woven-shell richiedono risoluzione ~512 su entrambi gli assi
=========================================================================
-->

# Da funzioni matematiche a forme

<!-- level: advanced -->

> Questo capitolo è tra i più avanzati del manuale. Le shape-fn sono uno strumento potente, ma non indispensabile per iniziare a modellare: con le primitive, le estrusioni e le operazioni booleane dei capitoli precedenti si può già costruire molto. Se stai leggendo il manuale per la prima volta, puoi saltare al cap. 7 e tornare qui quando sentirai il bisogno di superfici che variano lungo il percorso, texture procedurali o gusci decorati.

## Cosa sono le shape-fn

Nel cap. 4 abbiamo visto che `extrude` trascina una shape identica lungo un percorso. Ogni anello è una copia esatta del profilo di partenza. Quando il profilo deve cambiare lungo il percorso (rastremarsi, torcersi, ondularsi), serve `loft`, e serve un modo per dire a `loft` *come* il profilo cambia.

Quel modo sono le shape-fn: funzioni che, dato un valore `t` compreso tra 0 e 1, restituiscono una shape. `t = 0` è l'inizio del percorso, `t = 1` è la fine. Il loft valuta la shape-fn a ogni passo e usa il profilo risultante come shape per il passo corrente. Alla fine del processo vengono generate le facce laterali (unendo con nuovi segmenti i vertici corrispondenti di tutte le shape prodotte) e quelle iniziale e finale (cap). Tutto questo è possibile solo se ogni passo produce shape con lo stesso numero di vertici. È un vincolo importante: le shape-fn possono spostare, scalare, ruotare i punti del profilo, ma non possono aggiungerne o toglierne. Se due shape hanno un numero di punti diverso e devono coesistere in una catena, si usa `resample-shape` per portarle allo stesso conteggio prima di passarle alla shape-fn (alcune, come `morphed`, lo fanno automaticamente).

<!-- example-source: shapefn-intro-tapered -->
```clojure
(register cone
  (loft (tapered (circle 20) :to 0) (f 40)))
```

`tapered` prende un cerchio e restituisce una shape-fn. A `t = 0` il cerchio ha raggio 20; a `t = 1` il raggio è 0 (`:to 0`). In mezzo, il raggio decresce linearmente. Il loft produce un cono.

La stessa logica vale per tutte le shape-fn built-in: `twisted`, `fluted`, `noisy`, `morphed`, `profile`, `heightmap`. Ognuna descrive un tipo diverso di variazione, ma il meccanismo è lo stesso: una funzione da `t` a shape, che `loft` (o `revolve`) valuta passo per passo.

`loft` accetta anche una forma più semplice in cui il primo parametro è una shape e il secondo è una funzione di trasformazione `(fn [shape t] -> shape)`. Le shape-fn rendono esplicito ciò che lì è implicito: la logica di variazione vive *dentro* la shape, non fuori. Questo permette di comporre più variazioni con il threading `->`:

<!-- example-source: shapefn-intro-compose -->
```clojure
(register column
  (loft
    (-> (circle 15 128)
        (fluted :flutes 20 :depth 1.5)
        (tapered :to 0.25))
    (f 80)))
```

Un cerchio scanalato che si rastrema. Ogni shape-fn avvolge la precedente: `fluted` riceve il cerchio, `tapered` riceve il risultato di `fluted`. Il loft valuta la catena a ogni passo, e ogni passo produce un profilo scanalato e leggermente più stretto del precedente.


## Trasformazioni geometriche

Quattro shape-fn modificano la geometria del profilo in modi diversi lungo il percorso.

### tapered

Scala il profilo uniformemente. A `t = 0` il profilo ha scala 1 (o il valore di `:from`); a `t = 1` ha la scala indicata da `:to`.

<!-- example-source: shapefn-tapered -->
```clojure
;; Cono: da raggio 20 a raggio 0
(register cone (loft (tapered (circle 20) :to 0) (f 40)))
(rt 50)

;; Tronco di cono: da raggio 20 a raggio 10
(register frustum (loft (tapered (circle 20) :to 0.5) (f 40)))
(rt 50)

;; Espansione: da raggio 10 a raggio 20
(register horn (loft (tapered (circle 20) :from 0.5 :to 1) (f 40)))
```

`:to` e `:from` sono rapporti rispetto alla dimensione originale del profilo: `0.5` dimezza, `2` raddoppia. Se ometti `:from`, il valore di partenza è 1.

`tapered` funziona con qualsiasi shape, non solo cerchi. Un rettangolo rastremato produce una piramide tronca; una shape personalizzata produce una sezione che si restringe mantenendo le proporzioni.

### twisted

Ruota il profilo progressivamente lungo il percorso.

<!-- example-source: shapefn-twisted -->
```clojure
;; Rettangolo che ruota di 90° lungo il percorso
(register ribbon
  (loft (twisted (rect 30 5) :angle 90) (f 60)))
(rt 60)

;; Torsione completa (360° default)
(register drill
  (loft (twisted (rect 20 10)) (f 80)))
```

`:angle` è in gradi. Se lo ometti, il default è 360 (un giro completo). La rotazione è lineare rispetto a `t`.

`twisted` è più visibile su profili asimmetrici: un cerchio ruotato è identico a se stesso, quindi la torsione non produce effetti visibili (se non a risoluzione molto bassa, dove il cerchio è in realtà un poligono). Rettangoli, stelle, profili a L mostrano bene la spirale.

### fluted

Aggiunge scanalature longitudinali al profilo. La profondità delle scanalature è costante lungo tutto il percorso (non varia con `t`).

<!-- example-source: shapefn-fluted -->
```clojure
;; Colonna dorica: 20 scanalature
(register pillar
  (loft (fluted (circle 15 128) :flutes 20 :depth 1.5) (f 80)))
```

`:flutes` è il numero di scanalature; `:depth` è la profondità radiale. Il profilo deve avere abbastanza punti perché le scanalature siano ben definite: come regola pratica, almeno 6 punti per scanalatura. Un `(circle 15 128)` ha 128 punti, sufficiente per 20 scanalature.

### rugged

Aggiunge una perturbazione radiale composta da più sinusoidi sovrapposte (stile fBm), che varia sia attorno al profilo sia lungo il percorso. A differenza di `fluted` (un'unica sinusoide regolare con creste parallele all'asse), `rugged` produce asperità irregolari a più scale — utile per superfici rocciose, cortecce, scogliere.

<!-- example-source: shapefn-rugged -->
```clojure
(register rough-tube
  (loft (rugged (circle 15 256) :amplitude 2 :frequency 8 :octaves 2) (f 40)))
```

Parametri:
- `:amplitude` — spostamento radiale massimo.
- `:frequency` — numero base di oscillazioni della prima ottava attorno al profilo.
- `:octaves` (default 3) — quante sinusoidi sovrapposte; ogni ottava raddoppia la frequenza. Con `:octaves 1` torna a un'unica sinusoide.
- `:gain` (default 0.5) — ampiezza relativa di ogni ottava (0.5 = fBm standard, più alto = più aspro).
- `:seed` — sfasamento per variare il pattern senza cambiarne le caratteristiche.

Il profilo deve avere abbastanza punti per catturare l'ottava più alta: con `:frequency F` e `:octaves N` la frequenza massima è `F · 2^(N-1)`. Per `:frequency 8 :octaves 2` la frequenza finale è 16, quindi servono almeno ~64 punti per anello. L'esempio sopra usa 256 punti per avere margine, e il default di 64 passi longitudinali è sufficiente.


## Displacement

Le shape-fn di displacement spostano i vertici del profilo in modo non uniforme, producendo superfici organiche o texturizzate.

### noisy

Sposta i vertici radialmente usando una funzione di rumore continuo. A differenza di `rugged` (sinusoidi sovrapposte, dall'aspetto angolare/cristallino), `noisy` produce variazioni più morbide e organiche grazie all'interpolazione del rumore Perlin-like.

<!-- example-source: shapefn-noisy -->
```clojure
(register organic
  (loft (noisy (circle 15 256) :amplitude 3 :scale 3) (f 40)))
```

Le opzioni controllano il carattere del rumore:

- `:amplitude` (default 1.0) controlla l'entità dello spostamento.
- `:scale` (default 3.0) controlla la scala spaziale: valori bassi producono variazioni ampie e morbide, valori alti producono variazioni fitte e nervose.
- `:scale-x` e `:scale-y` permettono di avere scale diverse nelle due direzioni (attorno al profilo e lungo il percorso).
- `:octaves` (default 1) aggiunge dettaglio sovrapposto: più ottave producono una superficie più ricca, tipo terreno montagnoso.
- `:seed` (default 0) cambia il pattern mantenendo le stesse caratteristiche.

<!-- example-source: shapefn-noisy-detailed -->
```clojure
;; Superficie organica dettagliata
(register bark
  (loft
    (noisy (circle 12 96) :amplitude 1.5 :scale 5 :octaves 3)
    (f 60)))
```

Il `loft` di default usa 64 passi longitudinali. Per pattern molto fitti o con `:octaves` alti, può servire alzarli con `loft-n 128` o più.

### displaced

Lo strumento più flessibile: sposta ogni vertice radialmente secondo una funzione arbitraria.

<!-- example-source: shapefn-displaced -->
```clojure
(register ripple
  (loft
    (displaced (circle 15 64)
      (fn [p t] (* 2 (sin (* t PI 4)) (sin (* (angle p) 6)))))
    (f 40)))
```

La funzione riceve due argomenti: `p` (il punto 2D `[x y]` sul profilo, prima dello spostamento) e `t` (la posizione lungo il percorso, 0-1). Restituisce un numero: lo spostamento radiale in unità. Valori positivi spostano verso l'esterno, valori negativi verso l'interno.

`angle` è una funzione di utilità che restituisce l'angolo in radianti di un punto 2D rispetto all'origine. Utile per pattern che dipendono dalla posizione angolare sul profilo.

`displaced` è la shape-fn da usare quando nessuna delle built-in fa quello che serve. Qualsiasi pattern esprimibile come `f(posizione_sul_profilo, posizione_sul_percorso) -> spostamento_radiale` si può scrivere come `displaced`.

Dentro una `displaced` puoi chiamare qualsiasi funzione matematica. Per superfici organiche o rocciose, `fbm` (fractal Brownian motion) è il mattone più utile: somma più ottave di rumore a frequenze crescenti e ampiezze decrescenti, e produce dettaglio a più scale.

<!-- example-source: shapefn-fbm-rocky -->
```clojure
(register rocky
  (loft (displaced (circle 15 96)
          (fn [p t] (* 2 (fbm (* (angle p) 4) (* t 6) 4))))
    (f 50)))
```

`(fbm x y)` campiona il campo di rumore a quelle coordinate; il terzo argomento è il numero di ottave, più ottave più dettaglio. Qui l'angolo del punto e la posizione lungo il percorso diventano le due coordinate del rumore. È lo stesso meccanismo che `noisy` usa internamente con `:octaves > 1`: se ti basta il displacement radiale standard `noisy` è più diretto, `fbm` dentro `displaced` serve quando vuoi controllo pieno sulla formula.


## morphed: interpolare tra due profili

`morphed` interpola tra due shape diverse lungo il percorso. A `t = 0` il profilo è la prima shape; a `t = 1` è la seconda. In mezzo, i punti vengono interpolati linearmente.

<!-- example-source: shapefn-morphed -->
```clojure
(register transition
  (loft (morphed (rect 20 20) (circle 15 32)) (f 40)))
```

Un quadrato che diventa un cerchio. `morphed` si occupa automaticamente di due cose che renderebbero la transizione problematica:

1. **Conteggio punti**: se le due shape hanno un numero diverso di punti, vengono entrambe ricampionate al conteggio massimo.
2. **Allineamento angolare**: il vertice `i` di `shape-a` viene accoppiato con il vertice di `shape-b` angolarmente più vicino. Senza questo passaggio, accoppiando ad esempio il primo vertice di un rettangolo `(-10,-10)` con il primo di un cerchio `(15,0)`, l'interpolazione passerebbe vicino all'origine producendo un poligono autointersecante (un bowtie). Con l'allineamento, il punto intermedio è un quadrato arrotondato.

<!-- example-source: shapefn-morphed-star -->
```clojure
(register star-to-circle
  (loft (morphed (star 5 20 10) (circle 12 64)) (f 50)))
```

Il risultato di `morphed` è una transizione geometrica, non topologica: se le due shape hanno strutture di concavità molto diverse, anche con l'allineamento i punti intermedi possono autointersecarsi. Profili con la stessa struttura di concavità (entrambi convessi, o entrambi a 5 punte come stella e pentagono) producono transizioni più pulite.

Esiste anche la forma grezza del morphing: `loft` chiamato con due shape, documentata anche con il nome esplicito `loft-between`. Interpola linearmente tra le due a ogni anello, ma richiede che abbiano lo stesso numero di punti e non fa l'allineamento angolare di `morphed`. Per transizioni tra shape diverse `morphed` resta la scelta giusta; `loft-between` è comodo quando le due shape sono già compatibili e vuoi solo l'interpolazione diretta.


## profile: il profilo come silhouette

`profile` scala la sezione trasversale per seguire una silhouette definita da un path. Il path viene letto come un profilo 2D dove l'asse X rappresenta il raggio e l'asse Y rappresenta l'altezza.

<!-- example-source: shapefn-profile -->
```clojure
(def vase-silhouette
  (path (f 5) (th 80) (f 15) (arc-h 5 -160) (f 15)))

(register vase
  (loft (-> (circle 20 64) (profile vase-silhouette)) (f 40)))
```

A ogni passo `t`, `profile` legge la coordinata X del path alla posizione corrispondente e scala il profilo di base di conseguenza. Dove il path si allarga, il vaso si gonfia; dove si restringe, il vaso si stringe.

Il path della silhouette funziona meglio quando è liscio. Angoli bruschi producono scalini nella superficie. Per silhouette complesse, un `bezier-as` sul path produce risultati migliori:

<!-- example-source: shapefn-profile-smooth -->
```clojure
(def raw-sil (path (f 3) (th 60) (f 10) (th -120) (f 10) (th 60) (f 3)))

(register smooth-vase
  (loft (-> (circle 15 64) (profile (path (bezier-as raw-sil)))) (f 50)))
```

`profile` è lo strumento giusto per forme di rivoluzione asimmetriche: vasi, bottiglie, gambe di tavolo, colonne che si gonfiano leggermente a metà altezza. Il profilo di base (cerchio, quadrato, stella) determina la sezione trasversale; la silhouette determina come varia in altezza.


## heightmap: displacement da mappa di altezze

`heightmap` usa una griglia 2D di valori come mappa di displacement. Ogni cella della griglia corrisponde a uno spostamento radiale. La mappa viene "avvolta" attorno al profilo (asse X della mappa = posizione angolare, asse Y = posizione lungo il percorso) e applicata come displacement.

<!-- example-source: shapefn-heightmap -->
```clojure
(def weave (weave-heightmap :threads 6 :spacing 5 :radius 2 :resolution 128))

(register woven-cylinder
  (loft-n 128
    (heightmap (circle 20 128) weave :amplitude 3)
    (f 60)))
```

`:amplitude` controlla l'entità dello spostamento. La heightmap si ripete automaticamente (tiling) sia in orizzontale sia in verticale, quindi una mappa piccola può coprire superfici grandi.

Le opzioni di tiling controllano quante ripetizioni della mappa vengono mappate sulla superficie:

- `:tile-x` (default 1): ripetizioni nella direzione angolare.
- `:tile-y` (default 1): ripetizioni nella direzione del percorso.
- `:offset-x`, `:offset-y` (default 0): sfasamento iniziale.
- `:center` (default false): se true, sposta il range di campionamento a [-0.5, 0.5].

Una heightmap può venire da tre fonti: `weave-heightmap` (pattern analitico di intreccio), `mesh-to-heightmap` (rasterizzazione da geometria 3D), o costruita a mano come griglia di valori.

La risoluzione è importante su entrambi gli assi. La heightmap stessa deve avere abbastanza pixel per non apparire sfocata. Il profilo deve avere abbastanza punti perché il displacement sia leggibile. E il loft deve avere abbastanza passi longitudinali. Per risultati leggibili, 128 è un buon punto di partenza per tutti e tre.


## mesh-to-heightmap e heightmap tileabili

### mesh-to-heightmap

Qualsiasi mesh può diventare una heightmap. `mesh-to-heightmap` guarda la mesh dall'alto (asse Z), rasterizza i valori Z in una griglia 2D, e restituisce una heightmap pronta per l'uso con la shape-fn `heightmap`.

<!-- example-source: shapefn-mesh-to-heightmap  :warning slow -->
```clojure
;; Una sfera diventa una mappa di altezze a forma di cupola
(def dome-hm (mesh-to-heightmap (sphere 10 32 16) :resolution 128))

(register embossed
  (loft-n 256
    (heightmap (circle 20 256) dome-hm :amplitude 2 :tile-x 4 :tile-y 4)
    (f 60)))
```

Le opzioni di `mesh-to-heightmap` controllano la finestra di campionamento:

- `:resolution` (default 128): dimensione della griglia (pixel per lato).
- `:bounds` `[x0 y0 x1 y1]`: area XY da rasterizzare. Se omesso, usa il bounding box della mesh.
- `:offset-x`, `:offset-y`, `:length-x`, `:length-y`: modo alternativo per specificare la finestra (punto di partenza + dimensione).

### weave-heightmap

Genera analiticamente una heightmap che rappresenta un pattern di intreccio. A differenza di `mesh-to-heightmap`, non parte da una mesh ma calcola il pattern matematicamente.

<!-- example-source: shapefn-weave-heightmap -->
```clojure
(def basket (weave-heightmap :threads 4 :spacing 5 :radius 2 :resolution 128))

(register basket-weave
  (loft
    (heightmap (circle 20 128) basket :amplitude 2 :tile-x 4 :tile-y 4)
    (f 60)))
```

Le opzioni:

- `:threads` (default 4): numero di fili per direzione.
- `:spacing` (default 5): distanza tra i centri dei fili.
- `:radius` (default 2): raggio della sezione del filo.
- `:lift` (default = radius): quanto il filo sale sopra l'incrocio.
- `:resolution` (default 128): risoluzione della griglia.
- `:profile` (`:round` o `:flat`): sezione del filo.
- `:thickness` (default radius * 0.5, per `:flat`): spessore del filo piatto.

### heightmap-to-mesh

Converte una heightmap in una mesh piatta con Z dai valori della mappa. È uno strumento di debug: la mesh prodotta non è manifold (è una superficie aperta, non un solido chiuso), quindi non si può usare in operazioni booleane né esportare per la stampa. Il suo scopo è permettere di visualizzare una heightmap come superficie 3D per verificarne il contenuto prima di usarla con `heightmap`.

<!-- example-source: shapefn-heightmap-to-mesh -->
```clojure
(def dome-hm (mesh-to-heightmap (sphere 10 32 16) :resolution 128))
(def terrain (heightmap-to-mesh dome-hm :z-scale 0.5 :size 40))
(register ground terrain)
```

- `:z-scale` (default 1.0): amplifica i valori Z.
- `:size`: scala la mesh perché entri in un quadrato N×N centrato all'origine.

### Heightmap tileabili

La heightmap prodotta da `weave-heightmap` è tileabile per costruzione: i bordi combaciano. Per heightmap generate da `mesh-to-heightmap`, il tiling funziona solo se la mesh di partenza ha bordi compatibili. In pratica, questo si ottiene scegliendo una finestra di campionamento che copra un periodo completo della geometria sorgente.

`sample-heightmap` campiona una heightmap a coordinate `u, v` arbitrarie con interpolazione bilineare, e applica automaticamente il tiling (le coordinate che escono dal range [0,1] vengono riportate dentro). È la funzione usata internamente dalla shape-fn `heightmap`, ma è esposta anche per usi diretti come il calcolo di offset in funzioni custom.

```clojure
;; Campionare il valore al centro della heightmap
(sample-heightmap weave 0.5 0.5)
```


## Shell, woven shell e embroid

Le shape-fn viste finora deformano un profilo che resta pieno. Ma molti oggetti reali sono cavi: vasi, lampade, contenitori, paralumi. `shell` è una shape-fn che trasforma un profilo pieno in un guscio cavo con pareti di spessore controllato, con la possibilità di aprire finestre decorative nelle pareti.

Siccome `shell` è una shape-fn (il profilo cambia lungo il percorso, passando da pieno a cavo), serve `loft` al posto di `extrude`. La differenza pratica è minima: dove prima scrivevi `(extrude shape ...)`, ora scrivi `(loft (shell shape ...) ...)`.

<!-- example-source: shell-solid -->
```clojure
(register cup
  (loft (shell (circle 20 64) :thickness 2 :style :solid)
    (f 40)))
```

Un bicchiere: un cerchio estruso come guscio con pareti spesse 2. `:style :solid` produce pareti piene, senza aperture. Il risultato è un cilindro cavo aperto da entrambi i lati: il guscio non ha né fondo né coperchio. Per chiuderli, `shell` accetta le opzioni `:cap-top` e `:cap-bottom`. Un numero produce un tappo pieno dello spessore indicato; una mappa produce un tappo con pattern (Voronoi, griglia).

<!-- example-source: shell-cup-with-bottom -->
```clojure
(register cup-with-bottom
  (loft (shell (circle 20 64) :thickness 2 :style :solid :cap-bottom 2)
    (f 40)))
```

Con `:cap-bottom 2` il bicchiere ha il fondo chiuso, spesso 2. Il top resta aperto, come ci si aspetta da un bicchiere.

La parete è simmetrica: metà dello spessore sporge verso l'esterno, metà verso l'interno rispetto al profilo originale.

### Stili di apertura

Lo stile controlla il pattern delle aperture nelle pareti.

<!-- example-source: shell-voronoi :warning slow -->
```clojure
(register lamp
  (loft-n 512 (shell (circle 20 512) :thickness 2 :style :voronoi
                :cells 8 :rows 6 :softness 0.6)
    (f 50)))

```

`:voronoi` distribuisce celle irregolari sulle pareti. I bordi delle celle sono materiale, l'interno è vuoto. `:cells` controlla quante celle per anello, `:rows` quanti anelli lungo il percorso.

<!-- example-source: shell-lattice :warning slow  -->
```clojure
(register vase
  (loft-n 512 (shell (circle 15 512) :invert? true :thickness 2 :style :lattice :openings 4 :rows 6)
    (f 60)))
```

`:lattice` produce un pattern a mattoni: file di blob solidi sfasati lungo la circonferenza. `:openings` controlla il numero di mattoni per fila, `:rows` il numero di righe. Con `:invert? true` il pattern si inverte: i mattoni diventano aperture in un guscio continuo. Senza `:invert?`, il risultato sono i mattoni staccati (utile come texture a rilievo, meno come guscio).

L'opzione `:softness` (per `:voronoi` e `:lattice`, default `0.6`) controlla come vengono tagliati i bordi delle aperture. Per default il taglio è *isocontour*: il bordo viene affettato nella posizione esatta in cui la parete incontra l'apertura, fra un vertice e l'altro, e le aperture risultano lisce — con un piccolo labbro rastremato — anche a risoluzione moderata. Con `:softness 0` il taglio torna *binario*: ogni triangolo della griglia è tenuto o scartato per intero, e i bordi restano scalettati lungo la griglia di anelli e segmenti (alzare la risoluzione rimpicciolisce i denti ma non li elimina). Un valore intorno a `0.5–0.8` è il punto ottimale. È l'equivalente, per gli shell, dell'`:edge-softness` del rilievo testuale (cap. 13). (`:lattice` con `:invert?` usa sempre il taglio binario.)

Gli altri stili disponibili sono `:checkerboard` (scacchiera) e `:weave` (intreccio). Ognuno ha le sue opzioni specifiche; la Reference le documenta tutte. `:invert? true` funziona con tutti gli stili, comprese le thickness-fn custom passate con `:fn`.

### Shell in composizione

`shell` è una shape-fn come `tapered` o `twisted`, quindi si compone con `->`:

<!-- example-source: shell-composed :warning slow -->
```clojure
(register tapered-lamp
  (loft-n 512 (-> (circle 20 512)
                  (shell :thickness 2 :style :voronoi :cells 8 :rows 6)
                  (tapered :to 0.5))
    (f 60)))
```

Una lampada che si rastrema: `shell` rende il profilo cavo con aperture Voronoi, `tapered` lo riduce progressivamente lungo il percorso. Le due trasformazioni si applicano in sequenza a ogni anello del loft. Il quadro generale della composizione, e il vincolo per cui `shell` chiude sempre la catena, è il tema della sezione "Comporre shape-fn" più avanti.

### Woven shell

`woven-shell` è una variante di `shell` che non si limita a variare lo spessore delle pareti: sposta anche il centro della parete radialmente, così i fili passano davanti e dietro l'uno all'altro come in un intreccio reale.

<!-- example-source: woven-shell-basic :warning slow-->
```clojure
(register basket
  (loft-n 512 (woven-shell (circle 20 512) :thickness 3 :strands 8)
    (f 50)))
```

Un cesto intrecciato. I fili diagonali si incrociano con un vero sopra/sotto tridimensionale, non solo un pattern di fori. `:strands` controlla il numero di fili; `:mode :orthogonal` produce un intreccio a trama e ordito (tipo vimini) invece del pattern diagonale di default.

Come `shell`, `woven-shell` si compone con altre shape-fn:

<!-- example-source: woven-lamp :warning slow-->
```clojure
(register woven-lamp
  (loft-n 512 (-> (circle 20 512)
                (woven-shell :thickness 3 :strands 6)
                (tapered :to 3.5))
    (f 50)))
```

### Embroid: traforare

`shell` parte da un profilo pieno e lo svuota, lasciando pareti sottili con un pattern di aperture. `embroid` copre il caso complementare: una parete che è già una superficie sottile, non un solido da svuotare, e vi ritaglia dentro lo stesso tipo di finestre. È il caso in cui `shell` non si applica, perché non c'è niente da svuotare. Conviene pensarla come "fare una porzione di guscio".

A differenza delle altre shape-fn, `embroid` non prende una shape ma il path che definisce la linea mediana della parete, più lo spessore. Costruisce le due facce della parete spostandosi di `±spessore/2` perpendicolarmente al path in ogni punto, quindi la perforazione attraversa lo spessore qualunque sia la curvatura del percorso. Ogni apertura è un foro passante rifinito tra le due facce, e il risultato è watertight e manifold. Come `shell`, è una shape-fn e va usata con `loft`.

<!-- example-source: embroid-wall -->
```clojure
(register panel
  (loft (embroid (path (f 3) (arc-h 50 90) (f 70))
          3
          :resolution 400
          :wall {:style :honeycomb :cells 8 :border 4})
    (f 45)))
```

Una parete curva traforata con un nido d'ape regolare e una cornice piena di 4 unità su tutti i lati. Il primo argomento di `embroid` è il path della linea mediana (un tratto dritto, un arco di 90°, un altro tratto dritto), il secondo è lo spessore. Sia il tratto dritto sia quello curvo vengono perforati, perché il pattern segue il percorso invece di una direzione fissa.

Lo stile `:honeycomb` è il default. Con `:style :pattern` si usa una shape qualsiasi come motivo dell'apertura:

<!-- example-source: embroid-holes -->
```clojure
(register grille
  (loft (embroid (path (f 3) (arc-h 50 90) (f 70))
          3
          :wall {:style :pattern :pattern (circle 4)
                 :spacing 12 :grid :hex :inset 0.5})
    (f 45)))
```

Fori tondi su griglia esagonale. `:spacing` è il passo della griglia, `:grid` sceglie tra disposizione quadrata o esagonale sfalsata, `:inset` rimpicciolisce il motivo per ingrossare i ponticelli fra un foro e l'altro. C'è anche `:style :voronoi`, come per `shell`. La scheda Reference di [embroid](ref:embroid) documenta tutte le opzioni di pattern, cornice e tappi.

Due punti pratici. La nitidezza dei bordi lungo il percorso si regola con `:resolution` (campioni lungo il path), indipendentemente dal numero di passi del loft, che raffina solo la direzione di sweep: di solito non serve un `loft-n` alto una volta fissata `:resolution`. E a differenza di `shell`, `embroid` non si compone con `->` come le altre shape-fn: nel loft applica le proprie facce già pronte, quindi una `tapered` o una `twisted` aggiunte dopo vengono ignorate in silenzio. Per riposizionarla si usa l'opzione `:offset`, oppure si trasla la mesh risultante.

### Risoluzione e performance

Shell e woven-shell richiedono molta risoluzione su entrambi gli assi per rendere bene i pattern. Due numeri contano: il numero di punti del cerchio (la risoluzione della circonferenza) e il numero di passi del loft (la risoluzione longitudinale). Con i default di 64 punti e 64 passi, i pattern più semplici sono già leggibili, ma per risultati davvero nitidi servono valori nell'ordine di 512 su entrambi gli assi: `(circle 20 512)` per il profilo e `loft-n 512` per i passi. Il prezzo è un calcolo lento (diversi secondi) e una mesh con molti triangoli. Durante la prototipazione conviene usare numeri più bassi (128 o 256) per iterare velocemente, e alzare per il risultato finale.

L'impostazione globale `resolution` influenza anche il numero di passi di default del loft. Se serve un controllo esplicito, `loft-n` con il numero di passi desiderato ha sempre la precedenza.

## Raccordi sui cap

Un solido estruso ha spigoli vivi dove la sezione incontra le facce di testa (i "cap"). Nel cap. 3 abbiamo visto `fillet-shape` per arrotondare gli angoli 2D del profilo (gli spigoli lungo la direzione di estrusione). `capped` arrotonda l'altro set di spigoli: quelli dove il profilo incontra le facce di chiusura, alle due estremità.

<!-- example-source: capped-basic -->
```clojure
(register rounded-bar
  (loft (capped (rect 30 15) 3) (f 50)))
```

`capped` prende una shape e un raggio, e produce una shape-fn che raccorda le estremità: il profilo parte da zero, cresce fino alla dimensione piena nel giro dei primi 3 mm, resta costante lungo il percorso, e torna a zero negli ultimi 3 mm. La transizione segue un profilo a quarto di cerchio, quindi il raccordo è morbido. Il risultato è un parallelepipedo con i bordi di testa arrotondati.

Come `shell`, `capped` è una shape-fn e richiede `loft` al posto di `extrude`.

### Fillet e chamfer sui cap

`capped` ha due modalità: raccordo (default) e smusso.

```clojure
(loft (capped shape 3) (f 50))                ; raccordo (quarto di cerchio)
(loft (capped shape 3 :mode :chamfer) (f 50)) ; smusso (transizione lineare)
```

Con `:chamfer` la transizione è un taglio rettilineo a 45° invece di una curva.

### Controllare le estremità

Si può scegliere di raccordare solo un'estremità:

```clojure
(loft (capped shape 3 :start false) (f 50))   ; solo la fine
(loft (capped shape 3 :end false) (f 50))     ; solo l'inizio
```

### Comporre con fillet-shape e altre shape-fn

`fillet-shape` arrotonda gli angoli 2D del profilo. `capped` arrotonda i bordi 3D di testa. Le due operazioni sono ortogonali e si compongono:

<!-- example-source: capped-fillet-composed -->
```clojure
(register rounded-box
  (loft (-> (rect 40 20) (fillet-shape 5) (capped 3)) (f 50)))
```

Un parallelepipedo con gli angoli 2D arrotondati (raggio 5) e i bordi di testa raccordati (raggio 3). Il risultato è un oggetto con tutti gli spigoli morbidi, ottenuto componendo due operazioni indipendenti.

`capped` si compone anche con `tapered`, `twisted`, e qualsiasi altra shape-fn:

<!-- example-source: capped-tapered-foot -->
```clojure
(def foot
  (loft (-> (circle 15) (tapered :to 0.3) (capped -10)) (f 40)))

(register base
  (mesh-union
    (attach (box 30) (f (+ 40 15)))
    foot)
  )
```

Capped è un utile strumento, se usato con valori negativi (-10 in questo caso) per raccordare il risultato di un loft ad altri oggetti. Nell'esempio sopra il piede si allarga per incontrare il cubo.

La frazione del percorso dedicata alla transizione viene calcolata automaticamente da `capped` come rapporto fra il raggio e la lunghezza del percorso. Si può forzare con `:fraction` se il risultato automatico non va bene.
Non è possibile avere due valori differenti per i due cap (di conseguenza non posso averne uno che si rastrema ,valori positivi, e uno che si allarga ,valori negativi). Per ottenere quell'effetto bisogna usare due loft separate.

## Comporre shape-fn

Le shape-fn si compongono con il threading `->`. Ogni shape-fn della catena avvolge la precedente: quando il loft valuta la catena a un dato `t`, l'esecuzione procede dall'interno verso l'esterno.

<!-- example-source: shapefn-compose -->
```clojure
;; Colonna scanalata che si rastrema e si torce
(register column
  (loft
    (-> (circle 15 128)
        (fluted :flutes 20 :depth 1.5)
        (tapered :to 0.25)
        (twisted :angle 45))
    (f 80)))
```

La catena si legge dall'alto in basso: cerchio, poi scanalature, poi rastremazione, poi torsione. L'ordine conta. `(-> shape (twisted ...) (fluted ...))` produce un risultato diverso da `(-> shape (fluted ...) (twisted ...))`: nel primo caso le scanalature sono dritte su un profilo già ruotato; nel secondo le scanalature ruotano insieme al profilo.

### Il vincolo di shell

`shell` e `woven-shell` devono essere **ultime** nella catena di composizione. Il motivo è tecnico: `shell` annota la shape con metadata (`:shell-mode`) che il loft legge per generare i doppi anelli (esterno + interno). Le altre shape-fn non conoscono questo metadata e lo perderebbero.

```clojure
;; tapered è l'eccezione: può stare dopo shell
(-> (circle 20 64)
    (fluted :flutes 12 :depth 1)
    (shell :thickness 2 :style :voronoi :cells 8 :rows 6)
    (tapered :to 0.6))
```

L'eccezione è `tapered`: `tapered` *dopo* `shell` funziona perché `tapered` preserva il metadata di shell. In pratica, la regola si riduce a: metti `shell` o `woven-shell` dopo le shape-fn che modificano la geometria del profilo (`fluted`, `twisted`, `noisy`, ecc.), ma `tapered` può stare sia prima sia dopo.

### Il caso di embroid

`embroid` (visto sopra) è una shape-fn particolare: nel loft non trasforma il profilo passo per passo come le altre, ma stampa direttamente le proprie facce, già costruite a partire dal path. Per questo non si compone con `->`. Qualsiasi shape-fn messa dopo `embroid` nella catena viene ignorata in silenzio, perché non c'è un profilo intermedio su cui agire. È un'eccezione più forte di quella di shell: shell deve solo stare in fondo, embroid non entra proprio in catena. Per riposizionare il risultato si usa l'opzione `:offset` di `embroid`, oppure si trasla la mesh dopo il loft.

### Risoluzione

La composizione di shape-fn non cambia quanti passi fa il loft. Conta però quando il profilo varia in modo **non lineare lungo il percorso**: una torsione (`twisted`), un'ondulazione, o un path curvo. Una variazione lineare (`tapered`) o invariante lungo `t` (le scanalature dritte di `fluted`) viene riprodotta esatta anche con pochi passi, quindi alzare la risoluzione non cambia nulla.

Qui le due shape-fn da sole non avrebbero bisogno di risoluzione: ma `twisted` fa spiralare le scanalature, e la spirale è una curva nella direzione del percorso che il loft approssima con corde dritte tra un anello e il successivo. Con pochi passi appare spezzata; con molti, liscia. Se il risultato appare sfaccettato nella direzione del percorso, alza il numero di passi con `loft-n`:

<!-- example-source: shapefn-compose-resolution -->
```clojure
;; pochi passi: la spirale delle scanalature si vede spezzata
(register coarse
  (loft-n 12
    (-> (circle 15 64) (fluted :flutes 12 :depth 1.5) (twisted :angle 180))
    (f 80)))
(u -50)
;; molti passi: la spirale è liscia
(register smooth
  (loft-n 128
    (-> (circle 15 64) (fluted :flutes 12 :depth 1.5) (twisted :angle 180))
    (f 80)))
```

`resolution` (il comando globale) influenza anche il numero di passi di default di `loft`. Per un controllo esplicito, `loft-n` con il numero desiderato ha sempre la precedenza. `resolution` influenza anche il numero di punti del cerchio (se non specificato esplicitamente) e le curve nel percorso.


## Thickness-fn: controllare lo spessore delle pareti

Nella sezione su `shell` abbiamo usato gli stili built-in (`:voronoi`, `:lattice`, `:weave`, `:checkerboard`). Ognuno di quegli stili è una thickness-fn preconfezionata: una funzione che dice al loft quanto deve essere spessa la parete in ogni punto.

La thickness-fn ha la firma `(fn [angle t] -> 0..1)`. I due argomenti sono le coordinate di un punto sulla superficie del guscio:

- `angle`: posizione angolare sul profilo, in radianti (da 0 a 2π per un profilo chiuso). Dice *dove* sei attorno alla sezione trasversale.
- `t`: posizione lungo il percorso (da 0 a 1). Dice *dove* sei lungo l'estrusione.

Il valore restituito è un coefficiente di spessore: 1 significa parete piena (spessore = `:thickness`), 0 significa nessuna parete (apertura). Valori intermedi producono pareti più sottili. Valori sotto la soglia `:threshold` (default 0.05) vengono arrotondati a 0 per evitare triangoli degenerati.

<!-- example-source: shapefn-thickness-fn-basic -->
```clojure
;; Pattern a strisce orizzontali
(register striped
  (loft
    (shell (circle 20 64) :thickness 2
      :fn (fn [a t] (if (> (mod (* t 12) 1) 0.5) 1 0)))
    (f 60)))
```

La funzione alterna tra 1 (parete) e 0 (apertura) in base a `t`. Il risultato è un cilindro cavo con fasce orizzontali alternate di parete e aria.

### Progettare un pattern

Ragionare in coordinate `(angle, t)` è ragionare su un rettangolo che viene poi arrotolato attorno al profilo. `angle` è l'asse orizzontale del rettangolo, `t` è l'asse verticale. Il pattern che disegni nel rettangolo viene mappato sulla superficie del solido.

<!-- example-source: shapefn-thickness-fn-diagonal -->
```clojure
;; Pattern diagonale (strisce a 45°)
(register diagonal
  (loft
    (shell (circle 20 64) :thickness 2
      :fn (fn [a t]
        (if (> (mod (+ (* a 3) (* t 20)) 1) 0.4) 1 0)))
    (f 60)))
```

Sommando `angle` e `t` con pesi diversi si ottengono strisce diagonali. Il rapporto tra i pesi controlla l'angolo delle strisce; la soglia (0.4) controlla il rapporto tra pieno e vuoto.

<!-- example-source: shapefn-thickness-fn-sin -->
```clojure
;; Variazione sinusoidale morbida
(register wavy-shell
  (loft
    (shell (circle 20 64) :thickness 3
      :fn (fn [a t]
        (max 0.1 (sin (+ (* a 8) (* t PI 6))))))
    (f 60)))
```

Usando `sin` invece di soglie nette, lo spessore varia in modo continuo. Con `max 0.1` come pavimento la parete non scompare mai del tutto: alterna zone piene e zone più sottili senza creare buchi. Abbassare il pavimento a 0 produrrebbe aperture vere ma frammenterebbe la struttura in tante isole staccate, una per ogni cresta della sinusoide.

### woven-shell custom

`woven-shell` ha un modello simile ma più ricco. La funzione custom restituisce una mappa con due chiavi: `:thickness` (come per `shell`) e `:offset` (spostamento radiale del centro della parete). Lo spostamento radiale è ciò che crea il vero effetto di intreccio, con fili che passano uno davanti all'altro.

<!-- example-source: shapefn-woven-shell-custom -->
```clojure
(register custom-weave
  (loft
    (woven-shell (circle 20 128) :thickness 3
      :fn (fn [a t]
        {:thickness (if (> (mod (* a 4) 1) 0.3) 0.8 0)
         :offset (* 1.5 (sin (* a 8)) (cos (* t PI 4)))}))
    (f 60)))
```

`:thickness` controlla dove c'è materiale; `:offset` controlla dove quel materiale si trova rispetto alla superficie mediana. Un offset positivo sposta la parete verso l'esterno, negativo verso l'interno.

### Combinare pattern: un portapenne

Due gusci con pattern diversi possono coesistere nello stesso oggetto. Sovrapponendo l'intreccio precedente alle fasce orizzontali di `striped`, e aggiungendo una base, si ottiene un portapenne stampabile:

<!-- example-source: shapefn-uvase -->
```clojure
(def part-a
  (merge-vertices
    (loft
      (woven-shell (circle 20 128) :thickness 3
        :fn (fn [a t]
              {:thickness (if (> (mod (* a 4) 1) 0.3) 0.8 0)
               :offset (* 1.5 (sin (* a 8)) (cos (* t PI 4)))}))
      (f 60))))

(def part-b
  (merge-vertices
    (loft
      (shell (circle 20 64) :thickness 2
        :fn (fn [a t] (if (> (mod (* t 12) 1) 0.5) 1 0)))
      (f 60))))

(register uvase (mesh-union part-a part-b (cyl 20 3)))
```

`merge-vertices` è il dettaglio tecnico che rende possibile l'unione. Le thickness-fn che generano aperture (le `:fn` qui sopra restituiscono 0 in alcune zone) producono vertici duplicati ai bordi dei fori. `manifold` rifiuta le operazioni booleane su mesh con duplicati, e `mesh-union` fallirebbe silenziosamente. `merge-vertices` fonde i duplicati spazialmente coincidenti e rende la mesh utilizzabile.

### Rapporto con gli stili built-in

Gli stili built-in di `shell` (`:voronoi`, `:lattice`, `:checkerboard`, `:weave`) non sono altro che thickness-fn preconfezionate. Quando scrivi `(shell shape :thickness 2 :style :lattice :openings 8 :rows 12)`, internamente `shell` costruisce una thickness-fn che produce un pattern a griglia con quelle dimensioni.

Passare `:fn` sovrascrive lo stile: le due opzioni sono mutuamente esclusive. Se specifichi entrambe, `:fn` vince.

Per pattern che sono varianti di uno stile built-in (una griglia con aperture più larghe al centro, un voronoi che sfuma verso il basso), spesso è più semplice partire dalla thickness-fn custom che tentare di parametrizzare lo stile built-in.


## Scrivere una shape-fn propria

Le shape-fn built-in coprono i casi più comuni. Quando servono variazioni che nessuna built-in esprime, si può scrivere una shape-fn custom con la funzione `shape-fn`.

<!-- example-source: shapefn-custom-basic -->
```clojure
;; Pulsazione: il profilo si gonfia e si restringe
(register pulse
  (loft-n 32
    (shape-fn (circle 20)
      (fn [shape t]
        (scale-shape shape (+ 0.6 (* 0.4 (sin (* t PI)))))))
    (f 50)))
```

`shape-fn` prende due argomenti: una shape base e una funzione `(fn [shape t] -> shape)`. La funzione riceve la shape base (o, se la base è a sua volta una shape-fn, la shape valutata a quel `t`) e il valore `t`, e restituisce la shape trasformata.

Siccome la base può essere una shape-fn, le shape-fn custom si compongono con le built-in:

<!-- example-source: shapefn-custom-compose -->
```clojure
;; Prima rastremazione, poi pulsazione
(register tapered-pulse
  (loft-n 32
    (shape-fn (tapered (circle 20) :to 0.5)
      (fn [shape t]
        (scale-shape shape (+ 0.8 (* 0.2 (sin (* t PI 4)))))))
    (f 50)))
```

Qui la base è `(tapered (circle 20) :to 0.5)`, che è già una shape-fn. A ogni `t`, il loft prima valuta `tapered` (che restituisce un cerchio più piccolo), poi passa il risultato alla funzione custom (che lo fa pulsare). Il risultato è un cono che pulsa.

All'interno della funzione di trasformazione si può usare qualsiasi operazione su shape: `scale-shape`, `rotate-shape`, `translate-shape`, `resample-shape`, e le funzioni di utilità come `displace-radial`. La funzione deve restituire una shape con lo stesso numero di punti dell'input (il vincolo descritto nella 6.1: il loft collega vertici corrispondenti tra shape successive, e non può farlo se il conteggio cambia da un passo all'altro).

### Il predicato shape-fn?

`shape-fn?` verifica se un valore è una shape-fn. Utile in codice polimorfico che deve gestire sia shape statiche sia shape-fn:

```clojure
(shape-fn? (circle 20))                    ;; false
(shape-fn? (tapered (circle 20) :to 0.5))  ;; true
```
