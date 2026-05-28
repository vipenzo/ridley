# 11. Curve avanzate

Nei capitoli precedenti hai costruito percorsi con segmenti dritti (`f`) e cambi di direzione (`th`, `tv`). Per molti modelli basta, ma non tutti: un manico curvo, un profilo aerodinamico, un tubo che segue una traiettoria fluida richiedono curve vere. Ridley offre due famiglie di curve: gli archi (cerchio a raggio e angolo noti) e le Bezier (curve libere controllate da punti).

## 11.1 Archi

Un arco è un segmento di cerchio. La tartaruga si muove lungo l'arco, girando progressivamente di un angolo dato con un raggio dato.

```clojure
(arc-h 10 90)       ;; arco orizzontale: raggio 10, 90°
(arc-h 10 -90)      ;; direzione opposta
(arc-v 10 90)       ;; arco verticale
```

`arc-h` gira attorno all'asse up della tartaruga (arco nel piano orizzontale). `arc-v` gira attorno all'asse right (arco nel piano verticale). Angolo positivo = direzione standard di rotazione, negativo = opposta.

Un arco è di fatto una sequenza di piccoli `f` + `th` (o `tv`), dove il numero di passi dipende da `resolution`. Puoi sovrascriverlo per singola chiamata:

```clojure
(arc-h 10 90 :steps 32)    ;; 32 passi invece del default
```

Gli archi si concatenano fra loro e con segmenti dritti. La curva a S è il pattern classico:

<!-- example-source: arc-s-tube
(register s-tube
  (extrude (circle 3)
    (arc-h 15 90)
    (arc-h 15 -90)))
-->

La giunzione fra i due archi è liscia perché la tartaruga esce dal primo arco con l'heading tangente alla curva, e il secondo arco parte da lì.

### Archi dentro path ed extrude

Gli archi funzionano ovunque funzionano i comandi tartaruga: dentro `path`, dentro `extrude`, dentro `loft`, dentro `attach`. Non c'è differenza sintattica: sono comandi tartaruga come `f` e `th`.

<!-- example-source: arc-in-extrude
;; Path curvo con marcatori
(def curved-skel
  (path
    (mark :x-start)
    (f 20)
    (arc-h 20 45)
    (mark :x-bend)
    (arc-h 20 45)
    (f 30)
    (mark :x-end)))
;; Estrusione lungo il path
(register pipe (extrude (circle 4) curved-skel))
;; Decorazioni distribuite sui marcatori
(register decoration
  (on-anchors curved-skel
    "x" :align (cyl 10 2)))
-->

Il marcatore `:x-bend` è piazzato a metà della curva, fra i due archi: i marker possono stare ovunque lungo un path, anche dentro una curva. E `on-anchors` (§ 8.1) funziona su un path curvo esattamente come su uno fatto di segmenti dritti.

## 11.2 Curve di Bezier

Le Bezier sono curve più flessibili degli archi: invece di raggio e angolo, le definisci con un punto di destinazione e (opzionalmente) uno o due punti di controllo che ne determinano la curvatura.

### Bezier-to: curva verso un punto

La forma più semplice è `bezier-to` con solo il punto di destinazione:

```clojure
(bezier-to [30 0 20])    ;; curva dalla posizione corrente a [30 0 20]
```

Senza punti di controllo espliciti, Ridley genera automaticamente i control point in modo che la curva parta tangente all'heading corrente della tartaruga. Il risultato è una curva liscia che "esce" dalla direzione in cui stai guardando e arriva al punto target.

Con un punto di controllo (Bezier quadratica):

```clojure
(bezier-to [30 0 20] [15 0 30])    ;; la curva passa vicino a [15 0 30]
```

Con due punti di controllo (Bezier cubica):

```clojure
(bezier-to [30 0 20] [10 0 25] [25 0 25])
```

Il numero di passi della curva segue `resolution`, oppure lo specifichi:

```clojure
(bezier-to [30 0 20] :steps 32)
```

### Bezier-to-anchor: curva verso un marcatore

