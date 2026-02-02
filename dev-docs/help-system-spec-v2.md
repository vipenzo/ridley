# Help System — Specifica Completa

## Obiettivo

Sistema di ricerca e inserimento che permette di trovare e inserire qualsiasi simbolo Ridley/Clojure tramite voce, con template che includono default sensati e pronti all'esecuzione.

## Architettura

```
┌─────────────────────────────────────────────────────────────┐
│  "help def"                                                 │
│       │                                                     │
│       ▼                                                     │
│  ┌─────────────────────────────────────────┐               │
│  │  FUZZY SEARCH                           │               │
│  │  cerca in tutti i tier                  │               │
│  └────────────────┬────────────────────────┘               │
│                   │                                         │
│                   ▼                                         │
│  ┌─────────────────────────────────────────┐               │
│  │  RISULTATI (ranked)                     │               │
│  │  1. defn     Definisce una funzione     │               │
│  │  2. def      Definisce una variabile    │               │
│  │  3. defonce  Definisce una volta        │               │
│  └────────────────┬────────────────────────┘               │
│                   │                                         │
│       "uno" ──────┘                                         │
│                   │                                         │
│                   ▼                                         │
│  ┌─────────────────────────────────────────┐               │
│  │  INSERISCE TEMPLATE                     │               │
│  │  (defn nome [x]                         │               │
│  │    |)                                   │               │
│  │                                         │               │
│  │  Cursore posizionato su |               │               │
│  └─────────────────────────────────────────┘               │
└─────────────────────────────────────────────────────────────┘
```

## Comandi vocali

| IT | EN | Azione |
|----|-----|--------|
| `help` | `help` | Mostra categorie (Ridley, Clojure base, Clojure esteso) |
| `help X` | `help X` | Cerca X in tutti i simboli |
| `help ridley` | `help ridley` | Mostra tutti i simboli Ridley |
| `help clojure` | `help clojure` | Mostra Clojure base + esteso |
| `uno/due/tre...` | `one/two/three...` | Seleziona dalla lista |
| `prossimo` | `next` | Pagina successiva (se > 10 risultati) |
| `precedente` | `previous` | Pagina precedente |
| `esci` | `exit` | Chiude help senza inserire |

## Fuzzy matching

La ricerca deve essere tollerante:
- `def` → defn, def, defonce, defmacro...
- `box` → box
- `cerchio` → circle (alias italiano)
- `estrudi` → extrude (alias italiano)
- `dot` → dotimes, doto
- `filtr` → filter

Algoritmo: prefix match + contains match, ranked per:
1. Match esatto
2. Prefix match
3. Contains match
4. Alias match (IT → EN)

---

# TIER 1: Ridley DSL

~45 simboli essenziali per modellare in Ridley.

## Movimento Turtle

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `f` | `(f 20)` | distanza | Muove avanti (negativo = indietro) |
| `th` | `(th 45)` | gradi | Ruota orizzontale (yaw) |
| `tv` | `(tv 45)` | gradi | Ruota verticale (pitch) |
| `tr` | `(tr 45)` | gradi | Ruota su asse (roll) |
| `arc-h` | `(arc-h 20 90)` | raggio, gradi | Arco orizzontale |
| `arc-v` | `(arc-v 20 90)` | raggio, gradi | Arco verticale |
| `bezier-to` | `(bezier-to [50 30 0])` | punto | Curva bezier verso punto |
| `bezier-to-anchor` | `(bezier-to-anchor :nome)` | anchor | Bezier verso anchor |
| `bezier-as` | `(bezier-as my-path)` | path | Smoothing bezier di un path |

## Primitive 3D

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `box` | `(box 20)` | dim oppure l,p,h | Cubo o parallelepipedo |
| `sphere` | `(sphere 15)` | raggio | Sfera |
| `cyl` | `(cyl 10 30)` | raggio, altezza | Cilindro |
| `cone` | `(cone 15 5 30)` | r1, r2, altezza | Cono o tronco di cono |

