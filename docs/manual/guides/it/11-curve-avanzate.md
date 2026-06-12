# 11. Curve avanzate

<!-- level: advanced -->

Nei capitoli precedenti hai costruito percorsi con segmenti dritti (`f`) e cambi di direzione (`th`, `tv`). Per molti modelli basta, ma non tutti: un manico curvo, un profilo aerodinamico, un tubo che segue una traiettoria fluida richiedono curve vere. Ridley offre due famiglie di curve: gli archi (cerchio a raggio e angolo noti) e le Bezier (curve libere controllate da punti).

## 11.1 Archi

Gli archi li hai già usati nel cap. 4: `arc-h` curva nel piano orizzontale (attorno all'asse up della tartaruga), `arc-v` in quello verticale (attorno all'asse right); angolo positivo nella direzione standard di rotazione, negativo nell'opposta. Sotto il cofano un arco è una sequenza di piccoli `f` + `th` (o `tv`): il numero di passi dipende da `resolution` e si sovrascrive per singola chiamata con `:steps`:

```clojure
(arc-h 10 90 :steps 32)    ;; 32 passi invece del default
```

Due archi consecutivi si raccordano lisci da sé: la tartaruga esce dal primo con l'heading tangente alla curva, e il secondo parte da lì. Quello che il cap. 4 non diceva è che gli archi sono comandi tartaruga a tutti gli effetti, e questo ha una conseguenza preziosa per i path.

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

All'**arrivo**, di default, la curva è tangente alla corda inizio→fine: la tartaruga finisce rivolta verso il target, non più nella direzione di partenza. Con il flag `:preserve-heading` la curva arriva invece tangente all'heading corrente, che resta così **invariato**: un movimento successivo si salda senza cuspidi.

```clojure
(bezier-to [20 30 0] :preserve-heading)   ;; arriva tangente all'heading corrente
(f 10)                                     ;; prosegue dritto, senza spigolo
```

È la forma comoda per chiudere un profilo in cui un lato è curvo e gli altri dritti: la curva si raccorda con i segmenti retti senza che tu debba calcolare i punti di controllo. `:tension` (default `0.33`) regola quanto bomba la curva — più alto, pancia più larga. È in pratica un `bezier-to-anchor` verso un'ancora con il tuo stesso heading, ma senza dover definire l'ancora.

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

`edit-bezier` è una sessione modale come `tweak` e `pilot` (§ 15.3) e ne condivide i limiti: si apre una volta sola per valutazione e non convive con un'altra sessione interattiva già aperta. Mentre la sessione è aperta l'editor è di sola lettura: la conferma riscrive il sorgente da sé, e una modifica a mano romperebbe la sostituzione. Cambiare workspace chiude la sessione.

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

`:tension` controlla quanto i punti di controllo sono distanti dagli estremi: valori bassi producono curve più tese (vicine alla linea retta), valori alti curve più ampie. `:tension-end` regola separatamente la tension all'arrivo, per curve asimmetriche.

