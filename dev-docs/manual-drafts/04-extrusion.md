<!--
=========================================================================
NOTE INTERNE PER L'AUTORE — NON DESTINATE AL LETTORE FINALE

Struttura del capitolo:
4.1  Il concetto (extrude base)
4.2  Percorsi curvi e composti (th, arc-h, arc-v, bezier-to)
4.3  Quick path ed estrusione chiusa (qp, extrude-closed)
4.4  Transizioni di forma e modalità giunzione (joint-mode)
4.5  Risoluzione e dettaglio (resolution)
4.6  Shell e woven shell (loft + shell shape-fn)
4.7  Raccordi sui cap (capped)
4.8  Revolve (asse = up della tartaruga, verificato 2026-05-20)
4.9  Chaining: extrude+, revolve+, transform->

Decisioni:
- revolve aggiunto al cap. 4 (non era nel piano originale)
- chaining aggiunto al cap. 4
- asse revolve = up (Spec corretta il 2026-05-20, era sbagliata)
- loft+ non esiste ancora, segnato in Roadmap §1.5
=========================================================================
-->

# Estrusione

## Il concetto

Nel cap. 3 abbiamo costruito shape: profili 2D che vivono nel piano della tartaruga. `extrude` li trasforma in solidi trascinandoli lungo un percorso.

<!-- example-source: extrude-tube -->
```clojure
(register tube (extrude (circle 15) (f 30)))
```

Il primo argomento è la shape (un cerchio di raggio 15). Tutto quello che segue sono comandi tartaruga che definiscono il percorso. `(f 30)` significa "avanti di 30": il cerchio viene trascinato in avanti, e il volume che attraversa diventa un cilindro.

Il risultato è identico a `(cyl 15 30)`. La differenza è che con `extrude` la sezione può essere qualsiasi shape, non solo un cerchio:

<!-- example-source: extrude-l-bar -->
```clojure
(register l-bar
  (extrude
    (shape (f 10) (th 90)
           (f 5)  (th 90)
           (f 5)  (th -90)
           (f 5)  (th 90)
           (f 5))
    (f 40)))
```

Un profilato a L lungo 40. La shape è il profilo a L del cap. 3, il percorso è una linea retta.

