# 13. Testo

<!-- level: intermediate -->

Il testo in Ridley è geometria. Non c'è un sistema di annotazioni separato: le lettere sono shape 2D che puoi estrudere, combinare con booleane, piazzare lungo un percorso, o usare come heightmap per creare rilievi. Questo capitolo copre le tre modalità: testo come forma 2D, testo come geometria 3D estrusa, e testo come displacement in rilievo.

## 13.1 Forme di testo 2D

### Text-shape

`text-shape` converte una stringa in un vettore di shape 2D, una per ogni contorno esterno trovato nel testo:

```clojure
(text-shape "Hello")                ;; font default (Roboto), size default
(text-shape "RIDLEY" :size 40)      ;; dimensione maggiore
```

<!-- example-source: text-shape-basic
(stamp (text-shape "Hello" :size 30))
-->

Una cosa che può sorprendere è *dove* il testo appare rispetto alla tartaruga, e vale la pena fissare la logica una volta per tutte, perché le tre funzioni di testo seguono convenzioni diverse ma coerenti. `text-shape` produce la shape sul piano ortogonale all'heading, ma — a differenza di `rect`, che nasce *centrato* sulla tartaruga — usa la posa della tartaruga come *rigo di scrittura*: il testo si sviluppa verso l'alto e a destra a partire da quel punto, come scriveresti su un foglio appoggiando la penna in basso a sinistra. Se ti serve il testo centrato sulla tartaruga (come una shape primitiva), passa `:center true`: la shape viene centrata su entrambi gli assi rispetto al bounding box reale dei glifi — comodo per ruotare il testo attorno al proprio centro o allinearlo a un altro pezzo.

L'esempio mostra la differenza affiancando ciascuna versione a un `rect` (che è sempre centrato sulla tartaruga). In alto, `:center true`: il testo è centrato sul rettangolo. In basso, il default: il testo parte dall'angolo in basso a sinistra e si sviluppa verso l'alto e a destra.

<!-- example-source: text-shape-center
(stamp (text-shape "Hello" :size 30 :center true))
(stamp (rect 90 30))
(u 100)
(stamp (text-shape "Hello" :size 30))
(stamp (rect 90 30))
-->

Il risultato non è una shape per carattere, ma una shape per *contorno esterno*. La maggior parte dei caratteri ha un solo contorno esterno, ma i caratteri composti ne hanno di più: `i` e `j` ne hanno due (corpo + puntino), `ä` e `ö` ne hanno tre (corpo + due punti). I buchi interni (il vuoto dentro la `o`, la `a`, la `B`) vengono attribuiti automaticamente al contorno esterno più piccolo che li contiene.

Per le shape dei singoli caratteri (senza composizione automatica dei contorni):

```clojure
(text-shapes "ABC" :size 20)        ;; vettore di shape, una per carattere
```

`char-shape` produce la shape di un singolo carattere, e accetta l'id di un font (vedi sotto):

```clojure
(char-shape "A" :roboto-mono 20)    ;; singolo carattere con font specifico
```

### Font

Il font di default è Roboto Regular. `text-shape`, `text-shapes`, `extrude-text` e `text-on-path` lo usano automaticamente senza che tu debba passare nulla.

Per usare un font diverso, ti riferisci a esso tramite il suo **id** (una keyword). Due font sono sempre disponibili in ogni versione di Ridley, web inclusa: `:roboto` e `:roboto-mono`. Li passi alle funzioni di testo con l'opzione `:font`:

<!-- example-source: text-font-builtin
(register mono-title (extrude-text "RIDLEY" :size 40 :font :roboto-mono))
-->

Un font non è un oggetto da "caricare" prima dell'uso: è una risorsa registrata, citata per id — come una mesh nel registro o un marcatore in un path. Passi la keyword dove serve, e Ridley risolve l'id internamente.

**Font custom: solo nella versione desktop.** I due built-in sono gli unici font disponibili nella versione web. Per usare font aggiuntivi serve l'app desktop, dove li registri una volta dal pannello Settings → Fonts: scegli un file (`.ttf`, `.otf`, `.woff`, `.woff2`) e gli assegni un id. Da quel momento il font è disponibile in tutti i progetti con quell'id, e la registrazione persiste fra sessioni (come le librerie). Nel codice lo usi come i built-in:

```clojure
(extrude-text "RIDLEY" :size 40 :font :il-mio-font)
```

Se passi un id non registrato, l'errore è immediato e chiaro: `Font id :x is not registered. Add it in Settings → Fonts.` Nessuna attesa, nessun "riprova": o l'id c'è, o non c'è. Tieni presente che un modello che usa un font custom funziona solo dove quell'id è stato registrato — quindi sull'app desktop di chi ha aggiunto quel font. 

### Misurare il testo

`text-width` restituisce la larghezza orizzontale che una stringa occuperà una volta renderizzata. Accetta l'id del font:

<!-- example-source: text-width :no-run
(println (text-width "Hello" :roboto-mono 20))    ;; => larghezza in unità alla size 20
-->

