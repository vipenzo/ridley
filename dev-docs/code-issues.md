# Code issues — raccoglitore di discrepanze sorgente↔realtà

File interno per tracciare piccole incoerenze tra il codice sorgente di Ridley e il suo comportamento osservabile, scoperte durante la scrittura del manuale. Non sono bug funzionali — il comportamento osservabile è corretto e descritto nel manuale — ma sono trappole per chi legge il codice (autore, futuro collaboratore, AI assistente). Quando si toccano queste zone di codice per altre ragioni, vale la pena allinearle.

## Aperto

### `bezier-as` non è taggato `:smooth` — ogni step di tessellazione è un corner duro

**Contesto**: `rec-bezier-as*` (`src/ridley/editor/macros.cljs`) emette le rotazioni per step con `rec-th*`/`rec-tv*`/`rec-tr*` **plain**, mai `rec-th-smooth*`/`rec-tv-smooth*`/`rec-tr-smooth*`. `corner-rotation?` (`src/ridley/turtle/extrusion.cljs`) esclude dal trattamento corner (accorciamento mesh, anello di giunzione per-step) solo le rotazioni taggate `:smooth`; `bezier-to` (`rec-bezier-to*`) lo fa già, `bezier-as` no.

**Realtà**: ogni step della tessellazione di un `bezier-as` usato come rail per `extrude`/`loft` viene trattato come un corner vero e proprio, non come un pezzo continuo di curva — incoerenza strutturale con `bezier-to`.

**Impatto**: probabile difetto latente sulla qualità della mesh per rail `bezier-as` con curvatura pronunciata (facce accorciate/piegate lungo la curva invece di una superficie continua); non misurato.

**Fix possibile** (non tentato, fuori scope — vedi `dev-docs/brief-bezier-as-rail-lead.md`, punto 3 e "Fuori scope"): taggare `:smooth true` sulle emissioni di `rec-bezier-as*`, allineandole a `rec-bezier-to*`. Cambia l'output mesh di ogni modello esistente che estrude un `bezier-as` a metà percorso — va misurato prima di curarlo (un difetto, una cura).

**Scoperta**: Vincenzo/Claude, 2026-07-06, durante `dev-docs/brief-bezier-as-rail-lead.md` (il fix del falso positivo rail-start di `bezier-as` ha richiesto di tracciare esattamente cosa `:smooth` esclude, esponendo questa incoerenza separata).

### `edit-path` 3D: il valore `live` di un nodo bezier non è consumabile direttamente

**Contesto**: `ridley.editor.edit-path/request!` (chiamato da `(edit-path …)`) restituisce SEMPRE un valore, in modo che lo script circostante proceda anche prima della conferma — per il modo 2D è testato che coincida col confermato (`edit_path2d_script_test.cljs`). In 3D, quando i nodi contengono un `:bez` (qualsiasi bezier), il valore `live` è `{:type :path :commands (nodes->commands-3d nodes)}`, e `nodes->commands-3d` emette per un segmento bezier un **singolo comando compatto** `{:cmd :bezier-to :args [end c1 c2 :local]}` — pensato per essere ri-emesso come SORGENTE (`nodes->code-3d`) e ri-valutato dalla macro `path`, che lo tessella via `rec-bezier-to*`. Nessun consumatore (`extrude-from-path`/`analyze-open-path-dir`, loft/`analyze-loft-path`) sa interpretare un comando `:bezier-to` grezzo dentro `:commands` — non è mai gestito, in nessun `case`/`cond` su `:cmd`. Il risultato: il segmento viene semplicemente ignorato, il path ha 0 segmenti reali, ed `extrude`/`loft` costruito su questo valore `live` produce mesh nil silenziosamente (nessun errore).

