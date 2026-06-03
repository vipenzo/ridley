<!--
Convenzioni accertate per il capitolo 7:
- Una mesh Ridley è una mappa Clojure con :vertices, :faces, :creation-pose, e campi opzionali (:primitive, :face-groups, :material, ::raw-arrays).
- :vertices è un vettore di vettori [x y z], :faces è un vettore di vettori [i j k] (indici nei vertici).
- ::raw-arrays è una cache di sola uscita per Three.js e export; non viene usata nel path inverso (mesh → Manifold).
- :creation-pose registra la posa della turtle al momento della creazione.
- mesh-diagnose è puro ClojureScript, niente Manifold WASM.
- manifold? e mesh-status richiedono Manifold WASM.
- merge-vertices è il fix più comune per mesh non-manifold dopo CSG.
- inset-face e scale-face NON esistono come binding SCI.
- Codice esempi in inglese, prosa in italiano. No em-dash.
-->

# 7. Mesh


## 7.1 Cos'è una mesh

Fino a qui hai costruito oggetti in diversi modi: chiamando una primitiva, estrudendo una forma 2D lungo un percorso, facendo rivolvere un profilo, oppure componendo forme con le shape-fn. In tutti questi casi il risultato finale è lo stesso: una *mesh*.

Una mesh è la rappresentazione 3D che Ridley usa per tutto ciò che appare nel viewport, viene esportato in STL o 3MF, e partecipa alle operazioni booleane. È il formato in cui converge ogni tecnica di costruzione.

In Ridley una mesh è una mappa Clojure. Puoi ispezionarla, stamparla nella REPL, confrontarla con `=`, passarla a una funzione, salvarla in una variabile con `def`. Non è un oggetto opaco, non è un puntatore a memoria nativa: è un dato, come tutto il resto.

La struttura minima è questa:

```clojure
{:type :mesh
 :vertices [[x y z] [x y z] ...]
 :faces    [[i j k] [i j k] ...]}
```

`:vertices` è un vettore di punti nello spazio. Ogni punto è un vettore `[x y z]`. `:faces` è un vettore di triangoli, dove ogni triangolo è un vettore di tre indici che puntano dentro `:vertices`. Un cubo, per esempio, ha 8 vertici e 12 facce triangolari (due triangoli per ogni faccia quadrata del cubo).

Oltre a questi due campi essenziali, una mesh porta con sé altri dati. `:creation-pose` registra la posizione e l'orientamento della tartaruga al momento della creazione: serve a `attach` per riposizionare la mesh correttamente quando la agganci a un'altra. `:primitive` ricorda quale primitiva l'ha generata (`:box`, `:sphere`, `:cylinder`...), e `:face-groups` mappa nomi simbolici come `:top`, `:bottom`, `:front` ai gruppi di triangoli corrispondenti. Non tutte le mesh hanno questi campi: una mesh prodotta da un'operazione booleana non ha `:face-groups` predefiniti, per esempio.

Quando `:creation-pose` non compare, vale il default: `{:position [0 0 0] :heading [1 0 0] :up [0 0 1]}`, cioè la tartaruga all'origine, che guarda lungo X con l'up lungo Z. È il caso di una primitiva costruita senza spostare la tartaruga, come il `(box 20)` qui sotto.

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

Non tutte le mesh lo sono. Vertici distinti ma spazialmente coincidenti confondono la topologia: lo spigolo fra due vertici quasi-coincidenti viene contato come condiviso da un numero sbagliato di facce. Capita soprattutto unendo geometrie senza una booleana (`concat-meshes`, che accosta le mesh senza risolverne i confini), importando una mesh esterna cucita male, o come residuo di lunghe catene di operazioni in virgola mobile. `merge-vertices` (più sotto) li fonde e di solito ripristina la mesh come manifold. Quando una mesh non risulta manifold, `mesh-diagnose` aiuta a capire da quale parte guardare.