## Forme 2D

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `circle` | `(circle 10)` | raggio | Cerchio |
| `rect` | `(rect 20 10)` | larghezza, altezza | Rettangolo |
| `polygon` | `(polygon 6 15)` | lati, raggio | Poligono regolare |
| `star` | `(star 5 20 10)` | punte, r-ext, r-int | Stella |
| `shape` | `(shape (f 10) (th 120) (f 10) (th 120) (f 10))` | movimenti | Forma da percorso turtle |
| `stroke-shape` | `(stroke-shape my-path 2)` | path, spessore | Converte path in outline 2D |
| `path-to-shape` | `(path-to-shape my-path)` | path | Proietta path 3D su piano XY |

## Path

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `path` | `(path (f 20) (th 90) (f 20))` | movimenti | Registra percorso |
| `quick-path` | `(quick-path 20 90 30 -45 10)` | f,th,f,th... | Path da notazione compatta |
| `qp` | `(qp 20 90 30)` | f,th,f... | Alias di quick-path |
| `follow` | `(follow other-path)` | path | Splice path dentro path |
| `mark` | `(mark :nome)` | keyword | Marca posizione (dentro path) |
| `follow-path` | `(follow-path my-path)` | path | Esegue path sulla turtle |

## Anchor e Navigazione

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `with-path` | `(with-path skeleton\n  (goto :nome))` | path, body | Risolve mark di un path |
| `goto` | `(goto :nome)` | keyword | Vai ad anchor |
| `look-at` | `(look-at :nome)` | keyword | Orienta verso anchor |
| `path-to` | `(path-to :nome)` | keyword | Path verso anchor |
| `get-anchor` | `(get-anchor :nome)` | keyword | Dati anchor |

## Operazioni Generative

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `extrude` | `(extrude (circle 5) (f 30))` | forma, movimenti | Estrude forma lungo percorso |
| `extrude-closed` | `(extrude-closed (circle 5) square-path)` | forma, path | Estrusione chiusa (torus) |
| `loft` | `(loft (circle 20) #(scale %1 (- 1 %2)) (f 30))` | forma, fn, movimenti | Estrusione con trasformazione |
| `loft-n` | `(loft-n 32 (circle 20) #(scale %1 (- 1 %2)) (f 30))` | steps, forma, fn, mov | Loft con N steps |
| `revolve` | `(revolve profile :y)` | forma, asse | Rivoluzione |

## Stato Turtle

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `reset` | `(reset)` | [pos] [heading] [up] | Reset turtle |
| `push-state` | `(push-state)` | - | Salva stato su stack |
| `pop-state` | `(pop-state)` | - | Ripristina stato |
| `clear-stack` | `(clear-stack)` | - | Svuota stack |
| `pen` | `(pen :on)` | :on/:off | Controllo penna |
| `get-turtle` | `(get-turtle)` | - | Stato turtle completo |
| `turtle-position` | `(turtle-position)` | - | Posizione corrente |
| `turtle-heading` | `(turtle-heading)` | - | Heading corrente |
| `turtle-up` | `(turtle-up)` | - | Vettore up corrente |
| `attached?` | `(attached?)` | - | Turtle è attached? |
| `last-mesh` | `(last-mesh)` | - | Ultima mesh creata |

## Registry

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `register` | `(register :nome mesh)` | keyword, mesh | Registra mesh con nome |
| `show!` | `(show! :nome)` | keyword | Mostra mesh |
| `hide!` | `(hide! :nome)` | keyword | Nascondi mesh |
| `show-all!` | `(show-all!)` | - | Mostra tutto |
| `hide-all!` | `(hide-all!)` | - | Nascondi tutto |

## Boolean (Manifold)

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `mesh-union` | `(mesh-union a b)` | mesh... | Unione |
| `mesh-difference` | `(mesh-difference base tool)` | base, tool... | Sottrazione |
| `mesh-intersection` | `(mesh-intersection a b)` | mesh... | Intersezione |
| `mesh-hull` | `(mesh-hull a b)` | mesh... | Convex hull |

