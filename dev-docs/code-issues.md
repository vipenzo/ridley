# Code issues — raccoglitore di discrepanze sorgente↔realtà

File interno per tracciare piccole incoerenze tra il codice sorgente di Ridley e il suo comportamento osservabile, scoperte durante la scrittura del manuale. Non sono bug funzionali — il comportamento osservabile è corretto e descritto nel manuale — ma sono trappole per chi legge il codice (autore, futuro collaboratore, AI assistente). Quando si toccano queste zone di codice per altre ragioni, vale la pena allinearle.

## Aperto

### `auto-bounds` sovra-inflaziona in modo ordine-dipendente → mesh SDF degenere per storie `attach` con stretch dopo cp-rotazioni — RISOLTO 2026-07-09

**Contesto**: `sdf/auto-bounds` (`src/ridley/sdf/core.cljs:671`) sul ramo `rotate` sostituisce i bounds del figlio con la sua **sfera-bounding** (`r = sqrt(maxX²+maxY²+maxZ²)`, poi `[[-r r][-r r][-r r]]`): un box diventa il cubo che ne contiene la diagonale. Quando più `rotate` si annidano, ogni passo re-inflaziona di ~√3 il precedente (già inflazionato), quindi l'inflazione è ~(√3)^N con N = numero di `rotate` nell'albero. Dentro `attach` sul backend SDF, sia `sdf-stretch-along-axis` (sandwich move→rotate→scale→rotate→move) sia le cp-rotazioni (`cp-th`/`cp-tv`/`cp-tr`) aggiungono `rotate`; **quanti** ne aggiungono, e in che annidamento, dipende dall'ordine dei comandi. Due storie che descrivono la stessa geometria finiscono con conteggi di `rotate` diversi e quindi bounds enormemente diversi.

**Riproduzione** (REPL CLJS, server geo attivo, 2026-07-09):
```clojure
(def base (sdf/sdf-box 4 2 3))
(def A (impl/attach-impl base {:type :path :commands [{:cmd :stretch-f :args [1.6]} {:cmd :cp-th :args [37]}]}))
(def B (impl/attach-impl base {:type :path :commands [{:cmd :cp-th :args [37]} {:cmd :stretch-f :args [1.6]}]}))
(sdf/auto-bounds A) ; => ±11.8   (3 rotate nell'albero)
(sdf/auto-bounds B) ; => ±322.7  (9 rotate — 6 in più → (√3)^6 ≈ 27× ; 322.67/11.81 = 27.3×)
(count (:vertices (sdf/ensure-mesh A))) ; => 333  (mesh sana)
(count (:vertices (sdf/ensure-mesh B))) ; => 9    (degenere: auto-resolution affamata dai bounds giganti)
```
La geometria è la **stessa** in entrambi i casi: i bounding box dei vertici prodotti coincidono a 6 decimali (`[[-3.12 3.12][-3.04 3.04][-1 1]]`), e rimeshando B con i bounds sani di A (`(sdf/materialize B (sdf/auto-bounds A) 15)`) si ottiene una mesh sana (5829 vs 5969 vertici, stesso bbox). Il 9-vertici è solo sotto-campionamento: `ensure-mesh` sceglie la risoluzione sui bounds, e su una regione ~27× troppo larga per l'oggetto la griglia manca quasi tutto.

**Impatto**: medio, latente. Un `attach` su SDF con stretch **dopo** una cp-rotazione (o storie con molte rotazioni) può produrre una mesh degenere/vuota in silenzio (nessun errore, solo pochi vertici). È la stessa famiglia già nota (memoria `project_sdf_auto_bounds_overinflate`: gli override di risoluzione servono ~3× per gli SDF con rotazioni impilate). Sul backend mesh non esiste (le trasformazioni sono applicate ai vertici, i bounds sono quelli reali).