Restano due fonti di incertezza che vale la pena menzionare. La prima è la natura stessa delle shape-fn componibili (cap. 6): la loro espressività è il loro punto di forza, ma significa anche che è impossibile garantire a priori che ogni combinazione di shell, thickness-fn custom e parametri estremi produca una mesh manifold. In pratica si prova, si verifica con `mesh-diagnose`, e se serve si interviene con `merge-vertices` o `solidify`. La seconda è che Ridley è un sistema complesso e bug residui sono sempre possibili: se una costruzione che secondo questo manuale dovrebbe produrre una mesh manifold non lo fa, vale la pena segnalare il caso.

La distinzione conta perché alcune operazioni richiedono input manifold. `mesh-union`, `mesh-difference` e `mesh-intersection` funzionano solo su mesh watertight: Manifold (la libreria WASM che esegue queste operazioni) rifiuta le altre. Se provi a fare una booleana su una mesh non-manifold, ottieni un errore.

Il punto importante è che "non-manifold" non significa "rotto". Una mesh che Manifold rifiuta può comunque renderizzarsi correttamente nel viewport, esportarsi in STL, e la maggior parte degli slicer (Bambu Studio, OrcaSlicer, PrusaSlicer, Cura) la accetta senza problemi: riparano automaticamente i piccoli gap e affettano il risultato. Semplicemente, quella mesh non può partecipare a certe operazioni.

Per verificare rapidamente lo stato di una mesh hai due strumenti:

```clojure
(manifold? m)     ;; => true / nil
(mesh-status m)   ;; => informazioni dettagliate sullo stato
```

<!-- example-source: mesh-manifold-check
(register solid (box 20))
(println "manifold?" (manifold? solid))
(println "status:" (mesh-status solid))
-->