## Trasformazioni Mesh

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `attach` | `(attach mesh (f 10) (th 45))` | mesh, movimenti | Trasforma mesh in place |
| `clone` | `(clone mesh (f 10) (th 45))` | mesh, movimenti | Copia trasformata |
| `attach-face` | `(attach-face mesh :top (f 10))` | mesh, face, mov | Muove faccia |
| `clone-face` | `(clone-face mesh :top (f 10))` | mesh, face, mov | Estrude faccia |
| `inset` | `(inset 3)` | distanza | Inset faccia (dentro attach/clone-face) |

## Trasformazioni Forma 2D

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `scale` | `(scale shape 0.5)` | forma, fattore | Scala forma |
| `rotate-shape` | `(rotate-shape shape 45)` | forma, gradi | Ruota forma |
| `translate` | `(translate shape 10 5)` | forma, dx, dy | Trasla forma |
| `translate-shape` | `(translate-shape shape 10 5)` | forma, dx, dy | Alias translate |
| `morph` | `(morph shape-a shape-b 0.5)` | forma1, forma2, t | Interpola forme |
| `resample` | `(resample shape 32)` | forma, n | Ricampiona a n punti |

## Colore e Materiale

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `color` | `(color 0xff0000)` | hex oppure r,g,b | Imposta colore |
| `material` | `(material :metalness 0.8 :roughness 0.2)` | props | Imposta materiale |
| `reset-material` | `(reset-material)` | - | Reset materiale default |

## Panel (Text Billboard)

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `panel` | `(panel 40 20)` | larghezza, altezza | Crea pannello testo 3D |
| `out` | `(out :nome \"testo\")` | target, testo | Scrive su pannello |
| `append` | `(append :nome \"testo\")` | target, testo | Aggiunge a pannello |
| `clear` | `(clear :nome)` | target | Svuota pannello |

## Face Operations

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `list-faces` | `(list-faces mesh)` | mesh | Lista facce |
| `face-ids` | `(face-ids mesh)` | mesh | Solo ID facce |
| `face-info` | `(face-info mesh :top)` | mesh, face | Info dettagliate |
| `get-face` | `(get-face mesh :top)` | mesh, face | Info faccia |
| `flash-face` | `(flash-face mesh :top)` | mesh, face | Evidenzia temporaneo |
| `highlight-face` | `(highlight-face mesh :top)` | mesh, face | Evidenzia permanente |
| `clear-highlights` | `(clear-highlights)` | - | Rimuovi evidenziazioni |

## Settings

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `resolution` | `(resolution :n 32)` | :n/:a/:s, valore | Risoluzione curve |
| `joint-mode` | `(joint-mode :round)` | :flat/:round/:tapered | Modo giunture |

## Export

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `export` | `(export :nome)` | mesh o keyword | Esporta STL |

## Debug

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `println` | `(println \"debug:\" x)` | vals | Stampa con newline |
| `print` | `(print \"no newline\")` | vals | Stampa senza newline |
| `prn` | `(prn {:a 1})` | vals | Stampa struttura dati |
| `log` | `(log \"debug\" x)` | vals | Output a console browser |
| `T` | `(T \"label\" expr)` | label, expr | Tap: stampa e ritorna valore |

---

# TIER 2: Clojure Base

~35 simboli Clojure essenziali per programmare.

## Definizioni

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `defn` | `(defn nome [x]\n  \|)` | nome, params, body | Definisce funzione |
| `def` | `(def nome valore)` | nome, valore | Definisce variabile |
| `let` | `(let [x 10]\n  \|)` | bindings, body | Binding locali |
| `fn` | `(fn [x] \|)` | params, body | Funzione anonima |
| `defonce` | `(defonce nome valore)` | nome, valore | Definisce una volta |

## Condizionali

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `if` | `(if condizione\n  allora\n  altrimenti)` | test, then, else | Se-allora-altrimenti |
| `when` | `(when condizione\n  \|)` | test, body | Quando (senza else) |
| `cond` | `(cond\n  test1 risultato1\n  test2 risultato2\n  :else default)` | pairs | Condizioni multiple |
| `case` | `(case valore\n  val1 risultato1\n  val2 risultato2\n  default)` | expr, pairs | Match esatto |
| `if-let` | `(if-let [x (something)]\n  (use x)\n  fallback)` | binding, then, else | If con binding |
| `when-let` | `(when-let [x (something)]\n  (use x))` | binding, body | When con binding |

