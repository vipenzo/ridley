<!--
Capitolo 19 "Estendere Ridley" (bozza 2026-09-10, Claude-docs). Scaletta decisa
da Vincenzo il 2026-09-10: 19.1 il contratto delle shape-fn; 19.2 il contratto
delle thickness-fn; 19.3 registrare e ispezionare (il materiale "Internals" del
piano §6.3-6.5); 19.4 il path come dato; 19.5 le librerie sotto il cofano.
La sezione "architettura di uno script parametrico" è stata TOLTA su
decisione di Vincenzo (2026-09-10): regole di stile da clojurista, non di Ridley.
Convenzioni: prosa in italiano, codice in inglese, no em-dash, paragrafi su
una riga. Il capitolo NON ripete il cap. 6 (§6.10-6.12 introducono già
shape-fn e thickness-fn custom), il cap. 9 (uso pratico delle librerie) né il
cap. 16 (sintassi Clojure): li presuppone e li cita.
Fonti: src/ridley/turtle/shape_fn.cljs (contratto, metadata, *path-length*,
shell/woven-shell), docs/Spec.md §"Shape functions", §revolve, §18 Internals,
§Path utilities; docs/Architecture.md §5.1-5.5, §6.3, §11.3;
src/ridley/library/core.cljs, storage.cljs, src/ridley/editor/repl.cljs;
librerie built-in gears/puppet/weave ed examples/recursive-tree,
procedural-bowl, spiral-shell, meshing-gears.
Accertamenti chiusi il 2026-09-10 con dev-docs/brief-ch19-accertamenti-risposte.md
(Code, misurato allo schermo): defmacro/atom/defmulti/protocolli funzionano in
SCI (defmacro non attraversa il prefisso di libreria; defmulti su nome già bound
è un no-op); js/ chiuso; path->data riparato e i mark come chiavi top-level del
path; revolve: t = i/steps, l'ultimo anello di un giro intero si ferma a 63/64,
*path-length* = |angolo|·r; header `;; Requires: a, b`; angolo thickness-fn
(-π, π] e con :style :pattern la :fn riceve l'arco-lunghezza; :point-count
tolto; shape-fn con base parziale ora dà errore; tutte le built-in conservano
le chiavi di shell, morphed non è componibile; esposti smoothstep,
shape-centroid, current-path-length; embroid ha :fn; :fn di shell ha la sua
:softness; defonce esportato, ^:private escluso; weave.clj bonificata.
Cap. 6 e cap. 9 (IT+EN) allineati lo stesso giorno. Versione EN:
en/19-estendere-ridley.md (2026-09-15), da tenere in sync.
-->

# 19. Estendere Ridley

<!-- level: advanced -->

I capitoli precedenti spiegano come usare Ridley. Questo come estenderlo: spiega i contratti che le funzioni del linguaggio rispettano fra loro, così che le tue rispettino gli stessi, e apre il cofano su come il codice che scrivi viene valutato. Non serve per i primi cento oggetti. Serve quando una shape-fn tua fa una cosa strana, quando vuoi generare la scena da codice, o quando una libreria che hai scritto non si comporta come ti aspettavi.

## 19.1 Il contratto delle shape-fn

Il capitolo 6 mostra come si scrive una shape-fn con `shape-fn` e come si compone con le built-in. Qui c'è quello che il capitolo 6 non dice: cosa è davvero una shape-fn, cosa il loft si aspetta da lei, e gli errori che possono non farla funzionare.

### Una funzione con un'etichetta

