# Interview - Chapter 5: DSL Runtime

Fact-finding answers for Architecture.md chapter 5. Each answer cites the
file (and function/line) it was read from. Where code does not give a
clear answer, that is stated explicitly.

---

## Sezione 1 - SCI configuration

### 1.1 Dove vive l'inizializzazione del context SCI?

- File: `src/ridley/editor/repl.cljs`
- Funzione chiave: `make-sci-ctx` (repl.cljs:30-38), coordinata da
  `get-or-create-ctx` (repl.cljs:40-46) e `reset-ctx!` (repl.cljs:48-55).
- Il context e' un `defonce` atom: `sci-ctx` (repl.cljs:28). Un
  riferimento condiviso e' replicato in `state/sci-ctx-ref`
  (state.cljs:101) per evitare la dipendenza circolare
  repl -> bindings -> test-mode -> repl.

### 1.2 Quali namespace di base sono esposti a SCI?

Esposti esplicitamente da `make-sci-ctx`:

- `:bindings` = `base-bindings` (bindings.cljs:64-621), tutti i simboli
  vivono nel namespace di default utente (`user`).
- `:namespaces` = risultato di `library/load-active-libraries`
  (library/core.cljs:132-175), ovvero le librerie attive dell'utente
  come mappa `{lib-sym {fn-sym fn-val}}`. Ogni libreria viene
  auto-richiesta via `(require 'lib-sym)` subito dopo init
  (repl.cljs:36-37).

Nessun namespace della stdlib Clojure viene aggiunto esplicitamente:
si usa il set di namespace che SCI espone di default (che include
`clojure.core`, `clojure.string`, `clojure.set`, `clojure.walk`). La
tuple `{:bindings ... :namespaces ...}` passata a `sci/init`
(repl.cljs:32-33) non contiene `:classes`, `:imports`, ne'
estensioni particolari - quindi l'interop JS via `js/...` NON e'
esposta all'utente: tutto passa attraverso i binding.

Ambiguita': la sola prova che `clojure.string` sia raggiungibile arriva
dall'uso che le macro ne fanno (`clojure.string/join` in
macros.cljs:791, 1595, 1676) e dal fatto che richiede che il
namespace esista nel SCI di default.

### 1.3 Ciclo di vita del context

Piu' articolato di un singolo init. Il context viene **ricostruito da
zero ad ogni Run/Cmd+Enter**:

- `evaluate-definitions` (repl.cljs:61-89) chiama sempre `reset-ctx!`
  prima di valutare (repl.cljs:68).
- `reset-ctx!` (repl.cljs:48-55) chiama `svg/cleanup!`, crea un nuovo
  `make-sci-ctx`, riassegna entrambi gli atom `sci-ctx` e
  `state/sci-ctx-ref`, e resetta la turtle root con
  `state/reset-turtle!`.
- `evaluate-repl` (repl.cljs:91-125) NON fa reset del context: riusa
  `get-or-create-ctx` in modo che le `def` del pannello Definitions
  restino disponibili a REPL command successivi. Conserva la posa della
  turtle, ma azzera `geometry`/`meshes` del turtle-state, lo
  `scene-accumulator` e il `print-buffer` (repl.cljs:101-108).
- Le librerie vengono ricaricate ad ogni reset: `load-active-libraries`
  gira dentro `make-sci-ctx`. Modifiche nelle librerie si applicano al
  prossimo Run.
- Due variabili dinamiche SCI istanziate una volta sola in state.cljs
  sopravvivono al reset: `eval-source-var` (state.cljs:127) e
  `eval-text-var` (state.cljs:128); vengono ri-bindate con
  `sci/binding` ad ogni `eval-string` (repl.cljs:76-77, 111-112).

### 1.4 Feature SCI disabilitate deliberatamente

La chiamata a `sci/init` (repl.cljs:32-33) passa solo `:bindings` e
`:namespaces`. Non c'e' una lista esplicita di feature disabilitate,
pero' di fatto mancano:

- `:classes` / `:imports`: nessun accesso a classi JS via interop.
  L'utente non puo' scrivere `(js/fetch ...)` o `(.-foo some-obj)`:
  dovrebbe usare solo i binding dichiarati in `base-bindings`. I
  riferimenti JS che servono (es. `Math.sin`) sono iniettati come
  simboli espliciti (`'sin js/Math.sin`, bindings.cljs:466-482).
- `:load-fn`: non c'e' require dinamico di namespace arbitrari. Le
  uniche namespaces "utente" sono quelle della libreria caricate in
  anticipo.
- `:reader-macros` / `:features`: nessuna configurazione custom, si
  prende il default SCI.

Ambiguita': il codice non contiene un commento "disable X because Y".
Le restrizioni sono conseguenza della configurazione minimale, non di
una dichiarazione esplicita - quindi non possiamo asserire che siano
disabilitazioni intenzionali: sono semplicemente non abilitate.

---

## Sezione 2 - Binding system

### 2.1 Dove vivono i binding DSL

Confermato: `src/ridley/editor/bindings.cljs`, mappa `base-bindings`
(bindings.cljs:64-621). E' una singola mappa piatta da simbolo
Clojure a valore (funzione, var dinamica o valore JS).

Aggiunte complementari:

- `src/ridley/editor/macros.cljs` (`macro-defs`, una stringa di macro
  valutata nel context dopo `sci/init`, repl.cljs:34).
- Le librerie utente aggiungono i loro binding come namespace separati
  via `library/load-active-libraries` (library/core.cljs:132-175).

### 2.2 Come sono organizzati

- Elenco piatto in una singola mappa `base-bindings`, con commenti di
  sezione (`;; Implicit turtle commands`, `;; 3D primitives`, ecc.).
- Macro separate dalle funzioni: vivono in `macro-defs` come stringa
  iniettata dopo l'init (repl.cljs:34). Le funzioni di implementazione
  che le macro richiamano sono in `base-bindings`, spesso con il
  suffisso `-impl` (es. `extrude-impl`, `register` ma espande a
  primitive come `register-mesh!`).

### 2.3 Conteggio per macrocategoria

Totale binding in `base-bindings`: 425 voci (conteggiate con
`grep -E "^\s+'[a-zA-Z...]+" bindings.cljs | wc -l`).
Totale macro in `macro-defs`: 42 `defmacro`
(`grep defmacro src/ridley/editor/macros.cljs | wc -l`).

Tabella approssimativa (conteggio per sezioni nei commenti della
mappa):

