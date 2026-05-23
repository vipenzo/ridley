# 12. Lavorare con gli SDF

Fino a qui hai costruito tutto con le mesh: vertici, facce, triangoli. Gli SDF (Signed Distance Fields) sono il sistema di modellazione alternativo di Ridley. Dove le mesh descrivono la superficie di un oggetto come una rete di triangoli, un SDF descrive un oggetto come una funzione matematica che, dato un punto nello spazio, dice a che distanza si trova dalla superficie. Punti fuori dall'oggetto hanno distanza positiva, punti dentro negativa, punti sulla superficie hanno distanza zero.

La differenza pratica è che alcune operazioni che con le mesh sono impossibili o fragili diventano naturali con gli SDF: raccordi morbidi fra volumi, gusci cavi uniformi, pattern ripetitivi infiniti, deformazioni scultoree. Il prezzo è che un SDF resta astratto finché non lo materializzi in una mesh per vederlo, esportarlo o stamparlo.

## 12.1 Primitive e operatori

### Primitive SDF

```clojure
(sdf-sphere r)                    ;; sfera di raggio r
(sdf-box sx sy sz)                ;; box con dimensioni sx × sy × sz
(sdf-rounded-box sx sy sz r)      ;; box con spigoli arrotondati di raggio r
(sdf-cyl r h)                     ;; cilindro di raggio r e altezza h
(sdf-torus R r)                   ;; toro: R raggio maggiore, r raggio sezione
```

Le primitive SDF sono dati puri: costruire `(sdf-sphere 10)` non calcola nulla, produce una mappa Clojure `{:op "sphere" :r 10}`. Il calcolo avviene solo quando la mesh viene materializzata.

Una nota sugli argomenti di `sdf-box`: l'ordine è `(sdf-box x y z)`, che corrisponde a `(sdf-box right up forward)`. La primitiva mesh `box` usa lo stesso ordine: `(box right up forward)`. Le due forme producono la stessa geometria, ma per chiarezza puoi pensare agli argomenti come `(sdf-box r u f)` per mantenere la simmetria con `(box r u f)`.

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

`(sdf-blend-difference a b k)` sottrae b da a con una concavità morbida, il duale di `sdf-blend`.

`(sdf-shell a thickness)` svuota un solido lasciando un guscio uniforme di spessore `thickness`.

`(sdf-offset a amount)` espande (positivo) o contrae (negativo) la superficie. Una nota: `sdf-offset` produce un campo che non è più un vero SDF lontano dalla superficie. Per box arrotondati preferisci `sdf-rounded-box`; per operazioni di shell il risultato potrebbe non combinarsi correttamente con `sdf-intersection` di altri SDF.

`(sdf-morph a b t)` interpola tra due forme. `t` va da 0 (= a) a 1 (= b).

`(sdf-displace node formula)` deforma la superficie con una formula spaziale:

```clojure
;; Sfera ondulata
(register wavy
  (sdf-displace (sdf-sphere 10)
    '(* 1.5 (sin (* x 2)) (sin (* y 2)))))
```

La formula è un'espressione Clojure quotata che usa `x`, `y`, `z` come variabili spaziali. Valori positivi spingono la superficie verso l'interno, negativi verso l'esterno.

### Half-space e clip

`sdf-half-space` è un semispazio definito dalla posa della tartaruga. Il piano di taglio passa per la posizione della tartaruga con normale uguale all'heading. Per default tiene la metà *dietro* l'heading (la parte da cui la tartaruga è venuta):

```clojure
;; Taglia un cilindro a metà
(tv 90)
(register half-cyl
  (sdf-intersection (sdf-cyl 10 20) (sdf-half-space)))
```

`sdf-clip` è la scorciatoia per il caso comune:

```clojure
(sdf-clip (sdf-cyl 10 20))    ;; equivale a (sdf-intersection ... (sdf-half-space))
```

Per tenere la metà davanti alla tartaruga:

```clojure
(sdf-intersection shape (sdf-half-space :cut-ahead))
```

### Pattern infiniti

Gli SDF possono descrivere strutture infinite, da ritagliare poi con un'intersezione:

```clojure
;; Griglia lattice delimitata da una sfera
(register ball-lattice
  (sdf-intersection (sdf-sphere 20) (sdf-grid 8 1.5)))

;; Fenditure parallele
(register slotted
  (sdf-difference container (sdf-slats :x 8 2)))

;; Gabbia di barre
(register cage
  (sdf-intersection
    (sdf-rounded-box 60 60 90 6)
    (sdf-bar-cage 60 60 90 5 1.5)))
```

`sdf-slats`, `sdf-bars`, `sdf-bar-cage`, `sdf-grid` producono strutture infinite; `sdf-difference` e `sdf-intersection` le confinano nella regione che ti interessa.

Una avvertenza: le versioni con blend (`(sdf-grid period thickness blend-k)`) usano il blend esponenziale di libfive, che non produce un SDF valido. Il gradiente può invertirsi alle giunzioni, causando normali capovolte se combinato con `sdf-intersection` o `sdf-difference`. Per pezzi stampabili, preferisci la versione a spigoli vivi (senza `blend-k`).

### Formule custom

`sdf-formula` compila un'espressione Clojure quotata in un albero SDF. Le variabili `x`, `y`, `z` rappresentano le coordinate spaziali. Sono disponibili anche `r` (distanza dall'origine), `rho` (raggio cilindrico), `theta` (angolo azimutale), `phi` (angolo polare):

```clojure
;; Gyroid approssimato
(register gyroid
  (sdf-intersection
    (sdf-sphere 20)
    (sdf-formula
      '(+ (* (sin x) (cos y))
          (* (sin y) (cos z))
          (* (sin z) (cos x))))))
```

Le operazioni disponibili nella formula sono: aritmetiche (`+`, `-`, `*`, `/`), trigonometriche (`sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `atan2`), matematiche (`sqrt`, `abs`, `exp`, `log`, `pow`, `mod`, `square`, `neg`), e comparazione (`min`, `max`).

Un'insidia: `pow` con base negativa restituisce NaN (libfive calcola `pow(a,b)` come `exp(b * log(a))`). Per elevare al quadrato un'espressione che può essere negativa, usa `(* expr expr)` invece di `(pow expr 2)`.

## 12.2 Il blend come operazione scultorea

Il blend è il motivo principale per cui gli SDF esistono in Ridley. `sdf-blend` fonde due volumi con una transizione morbida che non è un raccordo geometrico calcolato a posteriori (come `fillet` sulle mesh) ma una proprietà della funzione distanza stessa.

```clojure
(def a (sdf-sphere 12))
(def b (translate (sdf-box 10 10 10) 10 0 0))

(register k1 (sdf-blend a b 1))    ;; blend stretto
(register k3 (sdf-blend a b 3))    ;; blend medio
(register k6 (sdf-blend a b 6))    ;; blend ampio
```

Il parametro `k` non ha un'unità intuitiva: è un coefficiente della funzione smooth-min. In pratica, parti da 2-3 e aggiusti a vista. Valori sotto 1 producono un blend quasi invisibile, valori sopra 10 fondono tutto in un blob.

`sdf-blend-difference` è il duale: sottrae con una concavità morbida. È l'operazione per scavare incavi organici:

```clojure
(register scooped
  (sdf-blend-difference
    (sdf-rounded-box 30 30 20 3)
    (translate (sdf-sphere 15) 0 0 10)
    3))
```

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
(sdf-ensure-mesh sdf ref-mesh)         ;; bounds estesi per coprire ref-mesh
(sdf-ensure-mesh sdf 30)               ;; risoluzione override
(sdf-ensure-mesh sdf ref-mesh 30)      ;; entrambi
```

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

Il workflow tipico con gli SDF è: costruisci l'albero SDF, materializza in mesh, poi continua con le operazioni mesh (booleane con altri pezzi, fillet, export).

```clojure
;; 1. Costruisci in SDF
(def organic-base
  (sdf-blend
    (sdf-sphere 15)
    (translate (sdf-cyl 8 30) 0 0 0)
    3))

;; 2. Materializza (automatico al register)
(register base organic-base)

;; 3. Continua in mesh
(register final
  (mesh-difference
    base
    (attach (cyl 4 40) (f 20))))
```

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