## Iterazione

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `for` | `(for [x coll]\n  \|)` | bindings, body | List comprehension |
| `doseq` | `(doseq [x coll]\n  \|)` | bindings, body | Iterazione side-effect |
| `dotimes` | `(dotimes [i 10]\n  \|)` | binding, body | Ripeti N volte |
| `loop` | `(loop [i 0]\n  (when (< i 10)\n    \|\n    (recur (inc i))))` | bindings, body | Loop con recur |
| `recur` | `(recur (inc i))` | args | Ricorsione in coda |

## Sequenze Base

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `map` | `(map f coll)` | fn, coll | Applica f a ogni elemento |
| `filter` | `(filter pred coll)` | pred, coll | Filtra elementi |
| `reduce` | `(reduce f init coll)` | fn, init, coll | Riduce collezione |
| `first` | `(first coll)` | coll | Primo elemento |
| `last` | `(last coll)` | coll | Ultimo elemento |
| `rest` | `(rest coll)` | coll | Tutto tranne primo |
| `nth` | `(nth coll 2)` | coll, index | Elemento alla posizione |
| `count` | `(count coll)` | coll | Numero elementi |
| `range` | `(range 10)` | n oppure start,end,step | Sequenza numeri |

## Aritmetica

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `+` | `(+ 1 2)` | nums | Somma |
| `-` | `(- 10 3)` | nums | Sottrazione |
| `*` | `(* 2 3)` | nums | Moltiplicazione |
| `/` | `(/ 10 2)` | nums | Divisione |
| `inc` | `(inc x)` | n | Incrementa di 1 |
| `dec` | `(dec x)` | n | Decrementa di 1 |
| `mod` | `(mod 10 3)` | n, divisor | Modulo |
| `max` | `(max 1 5 3)` | nums | Massimo |
| `min` | `(min 1 5 3)` | nums | Minimo |

## Math (senza prefisso)

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `PI` | `PI` | - | Pi greco |
| `sin` | `(sin x)` | radianti | Seno |
| `cos` | `(cos x)` | radianti | Coseno |
| `sqrt` | `(sqrt x)` | n | Radice quadrata |
| `pow` | `(pow x y)` | base, exp | Potenza |
| `abs` | `(abs -5)` | n | Valore assoluto |
| `floor` | `(floor x)` | n | Arrotonda giù |
| `ceil` | `(ceil x)` | n | Arrotonda su |
| `round` | `(round x)` | n | Arrotonda |
| `atan2` | `(atan2 y x)` | y, x | Arctangent a due argomenti |

## Confronto

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `=` | `(= a b)` | vals | Uguaglianza |
| `not=` | `(not= a b)` | vals | Diverso |
| `<` | `(< a b)` | nums | Minore |
| `>` | `(> a b)` | nums | Maggiore |
| `<=` | `(<= a b)` | nums | Minore o uguale |
| `>=` | `(>= a b)` | nums | Maggiore o uguale |

## Logica

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `and` | `(and a b)` | exprs | E logico |
| `or` | `(or a b)` | exprs | O logico |
| `not` | `(not x)` | expr | Negazione |

---

# TIER 3: Clojure Esteso

~50 simboli aggiuntivi per casi più avanzati.

## Funzioni Higher-Order

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `apply` | `(apply f args)` | fn, args | Applica con lista args |
| `partial` | `(partial f arg1)` | fn, args | Applicazione parziale |
| `comp` | `(comp f g)` | fns | Composizione funzioni |
| `identity` | `(identity x)` | x | Ritorna argomento |
| `constantly` | `(constantly 42)` | val | Funzione costante |
| `complement` | `(complement pred)` | pred | Nega predicato |
| `juxt` | `(juxt f g)` | fns | Applica multiple fn |