`manifold?` è il check veloce: restituisce `true` se la mesh è watertight, `nil` altrimenti (non `false`, perché sotto il cofano Manifold WASM lancia un'eccezione che viene catturata). `mesh-status` dà più dettaglio. Se una mesh non passa e non capisci perché, `mesh-diagnose` è lo strumento di triage: ti dice esattamente quanti open-edge e non-manifold-edge ci sono, e da lì puoi decidere come intervenire.

### Riparare una mesh

Gli shell con aperture (`:lattice`, `:voronoi`, `:checkerboard`, o thickness-fn che vanno a zero) **non** rientrano fra questi casi: il loft li sigilla da sé, perché dove lo spessore va a zero la parete esterna e quella interna collassano sullo stesso punto, e la mesh esce già manifold. Restano invece a rischio le mesh assemblate con `concat-meshes` o importate dall'esterno.

Le booleane 3D (`mesh-union`, `mesh-difference`, `mesh-intersection`) producono di norma output già manifold: Manifold gestisce internamente i quasi-duplicati. Quando una booleana fallisce, di solito il problema è nell'**input**, non nell'output.

`merge-vertices` collassa i vertici che distano meno di un epsilon (per default `1e-6`), saldando le giunzioni rimaste aperte e ripristinando spesso la manifoldness:

<!-- example-source: mesh-merge-vertices :no-run
;; m è una mesh con vertici coincidenti ma distinti
;; (assemblata con concat-meshes, importata, …)
(def fixed (merge-vertices m))
(println (boolean (manifold? fixed)))
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


La forma con vettore è comoda quando le mesh vengono da un `for` o da un `map`: puoi passare direttamente il risultato senza doverlo scompattare.

Quando hai un caso misto — una mesh singola da una parte e un vettore di mesh dall'altra, come tipicamente succede con `mesh-difference` quando i tool vengono da un `for` — la forma a vettore non basta da sola: devi prima mettere la mesh singola davanti al vettore. La via più diretta è `into`:

<!-- example-source: bool-into-vector -->
```clojure
(def base (box 40 40 20))
(def cuts
  (for [i (range 4)]
    (attach (cyl 5 30) (th (* i 90)) (f 12))))

(register drilled (mesh-difference (into [base] cuts)))
```

Per `mesh-difference`, l'ordine conta: il primo elemento è sempre la base, gli altri sono gli utensili. Per `mesh-union` e `mesh-intersection` l'ordine è irrilevante.

### Controllare lo stato di una mesh

Le booleane richiedono input manifold. Se una mesh non lo è, l'operazione fallisce. Due strumenti per verificare prima:

```clojure
(manifold? m)     ;; => true / nil
(mesh-status m)   ;; => informazioni dettagliate
```

<!-- example-source: bool-status-check
(register cube (box 20))
(println "manifold?" (manifold? cube))
(println "status:" (mesh-status cube))
-->

Se una booleana fallisce e non capisci perché, `mesh-diagnose` (sezione 7.1) è il passo successivo.

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

Il risultato è una mesh che contiene più pezzi nello stesso oggetto, senza che le intersezioni tra loro vengano risolte. Funziona come ingresso a una booleana solo se i pezzi non si compenetrano: se due cilindri si sovrappongono nello spazio, `concat-meshes` produce una mesh con auto-intersezioni che Manifold rifiuta. Per pezzi separati (come i 12 cilindri disposti in cerchio dell'esempio sotto), `concat-meshes` è perfetto.

Il vantaggio è la velocità. Considera un caso comune: forare un disco con 12 cilindri disposti in cerchio.

```clojure
;; Slow: 11 sequential unions, then one difference
(register plate-slow
  (mesh-difference
    (cyl 30 5)
    (mesh-union
      (for [i (range 12)]
        (attach (cyl 2 20) (tr (* i 30)) (rt 20) )))))

```

<!-- example-source: bool-concat-perf
;; Fast: one concat, then one difference
(register plate-fast
  (mesh-difference
    (cyl 30 5)
    (concat-meshes
      (for [i (range 12)]
        (attach (cyl 2 20) (tr (* i 30)) (rt 20))))))
-->

Nella versione lenta, `mesh-union` esegue 11 operazioni booleane in sequenza per fondere i 12 cilindri, e poi una dodicesima per sottrarli dal disco. Nella versione veloce, `concat-meshes` incolla i cilindri in tempo lineare, e `mesh-difference` fa una sola operazione booleana. Per pattern a griglia o ad anello con decine di pezzi, la differenza è un ordine di grandezza.

Lo stesso trucco funziona in addizione: se devi aggiungere molti pezzi a una base, passa `(concat-meshes (for ...))` come secondo argomento di `mesh-union` invece di fare N unioni sequenziali.

### Quando le booleane sono lente

Le operazioni booleane in Ridley passano attraverso Manifold WASM. Ogni chiamata converte le mesh operande in oggetti Manifold nativi, esegue l'operazione, e riconverte il risultato in una mappa Clojure. Tre booleane in fila sulla stessa mesh ricostruiscono tre volte l'oggetto Manifold.

In pratica questo non è un problema per la maggior parte dei modelli. Le booleane diventano lente quando ne concateni molte in sequenza sullo stesso pezzo, tipicamente dentro un loop. Il pattern `concat-meshes` descritto sopra è la soluzione principale. Se il modello resta comunque lento, l'altra strategia è ridurre la risoluzione delle mesh coinvolte: un cilindro a 16 segmenti costa molto meno di uno a 64 in una booleana.

Per le union variandiche, Manifold usa internamente un algoritmo tree-union che riduce in O(n log n) invece che in sequenza. Questo significa che `(mesh-union [a b c d e f])` è più veloce di `(-> a (mesh-union b) (mesh-union c) (mesh-union d) (mesh-union e) (mesh-union f))`: preferisci la forma con vettore quando unisci molti pezzi.

## 7.3 Raccordi e smussi 3D

Dopo una serie di booleane, un modello ha tipicamente spigoli vivi a 90° dove i volumi si incontrano. In un CAD tradizionale applicheresti un fillet o un chamfer selezionando gli spigoli uno per uno. In Ridley si lavora per direzione con `fillet` e `chamfer`.

Una premessa onesta: smussare gli spigoli di una mesh arbitraria è un problema notoriamente difficile, che anche i CAD commerciali risolvono solo parzialmente (Fusion 360, Onshape e altri falliscono regolarmente con messaggi tipo "fillet/chamfer encountered a non-manifold edge"). `chamfer` e `fillet` in Ridley funzionano bene sui casi comuni — primitive e booleane semplici — ma possono produrre risultati sbagliati su geometrie complesse, in particolare quando gli spigoli sono vicini ad altre parti del modello. Per questi casi il manuale indica come riconoscere il problema, come limitarlo con il parametro `:angle`, e quando conviene cambiare strategia usando gli SDF (cap. 12), che producono smussi morbidi per costruzione.

### Chamfer: smusso piatto

`chamfer` taglia gli spigoli vivi con un piano inclinato. Il risultato è uno smusso piatto, come una limatura.

<!-- example-source: chamfer-basic :warning slow -->
```clojure
(register A (-> (box 30 30 20) (chamfer :top 5)))
(f 60)
(register B (-> (mesh-union
                  (box 30 30 20)
                  (attach (cone 2 10 50) (f -10) (tv 50)))
                (chamfer :all 4.5)))
(f 60)
(register C (-> (mesh-union
                  (box 30 30 20)
                  (attach (cone 2 10 50) (f -10) (tv 50)))
                (chamfer :all 4.5 :angle 90)))
```


Il primo argomento dopo la mesh è un *direction selector* che sceglie quali spigoli lavorare: `:top`, `:bottom`, `:left`, `:right`, `:up`, `:down`, oppure `:all` per tutti. Il secondo è la distanza di taglio.

A mostra il caso più semplice: gli spigoli superiori di un box vengono smussati in modo pulito.

B e C mostrano cosa succede quando la geometria è più complessa. Un cono viene unito al box col vertice attaccato in alto e la base sospesa in aria. Senza il parametro `:angle`, `chamfer` lavora su molti spigoli, inclusi quelli attorno al vertice del cono (dove il cono incontra il piano superiore del box), e il risultato è visivamente sbagliato. Con `:angle 90`, `chamfer` esclude proprio quegli spigoli e si limita ai 90° del box e alla base larga del cono, producendo un risultato corretto.

`:angle` specifica la *piega minima* (in gradi) perché uno spigolo venga considerato. La piega è l'angolo tra le normali esterne delle due facce adiacenti: 0° su una superficie piana (nessuno spigolo), 90° sugli spigoli retti di un box, tendente a 180° sugli spigoli a lama. Vengono toccati gli spigoli con piega *maggiore o uguale* a `:angle`. Il default `:angle 80` cattura tutti gli spigoli da 80° in su, inclusi i 90° del box e gli ~81° generati dall'unione cono-box. Alzare la soglia esclude gli spigoli con piega più dolce e protegge le zone dove `chamfer` farebbe pasticci: è il primo strumento da provare quando un chamfer produce un risultato strano.

### Fillet: raccordo arrotondato

`fillet` fa la stessa cosa di `chamfer` ma produce un raccordo curvo al posto del taglio piatto. Tutte le considerazioni su `chamfer` (i direction selector, il parametro `:angle`, i limiti su geometrie complesse) si applicano identicamente a `fillet`. Internamente entrambe le operazioni costruiscono dei "cutter" e li sottraggono dalla mesh con `mesh-difference`: la differenza è solo nella forma del cutter (piatto per `chamfer`, arrotondato per `fillet`).

`fillet` ha un parametro in più, `:segments`, che controlla quanti passi usa per approssimare la curva:

```clojure
(-> (box 30 30 20) (fillet :top 3 :segments 8))
(-> (box 30 30 20) (fillet :all 2 :segments 8))
```

<!-- example-source: fillet-box
(register filleted (-> (box 30 30 20) (fillet :top 3 :segments 8)))
-->

Più segmenti producono un raccordo più liscio ma con più triangoli. Per la stampa 3D, 6-8 segmenti sono di solito sufficienti.

### Quando chamfer e fillet non bastano

Le situazioni in cui `chamfer` e `fillet` producono risultati sbagliati o falliscono sono tipicamente tre:

- **Spigoli concavi.** Entrambe le operazioni sottraggono materiale lungo lo spigolo. Su uno spigolo concavo (interno) questo scava un solco invece di smussare il raccordo. `chamfer` e `fillet` rifiutano di operare sugli spigoli concavi.
- **Spigoli troppo vicini ad altra geometria.** Se il cutter generato per uno spigolo si avvicina o attraversa altre parti del modello, il risultato può essere geometria malformata. Il parametro `:angle` aiuta a escludere gli spigoli problematici. Nel caso nel vertice del cono nell'esempio sopra il problema è dovuto ai tanti vertici dei triangoli, molto vicini tra loro, che approssimano la punta del cono.
- **Geometrie auto-intersecanti o con tolleranze molto strette.** Le booleane sottostanti possono fallire o produrre piccoli artefatti.

Se ti trovi in uno di questi casi e nessuna combinazione di `:angle` o di selezione direzionale ti soddisfa, la strategia alternativa è costruire l'oggetto direttamente come SDF (cap. 12). `sdf-blend`, `sdf-blend-difference` e simili producono raccordi morbidi *per costruzione*: lo smussamento è incorporato nelle operazioni di combinazione, non aggiunto come post-processing. Non hanno i limiti di `chamfer`/`fillet` perché non operano sulla topologia di una mesh esistente, ma sulla rappresentazione implicita.

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

Un gradino più sotto ancora c'è `find-sharp-edges`, che non smussa niente: ispeziona la mesh e restituisce l'elenco degli spigoli il cui angolo diedrale supera una soglia (`:angle`, default 30°), ciascuno descritto da una mappa con vertici, posizioni, angolo, punto medio e normali. Accetta lo stesso predicato `:where` di `chamfer-edges` per filtrare per posizione. Serve a ispezionare dove cadrebbe uno smusso prima di applicarlo, o come ingresso per pipeline custom: l'elenco che produce è esattamente il formato che `chamfer-edges` e `chamfer-prisms` accettano.

### Mesh-refine: suddivisione senza smoothing

`mesh-refine` suddivide i triangoli senza spostare i vertici: la forma resta identica, ma la mesh diventa più densa.

```clojure
(mesh-refine m 2)    ; ogni triangolo → 4 sotto-triangoli
(mesh-refine m 3)    ; ogni triangolo → 9 sotto-triangoli
```

<!-- example-source: mesh-refine
(register sparse-box (warp (box 20) (sphere 40) (roughen 3 2)))

(register not-so-dense-box
  (attach
    (warp (mesh-refine (box 20) 2) (sphere 40) (roughen 3 2))
    (f 30)))

(register dense-box
  (attach
    (warp (mesh-refine (box 20) 5) (sphere 40) (roughen 3 2))
    (f 60)))
-->

Da solo non cambia nulla visivamente. È utile come passo preparatorio quando un'operazione successiva ha bisogno di più vertici su cui lavorare (tipicamente `warp`, che deforma spostando i vertici esistenti).

### Mesh-laplacian: smoothing che preserva la topologia

`mesh-laplacian` è uno smoothing di Taubin: sposta i vertici sugli spigoli più acuti per smussarli, senza cambiare la topologia della mesh. Non aggiunge vertici e non richiede input manifold.

```clojure
(mesh-laplacian m)
(mesh-laplacian m :iterations 20 :feature-angle 120)
```

<!-- example-source: mesh-laplacian
(register rough (warp (sphere 20 32 16) (sphere 40) (roughen 5 4)))
(register smoothed
  (attach (mesh-laplacian rough :iterations 10) (f 50)))
-->

Il comportamento è selettivo: muove solo i vertici sugli spigoli il cui angolo diedrale è sotto `:feature-angle` (default 150°), lasciando le superfici piatte intatte. Questo lo rende ideale per ammorbidire le mesh esteticamente ruvide: superfici irregolari da `roughen` o da displacement con noise, heightmap a bassa risoluzione, STL importati, o pezzi assemblati con `concat-meshes`. Funziona anche su mesh non-manifold, dove `fillet` e le booleane non possono operare. (Per le shell perforate non serve: i contorni delle aperture escono già lisci grazie al parametro `:softness` del cap. 6.)

I parametri del ciclo di Taubin (`:lambda` 0.5, `:mu` -0.53) sono calibrati per smussare senza restringere il volume. Più iterazioni producono un effetto più marcato.

`mesh-laplacian` funziona bene su mesh dense (centinaia di vertici o più). Su mesh sparse come un box di sole 8 vertici, ogni vertice ha troppi pochi vicini perché la media abbia senso, e il filtro distorce invece di smussare: per arrotondare un box usa `fillet` o `chamfer`, non `mesh-laplacian`.

### Mesh-simplify: ridurre i triangoli

`mesh-simplify` riduce il numero di triangoli di una mesh per edge-collapse, cercando di preservarne la forma:

```clojure
(mesh-simplify m 0.5)    ; target: 50% dei triangoli originali
(mesh-simplify m 0.25)   ; target: 25%
```

<!-- example-source: mesh-simplify :warning slow
(def heavy
  (-> (mesh-difference (box 40 40 20) (cyl 12 30))
      (fillet :all 2)))
(register light (mesh-simplify heavy 0.3 :max-passes 50))
-->

Il rapporto è una frazione del conteggio originale. Il risultato è approssimativo: la mesh semplificata perde dettagli fini ma conserva la forma generale. Utile per ridurre il peso di mesh molto dense prima dell'export.

`mesh-simplify` funziona bene su mesh isotropiche (triangoli di dimensioni simili) come quelle uscite da `fillet`, `chamfer` o booleane su geometrie curve. Su mesh con triangoli molto non uniformi — tipicamente le sfere UV (`sphere`), che hanno ventagli densissimi ai poli, o i ventagli dei cap di `cyl` — il collasso preferenziale degli edge corti distorce la forma (sfera schiacciata ai poli, cilindro collassato): in questi casi è meglio ricostruire la primitiva a risoluzione più bassa che semplificarla a posteriori. Se il target non viene raggiunto, alza `:max-passes` (default 20).

### Scegliere lo strumento giusto

La scelta tra questi strumenti dipende da cosa vuoi ottenere:

Se vuoi arrotondare spigoli specifici (quelli in alto, quelli a destra, quelli in una regione), usa `fillet` con un direction selector o `chamfer-edges` con `:where`. Hai il controllo preciso su dove agisce l'operazione.

Se vuoi ammorbidire le sfaccettature visibili senza cambiare la topologia, usa `mesh-laplacian`. Non aggiunge geometria e funziona su qualsiasi mesh, anche non-manifold.

Se hai bisogno di un semplice smusso piatto per rompere gli spigoli vivi (comune nella stampa 3D per evitare l'effetto "elephant foot"), usa `chamfer`. È l'operazione più leggera.

Per arrotondamenti morbidi e globali tipici delle forme organiche, vedi gli SDF nel cap. 12: producono raccordi continui per costruzione (`sdf-blend`) invece che come post-processing.

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

<!-- example-source: face-ids-box :no-run
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

<!-- example-source: find-faces-direction :no-run
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

<!-- example-source: largest-face :no-run
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



<!-- example-source: attach-face-move
(register a (box 20))
(u 50)
(register b (attach-face (box 20) :top (f 5)))

;; operazioni multiple
(u 50)
(register c (attach-face (box 20) :top (f 10) (inset 3)))

(u 50)

(register d (clone-face (box 20) :top (inset 3) (f 10)))
-->

Le operazioni disponibili dentro `attach-face` (e dentro `clone-face`) includono i comandi tartaruga e tre operazioni specifiche per le facce:

`(f dist)` muove la faccia lungo la sua normale. Valori positivi la allontanano dalla mesh, negativi la avvicinano.

`(th angle)`, `(tv angle)`, `(tr angle)` cambiano la direzione della normale, esattamente come farebbero con la tartaruga. Utili per inclinare una faccia durante un'estrusione a gradini.

`(inset dist)` restringe la faccia verso il suo centro. È l'equivalente di un offset 2D negativo applicato al contorno della faccia.

`(scale factor)` scala la faccia uniformemente rispetto al suo centro.

Nota: queste forme a un solo argomento — `(inset dist)` e `(scale factor)` — agiscono sulla faccia selezionata e hanno senso solo dentro `attach-face` e `clone-face`. A top-level esiste comunque un `scale` distinto, `(scale mesh factor)` (e nelle varianti per SDF e shape 2D), che scala l'intero oggetto attorno alla sua creation-pose, non una singola faccia. Per `inset`, invece, non c'è una funzione standalone che prenda una mesh come argomento.

### Clone-face: estrudere una faccia

`clone-face` duplica una faccia e la collega alla mesh con nuova geometria laterale. È l'estrusione locale: la faccia originale resta dov'era, una copia si sposta lungo la normale, e le pareti laterali chiudono il gap.



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


<!-- example-source: face-shape-extrude
(register b (box 30 30 10))
(def top-info (face-shape b (:id (largest-face b :top))))
(turtle (:pose top-info)
  (color 0xffffff)
  (register tower (extrude (:shape top-info) (f 40))))
-->

(:pose top-info) restituisce posizione e direzione della faccia, apri un `turtle` scope con la pose della faccia ed estrudi la shape da lì:

Questo pattern è utile per costruire sopra il risultato di una booleana: trovi la faccia, ne estrai la forma, e la usi come profilo per una nuova estrusione posizionata esattamente dove serve.

## 7.5 Sezioni

A volte hai bisogno di tornare dal 3D al 2D: estrarre la sezione di una mesh a una certa altezza, catturare la silhouette di un oggetto vista da una direzione, o ricavare il contorno di una faccia per riusarlo come profilo. Ridley offre tre operazioni per questo, tutte guidate dalla posa della tartaruga.

### Slice-mesh: sezione su un piano

`slice-mesh` taglia una mesh con il piano definito dalla posizione e dalla direzione della tartaruga. Il vettore heading è la normale del piano. Il risultato è un vettore di shape 2D (una mesh può avere più contorni disgiunti sullo stesso piano).


<!-- example-source: slice-mesh-cup
(register cup (revolve (shape (f 20) (th 90) (f 30) (th 90) (f 15))))
(tv 90) (f 15)
(def shp (slice-mesh :cup))
(f 30)
(stamp shp)
-->

Le coordinate delle shape risultanti sono nel sistema locale del piano di taglio: l'asse X è il right della tartaruga, l'asse Y è il suo up. Le shape hanno `:preserve-position? true`, il che significa che `stamp` le renderizza nella posizione corretta nel viewport:

```clojure
(f 30)
(stamp shp)    ; visualizza la sezione su un piano a distanza 30 per renderla visibile
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
(register cup (revolve (shape (f 20) (th 90) (f 30) (th 90) (f 15))))
(tv 90) (f 15)
(def shp (slice-at-plane :cup [0 0 1] [0 0 15]))
(f 30)
(stamp shp)
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
(tv 40) (th 30) (f 50)
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



<!-- example-source: warp-inflate-basic
(register bumpy
  (warp (box 40) (attach (sphere 25) (f 20) (u 10)) (inflate 5) :subdivide 6))
-->

### Preset di deformazione

Ridley offre sette preset che coprono i casi più comuni:

`(inflate amount)` spinge i vertici verso l'esterno lungo le loro normali. Crea rigonfiamenti, bolle, rilievi.

`(dent amount)` è il contrario: spinge verso l'interno. Crea ammaccature, incavi, impronte.

`(attract strength)` tira i vertici verso il centro del volume. Strength va da 0 (nessun effetto) a 1 (tutti al centro).

`(twist angle)` ruota i vertici attorno a un asse, con l'angolo che cresce linearmente lungo l'asse. Per volumi cilindrici e conici l'asse è rilevato automaticamente; per altri volumi (box, sfera) devi specificarlo: `(twist 90 :x)`, `(twist 90 :y)`, `(twist 90 :z)`. Due condizioni perché il twist si veda: la sezione deve essere *non* assialsimmetrica (un cilindro a molti lati torto attorno al proprio asse resta identico a se stesso, mentre un box o un prisma a poche facce mostrano la torsione), e la mesh deve avere abbastanza vertici lungo l'asse (usa `mesh-refine` o `:subdivide`, altrimenti la torsione si applica solo alle estremità).

`(squash axis)` schiaccia i vertici verso un piano perpendicolare all'asse. `(squash :z)` appiattisce in Z. Con un secondo argomento puoi controllare quanto: `(squash :z 0.5)` è a metà strada tra piatto e invariato.

`(roughen amplitude)` aggiunge rumore lungo le normali, creando una superficie irregolare. Con due argomenti, `(roughen amplitude frequency)`, controlli anche la frequenza spaziale del rumore.



<!-- example-source: warp-presets
(defn transp [m]
  (-> m
    (color 0xffff00)
    (material :opacity 0.2)))


(def bump_control (attach (sphere 49.5) (u 30)))
(register BC (transp bump_control))
(register bumpy (warp (box 36) bump_control (inflate 7.5) :subdivide 3.3))

(f 100)
(def dent_control (attach (sphere 19) (f 13.5) (u 9)))
(register DC (transp dent_control))
(register dented (warp (sphere 22) dent_control (dent 12.9)))

(f 100)
(def twist_control (attach (box 40 70 40) (u 40) (f 5)))
(register TC (transp twist_control))
(register twisted (warp (mesh-refine (box 15 60 15) 32) twist_control (twist 90 :z)))

(f 100)
(def roughen_control (attach (sphere 22) (u 10)))
(register RC (transp roughen_control))
(register rough (warp (sphere 20 128 128) roughen_control (roughen 8 4)))
-->
L'esempio mostra per alcuni preset della funzione di deformazione la mesh che esprime il volume di influenza in giallo trasparente e la mesh deformata in azzurro.

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


<!-- example-source: warp-custom
(register gaussian-bump
  (warp (mesh-refine (box 40 40 5) 5) (sphere 25)
    (fn [pos _ _ _ _]
      (let [[x y z] pos
            r (sqrt (+ (* x x) (* y y)))]
        [x y (+ z (* 3 (exp (- (* r r 0.01)))))]))))
-->

Il falloff hermite (da `smooth-falloff`) è già applicato dal sistema prima di chiamare la tua funzione: i vertici al bordo del volume ricevono un effetto ridotto automaticamente. Non devi gestire il blend tu stesso.

### Quando usare warp vs altre tecniche

`warp` è la scelta giusta quando l'effetto che vuoi non si esprime con un profilo geometrico. Un rigonfiamento organico su un pezzo meccanico, una superficie irregolare che simula materiale naturale, una torsione localizzata: questi effetti si descrivono più facilmente come "sposta i vertici così" che come "estrudi questa forma lungo quest'altro percorso".

Per deformazioni globali e uniformi (torsione di un pezzo intero, rastremazione), le shape-fn del capitolo 6 sono spesso più naturali: la deformazione è parte della definizione della forma, non un'operazione post-hoc. `warp` dà il meglio quando la deformazione è *localizzata* a una regione specifica di un pezzo già costruito.