Utile per il layout: centrare il testo lungo un percorso, dimensionare una piastra di supporto, calcolare la spaziatura.

## 13.2 Testo come geometria estrusa

### Extrude manuale

Puoi passare il vettore di shape da `text-shape` direttamente a `extrude`:

<!-- example-source: text-extrude
(register title (extrude (text-shape "RIDLEY" :size 40) (f 5)))
-->

`extrude` combina le estrusioni delle singole shape in un'unica mesh, pronta per le booleane.

### Extrude-text: la scorciatoia

`extrude-text` combina `text-shape` e `extrude` in una sola chiamata:

```clojure
(extrude-text "RIDLEY")                            ;; default: size 10, depth 5
(extrude-text "RIDLEY" :size 40 :depth 3)          ;; personalizzato
(extrude-text "RIDLEY" :size 40 :font :roboto-mono);; font specifico per id
```

<!-- example-source: extrude-text
(register title (extrude-text "RIDLEY" :size 40 :depth 3))
-->

Come `text-shape`, usa la posa della tartaruga come rigo di scrittura (il testo parte da lì, non centrato) e si estrude in profondità. Restituisce un'unica mesh contenente tutti i glifi, pronta per essere combinata con booleane o passata a `attach`.

### Testo lungo un percorso

`text-on-path` piazza testo 3D lungo un percorso curvo:

<!-- example-source: text-on-path
(def curve (path (dotimes [_ 40] (f 2) (th 3))))
(register curved-text (text-on-path "Hello World" curve :size 15 :depth 3))
-->

Ogni lettera viene posizionata tangente al percorso nel punto corrispondente. Le opzioni:

`:spacing` aggiunge (o toglie, se negativo) spazio extra fra le lettere. `:align` controlla l'allineamento: `:start` (default), `:center`, `:end`. `:overflow` decide cosa fare se il testo è più lungo del percorso: `:truncate` (default, taglia), `:wrap` (ricomincia dall'inizio, utile per percorsi chiusi), `:scale` (scala il testo per farlo stare).

### Testo su un percorso 3D: preserve-up

Finché il percorso resta nel piano, le lettere stanno dritte da sole. Ma su un percorso che sale nello spazio — una spirale, un'elica — emerge un problema sottile. Ogni `th` e `tv` è una rotazione attorno agli assi *correnti* della tartaruga, e componendone tanti in sequenza il vettore up accumula una deriva: comincia a ruotare attorno all'heading senza che tu l'abbia chiesto (un *roll implicito*). Il testo, che usa l'up per sapere da che parte sta "in alto", si arrotola su sé stesso e diventa illeggibile.

`:preserve-up`, come opzione di `turtle`, cancella questa deriva: cattura l'up all'ingresso dello scope e dopo ogni rotazione lo riproietta sul piano perpendicolare all'heading. L'heading non cambia — quindi il percorso e le posizioni restano identici — ma l'up resta ancorato, e le lettere stanno su.

<!-- example-source: text-spiral
(def spiral (path (tv 7) (dotimes [_ 500] (f 1) (th 3))))
(register spiral-text
  (turtle :preserve-up
    (text-on-path
      "Once upon a time in a galaxy far far away there was a spiral that went up and up and up and never stopped and she's buying a stairway to heaven"
      spiral :size 6 :depth 1.2)))
-->

Lo `(tv 7)` iniziale inclina la tartaruga verso l'alto; i 500 passi di `(f 1) (th 3)` la fanno girare salendo, tracciando una spirale ascendente. Senza `:preserve-up` il testo si avviterebbe progressivamente; con, segue la spirale restando leggibile. È lo stesso flag che serve per eliche, molle, e corrimani che salgono.

### Pattern comune: testo inciso o in rilievo

Il testo 3D diventa utile quando lo combini con un pezzo tramite booleane:

<!-- example-source: text-engraved
(def plate (attach (box 3 20 80) (cp-f -40)))
(def label (attach (extrude-text "MODEL-A" :size 12 :depth 2) (f 15) (u -4)))
(register engraved (mesh-difference plate label))
-->