| Macrocategoria | Esempi | Conteggio approx |
| -------------- | ------ | ---------------- |
| Turtle implicit + pure (f/th/tv/tr/u/d/rt/lt + arc + bezier + goto) | `f`, `th`, `arc-h`, `bezier-to`, `goto`, `look-at`, `turtle-f` | ~35 |
| Path recorder (`rec-*`, `path-from-recorder`, ...) | `rec-f`, `rec-th`, `path-segments-impl` | ~20 |
| Shape constructors + trasformazioni 2D | `circle`, `rect`, `poly`, `fillet-shape`, `shape-union`, `voronoi-shell` | ~25 |
| Text shapes / font | `text-shape`, `load-font!`, `extrude-text` | ~7 |
| 3D primitives impl | `box-impl`, `sphere-impl`, `cyl-impl`, `cone-impl`, `stamp` | ~6 |
| Extrude/Loft/Revolve pure + impl | `ops-extrude`, `pure-loft-path`, `extrude-impl`, `loft-impl`, `revolve-impl`, `bloft-impl` | ~25 |
| Shape-fn system (modulatori) | `tapered`, `twisted`, `rugged`, `fluted`, `woven`, `displaced`, `shape-fn` | ~20 |
| Noise / heightmap / profile | `noise`, `fbm`, `heightmap`, `sample-heightmap`, `mesh-to-heightmap` | ~10 |
| Face selection + highlighting | `find-faces`, `face-at`, `auto-face-groups`, `highlight-face`, `flash-face`, `list-faces` | ~18 |
| Fillet / chamfer | `fillet`, `chamfer`, `chamfer-edges`, `find-sharp-edges`, `fillet-shape`, `chamfer-shape` | ~6 |
| Manifold booleans + utilita' mesh | `mesh-union-impl`, `mesh-difference-impl`, `mesh-hull-impl`, `mesh-smooth`, `mesh-refine`, `solidify-impl`, `slice-mesh` | ~12 |
| Scene registry + visibility | `register-mesh!`, `show-mesh!`, `hide-mesh!`, `claim-mesh!`, `visible-meshes`, `get-mesh`, `$`, `refresh-viewport!`, `register-value!`, `register-path!`, `register-shape!`, `register-panel!` | ~25 |
| Panel (billboard testuale 3D) | `panel`, `out`, `append`, `clear`, `show-panel!` | ~7 |
| Animation / easing / collisions / link | `anim-register!`, `play!`, `stop!`, `seek!`, `ease`, `link!`, `on-collide`, `anim-preprocess` | ~20 |
| Picking / selection readers | `selected`, `selected-mesh`, `selected-face`, `source-of`, `origin-of`, `last-op` | 7 |
| Pilot / Tweak / Test mode | `pilot-request!`, `tweak-start!`, `tweak-start-registered!` | 3 |
| STL / 3MF / Manual export | `save-stl`, `save-3mf`, `save-mesh`, `export`, `export-manual-en`, `export-manual-it`, `export-manual` | 7 |
| View capture (per AI describe) | `render-view`, `render-all-views`, `save-views`, `render-slice`, `save-image` | 5 |
| AI describe session | `describe`, `ai-ask`, `end-describe`, `cancel-ai`, `ai-status` | 5 |
| Import SVG / STL | `svg`, `svg-shape`, `svg-shapes`, `decode-mesh` | 4 |
| SDF (libfive via Rust) | `sdf-sphere`, `sdf-box`, `sdf-union`, `sdf->mesh`, `sdf-gyroid`, `sdf-bars`, `sdf-formula` | ~28 |
| Warp / mesh utilita' | `warp-impl`, `inflate`, `dent`, `twist`, `roughen`, `merge-vertices`, `mesh-simplify`, `mesh-diagnose`, `mesh-laplacian` | ~11 |
| Viewport visibility / camera | `show-lines`, `hide-lines`, `show-stamps`, `show-turtle`, `hide-turtle`, `fit-camera` | ~8 |
| Measurement | `distance`, `ruler`, `bounds`, `area`, `clear-rulers` | 5 |
| Source tracking (DSL helpers) | `*eval-source*`, `*eval-text*`, `add-source`, `source-ref`, `set-source-form!`, `get-source-form` | 6 |
| Math / Vec3 | `PI`, `sin`, `cos`, `sqrt`, `pow`, `atan2`, `vec3+`, `vec3-dot`, `vec3-cross`, `vec3-normalize` | ~20 |
| Printing + debug (`log`/`print`/`T`/`bench helpers`) | `print`, `println`, `pr`, `prn`, `log`, `T`, `perf-now`, `print-bench` | 8 |
| Env / settings / runtime | `env`, `desktop?`, `audio-feedback?`, `set-audio-feedback!`, `run-definitions!` | 5 |

I totali per sezione vanno sommati a mano: i numeri sopra sono stimati
leggendo i raggruppamenti commentati (bindings.cljs:64-621) e danno
425 circa, in linea con il grep. Da notare che molte "categorie"
condividono binding (es. `fillet-shape` conta sotto shape 2D e fillet).

### 2.4 Binding che espongono capability native del browser (non geometriche)

Nel file bindings.cljs, le capability non-geometriche esposte al DSL
sono:

| Binding | Descrizione |
| ------- | ----------- |
| `svg` / `svg-shape` / `svg-shapes` (bindings.cljs:609-611) | Import SVG: crea un `<div>` nascosto nel DOM e usa l'API SVG del browser per campionare `path`/`polyline`. |
| `load-font!` / `font-loaded?` / `text-shape` (bindings.cljs:176-181) | Caricamento font via `opentype.js` (DOM/HTTP). |
| `save-stl` / `save-3mf` / `save-mesh` / `export` (bindings.cljs:416-440) | Scrive file via Desktop (Tauri geo-server) o `showSaveFilePicker` / `<a download>` in browser. Il codice di dispatch vive in `core.cljs:541-578` (`save-blob-with-picker`). |
| `render-view` / `render-all-views` / `save-views` / `render-slice` / `save-image` (bindings.cljs:442-446) | Cattura offscreen WebGL (`WebGLRenderTarget`) per PNG export e AI describe - impl in `ridley.viewport.capture`. |
| `describe` / `ai-ask` / `end-describe` / `cancel-ai` / `ai-status` (bindings.cljs:452-456) | Richiama il provider LLM (modulo `ridley.ai.describe-session`). |
| `selected` / `selected-mesh` / `selected-face` / `selected-name` / `source-of` / `origin-of` / `last-op` (bindings.cljs:290-296) | Accesso in sola lettura allo stato del viewport picking (Alt+Click in `viewport/core.cljs:1509-1541`). |
| `tweak-start!` / `tweak-start-registered!` (bindings.cljs:549-550) | Avvia la sessione Tweak con slider DOM - impl in `editor/test_mode.cljs`. |
| `pilot-request!` (bindings.cljs:552) | Segnala a `core.cljs` di entrare in Pilot mode alla fine dell'eval (handler tastiera in `editor/pilot_mode.cljs`). |
| `flash-face` / `highlight-face` / `clear-highlights` / `fit-camera` (bindings.cljs:279-282) | Effetti visivi / controllo camera sul viewport Three.js. |
| `show-turtle` / `hide-turtle` / `show-lines` / `hide-lines` / `show-stamps` / `hide-stamps` (bindings.cljs:381-406) | Toggle visibilita' nel viewport. |
| `decode-mesh` (bindings.cljs:613) | Decodifica mesh STL codificate base64 (usata nelle library per mesh in-source). |
| `log` (bindings.cljs:484) | Scrive direttamente in `js/console.log` - canale diverso da `print`/`println` che vanno nel REPL history. |
| `run-definitions!` (bindings.cljs:618) | Richiama il tasto Run via callback registrata in `state/run-definitions-fn` (accessibility per screen reader). |
| `audio-feedback?` / `set-audio-feedback!` (bindings.cljs:615-616) | Toggle del beep Web Audio generato su eval (modulo `ridley.audio`). |
| `env` / `desktop?` (bindings.cljs:620-621) | Legge `window.RIDLEY_ENV` per sapere se siamo in Tauri (`ridley.env`). |

**Assenti come binding DSL** (confermato con grep su
bindings.cljs):

- Nessun binding per voice/dictation: il modulo `ridley.voice.*`
  e' guidato da UI/tastiera in `core.cljs` e non e' invocabile da
  codice utente (nessun `'voice-*` in bindings.cljs).
- Nessun binding per WebRTC sync: `ridley.sync.peer` e' pilotato
  dalla UI in `core.cljs` (`send-repl-command` a line 469); il codice
  utente non puo' avviare/stoppare session.
- Nessun binding per WebXR: `ridley.viewport.xr` e' pilotato dai
  bottoni VR/AR in `core.cljs:2436-2437`.
- Nessun binding per Web Audio "editing": `ridley.audio` espone solo il
  beep di feedback; non c'e' nulla di DSL-visible per manipolare audio
  dall'utente.

### 2.5 Binding che differiscono tra webapp e desktop

In bindings.cljs non c'e' branching: la mappa e' la stessa in tutti i
runtime. Il switch webapp vs desktop avviene **a runtime all'interno
delle implementazioni**:

- `env/desktop?` (env.cljs:14) legge `window.RIDLEY_ENV` iniettato
  da Tauri.
- `save-blob-with-picker` (core.cljs:541-578) decide se usare
  `desktop-pick-save-path` + `desktop-write-file`
  (export/stl.cljs:211-274), `showSaveFilePicker` o il fallback
  `<a download>` in base a `env/desktop?` e feature detection.
- `library/storage.cljs:19-32` (`lib-dir`) tenta una chiamata
  sincrona a `POST /home-dir` del geo-server; se fallisce cade su
  `localStorage` (confermato dal commento di header del file 1-8 e
  dalla logica nelle funzioni di lettura/scrittura successive).
- I moduli SDF (`ridley.sdf.core`) e Manifold "native"
  (`ridley.manifold.native`) fanno `XMLHttpRequest` sincrono contro
  `http://127.0.0.1:12321`. Se il server non c'e' (tipico webapp),
  l'HTTP fallisce e l'utente vede un errore a runtime. Nota che SDF
  e' l'unica strada (non c'e' fallback WASM per libfive al momento),
  quindi i binding `sdf-*` di fatto funzionano solo in desktop o se
  un utente webapp ha un geo-server locale acceso.
- I binding Manifold 3D (`mesh-union-impl`, ecc.) girano contro il
  modulo `ridley.manifold.core` che usa Manifold WASM - quindi funzionano
  anche in webapp.

Conclusione: la mappa SCI e' uniforme; il dispatch env-dependent e'
negli implementatori, non nei binding.

---

## Sezione 3 - Eval loop

### 3.1 Flusso da Cmd+Enter a viewport aggiornato

1. **Keyboard binding** - l'handler Cmd+Enter viene registrato due
   volte: dalla keymap CodeMirror (attiva quando l'editor ha focus,
   `core.cljs` commento a 681) e da un listener globale
   `core.cljs:704-716` per cattura "da REPL/viewport".
2. **evaluate-definitions** (core.cljs:257-274): pulisce
   `registry/clear-all!`, `anim/clear-all!`, `viewport/clear-rulers!`,
   mostra lo spinner e pianifica con `requestAnimationFrame` +
   `setTimeout 0` il vero lavoro (per permettere il rendering dello
   spinner prima di bloccare il main thread).
3. **evaluate-definitions-sci** (core.cljs:201-255) chiama
   `repl/evaluate-definitions` con il contenuto dell'editor
   (`cm/get-value`).
4. **repl/evaluate-definitions** (repl.cljs:61-89):
   - `reset-ctx!` (repl.cljs:48) ricostruisce il context SCI da zero,
     ricarica le librerie, resetta la turtle.
   - Reset di `turtle-state`, `scene-accumulator`, `print-buffer`
     (state.cljs).
   - Esegue `sci/eval-string explicit-code ctx` dentro
     `sci/binding [state/eval-source-var :definitions]`
     (repl.cljs:76-77). La valutazione SCI include parsing + expansion
     macro + execution. Le macro (es. `extrude`, `register`, `box`)
     catturano `&form` line/column (macros.cljs:499, 508, 1376 ecc.) e
     attaccano `{:op ... :line ... :col ... :source *eval-source*}` al
     mesh via `add-source` (bindings.cljs:47-52). Chiamate come
     `register-mesh!` inseriscono nella registry.
   - Restituisce `{:result turtle-state :explicit-result
     :print-output}` oppure `{:error msg}`.