`extrude` non modifica lo stato della tartaruga: dopo la chiamata la tartaruga è esattamente dove era prima. La mesh nasce nella posizione corrente della tartaruga, con la sezione nel piano destra-alto e il percorso che parte in avanti. È la stessa convenzione delle primitive: `(box 10 20 30)` produce lo stesso solido di `(extrude (rect 10 20) (f 30))`, a parte il punto di ancoraggio (la box è centrata, l'estrusione parte dalla base).

Il percorso può anche essere un path registrato in precedenza:

<!-- example-source: extrude-recorded-path -->
```clojure
(def my-path (path (f 20) (th 45) (f 20)))
(register bent-tube (extrude (circle 10) my-path))
```

`path` registra una sequenza di comandi tartaruga come dato riutilizzabile. Lo vedremo in dettaglio nel cap. 5. Per ora il punto è che un percorso può essere definito una volta e usato in più estrusioni.

## Percorsi curvi e composti

Il percorso di un'estrusione non deve essere necessariamente rettilineo. Qualsiasi comando tartaruga funziona dentro `extrude`: rotazioni per cambiare direzione, archi per curve morbide, bezier per traiettorie libere.

### Cambi di direzione

<!-- example-source: extrude-bent -->
```clojure
(register bent-tube
  (extrude (circle 10)
    (f 20)
    (th 45)
    (f 20)))
```

`(th 45)` gira la tartaruga di 45° a metà percorso. Il tubo parte dritto, piega, e continua dritto nella nuova direzione. La piega è smussata a bisello (la modalità giunzione di default è `:tapered`). Vedremo nella 4.4 come cambiarla.

Si possono concatenare più comandi per percorsi articolati:

<!-- example-source: extrude-zigzag -->
```clojure
(register zigzag
  (extrude (rect 8 4)
    (f 15) (th 60)
    (f 15) (th -120)
    (f 15) (th 60)
    (f 15)))
```

Un profilo rettangolare estruso a zigzag. Ogni `th` cambia direzione, ogni `f` aggiunge un tratto rettilineo.

### Archi

`arc-h` e `arc-v` producono curve morbide: archi circolari orizzontali o verticali.

<!-- example-source: extrude-arc -->
```clojure
(register u-tube
  (extrude (circle 5)
    (f 20)
    (arc-h 15 180)
    (f 20)))
```

Un tubo a U: tratto dritto, semicerchio orizzontale di raggio 15, tratto dritto. `arc-h` genera internamente una sequenza di piccoli segmenti e rotazioni, quindi la curva è liscia (la risoluzione di default è 64 segmenti per arco; si può abbassare con `:steps` o con `resolution` se serve meno dettaglio).

`arc-v` funziona allo stesso modo ma curva nel piano verticale (attorno all'asse destra della tartaruga). Combinando i due si possono costruire tubi che salgono, scendono, e girano nello spazio 3D.

<!-- example-source: extrude-s-curve -->
```clojure
(register s-pipe
  (extrude (circle 6)
    (arc-h 20 90)
    (arc-h 20 -90)))
```

Una curva a S: due archi consecutivi con angolo opposto.

### Bezier

Per traiettorie che non sono archi di cerchio, ci sono le curve di Bezier. `bezier-to` muove la tartaruga verso un punto target con una curva morbida:

<!-- example-source: extrude-bezier -->
```clojure
(register curved-bar
  (extrude (rect 6 3)
    (bezier-to [30 0 20])))
```

Un profilato rettangolare che sale dolcemente da dove si trova la tartaruga verso il punto `[30 0 20]` (30 in avanti e 20 in alto rispetto all'origine, se la tartaruga è nella posa di default). I punti di controllo vengono generati automaticamente in modo tangente alla direzione corrente della tartaruga.

Per curve più controllate, `bezier-to` accetta uno o due punti di controllo espliciti. E `bezier-as` prende un path esistente e lo percorre come curva di Bezier morbida, utile per smussare un percorso a segmenti. I dettagli sono nel cap. 5 (Path) e nella Reference.

### Combinare tutto

Segmenti rettilinei, archi e bezier si mescolano liberamente nello stesso percorso. L'estrusione li concatena senza soluzione di continuità:

<!-- example-source: extrude-mixed -->
```clojure
(register handle
  (extrude (circle 4)
    (f 30)
    (arc-h 10 90)
    (f 20)
    (arc-h 10 90)
    (f 30)))
```

Un manico: tratto dritto, curva di 90°, tratto dritto, altra curva di 90°, tratto dritto. La sezione circolare rimane costante per tutto il percorso.

## Quick path ed estrusione chiusa

### Quick path

Per percorsi rettilinei con cambi di direzione, scrivere `(f 20) (th 45) (f 30) (th -60) (f 10)` diventa verboso. `quick-path` (alias `qp`) compatta la notazione: numeri e angoli si alternano senza nomi di comando.

<!-- example-source: extrude-quick-path -->
```clojure
(register bracket
  (extrude (rect 8 4)
    (qp 20 90 30 -45 10)))
```

`(qp 20 90 30 -45 10)` equivale a `(f 20) (th 90) (f 30) (th -45) (f 10)`: i valori dispari sono distanze, i pari sono angoli. Comodo per prototipi veloci e percorsi semplici; per percorsi complessi con archi e bezier, la forma estesa resta più leggibile.

### Estrusione chiusa

`extrude` produce un solido con due tappi (la faccia iniziale e quella finale). `extrude-closed` invece collega l'ultimo anello al primo, producendo un solido senza tappi, come un toro.

<!-- example-source: extrude-closed-torus -->
```clojure
(def square-loop (path (dotimes [_ 4] (f 20) (th 90))))

(register torus (extrude-closed (circle 5) square-loop))
```

Il percorso è un quadrato (quattro lati da 20, quattro curve da 90°) che torna al punto di partenza. `extrude-closed` estrude il cerchio lungo quel quadrato e chiude la mesh collegando la fine con l'inizio. Il risultato è un toro a sezione circolare con una traiettoria quadrata.

Perché `extrude-closed` funzioni, il percorso deve tornare ragionevolmente vicino al punto di partenza. Non serve la precisione perfetta (piccoli errori numerici vengono tollerati), ma un percorso che finisce lontano da dove è partito produce una mesh con un salto visibile alla giunzione.

<!-- example-source: extrude-closed-round -->
```clojure
(register ring
  (extrude-closed (rect 3 6)
    (path (arc-h 25 360))))
```

Un anello a sezione rettangolare: il percorso è un cerchio completo (arco di 360°), la sezione è un rettangolo. Con `extrude` avremmo un tubo aperto; con `extrude-closed` i due estremi si saldano.

## Transizioni di forma e modalità giunzione

Quando il percorso cambia direzione, l'estrusione deve decidere come trattare l'angolo. La modalità giunzione controlla la geometria in quei punti.

<!-- example-source: joint-tapered -->
```clojure
(register tapered-bend
  (extrude (rect 12 6)
    (f 20) (th 90) (f 20)))
```

Con la modalità di default (`:tapered`), la giunzione è smussata a bisello: un taglio obliquo che raccorda i due tratti. Per la maggior parte delle situazioni è il compromesso giusto: visivamente pulito, geometria contenuta.

`joint-mode` cambia la modalità per tutte le estrusioni successive:

<!-- example-source: joint-round -->
```clojure
(joint-mode :round)
(register round-bend
  (extrude (circle 8)
    (f 20) (th 90) (f 20)))
```

Con `:round` la giunzione viene raccordata con segmenti intermedi che seguono la curva. L'angolo diventa morbido, come se il tubo piegasse invece di spezzarsi.

<!-- example-source: joint-flat -->
```clojure
(joint-mode :flat)
(register flat-bend
  (extrude (circle 8)
    (f 20) (th 90) (f 20)))
```

Con `:flat` la piega è uno spigolo vivo: le due sezioni si incontrano ad angolo e la giunzione è un taglio netto. Utile quando lo spigolo è intenzionale (scatolati, telai, oggetti tecnici).

Le tre modalità:

`joint-mode :tapered` (default) produce un raccordo lineare (bisello). Buon compromesso tra qualità visiva e quantità di geometria.

`joint-mode :round` inserisce anelli intermedi che curvano la sezione attorno all'angolo. La risoluzione della curva dipende dall'impostazione `resolution`. Produce le giunzioni più morbide, ma con più geometria.

`joint-mode :flat` produce spigoli vivi. La geometria è minimale. La giunzione taglia il profilo lungo il piano bisettore dell'angolo.

`joint-mode` modifica lo stato della tartaruga: una volta impostato, vale per tutte le estrusioni successive fino al prossimo cambio. Se vuoi una modalità diversa per una sola estrusione, usa un `turtle` scope:

```clojure
(turtle
  (joint-mode :round)
  (register this-one (extrude (circle 8) (f 20) (th 90) (f 20))))
;; fuori dal turtle scope, la joint-mode è di nuovo quella di prima
```

La macro `turtle` crea uno scope isolato: tutto quello che succede dentro (cambi di stato, movimenti, impostazioni) non influenza la tartaruga esterna. La vedremo in dettaglio nel cap. 5; qui basta sapere che è il modo per limitare un'impostazione come `joint-mode` a una singola operazione.

## Risoluzione e dettaglio

Le curve nel percorso (archi, bezier, giunzioni `:round`) vengono approssimate con segmenti rettilinei. Quanti segmenti determinano quanto la curva appare liscia. L'impostazione `resolution` controlla questo globalmente.

<!-- example-source: resolution-low -->
```clojure
(resolution :n 8)
(register rough
  (extrude (circle 15)
    (arc-h 20 180)))
```

Con 8 segmenti per arco il tubo è visibilmente sfaccettato, sia nella sezione circolare sia nella curva del percorso.

<!-- example-source: resolution-high -->
```clojure
(resolution :n 64)
(register smooth
  (extrude (circle 15)
    (arc-h 20 180)))
```

Con 64 segmenti le curve sono morbide. Il prezzo è più geometria: più triangoli nella mesh, più tempo di calcolo per le booleane.

`resolution` ha tre forme:

`(resolution :n 32)` fissa il numero di segmenti. È il modo più prevedibile: sai quanti segmenti avrai. Il default è 64.

`(resolution :a 5)` fissa l'angolo massimo per segmento (in gradi). Curve strette ricevono più segmenti di curve larghe: la risoluzione si adatta alla curvatura.

`(resolution :s 0.5)` fissa la lunghezza massima di ogni segmento. Archi grandi ricevono più segmenti di archi piccoli: la risoluzione si adatta alla dimensione.

`resolution` influenza tutto: archi, bezier, cerchi, sfere, cilindri, coni, giunzioni `:round`, e la rivoluzione (`revolve`). Come `joint-mode`, modifica lo stato della tartaruga e vale per tutto quello che viene dopo. Per limitarla a una singola operazione, usa un `turtle` scope.

Per casi puntuali, molte funzioni accettano un override diretto senza cambiare la risoluzione globale:

```clojure
(arc-h 10 90 :steps 32)      ; solo questo arco a 32 segmenti
(circle 15 64)                ; solo questo cerchio a 64 segmenti
```

Una regola pratica: il default (`:n 64`) produce curve già morbide per la maggior parte degli usi. Per la prototipazione veloce si può abbassare a `:n 16` o `:n 32` per ridurre la geometria e accelerare le booleane. Non serve cambiare la risoluzione globale se solo alcune curve richiedono un trattamento diverso: gli override puntuali come `(circle 15 128)` o `(arc-h 10 90 :steps 32)` sono fatti apposta.

## Shell e woven shell

Fin qui abbiamo estruso profili pieni. Ma molti oggetti reali sono cavi: vasi, lampade, contenitori, paralumi. `shell` è una shape-fn che trasforma un profilo pieno in un guscio cavo con pareti di spessore controllato, con la possibilità di aprire finestre decorative nelle pareti.

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

<!-- example-source: shell-voronoi -->
```clojure
(register lamp
  (loft-n 512 (shell (circle 20 512) :thickness 2 :style :voronoi :cells 8 :rows 6)
    (f 50)))
```

`:voronoi` distribuisce celle irregolari sulle pareti. I bordi delle celle sono materiale, l'interno è vuoto. `:cells` controlla quante celle per anello, `:rows` quanti anelli lungo il percorso.

<!-- example-source: shell-lattice -->
```clojure
(register vase
  (loft-n 512 (shell (circle 15 512) :invert? true :thickness 2 :style :lattice :openings 4 :rows 6)
    (f 60)))
```

`:lattice` produce un pattern a mattoni: file di blob solidi sfasati lungo la circonferenza. `:openings` controlla il numero di mattoni per fila, `:rows` il numero di righe. Con `:invert? true` il pattern si inverte: i mattoni diventano aperture in un guscio continuo. Senza `:invert?`, il risultato sono i mattoni staccati (utile come texture a rilievo, meno come guscio).

Gli altri stili disponibili sono `:checkerboard` (scacchiera) e `:weave` (intreccio). Ognuno ha le sue opzioni specifiche; la Reference le documenta tutte. `:invert? true` funziona con tutti gli stili, comprese le thickness-fn custom passate con `:fn`.

### Comporre con altre shape-fn

`shell` è una shape-fn come `tapered` o `twisted`, quindi si compone con `->`:

<!-- example-source: shell-composed -->
```clojure
(register tapered-lamp
  (loft-n 512 (-> (circle 20 512)
                  (shell :thickness 2 :style :voronoi :cells 8 :rows 6)
                  (tapered :to 0.5))
    (f 60)))
```

Una lampada che si rastrema: `shell` rende il profilo cavo con aperture Voronoi, `tapered` lo riduce progressivamente lungo il percorso. Le due trasformazioni si applicano in sequenza a ogni anello del loft.

### Woven shell

`woven-shell` è una variante di `shell` che non si limita a variare lo spessore delle pareti: sposta anche il centro della parete radialmente, così i fili passano davanti e dietro l'uno all'altro come in un intreccio reale.

<!-- example-source: woven-shell-basic -->
```clojure
(register basket
  (loft-n 512 (woven-shell (circle 20 512) :thickness 3 :strands 8)
    (f 50)))
```

Un cesto intrecciato. I fili diagonali si incrociano con un vero sopra/sotto tridimensionale, non solo un pattern di fori. `:strands` controlla il numero di fili; `:mode :orthogonal` produce un intreccio a trama e ordito (tipo vimini) invece del pattern diagonale di default.

Come `shell`, `woven-shell` si compone con altre shape-fn:

```clojure
(register twisted-basket
  (loft-n 512 (-> (circle 20 512)
                  (woven-shell :thickness 3 :strands 6)
                  (twisted :angle 90))
    (f 50)))
```

### Risoluzione e performance

Shell e woven-shell richiedono molta risoluzione su entrambi gli assi per rendere bene i pattern. Due numeri contano: il numero di punti del cerchio (la risoluzione circumferenziale) e il numero di passi del loft (la risoluzione longitudinale). Con i default di 64 punti e 64 passi, i pattern più semplici sono già leggibili, ma per risultati davvero nitidi servono valori nell'ordine di 512 su entrambi gli assi: `(circle 20 512)` per il profilo e `loft-n 512` per i passi. Il prezzo è un calcolo lento (diversi secondi) e una mesh con molti triangoli. Durante la prototipazione conviene usare numeri più bassi (128 o 256) per iterare velocemente, e alzare per il risultato finale.

L'impostazione globale `resolution` influenza anche il numero di passi di default del loft. Se serve un controllo esplicito, `loft-n` con il numero di passi desiderato ha sempre la precedenza.

Il cap. 6 tratta le shape-fn in profondità: composizione, shape-fn custom, thickness-fn. Qui l'obiettivo era mostrare che l'estrusione cava è a portata di mano senza uscire dal flusso shape → loft.

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

Si può raccordare solo un'estremità:

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

<!-- example-source: capped-tapered-drop -->
```clojure
(register drop
  (loft (-> (circle 20) (tapered :to 0.3) (capped 10)) (f 40)))
```

Una goccia: un cerchio che si rastrema e ha le estremità raccordate.

La frazione del percorso dedicata alla transizione viene calcolata automaticamente da `capped` come rapporto fra il raggio e la lunghezza del percorso. Si può forzare con `:fraction` se il risultato automatico non va bene.

## Revolve

`revolve` ruota un profilo 2D attorno all'asse up della tartaruga, producendo un solido di rivoluzione. Dove `extrude` trascina il profilo lungo un percorso, `revolve` lo fa girare su se stesso.

<!-- example-source: revolve-bowl -->
```clojure
(def profile (path
               (bezier-as
                 (path
                   (f 20)
                   (th 60)
                   (f 30)
                   (th 120)
                   (f 2)
                   (th 60)
                   (f 20)
                   (th -30)
                   (f 10)
                   (th -20)
                   (f 20)))))

(register bowl
  (revolve
    (path-to-shape
      profile)))
```

Una ciotola. Il profilo è un path angolare smussato con `bezier-as` e convertito in shape con `path-to-shape`. `revolve` senza secondo argomento fa un giro completo (360°). La coordinata X (destra) della shape è la distanza dall'asse di rivoluzione, la coordinata Y (alto) è la posizione lungo l'asse. La rotazione avviene attorno all'asse up della tartaruga.

Con un secondo argomento si controlla l'angolo di rivoluzione:

<!-- example-source: revolve-partial -->
```clojure
(register quarter-bowl
  (revolve
    (rect 20 10) 90))
```

Un quarto di rivoluzione: il rettangolo ruota solo di 90° invece di 360°. Utile per settori, spicchi, e geometrie parziali.

### Pivot

Quando si usa `revolve` per creare una curva (un gomito di tubo, un angolo di cornice), serve controllare quale bordo del profilo sta sull'asse di rivoluzione. `:pivot` sposta il profilo in modo che un bordo specifico coincida con l'asse:

<!-- example-source: revolve-pivot -->
```clojure
(register elbow
  (revolve (circle 8) 90 :pivot :left))
```

Un gomito di tubo a 90°: il bordo sinistro del cerchio sta sull'asse, quindi la rivoluzione produce una curva. Senza `:pivot` il cerchio sarebbe centrato sull'asse e la rivoluzione produrrebbe un toro (o un disco pieno, nel caso di un giro completo).

Le direzioni di pivot sono relative al piano 2D del profilo: `:left`, `:right`, `:up`, `:down`.

### Shape-fn con revolve

Come `loft`, anche `revolve` accetta shape-fn. Il profilo può variare durante la rotazione:

<!-- example-source: revolve-tapered -->
```clojure
(register horn
  (revolve (tapered (circle 10) :to 0.5) 270))
```

Un corno: il cerchio si riduce progressivamente durante i 270° di rivoluzione. `tapered`, `twisted`, `noisy` e tutte le altre shape-fn del cap. 6 funzionano con `revolve` esattamente come con `loft`.

### Revolve vs extrude con arco

Un gomito si può fare sia con `revolve` che con `extrude` + `arc-h`. La differenza: `extrude` + arco trascina il profilo lungo una curva, quindi la sezione resta perpendicolare alla traiettoria. `revolve` ruota il profilo attorno a un asse, quindi la sezione resta perpendicolare al piano di rotazione. Per angoli piccoli i risultati sono quasi identici; per angoli grandi (90° e oltre) la geometria diverge. Come regola pratica: `revolve` con `:pivot` per gomiti e angoli architettonici, `extrude` + arco per tubi che seguono un percorso.

## Chaining: extrude+, revolve+, transform->

Immagina di costruire un telaio: un tratto dritto, una curva a 30°, un altro tratto dritto, una curva in senso opposto, un tratto finale. Con `extrude` e `revolve` normali dovresti costruire ogni segmento separatamente, posizionare la tartaruga alla fine del segmento precedente, e poi fare `mesh-union` di tutti i pezzi. È fattibile, ma verboso e fragile: se cambi un angolo, tutti i segmenti successivi devono essere riposizionati a mano.

`extrude+` e `revolve+` risolvono il problema restituendo, oltre alla mesh, anche la shape e la posa della faccia finale. Quel dato diventa l'input del segmento successivo.

### extrude+ e revolve+

<!-- example-source: chaining-manual -->
```clojure
(def profile (shape-difference (rect 40 40) (rect 30 30)))

(def seg1 (extrude+ profile (f 20)))

(def corner (turtle :pose (:pose (:end-face seg1))
              (revolve+ (:shape (:end-face seg1)) 30 :pivot :left)))

(def seg2 (turtle :pose (:pose (:end-face corner))
            (extrude+ (:shape (:end-face corner)) (f 30))))

(register frame
  (mesh-union (:mesh seg1) (:mesh corner) (:mesh seg2)))
```

`extrude+` restituisce una mappa `{:mesh ... :end-face {:shape ... :pose ...}}`. La `:mesh` è il solido, la `:end-face` è il profilo e la posa alla fine del segmento. Il segmento successivo usa `turtle :pose` per posizionarsi alla posa della faccia finale, e da lì estrude o rivolta. Alla fine, `mesh-union` fonde tutto.

Il pattern funziona ma è ripetitivo: ogni segmento deve estrarre `:end-face`, aprire un `turtle` scope, e passare la shape. `transform->` automatizza tutto.

### transform->

<!-- example-source: chaining-transform -->
```clojure
(register frame
  (transform-> (shape-difference (rect 40 40) (rect 30 30))
    (extrude+ (f 20))
    (revolve+ 30 :pivot :left)
    (extrude+ (f 30))
    (revolve+ -30 :pivot :right)
    (extrude+ (f 20))))
```

Stesso telaio, una sola espressione. `transform->` prende una shape iniziale e una sequenza di passi. Ad ogni passo, la shape e la posa vengono passate automaticamente dall'end-face del passo precedente. Tutte le mesh prodotte vengono fuse con `mesh-union`. Il risultato è un unico solido.

Dentro `transform->`, le operazioni non prendono la shape come argomento (viene iniettata automaticamente). `(extrude+ (f 20))` riceve la shape corrente e la estrude in avanti di 20. `(revolve+ 30 :pivot :left)` riceve la shape corrente e la rivolta di 30° con il bordo sinistro sull'asse.

### Continuare da un pezzo esistente

`transform->` accetta anche un end-face come primo argomento, non solo una shape. Questo permette di continuare una catena da un segmento costruito separatamente:

```clojure
(def base (extrude+ my-profile (f 10)))

(register result
  (mesh-union (:mesh base)
    (transform-> (:end-face base)
      (revolve+ 45 :pivot :left)
      (extrude+ (f 30)))))
```

Il primo segmento è costruito a parte; `transform->` riparte dalla sua faccia finale e aggiunge i segmenti successivi.

### Quando usare il chaining

Il chaining è lo strumento giusto per geometrie che si sviluppano come una sequenza di segmenti collegati: telai, tubi piegati, cornici, guide. Il profilo resta costante (o varia in modo controllato) e ogni segmento parte esattamente dove finisce il precedente, senza gap né sovrapposizioni.

Per assemblaggi dove i pezzi non sono collegati in sequenza ma disposti liberamente nello spazio, `attach` e il sistema di scheletri del cap. 8 sono più appropriati.

Una nota: al momento `loft+` (la variante chaining di `loft`) non è ancora implementata. Per concatenare un segmento con profilo variabile bisogna valutare manualmente la shape-fn a `t=1` e riposizionare la tartaruga. L'implementazione di `loft+` è in Roadmap (§1.5).