## Sequenze Avanzate

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `mapcat` | `(mapcat f coll)` | fn, coll | Map + concat |
| `keep` | `(keep f coll)` | fn, coll | Map filtrando nil |
| `remove` | `(remove pred coll)` | pred, coll | Opposto di filter |
| `take` | `(take 5 coll)` | n, coll | Primi N elementi |
| `drop` | `(drop 5 coll)` | n, coll | Senza primi N |
| `take-while` | `(take-while pred coll)` | pred, coll | Prendi mentre true |
| `drop-while` | `(drop-while pred coll)` | pred, coll | Salta mentre true |
| `partition` | `(partition 2 coll)` | n, coll | Divide in gruppi |
| `partition-by` | `(partition-by f coll)` | fn, coll | Divide per valore f |
| `group-by` | `(group-by f coll)` | fn, coll | Raggruppa per chiave |
| `sort` | `(sort coll)` | coll | Ordina |
| `sort-by` | `(sort-by f coll)` | fn, coll | Ordina per chiave |
| `reverse` | `(reverse coll)` | coll | Inverti |
| `flatten` | `(flatten coll)` | coll | Appiattisce |
| `distinct` | `(distinct coll)` | coll | Rimuovi duplicati |
| `interleave` | `(interleave a b)` | colls | Alterna elementi |
| `interpose` | `(interpose sep coll)` | sep, coll | Inserisci separatore |

## Collezioni

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `conj` | `(conj coll x)` | coll, vals | Aggiungi elemento |
| `cons` | `(cons x coll)` | val, coll | Prependi elemento |
| `concat` | `(concat a b)` | colls | Concatena |
| `into` | `(into [] coll)` | to, from | Versa in collezione |
| `empty` | `(empty coll)` | coll | Collezione vuota stesso tipo |
| `empty?` | `(empty? coll)` | coll | È vuota? |
| `seq` | `(seq coll)` | coll | Converti a sequenza |
| `vec` | `(vec coll)` | coll | Converti a vettore |
| `set` | `(set coll)` | coll | Converti a set |

## Mappe

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `get` | `(get m :key)` | map, key | Ottieni valore |
| `get-in` | `(get-in m [:a :b])` | map, path | Ottieni nested |
| `assoc` | `(assoc m :key val)` | map, key, val | Associa valore |
| `assoc-in` | `(assoc-in m [:a :b] val)` | map, path, val | Associa nested |
| `dissoc` | `(dissoc m :key)` | map, keys | Rimuovi chiave |
| `update` | `(update m :key f)` | map, key, fn | Aggiorna con fn |
| `update-in` | `(update-in m [:a :b] f)` | map, path, fn | Aggiorna nested |
| `merge` | `(merge m1 m2)` | maps | Unisci mappe |
| `select-keys` | `(select-keys m [:a :b])` | map, keys | Sottoinsieme chiavi |
| `keys` | `(keys m)` | map | Lista chiavi |
| `vals` | `(vals m)` | map | Lista valori |
| `zipmap` | `(zipmap keys vals)` | keys, vals | Crea mappa |

## Stringhe

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `str` | `(str a b c)` | vals | Concatena stringhe |
| `subs` | `(subs s 0 5)` | s, start, end | Sottostringa |
| `format` | `(format "Ciao %s" nome)` | fmt, args | Formatta stringa |
| `clojure.string/join` | `(clojure.string/join ", " coll)` | sep, coll | Unisci con separatore |
| `clojure.string/split` | `(clojure.string/split s #",")` | s, regex | Dividi stringa |
| `clojure.string/trim` | `(clojure.string/trim s)` | s | Rimuovi spazi |
| `clojure.string/upper-case` | `(clojure.string/upper-case s)` | s | Maiuscolo |
| `clojure.string/lower-case` | `(clojure.string/lower-case s)` | s | Minuscolo |
| `clojure.string/replace` | `(clojure.string/replace s "a" "b")` | s, match, repl | Sostituisci |

