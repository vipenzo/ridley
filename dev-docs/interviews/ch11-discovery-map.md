# Discovery map per il capitolo 11 (Sottosistemi ausiliari)

## Premessa sul perimetro

Quanto segue elenca i sottosistemi che vivono in `src/ridley/` e che non rientrano in modo netto nei capitoli 5-10 secondo la mappatura fornita. Prima di entrare nell'elenco, due note sul perimetro.

**Cosa rientra ancora in ch5/ch6 anche se sta nella cartella `editor/`.** Quattro file dentro `src/ridley/editor/` non sono parte di ch11 ma estendono ch5 (runtime DSL) e ch6 (motore geometrico): [`editor/impl.cljs`](src/ridley/editor/impl.cljs) e [`editor/implicit.cljs`](src/ridley/editor/implicit.cljs) sono il lato runtime delle macro e dei binding impliciti del turtle, [`editor/operations.cljs`](src/ridley/editor/operations.cljs) e [`editor/text_ops.cljs`](src/ridley/editor/text_ops.cljs) implementano `extrude`/`loft`/`bloft`/`revolve` puri e l'estrusione di testo. Se ch5 copre "pattern di macro" e ch6 copre il "mesh engine (extrude/loft/bloft/revolve)", questi quattro file ne sono naturalmente parte. Lo segnalo perché la dicitura "include `geometry/`, `clipper/`, `manifold/`, `sdf/`, parte di `turtle/`" non li menziona esplicitamente.

**Stato condiviso dell'editor (ch8).** [`editor/state.cljs`](src/ridley/editor/state.cljs) custodisce `turtle-state-var` (SCI dynamic var su atom), `print-buffer`, `get-editor-content` e `run-definitions-fn`. È il caso di scuola del pattern callback su atom descritto in ch8. Lo cito qui per completezza, non come sottosistema autonomo.

## Sottosistemi candidati per ch11

### Frontend dell'editor (sopra l'eval loop)

#### CodeMirror
1. **Percorso**: [`src/ridley/editor/codemirror.cljs`](src/ridley/editor/codemirror.cljs).
2. **Cosa fa**: integrazione di CodeMirror 6 con paredit, line numbers togglabili, evidenziazione della forma sotto AI focus, history undo/redo, autocompletamento.
3. **Punto d'innesto**: unica chiamata da `core.cljs` a [`cm/create-editor`](src/ridley/core.cljs#L2393) con callback `:on-change`/`:on-run`/`:on-selection-change`. È il punto da cui parte tutto: la `:on-run` chiama `evaluate-definitions` (ch5).
4. **Volume**: 1137 righe.
5. **Note**: pacchetti npm `@codemirror/*` e `@nextjournal/clojure-mode`. È accoppiato a `ai/history` (auto require) per l'AI focus indicator.

#### Tweak mode
1. **Percorso**: [`src/ridley/editor/test_mode.cljs`](src/ridley/editor/test_mode.cljs).
2. **Cosa fa**: implementa il macro `(tweak ...)`, che valuta un'espressione e crea slider live sui literal numerici per ri-eseguirla in tempo reale.
3. **Punto d'innesto**: SCI binding `tweak`. Legge il sorgente via `state/get-editor-content`. Chiama `cm/replace-range` per riscrivere i literal nell'editor.
4. **Volume**: 770 righe.

#### Pilot mode
1. **Percorso**: [`src/ridley/editor/pilot_mode.cljs`](src/ridley/editor/pilot_mode.cljs).
2. **Cosa fa**: implementa il macro `(pilot :name)`, sessione interattiva da tastiera che pilota il turtle e, alla conferma, riscrive l'espressione in `(attach! :name commands...)`.
3. **Punto d'innesto**: SCI binding `pilot`. Coupling forte con `cm/*` per leggere e sostituire la forma corrente.
4. **Volume**: 579 righe.
5. **Note**: tweak e pilot sono "modal evaluators": stati interattivi che scrivono nel sorgente e si appoggiano sopra l'eval loop, non dentro.

### Sistema AI/LLM