**Non è un difetto del fix stretch-material-frame** (`dev-docs/brief-stretch-material-frame.md`): l'invariante di commutazione è verde a livello dati (`:material-heading`/`:material-up` **identici** fra i due ordinamenti — riconfermato in REPL: heading `[0.7986 -0.6018 0]`, up `[0 0 1]` per entrambi) e a livello geometria quando i bounds sono sani (rimeshando con bounds uguali le due superfici coincidono). Il 9-vs-533 segnalato nel report d'implementazione era il confronto geometrico fatto con `ensure-mesh`/auto-bounds, che era **compromesso** dai bounds gonfiati di un ordinamento: quel particolare confronto non provava né smentiva niente. La verifica dell'invariante SDF, rifatta su mesh sane (bounds espliciti condivisi), regge.

**Risoluzione** (dev-docs/brief-auto-bounds.md, 2026-07-09): riscritto il ramo `(= op "rotate")` di `auto-bounds` (`src/ridley/sdf/core.cljs`). Invece della sfera→cubo, ruota gli 8 corner dei bounds del figlio con la rotazione cardinale esatta del nodo (`:axis` + `:angle`) e ne prende l'AABB per asse — via `math/rotate-point-around-axis`, la stessa funzione di `pose-rotate`, così la convenzione coincide con quella che il backend applica al campo. Trappola di segno: l'angolo memorizzato è in convenzione libfive (negato per `:y` alla costruzione), recuperato prima di ruotare i corner; la convenzione è **fissata dal test** `rotate-decentred-about-y` (non dedotta dal sorgente, come raccomanda il brief). La stima è strettamente più stretta della sfera e mai più larga (proprietà "mai peggiore" resa test `rotate-aabb-contained-in-old-cube`), esatta per un singolo `rotate` su box axis-aligned, e preserva la posizione dei figli decentrati; in catena resta solo una perdita limitata da ri-boxing (AABB di AABB), senza il fattore √3 per nodo.

Test: `test/ridley/sdf/auto_bounds_test.cljs` (5 test, scritti prima del cambio: 90°=identità, 45°=√2 non √3, decentrato su z/y col segno giusto, contenimento nel cubo vecchio). Suite `npm test` verde (470 test, 0 fallimenti). Accettazione E2E su desktop con geo-server (stessa riproduzione sopra): A bounds ±11.8→~3.7 e mesh 333→709 (più densa, bounds più stretti); B bounds ±322.7→~7.7 e mesh 9→**89** (recuperata dal degenere); bbox mesh identico a 2 decimali `[[-3.12 3.12][-3.04 3.04][-1 1]]` per entrambi; a risoluzione condivisa i due ordinamenti concordano (3233 vs 3829). Il residuo 2× di B è la perdita di ri-boxing in catena, limitata e attesa.