Una shape-fn è una funzione Clojure `(fn [t] -> shape)` che porta il metadata `{:type :shape-fn}`. Il metadata è tutto: è quello che `loft` e `revolve` controllano per decidere se hanno davanti una shape statica (un'estrusione a profilo costante) o una shape da rivalutare a ogni anello. `shape-fn?` non fa altro che leggerlo:

```clojure
(shape-fn? (fn [t] (circle 20)))            ; false: a bare function has no metadata
```

Questa è la trappola. Una lambda che restituisce una shape non è una shape-fn, perché non ha l'etichetta: il loft non la riconosce come profilo variabile. Per costruirne una vera si passa da `shape-fn`, che mette l'etichetta e registra anche `:base`, la shape (o la shape-fn) di partenza. Il numero di punti del profilo non viene controllato da nessuno: se la tua funzione di trasformazione lo cambia, il loft se ne accorge solo quando prova a collegare anelli con un numero di vertici diverso. La regola del capitolo 6 (stesso numero di punti in ingresso e in uscita) è un contratto, non un consiglio.

### t, e quando non arriva a 1

`t` è la frazione del percorso: 0 sul primo anello, 1 sull'ultimo, e il numero di anelli in mezzo lo decide `loft-n` (o la risoluzione globale). Su un path con spigoli e segmenti corti l'ultimo anello può fermarsi a un `t` un po' sotto 1: è il motivo per cui `loft+` restituisce come `:end-face` la sezione effettivamente stampata sull'ultimo anello, e non una rivalutazione della shape-fn a `t=1`. Se concateni geometria a mano, usa quella.

Con `revolve` la shape-fn viene valutata a ogni passo di rivoluzione, con `t` uguale alla frazione dell'angolo percorso. Su un giro intero l'ultimo anello coincide col primo, quindi il profilo a `t = 1` non viene mai costruito: con 64 passi l'ultimo valore è 63/64. Su una rivoluzione parziale `t` arriva a 1, e quell'anello è la faccia di chiusura. Prima degli anelli il profilo viene valutato una volta a `t = 0` come sonda, per contare i punti.

### Ragionare in millimetri: `current-path-length`

`t` è una frazione, e per molte trasformazioni basta: "a metà percorso il profilo è il doppio" non ha bisogno di sapere quanto è lungo il percorso. Quando invece la forma dipende da una misura, per esempio un rigonfiamento lungo dieci millimetri qualunque sia la lunghezza del path, ti serve convertire `t` in millimetri, e la lunghezza totale la chiedi a `(current-path-length)`: dentro un loft è la lunghezza del path, dentro un `revolve` la lunghezza dell'arco percorso dal centroide del profilo (angolo per raggio).

```clojure
;; a bulge 10 mm long at the start, whatever the path length
(register bulge
  (loft (shape-fn (circle 12)
          (fn [s t]
            (let [mm (* t (or (current-path-length) 1))]
              (scale-shape s (+ 1 (* 0.4 (max 0 (- 1 (/ mm 10)))))))))
        (path (f 40) (arc-h 20 90) (f 30))))
```

L'`or` c'è perché fuori da un loft o da un revolve la funzione restituisce `nil` (e `revolve` valuta il profilo una volta a `t = 0` prima di iniziare, come sonda per contare i punti, quando la lunghezza non è ancora nota): una shape-fn deve poter essere valutata anche lì senza rompersi.

### Composizione, e cosa passa attraverso

`(-> shape (A ...) (B ...))` costruisce una shape-fn B la cui `:base` è la shape-fn A: quando il loft chiede B a un certo `t`, B chiede prima A allo stesso `t` e trasforma il risultato. L'esecuzione va dall'interno verso l'esterno, e ogni anello rivaluta l'intera catena (non c'è memoizzazione: una catena lunga su un `loft-n 256` costa in proporzione).

Un dettaglio del contratto che il capitolo 6 enuncia solo come regola: `shell` e `woven-shell` non trasformano i punti, **annotano** la shape con chiavi extra (`:shell-mode`, `:shell-thickness`, `:shell-values`, e `:shell-offsets` per il woven) che il loft legge per costruire il doppio anello, esterno e interno. Tutte le built-in conservano quelle chiavi, perché trasformano i punti con `update` e lasciano il resto: `tapered`, `twisted`, `fluted`, `capped` dopo uno `shell` producono la stessa mesh a doppio anello. Il motivo per cui `shell` sta comunque bene in fondo è di senso, non di meccanismo: i valori di spessore sono calcolati sul profilo che `shell` riceve, uno per punto, e una deformazione fatta dopo (`fluted`, `noisy`) sposta i punti lasciando i valori dov'erano. Se scrivi una shape-fn tua, fai lo stesso: aggiorna `:points` (e `:holes`) con `update` sulla shape ricevuta invece di costruirne una nuova, e le chiavi che non conosci passano. L'unica built-in che non entra in una catena è `morphed`: prende due shape statiche, e con una shape-fn come primo argomento dà errore.

### La forma parziale

`(tapered :to 0.5)`, senza shape davanti, non restituisce una shape-fn: restituisce la trasformazione nuda, `(fn [shape t] -> shape)`, senza metadata. È la forma che `loft` accetta come secondo argomento accanto a una shape statica, e che `loft+` accetta dentro un `transform->` dove il profilo arriva dal passo precedente. Il predicato la vede come una funzione qualunque (`(shape-fn? (tapered :to 0.5))` è `false`), e `shape-fn` la rifiuta con un errore che lo dice: come base vuole una shape o una shape-fn, perché una forma parziale non ha un profilo da cui partire. Se vuoi comporre una trasformazione tua con una built-in, dai a `shape-fn` la forma piena, `(tapered (circle 20) :to 0.5)`.

### Gli helper che hai a disposizione

Oltre alle operazioni su shape elencate nel capitolo 6, dentro una trasformazione hai gli helper pensati per questo lavoro: `angle`, che dà l'angolo in radianti di un punto rispetto all'origine (non al centroide: se il profilo non è centrato, sottrai prima il centro); `displace-radial`, che sposta ogni punto lungo la direzione dal centroide con un offset calcolato da una funzione `(fn [point] -> number)`, fori compresi; `noise` e `fbm`, rumore deterministico e continuo in due variabili (`fbm` con ottave, lacunarità e guadagno opzionali); `sample-heightmap`, che campiona una heightmap in `[u v]` con interpolazione bilineare e ripetizione ai bordi. Il capitolo 6 li usa quasi tutti negli esempi; qui contano le firme:

```clojure
(angle [x y])                        ; radians, atan2 y x
(displace-radial shape (fn [p] ...)) ; radial offset per point, holes included
(noise x y)                          ; ~[-1 1], deterministic, smooth
(fbm x y)  (fbm x y octaves)  (fbm x y octaves lacunarity gain)
(sample-heightmap hm u v)            ; bilinear, wraps at the edges
(smoothstep e0 e1 x)                 ; 0 below e0, 1 above e1, Hermite ramp between
(shape-centroid shape)               ; [cx cy] of the outer contour
(current-path-length)                ; mm of the sweep, nil outside a loft or revolve
```

Messi insieme, fanno una shape-fn che il capitolo 6 non ha: una corteccia, cioè un rumore che segue l'angolo attorno al profilo e scorre lungo il percorso, applicato come spostamento radiale:

<!-- example-source: extend-bark -->
```clojure
(register bark
  (loft-n 48
    (shape-fn (circle 15 96)
      (fn [s t]
        (displace-radial s
          (fn [p] (* 2 (fbm (* 3 (angle p)) (* 8 t) 3))))))
    (f 60)))
```

`angle` dà a `fbm` una coordinata che gira attorno al profilo, `t` moltiplicato una che sale lungo il percorso, e `displace-radial` applica il valore come spostamento verso l'esterno (o l'interno, se negativo) senza cambiare il numero di punti. Le due funzioni in più, `smoothstep` e `shape-centroid`, servono soprattutto alle thickness-fn, e le vediamo nella prossima sezione. Altri attrezzi che le built-in usano per sé (la distanza con segno da un poligono, la frazione di arco-lunghezza di ogni punto) restano privati: se ti servono, li riscrivi in poche righe in una libreria, che è il posto giusto per accumulare questi attrezzi (19.5).

## 19.2 Il contratto delle thickness-fn

Il capitolo 6 (6.11) mostra come si disegna un pattern in coordinate `(angolo, t)`. Qui c'è la definizione esatta di quelle coordinate e di cosa succede al valore che restituisci.

### Il dominio

Una thickness-fn per `shell` ha la firma `(fn [a t] -> numero)`. `a` è l'angolo del punto del profilo rispetto al **centroide della shape corrente**, calcolato con `atan2` (il centro è quello che `shape-centroid` restituisce): va quindi da **-π a π** (estremo superiore compreso), con lo zero sull'asse X positivo e il salto da π a -π sul lato opposto. Un pattern periodico in `a` non se ne accorge (`(sin (* a 8))` è continuo attraverso il salto), ma un pattern che usa `a` come coordinata lineare, per esempio `(mod (* a 3) 1)`, lì fa una cucitura: se vuoi un asse che va da 0 a 1 tutto intorno, calcolalo tu con `(/ (+ a PI) (* 2 PI))`. `t` è la frazione lungo il percorso, come per le shape-fn.

Il centroide è quello della shape **a quel `t`**: se la thickness-fn segue una `tapered` o una `noisy`, il centro da cui si misura l'angolo si sposta con la shape, non con il profilo originale. Per un profilo simmetrico non cambia nulla; per uno asimmetrico è la differenza fra un pattern che "gira" con la forma e uno fisso nello spazio.

Prima di tutto, una cosa che sui cerchi degli esempi non si vede: la thickness-fn viene valutata **una volta per punto del profilo**, e fra un punto e l'altro lo spessore viene interpolato. Un `(circle 20 64)` ha 64 punti e va bene; un `(rect 40 12)` ne ha quattro, gli angoli, quindi `shell` deciderebbe lo spessore in quattro posti soli e il pattern non esisterebbe. Un profilo poligonale va ricampionato prima, con `resample-shape`, a un numero di punti almeno doppio della frequenza del pattern.

Poi la coordinata. L'angolo è buono su un cerchio e cattivo su tutto il resto: su un rettangolo 40 per 12, un grado attorno al centroide copre pochi millimetri sui lati corti e molti sui lati lunghi, e dodici fessure "ogni 30 gradi" escono strette e fitte sui lati corti, larghe e rade sui lunghi. Se vuoi un passo costante in millimetri, la coordinata giusta è la **lunghezza d'arco** lungo il perimetro, e la ottieni passando `:style :pattern` insieme alla tua `:fn`: con quello stile la prima coordinata che la funzione riceve non è più l'angolo ma la frazione di perimetro percorso, da 0 a 1.

<!-- example-source: extend-thickness-arclength -->
```clojure
;; twelve slots of equal width all around a rectangle
(register slotted
  (loft (shell (resample-shape (rect 40 12) 208) :thickness 2 :style :pattern
          :fn (fn [u t] (if (< (mod (* u 12) 1) 0.6) 1 0)))
        (f 30)))
```

Prova a togliere `:style :pattern` e a leggere `a` al posto di `u` (con `(/ (+ a PI) (* 2 PI))` per riportarlo fra 0 e 1): le fessure restano dodici, ma non più uguali. E prova a togliere `resample-shape`: torna la cornice a quattro punti.

### Il valore

Quello che restituisci viene lavorato in tre passi, in quest'ordine: se hai passato `:invert? true`, il valore diventa `1 - v` (vale anche per una `:fn` tua, non solo per gli stili); se è sotto `:threshold` (default 0.05) diventa 0, cioè apertura; poi viene tagliato nell'intervallo `[0, 1]`. Un valore sopra 1 non fa una parete più spessa di `:thickness`: viene tagliato a 1. Se vuoi pareti di spessore diverso, la scala è `:thickness`, e la funzione modula fra 0 e 1.

`:softness` vale anche per una `:fn` tua, ma la devi chiedere: senza, le aperture seguono la griglia del loft, a gradini; con `:softness` maggiore di zero il loft taglia i triangoli lungo l'isolinea del valore 0.5 del tuo campo, e i bordi escono lisci. Perché funzioni il campo deve essere continuo: una funzione che restituisce solo 0 e 1 non ha isolinee da seguire. È il caso d'uso di `smoothstep`: `(smoothstep 0.4 0.6 v)` trasforma una soglia netta in una rampa larga quanto vuoi, e la rampa è quello che il taglio liscio insegue.

### woven-shell

`woven-shell` ha un contratto diverso: la funzione restituisce una mappa `{:thickness v :offset mm}`. `:thickness` è il coefficiente di prima, con la stessa soglia ma **senza** il taglio a 1 (i due fili di un intreccio si sommano dove si incrociano, e il built-in produce valori sopra 1 apposta); `:offset` è lo spostamento radiale del centro della parete, in millimetri, positivo verso l'esterno. È l'offset che crea il sopra e sotto dell'intreccio: due fili con la stessa `:thickness` e offset opposti passano uno davanti all'altro.

Anche `embroid` accetta una `:fn`, con una firma sua: `(fn [u t] -> 0..1)`, dove `u` è la frazione di arco-lunghezza lungo la parete e `t` lo sweep; 1 è montante, 0 apertura, taglio sull'isolinea 0.5. Le opzioni `:margin`, `:border` e `:softness` degli stili non si applicano a una funzione: se vuoi il pannello attaccato ai vicini, restituisci 1 vicino ai bordi.

### Una funzione da riusare

Il modo più pulito di scrivere thickness-fn è come funzioni che restituiscono funzioni, con i parametri chiusi dentro. Così il pattern si mette in una libreria e si richiama per nome:

<!-- example-source: extend-thickness-factory -->
```clojure
(defn stripes
  "Horizontal bands: n bands along the sweep, `fill` the solid fraction."
  [n fill]
  (fn [a t] (if (< (mod (* t n) 1) fill) 1 0)))

(defn helix
  "Diagonal bands: `turns` full turns along the sweep."
  [n turns fill]
  (fn [a t]
    (let [u (/ (+ a PI) (* 2 PI))]        ; a in [-PI PI] -> u in [0 1]
      (if (< (mod (+ (* u n) (* t turns)) 1) fill) 1 0))))

(register banded
  (loft (shell (circle 20 64) :thickness 2 :fn (stripes 10 0.5)) (f 60)))
(f 60)
(register helical
  (loft (shell (circle 20 64) :thickness 2 :fn (helix 6 2 0.5)) (f 60)))
```

Nota il calcolo di `u` in `helix`: è la riga che rende il pattern indifferente al salto di `atan2`.

## 19.3 Registrare e ispezionare

`register` è il modo normale di mettere qualcosa in scena. Sotto di lui c'è un registro, cioè una tabella di oggetti nominati, e un piccolo gruppo di funzioni per leggerlo e scriverlo da programma. Servono a chi genera la scena con codice invece che con una riga per oggetto: uno script che produce venti pezzi con nomi calcolati, una libreria che registra un assieme intero, un pezzo di codice che deve guardare cosa c'è già.

### Scrivere nel registro

`register` è una macro: guarda cosa le passi e chiama la funzione giusta. Le funzioni sono esposte, e sono quelle da usare quando il nome è calcolato:

```clojure
(register-mesh! name mesh)     ; a mesh, visible in the scene
(register-shape! name shape)   ; a 2D shape, by name only
(register-path! name path)     ; a path, by name only
(register-value! name value)   ; any other value (a map of parameters, say)
(add-mesh! mesh)               ; an anonymous mesh: in the scene, without a name
```

`name` è una keyword. `register-mesh!` e `add-mesh!` mettono entrambe una mesh in scena; la differenza è se vuoi ritrovarla dopo. Con un nome la mesh si può cercare (`get-mesh`), mostrare e nascondere, rivalutare con `tweak`, esportare per nome; senza nome è solo in scena, e va bene per geometria di contorno che nessuno interrogherà più, per esempio cento chiodini decorativi che non meritano cento nomi. La differenza fra `register` e `register-mesh!`, invece, non è solo sintattica: la macro cattura anche la form che ha prodotto la mesh (è quello che permette a `tweak :nome` di rivalutarla con parametri diversi), la funzione no. Se registri da codice, quel collegamento non c'è.

<!-- example-source: extend-register-loop -->
```clojure
;; a row of pegs of growing height, each with its own name
(doseq [i (range 5)]
  (register-mesh! (keyword (str "peg-" i))
                  (attach (cyl 3 (+ 5 (* 4 i))) (rt (* 12 i)))))
```

### Leggere il registro

```clojure
(get-mesh :peg-2)          ; the registered mesh
(get-shape :profile)       ; a registered shape
(get-path :spine)          ; a registered path
(registered-names)         ; every mesh name
(shape-names) (path-names) ; the other two registers
(visible-names)            ; the meshes currently shown
(all-meshes-info)          ; per mesh: name, visibility, vertex and face counts
```

Sono le funzioni che un pezzo di codice usa per lavorare su ciò che uno script precedente ha messo in scena, per esempio unire in un solo pezzo tutto quello che ha un certo prefisso:

```clojure
(register all-pegs
  (mesh-union
    (vec (map get-mesh
              (filter #(clojure.string/starts-with? (name %) "peg-") (registered-names))))))
```

Accanto ci sono le funzioni di visibilità (`show-mesh!`, `hide-mesh!`, `show-all!`, `hide-all!`, `show-only-registered!`), che sono le versioni a funzione di `show` e `hide`, e `refresh-viewport!`, che serve solo se muti il registro fuori dal normale ciclo di Run.

### Cosa ha prodotto una mesh

Ogni mesh passata da `register` porta due metadati: `:source-history`, il registro cronologico delle operazioni che l'hanno prodotta, e `:source-form`, la form quotata che le ha dato origine. Sono i dati che l'inspector e `tweak` leggono. Da codice si raggiungono dalla selezione corrente nel viewport:

```clojure
(selected)                 ; {:mesh :face :name :origin :last-op ...} or nil
(selected-name)            ; the registry name, nil if anonymous
(last-op (selected))       ; the last operation recorded on it
(source-of (selected))     ; the whole history
(get-source-form :peg-2)   ; the quoted form that produced it
```

È materiale per chi costruisce strumenti sopra Ridley più che per chi modella; lo citiamo perché esiste ed è stabile.

### Hook: animazioni e collisioni

`anim!` e `anim-proc!` (le macro di animazione, descritte nella Spec) sono macro sopra `anim-register!` e `anim-proc-register!`; `anim-make-cmd` e `anim-make-span` costruiscono i pezzi che le macro emettono. Servono a chi genera animazioni da dati, per esempio una sequenza di span calcolata da una tabella, dove la macro sarebbe scomoda. `on-collide` registra una callback `(fn [evt])` che scatta quando due mesh nominate si toccano durante la riproduzione, `off-collide` la toglie, `list-collisions` e `clear-collisions` amministrano l'elenco. Le firme complete sono nella Spec, sezione Internals.

### Singolo o collezione

Un'ultima regolarità che vale la pena conoscere quando si scrive codice generale: molte funzioni di Ridley accettano indifferentemente un valore singolo o un vettore di valori dello stesso tipo. `text-shape`, `slice-mesh`, `project-mesh` e `shape-xor` restituiscono vettori di shape; `extrude`, `loft`, `revolve`, `stamp` e `shape-offset` li accettano così come sono, e un'estrusione di un vettore di shape produce una mesh sola, già fusa, pronta per le booleane. Una funzione tua che vuole essere altrettanto comoda fa lo stesso: controlla `(sequential? x)` (o `shape?` sull'argomento) e mappa su sé stessa.

## 19.4 Il path come dato

Il capitolo 5 usa i path. Qui vediamo come sono fatti dentro.

### Comandi, non punti

Un path è una mappa: `{:type :path :commands [...]}`, dove ogni comando è a sua volta una mappa `{:cmd :f :args [30]}`. Quello che `path` registra sono le **istruzioni** che hai dato alla turtle, non i punti che ne risultano:

```clojure
(path->data (path (f 30) (th 90) (arc-h 10 90)))
;; => {:type :path,
;;     :commands [{:cmd :f, :args [30]} {:cmd :th, :args [90]} {:cmd :arc-h, :args [10 90], :steps 64}]}
```

`path->data` è la forma stampabile: il path vero porta in più una cache dei comandi tessellati, che i consumatori ricalcolano da soli se manca, e i mark. Ogni `(mark :nome)` compare anche come chiave di primo livello della mappa, con la posa nel sistema di riferimento del path: `(path (f 30) (mark :here))` ha `(:here p)` uguale a `{:position [30 0 0] :heading [1 0 0] :up [0 0 1]}`. `path?` risponde `true` a entrambe le forme.

I punti vengono calcolati dopo, da chi consuma il path: `extrude` lo interpreta con un campione per waypoint, `loft` con i passi che gli dici (`loft-n`), `path-to-shape` lo traccia nel piano. È la ragione per cui lo stesso path si può campionare a risoluzioni diverse senza ricostruirlo: la risoluzione non vive nel path, vive nel consumatore. Vale anche per le curve: `arc-h`, `bezier-to` e `bezier-as` entrano nel path come comandi analitici (angolo e raggio, punti di controllo), con un'eccezione dichiarata: il numero di passi con cui una curva verrà tessellata è deciso quando la registri (`:steps`), non da chi la consuma.

Da questo discende una cosa utile: un path si può costruire da dati, non solo scrivendolo a mano.

### Path generati

La via più diretta è `poly-path`, che prende coppie di coordinate in un vettore piatto. Un profilo calcolato da una funzione diventa un path con un `mapcat`:

<!-- example-source: extend-path-from-data -->
```clojure
(defn sampled
  "A closed 2D outline: the curve (f x) for x in [x0 x1], n samples,
   closed by a flat base at y = base."
  [f x0 x1 n base]
  (poly-path
    (vec (concat
           (mapcat (fn [i]
                     (let [x (+ x0 (* (- x1 x0) (/ i (dec n))))]
                       [x (f x)]))
                   (range n))
           [x1 base x0 base]))))

(register wave-plate
  (extrude (path-to-shape (sampled #(* 8 (sin (/ % 6))) 0 60 40 -12) :preserve-position true)
           (f 3)))
```

La seconda via è un loop dentro `path`: `dotimes` e `for` funzionano nel recorder come ovunque, quindi un path a spirale è una riga che cambia il raggio a ogni giro (è come è fatta la conchiglia di `examples/spiral-shell.clj`):

```clojure
(def spiral
  (path (dotimes [i 36]
          (arc-h (+ 10 (* i 0.8)) 30))))
```

### Comporre e trasformare

I path si compongono splicing e non concatenando: `(follow altro-path)` dentro un `path` inserisce i comandi dell'altro nel punto in cui sei, e `side-trip` esegue un sotto-path che alla fine rimette la turtle dove l'aveva presa, tenendo però i mark che il sotto-path ha lasciato. Le funzioni di trasformazione restituiscono path nuovi, senza toccare l'originale: `reverse-path` lo percorre al contrario, `mirror-path` lo riflette (rispetto al piano normale alla tangente finale, o a una normale che dai tu), `add-mark` inserisce un mark a una frazione della lunghezza, `subpath-y` ne ritaglia una fascia, `offset-x` e `fit` lo spostano e lo scalano. La ricetta classica per una curva simmetrica è disegnarne metà e chiudere con `(follow-path (reverse-path (mirror-path half)))` dentro il `path` che la completa.

Quello che manca, e non per dimenticanza, è un accesso ai punti campionati: il path non li ha. Se ti serve la posizione di un punto notevole, mettici un mark e leggilo con `mark-pos`; se ti serve la forma tracciata, passa da `path-to-shape` e guarda `:points` della shape, che invece è fatta di punti.

## 19.5 Le librerie sotto il cofano

Il capitolo 9 dice come si usa una libreria. Qui c'è cosa succede davvero quando premi Run, e cosa ne consegue per il codice che ci scrivi.

### Un interprete, non un compilatore

Il codice che scrivi in Ridley viene letto come stringa e valutato da SCI, un interprete Clojure scritto in ClojureScript che gira nel browser insieme a Ridley. Non c'è compilazione, non c'è un processo esterno. L'interprete riceve due cose all'avvio di ogni valutazione: la mappa dei simboli del linguaggio (qualche centinaio di funzioni, da `f` a `mesh-union`) e le librerie attive. È tutto quello che il tuo codice può vedere.

Il contesto è chiuso per scelta. Non c'è interop con JavaScript (`js/...` non esiste), non c'è `require` dinamico, non c'è accesso al DOM o alla rete. Dove Ridley ha bisogno del browser (caricare un font, aprire un dialogo, leggere un SVG) lo fa dentro una funzione del linguaggio, e tu vedi una funzione pura. `clojure.core` c'è per intero, `clojure.string` pure; la matematica è quella esposta da Ridley (`sin`, `cos`, `atan2`, `pow`, `sqrt`, `PI`...), in radianti, mentre i comandi della turtle sono in gradi: la conversione la scrivi tu. Il resto di Clojure c'è: `defmacro`, `atom` con `swap!` e `reset!`, `defmulti`/`defmethod`, `defprotocol`/`defrecord`, `letfn`, `try`/`catch`, `ex-info`; `clojure.string` e `clojure.set` si chiamano col nome intero, non c'è un alias `str/` predefinito. Due cose da sapere. Un `atom` vive fra un comando REPL e l'altro, ma a ogni Run il contesto è ricostruito e riparte dal valore iniziale. E `defmulti` ha semantica `defonce`: su un nome già definito, per esempio `area`, che è una funzione di Ridley, non fa nulla, e il `defmethod` successivo esplode con un messaggio poco parlante; scegli un nome nuovo.

Molte funzioni di Ridley sono in realtà macro: `extrude`, `path`, `register`, `loft`, `anim!`. Vengono definite dentro l'interprete a ogni avvio e si espandono sul tuo codice: `path` apre uno scope in cui `f` e `th` registrano invece di muovere, `register` guarda il tipo di quello che le passi e sceglie il registratore, `loft` decide cosa fare in base alla forma dei suoi argomenti. Per il tuo codice sono funzioni che funzionano; conta saperlo solo quando provi a passarle come valori (`(map extrude ...)` non ha senso: una macro non è una funzione) o quando una libreria vuole ridefinirle (non può: una libreria costruisce sopra il linguaggio, non lo cambia).

### Run e REPL

Ogni Run (Cmd+Invio nel pannello definizioni) ricostruisce l'interprete da zero: carica le librerie attive, definisce le macro, azzera la turtle e la scena, e valuta il tuo buffer intero. La REPL invece riusa l'interprete dell'ultimo Run: vede i `def` e le `defn` che il Run ha lasciato, conserva la posa della turtle fra un comando e l'altro, ma svuota la geometria a ogni comando, così vedi solo l'output dell'ultimo. Le conseguenze: un `def` fatto nella REPL sopravvive finché non premi Run; un `def` nel buffer viene rifatto a ogni Run; niente sopravvive alla chiusura dell'applicazione, tranne quello che sta in un workspace o in una libreria.

### Cosa è una libreria, per l'interprete

Una libreria è un file di codice con un nome e una lista di dipendenze. A ogni Run, per ciascuna libreria attiva e nell'ordine giusto, Ridley crea un interprete temporaneo con il linguaggio più le librerie già caricate, valuta il sorgente della libreria, estrae i nomi pubblici e li rilegge uno per uno. Il risultato è un namespace SCI col nome della libreria, e nel tuo buffer i suoi simboli si chiamano `nome/simbolo`. Il `require` lo fa Ridley per te.

Tre dettagli che spiegano altrettanti comportamenti sorprendenti:

- **I nomi pubblici si trovano per forma, non per valutazione.** Ridley cerca nel sorgente le righe che cominciano (spazi a parte) con `(def `, `(defn ` o `(defonce `. `defn-` e `^:private` sono esclusi, come da Clojure; è escluso anche un `def` prodotto da una macro, che nel sorgente non c'è. Una `defmacro` funziona dentro la libreria ma non attraversa il prefisso: il namespace riceve valori, e una macro non lo è. Se una funzione della tua libreria "non c'è", controlla prima com'è scritta la sua riga.
- **Un nome trovato ma mai definito sparisce in silenzio.** Dopo aver valutato il sorgente, Ridley rilegge ogni nome che la ricerca ha trovato; se un `def` sta in un punto che la valutazione non ha eseguito (dentro un `comment`, in un ramo non preso), quel nome viene saltato senza errore. Un errore nel sorgente della libreria, invece, la fa saltare per intero con un avviso nel pannello.
- **Il codice a top level viene eseguito.** Una libreria non è solo definizioni: se contiene un `register` fuori da ogni funzione, quella geometria entra in scena a ogni Run in cui la libreria è attiva. È quasi sempre involontario; una libreria pulita definisce e basta, e lascia registrare a chi la chiama.

Il nome della libreria è una stringa, non una gerarchia: `robot/arm` è una libreria che si chiama così, non una libreria `robot` con un sotto-namespace. Il prefisso non si accorcia e non si rinomina.

### Ordine, dipendenze, ricaricamento

Le dipendenze si dichiarano nell'intestazione del file, come commento, e Ridley le ordina topologicamente prima di caricare: una libreria che ne richiede un'altra viene valutata dopo, e vede i suoi simboli col prefisso. Una dipendenza non attiva, o un ciclo, fanno saltare la libreria con un avviso; l'attivazione non è automatica, attivi tu entrambe. Non c'è cache: tutte le librerie attive vengono rivalutate a ogni Run, anche se non sono cambiate, e con molte librerie attive un Run minimo si sente. È il prezzo di un interprete sempre fresco, in cui una libreria è sempre esattamente il suo sorgente attuale.

Dove i file vivono, e come si scambiano, lo dice il capitolo 9.6. L'intestazione che Ridley legge è due righe di commento:

```clojure
;; Ridley Library: shapes
;; Requires: utils, robot/arm
```

### Quello che ne consegue

Da tutto questo, le conseguenze pratiche per il codice di una libreria. Solo `def`, `defn` e `defonce` in testa alla loro riga diventano pubblici. Il codice a top level viene eseguito a ogni Run: un `register` o un movimento della turtle fuori da ogni funzione entrano in scena ogni volta che la libreria è attiva, quindi vanno messi lì solo se è quello che vuoi. Dentro la libreria i nomi del linguaggio sono quelli di sempre (`f` è `f`), il prefisso distingue solo i tuoi dal buffer. E una funzione che dipende dalla posa corrente della turtle la eredita dal chiamante, non da un proprio stato: la libreria non ne ha uno che sopravviva al Run.