#### Cluster `ai/`
1. **Percorso**: [`src/ridley/ai/`](src/ridley/ai/), 11 file.
2. **Cosa fa**: orchestrazione delle chiamate ai provider LLM (Anthropic, OpenAI, Groq, Ollama, Google) per generazione codice, descrizione vision, sessioni iterative con autocorrezione, RAG, history, parsing direttive di cattura.
3. **Punto d'innesto**: `core.cljs` requires sei namespace `ai/*` e chiama [`auto-session/init!`](src/ridley/core.cljs#L2483) iniettando callback (`editor-view-atom`, `save-fn`). Le funzioni `ai-insert-code` e `ai-edit-code` sono passate poi al sottosistema voce. SCI binding `(describe ...)` esposto via `ai/describe`.
4. **Volume**: ~3300 righe complessive. File maggiori: `prompts.cljs` (995, contenuto di prompt), `describe.cljs` (483), `auto_session.cljs` (461), `batch.cljs` (445), `core.cljs` (370).
5. **Note**: si appoggia massicciamente a `viewport/capture` e a `settings`. Vedi anche **Tre cose che potrebbero sorprendere** in fondo.

#### Pannello prompt
1. **Percorso**: [`src/ridley/ui/prompt_panel.cljs`](src/ridley/ui/prompt_panel.cljs).
2. **Cosa fa**: overlay dialog per visualizzare e modificare i system prompt usati dalle feature AI.
3. **Punto d'innesto**: aperto dalla UI, scrive su `ai/prompt-store`. Unico modulo in `ui/` di livello "panel".
4. **Volume**: 285 righe.

#### Settings
1. **Percorso**: [`src/ridley/settings.cljs`](src/ridley/settings.cljs).
2. **Cosa fa**: persistenza in localStorage delle impostazioni AI (provider, key, model, tier) e flag audio.
3. **Punto d'innesto**: caricato in [`core.cljs:2452`](src/ridley/core.cljs#L2452). Letto da tutto il cluster `ai/`, da `audio.cljs`, da `ui/prompt_panel`.
4. **Volume**: 330 righe.
5. **Note**: nominalmente "settings generici" ma di fatto è infrastruttura del cluster AI.

### Voce

#### Cluster `voice/`
1. **Percorso**: [`src/ridley/voice/`](src/ridley/voice/), 9 file.
2. **Cosa fa**: input vocale push-to-talk e listening continuo, pipeline speech-to-text, parser deterministico di comandi, dispatch a handler dell'editor, sintesi vocale di risposte, sistema di help con database di simboli e template multilingua.
3. **Punto d'innesto**: [`voice/init!`](src/ridley/core.cljs#L2463) riceve dal core sei callback (`insert`, `edit`, `navigate`, `execute`, `undo`, `get-script`). Le prime due sono in realtà `ai-insert-code` e `ai-edit-code`: la voce non interpreta sempre da sola, spesso passa per AI.
4. **Volume**: ~2700 righe in due assi: pipeline `state/speech/parser/panel/modes/i18n/core` (~1500), help system `help/{core,db,ui}` (~1300).
5. **Note**: il blob `help/db.cljs` (951 righe) è una tabella di simboli con template, alias e doc i18n.

### Manuale

#### Cluster `manual/`
1. **Percorso**: [`src/ridley/manual/`](src/ridley/manual/), 4 file.
2. **Cosa fa**: pannello manuale live con navigazione fra pagine, lingua IT/EN, esempi eseguibili e history.
3. **Punto d'innesto**: [`setup-manual`](src/ridley/core.cljs#L2457) wira il pannello e i bottoni Run/Copy. Lo stato è un atom locale `manual-state` con `add-state-watcher!` per agganciare la UI.
4. **Volume**: ~3650 righe. La quasi totalità è in `content.cljs` (2986).
5. **Note**: usa `viewport/capture` per esportare immagini. Vedi sorpresa #1.

### Libreria utente

#### Cluster `library/`
1. **Percorso**: [`src/ridley/library/`](src/ridley/library/), 5 file.
2. **Cosa fa**: librerie utente come namespace SCI separati (`robot/arm`), persistenza dual-backend (filesystem desktop via geo_server, localStorage web), import SVG e STL (parsing + generazione di sorgente libreria), pannello UI con drag&drop e edit mode.
3. **Punto d'innesto**: [`setup-library-panel`](src/ridley/core.cljs#L1745) inietta callback. `library/core` entra nell'opzione `:namespaces` di SCI e abilita gli import prefissati. Tre binding al DSL via `editor/bindings.cljs`: `svg`, `svg-shape`, `decode-mesh`. In edit mode il pannello sovrascrive il contenuto del CodeMirror e lo ripristina dopo (coupling con `cm/*`).
4. **Volume**: ~1600 righe. File maggiori: `panel.cljs` (584), `storage.cljs` (317), `svg.cljs` (299), `stl.cljs` (257), `core.cljs` (175).
5. **Note**: si appoggia al geo_server (ch9) per il backend filesystem ma fa anche fallback localStorage trasparente.