## Predicati

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `nil?` | `(nil? x)` | x | È nil? |
| `some?` | `(some? x)` | x | Non è nil? |
| `number?` | `(number? x)` | x | È numero? |
| `string?` | `(string? x)` | x | È stringa? |
| `keyword?` | `(keyword? x)` | x | È keyword? |
| `symbol?` | `(symbol? x)` | x | È simbolo? |
| `map?` | `(map? x)` | x | È mappa? |
| `vector?` | `(vector? x)` | x | È vettore? |
| `coll?` | `(coll? x)` | x | È collezione? |
| `fn?` | `(fn? x)` | x | È funzione? |
| `even?` | `(even? n)` | n | È pari? |
| `odd?` | `(odd? n)` | n | È dispari? |
| `pos?` | `(pos? n)` | n | È positivo? |
| `neg?` | `(neg? n)` | n | È negativo? |
| `zero?` | `(zero? n)` | n | È zero? |

## Threading

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `->` | `(-> x (f) (g))` | x, forms | Thread-first |
| `->>` | `(->> x (f) (g))` | x, forms | Thread-last |
| `as->` | `(as-> x $ (f $) (g $))` | expr, name, forms | Thread con nome |
| `doto` | `(doto obj (method1) (method2))` | obj, forms | Chiama metodi |
| `some->` | `(some-> x (f) (g))` | x, forms | Thread con nil check |
| `some->>` | `(some->> x (f) (g))` | x, forms | Thread-last con nil check |

## Varie

| Simbolo | Template | Args | Doc IT |
|---------|----------|------|--------|
| `do` | `(do\n  expr1\n  expr2)` | exprs | Esegue in sequenza |
| `some` | `(some pred coll)` | pred, coll | Primo truthy |
| `every?` | `(every? pred coll)` | pred, coll | Tutti true? |
| `not-every?` | `(not-every? pred coll)` | pred, coll | Non tutti true? |
| `not-any?` | `(not-any? pred coll)` | pred, coll | Nessuno true? |
| `type` | `(type x)` | x | Tipo dell'oggetto |
| `rand` | `(rand)` | [n] | Numero random |
| `rand-int` | `(rand-int 10)` | n | Intero random 0..n-1 |
| `rand-nth` | `(rand-nth coll)` | coll | Elemento random |
| `shuffle` | `(shuffle coll)` | coll | Mescola collezione |

---

# Alias Italiani

Mappings per cercare con parole italiane.

```clojure
(def italian-aliases
  {;; Ridley - Movimento
   "avanti"       "f"
   "indietro"     "f"  ; con nota: usa valore negativo
   "ruota"        "th"
   "gira"         "th"
   "alza"         "tv"
   "abbassa"      "tv"
   "rollio"       "tr"
   "arco"         "arc-h"
   "bezier"       "bezier-to"
   
   ;; Ridley - Primitive
   "cubo"         "box"
   "scatola"      "box"
   "sfera"        "sphere"
   "cilindro"     "cyl"
   "cono"         "cone"
   
   ;; Ridley - Forme 2D
   "cerchio"      "circle"
   "rettangolo"   "rect"
   "poligono"     "polygon"
   "stella"       "star"
   "forma"        "shape"
   "contorno"     "stroke-shape"
   
   ;; Ridley - Path
   "percorso"     "path"
   "cammino"      "path"
   "segui"        "follow"
   "marca"        "mark"
   
   ;; Ridley - Operazioni
   "estrudi"      "extrude"
   "estrusione"   "extrude"
   "loft"         "loft"
   "rivoluzione"  "revolve"
   
   ;; Ridley - Registry
   "registra"     "register"
   "mostra"       "show!"
   "nascondi"     "hide!"
   
   ;; Ridley - Boolean
   "unione"       "mesh-union"
   "sottrazione"  "mesh-difference"
   "differenza"   "mesh-difference"
   "intersezione" "mesh-intersection"
   
   ;; Ridley - Trasformazioni
   "scala"        "scale"
   "attacca"      "attach"
   "clona"        "clone"
   
   ;; Ridley - Colore
   "colore"       "color"
   "materiale"    "material"
   
   ;; Ridley - Panel
   "pannello"     "panel"
   "scrivi"       "out"
   "pulisci"      "clear"
   
   ;; Ridley - Settings
   "risoluzione"  "resolution"
   "giuntura"     "joint-mode"
   
   ;; Ridley - Export
   "esporta"      "export"
   
   ;; Clojure
   "funzione"     "defn"
   "definisci"    "defn"
   "variabile"    "def"
   "sia"          "let"
   "se"           "if"
   "quando"       "when"
   "condizione"   "cond"
   "caso"         "case"
   "per"          "for"
   "ciclo"        "loop"
   "ripeti"       "dotimes"
   "mappa"        "map"
   "filtra"       "filter"
   "riduci"       "reduce"
   "primo"        "first"
   "ultimo"       "last"
   "conta"        "count"
   "intervallo"   "range"
   "aggiungi"     "conj"
   "concatena"    "concat"
   "ordina"       "sort"
   "inverti"      "reverse"
   "unisci"       "merge"
   "chiavi"       "keys"
   "valori"       "vals"
   "stringa"      "str"
   "stampa"       "println"})
```