5. **Ritorno a core.cljs** (core.cljs:206-255):
   - `viewport/hide-loading!`.
   - In caso di errore: `show-error` + `audio/play-feedback! false`.
   - Caso successo:
     - Warning di libreria (`lib-core/load-warnings`) mostrati.
     - `print-output` (cio' che `print`/`println` ha scritto) appeso
       alla REPL history tramite `add-script-output`.
     - `extract-render-data` (repl.cljs:162-174) estrae lines/stamps
       dallo `scene-accumulator`.
     - `registry/set-lines!` / `set-stamps!` /
       `set-definition-meshes!`.
     - `registry/refresh-viewport! reset-camera?` (registry.cljs)
       riconcilia la registry con Three.js: aggiunge/rimuove/ricrea
       `BufferGeometry`, sync materials, aggiorna highlight.
     - `update-turtle-indicator` + `rebuild-turtle-dropdown!`
       aggiornano l'indicatore turtle (bindings.cljs ha `show-turtle`).
     - `audio/play-feedback! true` - beep.
     - Se l'eval ha chiamato `(pilot ...)`, `pilot-mode/enter!` entra
       in modalita' interattiva (core.cljs:254-255).

Per il tasto Enter nella REPL il flusso passa per
`evaluate-repl-input` (core.cljs:334-488) che chiama
`repl/evaluate-repl`: stesso schema ma senza `reset-ctx!`, e con
`*eval-source* = :repl` + `*eval-text* = repl-code` bindati
(repl.cljs:111-112). Alla fine aggiorna la REPL history con
`add-repl-entry` (core.cljs:478).

### 3.2 Gestione errori

Tre livelli:

- **Eccezioni durante eval SCI**: catturate in `evaluate-definitions` e
  `evaluate-repl` con `(catch :default e ...)` (repl.cljs:83-89 e
  119-125). Estraggono `line`/`column` da `ex-data` e ritornano
  `{:error "msg (line N:M)"}`. Questo copre parse error, macro
  expansion error, runtime error - SCI lancia `ex-info` con la
  location quando riesce a determinarla.
- **Errori di caricamento libreria**: silenziati in `eval-library`
  (library/core.cljs:120-127) e raccolti in
  `load-warnings` atom (library/core.cljs:130). Mostrati in UI come
  warning dopo ogni run (core.cljs:213-216).
- **Errori HTTP verso geo-server** (SDF, Manifold native, file I/O):
  `throw (js/Error. ...)` interno alle impl, propagato fino al catch
  SCI (es. sdf/core.cljs:20).

Dove finiscono visibili all'utente:

- `show-error` (referenziato in core.cljs:209) mostra una banda di
  errore sopra l'editor.
- `add-repl-entry input error true` (core.cljs:473) scrive l'errore
  nella REPL history con classe `repl-error`.
- `audio/play-feedback! false` (core.cljs:210) suona il beep di
  errore.

### 3.3 Evaluation history

Due storage distinti:

- **REPL history DOM** (`repl-history-el`, core.cljs:40): lista HTML
  visibile nel pannello REPL. Popolata da `add-repl-entry`
  (core.cljs:90-120) e `add-script-output` (core.cljs:122-135).
  Persistente solo finche' la pagina e' aperta (non viene serializzata
  in localStorage).
- **Command history per le frecce Up/Down** (`command-history` atom in
  core.cljs:44, `history-index` a 45): vettore di stringhe dei comandi
  REPL inviati, navigato da `navigate-history` (core.cljs:490-512).
  In-memory only, non persistito (confermato con grep: nessuna
  chiamata `localStorage.setItem` con chiave di history).

Il panel Definitions stesso e' salvato in `localStorage["ridley-definitions"]`
da `save-to-storage` (core.cljs:520-524) ma quello e' il sorgente, non
una history di run.

### 3.4 ReplOutput vs REPL history - stessa cosa o canali diversi?

Nel codice ci sono di fatto **due canali di output distinti** dal codice
utente:

- **Canale "DSL print"**: i binding `print`, `println`, `pr`, `prn`,
  `T` (bindings.cljs:486-491) sono redirezionati a
  `state/capture-print` / `state/capture-println` (state.cljs:22-26).
  Questi scrivono su `print-buffer` atom (state.cljs:17). A fine eval
  `get-print-output` lo drena (state.cljs:28-30) e il contenuto viene
  allegato alla REPL history come `print-html` dentro la entry
  (core.cljs:104-107). Stesso percorso per output dal pannello
  Definitions: `add-script-output` (core.cljs:122-135).
- **Canale "devtools console"**: il binding `log` (bindings.cljs:484)
  fa `apply js/console.log ...`. Bypassa la REPL history e finisce
  nelle browser devtools. Commento esplicito in bindings.cljs:483:
  `Debug logging (outputs to browser console)`.

Quindi il recon che parlava di due canali e' confermato: la REPL
history DOM riceve tutto cio' che passa per `capture-print`, mentre
`log` scavalca il sistema. Non e' uno "ReplOutput" separato dal
"REPL history" - e' un canale di bypass che va direttamente al
devtools console del browser.

---

## Sezione 4 - Source tracking

### 4.1 Come Ridley associa mesh -> codice sorgente

Il meccanismo centrale e' **macro expansion con metadata**, non tracing
a runtime. Nel dettaglio:

- Le macro in `macro-defs` catturano `(meta &form)` al momento
  dell'expansion per leggere `:line` e `:column`. Esempio
  paradigmatico in macros.cljs:498-501:
  ```clojure
  (defmacro extrude [shape & movements]
    (let [{:keys [line column]} (meta &form)]
      `(-> (extrude-impl ~shape (path ~@movements))
           (add-source {:op :extrude :line ~line :col ~column
                        :source *eval-source*}))))
  ```
  La macro inietta una chiamata a `add-source` che appende una entry
  alla mesh risultante. La funzione di implementazione (`extrude-impl`)
  e' pura e non sa nulla del sorgente.
- `add-source` (bindings.cljs:47-52) e' la funzione-ponte. Fa
  `(update mesh :source-history (fnil conj []) op-info)` se il mesh ha
  `:vertices`, altrimenti no-op (lascia passare senza tracciare).
- `*eval-source*` e' una SCI dynamic var (state.cljs:127): contiene
  `:definitions` o `:repl` a seconda di dove si sta valutando. E'
  rebindata da `repl/evaluate-*` con `sci/binding`.
- **Il tracking e' macro-only**: le funzioni `*-impl` non aggiungono
  metadata (confermato con grep: `add-source` appare solo in
  bindings.cljs e `macros.cljs`, mai in `impl.cljs`/`implicit.cljs`).
  Operazioni come `register` catturano anche il **form originale** via
  `(set-source-form! name-kw '~expr)` (macros.cljs:938) - questo
  abilita il re-tweak di un mesh registrato partendo dall'espressione
  che lo ha prodotto.

### 4.2 Struttura dati per source-location

Forma di una singola entry accumulata in `:source-history`
(bindings.cljs:47-52, usato ovunque nelle macro):

```clojure
{:op     :extrude            ;; keyword dell'operazione
 :line   42                  ;; 1-based line nel sorgente
 :col    5                   ;; 1-based column
 :source :definitions}       ;; :definitions oppure :repl
```

Varianti trovate:

- Per `register` la entry ha anche `:as <name-kw>`
  (macros.cljs:934-935).
- Per operazioni booleane (union/difference/intersection/hull, warp)
  la entry include `:operands` o `:input`, un vettore di riferimenti
  compatti calcolati da `source-ref` (bindings.cljs:54-62): o il nome
  registrato (`:as`) oppure `{:op :line}` dell'ultima entry
  dell'operando (macros.cljs:1394-1421, 1432-1438).
- Oltre a `:source-history`, i mesh registrati memorizzano anche
  `:source-form` (registry.cljs:312-316): la s-expr quoted del form
  passato a `(register ...)`, usata da Tweak per riapplicare
  modifiche.

### 4.3 Dove viene conservata l'informazione sulla mesh

`:source-history` vive **dentro l'oggetto mesh stesso** (e' un campo
della mappa mesh). Viene accumulato da macro successive man mano che
la mesh attraversa pipeline di operazioni (ad es. `(-> base warp
mesh-difference register)` aggiunge 3 entry in ordine). Quando la mesh
entra nella registry, `register-mesh!` la inserisce tale e quale:
l'history rimane dentro il record mesh memorizzato in `scene-meshes`
atom (registry.cljs:136-147).