### Animazione

#### Cluster `anim/`
1. **Percorso**: [`src/ridley/anim/`](src/ridley/anim/), 4 file.
2. **Cosa fa**: registry di animazioni preprocessate in array di pose per frame, link parent/child, easing, collision registry, playback nel render loop.
3. **Punto d'innesto**: cinque callback wirate da `core.cljs` ([righe 2412-2417](src/ridley/core.cljs#L2412-L2417)): `set-mesh-callbacks!` (registry), `set-update-geometry-callback!` (Three.js), `set-rigid-transform-callbacks!` (set/reset), `set-refresh-callback!`, `set-async-output-callback!`. Chiamato per frame da `viewport`. Le stesse cinque chiamate sono ripetute in `reload` (defonce hot-reload).
4. **Volume**: ~1700 righe. File maggiori: `playback.cljs` (669), `preprocess.cljs` (564), `core.cljs` (411).

### Cattura offscreen e XR

#### Cattura
1. **Percorso**: [`src/ridley/viewport/capture.cljs`](src/ridley/viewport/capture.cljs).
2. **Cosa fa**: rendering offscreen via `WebGLRenderTarget` per generare immagini delle sei viste ortografiche, prospettiva, slice e PNG export. Gestisce zip multi-immagine via JSZip.
3. **Punto d'innesto**: nessun binding DSL diretto. Chiamato da quattro punti, di cui tre nel cluster AI (`ai/describe`, `ai/capture-directives`, `ai/auto-session`, `ai/describe-session`) e uno in `manual/export`.
4. **Volume**: 592 righe.
5. **Note**: nominalmente è "viewport" ma di fatto è infrastruttura per AI vision e per gli screenshot del manuale.

#### WebXR
1. **Percorso**: [`src/ridley/viewport/xr.cljs`](src/ridley/viewport/xr.cljs).
2. **Cosa fa**: supporto VR e AR con controller, drag/teleport, control panel in scena, toggle visibilità per griglia/assi/turtle/linee, modalità move.
3. **Punto d'innesto**: `init` di `core.cljs` chiama [`xr/create-vr-button`](src/ridley/core.cljs#L2436) e `xr/create-ar-button`, e registra action callback ([`xr/register-action-callback!`](src/ridley/core.cljs#L2419)) per evitare dipendenza circolare con `registry`.
4. **Volume**: 810 righe.
5. **Note**: usa `three`. Stato locale `xr-state` con ~30 chiavi che traccia sessione, controller, drag, panel buttons.

### Export STL/3MF

1. **Percorso**: [`src/ridley/export/stl.cljs`](src/ridley/export/stl.cljs), [`src/ridley/export/threemf.cljs`](src/ridley/export/threemf.cljs).
2. **Cosa fa**: export binario STL e 3MF. `download-mesh` apre il file picker nativo (desktop) o fa fallback su download link (web), scegliendo il formato dall'estensione.
3. **Punto d'innesto**: chiamato dai bottoni Save in `core.cljs`. Esposto al DSL via `editor/bindings.cljs` (`download-mesh`).
4. **Volume**: 357 + 114 righe.
5. **Note**: si appoggia a `env` per scegliere fra picker nativo (Tauri) e download HTML.

### Sottosistemi minori

#### Audio feedback
1. **Percorso**: [`src/ridley/audio.cljs`](src/ridley/audio.cljs).
2. **Cosa fa**: ping/buzz Web Audio su esito eval (success/error).
3. **Punto d'innesto**: chiamato da `evaluate-definitions` e dal REPL eval in `core.cljs` (4 call site). Letto via `settings/audio-feedback?`.
4. **Volume**: 62 righe.