Esiste anche una forma path-first che evita `with-path`: passando il path come primo argomento, i mark vengono risolti al volo. `(bezier-to-anchor ps :at :end :tension 0.5)` è equivalente a racchiudere la chiamata in `(with-path ps …)`, ma più compatta quando il path è già sotto mano. È la forma che useremo nel §11.3.

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
(bezier-as zigzag :control true)             ;; vertici come punti di controllo
```

`:cubic true` usa tangenti Catmull-Rom invece delle tangenti lineari di default: il risultato è più liscio ma meno prevedibile.

Di default `bezier-as` interpola i vertici del path: la curva passa per ciascun vertice. Con `:control true` li tratta come punti di controllo: la curva non passa più per i vertici ma per i punti medi dei segmenti, restando tangente lì, e arrotonda il poligono invece di inseguirlo. È il duale dell'interpolazione, ed è comodo quando il poligono è il tuo strumento di disegno e vuoi controllare il bow muovendo i vertici. Lo vedremo all'opera nel §11.3.

`bezier-as` funziona sia in turtle mode diretto che dentro `path`.

## 11.3 Lo stesso profilo, cinque modi

Le funzioni del §11.2 sembrano alternative, ma spesso risolvono lo stesso problema da angolazioni diverse. Per renderlo concreto prendiamo un profilo solo e lo costruiamo in cinque modi, ordinati lungo uno spettro: a un estremo la matematica fa tutto, all'altro la disegni a occhio e di matematica non resta niente. Tre dei cinque modi producono la curva identica per strade diverse; gli altri due mostrano i confini della famiglia.

Il profilo è uno spigolo arrotondato a "L": la curva va da (0,0) a (a,a), parte tangente orizzontale e arriva tangente verticale, e bomba verso la diagonale a 45°. Una misura sola, D, dice quanto bomba.

Tutti i metodi partono dallo stesso scaffold: la "L" non arrotondata, con due mark agli estremi. Dai mark, e dagli heading in quei punti, vengono gli estremi e le direzioni tangenti che ogni curva deve rispettare.

```clojure
(def a 45)   ; lato: la curva va da (0,0) a (a,a)
(def D 51)   ; misura sulla diagonale a 45°
(def ps (path (mark :start) (f a) (th 90) (f a) (mark :end)))
```

In `:start` l'heading è orizzontale, in `:end` è verticale (dopo il `th 90`).

Per misurare il bombaggio reale serve uno scaffold della diagonale, indipendente dalla curva: un `side-trip` (che non aggiunge geometria, lascia solo mark) piazza `:center` sull'angolo (0,a) e `:D`, il bersaglio a distanza D sulla diagonale a 45°. Il `th 90` dopo `:D` gli dà l'heading +45°, la tangente della curva nell'apice: così `:D` non è solo una posizione ma un anchor con direzione, a cui mirare con `edit-bezier`, come faremo a fine modo 4 per disegnare la metà.

```clojure
(def diag
  (path (mark :start)
        (side-trip (th 90) (f a) (th -135) (mark :center) (f D) (th 90) (mark :D))))
```

Poi un helper `wall` per confrontare le curve a parità di tutto: estrude ognuna come muretto e le monta accanto un righello che ne misura il bombaggio sulla diagonale.

```clojure
(defn wall
  ([curve] (wall curve 0))
  ([curve dx]
   (let [profile (add-mark (path (follow-path diag)
                                 (follow-path curve))
                           :apex 0.5)
         w (attach (extrude (stroke-shape profile 3) (f 10)) (rt dx))]
     (ruler w :at :center w :at :apex)
     w)))
```

`wall` antepone `diag` alla curva con `follow-path` (il side-trip viaggia con la curva senza aggiungere geometria), poi `add-mark` marca l'apice reale a metà curva: `(add-mark … :apex 0.5)` mette `:apex` a metà lunghezza, l'apice del bombaggio. Infine estrude, posa a `dx` e tira il righello da `:center` a `:apex`. I mark diventano anchor della mesh estrusa, e la forma `:at` di `ruler` li rilegge sulla mesh già piazzata: `(ruler w :at :center w :at :apex)` resta agganciato anche dopo `attach` e `mesh-union`. È la lunghezza che cala quando una curva non arriva fino a D.

### Modo 1: la matematica per intero

La via diretta: risolvi l'equazione della cubica per trovare la lunghezza `p` delle maniglie che fa passare la curva esattamente a distanza D sulla diagonale. Funziona, ed è esatta, ma i conti li fai tu.

```clojure
(def p (* (/ 4 3) (- (* (sqrt 2) D) a)))   ; ≈ 36.17
(def curve-1 (path (bezier-to [a a 0] [p 0 0] [a (- a p) 0])))
```

I due punti di controllo stanno lungo le tangenti, a distanza `p` dagli estremi. Tutta la fatica è in quella formula.

### Modo 2: la matematica ridotta a un numero

`bezier-to-anchor` prende estremi e tangenti dai mark di `ps`, quindi resta un solo parametro da decidere: la tension. Qui la ricaviamo da `p`, ma da lì in poi è l'unico numero da toccare.

```clojure
(def t (/ p (* a (sqrt 2))))   ; ≈ 0.568
(def curve-2 (path (bezier-to-anchor ps :at :end :tension t)))
```

La forma path-first risolve i mark al volo, senza `with-path`. La curva è identica a quella del modo 1, ma l'abbiamo descritta con un parametro invece che con due punti calcolati.

### Modo 3: nessun calcolo, a occhio

`edit-bezier` toglie anche quell'ultimo numero: invece di calcolare la tension, la regoli a vista finché la curva tocca la diagonale.

<!-- example-source: cinque-modi-edit :no-run
(def a 45)
(def D 51)
(def ps (path (mark :start) (f a) (th 90) (f a) (mark :end)))
(def diag
  (path (mark :start)
        (side-trip (th 90) (f a) (th -135) (mark :center) (f D) (th 90) (mark :D))))
(defn wall
  ([curve] (wall curve 0))
  ([curve dx]
   (let [profile (add-mark (path (follow-path diag)
                                 (follow-path curve))
                           :apex 0.5)
         w (attach (extrude (stroke-shape profile 3) (f 10)) (rt dx))]
     (ruler w :at :center w :at :apex)
     w)))
(register corner (wall (path (edit-bezier ps :at :end :symmetric))))
-->

Si lancia dal pannello definizioni (Cmd+Enter): apre l'editor con uno slider per la tension, che trascini o muovi con le frecce, e il righello da `:center` a `:apex` montato da `wall` sale verso D=51 man mano che la curva tocca la diagonale; Invio conferma. Alla conferma la chiamata si riscrive proprio nella forma del modo 2, `(bezier-to-anchor ps :at :end :tension …)`. Il modo 3 quindi non è una curva in più: è il modo interattivo di ottenere il modo 2 senza fare conti. Con `:symmetric` lo slider è uno solo; senza, sono due, start ed end.

### Modo 4: simmetria per costruzione

Lo spigolo è simmetrico rispetto alla diagonale, e possiamo sfruttarlo: disegni solo la metà, da O al punto di mezzo M, e la rifletti.

```clojure
(def half (path (bezier-to [36.06 8.94 0] [18.09 0 0] [29.34 2.21 0])))
(def curve-4
  (path (follow-path half)
        (follow-path (reverse-path (mirror-path half)))))