`:source-form` sta invece nella registry entry (non nella mesh): e'
settato da `set-source-form!` (registry.cljs:312-316) che scrive in
`scene-meshes` il campo `:source-form` dell'elemento con quel `:name`.

### 4.4 Feature utente abilitate dal tracciamento

- **Picking status bar con link a sorgente**: al click Alt sul
  viewport, `handle-pick` (viewport/core.cljs:1453-1507) chiama
  `update-status-bar!` (core.cljs:2318-2357) che renderizza la
  `:source-history` come elenco di link. Il click sul link invoca
  `cm/scroll-to-line!` + `cm/flash-line!` (core.cljs:2366-2372).
- **Funzioni REPL per introspezione**: `source-of`, `origin-of`,
  `last-op` (bindings.cljs:294-296; impl in viewport/core.cljs:1650-1675)
  permettono `(source-of :mesh-name)` da REPL per stampare la history.
- **Tweak mode su mesh registrato**: `tweak-start-registered!`
  (test-mode.cljs, referenziata in bindings.cljs:550) usa
  `get-source-form` (registry.cljs:318-321) per recuperare la s-expr
  e costruire slider per i literal numerici. Senza il source-form
  salvato dal macro `register`, il tweak del mesh registrato non
  potrebbe ricreare i preview interattivi.
- **Pilot mode**: `pilot-mode/confirm!` (pilot_mode.cljs, referenziata
  da `pilot-request!` in bindings.cljs:552) sostituisce
  `(pilot :name)` con `(attach! :name ...)` nel sorgente - usa le
  coordinate char-offset (`pilot-from`/`pilot-to`) memorizzate al
  lancio, quindi lavora sul testo dell'editor, non su
  `:source-history`.
- **Describe / AI**: `describe-session/describe` (bindings.cljs:453)
  legge la registry e include source-info nei prompt. L'integrazione
  esatta va letta nel modulo `ridley.ai.describe-session`, che non
  ho ispezionato in dettaglio per questa intervista.

### 4.5 Limiti / degradazioni del source tracking

- **Code dentro `defn` richiamate a runtime**: la macro cattura
  `&form` al sito della chiamata. Se l'utente definisce
  `(defn cubo [] (box 10))` e poi chiama `(cubo)`, le entry
  `:line/:col` puntano alla riga dove `(box 10)` e' scritto (dentro
  la defn), non al sito di invocazione. Per mesh ricreate piu' volte
  dallo stesso defn si accumulano entry duplicate.
- **Eval annidati / REPL**: quando la riga viene dal pannello REPL,
  `*eval-source*` vale `:repl` e `:line` e' relativa al testo del
  singolo comando REPL (non al documento Definitions). Il
  `update-status-bar!` distingue i casi: i link per `:definitions` con
  `:line` sono cliccabili; quelli `:repl` diventano solo tooltip
  (core.cljs:2302-2314).
- **Codice ricevuto via WebRTC sync**: in
  `evaluate-repl-input` (core.cljs:467-469), se siamo host il comando
  viene inoltrato ai client; a destinazione passa per lo stesso
  `evaluate-repl` con `*eval-source* = :repl`. Il mittente quindi
  non e' conservato nella history.
- **Codice generato dall'AI**: l'AI produce testo che viene inserito
  nell'editor (`cm/insert-ai-block` / `cm/replace-ai-block`,
  core.cljs:312-317) e poi valutato via la pipeline normale. Il
  tracciamento e' identico, ma i numeri di riga sono quelli
  risultanti dal documento finale, non del generate round-trip. Non
  c'e' un marker "originato da AI" in `:source-history`.
- **Codice in librerie importate**: le librerie sono valutate da
  `eval-library` (library/core.cljs:106-127). Girando dentro la
  stessa SCI, le macro applicate dentro una libreria registreranno
  `*eval-source* = :definitions` (la binding attiva al momento) ma i
  numeri di riga sono relativi al **testo della libreria** - non c'e'
  un campo `:library` nella entry per distinguerla. Potenzialmente
  fuorviante se la history attraversa entrambi i domini.
- **Funzioni non macro-wrappate**: operazioni chiamate direttamente
  sulle funzioni `*-impl` (es. `(mesh-union-impl a b)`) saltano il
  macro layer e non producono entry `:source-history`. Lo stesso per
  le funzioni `pure-*` (`pure-extrude-path`, ecc.) che sono
  intenzionalmente pure.

---

## Sezione 5 - Macro e layered delegation

### 5.1 Esempi del pattern macro + `*-impl`

Esempi concreti (macro vive in `src/ridley/editor/macros.cljs`, impl
spesso in `src/ridley/editor/impl.cljs`):

1. **extrude**
   - Macro: `defmacro extrude` (macros.cljs:498-501).
   - Impl fn: `extrude-impl` (bindings.cljs:532 -> `macro-impl/extrude-impl`
     in `src/ridley/editor/impl.cljs`).
   - Cosa aggiunge la macro: avvolge la chiamata con `path` per
     costruire un path da forme `(f ...)` / `(th ...)`, cattura
     `&form` meta per `:line/:col`, poi chiama `add-source` per
     attaccare l'entry al risultato. La fn pura non sa nulla di path
     syntax ne' di source tracking.

