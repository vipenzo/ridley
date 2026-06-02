# 8. Assemblaggio

Fino a qui hai costruito pezzi singoli: una mesh, un'estrusione, una forma modellata con le shape-fn. Questo capitolo spiega come metterli insieme.

Assemblare in Ridley significa posizionare componenti nello spazio usando la tartaruga come strumento di posizionamento. Il principio è lo stesso che hai usato finora per muoverti e costruire: dai comandi alla tartaruga (`f`, `th`, `tv`, `rt`...), e lei si sposta. La differenza è che ora la tartaruga trascina con sé un pezzo già costruito invece di costruirne uno nuovo.

Il capitolo introduce quattro idee che si combinano fra loro: i marcatori come sistema per scoprire e nominare i punti di aggancio di un assemblaggio, la creation-pose come meccanismo per decidere *dove* su un componente cade il punto di aggancio, `attach` come operazione di posizionamento, e lo skeleton come struttura portante che tiene insieme tutto.

## 8.1 Marcatori e on-anchors

Un marcatore (`mark`) è una bandierina che pianti dentro un `path` per dire "questo punto mi interessa, ci tornerò". Al momento della creazione registra la posizione e l'orientamento della tartaruga e gli dà un nome. Quando in seguito assembli, i marcatori sono i punti su cui agganci i componenti.

Il modo idiomatico per distribuire pezzi sui marcatori di uno skeleton è la macro `on-anchors`. Immagina un quadro con quattro borchie decorative agli angoli:

<!-- example-source: on-anchors-quadro
(def W 100) (def D 60)
(def W2 (/ W 2)) (def D2 (/ D 2))
(def plate (extrude (rect W D) (f 3)))
(defn stud [] (sphere 4))
(def stud-skel
  (path
    (rt W2) (u D2)  (mark :stud-tr)
    (u (- D))       (mark :stud-br)
    (rt (- W))      (mark :stud-bl)
    (u D)           (mark :stud-tl)))
(register decorated-plate
  (mesh-union
    plate
    (on-anchors stud-skel
      "stud-" (stud))))
-->

Lo skeleton `stud-skel` cammina lungo i quattro angoli del rettangolo e pianta un marcatore in ognuno. La forma di `on-anchors` è una tabella di clausole: a sinistra un *pattern* che seleziona marcatori, a destra un *body* che viene eseguito su ciascun marcatore selezionato. Qui il pattern è la stringa `"stud-"`, che matcha tutti i marcatori il cui nome inizia con quel prefisso (tutti e quattro, in questo caso); il body è `(stud)`, che costruisce una sfera nuova sul marker corrente.

Una nota sul perché `stud` è una funzione e non un `def`. Dentro `on-anchors` ogni body è eseguito con la tartaruga posizionata sul marker; le primitive (`sphere`, `box`, `cyl`, ...) costruite *dentro* il body nascono direttamente in quella posa. Una mesh creata *prima* invece ha le sue coordinate fissate al punto di costruzione, e va spostata esplicitamente — un argomento che vedrai nel § 8.2 quando incontrerai `attach` e la *creation-pose*.

### Più ruoli con più pattern

Il punto forte di `on-anchors` è quando i marcatori hanno ruoli diversi e ogni ruolo riceve un pezzo diverso. Una staccionata con montanti d'angolo grossi e montanti intermedi sottili:

<!-- example-source: on-anchors-staccionata
(def row-skel
  (path
    (side-trip (tv 90) (mark :end-post-start))
    (f 20)
    (doseq [i (range 5)]
      (side-trip (tv 90) (mark (keyword (str "mid-post-" i))))
      (f 20))
    (side-trip (tv 90) (mark :end-post-finish))))
(defn end-post [] (attach (cyl 8 40) (cp-f -20)))
(defn mid-post [] (attach (cyl 4 30) (cp-f -15)))
(register fence
  (on-anchors row-skel
    "end-post-" :align (end-post)
    "mid-post-" :align (mid-post)))
-->

Due cose nuove in questo esempio. La prima è la generazione programmatica dei nomi: dentro `path`, un `doseq` itera cinque volte e pianta un marcatore con nome calcolato (`:mid-post-0`, `:mid-post-1`, ..., `:mid-post-4`). Quando il numero di marcatori dipende da un parametro, nominarli a mano è tedioso — generarli programmaticamente è la via.

La seconda è il modificatore `:align`. Di default `on-anchors` posiziona la tartaruga sul marcatore ma non ruota il body per allinearlo al frame del marcatore: il body conserva l'orientamento che avrebbe in coordinate di partenza. Aggiungendo `:align` dopo il pattern, anche l'orientamento si allinea — nel caso della staccionata, i montanti vengono ruotati per stare in piedi. La distinzione conta quando un marcatore ha un heading significativo; quando non conta (come per le borchie del quadro, dove ogni angolo è equivalente), si può omettere.

