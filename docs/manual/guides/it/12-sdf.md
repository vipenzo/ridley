# 12. Lavorare con gli SDF

Fino a qui hai costruito tutto con le mesh: vertici, facce, triangoli. Gli SDF (Signed Distance Fields) sono il sistema di modellazione alternativo di Ridley. Dove le mesh descrivono la superficie di un oggetto come una rete di triangoli, un SDF descrive un oggetto come una funzione matematica che, dato un punto nello spazio, dice a che distanza si trova dalla superficie. Punti fuori dall'oggetto hanno distanza positiva, punti dentro negativa, punti sulla superficie hanno distanza zero.

La differenza pratica è che alcune operazioni che con le mesh sono impossibili o fragili diventano naturali con gli SDF: raccordi morbidi fra volumi, gusci cavi uniformi, pattern ripetitivi infiniti, deformazioni scultoree. Il prezzo è che un SDF resta astratto finché non lo materializzi in una mesh per vederlo, esportarlo o stamparlo.

## 12.1 Primitive e operatori

### Primitive SDF

```clojure
(sdf-sphere r)                    ;; sfera di raggio r
(sdf-box size)                    ;; cubo di lato size
(sdf-box sx sy sz)                ;; box con dimensioni sx × sy × sz
(sdf-rounded-box sx sy sz r)      ;; box con spigoli arrotondati di raggio r
(sdf-cyl r h)                     ;; cilindro di raggio r e altezza h, asse lungo heading
(sdf-cone r1 r2 h)                ;; cono/tronco di cono, asse lungo heading
(sdf-torus R r)                   ;; toro: R raggio maggiore, r raggio sezione
```

Le primitive SDF sono dati puri: costruire `(sdf-sphere 10)` non calcola nulla, produce un albero di operazioni. Il calcolo avviene solo quando la mesh viene materializzata.

L'esempio dispone tutte le primitive SDF su una fila, e sopra ciascuna (dove esiste) la primitiva mesh corrispondente. Si vede la corrispondenza diretta SDF↔mesh — e le due primitive che vivono solo nel mondo SDF, `sdf-rounded-box` e `sdf-torus`, che restano senza compagna sopra:

<!-- example-source: sdf-primitives-gallery
;; Fila SDF (in basso)
(register s-sphere    (attach (sdf-sphere 18)))
(register s-cube      (attach (sdf-box 30) (rt 50)))
(register s-box       (attach (sdf-box 20 30 36) (rt 100)))
(register s-rbox      (attach (sdf-rounded-box 30 30 30 6) (rt 150)))
(register s-cyl       (attach (sdf-cyl 15 36) (rt 200)))
(register s-cone      (attach (sdf-cone 4 16 36) (rt 250)))
(register s-torus     (attach (sdf-torus 16 6) (rt 300)))

;; Fila mesh (in alto, +70 in up), allineata colonna per colonna
(register m-sphere (attach (sphere 18) (u 70)))
(register m-cube   (attach (box 30) (u 70) (rt 50)))
(register m-box    (attach (box 20 30 36) (u 70) (rt 100)))
;; (rt 150): nessun rounded-box mesh — colonna vuota
(register m-cyl    (attach (cyl 15 36) (u 70) (rt 200)))
(register m-cone   (attach (cone 4 16 36) (u 70) (rt 250)))
;; (rt 300): nessun torus mesh — colonna vuota
-->

Le colonne di `sdf-rounded-box` e `sdf-torus` non hanno una mesh sopra: sono forme che Ridley produce direttamente come SDF e che nel mondo mesh richiederebbero costruzioni più elaborate.

Come le primitive mesh, le primitive SDF nascono alla posa corrente della tartaruga. Quindi:

<!-- example-source: sdf-pose-aware
(register original (sdf-sphere 10))   ;; sfera all'origine
(f 30)
(th 60)
(register turned (sdf-box 4 8 16))    ;; box a (30 0 0), ruotato di 60° intorno a up
-->

Vale per `sdf-sphere`, `sdf-box`, `sdf-rounded-box`, `sdf-cyl`, `sdf-cone`, `sdf-torus` e `sdf-formula`. Le strutture infinite (`sdf-gyroid`, `sdf-schwarz-p`, `sdf-diamond`, `sdf-slats`, `sdf-bars`, `sdf-bar-cage`, `sdf-grid`) restano allineate agli assi del mondo: tipicamente le ritagli con `sdf-intersection` contro un volume posizionato.

