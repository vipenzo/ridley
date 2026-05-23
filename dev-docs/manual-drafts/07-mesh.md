PLACEHOLDER<!-- Convenzioni accertate per il capitolo 7:
- Una mesh Ridley è una mappa Clojure con :vertices, :faces, :creation-pose, e campi opzionali (:primitive, :face-groups, :material, ::raw-arrays).
- :vertices è un vettore di vettori [x y z], :faces è un vettore di vettori [i j k] (indici nei vertici).
- ::raw-arrays è una cache di sola uscita per Three.js e export; non viene usata nel path inverso (mesh → Manifold).
- :creation-pose registra la posa della turtle al momento della creazione.
- mesh-diagnose è puro ClojureScript, niente Manifold WASM.
- manifold? e mesh-status richiedono Manifold WASM.
- merge-vertices è il fix più comune per mesh non-manifold dopo CSG.
- mesh-smooth richiede input manifold (watertight).
- inset-face e scale-face NON esistono come binding SCI.
- Codice esempi in inglese, prosa in italiano. No em-dash.
-->

# 7. Mesh

## 7.1 Cos'è una mesh

Fino a qui hai costruito oggetti in diversi modi: chiamando una primitiva, estrudendo una forma 2D lungo un percorso, facendo rivolvere un profilo, oppure componendo forme con le shape-fn. In tutti questi casi il risultato finale è lo stesso: una *mesh*.

Una mesh è la rappresentazione 3D che Ridley usa per tutto ciò che appare nel viewport, viene esportato in STL o 3MF, e partecipa alle operazioni booleane. È il formato in cui converge ogni tecnica di costruzione.

In Ridley una mesh è una mappa Clojure. Puoi ispezionarla, stamparla nella REPL, confrontarla con `=`, passarla a una funzione, metterla in un atom. Non è un oggetto opaco, non è un puntatore a memoria nativa: è un dato, come tutto il resto.

La struttura minima è questa:

```clojure
{:type :mesh
 :vertices [[x y z] [x y z] ...]
 :faces    [[i j k] [i j k] ...]}
```

`:vertices` è un vettore di punti nello spazio. Ogni punto è un vettore `[x y z]`. `:faces` è un vettore di triangoli, dove ogni triangolo è un vettore di tre indici che puntano dentro `:vertices`. Un cubo, per esempio, ha 8 vertici e 12 facce triangolari (due triangoli per ogni faccia quadrata del cubo).

Oltre a questi due campi essenziali, una mesh porta con sé altri dati. `:creation-pose` registra la posizione e l'orientamento della tartaruga al momento della creazione: serve a `attach` per riposizionare la mesh correttamente quando la agganci a un'altra. `:primitive` ricorda quale primitiva l'ha generata (`:box`, `:sphere`, `:cylinder`...), e `:face-groups` mappa nomi simbolici come `:top`, `:bottom`, `:front` ai gruppi di triangoli corrispondenti. Non tutte le mesh hanno questi campi: una mesh prodotta da un'operazione booleana non ha `:face-groups` predefiniti, per esempio.

### Guardare dentro una mesh

Il modo più diretto per capire una mesh è chiedere a Ridley di raccontartela. `mesh-diagnose` analizza la topologia di una mesh e restituisce una mappa con le informazioni chiave:

```clojure
(def m (box 20))
(mesh-diagnose m)
;; => {:n-verts 8
;;     :n-faces 12
;;     :n-edges 18
;;     :edge-incidence-distribution {2 18}
;;     :open-edges 0
;;     :non-manifold-edges 0
;;     :degenerate-faces 0
;;     :euler-characteristic 2
;;     :is-watertight? true}
```

<!-- example-source: mesh-diagnose-box
(register m (box 20))
(println (mesh-diagnose m))
-->

I numeri raccontano la storia della mesh. Un cubo ha 8 vertici, 12 facce (triangolari), 18 spigoli. La `:edge-incidence-distribution` dice quanti spigoli sono condivisi da quante facce: `{2 18}` significa che tutti i 18 spigoli sono condivisi da esattamente 2 facce. È il caso sano: ogni spigolo ha una faccia da un lato e una dall'altro, nessun buco, nessuna sovrapposizione.

`:open-edges` conta gli spigoli che appartengono a una sola faccia: sono i bordi di un buco. `:non-manifold-edges` conta gli spigoli condivisi da tre o più facce: sono giunzioni a T, muri doppi, geometria che non può esistere come oggetto fisico. `:is-watertight?` è `true` quando entrambi sono zero.

La caratteristica di Eulero (`:euler-characteristic`) è un invariante topologico: per una sfera chiusa vale 2, per un toro vale 0. Se il numero non corrisponde a quello che ti aspetti, la mesh ha qualche problema strutturale.

`mesh-diagnose` è puro ClojureScript: non ha bisogno di Manifold, gira ovunque, ed è abbastanza leggero da poterlo chiamare liberamente durante lo sviluppo.

### Manifold e non-manifold

La parola "manifold" compare spesso quando si lavora con le mesh, e vale la pena chiarirla subito perché determina cosa puoi fare con un oggetto.

Una mesh è *manifold* (o *watertight*) quando rappresenta un solido chiuso: nessun buco, nessuno spigolo condiviso da più di due facce, nessun triangolo degenere. È l'equivalente digitale di un oggetto fisico che potresti riempire d'acqua senza che esca.

Le primitive (`box`, `sphere`, `cyl`, `cone`), le estrusioni chiuse, i loft, i revolve e i risultati delle operazioni booleane sono tutti manifold. È il caso normale.