L'esempio mostra un altro pattern utile: i marker sono piantati dentro un `side-trip`, ognuno preceduto da `(tv 90)` per ruotare l'orientamento verso l'alto. `side-trip` salva la posa della tartaruga, esegue il blocco, e ripristina lo stato — così il `(tv 90)` cambia solo il frame del marker, non quello che la tartaruga porterà al passo successivo (l'`(f 20)` che avanza in orizzontale). Vedrai `side-trip` in dettaglio nel § 8.5; qui basta sapere che permette di dotare un marker di un frame su misura senza disturbare il resto del path.

Le funzioni `end-post` e `mid-post` costruiscono ciascuna un cilindro nel suo frame canonico (asse del cilindro lungo l'heading), con la creation-pose spostata a un'estremità con `cp-f`. Sarà il `:align` di `on-anchors` a portare quei cilindri in piedi sui marker, ruotando il loro frame su quello che il path ha preparato.

### I tipi di pattern

Finora abbiamo usato la stringa-prefisso. `on-anchors` riconosce quattro tipi di pattern:

- **Stringa** (`"end-post-"`): matcha i marcatori il cui nome inizia con quel prefisso.
- **Regex** (`#"-l$"`): matcha sul nome del marcatore con `re-find`. Utile per pattern più sofisticati di un semplice prefisso, ad esempio marcatori che terminano con un certo suffisso.
- **Keyword** (`:base`): match esatto su un singolo marcatore.
- **Set** (`#{:front-left :front-right}`): matcha se il marcatore è uno di quelli elencati. Utile quando alcuni marcatori hanno ruoli speciali ma non condividono un prefisso comune.

I pattern si valutano nell'ordine in cui sono scritti, e per ogni marcatore vince il primo che matcha (niente fallthrough). Se nessun marcatore matcha un pattern, `on-anchors` emette un warning con l'elenco dei marcatori disponibili — utile per scoprire typo nei pattern. Se un marcatore non matcha alcun pattern viene silenziosamente saltato: filtrare solo un sottoinsieme è un uso legittimo.

### Attenzione ai pattern di naming

Un prefisso troppo corto può catturare marcatori che non vorresti. La stringa `"bracket"` cattura sia `:bracket-1` che `:bracket-spec` o `:end-bracket`. Usa prefissi distintivi (`"bracket-"` con il trattino finale) o regex ancorate (`#"^bracket-"`) quando c'è rischio di collisione.

L'ordine di iterazione sui marcatori è quello di inserimento nel path. Se il tuo body produce pezzi che dipendono dall'ordine (per esempio numerati progressivamente), tienine conto. Se i marcatori rappresentano un insieme di pezzi equivalenti, l'ordine non conta.

## 8.2 Creation-pose e creation-pose shifting

Ogni mesh ha un punto di ancoraggio: la posizione e l'orientamento della tartaruga al momento della creazione. È la *creation-pose*. Pensala come la maniglia del pezzo: quando in seguito scrivi `(attach mesh ...)`, la tartaruga afferra `mesh` proprio lì, e da lì la sposta, la ruota, la aggancia altrove.

Lo stesso vale nell'altro verso: se è la tua mesh a essere "ferma" e qualcun altro vi si aggancia (con `attach` + `move-to`), il pezzo ospite atterra sulla creation-pose della mesh ospitante. È lo stesso meccanismo visto da capo opposto: la creation-pose è il punto di contatto.

Il problema è che il punto di contatto di default non è sempre quello che ti serve. Le primitive (`box`, `cyl`, `sphere`, `cone`) hanno la creation-pose al centro della geometria. Le estrusioni e i loft ce l'hanno all'inizio del percorso. Se vuoi afferrare il pezzo in un punto diverso — un angolo, una faccia, l'estremità di un perno — devi spostare la creation-pose.

### Come funziona

La famiglia `cp-*` scorre la geometria sotto l'ancora (che resta ferma) in modo che un punto scelto venga a coincidere con essa:

`(cp-f n)` riancora al punto `+n` lungo l'heading. La geometria scorre all'indietro di `n`.

`(cp-u n)` riancora al punto `+n` lungo l'up. La geometria scorre verso il basso di `n`.

`(cp-rt n)` riancora al punto `+n` lungo il right. La geometria scorre a sinistra di `n`.

L'effetto si vede al momento dell'assemblaggio:

```clojure
;; Box afferrato per la faccia inferiore: si dispone verso l'alto
(def box-up
  (attach (box 40 40 40) (cp-u -20)))

;; Stessa box afferrata per la faccia superiore: si dispone verso il basso
(def box-top-anchor
  (attach (box 40 40 40) (cp-u 20)))
```

<!-- example-source: cp-shift-box
(register box-up (attach (box 40 40 40) (cp-u -20)))
(color 0x00ff00)
(register box-top-anchor (attach (box 40 40 40) (cp-u 20)))
-->

Registrati nello stesso punto, i due cubi si dispongono in colonna, combaciando sull'origine: `box-up` viene appeso per la faccia inferiore e cresce verso l'alto, `box-top-anchor` viene appeso per la faccia superiore e cade verso il basso. Stessa geometria, diverso punto di presa.

Lo stesso shift cambia anche dove atterra ciò che agganci *al* box: qualunque cosa attaccata a `box-top-anchor` arriva sulla sua faccia superiore. Il meccanismo è uno solo; cambia il lato della relazione da cui lo guardi.

### La creation-pose come pivot di rotazione

Una rotazione applicata dentro `attach` ruota il pezzo attorno alla creation-pose. Questo è il motivo principale per cui vale la pena spostare la creation-pose: non solo per posizionare correttamente, ma per ruotare attorno al punto giusto.


<!-- example-source: cp-shift-rotation
(def H 60)
(register default (attach (box 20 20 60) (tv 30)))
(rt 50)
(color 0xffff00)
(register shifted
  (attach
    (attach (box 20 20 60) (cp-f -30))
    (tv 30)))
-->
Il box giallo (shifted) ruota su un'estremità, quello blu (default) ruota sul centro.

Se una rotazione sembra sbagliata (il pezzo ruota attorno a un punto inaspettato, vola nello spazio), controlla prima la creation-pose. Quasi sempre il problema è lì.

### Quando usare cp-* e quando no

Se il tuo assemblaggio ha tre o quattro pezzi con punti di aggancio ovvi (il centro di un cilindro, l'inizio di un'estrusione), probabilmente non serve spostare la creation-pose. Se stai costruendo un assemblaggio parametrico dove i componenti si agganciano a uno skeleton con `move-to :align`, lo shift della creation-pose è quasi sempre necessario per far atterrare ogni pezzo nel punto giusto.

L'alternativa (lasciare la creation-pose al default e compensare con `(f ...)` o rotazioni al momento dell'assemblaggio) funziona, ma sparge la geometria nel codice. Con la creation-pose spostata nel punto giusto, la chiamata di assemblaggio resta pulita: `(attach component (move-to skel :at :mark :align))` e nient'altro.