**Evoluzione futura** (fuori scope, non urgente con l'AABB in mezzo): far collassare le catene affini consecutive (`rotate`/`move`/`scale`) in un'unica trasformazione prima del meshing — i sandwich di `sdf-stretch-along-axis`/cp introducono catene lunghe e ridondanti, e collassarle azzererebbe anche il residuo di ri-boxing. È un refactor del pipeline di meshing (superficie ampia), rimandato; vedi anche la memoria `project_sdf_auto_bounds_overinflate`.

**Scoperta**: report d'implementazione di `brief-stretch-material-frame.md` (2026-07-09) come "sospetto artefatto SDF"; root-causato come limite noto di `auto-bounds` (addendum-brief-stretch-material-frame.md, punto 1) e risolto col ramo AABB, 2026-07-09, con evidenza REPL sopra.

### `set-image` ha la stessa esposizione al silent-nil che `image-board` aveva

**Contesto**: `set-image` (`src/ridley/turtle/shape.cljs`) ha arità fissa `[shape path width offset-x offset-y]`, nessun default, nessuna validazione. Esattamente il pattern che `dev-docs/brief-image-board-defaults.md` ha appena chiuso per `image-board`: un'arità incompleta o argomenti malformati non lanciano, destrutturano/calcolano su nil e producono una shape con `:image` apparentemente valido ma rotto (coordinate nil/NaN), che emerge a valle.

**Realtà**: non misurato — nessuna riproduzione tentata, solo la stessa forma strutturale del difetto già confermato su `image-board` (stessa famiglia del bug di mesh-hull citato nel brief).

**Fix possibile** (non tentato, esplicitamente fuori scope dal brief-image-board-defaults.md): applicare lo stesso pattern — una funzione di risoluzione condivisa con default sensati (se esistono) e validazione con throw sui quattro argomenti, riusando l'approccio di `image-board-params` come riferimento.

**Scoperta**: brief-image-board-defaults.md, sezione "Fuori scope", punto esplicito da loggare durante l'implementazione. Claude, 2026-07-08.

### Manuale: `pilot-request!` documenta un binding SCI che non esiste più

**Contesto**: `docs/manual/reference/en/pilot-request-bang.md` (e la voce generata in `src/ridley/manual/reference_index.cljs`) descrivono `pilot-request!` come il low-level entry point di pilot mode, con output `(attach! ...)`. Il brief `dev-docs/brief-edit-attach.md` (2026-07-08) ha rinominato il modulo `pilot_mode.cljs` → `edit_attach.cljs` e il binding SCI `pilot-request!` → `edit-attach-request!` (`pilot` resta come alias macro, ma non espone più un binding proprio); l'output canonico alla conferma è ora `(attach ...)` flat, non `(attach! ...)`.

**Realtà**: la pagina manuale è stale su due fronti indipendenti dal lavoro di rename — cita un binding non più risolvibile e un output mai stato quello vero (`attach!` è per mesh registrate per keyword, `pilot`/`edit-attach` producono `attach`). Non è un difetto funzionale (il manuale è generato da markdown, non eseguito), ma un link/riferimento morto per chi lo consulta.

**Fix possibile** (non tentato, fuori scope per il brief edit-attach — che esclude esplicitamente l'aggiornamento della documentazione a valle dell'implementazione): riscrivere `pilot-request-bang.md` come `edit-attach-request-bang.md` (o rinominarlo), documentando `edit-attach-request!` a 5 argomenti (quoted-mesh, mesh-value, quoted-body, attached-value, marker-kind), l'alias `pilot`, e l'output flat `(attach mesh cmd...)`; poi rigenerare l'indice con `bb scripts/build_reference_index.bb` (vedi [[project_manual_reference_index_generated]] in memoria).

**Scoperta**: Claude, 2026-07-08, durante l'implementazione di `dev-docs/brief-edit-attach.md`.

### `bezier-as` non è taggato `:smooth` — ogni step di tessellazione è un corner duro

**Contesto**: `rec-bezier-as*` (`src/ridley/editor/macros.cljs`) emette le rotazioni per step con `rec-th*`/`rec-tv*`/`rec-tr*` **plain**, mai `rec-th-smooth*`/`rec-tv-smooth*`/`rec-tr-smooth*`. `corner-rotation?` (`src/ridley/turtle/extrusion.cljs`) esclude dal trattamento corner (accorciamento mesh, anello di giunzione per-step) solo le rotazioni taggate `:smooth`; `bezier-to` (`rec-bezier-to*`) lo fa già, `bezier-as` no.

**Realtà**: ogni step della tessellazione di un `bezier-as` usato come rail per `extrude`/`loft` viene trattato come un corner vero e proprio, non come un pezzo continuo di curva — incoerenza strutturale con `bezier-to`.

**Impatto**: probabile difetto latente sulla qualità della mesh per rail `bezier-as` con curvatura pronunciata (facce accorciate/piegate lungo la curva invece di una superficie continua); non misurato.

**Fix possibile** (non tentato, fuori scope — vedi `dev-docs/brief-bezier-as-rail-lead.md`, punto 3 e "Fuori scope"): taggare `:smooth true` sulle emissioni di `rec-bezier-as*`, allineandole a `rec-bezier-to*`. Cambia l'output mesh di ogni modello esistente che estrude un `bezier-as` a metà percorso — va misurato prima di curarlo (un difetto, una cura).

**Scoperta**: Vincenzo/Claude, 2026-07-06, durante `dev-docs/brief-bezier-as-rail-lead.md` (il fix del falso positivo rail-start di `bezier-as` ha richiesto di tracciare esattamente cosa `:smooth` esclude, esponendo questa incoerenza separata).

### `anim-parse-cmd` non riconosce `(path ...)` come argomento di `span` — silenziosamente ignorato da `preprocess.cljs`

**Contesto**: `anim-parse-cmd` (`src/ridley/editor/macros.cljs` ~1534) riconosce solo `f/u/d/rt/lt/th/tv/tr/parallel` dentro il corpo di `span`; il ramo `:else` lascia passare la forma non riconosciuta così com'è, che SCI valuta a runtime e il cui risultato finisce splice-ato in `anim-make-span`. Poiché `path` è una macro disponibile nello stesso scope SCI, `(span 1 :linear (path (f 10) (th 90)))` produce un vero path (`{:type :path :commands [...] ...}`) come elemento del vettore `:commands` dello span. `anim/preprocess.cljs` (`command-effective-distance`, `apply-command-to-turtle`, le loro varianti orbitali) fa `case` su `(:type cmd)`, che per un path è `:path` — non combacia con nessun ramo, default silenzioso (distanza 0 / turtle invariata), esattamente il pattern che `lower-commands` (`turtle/core.cljs`) ora rifiuta con un `throw` per i micro-comandi del path.

**Realtà**: verificato per tracciamento statico (nessuna riproduzione runtime eseguita) da un agente Explore, 2026-07-06: nessun esempio/libreria nel repo combina oggi `span` con `path` al suo interno, quindi il buco non risulta ancora innescato in contenuto spedito — ma nessun codice in `anim-parse-cmd`/`make-span`/`preprocess-animation` lo impedisce o lo rileva.

**Non è toccato dalla Fase 1** (dev-docs/brief-recording-highlevel-fase1.md): il buco riguarda il VALORE path intero visto come UN comando dello span (il suo `:type`, sempre stato `:path`, mai riconosciuto da `preprocess.cljs`) — non la rappresentazione interna del path stesso, che è quanto la Fase 1 ha cambiato. Identico prima e dopo.

**Fix possibile** (non tentato, fuori scope — sistema di animazione, non di recording): o `anim-parse-cmd` rifiuta/segnala una forma non riconosciuta invece di lasciarla passare muta, o i `case` di `preprocess.cljs` lanciano su un `:type` sconosciuto invece di azzerare in silenzio.

**Scoperta**: agente Explore, 2026-07-06, rispondendo a un'obiezione di Vincenzo sulla rigorosità della whitelist del grep-guard della Fase 1 (verifica se `anim`/`pilot_mode` possano mai ricevere un path turtle). `pilot_mode.cljs` risulta invece strutturalmente isolato: `request!` rifiuta qualunque valore che non sia mesh (`:vertices`) o nodo SDF (`:op` stringa) prima ancora di creare lo stato pilot.

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

### STL importati: si sviluppano lungo Z, quindi `th` su oggetti a simmetria radiale sembra un no-op

**Contesto**: un STL appena importato in libreria ha l'asse di sviluppo lungo Z. In `attach`, `th` ruota attorno all'up: per un oggetto a simmetria radiale attorno a Z la rotazione è reale ma visivamente nulla; `tv`/`tr` mostrano invece effetto. Segnalato 2026-07-07 su un mount STL; inizialmente sospettato come regressione del lavoro sul recording, **scagionato** verificando che il comportamento è identico sulla versione di produzione (pre-Fase-1).

**Realtà**: non è un difetto della pipeline path/attach. È una questione di convenzione d'orientamento all'import: l'oggetto arriva "in piedi" (sviluppo lungo Z) mentre la turtle ragiona con heading lungo X, quindi l'intuizione dell'utente su cosa faccia `th` non combacia con la geometria.

**Fix possibile** (non tentato, da accertare): decidere una convenzione di `creation-pose` all'import degli STL (es. heading allineato all'asse di sviluppo rilevato o a Z per convenzione dichiarata), così `th`/`tv`/`tr` in attach hanno il significato che l'utente si aspetta; in alternativa, documentare la convenzione attuale nel manuale (capitolo import). Minor, ma da riprendere.

