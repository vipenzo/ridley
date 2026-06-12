<!--
=========================================================================
NOTE INTERNE PER L'AUTORE — NON DESTINATE AL LETTORE FINALE

Struttura del capitolo:
4.1  Il concetto (extrude base)
4.2  Percorsi curvi e composti (th, arc-h, arc-v, bezier-to)
4.3  Quick path ed estrusione chiusa (qp, extrude-closed)
4.4  Transizioni di forma e modalità giunzione (joint-mode)
4.5  Risoluzione e dettaglio (resolution)
4.6  Oltre l'estrusione piena (sezione-ponte verso il cap. 6)
4.7  Revolve (asse = up della tartaruga, verificato 2026-05-20)
4.8  Chaining: extrude+, revolve+, transform->

Decisioni:
- revolve aggiunto al cap. 4 (non era nel piano originale)
- chaining aggiunto al cap. 4
- asse revolve = up (Spec corretta il 2026-05-20, era sbagliata)
- loft+ non esiste ancora, segnato in Roadmap §1.5
- embroid aggiunto al 4.6 come complemento di shell (2026-06-06); shell/woven-shell/capped/embroid spostati al cap. 6 il 2026-06-10 (brief dev-docs/brief-shapefn-move.md)
=========================================================================
-->

# Estrusione

<!-- level: base -->

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

Per curve più controllate, `bezier-to` accetta uno o due punti di controllo espliciti. E `bezier-as` prende un path esistente e lo percorre come curva di Bezier morbida, utile per smussare un percorso a segmenti. La casa delle curve è il cap. 11: punti di controllo espliciti, coordinate locali, curve verso i marcatori, e il disegno interattivo con `edit-bezier`.

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

## Oltre l'estrusione piena

Fin qui abbiamo estruso profili pieni e costanti. Ma molti oggetti reali sono cavi (vasi, lampade, contenitori) o cambiano sezione lungo il percorso: si rastremano, si torcono, si gonfiano. In Ridley questo è il territorio di `loft` e delle shape-fn, funzioni che descrivono come il profilo varia da un capo all'altro del percorso. Un assaggio:

<!-- example-source: beyond-solid-extrusion -->
```clojure
(register cup
  (loft (shell (circle 20 64) :thickness 2 :style :solid :cap-top 2)
    (f 40)))
```

Dove prima scrivevi `(extrude shape ...)`, qui scrivi `(loft (shell shape ...) ...)`: il profilo passa da pieno a guscio, e il risultato è un bicchiere a pareti sottili (spessore 2) con un'estremità chiusa. Per convenzione chiamiamo **top** il punto di **arrivo** dell'estrusione — qui in alto, dopo `(f 40)` — e **bottom** quello di **partenza**: `:cap-top 2` chiude quindi l'estremità lontana con un disco pieno di spessore 2, lasciando aperta quella da cui parte la tartaruga. (Usiamo `:cap-top` invece di `:cap-bottom` solo perché, con il viewport al reset, la cappa chiusa resta in vista.) Le shape-fn non si fermano qui: gusci con aperture decorative, intrecci, rastremazioni, torsioni, superfici rugose, e qualsiasi combinazione di queste. Il cap. 6 è la loro casa: puoi leggerlo subito dopo questo capitolo, o tornarci quando un progetto lo richiede.

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

Un dettaglio può sorprendere: `rect 20 10` è centrato sull'origine, quindi va da X=−10 a X=+10, eppure la sezione che vedi ruotare è quadrata (10×10), non 20×10. Il motivo è che la X del profilo è la distanza dall'asse, e `revolve` **scarta la parte con X negativa**: del rettangolo resta solo la metà destra (larga 10), che ruota fino all'altezza 10. Per sfruttare l'intera larghezza del profilo lo si sposta tutto da un lato dell'asse — è il compito di `:pivot`, qui sotto. (Il clipping vale per le shape piene; una shape-fn che attraversa l'asse non può essere tagliata ring per ring, quindi viene traslata in automatico — un `:pivot :left` implicito.)

### Pivot

Quando si usa `revolve` per creare una curva (un gomito di tubo, un angolo di cornice), serve controllare quale bordo del profilo sta sull'asse di rivoluzione. `:pivot` sposta il profilo in modo che un bordo specifico coincida con l'asse:

<!-- example-source: revolve-pivot -->
```clojure
(register elbow
  (revolve (circle 8) 90 :pivot :left))
```

Un gomito di tubo a 90°: il bordo sinistro del cerchio sta sull'asse, quindi la rivoluzione produce una curva. Senza `:pivot` il cerchio sarebbe centrato sull'asse e la rivoluzione produrrebbe un quarto di sfera.

Le direzioni di pivot sono relative al piano 2D del profilo: `:left`, `:right`, `:up`, `:down`.

### Shape-fn con revolve

Come `loft`, anche `revolve` accetta shape-fn. Il profilo può variare durante la rotazione:

<!-- example-source: revolve-tapered -->
```clojure
(register horn
  (revolve (tapered (circle 10) :to 0.5) 270))
```

Un corno: il cerchio si riduce progressivamente durante i 270° di rivoluzione. `tapered`, `twisted`, `noisy` e tutte le altre shape-fn del cap. 6 funzionano con `revolve` esattamente come con `loft`.
Da notare che nel caso di uso di una shape-fn la shape non ruota su se stessa, ma viene imposto un :pivot implicito, ruota su un lato, per evitare auto intersezioni.

L'angolo di rotazione viene silenziosamente tagliato a 360 gradi se superiore. Valori maggiori di 360 o minori di -360 si comportano come 360/-360.

<!-- example-source: i-like-this -->
```clojure
(resolution :n 512)
(register i-like-this
  (revolve (twisted (rect 2 10) :to 0.5) 600))
  
```


### Revolve vs extrude con arco

Un gomito si può fare sia con `revolve` che con `extrude` + `arc-h`. La differenza: `extrude` + arco trascina il profilo lungo una curva, quindi la sezione resta perpendicolare alla traiettoria. `revolve` ruota il profilo attorno a un asse, quindi la sezione resta perpendicolare al piano di rotazione. Per angoli piccoli i risultati sono quasi identici; per angoli grandi (90° e oltre) la geometria diverge. Come regola pratica: `revolve` con `:pivot` per gomiti e angoli architettonici, `extrude` + arco per tubi che seguono un percorso.

## Chaining: extrude+, revolve+, transform->

<!-- level: advanced -->

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

Una sola espressione. `transform->` prende una shape iniziale e una sequenza di passi. Ad ogni passo, la shape e la posa vengono passate automaticamente dall'end-face del passo precedente. Tutte le mesh prodotte vengono fuse con `mesh-union`. Il risultato è un unico solido.

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