## 8.3 Attach e move-to

`attach` è l'operazione fondamentale di posizionamento. Prende un pezzo già costruito e lo trasforma usando comandi tartaruga: sposta, ruota, scala lungo assi locali. Il risultato è una nuova mesh (o SDF) con la geometria trasformata; l'originale resta invariato.

### La forma base

I comandi dentro `attach` sono gli stessi della tartaruga — `f`, `th`, `tv`, `tr`, `rt`, `u`, `lt` — e si applicano alla *maniglia* del pezzo (la sua creation-pose, vedi § 8.2):

<!-- example-source: attach-basic
(register b (box 20))
;; Sposta in avanti e ruota
(register b2 (attach b (f 30) (th 45)))
;; Concatena quanti comandi servono
(register b3 (attach b (f 50) (tv 30) (rt 5) (f 20)))
-->

L'ordine conta: `(f 10) (th 45)` non equivale a `(th 45) (f 10)`. Il primo avanza e poi ruota; il secondo ruota e poi avanza in una direzione diversa.

### Attach!: la forma imperativa

`attach!` modifica direttamente una mesh registrata:

```clojure
(register b (box 20))
(attach! :b (f 30) (th 45))    ;; modifica :b in posto
```

Il nome è una keyword (`:b`, non `b`). Il pezzo nel registro viene sostituito con la versione trasformata. È comodo nella REPL per spostare un pezzo senza riscrivere tutto, ma nel codice di un modello è in genere meglio usare la forma funzionale `attach`: muta lo stato globale, e rende meno deterministica la riesecuzione del file.

### Move-to: il ponte fra due mesh

`attach` da solo muove un pezzo nel suo sistema di coordinate locale. Quando vuoi posizionare un pezzo *rispetto a un altro* — sopra un ospite, su un marcatore di uno skeleton, dentro lo slot di un componente — usi `move-to` come primo comando dentro `attach`.

`move-to` accetta come target una **mesh** o un **path**, e ha quattro forme che combinano due decisioni indipendenti: a *quale punto* del target andare, e se la mesh agganciata deve anche *ruotare* per allinearsi al frame del target.

#### Target = mesh

Le forme disponibili quando il target è una mesh registrata:

```clojure
;; Vai alla creation-pose del target. Il frame della tartaruga
;; adotta heading/up del target; la mesh agganciata NON viene ruotata.
(attach piece (move-to :host))

;; Vai al centroide del target. Il frame della tartaruga rimane invariato.
(attach piece (move-to :host :center))

;; Come il primo, ma in più la mesh agganciata viene ruotata
;; per allineare il suo frame a quello del target.
(attach piece (move-to :host :align))
```

L'esempio mostra le tre varianti affiancate. Gli ospiti sono cilindri verticali; il pezzo agganciato è sempre un disco che parte verticale.

<!-- example-source: move-to-pose
(color 0x0000ff)
(register host-A (attach (cyl 5 40) (rt -30) (tv 90)))
(color 0xff8800)
(register guest-A (attach (cyl 10 4) (move-to :host-A) (f 20)))