### `arc-v-endpoint` 3D: la formula dell'`:end` diverge da `turtle/arc-v` per un segno

**Contesto**: emerso durante la verifica nREPL del brief lettura-2D (2026-07-07), confrontando la nuova closed form 2D (`arc-2d-endpoint`, verificata indipendentemente contro la tessellazione reale) con l'omologa 3D preesistente: `arc-v-endpoint` calcola l'`:end` con un segno in disaccordo con ciò che `turtle/arc-v` traccia davvero.

**Realtà**: difetto preesistente, non introdotto dal filone recording. Colpisce solo i seed 3D con `arc-v` scritto a mano aperti nell'editor 3D (il nodo ricostruito atterra specchiato rispetto alla geometria reale). Gli `arc-h` e tutto il percorso 2D non sono toccati.

**Fix possibile** (non tentato): allineare la formula a `turtle/arc-v` e aggiungere il test che `arc-2d-endpoint` ha già ricevuto — confronto della closed form contro una tessellazione fine — per entrambe le varianti 3D, così il segno non può più divergere in silenzio.

### edit-attach: il primo rientro dopo un OK non apre la sessione — RISOLTO 2026-07-09

**Contesto**: confermando (OK) una sessione `edit-attach` e reinvocandola subito dopo (stesso mesh o un altro), il primo tentativo di riapertura non fa nulla — nessun pannello, nessun indicatore turtle, la sessione non si apre affatto — nessun errore in console del browser. Il secondo tentativo funziona normalmente.