Una nota sugli argomenti di `sdf-box`: l'ordine è `(sdf-box x y z)`, che corrisponde a `(sdf-box right up forward)`. La primitiva mesh `box` usa lo stesso ordine: `(box right up forward)`. Le due forme producono la stessa geometria nella stessa posa, e per chiarezza puoi pensare agli argomenti come `(sdf-box r u f)` per mantenere la simmetria con `(box r u f)`.

### Booleane SDF

```clojure
(sdf-union a b)           ;; unione
(sdf-difference a b)      ;; sottrazione: rimuovi b da a
(sdf-intersection a b)    ;; intersezione: tieni solo il volume comune
```

Stessa semantica delle booleane mesh, ma eseguite sulla funzione distanza, non sui triangoli.

### Trasformazioni

Le trasformazioni SDF usano le stesse funzioni polimorfe delle mesh:

```clojure
(translate node dx dy dz)
(rotate node :z 45)
(rotate node [1 0 1] 30)
(scale node 2)
(scale node 2 1 0.5)
```

`attach` funziona identicamente: comandi tartaruga, move-to, cp-*, tutto come nel capitolo 8.

### Operazioni scultoree

Queste operazioni sfruttano la rappresentazione implicita e non hanno equivalenti diretti nel mondo mesh:

`(sdf-blend a b k)` fonde due SDF con un raccordo morbido. `k` controlla il raggio di blend: più grande, più ampia la zona di transizione. È l'operazione che rende gli SDF interessanti per forme organiche.

<!-- example-source: sdf-blend-intro
(register blob
  (sdf-blend
    (sdf-sphere 12)
    (attach (sdf-box 10 10 10) (f 12))
    4))
-->

`(sdf-blend-difference a b k)` sottrae b da a con una concavità morbida, il duale di `sdf-blend`.

<!-- example-source: sdf-blend-difference-intro
(register scooped
  (sdf-blend-difference
    (sdf-rounded-box 30 30 20 3)
    (attach (sdf-sphere 15) (u 10))
    3))
-->

`(sdf-shell a thickness)` svuota un solido lasciando un guscio uniforme di spessore `thickness`.

<!-- example-source: sdf-shell-intro
(register hollow
  (sdf-clip
    (attach (sdf-shell (sdf-sphere 15) 1) (f -11))))
-->

`(sdf-offset a amount)` espande (positivo) o contrae (negativo) la superficie. Affianchiamo una sfera offsettata e una originale per vedere la differenza:

<!-- example-source: sdf-offset-intro
(register grown (sdf-offset (sdf-sphere 10) 3))
(register original (attach (sdf-sphere 10) (rt 40)))
-->

Una nota: `sdf-offset` produce un campo che non è più un vero SDF lontano dalla superficie. Per box arrotondati preferisci `sdf-rounded-box`; per operazioni di shell il risultato potrebbe non combinarsi correttamente con `sdf-intersection` di altri SDF.

`(sdf-morph a b t)` interpola tra due forme. `t` va da 0 (= a) a 1 (= b).

<!-- example-source: sdf-morph-intro
(register half-way
  (sdf-morph (sdf-box 16 32 8) (sdf-sphere 11) 0.5))
-->

`(sdf-displace node formula)` deforma la superficie con una formula spaziale:

<!-- example-source: sdf-displace-intro
(register wavy
  (sdf-displace (sdf-sphere 10)
    '(* 1.5 (sin (* x 2)) (sin (* y 2)))))
-->

La formula è un'espressione Clojure quotata che usa `x`, `y`, `z` come variabili spaziali. Valori positivi spingono la superficie verso l'interno, negativi verso l'esterno.

### Half-space e clip

`sdf-half-space` è un semispazio definito dalla posa della tartaruga. Il piano di taglio passa per la posizione della tartaruga con normale uguale all'heading. Per default tiene la metà *dietro* l'heading (la parte da cui la tartaruga è venuta):

<!-- example-source: sdf-half-space-intro
;; Taglia un cilindro a metà
(tv 90)
(register half-cyl
  (sdf-intersection (attach (sdf-cyl 10 20) (tv 30)) (sdf-half-space)))
-->

`sdf-clip` è la scorciatoia per il caso comune:

<!-- example-source: sdf-clip-intro
(tv 90)
(register clipped
  (sdf-clip
    (sdf-blend
      (sdf-sphere 12)
      (attach (sdf-box 10 10 10) (f 12))
      4)))    ;; equivale a (sdf-intersection ... (sdf-half-space))
-->

Per tenere la metà davanti alla tartaruga:

```clojure
(sdf-intersection shape (sdf-half-space :cut-ahead))
```

### Pattern infiniti

Gli SDF possono descrivere strutture infinite, da ritagliare poi con un'intersezione:

<!-- example-source: sdf-grid-lattice
;; Griglia lattice delimitata da una sfera
(register ball-lattice
  (sdf-intersection (sdf-sphere 20) (sdf-grid 8 1.5)))
-->

<!-- example-source: sdf-bar-cage-intro
;; Gabbia di barre
(register cage
  (sdf-intersection
    (sdf-rounded-box 60 60 90 6)
    (sdf-bar-cage 60 60 90 5 1.5)))
-->

Un terzo costrutto sono le fenditure parallele, che si sottraggono da un contenitore:

<!-- example-source: sdf-slats-intro
;; Fenditure parallele scavate in un box (periodo 8, spessore 2, perpendicolari a X)
(register slotted
  (sdf-difference (sdf-rounded-box 50 30 30 4) (sdf-slats :x 8 2)))
-->

`sdf-slats`, `sdf-bars`, `sdf-bar-cage`, `sdf-grid` producono strutture infinite; `sdf-difference` e `sdf-intersection` le confinano nella regione che ti interessa.

Una avvertenza: le versioni con blend (`(sdf-grid period thickness blend-k)`) usano il blend esponenziale di libfive, che non produce un SDF valido. Il gradiente può invertirsi alle giunzioni, causando normali capovolte se combinato con `sdf-intersection` o `sdf-difference`. Per pezzi stampabili, preferisci la versione a spigoli vivi (senza `blend-k`).

### Formule custom

`sdf-formula` compila un'espressione Clojure quotata in un albero SDF. Le variabili `x`, `y`, `z` rappresentano le coordinate spaziali. Sono disponibili anche `r` (distanza dall'origine), `rho` (raggio cilindrico), `theta` (angolo azimutale), `phi` (angolo polare):

<!-- example-source: sdf-formula-gyroid
;; Gyroid approssimato
(register gyroid
  (sdf-intersection
    (sdf-sphere 20)
    (sdf-formula
      '(+ (* (sin x) (cos y))
          (* (sin y) (cos z))
          (* (sin z) (cos x))))))
-->