(color 0xffffff)
(register host-B (attach (cyl 5 40) (tv 90)))
(color 0xff8800)
(register guest-B (attach (cyl 10 4) (move-to :host-B :center) (f 20)))

(color 0x000000)
(register host-C (attach (cyl 5 40) (rt 30) (tv 90)))
(color 0xff8800)
(register guest-C (attach (cyl 10 4) (move-to :host-C :align) (f 20)))
-->

Nel caso A (cilindro blu): `(move-to :host-A)` porta la tartaruga sulla creation-pose di `host-A` (di default è al centro della mesh). Il `(f 20)` successivo avanza nel *frame della tartaruga*, che ora coincide con quello di `host-A` — quindi il disco si sposta lungo l'asse del cilindro (verticalmente, verso +Z). Ma il disco stesso non è stato ruotato: i suoi vertici sono ancora orientati come quando è stato costruito.

Nel caso B (cilindro bianco): `:center` porta la tartaruga al centroide della mesh ma *non* aggiorna il suo frame. Il `(f 20)` avanza nella direzione di partenza della tartaruga (+X di default), quindi il disco si sposta lateralmente, non lungo l'asse del cilindro.

Nel caso C (cilindro nero): `:align` fa entrambe le cose — sposta la tartaruga sulla creation-pose dell'ospite *e* ruota la mesh agganciata in modo che il suo frame combaci con quello del target. Risultato: il disco si appoggia ortogonalmente all'asse del cilindro.

#### Target = path

Quando il target è un path, c'è una differenza importante: i marcatori del path sono *posizioni assolute* rispetto al frame del path, e ignorano dove sta la tartaruga al momento dell'`attach`. La forma `(move-to path)` da sola non ha senso (un path nel suo complesso non ha una pose) ed è rifiutata; serve sempre `:at :marker` per scegliere un marcatore specifico.

Le forme disponibili sono due:

```clojure
;; Vai al marcatore :base del path. Il frame della tartaruga adotta
;; heading/up del marcatore; la mesh agganciata NON viene ruotata.
(attach piece (move-to skel :at :base))

;; Come sopra, ma in più la mesh agganciata viene ruotata
;; per allineare il suo frame a quello del marcatore.
(attach piece (move-to skel :at :base :align))
```

L'esempio definisce uno skeleton che cambia direzione, e mostra tre modi di usarlo: aggancio diretto, aggancio dopo aver mosso la tartaruga, e aggancio dentro `with-path`.

<!-- example-source: move-to-path
(color 0xffff00)
(def axle-skel
  (path (mark :base) (th -45) (f 30) (mark :mid) (th 90) (f 30) (mark :top)))
(def disk (cyl 15 4))
(register axle-stack
  (mesh-union
    (attach disk (move-to axle-skel :at :base))
    (attach disk (move-to axle-skel :at :mid))
    (attach disk (move-to axle-skel :at :top))))

(u 50)
(color 0x000000)
(def cn (cone 5 1 8))
(register axle-stack-offset
  (mesh-union
    (attach cn (move-to axle-skel :at :base))
    (attach cn (move-to axle-skel :at :mid :align))
    (attach cn (move-to axle-skel :at :top))))

(color 0xffffff)
(def cylinder (cyl 8 10))
(register axle-stack-replayed
  (with-path axle-skel
    (mesh-union
      (attach cylinder (move-to :base))
      (attach cylinder (move-to :mid :align))
      (attach cylinder (move-to :top)))))
-->

Il primo blocco (`axle-stack`) è il pattern di base: tre dischi agganciati ai tre marcatori dello skeleton. Senza `:align`, i dischi conservano il loro orientamento di costruzione (asse +X); il fatto che `:mid` e `:top` abbiano heading diversi non li ruota.

Il secondo blocco (`axle-stack-offset`) è preceduto da `(u 50)` (la tartaruga sale di 50 unità), ma i coni finiscono comunque dove erano i dischi, non 50 unità più in alto. Quando il target di `move-to` è un path, i marcatori sono assoluti: la posa corrente della tartaruga non conta. Nota anche `:align` sul cono in `:mid`: questo cono viene ruotato per allinearsi all'heading del marcatore (diagonale per via del `(th -45)` nel path), mentre gli altri due restano allineati a +X.

Il terzo blocco (`axle-stack-replayed`) usa `with-path`, che "ripianta" il path nella posa corrente della tartaruga al momento dell'entrata. Adesso il `(u 50)` conta: i cilindri appaiono 50 unità più in alto. Dentro `with-path` dobbiamo citare i marcatori per nome (`(move-to :base)` invece di `(move-to axle-skel :at :base)`): il fatto che stiamo lavorando su axle-skel lo abbiamo già detto con la `with-path`.

Lo stesso terzo blocco si può anche scrivere con `turtle :anchor`. Dentro `with-path`, `(turtle :base ...)` apre uno scope con la tartaruga posizionata e orientata sul marcatore `:base`; il body di quello scope può essere qualsiasi cosa — un `attach`, una primitiva nuda, una composizione di mesh, perfino una `scale` o un `mesh-difference`. Nell'esempio sotto ciascun marker ospita un disco diverso, e l'`attach` non c'è più:

```clojure
(u 50)
(register axle-stack-replayed
  (with-path axle-skel
    (mesh-union
     (turtle :base (scale (cyl 15 4) 1 1 1.5))
     (turtle :mid  (cyl 15 4))
     (turtle :top  (scale (cyl 15 4) 0.5 1 1)))))
```

(`scale` qui lavora sugli assi mondo: `1 1 1.5` allunga il disco di base lungo Z, `0.5 1 1` lo schiaccia in X sul top. La distinzione fra scale mondo e `stretch-*` locale la rivedremo più sotto.)

Le due forme — `move-to :base` dentro `attach`, oppure `turtle :base` come scope — producono geometrie posizionate allo stesso modo. La differenza pratica: `(move-to :base)` posiziona la tartaruga *dentro* l'`attach` corrente; `(turtle :base ...)` apre uno scope in cui ci si può stare per più operazioni, anche fuori da `attach`.

Un'avvertenza sulla semantica: la regola "i marcatori del path sono posizioni assolute" vale specificamente per `move-to ... :at ...`. Altri strumenti che incontrerai più avanti — `with-path`, `on-anchors`, `pin-path` — trattano invece i marcatori come *relativi* alla posa corrente della tartaruga, così che un path possa fare da template riusabile. La distinzione è deliberata: `move-to` è per agganci puntuali a uno skeleton specifico, gli altri sono per comporre assemblaggi.

### Come i marcatori finiscono su una mesh

Finora `move-to` con `:at` ha sempre cercato i marcatori in un path. Ma anche le mesh registrate possono avere marcatori: `attach-path` associa un path (con i suoi marcatori) a una mesh già registrata.

<!-- example-source: attach-path-mesh
(register column (cyl 5 60))
(attach-path :column (path (mark :base) (f 30) (mark :top)))
(register cap (attach (sphere 8) (move-to :column :at :top)))
-->

Il path viene agganciato alla creation-pose corrente della mesh (in questo caso al centro del cilindro), poi `mark` registra i suoi punti rispetto a quel frame. Da quel momento la mesh ha marcatori utilizzabili come quelli di uno skeleton.

I marcatori creati con `mark` dentro un `attach` su SDF sopravvivono automaticamente attraverso le operazioni booleane e la materializzazione (vedi cap. 12). Per le mesh, `attach-path` è la via esplicita per dichiarare marcatori "sopra" una geometria già costruita.

### Play-path: replay di un percorso

Se hai un percorso calcolato — magari restituito da una funzione — e vuoi usarlo per posizionare un pezzo dentro `attach`, `play-path` lo riproduce nel contesto giusto:

<!-- example-source: attach-play-path
(defn branch-path [l]
  (path (tv 90) (f (/ l 8)) (tv -90) (f l)))
(register Y (attach (sphere 3) (play-path (branch-path 30))))
-->

`play-path` risolve un problema tecnico: le funzioni che restituiscono path catturano i binding globali di `f`/`th`/`tv`, non quelli ridefiniti dentro `attach`. Senza `play-path` il path verrebbe ignorato.

### Stretch: scala lungo assi locali

Dentro `attach` (e solo lì) sono disponibili `stretch-f`, `stretch-rt`, `stretch-u` per scalare il pezzo lungo gli assi locali della tartaruga:

<!-- example-source: attach-stretch
(register b (box 20))

;; Raddoppia lungo l'heading
(register b2 (attach b (stretch-f 2)))

;; Ruota la tartaruga, poi scala lungo il nuovo heading
(register b3 (attach b (th 90) (stretch-f 2)))
-->

`stretch-*` è il complemento locale di `scale` (che lavora su assi mondo). Fuori da `attach` non sono disponibili. Se scrivi `(scale ...)` dentro un `attach`, Ridley segnala l'errore e ti indica `stretch-*`.

### Attach su SDF

`attach` funziona identicamente su mesh e SDF. Gli SDF sono il sistema di modellazione alternativo di Ridley, trattato nel capitolo 12. Se non li hai ancora incontrati, puoi saltare questo paragrafo: per ora basta sapere che `attach` è polimorfo, e funziona allo stesso modo su entrambi i tipi.

I comandi tartaruga vengono eseguiti uno per uno e ogni comando trasforma il nodo SDF direttamente. I marcatori registrati con `mark` dentro un `attach` su SDF sopravvivono attraverso le booleane SDF e attraverso la materializzazione (SDF → mesh).

## 8.4 Skeleton-driven assembly

Lo skeleton-driven assembly è il pattern completo per assemblare componenti multipli lungo una struttura portante. Combina le tre idee viste finora: marcatori (8.1), creation-pose (8.2), e attach con move-to (8.3).

### Il pattern