---

# Implementazione

## Struttura dati

```clojure
(def help-db
  {:entries
   {:f {:tier :ridley
        :template "(f 20)"
        :args [{:name "distanza" :type :number :default 20}]
        :doc {:it "Muove avanti (negativo = indietro)"
              :en "Move forward (negative = backward)"}
        :aliases {:it ["avanti"] :en ["forward"]}}
    
    :defn {:tier :clojure-base
           :template "(defn nome [x]\n  |)"
           :args [{:name "nome" :type :symbol :placeholder "nome"}
                  {:name "params" :type :vector :placeholder "[x]"}
                  {:name "body" :type :expr :cursor true}]
           :doc {:it "Definisce una funzione"
                 :en "Define a function"}
           :aliases {:it ["funzione" "definisci"] :en ["function" "define"]}}
    
    ;; ... tutti gli altri
    }
   
   :tiers
   {:ridley {:order 1 :name {:it "Ridley DSL" :en "Ridley DSL"}}
    :clojure-base {:order 2 :name {:it "Clojure Base" :en "Clojure Base"}}
    :clojure-extended {:order 3 :name {:it "Clojure Esteso" :en "Clojure Extended"}}}})
```

## Funzioni core

```clojure
(defn search-help
  "Cerca simboli per query. Ritorna lista ordinata per rilevanza."
  [query language]
  (let [q (str/lower-case query)
        entries (:entries help-db)]
    (->> entries
         (map (fn [[sym entry]]
                (let [score (calculate-match-score q sym entry language)]
                  (when (pos? score)
                    (assoc entry :symbol sym :score score)))))
         (remove nil?)
         (sort-by :score >)
         (take 10))))

(defn calculate-match-score
  "Calcola score di match. Più alto = migliore."
  [query symbol entry language]
  (let [sym-str (name symbol)
        aliases (get-in entry [:aliases language] [])]
    (cond
      ;; Match esatto
      (= query sym-str) 100
      
      ;; Match esatto alias
      (some #(= query %) aliases) 95
      
      ;; Prefix match simbolo
      (str/starts-with? sym-str query) (+ 80 (- 10 (count query)))
      
      ;; Prefix match alias  
      (some #(str/starts-with? % query) aliases) 75
      
      ;; Contains match simbolo
      (str/includes? sym-str query) 50
      
      ;; Contains match alias
      (some #(str/includes? % query) aliases) 45
      
      :else 0)))

(defn insert-template
  "Inserisce template nel buffer, posiziona cursore."
  [entry]
  (let [template (:template entry)
        cursor-pos (str/index-of template "|")
        clean-template (str/replace template "|" "")]
    {:text clean-template
     :cursor-offset cursor-pos}))

(defn get-categories
  "Ritorna lista categorie per help senza argomenti."
  [language]
  (->> (:tiers help-db)
       (sort-by (comp :order val))
       (map (fn [[tier-key tier-info]]
              {:key tier-key
               :name (get-in tier-info [:name language])
               :count (count (filter #(= tier-key (:tier (val %)))
                                     (:entries help-db)))}))))
```

## UI State