Un dettaglio sull'orientamento, che spiega le dimensioni scelte: il testo si sviluppa lungo l'asse "di scrittura" della tartaruga, quindi la piastra che lo deve ospitare va dimensionata lungo *quello* stesso asse (qui `box 3 20 80`, con la dimensione maggiore, 80, sull'asse del testo) perché la scritta ci stia dentro. La `cp-f -40` sposta la creation-pose della piastra a un'estremità, così il testo — che parte dalla posa corrente — si allinea al bordo invece di centrarsi. Per testo in rilievo, usa `mesh-union` al posto di `mesh-difference`. La profondità dell'estrusione (`:depth`) controlla quanto il testo sporge o penetra. Per una targhetta a due colori (testo in contrasto sulla base), vedi il pattern bicolore nel cap. 14.

## 13.3 Heightmap e testo in rilievo

Per testo in rilievo su una superficie curva (un cilindro, un vaso, un profilo organico), l'estrusione dritta non basta: il testo dovrebbe seguire la curvatura. La heightmap è la soluzione.

Una heightmap è una griglia 2D dove ogni cella contiene un'altezza. Usata come shape-fn in un `loft`, modula il profilo aggiungendo o togliendo materiale punto per punto: dove la cella è alta, il profilo si gonfia verso l'esterno.

### Text-heightmap

`text-heightmap` produce direttamente una heightmap a partire da una stringa, già orientata correttamente per essere avvolta su un profilo. La passi alla shape-fn `heightmap` dentro un `loft`:

<!-- example-source: text-heightmap :warning slow
(def hm (text-heightmap "Ridley" :size 20)) ; resolution 256, supersample 3, edge-softness 0.02
(register embossed-cylinder
  (loft-n 256 (heightmap (circle 10 256) hm :amplitude 1.5 :direction :height :center true) (f 60)))
-->

Il risultato è un cilindro con "Ridley" in rilievo, leggibile, alto ~20 unità (la `:size` che hai chiesto). Il punto chiave è che la heightmap *conosce la propria dimensione fisica*: `:size 20` non viene stirato fino a riempire la parete, ma piazzato alla taglia reale. È la shape-fn `heightmap` a leggere quella dimensione e a posizionare il testo di conseguenza — col comportamento di default (una copia singola, centrata, alla taglia fisica). `:amplitude` controlla quanto il rilievo sporge; `:center true` lo distribuisce simmetricamente, così le lettere emergono da una superficie neutra invece di gonfiare il cilindro verso l'esterno.

I parametri di `text-heightmap`: `:size` (altezza fisica dei caratteri, default 5), `:font` (id del font, default `:roboto`), `:resolution` (dimensione della griglia, default 256), più due manopole che governano la qualità del bordo: `:supersample` e `:edge-softness`.

Vale la pena capire perché servono, perché la causa è controintuitiva. Il rilievo del testo è una *maschera binaria*: lettera (1) o sfondo (0), con un bordo netto largo una sola cella della griglia. Quando il loft campiona il profilo più rado della griglia — e quasi sempre lo fa — i suoi vertici scavalcano quel bordo netto e le lettere vengono fuori a pettine, scalettate. La tentazione è alzare la risoluzione della griglia o del loft, ma non serve: il bordo resta comunque largo una cella. La soluzione è ammorbidire il bordo stesso. `:supersample` (default 3) rasterizza a risoluzione maggiore e media, portando la posizione del bordo a precisione sub-cella; `:edge-softness` (default 0.02, cioè il 2% dell'altezza dei caratteri) allarga la rampa del bordo a una scala che il loft riesce a risolvere, trasformando lo scalino in una smussatura morbida. Sono questi due — non `:resolution` né `:curve-segments` — a togliere il pettine. Per lettere più morbide alza `:edge-softness` (es. 0.04); per bordi netti, `:edge-softness 0`. (`:curve-segments` esiste e regola la fedeltà del *contorno* dei glifi, ma non c'entra col faceting sul loft.)

Una volta che il bordo è ammorbidito, un loft ragionevolmente fitto (`loft-n` con un buon numero di passi, e un cerchio a risoluzione adeguata) lo rende al meglio: i due lavorano insieme, non in alternativa. La morbidezza dà al loft una rampa da seguire invece di uno scalino netto; il loft fitto la campiona con abbastanza vertici da renderla liscia. È per questo che gli esempi qui sotto usano `loft-n 256` e `(circle ... 256)`.

Un dettaglio utile: gli spazi nel testo diventano margine piatto reale. `"Ridley "` (con lo spazio finale) è più largo di `"Ridley"`, e quel margine torna comodo come stacco quando ripeti il testo attorno a un profilo.

Direzione, copertura e ripetizione non sono compito di `text-heightmap` ma della shape-fn `heightmap`, che le controlla con `:direction`, `:tile-x`/`:tile-y`, `:fit`. Così la stessa heightmap si può far girare una volta attorno al cilindro, impacchettare a ripetizione lungo tutta la circonferenza, o far salire lungo l'asse:

<!-- example-source: text-heightmap-variants :warning slow
(def band (text-heightmap "Ridley " :size 10))

;; Ripetuta senza giunzioni attorno a tutta la circonferenza
(register tube
  (loft-n 256 (heightmap (circle 10 256) band :amplitude 1.2 :tile-x :fill) (f 30)))

;; Che sale lungo l'asse invece di avvolgersi
(register column
  (attach (loft-n 256 (heightmap (circle 8 256) band :amplitude 1.2 :direction :height) (f 40))
          (rt 60)))
-->

`:tile-x :fill` impacchetta tante copie quante ne servono per coprire la circonferenza (lo spazio finale di `"Ridley "` fa da stacco fra una e l'altra); `:direction :height` orienta il testo perché corra lungo l'asse del loft anziché attorno.

