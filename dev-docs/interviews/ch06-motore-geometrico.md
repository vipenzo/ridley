# Interview - Chapter 6: Motore geometrico

Risposte di fact-finding per il capitolo 6 di `Architecture_1.md`.
Ogni risposta cita file e funzione (con riga, dove utile). Dove il
codice non offre una risposta univoca, lo dichiaro esplicitamente.

---

## Sezione 1 - Confini e file principali

### 1.1 Mappa dei sottosistemi

**Turtle engine.** Stato e movimento della tartaruga 3D.

- `src/ridley/turtle/core.cljs` (2111 righe). Il cuore: definizione
  della struttura turtle (`make-turtle`, core.cljs:26-49), primitive
  `f`/`th`/`tv`/`tr`/`u`/`rt`/`lt`/`arc-h`/`arc-v` (core.cljs:451-721),
  recorder (`make-recorder`, `rec-f`, `rec-th`, ...; core.cljs:1202-1306),
  costruttori path (`make-path`, core.cljs:1174; `path-from-recorder`,
  core.cljs:1303), interprete `run-path` (core.cljs:1308), e funzioni
  di attachment di alto livello (`attach`, `attach-face`, `attach-move`,
  core.cljs:1976-2075).
- `src/ridley/turtle/transform.cljs` (343 righe). Operazioni di
  trasformazione su mesh ottenute via turtle (rotate/scale/translate
  con preservazione di creation-pose).
- `src/ridley/turtle/attachment.cljs` (693 righe). Manipolazione
  rigida e per-faccia delle mesh quando la turtle e' agganciata
  (translate-mesh, rotate-mesh, scale-mesh, build-face-extrusion,
  inset-attached-face).

**Shape system 2D.** Profili planari chiusi.

- `src/ridley/turtle/shape.cljs` (1154 righe). Struttura dati
  `{:type :shape :points [...] :holes [...]}` (`make-shape`,
  shape.cljs:19-38), primitive (`circle-shape`, `rect-shape`,
  `polygon-shape`, `ngon-shape`, `star-shape`, `poly-shape`,
  shape.cljs:49-126), conversione path-shape (`path-to-2d-waypoints`,
  shape.cljs:440; `path-to-shape`, shape.cljs:359), stamping su
  piano della turtle (`transform-shape-to-plane`, shape.cljs:896).
- `src/ridley/turtle/text.cljs` (416 righe). Testo via opentype.js,
  produce shape estrudibili.
- `src/ridley/turtle/shape_fn.cljs` (1011 righe). Le shape-fn
  (`tapered`, `twisted`, `fluted`, `rugged`, `displaced`, `morphed`,
  `shell`, `woven-shell`, `profile`, `capped`, `heightmap`,
  `noise-displace`, ...). Funzioni che restituiscono altre funzioni
  `(fn [t] -> shape)`.

**Path recorder.** La cattura di sequenze turtle in dato.

- `src/ridley/turtle/path.cljs` (30 righe). Solo predicato `path?`.
- `src/ridley/turtle/bezier.cljs` (192 righe). Calcolo dei control
  point e campionamento di Bezier (`compute-bezier-control-points`,
  bezier.cljs:107; `sample-bezier-segment`, bezier.cljs:143). Il
  campionamento espande in una sequenza di `f` registrabili nel
  path.
- Recorder vero e proprio in `core.cljs:1202-1306` (vedi sopra).

**Mesh engine.** Creazione, sweep, deformazione di mesh 3D.

- `src/ridley/geometry/primitives.cljs` (364 righe). Costruttori
  ad-hoc dei solidi base (`box-mesh`, `sphere-mesh`, `cyl-mesh`,
  `cone-mesh`, ...). Generano vertici/facce in ClojureScript senza
  passare da Manifold.
- `src/ridley/geometry/operations.cljs` (211 righe). `revolve` puro
  (operations.cljs:98-164) e altre operazioni di livello geometrico.
- `src/ridley/geometry/faces.cljs` (1216 righe). Topologia delle
  facce (auto-face-groups, identificazione di facce piane, picking).
- `src/ridley/geometry/mesh_utils.cljs` (368 righe). Utility
  (mesh-simplify, mesh-laplacian, merge-vertices, mesh-diagnose).
- `src/ridley/geometry/warp.cljs` (520 righe). Deformazione spaziale
  (`warp`, warp.cljs:362-419), preset (`inflate`, `dent`, `attract`,
  `twist`, `squash`, `roughen`).
- `src/ridley/turtle/extrusion.cljs` (1592 righe). Costruzione delle
  mesh sweep da rings (`build-sweep-mesh`, extrusion.cljs:498-578;
  `build-sweep-mesh-with-holes`, extrusion.cljs:724). Punto in cui
  i ring stampati diventano vertici/facce reali.
- `src/ridley/turtle/loft.cljs` (1369 righe). Loft normale e bloft.
  Walking del path (`walk-path-poses`, loft.cljs:1011), versione
  adattiva (`walk-path-poses-adaptive`, loft.cljs:1134), corner
  smoothing (`process-loft-corners`, loft.cljs:210-283), bridging
  via convex hull (loft.cljs:1229-1369).
- `src/ridley/editor/operations.cljs` (le funzioni `pure-*`:
  `pure-extrude-path` operations.cljs:76, `pure-loft-path`
  operations.cljs:155, `pure-loft-two-shapes` operations.cljs:191,
  `pure-bloft` operations.cljs:217, `pure-loft-shape-fn`
  operations.cljs:277, `pure-revolve` operations.cljs:329,
  `pure-revolve-shape-fn` operations.cljs:368). Sono le funzioni
  pure che le `*-impl` chiamano.
- `src/ridley/editor/impl.cljs`. Layer di dispatcher chiamato
  dalle macro: `extrude-impl` (impl.cljs:35), `loft-impl`
  (impl.cljs:47), `bloft-impl` (impl.cljs:91), `revolve-impl`
  (impl.cljs:135), `attach-impl` (impl.cljs:609),
  `attach-face-impl` (impl.cljs:621). Validano gli argomenti e
  smistano alle `pure-*`.
- `src/ridley/manifold/core.cljs` (796 righe). Ponte verso
  Manifold WASM (boolean, hull, refine, smooth, slice).

**SDF tree builder.**

- `src/ridley/sdf/core.cljs` (539 righe). Costruttori di alberi
  SDF, materializzazione via geo-server desktop, autotuning di
  resolution e bounds.