```

I quattro numeri di `half` non sono calcolati: sono quello che `edit-bezier` ti scrive a sorgente dopo aver disegnato la metà a occhio, esattamente come nel modo 3. Li mostriamo letterali perché sono l'artefatto del disegno, non una formula da derivare. Ricavarli dalla cubica del modo 1 farebbe perdere al modo 4 la sua identità: una costruzione simmetrica che non dipende da nessuno degli altri.

`mirror-path` riflette la metà sul piano di simmetria, `reverse-path` la rigira perché si concateni con la prima. Non serve dichiarare l'asse: il path finisce rivolto verso la tangente vera, e `mirror-path` usa quell'heading come normale del piano, cioè il piano right/up della tartaruga in quel punto. La curva esce simmetrica da sola. `follow-path`, dentro un `(path …)`, registra invece di limitarsi a muovere la tartaruga, ed è questo che permette di cucire le due metà in un unico path.

E come il modo 3 produce la curva intera a occhio, la metà del modo 4 si autora allo stesso modo con `edit-bezier`: solo che invece di mirare a `:end` si mira a `:D`, l'apice. È qui che serve un `:D` raggiungibile, ed è per questo che `diag` gli ha dato un heading: con estremi e tangenti fissi resta un solo numero, la tension. Mentre la regoli, il preview ricompila tutto, metà più riflessione, e il righello da `:center` a `:apex` sale verso D=51; Invio riscrive in `(bezier-to-anchor diag :at :D :tension …)`.

<!-- example-source: cinque-modi-bis-edit :no-run
(def a 45)
(def D 51)
(def diag
  (path (mark :start)
        (side-trip (th 90) (f a) (th -135) (mark :center) (f D) (th 90) (mark :D))))
(defn wall
  ([curve] (wall curve 0))
  ([curve dx]
   (let [profile (add-mark (path (follow-path diag)
                                 (follow-path curve))
                           :apex 0.5)
         w (attach (extrude (stroke-shape profile 3) (f 10)) (rt dx))]
     (ruler w :at :center w :at :apex)
     w)))
(def half (path (edit-bezier diag :at :D :symmetric)))
(def curve-4
  (path (follow-path half)
        (follow-path (reverse-path (mirror-path half)))))
(register corner (wall curve-4))
-->

Così l'interattivo compare due volte nello spettro, una per strategia: il modo 3 disegna la curva intera mirando a `:end`, e questa variante ne disegna la metà mirando all'apice. Lo stesso strumento serve sia la costruzione diretta sia quella simmetrica.

### Modo 5: il poligono di controllo

Gli altri quattro modi (con il 3 che collassa sul 2) danno la curva identica. Il quinto è un cugino: stesse tangenti agli estremi, ma bow un filo diverso, perché nasce da un poligono di controllo invece che da una cubica tarata su D.

`bezier-as :control` tratta i vertici del path come punti di controllo: la curva passa per i punti medi dei segmenti, tangente lì, e arrotonda il poligono.

```clojure
(def curve-5
  (let [x 24                     ; unico parametro libero: le gambe, accoppiate
        y (* (- a x) (sqrt 2))   ; bevel a 45° derivato: chiude su (a,a)
        poly (path (f x) (th 45) (f y) (th 45) (f x))]
    (path (bezier-as poly :control true))))