**Meccanismo** (root-causato 2026-07-09, dev-docs/brief-edit-attach-reentry.md; il sospetto race è stato **falsificato**): è un **flag di skip globale residuo**, deterministico, nessun timing coinvolto. Il flag condiviso `modal-evaluator/skip-next` esiste perché un re-eval il cui marker è sopravvissuto come letterali non rientri ricorsivamente; `reeval-script!` lo arma a meno che il chiamante passi `arm-skip? = false`. La preview live di edit-attach (drag gizmo / frecce) chiamava `reeval-script!` a `src/ridley/editor/edit_attach.cljs` **con soli 2 argomenti** → `arm-skip?` defaultava a `true`, mentre `build-modified-script` sostituisce SEMPRE il marker con codice `attach` letterale → durante la preview `request!` non viene mai raggiunta → `consume-skip!` non scatta → **il flag resta armato**. Sopravvive al `run-definitions!` di `confirm!`/`cancel!` (buffer bakato, nessun marker) e viene consumato dalla PRIMA reinvocazione, che imbocca il ramo skip di `request!` e ritorna il valore attached senza aprire nulla, in silenzio. La seconda reinvocazione trova il flag disarmato → apre. Evidenza REPL: `armed-after-preview=true`; con flag armato `request!` non apre (`opened-when-armed=false`), disarmato apre (`opened-when-disarmed=true`).

**Perimetro**: **non è di famiglia** — edit-path, edit-image-board, edit-bezier passano tutti `false` a `reeval-script!`; edit-attach era l'unico a usare il default `true`. Il glitch si presenta identico anche dopo `cancel!` (stessa coda `run-definitions!`). Precondizione: almeno una preview live durante la sessione (aprire+OK senza gesti non arma il flag); in pratica ogni sessione ha un gesto, quindi è di fatto sempre presente. Menu Edit vs Run è indifferente (entrambi raggiungono `request!`).

**Fix** (RISOLTO 2026-07-09): (1) `eval-with-commands-script!` passa ora `false` come `arm-skip?` a `reeval-script!`, allineandosi a tutti gli altri editor della famiglia — la preview di edit-attach non arma più il flag. (2) Rumore sul silenzio: il ramo skip di `request!` logga ora un `console.warn` (il punto esatto che rendeva il difetto non diagnosticabile sul posto); post-fix quel ramo non è raggiunto nel flusso normale, quindi resta muto e parla solo se questa famiglia di leak si ripresenta. Verifica al livello del meccanismo (REPL): dopo una preview col nuovo `false` il flag non è armato (`false`) e una `request!` fresca apre al primo colpo (`true`). Suite `npm test` verde (470 test, 0 fallimenti).