```clojure
(defonce help-state
  (atom {:active? false
         :query nil
         :results []
         :selected-index 0
         :page 0
         :items-per-page 7}))
```

## Comandi vocali

```clojure
(def help-voice-commands
  {:activate
   {:it ["help" "aiuto" "cerca"]
    :en ["help" "search"]}
   
   :select
   {:it {"uno" 0 "due" 1 "tre" 2 "quattro" 3 "cinque" 4
         "sei" 5 "sette" 6 "otto" 7 "nove" 8 "dieci" 9}
    :en {"one" 0 "two" 1 "three" 2 "four" 3 "five" 4
         "six" 5 "seven" 6 "eight" 7 "nine" 8 "ten" 9}}
   
   :navigate
   {:it {"prossimo" :next "precedente" :prev "esci" :exit}
    :en {"next" :next "previous" :prev "exit" :exit}}
   
   :categories
   {:it {"ridley" :ridley "clojure" :clojure-base "esteso" :clojure-extended}
    :en {"ridley" :ridley "clojure" :clojure-base "extended" :clojure-extended}}})
```

---

# UI Visuale

## Layout risultati

```
┌─────────────────────────────────────────┐
│  🔍 help: def                           │
├─────────────────────────────────────────┤
│  1. defn     Definisce una funzione     │
│  2. def      Definisce una variabile    │
│  3. defonce  Definisce una volta        │
├─────────────────────────────────────────┤
│  Pagina 1/1  •  "uno"-"tre" / "esci"    │
└─────────────────────────────────────────┘
```

## Layout categorie (help senza query)

```
┌─────────────────────────────────────────┐
│  📚 Categorie                           │
├─────────────────────────────────────────┤
│  1. Ridley DSL         (45 simboli)     │
│  2. Clojure Base       (35 simboli)     │
│  3. Clojure Esteso     (50 simboli)     │
├─────────────────────────────────────────┤
│  "uno"-"tre" / "help X" per cercare     │
└─────────────────────────────────────────┘
```

## Layout dettaglio (dopo selezione categoria)

```
┌─────────────────────────────────────────┐
│  📦 Ridley DSL                 [1/7]    │
├─────────────────────────────────────────┤
│  1. f         Muove avanti              │
│  2. th        Ruota orizzontale         │
│  3. tv        Ruota verticale           │
│  4. tr        Ruota roll                │
│  5. box       Crea cubo                 │
│  6. sphere    Crea sfera                │
│  7. cyl       Crea cilindro             │
├─────────────────────────────────────────┤
│  "prossimo" / "precedente" / "esci"     │
└─────────────────────────────────────────┘
```

---

# File da creare/modificare

```
src/ridley/voice/
├── help/
│   ├── db.cljs          # Database simboli (tutti i tier)
│   ├── search.cljs      # Fuzzy search
│   ├── commands.cljs    # Comandi vocali help
│   └── ui.cljs          # Rendering UI help panel
```

---

# Test

```clojure
;; Search tests
(search-help "def" :it)
;; => [{:symbol :defn :score 80 ...}
;;     {:symbol :def :score 100 ...}
;;     {:symbol :defonce :score 75 ...}]

(search-help "funzione" :it)
;; => [{:symbol :defn :score 95 ...}]  ; via alias

(search-help "box" :en)
;; => [{:symbol :box :score 100 ...}]

(search-help "cubo" :it)
;; => [{:symbol :box :score 95 ...}]  ; via alias

(search-help "bezier" :en)
;; => [{:symbol :bezier-to :score 80 ...}
;;     {:symbol :bezier-to-anchor :score 75 ...}
;;     {:symbol :bezier-as :score 75 ...}]

;; Insert tests
(insert-template (:defn (:entries help-db)))
;; => {:text "(defn nome [x]\n  )"
;;     :cursor-offset 17}
```

---

# Priorità implementazione

1. [ ] Struttura dati help-db con tutti i tier
2. [ ] Fuzzy search base
3. [ ] Comandi vocali (help X, uno/due/tre, esci)
4. [ ] UI panel risultati
5. [ ] Navigazione categorie
6. [ ] Alias italiani
7. [ ] Paginazione
