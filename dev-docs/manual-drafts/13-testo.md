# 13. Testo

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

Il risultato non è una shape per carattere, ma una shape per *contorno esterno*. La maggior parte dei caratteri ha un solo contorno esterno, ma i caratteri composti ne hanno di più: `i` e `j` ne hanno due (corpo + puntino), `ä` e `ö` ne hanno tre (corpo + due punti). I buchi interni (il vuoto dentro la `o`, la `a`, la `B`) vengono attribuiti automaticamente al contorno esterno più piccolo che li contiene.

Per le shape dei singoli caratteri (senza composizione automatica dei contorni):

```clojure
(text-shapes "ABC" :size 20)        ;; vettore di shape, una per carattere
```

`char-shape` produce la shape di un singolo carattere, ma richiede un oggetto font esplicito (vedi sotto):

```clojure
(char-shape "A" my-font 20)         ;; singolo carattere con font esplicito
```

### Font

Il font di default è Roboto Regular. `text-shape`, `text-shapes`, `extrude-text` e `text-on-path` lo usano automaticamente senza che tu debba passare nulla.

Per usare un font diverso, caricalo con `load-font!`:

```clojure
;; Font monospace built-in
(def mono (load-font! :roboto-mono))

;; Font custom da file (desktop)
(def my-font (load-font! "/path/to/font.ttf"))

;; Usa il font caricato
(extrude-text "RIDLEY" :size 40 :font mono)
(char-shape "A" mono 20)
```

`load-font!` restituisce l'oggetto font. È asincrono internamente, ma in pratica i font built-in si caricano prima del primo eval. Un font custom da file potrebbe richiedere un secondo eval per essere disponibile.

`(font-loaded?)` restituisce `true` se il font di default (Roboto) è pronto. Non accetta parametri: è un check sul font di default, non su quelli custom.

### Misurare il testo

`text-width` restituisce la larghezza orizzontale che una stringa occuperà una volta renderizzata. Richiede un oggetto font esplicito:

```clojure
(def mono (load-font! :roboto-mono))
(text-width "Hello" mono 20)    ;; => larghezza in unità alla size 20
```

Utile per il layout: centrare il testo lungo un percorso, dimensionare una piastra di supporto, calcolare la spaziatura.

## 13.2 Testo come geometria estrusa

### Extrude manuale

Puoi passare il vettore di shape da `text-shape` direttamente a `extrude`:

```clojure
(register title (extrude (text-shape "RIDLEY" :size 40) (f 5)))
```

<!-- example-source: text-extrude
(register title (extrude (text-shape "RIDLEY" :size 40) (f 5)))
-->

`extrude` combina le estrusioni delle singole shape in un'unica mesh, pronta per le booleane.

### Extrude-text: la scorciatoia

`extrude-text` combina `text-shape` e `extrude` in una sola chiamata:

```clojure
(extrude-text "RIDLEY")                            ;; default: size 10, depth 5
(extrude-text "RIDLEY" :size 40 :depth 3)          ;; personalizzato
(extrude-text "RIDLEY" :size 40 :font my-font)     ;; font custom
```

<!-- example-source: extrude-text
(register title (extrude-text "RIDLEY" :size 40 :depth 3))
-->

Il testo scorre lungo l'heading della tartaruga e si estrude lungo l'up. Restituisce una mesh per carattere; usa `concat-meshes` se ti serve un'unica mesh.

### Testo lungo un percorso

`text-on-path` piazza testo 3D lungo un percorso curvo:

```clojure
(def curve (path (dotimes [_ 40] (f 2) (th 3)))))

(register curved-text
  (text-on-path "Hello World" curve :size 15 :depth 3))
```

<!-- example-source: text-on-path
(def curve (path (dotimes [_ 40] (f 2) (th 3))))
(register curved-text (text-on-path "Hello World" curve :size 15 :depth 3))
-->

Ogni lettera viene posizionata tangente al percorso nel punto corrispondente. Le opzioni:

`:spacing` aggiunge (o toglie, se negativo) spazio extra fra le lettere. `:align` controlla l'allineamento: `:start` (default), `:center`, `:end`. `:overflow` decide cosa fare se il testo è più lungo del percorso: `:truncate` (default, taglia), `:wrap` (ricomincia dall'inizio, utile per percorsi chiusi), `:scale` (scala il testo per farlo stare).

### Pattern comune: testo inciso o in rilievo

Il testo 3D diventa utile quando lo combini con un pezzo tramite booleane:

```clojure
;; Testo inciso su una piastra
(def plate (box 80 20 3))
(def label (attach (extrude-text "MODEL-A" :size 12 :depth 2) (u 2)))

(register engraved (mesh-difference plate label))
```

<!-- example-source: text-engraved
(def plate (box 80 20 3))
(def label (attach (extrude-text "MODEL-A" :size 12 :depth 2) (u 2)))
(register engraved (mesh-difference plate label))
-->

Per testo in rilievo, usa `mesh-union` al posto di `mesh-difference`. La profondità dell'estrusione (`:depth`) controlla quanto il testo sporge o penetra.

## 13.3 Heightmap e testo in rilievo

Per testo in rilievo su una superficie curva (un cilindro, un vaso, un profilo organico), l'estrusione dritta non basta: il testo dovrebbe seguire la curvatura. La heightmap è la soluzione.

Una heightmap è una griglia 2D dove ogni cella contiene un'altezza. Usata come shape-fn in un `loft`, modula il profilo aggiungendo o togliendo materiale punto per punto.

### Mesh-to-heightmap

`mesh-to-heightmap` prende una mesh e ne rasterizza i valori Z in una griglia 2D:

```clojure
;; Crea il testo come mesh piatta
(def text-mesh (extrude-text "Ridley" :size 20 :depth 1))

;; Rasterizza in heightmap
(def hm (mesh-to-heightmap text-mesh :resolution 128))
```

<!-- example-source: text-heightmap
(def text-mesh (extrude-text "Ridley" :size 20 :depth 1))
(def hm (mesh-to-heightmap text-mesh :resolution 128))
(register embossed
  (loft (heightmap (circle 20 64) :amplitude 0.5 :tile-x 1 :tile-y 1)
    (f 40)))
-->

La heightmap risultante può essere usata con la shape-fn `heightmap` per applicare il rilievo del testo a qualsiasi estrusione o loft:

```clojure
(register embossed-cylinder
  (loft
    (heightmap (circle 20 64) hm :amplitude 1.5)
    (f 40)))
```

`:amplitude` controlla l'altezza del rilievo. `:tile-x` e `:tile-y` ripetono la heightmap lungo i due assi del profilo.

### Heightmap tileabili

Per pattern ripetitivi (texture, trame), le heightmap tileabili evitano giunzioni visibili:

`weave-heightmap` genera analiticamente una trama di intreccio:

```clojure
(def weave (weave-heightmap :threads 4 :spacing 5 :radius 2 :resolution 128))
```

`sample-heightmap` campiona una heightmap con interpolazione bilineare e auto-tiling:

```clojure
(sample-heightmap hm u v)    ;; u, v in [0, 1], auto-repeat
```

`heightmap-to-mesh` converte una heightmap in una mesh piatta con Z dai valori della griglia:

```clojure
(register terrain (heightmap-to-mesh hm :z-scale 3 :size 100))
```

Queste funzioni sono gli stessi strumenti usati dalla shape-fn `heightmap` del capitolo 6, presentati qui come operazioni standalone.