<!-- TODO: chiarire quali costruzioni Ridley producono effettivamente mesh non-manifold e in quali circostanze. Verificare il comportamento di shell con voronoi/lattice/checkerboard e di thickness-fn a zero rispetto a Manifold. Oggi la Spec dice che Manifold le rifiuta con status 2, ma il motivo topologico preciso non è accertato. -->

Non tutte le mesh lo sono. Alcune costruzioni possono produrre geometrie che Manifold non accetta: giunzioni a T, muri sovrapposti, bordi condivisi da più di due facce. Quando succede, `mesh-diagnose` ti dice esattamente qual è il problema.

La distinzione conta perché alcune operazioni richiedono input manifold. `mesh-union`, `mesh-difference`, `mesh-intersection` e `mesh-smooth` funzionano solo su mesh watertight: Manifold (la libreria WASM che esegue queste operazioni) rifiuta le altre. Se provi a fare una booleana su una mesh non-manifold, ottieni un errore.

Il punto importante è che "non-manifold" non significa "rotto". Una mesh che Manifold rifiuta può comunque renderizzarsi correttamente nel viewport, esportarsi in STL, e la maggior parte degli slicer (Bambu Studio, OrcaSlicer, PrusaSlicer, Cura) la accetta senza problemi: riparano automaticamente i piccoli gap e affettano il risultato. Semplicemente, quella mesh non può partecipare a certe operazioni.

Per verificare rapidamente lo stato di una mesh hai due strumenti:

```clojure
(manifold? m)     ;; => true / false
(mesh-status m)   ;; => informazioni dettagliate sullo stato
```

<!-- example-source: mesh-manifold-check
(register solid (box 20))
(println "manifold?" (manifold? solid))
(println "status:" (mesh-status solid))
-->

