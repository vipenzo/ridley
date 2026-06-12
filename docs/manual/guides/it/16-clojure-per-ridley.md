# 16. Clojure per Ridley

<!-- level: base -->

Ridley usa un sottinsieme di Clojure come linguaggio. Se vieni da Python, JavaScript, o da un CAD tradizionale, la sintassi a parentesi può sembrare aliena. Questo capitolo copre il minimo necessario per scrivere modelli Ridley senza doverti leggere un libro su Clojure. Se già conosci Clojure o un altro Lisp, puoi saltare direttamente al capitolo 17.

## 16.1 Strutture di controllo

La cosa più importante da capire di Clojure non sono le parentesi, ma il fatto che tutto è un'espressione che restituisce un valore. Un `if` non "fa" qualcosa: restituisce il valore del ramo vero o del ramo falso. Un `let` non "dichiara variabili": restituisce il valore dell'ultima espressione nel suo corpo. Un `for` non "cicla": restituisce un vettore di risultati.

Se vieni da Python, JavaScript o C, sei abituato a pensare in termini di istruzioni che *fanno cose* (assegna, stampa, modifica). In Clojure pensi in termini di espressioni che *producono valori*. Questa differenza si vede ovunque:

```clojure
;; In un linguaggio imperativo penseresti:
;; "se la larghezza è grande, crea un box, altrimenti crea un cilindro"
;;
;; In Clojure dici:
;; "il risultato è un box o un cilindro, a seconda della larghezza"
(register piece
  (if (> width 50)
    (box width width 10)
    (cyl (/ width 2) 10)))
```

Il `register` riceve direttamente il valore restituito da `if`. Non c'è bisogno di una variabile intermedia o di un assegnamento condizionale. Questo pattern si ripete in tutto il codice Ridley: le strutture di controllo vivono dentro le espressioni, non attorno ad esse.

Ci sono eccezioni. `register` stesso è una di queste: non restituisce un valore, ma memorizza una mesh nel registro e la mostra nel viewport. `def` è un'altra: crea una variabile globale per il suo effetto, non per il suo valore di ritorno. `println`, `color` (nella forma globale), `sdf-resolution!`, `attach!` sono tutti comandi che *fanno cose*. In Clojure li chiamano "side effects", e non sono proibiti: sono solo la minoranza. La maggior parte del codice che scriverai produce valori e li passa ad altre espressioni.

### If, when, cond, case

`if` ha due rami: vero e falso. L'hai già visto nell'esempio sopra. La forma è `(if condizione valore-se-vero valore-se-falso)`.

`when` è un `if` senza il ramo falso. Utile quando vuoi calcolare un valore solo se una condizione è vera (se è falsa torna nil, un valore speciale che vuol dire `assenza di valore`):

```clojure
(when (> n 0)
  (register holes
    (concat-meshes
      (for [i (range n)]
        (attach (cyl 3 20) (th (* i (/ 360 n)) (f 15)))))))
```

`cond` è una catena di condizioni:

```clojure
(cond
  (< r 5)   (sphere r)
  (< r 15)  (cyl r 20)
  :else     (box r r r))
```

`case` confronta un valore con costanti:

```clojure
(case shape-type
  :round (cyl 10 20)
  :square (box 20 20 20)
  :hex (cyl 10 20 6)
  (box 10 10 10))            ;; default
```

### For: generare sequenze

`for` è una list comprehension, non un loop imperativo. Produce un vettore di risultati:

```clojure
;; 12 cilindri in cerchio
(for [i (range 12)]
  (attach (cyl 3 20) (th (* i 30)) (f 40)))
```

Il risultato è un vettore di 12 mesh. Per farne una sola, avvolgilo in `concat-meshes` o `mesh-union`.

`for` accetta più binding e filtri:

```clojure
;; Griglia 5x5 con filtro
(for [x (range 5)
      y (range 5)
      :when (not= x y)]
  (attach (box 4 4 4) (rt (* x 10)) (u (* y 10))))
```

### Dotimes: ripetere N volte

`dotimes` esegue un blocco N volte. A differenza di `for`, non produce una sequenza: è per gli effetti collaterali (muovere la tartaruga, registrare mesh).

```clojure
(dotimes [i 6]
  (register (symbol (str "post-" i)) (cyl 3 30))
  (f 20))
```

### Loop/recur: ricorsione controllata

Per iterazioni più complesse dove `for` non basta:

```clojure
(loop [n 5 r 20]
  (when (> n 0)
    (register (symbol (str "ring-" n)) (cyl r 3))
    (f 8)
    (recur (dec n) (* r 0.8))))
```

`loop` definisce i binding iniziali, `recur` salta all'inizio del loop con nuovi valori. È l'unico modo idiomatico di fare un ciclo con stato mutevole in Clojure.

## 16.2 Let e variabili locali

`let` crea variabili locali. I binding sono coppie nome-valore dentro parentesi quadre:

```clojure
(let [r 15
      h (* r 2)
      base (cyl r h)
      cap (attach (sphere r) (f h))]
  (register piece (mesh-union base cap)))
```

Le variabili vivono solo dentro il `let`. Fuori, `r`, `h`, `base`, `cap` non esistono.

I binding sono sequenziali: un binding può riferirsi a quelli precedenti (`h` usa `r`, `base` usa `r` e `h`).

### Def e defn: definizioni globali

`def` crea una variabile globale:

```clojure
(def wall-thickness 2)
(def bolt-r 3)
```

`defn` crea una funzione con un nome:

```clojure
(defn pillar [r h]
  (mesh-union
    (cyl r h)
    (attach (sphere (* r 1.2)) (f h))))
```

Le funzioni si chiamano come tutto il resto in Clojure: nome e argomenti dentro parentesi.

```clojure
(register p1 (pillar 5 40))
(register p2 (pillar 8 60))
```

## 16.3 Funzioni anonime

Le funzioni anonime servono quando hai bisogno di una funzione piccola da passare a `map`, `filter`, `for`, o a una shape-fn, senza darle un nome.

La forma estesa:

```clojure
(fn [x] (* x 2))
```

La forma compatta (reader macro `#()`):

```clojure
#(* % 2)            ;; un argomento: % è l'argomento
#(+ %1 %2)          ;; due argomenti: %1 e %2
```

Esempio pratico con `map`:

```clojure
;; Raddoppia tutti i raggi
(def radii [5 8 12 15])
(def doubled (map #(* % 2) radii))
;; => (10 16 24 30)
```

Con le shape-fn:

```clojure
;; Rastremazione custom
(register tapered-tube
  (loft (circle 20)
    #(scale-shape %1 (- 1 (* 0.5 %2)))
    (f 60)))
```

Qui `%1` è la shape e `%2` è `t` (il progresso lungo il percorso, da 0 a 1).

## 16.4 Map, filter, reduce

### Map: trasformare una sequenza

`map` applica una funzione a ogni elemento di una sequenza:

```clojure
(def sizes [10 15 20 25])
(map #(cyl % 30) sizes)      ;; => (cyl-10 cyl-15 cyl-20 cyl-25)
```

### Filter: selezionare elementi

`filter` tiene solo gli elementi che soddisfano un predicato:

```clojure
(def marks (keys (anchors skel)))
(def mid-marks (filter #(re-find #"^mid-" (name %)) marks))
```

### Reduce: accumulare un risultato

`reduce` combina gli elementi di una sequenza con una funzione a due argomenti:

```clojure
;; Somma
(reduce + [1 2 3 4 5])    ;; => 15

;; Unione progressiva di mesh
(reduce mesh-union meshes)
```

Per le booleane, la forma variadica (`(mesh-union [a b c])`) è di solito più chiara di `reduce`. Ma `reduce` è utile quando l'operazione di combinazione non è una booleana:

```clojure
;; Applica una lista di trasformazioni in sequenza
(reduce (fn [m transform] (transform m))
        (box 20)
        [#(chamfer % :all 2)
         #(color % 0xff0000)])
```

## 16.5 Usare la REPL

La REPL (Read-Eval-Print Loop) è la riga di comando in basso nell'editor. Puoi scrivere espressioni e vedere il risultato immediatamente, senza premere Cmd+Enter per rieseguire tutto il programma.

### Eval inline vs Cmd+Enter

Cmd+Enter (o il bottone Run) riesegue tutto il codice nell'editor da zero: la scena viene svuotata e ricostruita. È il modo normale di lavorare quando scrivi il programma.

La REPL esegue una singola espressione nel contesto corrente, senza svuotare la scena. Puoi ispezionare valori, provare operazioni, aggiungere mesh temporanee, tutto senza toccare il programma principale.

```clojure
;; Nella REPL:
(bounds :my-piece)                    ;; quanto è grande?
(manifold? (mesh :my-piece))          ;; è watertight?
(mesh-diagnose (mesh :my-piece))      ;; diagnosi completa
(flash-face (mesh :my-piece) :top)    ;; dove è la faccia top?
```

### Cose utili nella REPL

`(objects)` elenca le mesh visibili. `(registered)` elenca tutto nel registro. `(info :name)` dà il riepilogo di una mesh. `(bounds :name)` dà il bounding box.

`(tweak :name)` apre il tweaking interattivo su una mesh registrata. `(pilot :name)` apre il posizionamento interattivo.

`(export :name)` esporta una mesh. `(export :a :b :3mf)` esporta in 3MF multi-materiale.

`(hide :name)`, `(show :name)`, `(hide-all)`, `(show-all)` controllano la visibilità.

Le espressioni REPL non modificano il codice nell'editor. Se trovi un valore che ti piace (un raggio, una posizione), scrivilo a mano nel programma. L'eccezione è `tweak` e `pilot`, che possono riscrivere il sorgente quando confermi.