**Bridge esterni.**

- `src/ridley/clipper/core.cljs` (423 righe). Boolean 2D e offset.
- `src/ridley/manifold/core.cljs`. Boolean 3D, hull, refine.
- `desktop/src-tauri/src/sdf_ops.rs` e `geo_server.rs`. Lato Rust
  che parla con libfive (vedra' il cap. 9).

### 1.2 Dove vive lo stato

**Turtle.** Atom dentro una SCI dynamic var `*turtle-state*`,
definita in `src/ridley/editor/state.cljs:13-14`:

```clojure
(def turtle-state-var
  (sci/new-dynamic-var '*turtle-state* (atom (turtle/make-turtle))))
```

Si dereferenzia con `@@*turtle-state*`.

**Shape-fn registrate.** Non c'e' un registry in senso classico:
le shape-fn sono semplici funzioni esposte come binding SCI in
`src/ridley/editor/bindings.cljs` (sezione shape-fn). L'utente le
usa direttamente per nome.

**Path recorder.** Non e' un atom globale. Ogni `(path ...)` o
`(shape ...)` istanzia un recorder locale (turtle con `:recording []`
in piu') tramite `make-recorder` (core.cljs:1202-1206), e lo
distrugge alla fine dello scope. Il path e' interamente generato
dentro l'espansione macro.

---

## Sezione 2 - Turtle engine

### 2.1 Struttura dati

`make-turtle` in `src/ridley/turtle/core.cljs:26-49` produce una
mappa Clojure immutabile con questi campi (raggruppati per ruolo):

- **Posa.** `:position [x y z]` (default `[0 0 0]`),
  `:heading [x y z]` (default `[1 0 0]`), `:up [x y z]`
  (default `[0 0 1]`). Il terzo asse "right" non e' memorizzato:
  si calcola con `cross(heading, up)` ogni volta che serve.
- **Modo penna.** `:pen-mode` ∈ `{:off :on :shape}`. `:on` accumula
  segmenti in `:geometry`; `:shape` e' usato dall'estrusione interna.
- **Geometria accumulata.** `:geometry []` (segmenti pen-on),
  `:meshes []` (mesh prodotte da operazioni che concludono dentro
  uno scope turtle), `:stamps []` (outline 2D di debug).
- **Sweep state.** `:stamped-shape`, `:sweep-rings []`,
  `:sweep-base-shape`, `:pending-rotation`. Validi solo durante
  `:pen-mode :shape` o `:loft`.
- **Loft state.** `:loft-orientations []`, `:loft-corners []`.
- **Anchors / mark.** `:anchors {}` mappa nome -> posa, popolata
  dal comando `mark`.
- **Attachment.** `:attached`. Quando presente:
  `{:type :pose :mesh m :original-pose p}` per `attach`,
  `{:type :face :mesh m :face-id id :face-info info :extrude-mode b}`
  per `attach-face`.
- **Configurazione.** `:resolution`, `:joint-mode`, `:material`,
  `:preserve-up`, `:reference-up`.

E' una struttura "fat": tiene insieme posa, modo, accumulatori e
configurazione. La purezza si conserva perche' la mappa e'
sostituita per intero a ogni operazione tramite `swap!`.

### 2.2 Primitive di movimento

Le primitive vere, definite come funzioni pure su stato:

- `f` (forward, core.cljs:451). Si specializza in base a
  `:pen-mode`: in modalita' `:off` muove e basta, `:on` aggiunge
  un segmento, `:shape` stampa un nuovo ring e accumula, `:loft`
  registra un waypoint.
- `th` (turn horizontal/yaw, core.cljs:547). Ruota heading attorno a up.
- `tv` (turn vertical/pitch, core.cljs:582). Ruota heading e up
  attorno all'asse "right".
- `tr` (turn roll, core.cljs:618). Ruota up attorno a heading.
- `move-up`/`move-down` (core.cljs:504, 513). Spostamento lungo up
  senza ruotare.
- `move-right`/`move-left` (core.cljs:518, 528). Spostamento lungo
  l'asse "right" calcolato.

`f`, `th`, `tv`, `tr`, gli spostamenti laterali sono **primitive
indipendenti**: nessuna delle quattro si riduce alle altre. `f`
muove lungo heading, `th`/`tv`/`tr` ruotano la base, e gli
spostamenti laterali muovono senza ruotare.

`arc-h` (core.cljs:647) e `arc-v` (core.cljs:686) **non sono
primitive**: si decompongono in una sequenza di `th`/`f` (oppure
`tv`/`f`) di passi piccoli, con numero di step controllato da
`:steps` o ricavato dalla `:resolution`. Sono helper, non un
movimento atomico.

In modalita' `:shape` o `:loft`, le rotazioni `th`/`tv`/`tr` non
si applicano subito ma sono accodate in `:pending-rotation` e
trasformate in fillet/corner sul prossimo `f` (vedi
`store-pending-rotation`, core.cljs:535-545). E' la differenza
piu' importante fra "muovere la turtle" e "muovere la turtle
mentre estrudo".

Non esistono `f-impl`, `th-impl`, ecc.: la coppia macro/`-impl` non
si applica ai comandi turtle puri. Le funzioni in core.cljs sono
gia' pure e sono esposte direttamente come binding (con il livello
"implicito" che opera sull'atom `*turtle-state*`, vedi 2.5).

### 2.3 Stack della turtle

**Non esiste uno stack esplicito.** Ridley usa lo *scoping
lessicale* della macro `turtle` (macros.cljs:1652-1657):

```clojure
(defmacro turtle [& args]
  ...
  `(let [parent-state# @*turtle-state*]
     (binding [*turtle-state* (atom (init-turtle ~opts-form parent-state#))]
       ~@body)))
```

`(turtle ...)` ribinda la dynamic var su un nuovo atom, costruito
da `init-turtle` (state.cljs) clonando i campi rilevanti dal
parent (posa, materiale, resolution, joint-mode, anchors,
preserve-up, reference-up) salvo che `:reset true` non chieda di
azzerare. All'uscita dello scope la binding cade e si torna al
parent. Niente push/pop manuale.

Effetto: scope nidificati ricevono ciascuno il proprio atom, isolati.

### 2.4 Turtle implicita globale vs scope locale

Realizzata via *SCI dynamic var*. `*turtle-state*` e' una variabile
dinamica (state.cljs:13-14). Fuori da ogni `(turtle ...)`, il bind
attivo e' quello root, definito una volta e mantenuto (defonce)
fra eval REPL successive. Le primitive `f`, `th`, ecc., quando
chiamate "implicitamente" (cioe' senza scope), leggono e
aggiornano `@*turtle-state*` con `swap!`.

Dentro `(turtle ...)`, `*turtle-state*` e' ribindata a un atom
diverso. Le stesse primitive ora leggono/scrivono il nuovo atom
senza saperlo: e' il `binding` che le redirige.

Una `f` non riceve un parametro turtle implicito: lo legge da
`*turtle-state*` direttamente. Il "currying invisibile" e' quello
della dynamic var.

### 2.5 Cosa registrano `path`/`shape`/`turtle`

**`(path ...)`** (macros.cljs:410-449):

- Crea un nuovo recorder con `make-recorder` (un turtle con
  `:recording []` in piu', core.cljs:1202).
- All'interno dello scope, simboli come `f`, `th`, `tv`, `arc-h`
  ecc. sono shadow-bindati a versioni `rec-f`, `rec-th`, `rec-tv`
  ecc., che fanno **due cose**: (1) appendono al vector
  `:recording` un mappa `{:cmd :f :args [10]}`, (2) eseguono il
  movimento sul recorder per tener sincronizzata la posa interna
  (utile per primitive successive che dipendono dall'orientamento
  corrente, es. `bezier-as`).
- Alla fine dello scope, `path-from-recorder` (core.cljs:1303-1306)
  estrae il vector `:recording` e lo avvolge in
  `{:type :path :commands [...]}` via `make-path` (core.cljs:1174).
- Output finale: `{:type :path :commands [{:cmd :f :args [10]}
  {:cmd :th :args [90]} ...]}`. Eventualmente con altre chiavi se
  costruita altrove (es. `:resolution`, `:joint-mode`).
- Consumatori: `extrude-impl`, `loft-impl`, `bloft-impl`,
  `revolve-impl`, `attach-impl`, `attach-face-impl`.

**`(shape ...)`** (macros.cljs:466-473):

- Funziona sullo stesso pattern, ma vincolato al piano: solo `f`
  e `th` sono ammessi (le altre macro 3D lanciano un errore se
  usate in shape).
- L'output non e' una sequenza di comandi ma un'array di punti
  2D: `{:type :shape :points [[x0 y0] [x1 y1] ...] :centered? false}`
  (vedi `make-shape`, shape.cljs:19-38). La conversione path-shape
  e' fatta da `path-to-shape` (shape.cljs:359), che traccia i
  punti e garantisce l'avvolgimento CCW.
- Consumatori: `extrude`, `loft`, `revolve`, e in generale tutto
  cio' che si aspetta un profilo 2D.

**`(turtle ...)`** (macros.cljs:1652-1657):

- Non registra nulla. Apre uno scope con un atom locale per
  `*turtle-state*` e valuta il body con la turtle ribindata. Il
  valore di ritorno e' quello dell'ultima espressione del body.
- E' la primitiva di scope locale, non un recorder.

In sintesi: `path` produce **comandi**, `shape` produce **punti**,
`turtle` produce **valore + side-effects nel proprio scope**.

Trasformazione successiva: il recorder mantiene comandi crudi.
La sintesi geometrica (vertici reali della mesh) avviene piu'
tardi, nelle funzioni `pure-extrude-path` o `pure-loft-path`,
chiamate da `*-impl`. Questo e' il momento in cui il path
"materializza" la geometria.

---

## Sezione 3 - Shape system 2D

### 3.1 Rappresentazione interna

Mappa Clojure plain (`make-shape`, shape.cljs:19-38):

```clojure
{:type :shape
 :points [[x0 y0] [x1 y1] ...]   ; outer contour, CCW
 :centered? bool
 :holes [[[hx hy] ...] ...]      ; opzionale, ognuno CW
 ;; flag opzionali per testo:
 :preserve-position?, :align-to-heading?, :flip-plane-x?}
```

Il riferimento a Clipper2 **non e' nel dato**: la shape e' sempre
una rappresentazione native CLJS. La conversione a `Path64`
Clipper2 e' on-demand, fatta da `shape->clipper-paths`
(clipper/core.cljs:90-99), e il risultato Clipper viene riportato
in shape via `paths-result->shape` (clipper/core.cljs:179-192).

### 3.2 Primitive

In shape.cljs:

- `circle-shape` (49-58): genera N segmenti, default 32.
- `rect-shape` (60-69): 4 angoli, centrato.
- `polygon-shape`/`ngon-shape` (71-111): poligono regolare a N lati.
- `star-shape` (113-126): alterna raggio interno/esterno.
- `poly-shape` (77-92): lista piatta di coordinate xy.

In text.cljs: `text-shape` produce shape multi-contour da glifi
opentype.js (i fori vengono assegnati come `:holes`).

Tutte producono `{:type :shape :points [...]}` con le opzioni che
servono.

### 3.3 Boolean 2D, ponte verso Clipper2

File: `src/ridley/clipper/core.cljs`.

- `shape-union` (240), `shape-difference` (246),
  `shape-intersection` (253), `shape-xor` (259), `shape-offset`
  (277-290), `shape-hull` (322-331), `shape-bridge` (335-351).
- Helper privato di delega: `clipper-boolean` (231-238). Converte
  i due shape, chiama `op-fn` (es. `c-union`, `c-difference`,
  `c-intersect`), riconverte.
- I siti di chiamata reale a Clipper2 stanno a clipper/core.cljs:
  `c-union` (50), `c-difference` (53), `c-intersect` (48). Usano
  `.call (.-Union c2/Clipper) c2/Clipper subject clip fill-rule`
  via interop JS sul namespace `clipper-lib`.
- Nota di scala: tutte le coordinate 2D vengono moltiplicate per
  `SCALE = 1000` (clipper/core.cljs:14) all'ingresso e divise
  all'uscita, perche' Clipper2 lavora in interi.

C'e' anche un monkey-patch (clipper/core.cljs:27-43) che corregge
un bug della porta JS di `ClipperOffset.offsetPolygon`.

### 3.4 Shape-fn

Una shape-fn e' **una funzione `(fn [t] -> shape)`** dove `t ∈ [0,1]`
e' la posizione lungo un loft. Il costruttore canonico e'
`shape-fn` (shape_fn.cljs:57-70):

```clojure
(defn shape-fn [base transform]
  (let [evaluate (if (shape-fn? base)
                   (fn [t] (transform (base t) t))
                   (fn [t] (transform base t)))]
    (with-meta evaluate {:type :shape-fn :base base :point-count ...})))
```

Quindi una shape-fn e' una funzione "marcata" via metadata. Si
controlla con `shape-fn?` (predicato analogo a `shape?`).

**Nessun registry centrale.** Le shape-fn built-in sono semplici
funzioni esposte come binding SCI:

- `tapered` (shape_fn.cljs:99), `twisted` (108), `fluted` (126),
  `rugged` (117), `displaced` (135), `morphed` (144),
  `profile` (583), `heightmap` (544), `noise-displace` (varie),
  `shell` (746), `woven-shell` (871), `capped` (932).

Vengono invocate **dentro la pipeline di loft**, in
`pure-loft-shape-fn` (operations.cljs:277) e nei walker di loft
(loft.cljs:556+, dove ogni ring `t` calcolato si trasforma con
`(transform-fn base-shape t)`). Ricevono `t` ∈ [0,1] e a volte
contesto extra via dynamic var (`*path-length*`, shape_fn.cljs:20-23).

### 3.5 Import SVG

Pipeline in `src/ridley/library/svg.cljs`:

- `svg` (svg.cljs:64) prende una stringa SVG, crea un `<div>`
  nascosto nel DOM, ritorna `{:type :svg-data :elements [...]}`.
- `svg-shape` (svg.cljs:139) seleziona un elemento, lo campiona
  via `getPointAtLength` (svg.cljs:110-119), applica la matrice
  di trasformazione cumulativa (svg.cljs:162), inverte Y (SVG
  Y-down -> Ridley Y-up, svg.cljs:167), forza CCW (svg.cljs:180).
- Punto di ingresso nel sistema shape: svg.cljs:183 con
  `(shape/make-shape final {:centered? center})`. Da quel
  momento la shape SVG e' indistinguibile da una `circle-shape`.

Clipper2 non e' coinvolto nella conversione: lo diventa solo se
l'utente combina la shape via boolean 2D.

---

## Sezione 4 - Path recorder

### 4.1 Struttura del path

Sequenza di **comandi**, non campioni:
`{:type :path :commands [{:cmd :f :args [10]} {:cmd :th :args [90]} ...]}`
(`make-path`, core.cljs:1174).

I campioni (waypoint con posizione + tangente) si ottengono solo
piu' tardi, quando un consumer (extrude, loft, revolve) "cammina"
il path con `walk-path-poses` (loft.cljs:1011) o
`walk-path-poses-adaptive` (loft.cljs:1134), interpretando i
comandi su una turtle locale e raccogliendo le pose.

Quindi: **forma canonica = comandi, forma derivata = waypoint**.

### 4.2 `bezier-as`, `quick-path`, `arc-h`, `arc-v` interni a un path

- `arc-h`/`arc-v` interni si comportano come quelli "implicit":
  espandono in step di `th`/`f` (rispettivamente `tv`/`f`). Ogni
  step produce un comando registrato individuale. Niente comando
  speciale `:arc`.
- `bezier-as`/`bezier-to`/`bezier-to-anchor`: i control point
  vengono calcolati da `compute-bezier-control-points`
  (bezier.cljs:107), poi `sample-bezier-segment` (bezier.cljs:143)
  campiona il segmento in N piccoli `f` (con eventualmente piccole
  rotazioni intermedie per mantenere la tangente). Il path
  registrato contiene solo `:f` e `:th`/`:tv`, espansi a tempo di
  espansione.
- `quick-path` (core.cljs:1183-1200): zucchero per costruire path
  "lunghezza, angolo, lunghezza, angolo, ..." in modo compatto.
  Espande in `:f` e `:th` come i comandi normali.

Conseguenza: **il recorder accumula sempre primitive di basso
livello** (`:f`, `:th`, `:tv`, `:tr`, `:u`, `:rt`, `:lt`,
`:set-heading`, plus i record-only `:inset`, `:scale`, `:move-to`,
`:cp-f`, `:mark`).

### 4.3 Consumatori dei path completati

I consumatori ricevono il path **come comandi** e lo campionano
loro. Il sample rate non e' nel path, e' nel consumer:

- `extrude` (`pure-extrude-path`, operations.cljs:76): riceve
  shape e path, costruisce ring per ogni waypoint stampato e li
  passa a `build-sweep-mesh` (extrusion.cljs:498-578).
- `loft` (`pure-loft-shape-fn`/`pure-loft-path`/`pure-loft-two-shapes`,
  operations.cljs:155-291): cammina il path con
  `walk-path-poses` (loft.cljs:1011) calcolando un parametro `t`
  ∈ [0,1] dal cumulo delle distanze (`total-dist`, loft.cljs:1020;
  `sample-interval = total-dist / steps`, loft.cljs:1028).
- `bloft` (Bezier-safe loft, loft.cljs:1229+): usa
  `walk-path-poses-adaptive` (loft.cljs:1134), campiona piu' fitto
  dove la curvatura e' alta. Inoltre attiva il bridging via convex
  hull se due ring consecutivi si auto-intersecano (loft.cljs:1308).
- `revolve` (`pure-revolve`, operations.cljs:329): caso speciale,
  non cammina un path libero ma ruota un profilo attorno a un
  asse, con numero di step esplicito.

Il sample rate, in tutti i casi tranne bloft, dipende dal parametro
`steps` (default 16, configurable da loft-n / bloft-n / chiamando
direttamente la macro con un numero esplicito). Per bloft dipende
dalla curvatura locale e da una soglia `threshold` (default 0.1).

Il path e' eager: i comandi sono in memoria nel vector. Il walker
e' eager-loopy, non lazy seq.

### 4.4 Path 3D vs shape 2D

Distinzione netta:

- **Shape**: punti `[x y]` in un piano astratto. Diventa 3D solo
  quando viene "stampata" sul piano della turtle, via
  `transform-shape-to-plane` (shape.cljs:896), che usa heading,
  up e position correnti per piazzare i punti nello spazio.
- **Path**: comandi che, replayati su una turtle 3D, producono
  posizioni e orientamenti in 3D. Inerentemente 3D, perche' la
  turtle che li genera/replaya ha `heading` e `up` 3D.

C'e' una conversione **da path a shape** (`path-to-shape`,
shape.cljs:359; `path-to-2d-waypoints`, shape.cljs:440) che
"appiattisce" un path planare proiettando le sue posizioni su un
piano. E' usata quando l'utente costruisce una shape via comandi
turtle invece che via primitive (caso `(shape (f 10) (th 90)
(f 10) ...)`). Non c'e' la conversione inversa.

---

## Sezione 5 - Mesh engine

### 5.1 Rappresentazione interna delle mesh

Una mesh Ridley e' una **mappa CLJS pura**, non un wrapper di
Manifold:

```clojure
{:type :mesh
 :vertices [[x y z] ...]
 :faces [[i j k] ...]
 :creation-pose {:position [...] :heading [...] :up [...]}
 :primitive :box | :sphere | :cylinder | :cone | :extrude | :revolve | :loft
 :face-groups {...}                  ; opzionale
 :material {...}                     ; opzionale
 ::raw-arrays {...}}                 ; opzionale, per zero-copy con Three.js
```

La conversione a oggetto Manifold e' **esplicita e on-demand**,
fatta solo quando serve un'operazione che Manifold sa fare:
`ridley-mesh->manifold-mesh` (manifold/core.cljs:72-105) costruisce
un `#js {:numProp 3 :vertProperties Float32Array :triVerts Uint32Array}`,
da cui `mesh->manifold` (manifold/core.cljs:153-166) crea un
`new Manifold(...)`. Dopo l'operazione, `manifold->mesh`
(manifold/core.cljs:168) riestrae vertici e indici con
`.getMesh()` e ricostruisce la mappa Ridley.

`:creation-pose` e' la posa della turtle al momento della
creazione: serve a `attach` (per ricollocare la turtle nella sua
"frame d'origine") e ai vari `mesh-rotate-around-pose`. Vive sulla
mappa, non e' calcolata al volo.

### 5.2 Primitive solide

In `src/ridley/geometry/primitives.cljs`. Generano triangoli
ad-hoc, **non chiamano costruttori Manifold**:

- `box-mesh` (primitives.cljs:70). 8 vertici, 12 facce.
- `sphere-mesh` (primitives.cljs:181). Vertici via `make-sphere-vertices`
  (primitives.cljs:114), facce via `make-sphere-face` (primitives.cljs:140).
  Anelli + poli.
- `cyl-mesh` (primitives.cljs:269). Disco superiore, disco
  inferiore, fianchi.
- `cone-mesh` (presumibilmente primitives.cljs ~300). Stessa logica
  del cilindro con un raggio nullo.

Tutti restituiscono direttamente la mappa mesh con `:vertices`,
`:faces`, `:primitive`, `:creation-pose`. La generazione e' loop
trigonometrico e non passa da Manifold.

### 5.3 Operazioni costruttive

**`extrude`** (shape + path):

- Macro in macros.cljs:498-501.
- `extrude-impl` (impl.cljs:35-37) valida il path con
  `validate-extrude-path!` (rifiuta comandi attach-only come
  `:inset`, `:scale`, `:move-to`) e delega a
  `pure-extrude-path` (operations.cljs:76).
- `pure-extrude-path` campiona il path, stampa la shape ad ogni
  posa, alimenta `build-sweep-mesh` (extrusion.cljs:498-578) che
  appiattisce i ring in un singolo array vertici, costruisce le
  facce laterali, calcola le cap.
- Output: mappa mesh pura. **Nessuna chiamata Manifold** durante
  la pipeline.

**`loft` / `loft-n` / `bloft` / `loft-between`**:

- Tutte le macro stanno in macros.cljs:529-619.
- `loft-impl` (impl.cljs:47-66) e `loft-n-impl` (impl.cljs:68-85)
  smistano in base alla forma degli argomenti tra
  `pure-loft-shape-fn` (operations.cljs:277), `pure-loft-two-shapes`
  (operations.cljs:191), `pure-loft-path` (operations.cljs:155).
- `bloft-impl` (impl.cljs:91-115) ha la stessa firma di `loft-impl`
  ma usa la versione adattiva di walking. La differenza
  architetturale chiave del bloft: in
  `loft.cljs:1229-1369` il walker (`walk-path-poses-adaptive`,
  loft.cljs:1134) campiona piu' fitto dove curvature alta, e
  quando rileva auto-intersezione fra ring consecutivi
  (`rings-intersect?`, loft.cljs:984-1008) costruisce un convex
  hull tramite Manifold (`manifold/hull-from-points`,
  manifold/core.cljs:537-586) e lo unisce alla mesh totale con
  `manifold/union` (loft.cljs:1308, 1347). E' l'**unico ramo del
  mesh engine, fuori dai boolean veri, in cui Manifold viene
  invocato durante la costruzione**.
- Gestione angoli: `process-loft-corners` (loft.cljs:210-283)
  calcola gli offset di scorciatoia per profili rastremati. Non
  e' bridging via hull.

**`revolve`**:

- Macro in macros.cljs:652-662.
- `revolve-impl` (impl.cljs:135) -> `pure-revolve` (operations.cljs:329).
- Implementazione di base in `revolve` (geometry/operations.cljs:98-164):
  estrai punti dal profilo, ruota il profilo `n-rings` volte
  attorno all'asse, costruisci facce tra ring, aggiungi cap se
  l'angolo non e' 360°.
- Mesh map pura, senza Manifold.

### 5.4 Operazioni booleane

File del ponte: `src/ridley/manifold/core.cljs`.

- `union` (manifold/core.cljs:303-320). Internamente usa
  `tree-union` (287-301) per combinare in O(n log n).
- `difference` (manifold/core.cljs:348-362). `difference-two`
  (322-346) e' il fold step.
- `intersection` (manifold/core.cljs:385-399).
- `solidify` (manifold/core.cljs:232-261). A `union` self.
- `hull` (manifold/core.cljs:401-443). Convex hull statico.

Il dato passato e': `Manifold object` costruito da
`mesh->manifold` (manifold/core.cljs:153-166). Internamente, la
chiamata e' un metodo JS sull'istanza Manifold:
`(.add ma mb)` per union, `(.subtract ma mb)` per difference,
`(.intersect ma mb)` per intersection (vedi `union-two`,
manifold/core.cljs:267-285). Il risultato si trasforma con
`(.asOriginal)` per pulire faccie interne, poi
`manifold-mesh->ridley-mesh` (114-147) lo riporta in formato Ridley.

**Conversione esplicita.** Ogni boolean costruisce nuovi oggetti
Manifold dai due input mesh; non c'e' caching dell'oggetto sulla
mappa Ridley. Questo significa che chiamare 3 boolean in fila
con la stessa mesh ricostruisce 3 volte il Manifold di quella
mesh. E' un costo accettato per mantenere la mesh come dato puro
spostabile e copiabile.

### 5.5 `warp`

File: `src/ridley/geometry/warp.cljs`. La macro DSL e' in
macros.cljs:1432; la funzione e' `warp` (warp.cljs:362-419).

Pipeline:

1. **Bounds del volume di influenza** via `compute-volume-bounds`
   (warp.cljs:27-83): la geometria del volume (sphere, cyl, cone,
   box, ...) viene letta dal `:primitive` per dare regole rapide
   di "punto dentro volume?" senza dover calcolare distanze
   triangolari.
2. **Subdivision opzionale** via `subdivide-mesh` (warp.cljs:227-233).
   Se l'utente passa `:subdivide n`, le facce dentro il volume
   vengono subdivise n volte (1 -> 4 per midpoint) per dare
   risoluzione sufficiente alla deformazione. Questo e' il "triangola
   piu' fitto prima" cercato.
3. **Stima delle normali** con `estimate-vertex-normals`
   (warp.cljs:288-345), con detezione di crease basata su angolo.
4. **Loop di deformazione** (warp.cljs:404-415): per ogni vertice,
   verifica se sta dentro al volume, calcola posizione locale,
   chiama la `deform-fn` utente con `(pos local-pos dist normal vol)`,
   sostituisce il vertice con il risultato.
5. Output: mesh modificata, sempre come mappa pura.

Preset: `inflate`, `dent`, `attract`, `twist`, `squash`, `roughen`
(warp.cljs:425-520). Sono semplicemente `deform-fn` predefinite.

### 5.6 `attach`, `attach-face`, `move-to`

**Layer**: sono parte del mesh engine, ma istanziate via macro DSL
in `macros.cljs:705-741`.

- `attach` (macros.cljs:736) -> `attach-impl` (impl.cljs:609-619).
  Costruisce un path dai movimenti, replaya il path su uno stato
  derivato dalla `:creation-pose` della mesh, applica le
  trasformazioni rigide (`translate-mesh`, `rotate-mesh`,
  `scale-mesh` da attachment.cljs:37-105). Rendering: la mesh
  ritorna spostata.
- `attach-face` (macros.cljs:705) -> `attach-face-impl`
  (impl.cljs:621). Sposta la turtle sul centro della face,
  orienta heading lungo la normale, replaya il path: ogni `f`
  diventa un'estrusione della face (`build-face-extrusion`,
  attachment.cljs:214-281), ogni `inset` diventa un offset planare
  (`inset-attached-face`, attachment.cljs:498-530).
- `move-to` (record-only, replayato in impl.cljs:494-501): risolve
  la posizione target a tempo di replay, decompone in coppie
  th/f locali per raggiungere il target con movimenti turtle nativi.

L'uso che fanno della turtle: `attach` la riposiziona sulla
creation-pose (lettura), `attach-face` la riposiziona sulla face
(lettura), poi i comandi nel body modificano la turtle e contestualmente
trasformano la mesh agganciata via il flag `:attached` impostato
sullo stato.

### 5.7 `capped`

Implementato come **shape-fn** in shape_fn.cljs:932. Riceve la
shape base, un raggio (positivo per fillet, negativo per espansione),
opzionali `:mode :chamfer`, `:start`, `:end`, `:end-radius`.

A tempo di loft, viene chiamata con `t` ∈ [0,1]. Vicino a `t=0` e
`t=1` (cap dell'estrusione), trasforma la shape scalandola/offsettandola
verso un raggio ridotto, in modo che il loft tracci una transizione
liscia che simula un fillet. Negli `t` intermedi restituisce la
shape inalterata.

Il fillet quindi non e' costruito come geometria post-hoc: e'
costruito *durante* il loft, modulando il profilo. Questo limita
i fillet a scenari con loft lungo path, ma evita un secondo round
di mesh-edit.

### 5.8 Lazy o eager Manifold?

**Lazy.** Una mesh ottenuta da `box`, `extrude`, `loft`, `revolve`,
`warp`, `attach`, `move-to` resta una mappa CLJS pura: nessun
oggetto Manifold viene costruito.

L'unica eccezione interna al mesh engine e' `bloft`, che chiama
`hull-from-points` durante il bridging dei ring. La mappa risultante
torna pero' Ridley-mesh, l'oggetto Manifold non viene esposto.

Il commit a Manifold avviene quando l'utente invoca `mesh-union`,
`mesh-difference`, `mesh-intersection`, `solidify`, `hull`,
`mesh-refine`, `mesh-smooth`, `slice-at-plane`. In quel momento le
mesh operande vengono convertite a oggetti Manifold (una conversione
nuova per chiamata), si fa il calcolo, si riconvertono.

### 5.9 Ordine di invocazione in un `extrude`

Per un `(extrude (circle 20) (f 30) (th 90) (f 20))`:

1. **Macro `extrude` cattura source.** macros.cljs:498-501. Espande
   in `(-> (extrude-impl (circle 20) (path (f 30) (th 90) (f 20)))
   (add-source {:op :extrude :line ... :col ... :source ...}))`.
2. **Macro `path` cattura comandi.** macros.cljs:410-449. Crea
   recorder, shadow-binda `f`, `th`. I tre `(f 30)`, `(th 90)`,
   `(f 20)` chiamano `rec-f`, `rec-th`, `rec-f`, che appendono a
   `:recording` mapping `{:cmd :f :args [30]}` ecc., e fanno
   avanzare il recorder. A fine scope, `path-from-recorder`
   (core.cljs:1303) wrappa in `{:type :path :commands [...]}`.
3. **Shape costruita.** `(circle 20)` e' una funzione (non macro).
   Restituisce `{:type :shape :points [...] :centered? true}` via
   `circle-shape` (shape.cljs:49-58). Niente normalizzazione
   ulteriore qui.
4. **`extrude-impl` riceve shape e path.** impl.cljs:35-37. Valida
   che il path non contenga comandi attach-only.
5. **`pure-extrude-path` cammina il path.** operations.cljs:76.
   Internamente usa una turtle locale (`make-turtle` + `run-path`-like)
   per ricavare le pose ad ogni passo. Per ogni posa, stampa la
   shape sul piano (`transform-shape-to-plane`, shape.cljs:896)
   ottenendo un ring 3D di vertici.
6. **`build-sweep-mesh` produce la mesh.** extrusion.cljs:498-578.
   Concatena i ring in `:vertices`, costruisce le facce laterali
   (quadrilateri spezzati in 2 triangoli), aggiunge cap iniziale
   e finale. Restituisce `{:type :mesh :primitive :extrude
   :vertices [...] :faces [...] :creation-pose ...}`.
7. **`add-source` aggiunge la entry storica.** Vedi cap. 5.

Manifold non viene mai chiamato in questo flusso. Il primo punto
di contatto con Manifold sara' un eventuale boolean successivo.

---

## Sezione 6 - SDF tree builder

### 6.1 Rappresentazione

**Albero di operazioni come dato puro**, costruito a runtime e
inviato a libfive (lato Rust) per la materializzazione. File:
`src/ridley/sdf/core.cljs`.

Ogni nodo e' una mappa con chiave `:op` (string) e parametri
specifici dell'operazione:

```clojure
{:op "sphere" :r 5}
{:op "box" :sx 10 :sy 8 :sz 6}
{:op "union" :a node-a :b node-b}
{:op "blend" :a node-a :b node-b :k 0.5}
{:op "shell" :a node :t 1.5}
```

Costruzione e composizione sono pure operazioni di mappa Clojure;
non c'e' codice CLJS che valuta SDF in browser. Tutta la
valutazione avviene lato Rust.

### 6.2 File principale

`src/ridley/sdf/core.cljs` (539 righe). Tutto il builder e l'API
materializzazione vivono qui. Non ci sono sub-namespace per SDF.

### 6.3 Primitive e operazioni

Costruttori di primitive (sdf/core.cljs:69-91):

- `sdf-sphere` (69), `sdf-box` (71-74), `sdf-cyl` (76),
  `sdf-rounded-box` (78-82).

Operazioni booleane (variadiche, accettano sia `(op a b c)` sia `(op [a b c])`):

- `sdf-union` (93-102), `sdf-difference` (104-113),
  `sdf-intersection` (115-124).

Operazioni esclusive di SDF (no equivalente mesh):

- `sdf-blend` (130), `sdf-shell` (131), `sdf-offset` (132),
  `sdf-morph` (133), `sdf-displace` (135-139).

Trasformazioni:

- `sdf-move` (143-144), `sdf-rotate` (146-149), `sdf-scale`
  (184-188), `sdf-revolve` (174-182).

Pattern periodici e TPMS:

- `sdf-gyroid` (240-249), `sdf-schwarz-p` (251-260),
  `sdf-diamond` (262-283), `sdf-slats` (296-307), `sdf-bars`
  (309-328), `sdf-bar-cage` (330-347), `sdf-grid` (349-366).

Compilatore di formule (sdf/core.cljs:199-236): `compile-expr`
cammina un'espressione Clojure e produce un albero SDF
("expression tree") che il backend traduce in libfive primitives.

Materializzazione e meshing:

- `materialize` (505-515): conversione esplicita (richiede bounds
  e resolution).
- `ensure-mesh` (517-539): converte se SDF, lascia stare se gia'
  mesh.
- Helper di tuning: `auto-bounds` (370-424), `mesh-bounds`
  (426-433), `expand-bounds` (435-442), `min-feature-size`
  (446-465), `resolution-for-bounds` (472-499).

Tutte esposte a SCI in `bindings.cljs` (sezione SDF, ~571-599).

### 6.4 Quando avviene la materializzazione

**Differita.** `(sdf-box ...)`, `(sdf-union ...)` ecc. costruiscono
solo nodi puri. Niente comunicazione col backend.

La materializzazione parte quando:

- L'utente chiama esplicitamente `(materialize sdf bounds resolution)`
  o `(sdf->mesh sdf)`.
- Una funzione che richiede una mesh chiama `ensure-mesh`
  (sdf/core.cljs:517-539): un'operazione che combina SDF e mesh
  (es. `mesh-union` su input SDF) o un export.

In quel momento il payload `{:tree node :bounds bounds :resolution res}`
viene serializzato a JSON e inviato sincronamente in POST a
`http://127.0.0.1:12321/sdf-mesh` via `XMLHttpRequest` (sdf/core.cljs:11-20).
Il server Rust (`desktop/src-tauri/src/sdf_ops.rs`) ricostruisce
l'albero come libfive tree e chiama `libfive_tree_render_mesh`,
ritornando JSON `{vertices: [...] faces: [...]}` che torna in una
mesh Ridley standard.

**Risoluzione**: scelta da `*sdf-resolution*` (dynamic var,
sdf/core.cljs:501-503, default 15 voxel/unita'). Sovrascrivibile
nelle chiamate. `resolution-for-bounds` (472-499) la converte in
una griglia compatibile con la "resolution" turtle (10-80) e con
un budget massimo di voxel (`MAX-VOXELS = 4e6`). Se l'SDF ha
feature sottili, `min-feature-size` boosta automaticamente.

### 6.5 Disponibilita' libfive in browser

Il binding e' presente nel context SCI, ma la chiamata
`materialize` fallisce a runtime perche' il geo-server non risponde
in browser (no `localhost:12321`). Non c'e' fallback. Dettagli
nel cap. 9.

---

## Sezione 7 - Punti di contatto fra i sottosistemi

### 7.1 Chi campiona il path, chi normalizza la shape

In un `extrude`:

- **Path sampling**: il consumer. `pure-extrude-path`
  (operations.cljs:76) avvia una turtle locale, chiama
  `run-path` (core.cljs:1308), raccogliendo posa a ogni
  comando. Poi fra waypoint adiacenti calcola eventuale interpolazione
  (con `walk-path-poses` per loft, o stamp diretto per extrude).
- **Shape normalization**: la shape arriva gia' come mappa
  `:type :shape`. La normalizzazione vera e' lo *stamping*:
  `transform-shape-to-plane` (shape.cljs:896) prende i punti
  2D e li proietta nel piano perpendicolare a `heading` con
  `up` come asse Y locale, traslati a `position`. Risultato:
  vector di vertici 3D (un ring).
- **Costruzione vertici**: `build-sweep-mesh`
  (extrusion.cljs:498-578) appiattisce i ring e tessella le facce.

### 7.2 Lazy commit a Manifold

Eager nella costruzione mesh CLJS, lazy nella conversione a
Manifold. Vedi 5.8.

### 7.3 Parametri ricevuti dalle shape-fn

Una shape-fn riceve `t` ∈ [0,1] (frazione lungo il path), e
restituisce una shape. Non riceve la posa turtle e non riceve
l'indice del campione: per le ricezioni che ne hanno bisogno
(es. `capped` che vuole sapere lunghezze di transizione in
unita' assolute), il loft espone *path length* via la dynamic
var `*path-length*` (shape_fn.cljs:20-23) che la shape-fn puo'
leggere.

Alcune shape-fn (`displaced`, `morphed`) prendono ulteriori
funzioni come parametri al momento della costruzione, e quelle
funzioni *si* aspettano firme diverse (es. `displaced` riceve
una `(fn [t angle] -> radial-offset)` per vertice).

### 7.4 Flusso dati di un `extrude` (sintesi)

Vedi 5.9 per il dettaglio. Sintesi a un livello sopra:

1. Macro cattura source.
2. `path` macro accumula comandi turtle in `{:type :path :commands ...}`.
3. `extrude-impl` valida, smista a `pure-extrude-path`.
4. La shape gia' costruita viene stampata su ogni waypoint del
   path generando ring 3D.
5. `build-sweep-mesh` tessella ring e cap.
6. Mesh map ritorna, `add-source` annota la storia, output e' una
   mesh pura non-Manifold.

---

## Sezione 8 - Stato non-puro

Lo stato mutabile rilevante per il motore geometrico:

**Turtle (cuore del sottosistema).**

- `state/turtle-state-var` (state.cljs:13-14). SCI dynamic var
  che incapsula `(atom (turtle/make-turtle))`. Necessaria per
  permettere ai comandi turtle "impliciti" (`f`, `th`, ...) di
  modificare lo stato senza riceverlo come parametro, e per
  consentire scope nidificati con `(turtle ...)` (ribinding del
  dynamic). E' lo stato non-puro principale del sistema. Reset a
  ogni Run via `state/reset-turtle!`.

**Output accumulato.**

- `state/scene-accumulator` (state.cljs:113). `atom` con
  `{:lines [] :stamps []}` condiviso fra scope turtle, raccoglie
  output visivo (segmenti pen-on, outline shape) durante il Run.
  Reset all'inizio di ogni eval.
- `state/print-buffer` (state.cljs:17). `atom []` cumulando l'output
  di `print`/`println` durante il Run, scaricato in REPL history
  alla fine.
- `state/mark-anchors` (state.cljs:114). `atom {}` per le anchor di
  `mark` accessibili globalmente.

**Modi interattivi e callback.**

- `state/interactive-mode` (state.cljs:137). `atom` ∈ {:pilot,
  :tweak, nil}. Garantisce esclusione mutua fra modi interattivi.
- `state/sci-ctx-ref` (state.cljs:101), `state/run-definitions-fn`
  (state.cljs:105), `state/get-editor-content` (state.cljs:8).
  Atom-callback usati per rompere dipendenze circolari fra moduli.

**Manifold.**

- `manifold/manifold-state` (manifold/core.cljs:17). `defonce
  ^:private atom`. Tiene il modulo WASM caricato (`{:wasm
  :Manifold :Mesh :CrossSection}`). Necessario perche' il caricamento
  e' async, e tutte le operazioni Manifold devono prima verificare
  che `(initialized?)` (manifold/core.cljs:22).

**Font.**

- `text/font-cache` (text.cljs:22). `atom {}` cache di font
  caricati per URL.
- `text/default-font` (text.cljs:25). `atom nil` font di default.

**Scene registry (anticipo del cap. 7).**

- `registry/scene-meshes`, `mesh-id-counter`, `scene-lines`,
  `scene-stamps`, `scene-paths`, `scene-panels`, `scene-shapes`,
  `scene-values` (registry.cljs:19-42). Diversi atom per i tipi
  di "cosa registrata". Reset via `clear-all!` ad ogni Run.

**Dynamic var.**

- `*turtle-state*` (state.cljs:13). Vista come dynamic var,
  separata dall'atom: il binding cambia dentro `(turtle ...)`,
  l'atom dietro al binding cambia dentro lo scope.
- `*path-length*` (shape_fn.cljs:20-23). Dynamic var bindata dal
  loft, letta dalle shape-fn (es. `capped`).
- `*sdf-resolution*` (sdf/core.cljs:501-503). Dynamic var per
  override globale della risoluzione SDF.

Recorder di path/shape: **non ci sono atom globali**. Ogni `(path
...)` o `(shape ...)` istanzia il proprio recorder lessicale e lo
butta. Il recorder e' un parametro implicito allo scope, non uno
stato del sistema.

---

## Sezione 9 - Cose che vorresti dire e che non ho chiesto

Tre osservazioni che ritengo utili al cap. 6:

**Il flusso "cattura → comando → replay" e' uniforme.** Path,
shape, attach: tutti e tre si esprimono come "registra una
sequenza di comandi turtle, valutali piu' tardi su uno stato".
La differenza e' solo *quale stato* riceve i comandi: stato vuoto
con turtle a origine (path), stato 2D vincolato (shape), stato
derivato dalla creation-pose della mesh (attach). Una volta colto
questo, tutto il sistema diventa molto piu' leggibile. Il
recorder e l'interprete (`run-path`, `replay-path-commands`) sono
gli stessi.

**Le coordinate Clipper2 sono scalate per evitare l'integer.**
Non e' solo un dettaglio implementativo: significa che la fedelta'
delle boolean 2D e' di tre cifre decimali. Per l'uso CAD di Ridley
basta, ma se in futuro qualcuno volesse boolean su geometrie
sub-millimetriche andrebbe rivisto.

**La mesh come dato puro e' una scelta architetturale, non un
caso.** Ridley avrebbe potuto wrappare ogni mesh come `Manifold`
non appena costruita, evitando le riconversioni nei boolean. Ha
scelto il contrario: la mesh e' una mappa Clojure, copiabile,
diffabile, serializzabile, trasportabile in WebRTC e in libreria.
Manifold e' un calcolatore esterno che chiamiamo quando serve.
Questa scelta supporta la sezione "Decisioni fondative" (cap. 2):
il modello deve essere *dato*, non riferimento opaco a un oggetto
WASM.