Le operazioni disponibili nella formula sono: aritmetiche (`+`, `-`, `*`, `/`), trigonometriche (`sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `atan2`), matematiche (`sqrt`, `abs`, `exp`, `log`, `pow`, `mod`, `square`, `neg`), e comparazione (`min`, `max`).

Un esempio più solido di come `sdf-formula` permette di costruire primitive da zero: `sdf-cone` è definito internamente proprio così. Un tronco di cono con raggio `r1` alla base (z = -h/2), `r2` in cima (z = +h/2) e altezza `h` ha questo campo distanza:

<!-- example-source: sdf-formula-cone
;; Definizione di sdf-cone usando sdf-formula
(defn my-cone [r1 r2 h]
  (let [half-h (/ h 2)
        slope  (/ (- r2 r1) h)
        max-r  (max r1 r2)]
    (-> (sdf-formula
          (list 'max
                ;; rho - r(z), dove r(z) interpola linearmente da r1 a r2
                (list '- 'rho
                      (list '+ r1 (list '* slope (list '+ 'z half-h))))
                ;; |z| - h/2: ritaglia tra le due basi
                (list '- (list 'abs 'z) half-h)))
        ;; auto-bounds: :x-range è estensione radiale, :y-range è altezza.
        ;; Il +1 sul y-range allarga la regione di meshing oltre i piani
        ;; dei dischi: senza quel margine libfive non chiude le basi.
        (assoc :x-range [(- max-r) max-r]
               :y-range [(- (+ half-h 1)) (+ half-h 1)]))))

(register cone-from-formula (my-cone 16 4 36))
-->

L'idea è che il cono è l'intersezione di due regioni: il "disco inclinato" `rho ≤ r(z)` e la lastra `|z| ≤ h/2`. Il massimo delle due funzioni distanza dà l'SDF dell'intersezione. Non è un SDF euclideo perfetto (la distanza vera fuori dalla superficie sarebbe più piccola), ma il contorno a iso-zero coincide con la superficie del cono — abbastanza per booleane e meshing.

L'`assoc` finale serve al meshing automatico. `sdf-formula` non sa cosa rappresenta la tua espressione, quindi se non gli dici dove vive il solido `auto-bounds` ripiega su un cubo `[-10 10]` su ogni asse — col risultato che il nostro cono di raggio 16 e altezza 36 verrebbe clippato. `:x-range` è letto come estensione radiale (e applicato sia a X che a Y, con un po' di padding); `:y-range` come estensione lungo Z. Per il cono i numeri escono direttamente dai parametri: il raggio massimo dei due dischi (`max r1 r2`) e mezza altezza.

Il `+1` aggiunto al `y-range` non è cosmetico: la superficie iso-zero delle due basi giace esattamente a `z = ±h/2`, e se la regione di meshing termina lì libfive non vede voxel "esterni" oltre il cap, quindi non lo chiude. Lasciando un po' di margine (un'unità basta) il marching cubes trova il salto di segno e produce un mesh chiuso. È una conseguenza dello schema "max di semispazi" — se costruisci una primitiva con superfici piatte tangenti al bounding box, ricordati di lasciare un po' d'aria sui lati interessati.

Lo schema "max di semispazi" si generalizza: ogni solido convesso può essere descritto come max di tante funzioni `f_i(x,y,z) - 0` dove `f_i` è positivo fuori e negativo dentro l'i-esima faccia. Le primitive più sofisticate (`sdf-rounded-box`, le TPMS) usano formule più ricche, ma il principio è lo stesso.

Un'insidia: `pow` con base negativa restituisce NaN (libfive calcola `pow(a,b)` come `exp(b * log(a))`). Per elevare al quadrato un'espressione che può essere negativa, usa `(* expr expr)` invece di `(pow expr 2)`.

## 12.2 Il blend come operazione scultorea

Il blend è il motivo principale per cui gli SDF esistono in Ridley. `sdf-blend` fonde due volumi con una transizione morbida che non è un raccordo geometrico calcolato a posteriori (come `fillet` sulle mesh) ma una proprietà della funzione distanza stessa.

<!-- example-source: blend-levels
(def a (sdf-sphere 12))
(def b (attach (sdf-box 10 10 10) (f 12)))

(register k1 (sdf-blend a b 1))                  ;; blend stretto
(register k3 (attach (sdf-blend a b 3) (rt 50))) ;; blend medio
(register k6 (attach (sdf-blend a b 6) (rt 100)));; blend ampio
-->

Il parametro `k` non ha un'unità intuitiva: è un coefficiente della funzione smooth-min. In pratica, parti da 2-3 e aggiusti a vista. Valori sotto 1 producono un blend quasi invisibile, valori sopra 10 fondono tutto in un blob.

`sdf-blend-difference` è il duale: sottrae con una concavità morbida. È l'operazione per scavare incavi organici:

<!-- example-source: blend-difference-scoop
(register scooped
  (sdf-blend-difference
    (sdf-rounded-box 30 30 20 3)
    (attach (sdf-cone 2 10 30) (u 10) (tv 90))
    3))
-->

## 12.3 Risoluzione e auto-meshing

Un SDF è un albero di operazioni, non una mesh. La mesh viene generata solo quando serve: al momento del `register`, quando combini un SDF con una mesh in una booleana, o all'export. Questo processo si chiama materializzazione.

### Risoluzione globale

`sdf-resolution!` imposta la risoluzione globale del meshing SDF:

```clojure
(sdf-resolution! 15)     ;; default: bozza veloce
(sdf-resolution! 40)     ;; buona qualità
(sdf-resolution! 80)     ;; alta qualità, lento
```

Il numero è in "unità turtle" (come `resolution :n` per le curve). Internamente viene convertito in voxel per unità, tenendo conto dei bounds dell'oggetto. Il conteggio totale dei voxel è limitato da un cap per evitare meshing troppo costosi.

### Auto-boost per feature sottili

Quando l'albero SDF contiene `sdf-shell` o `sdf-offset` piccoli, la risoluzione viene automaticamente aumentata per garantire almeno 3 voxel attraverso la feature più sottile. Non devi fare nulla: il sistema rileva lo spessore minimo e adatta.

### Dolcezza delle curve

Per le primitive curve (`sdf-cyl`, `sdf-cone`, `sdf-torus`) la dolcezza visiva non dipende dalla `sdf-resolution!` ma dalla **turtle resolution** corrente al momento della costruzione — esattamente come per le primitive mesh (`cyl`, `cone`, `sphere`). Il default `:n 64` dà ~64 voxel attorno al perimetro più curvo; basta alzarlo per liscere:

<!-- example-source: sdf-curve-resolution
;; Toro liscio solo all'interno dello scope, default altrove.
;; Cambia :n e riesegui per vedere l'effetto sulla dolcezza.
(turtle (resolution :n 256)
  (register hi-ring (sdf-torus 16 6)))
-->

Lo scope `(turtle …)` ti permette di mirare la risoluzione alta su una singola primitiva, lasciando il resto del modello al default. È la stessa semantica delle mesh, quindi un solo `resolution` controlla l'intera scena coerentemente.

### Controllo esplicito

Per il controllo completo su bounds e risoluzione, usa `sdf->mesh` direttamente:

```clojure
(sdf->mesh node)                                          ;; auto bounds e risoluzione
(sdf->mesh node [[-12 12] [-12 12] [-12 12]] 30)         ;; bounds e risoluzione espliciti
```

I bounds sono tre intervalli `[min max]`, uno per asse. La risoluzione è in voxel per unità.

`sdf-ensure-mesh` è la forma condizionale: se l'input è già una mesh, la restituisce invariata. Se è un nodo SDF, lo materializza. Utile nel codice polimorfo:

```clojure
(sdf-ensure-mesh x)                    ;; auto tutto
(sdf-ensure-mesh sdf ref-mesh)         ;; estende i bounds per coprire ref-mesh
(sdf-ensure-mesh sdf 30)               ;; risoluzione override
(sdf-ensure-mesh sdf ref-mesh 30)      ;; entrambi
```

La forma con `ref-mesh` serve a un problema specifico: gli **SDF infiniti o procedurali** (gyroid, half-space, le formule senza range dichiarati) non dicono ad `auto-bounds` quanto sono grandi, e il sistema ripiega su un cubo di default di circa `[-10 10]` per asse. Finché li guardi da soli non è un problema, ma quando li usi come *cutter* in una booleana contro una mesh più grande, il cutter viene materializzato solo in quel cubetto centrale: il resto della mesh ospite resta intatto, e il risultato è geometricamente sbagliato (pur restando manifold).

Ma prima di raggiungere `ref-mesh`, c'è quasi sempre una strada migliore: **tieni la booleana in SDF e materializza solo il risultato finale**. Se il contenitore può essere un SDF, non passare per una mesh intermedia. `sdf-ensure-mesh` torna utile proprio quando un ramo SDF deve convivere con della geometria mesh nella stessa espressione — qui un loft (che è già mesh) e un box scavato da un gyroid (SDF) uniti insieme:

<!-- example-source: sdf-ensure-mesh-union :warning slow
(def container (sdf-box 20 20 20))   ;; SDF, non box mesh
(def pattern   (sdf-gyroid 8 1))

(register carved
  (mesh-union
    (loft (tapered (twisted (rect 30 2))) (f 50))
    (sdf-ensure-mesh (sdf-difference container pattern))))
-->

`sdf-ensure-mesh` converte il ramo SDF in mesh perché `mesh-union` possa unirlo al loft. La booleana `(sdf-difference container pattern)` resta in SDF fino a quel punto: `auto-bounds` la dimensiona partendo dal box (che conosce le sue dimensioni), e libfive valuta la difference direttamente durante il meshing, triangolando solo le porzioni di gyroid dentro il box — non esiste mai una mesh-gyroid standalone da milioni di facce. È il pattern idiomatico: tieni il lavoro in SDF il più a lungo possibile, e converti a mesh solo dove devi incontrare geometria mesh.

`ref-mesh` resta la via quando l'ospite *deve* essere una mesh — un loft, una mesh importata, una mesh con anchor da preservare — e vuoi usarci un cutter SDF infinito. In quel caso il cutter va materializzato per la booleana, e `ref-mesh` gli dà i bounds giusti:

```clojure
;; Ospite mesh, cutter SDF infinito: ref-mesh dimensiona il cutter sulla sua estensione
(mesh-difference some-loft (sdf-ensure-mesh pattern some-loft))
```

I bounds finali sono l'unione di quelli auto-stimati dell'SDF e di quelli della mesh (allargati del 30%, così la superficie non viene tagliata sul bordo). La `ref-mesh` serve solo a dimensionare la regione: non entra nella geometria del risultato. Attenzione al costo: un cutter denso (gyroid a passo fine) su bounds grandi produce moltissimi triangoli e il meshing diventa lento. Per contenerlo, allarga il periodo del pattern, abbassa la risoluzione con `sdf-resolution!`, o restringi la `ref-mesh` al solo sotto-volume da scavare.

Quando l'SDF è "finito" (sfere, box, tori, cilindri, coni e le loro composizioni) niente di tutto questo serve: `auto-bounds` lo dimensiona da solo. E se controlli la definizione di una formula, l'alternativa è dichiarare `:x-range`/`:y-range` sul nodo (come per il cono in 12.1).

## 12.4 Sdf-offset: l'offset come operazione di prima classe

`sdf-offset` espande o contrae la superficie di un SDF di una quantità uniforme. È concettualmente semplice (aggiungi o sottrai un valore alla funzione distanza), ma ha implicazioni pratiche che meritano una nota.

```clojure
;; Espandi una sfera di 2mm
(sdf-offset (sdf-sphere 10) 2)

;; Contrai un box di 1mm (arrotonda gli spigoli)
(sdf-offset (sdf-box 20 20 20) -1)
```

L'offset è una delle quattro vie all'offset in Ridley (insieme a `shape-offset` per le shape 2D, shell come shape-fn sui loft, e `woven-shell` per i loft di rivoluzione). La guida tematica "Superfici parallele" le mette a confronto.

## 12.5 Il pattern SDF-then-mesh

Il workflow tipico con gli SDF è: costruisci l'albero SDF, poi materializzalo in mesh nel momento in cui lo combini con operazioni mesh — booleane con altri pezzi, fillet, export. La materializzazione è implicita: avviene quando un nodo SDF finisce dentro un'operazione mesh o un `register`.

<!-- example-source: sdf-then-mesh
;; 1. Costruisci in SDF (come funzione, così nasce alla posa corrente)
(defn organic-base []
  (sdf-blend
    (sdf-sphere 15)
    (sdf-cyl 8 30)
    3))

;; 2. Posiziona la tartaruga e materializza (automatico al register)
(f 30) (tv 30)
(register final
  (mesh-difference
    (organic-base)
    (attach (cyl 4 100))))
-->

Il passaggio SDF → mesh è irreversibile: una volta materializzato, l'oggetto è una mesh e le operazioni SDF non sono più disponibili. Non esiste una funzione inversa `mesh->sdf`. Questo significa che è consigliabile fare tutto il lavoro "organico" (blend, shell, offset, pattern) in SDF, e poi passare a mesh per le operazioni "meccaniche" (fori, agganci, assemblaggio).

`sdf-node?` distingue i due tipi:

```clojure
(sdf-node? (sdf-sphere 10))    ;; => true
(sdf-node? (box 20))           ;; => false
```

## 12.6 Quando SDF, quando mesh

Non c'è un sistema "migliore": mesh e SDF fanno cose diverse bene.

Usa gli SDF quando vuoi raccordi morbidi fra volumi (blend), gusci cavi uniformi (shell), pattern ripetitivi (grid, slats, bars), deformazioni scultoree (displace), o forme definite da equazioni matematiche (formula). Il blend in particolare non ha equivalente mesh: `mesh-smooth` arrotonda gli spigoli, ma non fonde due volumi con una transizione morbida.

Usa le mesh quando lavori con forme precise (profili estrusi, loft, revolve), quando hai bisogno di facce accessibili via id/nome, quando assembli componenti con skeleton e marcatori, o quando la velocità conta (le mesh sono più veloci per la maggior parte delle operazioni).

Il pattern più comune è quello della sezione 12.5: costruisci la parte organica in SDF, materializza, e completa in mesh. I due sistemi convivono nello stesso progetto senza conflitti.