```

Il poligono è simmetrico con un solo parametro libero, `x`, la lunghezza delle due gambe; il bevel a 45° `y` è derivato perché la curva chiuda esattamente su (a,a). Cambiando `x` cambi il bow mantenendo simmetria ed estremi: lo puoi mettere a slider con `tweak` e guardare il righello da `:center` a `:apex` (montato da `wall`) scendere sotto D quando bombi di meno.

### Il confronto

Affianchiamo i quattro muretti, a 80mm l'uno dall'altro, ciascuno con il suo righello:

<!-- example-source: cinque-modi-confronto
(def a 45)   ; lato: la curva va da (0,0) a (a,a)
(def D 51)   ; misura sulla diagonale a 45°
(def ps (path (mark :start) (f a) (th 90) (f a) (mark :end)))

;; scaffold della diagonale: :center sull'angolo, :D bersaglio + heading apice
(def diag
  (path (mark :start)
        (side-trip (th 90) (f a) (th -135) (mark :center) (f D) (th 90) (mark :D))))

;; muretto + righello :center → :apex (il bombaggio reale)
(defn wall
  ([curve] (wall curve 0))
  ([curve dx]
   (let [profile (add-mark (path (follow-path diag)
                                 (follow-path curve))
                           :apex 0.5)
         w (attach (extrude (stroke-shape profile 3) (f 10)) (rt dx))]
     (ruler w :at :center w :at :apex)
     w)))

;; 1. cubica risolta a mano
(def p (* (/ 4 3) (- (* (sqrt 2) D) a)))
(def curve-1 (path (bezier-to [a a 0] [p 0 0] [a (- a p) 0])))

;; 2. una sola tension (path-first, niente with-path)
(def t (/ p (* a (sqrt 2))))
(def curve-2 (path (bezier-to-anchor ps :at :end :tension t)))

;; 4. metà disegnata + mirror
(def half (path (bezier-to [36.06 8.94 0] [18.09 0 0] [29.34 2.21 0])))
(def curve-4
  (path (follow-path half)
        (follow-path (reverse-path (mirror-path half)))))

;; 5. poligono di controllo
(def curve-5
  (let [x 24
        y (* (- a x) (sqrt 2))
        poly (path (f x) (th 45) (f y) (th 45) (f x))]
    (path (bezier-as poly :control true))))

(register confronto
  (mesh-union
    [(wall curve-1 0)
     (wall curve-2 80)
     (wall curve-4 160)
     (wall curve-5 240)]))
-->

Nel viewport il conteggio si legge su tre livelli, e i righelli lo confermano con un numero. Cinque metodi, ma quattro muretti: il modo 3 non è un muretto a sé, è la rotta interattiva verso il modo 2. Quattro muretti, ma due curve distinte: i modi 1, 2 e 4 sono pixel-identici e i loro righelli leggono D=51, mentre il poligono di controllo del modo 5 bomba un po' meno e il suo righello scende a circa 48.8.

Lo spettro è tutto qui: dal modo 1, dove la matematica fa tutto, al modo 3, dove non ne resta niente, passando per gradi intermedi. La curva di arrivo è la stessa; cambia solo quanto lavoro deleghi al calcolo e quanto all'occhio.

## 11.4 Un limite del loft: quando la mesh non è manifold

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