`manifold?` è il check veloce: restituisce `true` se la mesh è watertight, `nil` altrimenti (non `false`, perché sotto il cofano Manifold WASM lancia un'eccezione che viene catturata). `mesh-status` dà più dettaglio. Se una mesh non passa e non capisci perché, `mesh-diagnose` è lo strumento di triage: ti dice esattamente quanti open-edge e non-manifold-edge ci sono, e da lì puoi decidere come intervenire.

### Riparare una mesh

Il caso più comune di mesh non-manifold è il risultato di una sequenza di operazioni booleane che produce vertici quasi-duplicati. Due vertici a distanza infinitesimale l'uno dall'altro confondono la topologia: lo spigolo tra loro viene contato come condiviso da un numero sbagliato di facce.

`merge-vertices` risolve il problema collassando i vertici che distano meno di un epsilon (per default `1e-6`):

```clojure
;; CSG result that fails mesh-smooth
(def result (mesh-difference a b))
(manifold? result)          ;; => false

;; After merging near-duplicate vertices
(def fixed (merge-vertices result))
(manifold? fixed)           ;; => true
```

<!-- example-source: mesh-merge-vertices
(def a (box 30))
(def b (attach (box 20) (f 10)))
(def result (mesh-difference a b))
(println "before:" (manifold? result))
(register fixed (merge-vertices result))
(println "after:" (manifold? fixed))
-->

Non è una garanzia universale, ma è il primo tentativo che vale sempre la pena fare. Se la mesh ha problemi strutturali più seri (buchi veri, geometria sovrapposta), servono strategie diverse che vedremo nelle sezioni successive.

`mesh-diagnose` resta lo strumento di triage. Se dopo `merge-vertices` la mesh è ancora non-manifold, guarda `:open-edges` e `:non-manifold-edges` nella diagnosi: ti dicono se il problema sono buchi (open) o giunzioni anomale (non-manifold), e da lì scegli la strada giusta.

## 7.2 Operazioni booleane 3D

Nel capitolo 2 hai già usato le booleane per comporre primitive: unire, sottrarre, intersecare. Qui le riprendiamo con più dettaglio, perché lavorare con le mesh significa inevitabilmente lavorare con le booleane, e conoscerne i pattern e i limiti fa la differenza tra un modello che si costruisce in pochi secondi e uno che ti fa perdere un'ora.

### Le tre operazioni

`mesh-union` fonde due o più mesh in un unico solido. Le parti che si sovrappongono vengono risolte: il risultato ha una superficie esterna pulita, senza facce interne.

`mesh-difference` sottrae una o più mesh da una mesh base. La prima mesh è il pezzo da cui si toglie, le successive sono gli utensili che scavano.

`mesh-intersection` tiene solo il volume condiviso fra le mesh.

Tutte e tre accettano due o più argomenti, oppure un vettore:

```clojure
;; Two arguments
(mesh-union a b)
(mesh-difference base tool)
(mesh-intersection a b)

;; Variadic
(mesh-union a b c d)
(mesh-difference base t1 t2 t3)

;; From a vector (handy with for)
(mesh-union [a b c d])
(mesh-difference [base t1 t2])
```

<!-- example-source: bool-variadic
(def base (box 40 40 20))
(def holes
  (for [i (range 4)]
    (attach (cyl 5 30) (th (* i 90)) (f 12))))
(register drilled (mesh-difference base (mesh-union holes)))
-->

La forma con vettore è comoda quando le mesh vengono da un `for` o da un `map`: puoi passare direttamente il risultato senza doverlo scompattare.

Per `mesh-difference`, l'ordine conta: il primo elemento è sempre la base, gli altri sono gli utensili. Per `mesh-union` e `mesh-intersection` l'ordine è irrilevante.

### Controllare lo stato di una mesh

Le booleane richiedono input manifold. Se una mesh non lo è, l'operazione fallisce. Due strumenti per verificare prima:

```clojure
(manifold? m)     ;; => true / false
(mesh-status m)   ;; => informazioni dettagliate
```

<!-- example-source: bool-status-check
(register cube (box 20))
(println "manifold?" (manifold? cube))
(println "status:" (mesh-status cube))
-->

Se una booleana fallisce e non capisci perché, `mesh-diagnose` (sezione 7.1) è il passo successivo.

### Solidify: ripulire auto-intersezioni

A volte un'estrusione o un loft producono geometria che si auto-interseca: il profilo attraversa sé stesso in una curva stretta, oppure due segmenti di un percorso si sovrappongono. La mesh risultante non è pulita e le booleane successive la rifiutano.

`solidify` risolve il problema facendo passare la mesh attraverso Manifold, che ricalcola la superficie eliminando le intersezioni interne:

```clojure
(def messy (extrude (circle 8) (f 20) (th 120) (f 20)))
(def clean (solidify messy))
```

<!-- example-source: bool-solidify
(def messy (extrude (circle 8) (f 20) (th 120) (f 20)))
(register clean (solidify messy))
-->

Non serve sempre: la maggior parte delle estrusioni e dei loft producono mesh già pulite. Ma quando una booleana fallisce su una mesh che *sembra* a posto, `solidify` è il primo tentativo da fare.

### Convex hull

`mesh-hull` calcola l'inviluppo convesso di una o più mesh: il più piccolo solido convesso che le contiene tutte. È un'operazione generativa, non una booleana nel senso classico: non taglia e non fonde, costruisce una forma nuova *attorno* alle mesh di partenza.

```clojure
;; Capsule from two spheres
(register s1 (sphere 10))
(f 30)
(register s2 (sphere 10))
(register capsule (mesh-hull s1 s2))

;; From a vector
(mesh-hull [s1 s2 s3])
```

<!-- example-source: bool-hull-capsule
(def s1 (sphere 10))
(def s2 (attach (sphere 10) (f 30)))
(register capsule (mesh-hull s1 s2))
-->

L'hai già vista nel capitolo 2 per fare basette con bordi morbidi. In generale, `mesh-hull` è utile ogni volta che vuoi una forma che avvolga un insieme di pezzi con una superficie liscia e convessa.

### Concat-meshes: combinare senza booleane

Non sempre serve una booleana per mettere insieme più mesh. `concat-meshes` unisce i vertici e le facce di più mesh in un'unica mesh, senza fare nessun calcolo di intersezione:

```clojure
(concat-meshes m1 m2 m3)        ; variadic
(concat-meshes [m1 m2 m3])      ; from a vector
```

Il risultato non è manifold (le mesh si compenetrano senza che le intersezioni vengano risolte), ma questo non è un problema quando il passo successivo è una booleana. Manifold accetta geometria concatenata come operando, e la risolve durante l'operazione.

Il vantaggio è la velocità. Considera un caso comune: forare un disco con 12 cilindri disposti in cerchio.

```clojure
;; Slow: 11 sequential unions, then one difference
(register plate-slow
  (mesh-difference
    (cyl 30 5)
    (mesh-union
      (for [i (range 12)]
        (attach (cyl 2 8) (th (* i 30)) (f 20))))))

;; Fast: one concat, then one difference
(register plate-fast
  (mesh-difference
    (cyl 30 5)
    (concat-meshes
      (for [i (range 12)]
        (attach (cyl 2 8) (th (* i 30)) (f 20))))))
```

<!-- example-source: bool-concat-perf
(register plate
  (mesh-difference
    (cyl 30 5)
    (concat-meshes
      (for [i (range 12)]
        (attach (cyl 2 8) (th (* i 30)) (f 20))))))
-->

Nella versione lenta, `mesh-union` esegue 11 operazioni booleane in sequenza per fondere i 12 cilindri, e poi una dodicesima per sottrarli dal disco. Nella versione veloce, `concat-meshes` incolla i cilindri in tempo lineare, e `mesh-difference` fa una sola operazione booleana. Per pattern a griglia o ad anello con decine di pezzi, la differenza è un ordine di grandezza.

Lo stesso trucco funziona in addizione: se devi aggiungere molti pezzi a una base, passa `(concat-meshes (for ...))` come secondo argomento di `mesh-union` invece di fare N unioni sequenziali.

### Quando le booleane sono lente

Le operazioni booleane in Ridley passano attraverso Manifold WASM. Ogni chiamata converte le mesh operande in oggetti Manifold nativi, esegue l'operazione, e riconverte il risultato in una mappa Clojure. Tre booleane in fila sulla stessa mesh ricostruiscono tre volte l'oggetto Manifold.

In pratica questo non è un problema per la maggior parte dei modelli. Le booleane diventano lente quando ne concateni molte in sequenza sullo stesso pezzo, tipicamente dentro un loop. Il pattern `concat-meshes` descritto sopra è la soluzione principale. Se il modello resta comunque lento, l'altra strategia è ridurre la risoluzione delle mesh coinvolte: un cilindro a 16 segmenti costa molto meno di uno a 64 in una booleana.

Per le union variandiche, Manifold usa internamente un algoritmo tree-union che riduce in O(n log n) invece che in sequenza. Questo significa che `(mesh-union [a b c d e f])` è più veloce di `(-> a (mesh-union b) (mesh-union c) (mesh-union d) (mesh-union e) (mesh-union f))`: preferisci la forma con vettore quando unisci molti pezzi.

## 7.3 Raccordi e smussi 3D

Dopo una serie di booleane, un modello ha tipicamente spigoli vivi a 90° dove i volumi si incontrano. In un CAD tradizionale applicheresti un fillet o un chamfer selezionando gli spigoli uno per uno. In Ridley ci sono due strade: lavorare per direzione con `fillet` e `chamfer`, oppure smussare globalmente con `mesh-smooth`.

### Chamfer: smusso piatto

`chamfer` taglia gli spigoli vivi con un piano inclinato. Il risultato è uno smusso piatto, come una limatura a 45°.

```clojure
(-> (box 30 30 20) (chamfer :top 2))
(-> (box 30 30 20) (chamfer :all 1.5))
(-> (box 30 30 20) (chamfer :all 1.5 :angle 60))
```

<!-- example-source: chamfer-box
(register chamfered (-> (box 30 30 20) (chamfer :top 2)))
-->

Il primo argomento dopo la mesh è un *direction selector* che sceglie quali spigoli lavorare: `:top`, `:bottom`, `:left`, `:right`, `:up`, `:down`, oppure `:all` per tutti. Il secondo è la distanza di taglio. `:angle` controlla la soglia dell'angolo diedrale: solo gli spigoli più acuti di quel valore vengono toccati (default 80°).

### Fillet: raccordo arrotondato

`fillet` fa la stessa cosa ma produce un raccordo curvo al posto del taglio piatto. Ha un parametro in più, `:segments`, che controlla quanti passi usa per approssimare la curva:

```clojure
(-> (box 30 30 20) (fillet :top 3 :segments 8))
(-> (box 30 30 20) (fillet :all 2 :segments 8))
```

<!-- example-source: fillet-box
(register filleted (-> (box 30 30 20) (fillet :top 3 :segments 8)))
-->

Più segmenti producono un raccordo più liscio ma con più triangoli. Per la stampa 3D, 6-8 segmenti sono di solito sufficienti.

`:blend-vertices true` aggiunge un raccordo sferico ai vertici dove convergono tre spigoli:

```clojure
(-> (box 30 30 20) (fillet :top 3 :blend-vertices true))
```

### Chamfer-edges e chamfer-prisms: il livello basso

`chamfer` e `fillet` usano i direction selector per scegliere gli spigoli. Se hai bisogno di un controllo più fine, `chamfer-edges` lavora direttamente sulla topologia della mesh: trova tutti gli spigoli il cui angolo diedrale supera una soglia e li smussa via CSG.

```clojure
(chamfer-edges mesh 2)                         ; default: angle 80
(chamfer-edges mesh 2 :angle 60)               ; soglia più bassa
(chamfer-edges mesh 2 :where #(pos? (first %))) ; solo spigoli con x > 0
```

<!-- example-source: chamfer-edges-where
(register half-chamfered
  (chamfer-edges (box 30 30 20) 2 :where #(pos? (second %))))
-->

`:where` è un predicato che riceve le coordinate `[x y z]` di entrambi gli endpoint dello spigolo: solo gli spigoli per cui entrambi i punti soddisfano il predicato vengono smussati. Con questo puoi lavorare selettivamente su metà di un pezzo, su una regione specifica, o su spigoli che soddisfano qualsiasi condizione geometrica.

`chamfer-prisms` restituisce i prismi triangolari intermedi (uno per spigolo) senza applicare il taglio booleano. Utile per ispezionare dove cadrebbe lo smusso prima di applicarlo:

```clojure
(def prisms (chamfer-prisms (box 30) 2))
;; => [prism1 prism2 ...] or nil
```

Puoi filtrare i prismi, trasformarli, o usarli per costruire smussi asimmetrici.

### Mesh-smooth: arrotondamento globale

`mesh-smooth` è un approccio diverso: invece di lavorare su spigoli selezionati, arrotonda globalmente tutti gli spigoli della mesh che hanno un angolo diedrale inferiore a una soglia. È il modo più veloce per dare un aspetto organico a un pezzo uscito da una pipeline CSG.

```clojure
(mesh-smooth m)                              ; default: sharp-angle 100, refine 3
(mesh-smooth m :sharp-angle 120 :refine 4)   ; più liscio, più denso
(mesh-smooth m :sharp-angle 60)              ; conserva gli angoli > 60°
(mesh-smooth m :sharp-angle 180)             ; arrotonda tutto
```

<!-- example-source: mesh-smooth-csg
(register rounded-widget
  (-> (mesh-difference (box 40 40 20) (cyl 12 30))
      (mesh-smooth :sharp-angle 100 :refine 3)))
-->

Il parametro chiave è `:sharp-angle`: gli spigoli con angolo diedrale *maggiore* di questo valore restano vivi, gli altri vengono arrotondati. Il default di Ridley è 100° (quello di Manifold è 60°): per modelli procedurali 90-120° funziona meglio, perché gli spigoli a 90° delle booleane vengono arrotondati invece che conservati.

`:refine` controlla la suddivisione: ogni triangolo diventa `n²` sotto-triangoli. Il default 3 produce 9 triangoli per ogni triangolo originale. Aumentare migliora la qualità visiva ma il costo cresce quadraticamente. Parti dai default e alza solo se vedi ancora sfaccettature.

`:smoothness` (0-1) controlla il raccordo sugli spigoli che sopravvivono come "sharp": 0 li lascia perfettamente vivi, 1 li arrotonda completamente.

`mesh-smooth` richiede input manifold. Se la mesh non lo è, l'operazione fallisce. Per mesh non-manifold che hanno bisogno di un trattamento estetico simile, vedi `mesh-laplacian` più avanti.

### Mesh-refine: suddivisione senza smoothing

`mesh-refine` suddivide i triangoli senza spostare i vertici: la forma resta identica, ma la mesh diventa più densa.

```clojure
(mesh-refine m 2)    ; ogni triangolo → 4 sotto-triangoli
(mesh-refine m 3)    ; ogni triangolo → 9 sotto-triangoli
```

<!-- example-source: mesh-refine
(register dense-box (mesh-refine (box 20) 2))
-->

Da solo non cambia nulla visivamente. È utile come passo preparatorio quando un'operazione successiva ha bisogno di più vertici su cui lavorare (tipicamente `warp`, che deforma spostando i vertici esistenti).

### Mesh-laplacian: smoothing che preserva la topologia

`mesh-laplacian` è uno smoothing di Taubin: sposta i vertici sugli spigoli più acuti per smussarli, senza cambiare la topologia della mesh. A differenza di `mesh-smooth`, non aggiunge vertici e non richiede input manifold.

```clojure
(mesh-laplacian m)
(mesh-laplacian m :iterations 20 :feature-angle 120)
```

<!-- example-source: mesh-laplacian
(register smoothed (mesh-laplacian (box 20) :iterations 15))
-->

Il comportamento è selettivo: muove solo i vertici sugli spigoli il cui angolo diedrale è sotto `:feature-angle` (default 150°), lasciando le superfici piatte intatte. Questo lo rende particolarmente utile per ammorbidire l'aliasing a gradini sulle shell perforate e su altre mesh esteticamente ruvide che non puoi rendere manifold.

I parametri del ciclo di Taubin (`:lambda` 0.5, `:mu` -0.53) sono calibrati per smussare senza restringere il volume. Più iterazioni producono un effetto più marcato.

### Mesh-simplify: ridurre i triangoli

`mesh-simplify` riduce il numero di triangoli di una mesh per edge-collapse, cercando di preservarne la forma:

```clojure
(mesh-simplify m 0.5)    ; target: 50% dei triangoli originali
(mesh-simplify m 0.25)   ; target: 25%
```

<!-- example-source: mesh-simplify
(register simple-sphere (mesh-simplify (sphere 20 64 32) 0.3))
-->

Il rapporto è una frazione del conteggio originale. Il risultato è approssimativo: la mesh semplificata perde dettagli fini ma conserva la forma generale. Utile per ridurre il peso di mesh molto dense (tipicamente quelle uscite da `mesh-smooth` con `:refine` alto) prima dell'export.

### Scegliere lo strumento giusto

La scelta tra questi strumenti dipende da cosa vuoi ottenere:

Se vuoi arrotondare spigoli specifici (quelli in alto, quelli a destra, quelli in una regione), usa `fillet` con un direction selector o `chamfer-edges` con `:where`. Hai il controllo preciso su dove agisce l'operazione.

Se vuoi dare un aspetto organico globale a un pezzo CSG, usa `mesh-smooth`. È veloce da scrivere e il risultato è visivamente convincente. L'unico vincolo è che la mesh deve essere manifold.

Se la mesh non è manifold ma vuoi ammorbidire le sfaccettature visibili, usa `mesh-laplacian`. Non aggiunge geometria, non cambia topologia, e funziona su qualsiasi mesh.

Se hai bisogno di un semplice smusso piatto per rompere gli spigoli vivi (comune nella stampa 3D per evitare l'effetto "elephant foot"), usa `chamfer`. È l'operazione più leggera.

## 7.4 Modificare le facce di una mesh

Le primitive di Ridley nascono con facce nominate: un `box` ha `:top`, `:bottom`, `:front`, `:back`, `:left`, `:right`; un `cyl` ha `:top`, `:bottom`, `:side`; una `sphere` ha `:surface`. Questi nomi ti permettono di selezionare una faccia e lavorarci sopra: spostarla, estruderne una copia, estrarne il contorno come forma 2D, o semplicemente evidenziarla per capire dove sei.

Questa sezione copre l'intero ciclo: trovare le facce, ispezionarle, modificarle, e riestrarre la loro forma.

Una nota sul nome: `attach-face` non va confuso con `attach`. `attach` posiziona una mesh intera nello spazio (cap. 8). `attach-face` muove i vertici di una singola faccia di una mesh. Operazioni diverse, scala diversa, ma il principio è lo stesso: selezioni l'oggetto (mesh o faccia) e poi ci operi sopra con comandi tartaruga.

### Facce nominate e facce scoperte

Le primitive hanno face group predefiniti. Per vederli:

```clojure
(def b (box 20))
(face-ids b)        ;; => (:top :bottom :front :back :left :right)
(list-faces b)      ;; => [{:id :top :normal [...] :center [...] ...} ...]
```

<!-- example-source: face-ids-box
(register b (box 20))
(println (face-ids b))
-->

`face-ids` restituisce la lista dei nomi. `list-faces` restituisce una lista di mappe con tutte le informazioni: normale, centro, heading, e il campo `:id` che identifica la faccia.

Per informazioni dettagliate su una singola faccia:

```clojure
(get-face b :top)       ;; info base
(face-info b :top)      ;; info completa: area, spigoli, posizioni dei vertici
```

Le mesh prodotte da operazioni booleane non hanno face group predefiniti: la fusione/sottrazione ricalcola la geometria e i nomi originali si perdono. Per queste mesh servono le query geometriche.

### Trovare facce per direzione

`find-faces` seleziona le facce la cui normale è allineata con una direzione. Le direzioni sono relative alla creation-pose della mesh, non al mondo:

```clojure
(find-faces mesh :top)                          ; facce allineate con heading
(find-faces mesh :bottom)                       ; opposto a heading
(find-faces mesh :up)                           ; allineate con up
(find-faces mesh :all)                          ; tutte le facce raggruppate
```

<!-- example-source: find-faces-direction
(register result (mesh-difference (box 40 40 20) (cyl 10 30)))
(println "top faces:" (count (find-faces result :top)))
-->

`:threshold` controlla quanto deve essere stretta l'allineamento (default 0.7, dove 1.0 è perfetto). `:where` aggiunge un predicato sulla mappa della faccia:

```clojure
(find-faces mesh :top :threshold 0.9)           ; solo facce molto allineate
(find-faces mesh :top :where #(> (:area %) 100)) ; solo facce grandi
```

### Trovare facce per posizione

Quando sai *dove* si trova la faccia che cerchi, ma non in quale direzione guarda:

```clojure
(face-at mesh [0 0 10])          ;; faccia il cui piano passa più vicino al punto
(face-nearest mesh [10 0 0])     ;; faccia il cui centroide è più vicino al punto
```

`face-at` ragiona per piano (proiezione), `face-nearest` per distanza del centroide. In pratica la differenza conta raramente: usa `face-nearest` come default.

### Trovare la faccia più grande

```clojure
(largest-face mesh)              ;; la faccia più grande della mesh
(largest-face mesh :top)         ;; la più grande nella direzione :top
```

<!-- example-source: largest-face
(register b (box 30 20 10))
(println "largest face:" (:id (largest-face b)))
-->

Tutte le funzioni di selezione restituiscono mappe con un campo `:id` che puoi passare a `attach-face`, `clone-face`, `face-shape`, e alle altre operazioni.

### Auto face groups

Se una mesh non ha face group (tipico dopo una booleana), puoi generarli automaticamente:

```clojure
(auto-face-groups mesh)     ;; raggruppa i triangoli per adiacenza coplanare
(ensure-face-groups mesh)   ;; aggiunge :face-groups solo se mancanti
```

`auto-face-groups` analizza la topologia e raggruppa i triangoli adiacenti che giacciono sullo stesso piano. Il risultato è una mesh con `:face-groups` popolati, su cui puoi usare `find-faces` e le altre funzioni di selezione.

`ensure-face-groups` è la versione idempotente: se la mesh ha già face group, la restituisce invariata.

### Evidenziare le facce

Per capire visivamente quale faccia stai selezionando:

```clojure
(highlight-face mesh :top)                  ; evidenzia permanente (arancione)
(highlight-face mesh :top 0xff0000)         ; colore custom (rosso)

(flash-face mesh :top)                      ; evidenzia 2 secondi e sparisce
(flash-face mesh :top 3000)                 ; durata custom (ms)
(flash-face mesh :top 2000 0x00ff00)        ; durata + colore

(clear-highlights)                          ; rimuove tutte le evidenziazioni
```

<!-- example-source: flash-face
(register b (box 20))
(flash-face b :top 2000 0x00ff00)
-->

`flash-face` è particolarmente utile nella REPL: evidenzi, guardi, e l'highlight sparisce da solo.

### Attach-face: muovere una faccia

`attach-face` sposta i vertici di una faccia lungo la sua normale o li trasforma in posto. Non crea nuova geometria: i vertici esistenti si muovono.

```clojure
(register b (box 20))

;; sposta la faccia top verso l'alto di 5 unità
(register b (attach-face b :top (f 5)))

;; operazioni multiple
(register b (attach-face b :top (f 10) (inset 3)))
```

<!-- example-source: attach-face-move
(register b (box 20))
(register b (attach-face b :top (f 5)))
-->

Le operazioni disponibili dentro `attach-face` (e dentro `clone-face`) includono i comandi tartaruga e tre operazioni specifiche per le facce:

`(f dist)` muove la faccia lungo la sua normale. Valori positivi la allontanano dalla mesh, negativi la avvicinano.

`(th angle)`, `(tv angle)`, `(tr angle)` cambiano la direzione della normale, esattamente come farebbero con la tartaruga. Utili per inclinare una faccia durante un'estrusione a gradini.

`(inset dist)` restringe la faccia verso il suo centro. È l'equivalente di un offset 2D negativo applicato al contorno della faccia.

`(scale factor)` scala la faccia uniformemente rispetto al suo centro.

Nota: `inset` e `scale` esistono solo come operazioni dentro `attach-face` e `clone-face`. Non sono funzioni standalone che puoi chiamare a top-level.

### Clone-face: estrudere una faccia

`clone-face` duplica una faccia e la collega alla mesh con nuova geometria laterale. È l'estrusione locale: la faccia originale resta dov'era, una copia si sposta lungo la normale, e le pareti laterali chiudono il gap.

```clojure
(register b (box 20))

;; estrudi la faccia top verso l'alto
(register b (clone-face b :top (f 10)))

;; estrusione a gradini
(register b
  (-> b
      (clone-face :top (f 5))
      (clone-face :top (inset 3) (f 5))))
```

<!-- example-source: clone-face-step
(register b (box 20))
(register b
  (-> b
      (clone-face :top (f 5))
      (clone-face :top (inset 3) (f 5))))
-->

Il pattern `inset` + `f` è il più comune: restringi la faccia e poi la estrudi, creando un gradino rientrante. Concatenando più `clone-face` in un threading `->` puoi costruire profili a più livelli direttamente sulla mesh, senza passare per estrusioni separate e booleane.

### Face-shape: estrarre il contorno di una faccia

`face-shape` estrae il contorno di una faccia come forma 2D, pronta per essere usata in un'estrusione o in qualsiasi altra operazione che accetta una shape. Restituisce una mappa con la shape e la posa (posizione e orientamento) della faccia nello spazio:

```clojure
(def top-info (face-shape mesh (:id (largest-face mesh :top))))
;; => {:shape <ridley-shape>
;;     :pose {:pos [x y z] :heading [hx hy hz] :up [ux uy uz]}}
```

<!-- example-source: face-shape-extrude
(register b (box 30 30 10))
(def top-info (face-shape b (:id (largest-face b :top))))
(turtle (:pose top-info)
  (register tower (extrude (:shape top-info) (f 40))))
-->

La posa usa `:pos` (non `:position`) per compatibilità con la macro `turtle`. Per usarla, apri un `turtle` scope con la posa della faccia ed estrudi la shape da lì:

```clojure
(turtle (:pose top-info)
  (register tower (extrude (:shape top-info) (f 40))))
```

Questo pattern è utile per costruire sopra il risultato di una booleana: trovi la faccia, ne estrai la forma, e la usi come profilo per una nuova estrusione posizionata esattamente dove serve.

## 7.5 Sezioni

A volte hai bisogno di tornare dal 3D al 2D: estrarre la sezione di una mesh a una certa altezza, catturare la silhouette di un oggetto vista da una direzione, o ricavare il contorno di una faccia per riusarlo come profilo. Ridley offre tre operazioni per questo, tutte guidate dalla posa della tartaruga.

### Slice-mesh: sezione su un piano

`slice-mesh` taglia una mesh con il piano definito dalla posizione e dalla direzione della tartaruga. Il vettore heading è la normale del piano. Il risultato è un vettore di shape 2D (una mesh può avere più contorni disgiunti sullo stesso piano).

```clojure
(register cup (revolve (shape (f 20) (th -90) (f 30) (th -90) (f 15))))

;; posiziona la tartaruga sul piano di taglio
(tv 90) (f 15)
(def sections (slice-mesh :cup))
```

<!-- example-source: slice-mesh-cup
(register cup (revolve (shape (f 20) (th -90) (f 30) (th -90) (f 15))))
(tv 90) (f 15)
(stamp (slice-mesh :cup))
-->

Le coordinate delle shape risultanti sono nel sistema locale del piano di taglio: l'asse X è il right della tartaruga, l'asse Y è il suo up. Le shape hanno `:preserve-position? true`, il che significa che `stamp` le renderizza nella posizione corretta nel viewport:

```clojure
(stamp (slice-mesh :cup))    ; visualizza la sezione sul piano di taglio
```

`slice-mesh` accetta sia una mesh che un nome registrato (keyword), oppure un nodo SDF (che viene materializzato automaticamente).

Un uso tipico è il reverse engineering di un profilo: hai una mesh, vuoi riusare una sezione come base per un nuovo pezzo. Per esempio, un O-ring che segue il bordo di un contenitore, o un rinforzo che abbraccia un profilo irregolare.

### Slice-at-plane: taglio senza tartaruga

Se il piano di taglio viene da un calcolo e non dalla posa della tartaruga, `slice-at-plane` è la forma esplicita:

```clojure
;; taglio orizzontale a Z=90
(slice-at-plane :cup [0 0 1] [0 0 90])

;; taglio verticale a X=0 con base esplicita
(slice-at-plane :cup [1 0 0] [0 0 0] [0 1 0] [0 0 1])
```

<!-- example-source: slice-at-plane
(register cup (revolve (shape (f 20) (th -90) (f 30) (th -90) (f 15))))
(stamp (slice-at-plane :cup [0 0 1] [0 0 15]))
-->

Il primo argomento è la mesh (o keyword), il secondo è la normale del piano, il terzo è un punto sul piano. Opzionalmente puoi passare i vettori right e up per controllare la base locale delle shape risultanti. Senza di essi, Ridley sceglie una base ortogonale automaticamente.

Restituisce lo stesso tipo di risultato di `slice-mesh`: un vettore di shape 2D con `:preserve-position? true`.

### Project-mesh: silhouette

`project-mesh` proietta una mesh sul piano perpendicolare alla direzione della tartaruga, restituendo il contorno della silhouette come shape 2D. La differenza con `slice-mesh` è che `slice-mesh` dà la sezione *su* un piano, mentre `project-mesh` dà l'ombra *del* l'oggetto visto lungo una direzione.

```clojure
(project-mesh mesh)           ; silhouette lungo heading
(project-mesh :neck)          ; per nome registrato
(project-mesh (sdf-blend a b 5))  ; anche su SDF
```

<!-- example-source: project-mesh-silhouette
(register b (box 30 20 10))
(tv 90)
(stamp (project-mesh :b))
-->

Stesse convenzioni di `slice-mesh`: heading è la direzione di proiezione, right/up diventano gli assi locali della shape, i buchi sono preservati.

Il caso d'uso classico è estrarre un footprint 2D da un oggetto 3D per costruirci sopra. Per esempio, prendere la silhouette dall'alto di un pezzo, allargarla leggermente, e usarla come negativo per scavare una tasca di alloggiamento:

```clojure
(def footprint
  (turtle (tv 90)
    (let [s (project-mesh my-piece)
          bigger (scale-shape s 1.05)]
      (extrude bigger (f 5)))))
```

## 7.6 Warp: deformazione spaziale

Le operazioni viste finora lavorano sulla topologia della mesh: tagliano, fondono, arrotondano, estraggono sezioni. `warp` fa una cosa diversa: deforma i vertici di una mesh muovendoli nello spazio, senza cambiare la connettività dei triangoli. È lo strumento per effetti organici, ammaccature, rigonfiamenti, torsioni, e in generale per qualsiasi forma che non nasce da un profilo geometrico preciso.

### Come funziona

`warp` prende tre argomenti: una mesh, un volume di influenza, e una funzione di deformazione. Solo i vertici che cadono dentro il volume vengono toccati. I vertici al bordo del volume hanno un effetto attenuato (falloff hermite), quelli fuori restano fermi.

```clojure
(warp mesh volume deform-fn)
```

Il volume è una mesh ordinaria usata solo per i suoi bounds: `(sphere 25)`, `(box 30 30 20)`, `(cyl 12 40)`. Puoi posizionarlo con `attach`:

```clojure
;; volume spostato in avanti di 15
(warp mesh (attach (sphere 15) (f 15)) (inflate 5))
```

<!-- example-source: warp-inflate-basic
(register bumpy
  (warp (box 40) (sphere 25) (inflate 5) :subdivide 2))
-->

### Preset di deformazione

Ridley offre sette preset che coprono i casi più comuni:

`(inflate amount)` spinge i vertici verso l'esterno lungo le loro normali. Crea rigonfiamenti, bolle, rilievi.

`(dent amount)` è il contrario: spinge verso l'interno. Crea ammaccature, incavi, impronte.

`(attract strength)` tira i vertici verso il centro del volume. Strength va da 0 (nessun effetto) a 1 (tutti al centro).

`(twist angle)` ruota i vertici attorno a un asse, con l'angolo che cresce linearmente lungo l'asse. Per volumi cilindrici e conici l'asse è rilevato automaticamente; per altri volumi puoi specificarlo: `(twist 90 :x)`, `(twist 90 :y)`, `(twist 90 :z)`.

`(squash axis)` schiaccia i vertici verso un piano perpendicolare all'asse. `(squash :z)` appiattisce in Z. Con un secondo argomento puoi controllare quanto: `(squash :z 0.5)` è a metà strada tra piatto e invariato.

`(roughen amplitude)` aggiunge rumore lungo le normali, creando una superficie irregolare. Con due argomenti, `(roughen amplitude frequency)`, controlli anche la frequenza spaziale del rumore.

```clojure
;; organic bump on a box
(register bumpy (warp (box 40) (sphere 25) (inflate 5) :subdivide 2))

;; dent on a sphere
(register dented (warp (sphere 30 32 16) (attach (sphere 10) (f 15)) (dent 3)))

;; twisted cylinder
(register twisted-cyl (warp (cyl 10 40 32) (cyl 12 40) (twist 90)))

;; roughened surface
(register rough (warp (sphere 20 32 16) (sphere 22) (roughen 2 3)))
```

<!-- example-source: warp-presets
(register bumpy (warp (box 40) (sphere 25) (inflate 5) :subdivide 2))
(register dented (warp (sphere 30 32 16) (attach (sphere 10) (f 15)) (dent 3)))
(register twisted-cyl (warp (cyl 10 40 32) (cyl 12 40) (twist 90)))
(register rough (warp (sphere 20 32 16) (sphere 22) (roughen 2 3)))
-->

### Concatenare deformazioni

Puoi passare più funzioni di deformazione a una singola chiamata di `warp`:

```clojure
(warp mesh volume (inflate 3) (roughen 1 4))
```

### Subdivide: aggiungere risoluzione prima di deformare

`warp` muove i vertici esistenti. Se la mesh è a bassa risoluzione (un box ha solo 8 vertici), la deformazione non ha abbastanza punti su cui lavorare e il risultato sembra sfaccettato. L'opzione `:subdivide` aggiunge vertici prima di deformare:

```clojure
(warp (box 40) (sphere 25) (inflate 5) :subdivide 2)
```

Ogni passo di suddivisione divide ogni triangolo in 4 sotto-triangoli (split ai punti medi degli spigoli). Due passi producono 16 sotto-triangoli per ogni triangolo originale. Il costo cresce in fretta, ma per le primitive a bassa risoluzione uno o due passi fanno la differenza.

La suddivisione è limitata ai triangoli che cadono dentro il volume: i triangoli fuori restano invariati. Nota che `:subdivide` elimina i `:face-groups` dalla mesh risultante.

In alternativa, puoi usare `mesh-refine` (sezione 7.3) come passo separato prima del warp. La differenza è che `mesh-refine` suddivide tutta la mesh, `:subdivide` solo la zona interessata.

### Deformazioni custom

I preset coprono i casi comuni, ma `warp` accetta qualsiasi funzione con la firma giusta:

```clojure
(fn [pos local-pos dist normal vol] -> new-pos)
```

`pos` è la posizione del vertice nello spazio mondo `[x y z]`. `local-pos` è la posizione normalizzata nel volume, con ogni asse in `[-1, 1]`. `dist` è la distanza normalizzata dal centro del volume (0 al centro, 1 al bordo). `normal` è la normale stimata del vertice. `vol` è una mappa con i bounds del volume.

Non devi usare tutti i parametri. Una deformazione custom che sposta i vertici verso l'alto in funzione della loro distanza dal centro:

```clojure
(warp mesh volume
  (fn [pos _local _dist _normal _vol]
    (let [[x y z] pos
          r (Math/sqrt (+ (* x x) (* y y)))]
      [x y (+ z (* 3 (Math/exp (- (* r r 0.01)))))])))
```

<!-- example-source: warp-custom
(register gaussian-bump
  (warp (mesh-refine (box 40 40 5) 3) (sphere 25)
    (fn [pos _ _ _ _]
      (let [[x y z] pos
            r (Math/sqrt (+ (* x x) (* y y)))]
        [x y (+ z (* 3 (Math/exp (- (* r r 0.01)))))]))))
-->

Il falloff hermite (da `smooth-falloff`) è già applicato dal sistema prima di chiamare la tua funzione: i vertici al bordo del volume ricevono un effetto ridotto automaticamente. Non devi gestire il blend tu stesso.

### Quando usare warp vs altre tecniche

`warp` è la scelta giusta quando l'effetto che vuoi non si esprime con un profilo geometrico. Un rigonfiamento organico su un pezzo meccanico, una superficie irregolare che simula materiale naturale, una torsione localizzata: questi effetti si descrivono più facilmente come "sposta i vertici così" che come "estrudi questa forma lungo quest'altro percorso".

Per deformazioni globali e uniformi (torsione di un pezzo intero, rastremazione), le shape-fn del capitolo 6 sono spesso più naturali: la deformazione è parte della definizione della forma, non un'operazione post-hoc. `warp` dà il meglio quando la deformazione è *localizzata* a una regione specifica di un pezzo già costruito.