**1. Costruisci uno skeleton.** Un `path` che cammina lungo la struttura dell'assemblaggio, piazzando marcatori in ogni punto rilevante:

```clojure
(def axle-skel
  (path
   (mark :base)
   (f 30) (mark :mid)
   (f 30) (mark :top)))
```

**2. Modella ogni componente in modo indipendente.** Ogni pezzo nasce nel suo frame locale, con la creation-pose (la maniglia, § 8.2) nel punto in cui si aggancerà allo skeleton. Spesso questo richiede uno shift con `cp-*`: il default — centro per le primitive, inizio del percorso per le estrusioni — coincide raramente con il punto di aggancio desiderato.

**3. Assembla agganciando allo skeleton.** Hai tre modi: `attach` + `(move-to skel :at :mark)` per un aggancio puntuale (§ 8.3), `with-path` + `(move-to :mark)` (o `turtle :mark`) per agganciare più pezzi dentro uno scope (§ 8.3), e `on-anchors` (§ 8.1) quando vuoi distribuire pezzi su molti marcatori secondo un pattern.

Lo skeleton è la fonte di verità dell'assemblaggio. Sposta un marcatore, e ogni componente agganciato lo segue.

### Marcatori proporzionali

Invece di distanze fisse nel path, esprimi le posizioni come frazioni di una lunghezza di riferimento:

```clojure
(def H 60)

(def axle-skel
  (path
   (mark :base)
   (f (* 0.5 H)) (mark :mid)
   (f (* 0.5 H)) (mark :top)))
```

Ora cambiare `H` riscala l'intero assemblaggio. Utile quando lo stesso design deve esistere in più taglie, o quando una singola dimensione guida molti offset derivati.

### Sub-path e side-trip

Gli skeleton complessi si costruiscono componendo sub-path con `follow` e `side-trip`:

```clojure
(defn arm [side depth mname]
  (path
   (side-trip
    (th (if (pos? side) 90 -90))
    (f (abs side))
    (u (- depth))
    (mark mname))))

(def hinge-skel
  (path
   (mark :pin-axis)
   (doseq [[i k] (map-indexed vector bracket-axials)]
     (follow (bracket-pair i k)))))
```

`side-trip` esegue una deviazione e torna al punto di partenza. `follow` inserisce un sub-path nello skeleton. I marcatori piazzati dentro un sub-path sono visibili dall'intero skeleton.

### Assemblaggio gerarchico con with-path e turtle

`with-path` e `turtle` sono l'alternativa a `attach` + `move-to` quando i componenti sono estrusioni che seguono segmenti dello skeleton. Invece di costruire i pezzi altrove e poi agganciarli, li costruisci direttamente nello scope dello skeleton:

<!-- example-source: skeleton-with-path
(def arm-sk (path (mark :shoulder) (f 15) (mark :elbow) (f 12) (mark :wrist)))
(register upper
  (with-path arm-sk
    (extrude (circle 1.5) (path-to :elbow))))
(register lower
  (with-path arm-sk
    (turtle :elbow
      (extrude (circle 1.2) (path-to :wrist)))))
-->

`turtle :elbow` apre uno scope in cui la tartaruga è posizionata al marcatore `:elbow` (posizione, heading, up); `path-to :wrist` estrude da lì fino al marcatore successivo. I pezzi nascono direttamente nelle coordinate mondo, senza bisogno di snapping post-hoc.

Questo approccio è naturale quando i componenti sono estrusioni che seguono segmenti dello skeleton. Il pattern `attach` + `move-to` è più adatto quando i componenti sono pezzi già costruiti indipendentemente.

### Quando NON usare uno skeleton

Se l'assemblaggio è due pezzi incollati insieme, o se non c'è una "spina dorsale" che collega le parti, uno skeleton è eccessivo. Una coppia di `attach` innestati basta. Usa lo skeleton quando i componenti condividono un asse, una catena, o una struttura portante, e quando le posizioni relative contano più di quelle assolute.

## 8.5 Stack di stato e branching

Quando costruisci un assemblaggio, ogni comando tartaruga modifica lo stato globale: posizione, heading, up. Se vuoi piazzare due componenti che partono dallo stesso punto ma vanno in direzioni diverse, hai bisogno di un modo per "salvare" lo stato, esplorare un ramo, e tornare indietro.

### Side-trip

`side-trip` è il meccanismo principale. All'interno di un `path`, esegue una sequenza di comandi e poi ripristina lo stato della tartaruga al punto di partenza:

<!-- example-source: side-trip-branching
(def skel
  (path
   (mark :root)
   (side-trip (th 30) (f 20) (mark :branch-a))
   (side-trip (th -30) (f 20) (mark :branch-b))
   (f 40) (mark :tip)))
(follow-path skel)
-->

Dopo la prima `side-trip`, la tartaruga è di nuovo a `:root`. La seconda `side-trip` parte dallo stesso punto. Il `(f 40)` finale avanza dalla posizione originale, non da un ramo.