**Chiusura della classe di difetto** (grep di coda, 2026-07-09): verificato che il meccanismo di skip **non è codice morto** — resta usato legittimamente da `edit-bezier/cancel!` e `tweak/cancel!` (permanent), che LASCIANO il marker nel sorgente e armano il flag via `arm-skip!` diretto perché la loro re-eval deve passarci attraverso invece di riaprire. Ma tutti e 5 i call site di `reeval-script!` passano ora esplicitamente `false`: **nessuno usava più il default `true`**, che era un footgun puro (il prossimo editor che dimentica il terzo argomento ripeteva esattamente questo bug — la causa originale). Rimosso il default: `arm-skip?` è ora un **argomento obbligatorio** di `reeval-script!` (tolta l'arità a 2). In CLJS questo dà un **warning di arità a compile-time** se un chiamante lo omette (l'arità-2 lo rendeva prima un 2-arg valido e silenzioso), non un errore a runtime, ma è sufficiente a far emergere l'omissione nel watch build. L'arming legittimo resta esplicito nei `cancel!` che lasciano il marker.

**Scoperta**: Vincenzo/Claude, 2026-07-08, durante il testing interattivo di `dev-docs/brief-edit-attach-handles.md`; root-causato e risolto 2026-07-09.

## Chiuso

### Il rider `:pure` di un bezier è congelato nel frame del sotto-path, non del path che lo `follow`a — RISOLTO 2026-07-07

**Stato originale**: `rec-follow*` (`src/ridley/editor/macros.cljs`) spliceva `(path-micro-commands sub-path)` — i micro-comandi GIÀ tessellati del sotto-path — dentro il `:recording` del path esterno. I micro th/tv/tr/f sono relativi e compongono correttamente sotto qualunque posa esterna; il rider `:pure` no — restava congelato ai valori calcolati quando `sub-path` era stato tessellato isolatamente (sempre dall'identità), indipendentemente dalla posa del path esterno nel punto del `follow`.

**Risoluzione** (dev-docs/brief-recording-highlevel-fase2a.md, Parte 1): `rec-follow*` ora splice-a i comandi ALTO LIVELLO di `sub-path` (`(:commands path)`, non la loro tessellazione) nel `:recording` esterno, poi avanza la posa locale del recorder lowerando solo ciò che è stato splice-ato — stesso pattern di `rec-append-curve!`. Il rider `:pure` di un bezier dentro `sub` viene quindi (ri)calcolato da `lower-commands` quando lowera il path ESTERNO, contro la posa d'ingresso reale di quel comando in `outer` — corretto per costruzione, non un fix ad-hoc sul rider. Golden di regressione: `c8c` in `test/ridley/turtle/lower_commands_golden_test.cljs` (follow con bezier dentro `sub`, precedeuto da una rotazione nel path esterno).

### `edit-path` 3D: il valore `live` di un nodo bezier non è consumabile direttamente — RISOLTO 2026-07-07

**Stato originale**: in 3D, quando i nodi contenevano un `:bez`, il valore `live` di `request!` era `{:type :path :commands (nodes->commands-3d nodes)}` con `nodes->commands-3d` che emetteva per un segmento bezier un comando compatto ARGS-BASED (`{:cmd :bezier-to :args [end c1 c2 :local]}`), che nessun consumatore (`extrude-from-path`, loft) sapeva interpretare — mesh nil silenziosa.

**Risoluzione** (dev-docs/brief-recording-highlevel-fase2a.md, Parte 2, punti 4 e 7): `nodes->commands-3d` emette ora la forma MAPPA dello schema del recorder (`{:cmd :bezier-to :c1 :c2 :end :steps}`, `:steps` risolto a bake-time con la stessa formula di `rec-bezier-to*`) — la stessa forma che `lower-commands`/`path-micro-commands` già sanno tessellare per ogni altro path, quindi ogni consumatore la capisce senza bisogno di un caso speciale. Il valore `live` è avvolto in `turtle/with-micro-commands` per coerenza/memoization col resto del sistema. `seed->nodes-3d` è stato riscritto in parallelo come interprete diretto sui comandi alto livello (elimina `path-micro-commands`/`:pure`/`:span` dal lato 3D).

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