2. **register**
   - Macro: `defmacro register` (macros.cljs:857-965).
   - Impl fns usate: `register-mesh!`, `register-path!`,
     `register-shape!`, `register-panel!`, `register-value!`,
     `refresh-viewport!`, `claim-mesh!`, `set-source-form!`,
     `add-source`, `hide-mesh!`, `show-mesh!`, `asm-register-mesh!`
     (tutte in bindings.cljs oppure dichiarate in macros.cljs stesso).
   - Cosa aggiunge la macro: (a) binding `def ~name` in SCI per
     consentire riuso via simbolo; (b) dispatch su tipo del risultato
     (mesh / vector / map / shape / path / panel) con funzioni diverse
     per ciascuno; (c) supporto "assembly mode" quando `expr` e' un
     map literal (macros.cljs:860-891) che spinge/pop frame di gerarchia
     e crea automaticamente `link!` parent-child; (d) cattura del form
     originale (`set-source-form!`) per il successivo Tweak; (e)
     aggiunta di `:register` a `:source-history`. Niente di tutto cio'
     si potrebbe fare con una semplice funzione: serve il macro per
     creare il `def`, per quotare il form originale, e per inferire la
     struttura hierarchical.

3. **attach-face**
   - Macro: `defmacro attach-face` (macros.cljs:705-716).
   - Impl fn: `attach-face-impl` (bindings.cljs:544 ->
     `macro-impl/attach-face-impl`).
   - Cosa aggiunge la macro: distingue la forma `(attach-face mesh
     face-id & body)` da `(attach-face selection & body)` ispezionando
     il tipo del secondo argomento (keyword/number/vector/symbol ->
     face-id; altrimenti selection); avvolge il body in `path` in modo
     che movimenti DSL tipo `f`/`th` diventino comandi recorded; add
     source entry. La fn di impl accetta gia' un path struct.

4. **box** / **sphere** / **cyl** / **cone**
   - Macro: macros.cljs:1376-1391.
   - Impl fn: `box-impl`, `sphere-impl`, `cyl-impl`, `cone-impl`
     (bindings.cljs:134-137, originali in
     `ridley.editor.implicit`).
   - Cosa aggiunge la macro: solo cattura di `&form` meta -> entry
     `:op :box/...` nel `source-history`. Se non servisse il tracking,
     queste macro sarebbero superflue.

### 5.2 Perche' il pattern a due livelli

Motivazioni leggibili dal codice / commenti:

- **Source tracking**: la gran maggioranza dei pattern impl+macro
  esiste esattamente per catturare `(meta &form)` al momento
  dell'expansion. Commento in macros.cljs:1370-1374: `Source-tracking
  macro wrappers for function-bound operations. These shadow the *-impl
  function bindings to capture &form metadata`.
- **Shadowing di simboli DSL all'interno di body**: macros come `path`
  (macros.cljs:410-449), `shape` (466-473), `turtle` (1652-1657)
  ribindano simboli locali (`f`, `th`, `tv`, ...) a versioni
  recorder-driven o turtle-scoped. Senza macro non si puo' shadowing
  lessicale dei simboli del DSL dentro un body. Questa e' anche la
  ragione dei simboli riservati nei path (memoria utente).
- **Dispatch sintattico**: molte macro analizzano la forma delle args
  per scegliere il branch impl (es. `loft` / `bloft` decidono se i
  rest-args sono movements turtle, un dispatch-arg + movements, o
  due shape). Non e' materia da funzione.
- **Emissione di `def`**: solo una macro puo' generare
  `(def ~name ~value)` (usato in `register`).

### 5.3 Macro che NON seguono il pattern

Gli schemi alternativi piu' rappresentativi:

- **Pure shadow-and-record** (no impl fn): `path` (macros.cljs:410-449),
  `shape` (466-473), `with-path` (976-...), `turtle` (1652-1657). Qui
  la macro **e'** la semantica: ribinda simboli locali e chiama
  direttamente le funzioni registrate. Non c'e' un `*-impl` perche'
  il lavoro e' tutto in shadowing e binding.
- **Delega 1:1 a fn, senza dispatch sintattico**: `pen`
  (macros.cljs:477-478) -> `(pen-impl ~mode)`. Esiste solo per
  coerenza stilistica; potrebbe essere una pura fn. `solidify`
  (1441-1444), `text-on-path` (1447-1450) seguono il pattern con
  `add-source`. `bench` (1424-1429) misura ma non cattura source.
- **Emissione di strutture dati al compile time**:
  `transform->` (673-696) scansiona i passi e genera vettori
  `{:op :extrude+ :args [(path ...)]}` al momento dell'expansion;
  l'impl fn `transform->impl` poi riduce quella struttura. Il parsing
  della sintassi `:mark name` vive nel macro.
- **Animation macro** (`span`, `anim!`, `anim-proc!`, `anim-fn`,
  `anim-proc-fn`, macros.cljs:1479-1685): il macro fa
  parsing dei turtle command in AST animazione tramite
  `anim-parse-cmd` (1457-1473) - lavoro impossibile da spostare a
  runtime senza perdere la sintassi.
- **register / r**: `r` e' solo alias su `register`
  (macros.cljs:968-969). `register` e' un macro "speciale" perche'
  produce `def` - eccezione al pattern impl-puro.

Criterio per distinguerle: se la macro espande in
`(-> (<nome>-impl ...) (add-source {...}))` segue il pattern. Le
altre espandono in codice piu' strutturato o in shadowing lessicale.

### 5.4 Pattern `register` in dettaglio

Source utente:

```clojure
(register vase (extrude (circle 20) (f 30)))
```

Espansione (macros.cljs:857-965, ramo non-assembly):

```clojure
(let [raw-value# (extrude (circle 20) (f 30))
      name-kw#   :vase
      hidden?#   false
      ;; Se l'espressione ha prodotto un SDF node, materializza
      value#     (... sdf-ensure-mesh ...)
      ]
  (register-value! :vase value#)
  (if (panel? value#)
    ...
    (do
      (def vase value#)
      (cond
        (mesh? value#)
        (let [value# (add-source value# {:op :register :as :vase
                                         :line L :col C
                                         :source *eval-source*})]
          (register-mesh! :vase value#)
          (set-source-form! :vase '(extrude (circle 20) (f 30)))
          (if hidden? (hide-mesh! :vase) (show-mesh! :vase)))
        ...)
      (refresh-viewport! false)
      value#)))
```

Effetti, in ordine:

1. **Materializzazione SDF**: se `raw-value` e' un SDF node (mappa con
   `:op` stringa, macros.cljs:898-900), `sdf-ensure-mesh` lo converte
   in mesh invocando il Rust server via `sdf->mesh`.
2. **register-value!** (registry.cljs): salva il valore nella registry
   `values` per lookup via `($ :vase)`.
3. **def SCI**: `(def ~name value)` inserisce il binding nel namespace
   SCI dell'utente cosi' che riferimenti successivi a `vase` funzionino
   come normale var.
4. **add-source** con `:op :register :as :vase`: aggiunge al
   `:source-history` una entry che indica dove il register e' avvenuto
   e il nome simbolico.
5. **register-mesh!** (registry.cljs:136-147): inserisce/aggiorna
   l'entry `{:name :vase :mesh value}` nell'atom `scene-meshes`,
   preservando lo stato di visibilita' precedente.
6. **set-source-form!** (registry.cljs:312-316): salva la s-expr
   originale nell'entry, usata poi dal Tweak mode.
7. **show-mesh!** / **hide-mesh!**: imposta `:visible` nell'entry
   registry. `show-mesh!` e' idempotente e rispetta lo stato precedente
   (re-register non cambia visibilita' se gia' presente).
8. **refresh-viewport!** (registry.cljs): riconcilia Three.js - per
   ogni mesh visibile crea/aggiorna il `BufferGeometry` e applica
   materiali; rimuove mesh non piu' registrate.

Per map literal (`(register robot {:arm ... :leg ...})`) il macro
entra in "assembly mode" (macros.cljs:860-891, con helper
`asm-push!` / `asm-register-mesh!` / `assembly-goto`): crea nomi
gerarchici `:robot/arm`, `:robot/leg`, chiama `link!` per creare
legami parent-child anche inferendo punti di ancoraggio da
`(goto ...)` incontrati nel body.

---

## Sezione 6 - Integrazioni JS (ponti)

Per ciascun sottosistema indico: binding DSL (se c'e'), impl, commento.
Dove non c'e' binding DSL la tabella lo nota esplicitamente.

### 6.1 Voice interface (dettatura)

- Binding DSL: **nessuno** (grep su bindings.cljs: zero match su
  "voice"). L'utente non controlla la dettatura da DSL.
- Impl: `src/ridley/voice/core.cljs` (dispatch, ~40 defn elencate da
  grep), `voice/speech.cljs`, `voice/parser.cljs`, `voice/panel.cljs`,
  `voice/state.cljs`, `voice/i18n.cljs`, `voice/help/*`, `voice/modes/`.
  Il modulo aggancia Web Speech API; il dispatch dei comandi e'
  guidato dall'utterance (core.cljs:328, parser in `voice/parser.cljs`).
- Cosa fa: riconosce utterance, prova a matcharla come comando
  (es. "run", "save", nav tra form) o come dettatura libera verso
  l'editor, con supporto multilingua (`voice.i18n`). Si integra con
  AI per dettatura "tier" e con CodeMirror per inserire/cancellare
  form.
- **Segnalazione per capitolo 11**: il subsystem ha parser
  dedicato, i18n, modalita' multiple, help db e UI panel - non e' solo
  "qui c'e' il binding". Merita trattazione a se'.

### 6.2 Web Audio (editing audio a voce)

- Binding DSL: **nessuno per editing**. Esistono solo
  `audio-feedback?` e `set-audio-feedback!` (bindings.cljs:615-616) per
  il beep success/error.
- Impl del beep: `src/ridley/audio.cljs` (play-success! / play-error!
  via `AudioContext`).
- Cosa fa concretamente: l'unico uso Web Audio nel progetto e' il beep
  di feedback su eval. Non c'e' "editing audio a voce" nel codice: la
  dettatura vocale (vedi 6.1) non edita audio, edita codice. Se la
  domanda si riferisce a "audio generato dal codice utente", la
  risposta e' che non c'e' superficie DSL per Web Audio.
- Ambiguita': la domanda suggerisce un subsistema "Web Audio editing"
  che non ho trovato. Se esiste, non e' in bindings.cljs ne' nei moduli
  audio/voice esplorati.

### 6.3 WebRTC sync (host side)

- Binding DSL: **nessuno**. Non esistono binding `'host`, `'sync`,
  `'share` in bindings.cljs. Il sync e' completamente pilotato dalla UI.
- Impl: `src/ridley/sync/peer.cljs`. API interna: `host-session`
  (peer.cljs:133), `join-session` (198), `send-script` (232),
  `send-repl-command` (252), `send-ping` (264), `generate-share-url`
  (301), `generate-qr-code` (306).
- Usato da: `ridley.core/sync-mode` atom + UI. `core.cljs:467-469`
  mostra che dopo ogni eval REPL, se siamo host, il comando viene
  inoltrato ai client con `sync/send-repl-command`.
- Cosa fa: PeerJS (WebRTC) con codice corto, invia script completi e
  singoli comandi REPL ai peer connessi, riceve ACK (send-ping). I
  client ricevono e chiamano `evaluate-repl` localmente con
  `*eval-source* = :repl`.
- **Segnalazione per capitolo 11**: ha suo modello client/host,
  QR code, short code, ping/ack - merita trattazione dedicata.

### 6.4 WebXR (headset rendering)

- Binding DSL: **nessuno**. L'ingresso in VR/AR e' via bottoni UI.
- Impl: `src/ridley/viewport/xr.cljs`. API principali: `check-xr-support`
  (56), `check-ar-support` (63), `enter-vr` (115), `enter-ar` (126),
  `toggle-vr` (143), `toggle-ar` (150), `register-action-callback!`
  (161), `create-vr-button` (172), `create-ar-button` (193),
  `xr-presenting?` (214).
- Usato da: `core.cljs:2419-2437` - setup dei bottoni VR/AR, registra
  un callback su azione `:toggle-all-obj`.
- Cosa fa: usa l'API WebXR di Three.js per session immersive con
  controller ray (`create-controller-ray` a xr.cljs:223), testo di
  debug sprite-based, indicatori di modalita'. L'integrazione viewport
  passa dal renderer condiviso in `viewport/core.cljs`.
- **Segnalazione per capitolo 11**: ha controller handling, pannelli
  VR, azioni bindabili. Trattazione dedicata consigliata.

### 6.5 Viewport picking (Alt+Click)

- Binding DSL: `selected`, `selected-mesh`, `selected-face`,
  `selected-name`, `source-of`, `origin-of`, `last-op`
  (bindings.cljs:290-296). Sono reader dello stato picking.
- Impl interattiva: `src/ridley/viewport/core.cljs` con
  `on-viewport-alt-click` (1509-1541), `handle-pick` (1453-1507),
  `raycast-point` (1543-1567). Callback UI-side:
  `setup-picking-status-bar` in core.cljs:2359-2372.
- Cosa fa: Alt+Click raycasta contro le mesh dello scene-group Three.js,
  seleziona oggetto o fa drill-down a faccia (con flood-fill su
  coplanarity tolerance). Alt+Shift+Click toggla facce nella
  selection. Al pick, `:source-history` della mesh viene renderizzata
  nel picking status bar con link cliccabili a CodeMirror linea
  (core.cljs:2366-2372).

### 6.6 Pilot mode

- Binding DSL: `pilot` (macro in macros.cljs:1757-1758) -> espande
  a `(pilot-request! '~arg ~arg)`, dove `pilot-request!` e'
  `editor/pilot_mode/request!` (bindings.cljs:552).
- Impl: `src/ridley/editor/pilot_mode.cljs` (intero modulo, 579 righe,
  header a pilot_mode.cljs:1-5 con descrizione). Flusso:
  `request!` marca lo stato, `core.cljs:254-255` chiama
  `pilot-mode/enter!` dopo l'eval se `requested?`. Da li' la tastiera
  accumula comandi turtle, al confirm sostituisce `(pilot :name)` con
  `(attach! :name cmd1 cmd2 ...)` nell'editor usando offset
  `pilot-from/pilot-to`.
- Cosa fa: "cavalca" una mesh registrata muovendo la turtle con
  tastiera (linear step, angular step, scale step cyclable con Tab,
  count buffer vim-like). Il risultato e' codice modificato
  nell'editor, non solo una modifica runtime.

### 6.7 Tweak mode

- Binding DSL: `tweak` (macro in macros.cljs:1721-1751) -> espande a
  `tweak-start!` o `tweak-start-registered!` (bindings.cljs:549-550),
  passando (in alcune varianti) la quotazione `'expr` per poter
  riparse i literal numerici.
- Impl: `src/ridley/editor/test_mode.cljs` (770 righe, header
  test_mode.cljs:1-5). Walk AST su quoted form via
  `find-numeric-literals` (test_mode.cljs:38-60), crea slider DOM,
  ogni modifica reimposta il literal nella s-expr e rivaluta via
  SCI (context salvato in `state/sci-ctx-ref` per evitare il ciclo
  delle require).
- Cosa fa: trasforma un'espressione DSL in preview interattivo; gli
  slider mostrano in vivo le varianti. Variante `tweak :name` prende
  il source-form dalla registry (registry.cljs:318-321) - quindi
  richiede che la mesh sia registrata con il source-form gia' salvato
  dalla macro `register`.

### 6.8 File API (SVG import, file dialog)

- Binding DSL:
  - `svg`, `svg-shape`, `svg-shapes` (bindings.cljs:609-611).
  - `save-stl`, `save-3mf`, `save-mesh`, `export` (bindings.cljs:416-440).
  - `save-image`, `save-views` (bindings.cljs:444-445).
  - `load-font!` (176-179) per font.
- Impl:
  - SVG: `src/ridley/library/svg.cljs` - usa DOM hidden container e
    l'API SVG del browser per parse/sample (svg.cljs:64-100).
  - Save dialog: `core.cljs/save-blob-with-picker` (541-578) sceglie
    tra geo-server (desktop), `showSaveFilePicker` (Chrome/Edge) e
    `<a download>` (altri browser).
  - Desktop file I/O: `export/stl.cljs` con
    `desktop-pick-save-path` (211-242) e `desktop-write-file`
    (243-274) via HTTP sincrono al geo-server.
- Cosa fa: i binding `svg*` caricano una stringa SVG, creano un
  `<div>` nascosto nel DOM, campionano `path`/`polygon` usando i
  metodi `getTotalLength`/`getPointAtLength`. I binding `save-*`
  costruiscono un `Blob` e lo scrivono via il miglior canale
  disponibile (nativa in Tauri, FS Access API in Chromium, download
  link altrove).

### 6.9 localStorage (library system)

- Binding DSL: i binding per library sono indiretti. Le librerie NON
  sono manipolate direttamente da SCI-binding: vengono caricate a
  init del context da `library/load-active-libraries`
  (repl.cljs:31). L'utente le attiva/disattiva dalla UI, non da
  codice utente.
- Impl storage: `src/ridley/library/storage.cljs`. Header esplicito
  (storage.cljs:1-8): desktop = `~/.ridley/libraries/*.clj` via
  geo-server, webapp = `localStorage`.
- `lib-dir` (storage.cljs:19-32) sceglie il path tentando
  `POST /home-dir`; se fallisce cade su `/tmp/.ridley/libraries` e
  in pratica su localStorage (le ulteriori fn read/write del file
  sono tutti fallback try/catch - non ho letto l'intero file).
- Cosa fa: mantiene metadati di libreria (nome, requires, source) e
  flag "active"; `load-active-libraries` ordina topologicamente,
  valuta ciascuna con `eval-library` (core.cljs:106-127) costruendo
  una mappa SCI `:namespaces` con i var pubblici. I warning vanno in
  `library/core/load-warnings` atom e finiscono a UI via
  `core.cljs:213-216`.

Segnalazioni per capitolo 11 (sottosistemi ausiliari con architettura
oltre "binding + impl"): voice (6.1), WebRTC sync (6.3), WebXR (6.4),
library system (6.9). Per gli altri il pattern e' sostanzialmente
"binding DSL + modulo che chiama API browser".

---

## Note per il capitolo

- Il pattern macro + `*-impl` non e' universale: e' uno tra quattro
  schemi (macro-wrapped impl / shadow-and-record / dispatch-heavy /
  runtime-expansion). Distinguerli esplicitamente rende piu'
  leggibile il capitolo.
- L'argomento "source tracking = macro expansion + dynamic var"
  merita un diagramma: flusso da `&form` meta a
  `:source-history` a UI status bar.
- Il ciclo `reset-ctx!` su ogni Run e' una scelta semantica
  importante (idempotenza del pannello Definitions) e convieve con la
  preservazione della turtle pose tra comandi REPL. Vale la pena
  ribadirlo nel testo.
- Le capability native esposte a DSL sono poche e ben definite
  (sezione 2.4). La lista puo' essere usata come "superficie DSL
  verso il browser" - utile specie per la sezione accessibilita' del
  capitolo.