Il pattern classico è lo skeleton a Y: una spina che si biforca. Ogni ramo è un `side-trip` che piazza marcatori, poi lo spine continua dritto.

### Branching annidato

`side-trip` si annida:

```clojure
(def tree-skel
  (path
   (mark :trunk)
   (f 30)
   (side-trip
    (th 30)
    (f 15)
    (mark :branch-1)
    (side-trip
      (th 20) (f 10) (mark :twig-1a))
    (side-trip
      (th -20) (f 10) (mark :twig-1b)))
   (f 30)
   (mark :top)))
```

Ogni livello di annidamento salva e ripristina il suo stato indipendentemente. Utile per strutture ad albero, grafi di parentela, o qualsiasi topologia che non è una linea retta.

### With-path come scope

`with-path` è uno scope che lega la tartaruga a un path registrato. All'interno, `goto` e `path-to` navigano fra i marcatori. All'uscita, lo stato della tartaruga torna a quello precedente l'ingresso:

```clojure
;; Lo stato della tartaruga qui è X
(with-path my-skeleton
  ;; Dentro: la tartaruga parte dall'inizio del path
  (turtle :some-mark
    ;; ... costruisci qualcosa ...
    ))
;; Lo stato della tartaruga qui è di nuovo X
```

Questo lo rende un meccanismo di branching implicito: entri nel path, fai quello che devi, esci, e sei dove eri prima. Combinato con più `with-path` in sequenza, puoi navigare skeleton diversi senza che si influenzino.

## 8.6 Un esempio completo: portachiavi a parete

Fin qui hai visto le idee del capitolo una alla volta. Questa sezione le mette insieme in un singolo oggetto realmente stampabile: un portachiavi che si fissa al muro con due tasselli e ospita un numero variabile di portachiavi a etichetta intercambiabili.

L'oggetto è composto da due pezzi separati. Il **dispenser** (`D`) è un nastro a spirale, a sezione quadrata, lungo il quale sono saldati N socket: ogni socket ospita un singolo portachiavi e ha al centro un anello a forma di C che impedisce alla chiave di sganciarsi. Ai due capi del nastro ci sono i fori per i tasselli. Il **portachiavi** (`SK`) è il pezzo intercambiabile: un'etichetta a forma di compressa, con un foro a un'estremità per la chiave. Si infila nel socket facendolo scorrere sotto l'anello.

