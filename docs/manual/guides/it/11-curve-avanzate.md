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

### Coordinate mondo o locali

Di default il punto di destinazione e i punti di controllo sono coordinate del mondo: `[30 0 20]` è quel punto nello spazio, dovunque si trovi la tartaruga. Con il flag `:local` le stesse coordinate vengono lette nel frame locale della tartaruga, cioè nella terna `[right up heading]` con origine nella posizione corrente:

```clojure
(bezier-to [0 0 40] [10 0 15] [10 0 25] :local)
```

Qui `[0 0 40]` significa "40 unità in avanti lungo l'heading", non un punto fisso nello spazio. La differenza conta quando vuoi che la stessa curva funzioni ovunque metti la tartaruga: una `bezier-to` in coordinate mondo è ancorata a quei numeri, una `:local` segue la posa. È anche la forma che produce `edit-bezier`, ed è ciò che le permette di riscrivere una curva indipendente dalla posizione di partenza.

### edit-bezier: disegnare la curva invece di calcolarla

I punti di controllo di una Bezier cubica sono potenti ma poco intuitivi: per far seguire alla curva il profilo di un oggetto dovresti calcolarli a mano, e l'unica via esatta è risolvere l'equazione della cubica. `edit-bezier` ribalta il problema: invece di calcolare i punti di controllo, li disegni.

Si usa al posto di `bezier-to`, ovunque questa vada, e si lancia dal codice della definizione (Cmd+Enter), non dalla REPL:

<!-- example-source: edit-bezier-draw :no-run
(register handle
  (extrude (circle 3) (edit-bezier)))
-->

Quando valuti, `edit-bezier` disegna una curva di default valida, così che `extrude` e `register` girino comunque, e apre una sessione modale. Il punto di partenza è la posa della tartaruga in quel punto del codice: non è editabile e non viene mai scritto a sorgente, si ricalcola a ogni valutazione. Gli altri tre punti, il punto di arrivo e i due di controllo, sono mobili: li vedi nel viewport insieme al poligono di controllo e alla curva di anteprima, che si aggiorna mentre li sposti. Tab cicla fra i tre punti, le frecce muovono quello selezionato (con il terzo asse raggiungibile da tastiera), Enter conferma ed Esc annulla. I tasti precisi e le opzioni sono nella Reference.

Alla conferma la chiamata `(edit-bezier)` viene riscritta nella `bezier-to` corrispondente, in coordinate locali:

```clojure
(bezier-to [12 0 38] [4 0 14] [9 0 30] :local)
```

È qui che `:local` torna utile: i punti sono espressi nel frame della posa di partenza, quindi la curva resta corretta anche se sposti il codice che la precede, e riaprirla è un round-trip esatto. Per ri-editare una curva già prodotta, passi i suoi punti espliciti a `edit-bezier`.

C'è anche un modo `:shape`, pensato per disegnare un profilo 2D da dare a `stroke-shape`: la curva è planare e l'anteprima è allineata alla sezione che verrà estrusa. È la variante adatta proprio al caso da cui siamo partiti, far seguire a un bordo il profilo di un oggetto.

<!-- example-source: edit-bezier-profile :no-run
(register wall
  (extrude (stroke-shape (path (edit-bezier :shape)) 3) (f 20)))
-->

`edit-bezier` è una sessione modale come `tweak` e `pilot` (§ 15.3) e ne condivide i limiti: si apre una volta sola per valutazione e non convive con un'altra sessione interattiva già aperta.

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

## 11.3 Un limite del loft: quando la mesh non è manifold

`loft` costruisce la mesh spazzando una shape lungo il path, ma non controlla che le sezioni successive non si compenetrino. Su certe geometrie questo produce una mesh auto-intersecante: topologicamente chiusa, ma che `manifold?` rifiuta e che non è stampabile.

Le direzioni di rischio sono tre, spesso combinate:

- **shape forate** (anelli, profili cavi): il contorno interno ruota più stretto di quello esterno e si auto-interseca prima;
- **angoli stretti** nel path: più la curva è chiusa, più le sezioni si scavalcano sul lato concavo;
- **path bezier**: la smussatura distribuisce la curvatura, ma il raggio minimo locale può risultare più stretto di quanto sembri — motivo per cui `bezier-as`, che di solito *migliora* un loft, su una shape forata può peggiorarlo.

Le tre cose si sommano: un anello sottile su un angolo già modesto basta a far fallire il loft.

<!-- example-source: holed-shape-on-curve
;; Anello su un angolo contenuto: manifold
(register pipe-good
  (loft (shape-difference (circle 8) (circle 7)) identity
        (path (f 30) (th 90) (f 20))))

;; Stesso anello, angolo un po' più stretto: si auto-interseca
(register pipe-bad
  (attach (loft (shape-difference (circle 8) (circle 7)) identity
                (path (f 30) (th 100) (f 20)))
          (f 60)))

(println "Pipe good:" (if (manifold? (get-mesh :pipe-good)) "Good" "Bad"))
(println "Pipe bad:"  (if (manifold? (get-mesh :pipe-bad))  "Good" "Bad"))
-->

Non ci sono controlli automatici né una manopola che risolva il caso: lo strumento è verificare con `manifold?` e, dove fallisce, cercare un bypass. Per un profilo anulare lungo una curva, due bypass affidabili:

- **Costruire il tubo per differenza**: estrudi il profilo pieno (`circle 8`) lungo il path, estrudi il foro (`circle 7`) lungo lo *stesso* path, e sottrai il secondo dal primo con `mesh-difference`. Più lento, ma ogni estrusione lavora su una shape piena, molto più tollerante.
- **Tenere il path dritto e curvare con `attach`**, applicando le rotazioni al pezzo finito invece che durante l'estrusione.

La regola pratica resta: se `manifold?` torna `nil`, il rimedio è a monte, sul path o sulla strategia di costruzione.