`bezier-to-anchor` costruisce una Bezier cubica che collega la posizione corrente della tartaruga a un marcatore nominato. I punti di controllo vengono generati automaticamente usando gli heading di partenza e di arrivo, garantendo continuità tangenziale (la curva esce tangente al tuo heading e arriva tangente all'heading del marcatore):

<!-- example-source: bezier-to-anchor-demo
(def skel (path (mark :A) (f 30) (th 90) (f 20) (mark :B)))
(color 0xff0000)
(follow-path skel)
(color 0xffffff)
(reset)
(with-path skel
  (bezier-to-anchor :B :tension 0.5))
(color 0x0000ff)
(reset)
(u -30) (th 45) (f 20)
(register curve (extrude (circle 2) (with-path skel (bezier-to-anchor :B :tension 0.5))))
-->

`bezier-to-anchor` va usato dentro `with-path`: è il `with-path` che dà senso al riferimento `:B`, risolvendolo sul path corrente. La curva parte dalla posizione della tartaruga al momento della chiamata e arriva a `:B`. Nell'esempio: prima la tracciamo in bianco (dopo `(reset)`, quindi parte dall'origine dove sta `:A`) sovrapposta allo skeleton spigoloso in rosso, poi la riusiamo in blu come spina di un'estrusione, con la tartaruga riposizionata altrove per mostrare che la curva si costruisce dalla posa corrente.

`:tension` controlla quanto i punti di controllo sono distanti dagli estremi: valori bassi producono curve più tese (vicine alla linea retta), valori alti curve più ampie.

### Bezier-as: smussare un path esistente

A volte hai già un path fatto di segmenti dritti e curve discrete, e vuoi trasformarlo in una curva liscia. `bezier-as` prende un path e lo approssima con una sequenza di Bezier cubiche, una per ogni segmento del path originale, con continuità C1 alle giunzioni:

<!-- example-source: bezier-as-smooth
;; Path a zig-zag
(def zigzag (path (f 20) (th 45) (f 15) (th -90) (f 15) (th 45) (f 20)))

;; Versione smussata
(register smooth-tube
  (extrude (circle 3) (bezier-as zigzag)))
-->

Le opzioni:

```clojure
(bezier-as zigzag :tension 0.5)              ;; punti di controllo più lontani
(bezier-as zigzag :steps 32)                 ;; risoluzione per segmento
(bezier-as zigzag :cubic true)               ;; tangenti Catmull-Rom
(bezier-as zigzag :max-segment-length 20)    ;; suddividi segmenti lunghi prima
```

`:cubic true` usa tangenti Catmull-Rom invece delle tangenti lineari di default: il risultato è più liscio ma meno prevedibile.

`bezier-as` funziona sia in turtle mode diretto che dentro `path`.

## 11.3 Curve strette e auto-intersezione

Le curve Bezier possono avere curvature strette, specialmente con tensione alta o traiettorie a U. Quando il raggio di curvatura locale è confrontabile col raggio della shape estrusa, le sezioni (ring) successive del loft sul lato interno della curva si scavalcano: il loft non se ne accorge e produce geometria auto-intersecante. `manifold?` restituisce `nil` (la mesh non è stampabile), e operazioni booleane successive sulla mesh saltano in aria.

Il rimedio è a monte, sul path: ridurre la curvatura locale rispetto al raggio della shape. In ordine di crescente costo:

1. **Smussare il path con `bezier-as`** quando hai spigoli netti (`(th 120)` o simili). Anche una smussatura leggera (`:tension 0.3`) trasforma uno spigolo in un arco di raggio finito, dando alle ring lo spazio per ruotare senza scavalcarsi.

2. **Sostituire l'angolo con un `arc-h`/`arc-v` di raggio esplicito**: `(arc-h r 90)` dichiara esattamente con che raggio gira il path. Se `r` è almeno comparabile col raggio della shape (e meglio 2-3× più grande), il loft passa pulito.

3. **Abbassare il raggio della shape** se il path non si può cambiare (per esempio quando l'utente lo fornisce). Una `circle 2` su una curva che ha problemi con `circle 8` può funzionare senza modifiche al path.

4. **Spezzare il loft in tratti dritti uniti con `mesh-union`**, accettando un raccordo squadrato al posto della transizione continua.

Le shape con holes (es. `(shape-difference (circle 8) (circle 4))`) sono più sensibili: il ring interno spazza un raggio più piccolo del path e si auto-interseca prima del ring esterno. Se ti serve un profilo anulare lungo una curva, tieni la curvatura gentile rispetto al raggio del foro (in pratica: smussa di più, o usa un foro più piccolo).

La regola pratica resta: se `manifold? nil`, agisci sul path. Loft non ha meccanismi automatici di recupero, e fingere che ce ne fossero produrrebbe solo silent failure.