#### Voronoi
1. **Percorso**: [`src/ridley/voronoi/core.cljs`](src/ridley/voronoi/core.cljs).
2. **Cosa fa**: generazione procedurale di shell 2D Voronoi compatibili con loft/extrude/revolve.
3. **Punto d'innesto**: import in [`editor/bindings.cljs:32`](src/ridley/editor/bindings.cljs#L32). PRNG mulberry32 deterministico, parte da Delaunay (`d3-delaunay`) e clipper.
4. **Volume**: 263 righe.
5. **Note**: ambiguo. È compute geometrico puro, ma non è citato nel perimetro di ch6 ("`geometry/`, `clipper/`, `manifold/`, `sdf/`, parte di `turtle/`"). Da decidere se sta in ch6 (consigliato) o in ch11.

#### Schema
1. **Percorso**: [`src/ridley/schema.cljs`](src/ridley/schema.cljs).
2. **Cosa fa**: spec `cljs.spec.alpha` per mesh e path, con `assert-mesh!` e `assert-path!` no-op fuori da `goog.DEBUG`.
3. **Punto d'innesto**: validazione runtime dev. Re-export in `api.cljs`.
4. **Volume**: 62 righe.
5. **Note**: foundational, non un sottosistema. Probabile breve menzione in nota di ch6 oppure in ch11 come "tooling dev".

#### Math
1. **Percorso**: [`src/ridley/math.cljs`](src/ridley/math.cljs).
2. **Cosa fa**: vector utilities (`v+`, `v-`, `cross`, `rotate-around-axis`, ...).
3. **Punto d'innesto**: usato ovunque (turtle, geometry, anim).
4. **Volume**: 62 righe.
5. **Note**: foundational, non un sottosistema. Stesso ragionamento di `schema`.

#### API Node target
1. **Percorso**: [`src/ridley/api.cljs`](src/ridley/api.cljs).
2. **Cosa fa**: entry point della build `ridley-core` (Node library), re-export di moduli puri (math, schema, geometry, turtle, clipper, voronoi, manifold).
3. **Punto d'innesto**: build target separato, non istanziato dal browser.
4. **Volume**: 72 righe.
5. **Note**: rivela un secondo target di build oltre al bundle browser/Tauri. Da chiarire se ch9 (confine webapp/desktop) include o meno questo terzo asse "libreria Node". Vedi sorpresa #3.

## Tre cose che potrebbero sorprendere

**1. Manual e voice sono dominati da blob di contenuto, non da logica.** [`manual/content.cljs`](src/ridley/manual/content.cljs) è 2986 righe (il file più lungo del progetto, alla pari con `viewport/core` e `core` stessi) ma è quasi interamente una struttura dati di pagine multilingua hardcoded. [`voice/help/db.cljs`](src/ridley/voice/help/db.cljs) è altre 951 righe di tabella di simboli con template e doc. Trattare questi due come "contenuto", non come logica del sottosistema, evita di gonfiare le pagine di ch11. Il sottosistema vero è il rendering del manual (~660 righe in `core/components/export`) e la pipeline voice senza help (~1500 righe).

**2. `viewport/capture` è di fatto infrastruttura AI.** Su cinque chiamanti, quattro sono dentro `ai/*` e il quinto è `manual/export`. Nessun binding DSL diretto, nessun wiring da `core.cljs`. È più onesto descriverlo come "infrastruttura del cluster AI per generare input vision" che come modulo viewport. Lo stesso vale per `settings.cljs`: è formalmente generico ma di fatto custodisce solo config AI più un flag audio.

**3. Esiste un terzo target di build che non è ancora stato discusso.** [`api.cljs`](src/ridley/api.cljs) dichiara una superficie pubblica per "ridley-core (Node library target)" che re-esporta solo moduli puri (no browser deps). Ch9 menziona il bundle unico browser/Tauri con `RIDLEY_ENV`, ma non chiarisce se questo target Node è vivo, dismesso o solo un placeholder. Vale la pena verificare prima di scrivere ch11, perché se è vivo ridisegna il discorso del confine: non sono due ambienti (web/desktop) ma tre (web/desktop/lib).

**Bonus.** Il pattern callback-passing che ch8 descrive come trasversale è particolarmente denso in `anim/playback`: cinque callback distinti wirati due volte (init e reload) per evitare circolarità con `viewport` e `registry`. Anche `xr` lo usa per le action del control panel, e `voice/init!` ne riceve sei dal core. Se ch8 vuole un caso da manuale, `anim/playback` è il candidato più denso, `voice/init!` quello con più callback per chiamata.