**Riproduzione**: `(ep/request! (:result (h/eval-dsl "(path (bezier-to [30 10 0] [20 0 0] [28 5 0]))")))` seguito da `(ops/pure-extrude-path profilo risultato)` → `nil`, anche con un bezier perfettamente tangente (nessuna violazione dell'invariante rail-start). Scoperto scrivendo `test/ridley/editor/edit_path_3d_test.cljs` (dev-docs/brief-rail-start-tangent.md, Parte 2): i test hanno dovuto ri-valutare i comandi come sorgente DSL (mimando il percorso reale confirm→splice→ri-eval) invece di consumare `result` direttamente, per aggirare questo buco.

**Impatto**: chiunque incorpori `(edit-path (bezier-to …))` **inline** dentro uno script (es. `(extrude prof (edit-path …))`) senza mai confermare l'edit vede una mesh vuota al primo Run, anche se il codice è geometricamente valido — comportamento silenziosamente diverso da 2D, dove `request!` è già testato per restituire un valore fedele.

**Fix possibile** (non tentato, fuori scope): far tessellare `nodes->commands-3d` un bezier in linea (stessa matematica di `rec-bezier-to*`, riusando `bezier-frame-3d`) invece di emettere il comando compatto — oppure far riconoscere `:bezier-to` come comando valido a `analyze-open-path-dir`/`analyze-loft-path` espandendolo lì. Va scelto con attenzione: il comando compatto esiste apposta per il round-trip sorgente (`nodes->code-3d` + `:pure` tag per il re-editing), quindi tessellarlo qui non deve rompere quel percorso.

**Scoperta**: Vincenzo/Claude, 2026-07-04, durante l'implementazione di dev-docs/brief-rail-start-tangent.md Parte 2.

### Ricostruire una shape enumera a mano gli attributi che sopravvivono

**Contesto**: `lerp-shape` (e a monte `make-shape`) ricostruisce la shape elencando **esplicitamente** quali attributi ricopiare (`:centered?`, `:holes`, `:preserve-position?`, …). Ogni nuovo attributo shape che qualcuno aggiunge rischia di cadere silenziosamente in questa ricostruzione — esattamente come `:preserve-position?` veniva perso dal loft two-shape (fix `2fb18f6`): nessun errore, solo geometria spostata di nascosto.

**Esempio (la trappola, già scattata una volta)**: `(loft profilo-preserve-position altro-profilo (f d))` ri-ancorava le sezioni sul primo vertice perché `lerp-shape` copiava `:centered?` ma non `:preserve-position?`.

**Comportamento atteso** (da definire, non agire ora): propagare TUTTI gli attributi di anchoring/rendering rilevanti dalla shape sorgente, non un sottoinsieme hardcoded — es. una whitelist condivisa di "shape attrs da preservare nella ricostruzione", usata sia da `lerp-shape` sia dalle altre trasformazioni che ricostruiscono shape (`resample`, `align-to-shape`, …).

**Impatto**: basso oggi (gli attributi attuali sono coperti, e il test `preserve_position_test` blinda l'osservabile per stamp/extrude/loft), ma è una trappola latente: il prossimo attributo cadrà nello stesso modo, senza feedback. Candidato per un refactor quando si tocca `make-shape`/`lerp-shape`.

**Scoperta**: verifica del `:preserve-position?` nel workflow image-board, 2026-06-19.

### Il salto status bar → editor non è sincronizzato col workspace di provenienza

**Contesto**: il source tracking (Opzione/⌥+Click su una mesh nel viewport → catena di link nella status bar → click sul link → salto all'editor) assume che il codice che ha generato la mesh sia quello attualmente nell'editor. Non è sempre vero: se dopo il Run si cambia workspace, o se la mesh è stata generata dal Run di un esempio del manuale, il link porta a una posizione dell'editor corrente che non corrisponde al codice di provenienza. Il problema è probabilmente più generale: il viewport conserva la scena dell'ultimo Run, ma non ricorda *quale* workspace/documento l'ha prodotta, quindi editor e viewport possono divergere silenziosamente.

**Riproduzione**: Run di un esempio dal manuale (o Run in un workspace A, poi switch a B) → ⌥+Click sulla mesh → click sul link nella status bar → l'editor mostra codice che non c'entra.

**Comportamento atteso** (da definire): come minimo, il link potrebbe portare con sé l'identità del documento di provenienza e avvisare (o switchare) quando non coincide con l'editor corrente; più in generale, la scena potrebbe ricordare il workspace che l'ha generata.

**Impatto**: minore (feature di comodità, il caso comune funziona), ma mina la fiducia nel source tracking proprio nei casi in cui servirebbe di più. Candidato per la Roadmap più che fix immediato: tocca l'architettura della sincronizzazione viewport↔workspace.

**Scoperta**: verifica interattiva del cap. 1 del manuale, Vincenzo, 2026-06-12.

### `tr` dentro extrude produce geometria degenere

**Contesto**: un `tr` (rollio) dentro un percorso di estrusione provoca uno sfasamento repentino dei punti corrispondenti tra due ring adiacenti. Il risultato è un pezzo di geometria degenere (facce attorcigliate).

**Esempio**:
```clojure
(register broken
  (extrude (rect 10 5)
    (f 20) (tr 45) (f 20)))
```

**Comportamento atteso**: errore esplicito. `tr` non ha senso dentro un percorso di estrusione. Allo stesso modo, anche `(u n)` e `(rt n)` dovrebbero essere proibiti dentro extrude: spostano la tartaruga lateralmente o verticalmente senza avanzare, creando un salto nel percorso che produce geometria invalida.

**Comandi da proibire dentro extrude**: `tr`, `u`, `d`, `rt`, `lt` (movimenti laterali/verticali e rollio). I comandi leciti sono `f`, `th`, `tv`, `arc-h`, `arc-v`, `bezier-to` e simili.

**Impatto**: medio-alto. L'utente non riceve feedback e il risultato visivo è silenziosamente sbagliato.

**Scoperta**: revisione manuale cap. 4, 2026-05-22.

### `shape` accetta silenziosamente comandi non supportati

**Contesto**: `shape` costruisce un contorno 2D usando solo `f` e `th`. Se si passa un comando 3D come `arc-v`, non viene dato errore: il comando viene eseguito come se fosse fuori dalla shape, producendo un risultato silenziosamente sbagliato.

**Esempio**:
```clojure
(stamp (shape (f 10) (th 90)
              (f 5)  (th 90)
              (f 5)  (th -90)
              (f 5)  (th 90)
              (arc-v 5 40)))
```

`arc-v` viene eseguito come comando della tartaruga principale, non come parte della shape.

**Comportamento atteso**: errore esplicito quando `shape` incontra un comando che non è `f` o `th`.

**Impatto**: medio. L'utente non riceve feedback che il comando è stato ignorato dalla shape, e il risultato visivo è confuso.

**Scoperta**: revisione manuale cap. 3, 2026-05-22.

### Docstring `cyl-with-resolution` disallineata dal comportamento

**File**: `src/ridley/editor/implicit.cljs:457`

**Stato**: docstring dice `"height along turtle's UP axis"`.

**Realtà**: altezza lungo `heading` (l'avanti della turtle). Lo swap heading↔up è applicato dal wrapper `transform-mesh-to-turtle-upright` in `implicit.cljs:402-428`, e il commento di quella wrapper spiega correttamente il comportamento.

**Origine**: scoperta da Code durante la diagnosi sull'orientamento delle primitive per cap. 2 del manuale (2026-05-16). Probabilmente residuo di una refactor precedente che ha cambiato il comportamento senza aggiornare la docstring chiamante.

**Impatto**: alto se l'utente legge la docstring direttamente, alto se un'AI legge il codice per documentarlo (è successo, abbiamo perso una sessione di chat a capirlo).

**Fix**: aggiornare la docstring a `"length along turtle's HEADING (forward) axis"`. Lavoro di una riga.

### Docstring `cone-with-resolution` da verificare

**File**: `src/ridley/editor/implicit.cljs` (riga da identificare)

**Stato**: presumibilmente analoga a `cyl-with-resolution`, da verificare se ha lo stesso problema.

**Fix**: stesso pattern del precedente.

### `turtle-box`/`turtle-cyl`/`turtle-sphere`/`turtle-cone` vs versioni "implicit"

**Files**: `src/ridley/geometry/primitives.cljs:88, 193, 281, 342` (le versioni `defn turtle-X` "pure")

**Stato**: le versioni `turtle-X` chiamano `apply-transform` direttamente, senza il wrapper `transform-mesh-to-turtle-upright`.

**Realtà**: hanno comportamento direzionale diverso dalle versioni "implicit" che l'utente chiama normalmente (`cyl`, `cone`):
- `turtle-cyl` → asse lungo UP della turtle
- `cyl` (implicit) → asse lungo HEADING della turtle

**Impatto**: medio. Un utente che usa il threading esplicito (`(-> turtle (turtle-cyl 5 30))`) ottiene un cilindro orientato diversamente dal `cyl` chiamato in modo idiomatico. È un'inconsistenza dell'API.

**Fix possibile**: applicare lo stesso wrapper anche nelle versioni `turtle-X`, oppure chiarire in docstring che le versioni `turtle-X` sono "primitive low-level senza riorientamento". Decisione fuori scope per ora — annotato per quando si tornerà a quel codice.

## Chiuso

### Spec.md descrive `box` come `[w d l]` senza ancoraggio direzionale — RISOLTO 2026-05-17

**File**: `docs/Spec.md` (sezione §5 3D Primitives)

**Stato originale**: `box` era descritto con parametri `w, d, l` senza specificare il rapporto con il sistema di riferimento della turtle. `cyl` e `cone` erano descritti come "height along UP axis", che non corrisponde al comportamento osservabile (lungo heading).

**Risoluzione**: Spec.md aggiornato durante T-006 con la nuova convenzione di naming dei parametri direzionali. Le signature ora sono:
- `(box r u f)` con `r=right, u=up, f=forward (heading)`
- `(rect r u)` con `r=right, u=up`
- `(cyl radius height)` con `height along forward (heading)`
- `(cone r1 r2 height)` con `height along forward (heading)`, `r1=near/start radius` (lato −heading), `r2=far radius` (lato +heading) — ordine come loft: `(cone r1 r2 h) ≈ (loft (circle r1) (circle r2) (f h))`. **Flip 2026-06-04**: prima era `r1` verso l'avanti (+heading); invertito per allineare l'ordine dei parametri a `loft`/`extrude`. Implementazione in `make-cone-vertices`, `implicit-sdf-cone`.

Il paragrafo "Orientation" è stato riscritto per chiarire che tutte le primitive con asse di sviluppo (`box`, `cyl`, `cone`) si estendono lungo forward, con sezione nel piano right–up. La nomenclatura `r u f` per i parametri direzionali è stata adottata come convenzione del DSL (vedi quaderno §14.5 del piano).

**Voce gemella**: la docstring di `cone-with-resolution` è stata allineata durante il flip del 2026-06-04 (ora documenta asse=heading e r1=near/r2=far). Resta da verificare quella di `cyl-with-resolution` (vedi voce "Docstring `cyl-with-resolution` disallineata dal comportamento" qui sopra).