Due path organizzano la geometria. `KP` ("key path") descrive la struttura geometrica condivisa fra portachiavi e socket: davanti alla pose iniziale i marker della chiave (l'etichetta, i due cerchi alle estremità); dietro — raggiunti con un `side-trip` che ruota di 180° sul piano — i marker del socket (il punto in cui sale il semianello, il corpo, le due braccia laterali che lo prolungano). È lo stesso path a guidare la costruzione delle due metà dell'oggetto. `KHP` ("key holder path") descrive il nastro a spirale che ospita i socket, con un marcatore per ogni socket e due marcatori speciali ai capi per i tasselli.

<!-- example-source: portachiavi
(def tolerance 0.4)
(def label-length 40)
(def label-depth 3)
(def label-h 25)
(def trace-w 5)
(def socket-h (+ label-h (/ trace-w 2)))

(def KP
  (path
    (mark :key-label)
    (side-trip (u (/ label-length 2)) (mark :key-circle))
    (side-trip (u (- (/ label-length 2))) (mark :key-ring))
    (side-trip (th 180) (u (* label-length 0.2)) (mark :socket-up)
               (u (- (/ label-length 2))) (mark :socket-body)
      (rt (- (/ socket-h 2) (/ trace-w 4))) (mark :socket-bar-1)
      (rt (- (- socket-h (/ trace-w 2)))) (mark :socket-bar-2))))

(defn socket-ring [key-path]
  (with-path key-path
    (attach
      (mesh-difference
        (extrude (circle (/ socket-h 2)) (f trace-w))
        (extrude (circle (/ (- socket-h trace-w) 2)) (f trace-w))
        (attach (extrude (rect (inc socket-h) label-h) (f (+ trace-w 2)))
                (u (/ label-h -2)) (f -1)))
      (move-to :socket-up :align))))

(defn single-key [key-path]
  (with-path key-path
    (mesh-difference
      (mesh-hull
        (attach (extrude (rect label-h label-length) (f label-depth)) (move-to :key-label))
        (attach (extrude (circle (/ label-h 2)) (f label-depth)) (move-to :key-circle))
        (attach (extrude (circle (/ label-h 2)) (f label-depth)) (move-to :key-ring)))
      (attach (extrude (circle (/ (- label-h trace-w) 2)) (f (+ label-depth 2)))
              (move-to :key-ring) (f -1)))))

(defn tolerance-ratio [n]
  (/ (+ n tolerance tolerance) n))

(defn socket [key-path]
  (mesh-difference
    (mesh-union
      (socket-ring key-path)
      (on-anchors key-path
        "socket-bar"  :align (attach (extrude (rect (/ trace-w 2) label-length) (f trace-w)))
        "socket-body" :align (mesh-difference
                               (attach (box socket-h label-length (* trace-w 2)))
                               (attach (box (- socket-h trace-w) (inc label-length) (inc (* trace-w 2)))))))
    (attach (single-key KP) (f tolerance)
      (stretch-f (tolerance-ratio label-depth))
      (stretch-rt (tolerance-ratio label-h)))))

(register SK (attach (single-key KP) (tv 90) (f 50)))

(def KHP
  (path
    (mark :start)
    (th 90)
    (dotimes [i 8]
      (arc-h (* (+ i 3) 20) 80) (mark (keyword (str "key-" i))))
    (th -90)
    (mark :end)))

(defn washer []
  (mesh-difference
    (cyl 10 trace-w)
    (cyl 1.5 trace-w)))

(register D
  (mesh-union
    (attach (extrude (rect trace-w trace-w) (play-path KHP)))
    (on-anchors KHP
      "key-"           (attach (socket KP) (tv -90) (f (/ trace-w 2)))
      #{:start :end}   (attach (washer) (tv -90)))))
-->

L'esempio usa anche `mesh-hull`, `mesh-difference`, `stretch-f`/`stretch-rt`, `play-path`: sono operatori che vedrai più avanti, qui ti chiedo di prenderli sulla fiducia e concentrarti sul ruolo dei due path e di `on-anchors`.

### KP come template riusabile

`KP` non viene mai usato direttamente per costruire qualcosa: è una mappa di posizioni che funzioni diverse interpretano in modi diversi. `single-key` ne usa `:key-label`, `:key-circle` e `:key-ring` per sagomare il portachiavi. `socket-ring` ne usa `:socket-up` per agganciare l'anello a C. `socket` ne usa `:socket-bar-1`, `:socket-bar-2` e `:socket-body` per costruire il corpo del socket.

Questo è il pattern dei componenti riusabili: un path descrive *dove* sono le parti di un oggetto, funzioni distinte vi costruiscono sopra *cosa* va in ogni parte. Le funzioni prendono `key-path` come argomento, lo aprono con `with-path` e poi citano i marker per nome.

Vale la pena notare che `KP` e `KHP` usano i path in due modi qualitativamente diversi. `KP` è il path di un *singolo oggetto*: le posizioni dei suoi marker sono in relazione stretta con la geometria che vi si costruirà sopra, e le funzioni `single-key`/`socket-ring`/`socket` conoscono nello specifico quali marker esistono e cosa farne. Cambiare la struttura di `KP` (un marker in meno, un orientamento diverso) significa modificare anche le funzioni: path e codice sono *intrecciati*, perché le relazioni geometriche fra le parti dell'oggetto sono espresse da entrambi insieme.

`KHP` invece è il path di un *layout*: una sequenza di stazioni indipendenti su cui si dispongono oggetti che non hanno relazioni geometriche fra loro. Il percorso è determinato interamente dal path, e il codice che vi distribuisce socket e rondelle non ha bisogno di sapere altro sui marker se non il loro nome. Sostituire `KHP` con un altro path — una linea retta, un cerchio, una griglia — ridisporrebbe i socket in modo diverso senza richiedere alcuna modifica al resto.

La stessa distinzione tornerà fra poco, vista da un altro angolo, quando guarderemo le due `on-anchors`.

Un'altra proprietà che vale la pena notare è che tutte le misure sono espresse come `def` in cima al file (`label-length`, `label-h`, `trace-w`, `tolerance`...) e il path le riusa per calcolare le posizioni dei marker. L'oggetto è così interamente parametrico: cambia `label-length` e tutto — path, socket, chiave, fori, tolleranze — si ridimensiona di conseguenza. È il payoff del pattern parametrico applicato ai marker: non si modificano numeri in venti posti diversi, si modifica un `def` in alto.

### Le due on-anchors

Dentro `socket`, `on-anchors` distribuisce *sotto-pezzi all'interno di un singolo componente*: due braccia laterali (pattern `"socket-bar"`) che prolungano il semianello, e un corpo cavo (pattern `"socket-body"`, che matcha l'unico marker col prefisso) da cui verrà poi scavata la sagoma della chiave. I marker sono parti anatomiche del socket. Tutte le clausole usano `:align` perché le braccia e il corpo devono seguire l'orientamento locale del marker, non l'orientamento di costruzione.

Dentro `D`, `on-anchors` distribuisce *componenti completi lungo una struttura portante*: un socket per ogni marker `"key-"` (otto socket lungo la spirale), una rondella sui due marker speciali `:start` e `:end` (i fori per i tasselli). Il pattern set `#{:start :end}` raggruppa due marker che hanno ruolo simile ("capo del nastro") ma non condividono un prefisso, ed è esattamente il caso in cui il set è la scelta naturale.

Lo stesso costrutto, due usi che sembrano lontani e che invece sono la stessa cosa: per ogni marker di un path, esegui un body in uno scope posizionato sul marker. È la composabilità a fare la differenza.
