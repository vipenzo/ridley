# Cap. 9 fase 1: confine webapp/desktop, panoramica

Risposte a partire dalla lettura del codice al 2026-04-25. Le citazioni sono `file:linea`. Dove l'assunzione della tua domanda non corrisponde più al codice corrente, lo segnalo nelle note.

---

## 1. Geografia del repository

### Dove vive il codice di ridley-desktop

Tutto in un unico repo (lo stesso di Ridley). Non c'è workspace separato: la cartella `desktop/` è un subdir del progetto principale e contiene la shell Tauri. Non c'è uno scaffolding stile `npm workspaces` o Cargo workspace; sono due alberi paralleli che si parlano via filesystem.

```
Ridley/
├── src/ridley/...           # Tutto il frontend ClojureScript (condiviso)
├── public/                  # Output del build :app, anche frontendDist di Tauri
├── shadow-cljs.edn          # Build CLJS
├── package.json             # npm scripts (dev, release)
├── desktop/
│   ├── dev.sh               # Wrapper di compatibilità per `npm run dev`
│   ├── build.sh             # Wrapper di compatibilità per `npm run release`
│   ├── bundle-dylibs.sh     # Post-build: copia .dylib in Ridley.app/Frameworks
│   └── src-tauri/
│       ├── Cargo.toml       # Crate ridley-desktop
│       ├── tauri.conf.json
│       ├── build.rs         # Compila libfive da sorgente vendored
│       ├── vendor/libfive/  # Sorgente libfive (CMake)
│       └── src/
│           ├── main.rs      # Entry: avvia geo-server, spawn npm in dev, build webview
│           ├── geo_server.rs  # HTTP server 127.0.0.1:12321 (tiny_http)
│           └── sdf_ops.rs   # FFI minimo a libve (C API)
```

### Sotto-progetti / namespace principali

Lato Rust il crate è uno solo, [ridley-desktop](desktop/src-tauri/Cargo.toml#L2). Ci sono tre moduli:

- [main.rs](desktop/src-tauri/src/main.rs:58-103): orchestrazione. Avvia il geo-server su thread separato, in dev mode fa spawn di `npm run dev` e attende la porta 9000, infine costruisce la finestra Tauri.
- [geo_server.rs](desktop/src-tauri/src/geo_server.rs:171): HTTP loop in un thread dedicato. Espone gli endpoint per file I/O e SDF (vedi sez. 4 e 5).
- [sdf_ops.rs](desktop/src-tauri/src/sdf_ops.rs): bindings FFI a libve, costruzione ricorsiva del tree SDF da JSON, marching-cubes via `libfive_tree_render_mesh`.

Lato CLJS i namespace sono in [src/ridley/](src/ridley/). I rilevanti per il confine sono:

- [src/ridley/env.cljs](src/ridley/env.cljs:1-17): nuovo file (17 righe), espone `(env)` e `(desktop?)` leggendo `window.RIDLEY_ENV`.
- [src/ridley/library/storage.cljs](src/ridley/library/storage.cljs): backend duale filesystem/localStorage.
- [src/ridley/manifold/core.cljs](src/ridley/manifold/core.cljs): WASM Manifold v3.0.
- [src/ridley/sdf/core.cljs](src/ridley/sdf/core.cljs): client del geo-server SDF.
- [src/ridley/export/stl.cljs](src/ridley/export/stl.cljs): export STL con dialog nativo o `<a download>`.

### Build chain

Tre processi distinti, configurati indipendentemente.

**ClojureScript (sempre)** [shadow-cljs.edn](shadow-cljs.edn:6-13). Il target `:app` produce `public/js/main.js`. Optimizations `:simple` anche in release (per non rompere interop con Manifold WASM e Three). Compilato da `npm run dev` (watch) o `npm run release`.

**Rust desktop** [Cargo.toml](desktop/src-tauri/Cargo.toml). `cargo tauri dev` per dev, `cargo tauri build` per release. Il [build.rs](desktop/src-tauri/build.rs) compila libfive da `vendor/libfive/` con CMake al primo build (si rerunna solo se cambia la cartella vendor); senza una toolchain CMake il build fallisce.

**Bundling macOS** [bundle-dylibs.sh](desktop/bundle-dylibs.sh): da lanciare a mano dopo `cargo tauri build` per copiare `libfive.dylib` e `libfive-stdlib.dylib` dentro `Ridley.app/Contents/Frameworks/`. Senza questo passo il binario distribuito non trova le dylib.

### Lancio in dev

`cd desktop && cargo tauri dev`. Il binario debug parte e fa tre cose:

1. Avvia il geo-server thread su 12321 ([main.rs:62](desktop/src-tauri/src/main.rs#L62)).
2. Spawn di `npm run dev` come child process, attesa della porta 9000 con timeout 30 s ([main.rs:64-77](desktop/src-tauri/src/main.rs#L64-L77)).
3. Apre la WKWebView e carica `frontendDist` configurato come `../../public` ([tauri.conf.json:7](desktop/src-tauri/tauri.conf.json#L7)).

Il child npm viene ucciso all'uscita ([main.rs:92-101](desktop/src-tauri/src/main.rs#L92-L101)).

Gli script `desktop/dev.sh` e `desktop/build.sh` esistono ma sono wrapper sottili: `tauri.conf.json` non ha `beforeDevCommand` o `beforeBuildCommand`, quindi non vengono invocati automaticamente. Sono utili solo se chiami a mano `./desktop/dev.sh` da un CWD diverso. Lo segnalo come potenziale debito: la documentazione storica ne presupponeva l'invocazione automatica.

### Bundle distribuibile

`cd desktop/src-tauri && cargo tauri build` produce:

- `target/release/ridley-desktop` (binario nudo)
- `target/release/bundle/macos/Ridley.app` (app bundle)
- `target/release/bundle/dmg/Ridley_*.dmg` (installer)

Poi va lanciato `desktop/bundle-dylibs.sh` per il bundling delle dylib libfive nel `.app`. Senza questo step, l'app distribuita crasha al primo `(sdf->mesh ...)` per dylib mancante.

Note: in release mode il binario non spawn'a `npm run dev`. Il bundle CLJS deve essere già compilato in `public/js/`. La pipeline release intesa è: `npm run release` poi `cargo tauri build` poi `bundle-dylibs.sh`.

---

## 2. Architettura runtime

### Quanti processi quando l'utente apre l'app

In **dev**:

1. **Processo Tauri Rust** (binario `ridley-desktop`). Contiene il main thread Tauri, il thread `geo_server` su 12321, e gestisce la WKWebView come componente embedded.
2. **Processo `npm run dev`** child del precedente. Tiene su shadow-cljs watch + http server di sviluppo su 9000 + nREPL su 7888.
3. **WKWebView** (sotto-processo gestito dal sistema operativo, non visibile direttamente). Esegue il bundle CLJS.

In **release**, scompare il #2: il bundle CLJS è statico in `public/js/`, servito direttamente dalla WKWebView attraverso `frontendDist`. Restano #1 e #3.

**Niente worker dedicati** per Manifold o libfive. Manifold è WASM in-process nella WebView. libfive è in-process nel binario Rust (chiamato sincronamente all'arrivo della richiesta HTTP). Nessun pool, nessun child process, nessun web worker.

### Frontend ClojureScript: stesso bundle?

Sì, **identico**. Il target shadow-cljs `:app` produce un solo `public/js/main.js`. La webapp pubblica (per esempio una build deployata su GitHub Pages) e l'app desktop caricano lo stesso artefatto.

La distinzione runtime avviene tramite [`window.RIDLEY_ENV`](desktop/src-tauri/src/main.rs#L12) che Tauri inietta prima di qualsiasi codice utente:

```rust
const INIT_SCRIPT: &str = r#"window.RIDLEY_ENV = "desktop";"#;
// ...
.initialization_script(INIT_SCRIPT)
```

Il namespace [ridley.env](src/ridley/env.cljs:8-17) lo legge e ritorna `:desktop` o `:webapp`. Tutto il codice CLJS che si comporta diversamente nei due ambienti chiama `(env/desktop?)`. Esempi:

- [storage.cljs:160-178](src/ridley/library/storage.cljs#L160-L178) seleziona filesystem o localStorage.
- [export/stl.cljs](src/ridley/export/stl.cljs) sceglie tra dialog nativo e `<a download>`.

Il binding `desktop?` è anche esposto al DSL utente ([bindings.cljs:621](src/ridley/editor/bindings.cljs#L621)).

Nota correttiva alla tua memoria: precedentemente la detection passava per uno **XHR sincrono a `/ping`** del geo-server con cache in atom (vedi recon doc 8.10). È stato sostituito da `RIDLEY_ENV` perché era bloccante sul main thread. Il recon doc è stale su questo punto. L'endpoint `/ping` non esiste più nemmeno lato Rust.

### Comunicazione frontend/backend

**Un solo canale: HTTP su 127.0.0.1:12321 via XMLHttpRequest sincrono.**

Niente Tauri IPC. Niente eventi. Niente WebSocket custom. Niente custom URI scheme. Il flag `withGlobalTauri: true` è in [tauri.conf.json:10](desktop/src-tauri/tauri.conf.json#L10), ma una grep su `__TAURI__` o `invoke` nel CLJS dà zero risultati. La ragione è il contratto sincrono del DSL: SCI esegue codice utente sincronamente, e `XMLHttpRequest` con `async=false` è l'unico trasporto sincrono dal browser. L'argomento è esposto in dettaglio in [transport-audit.md](dev-docs/transport-audit.md), specie sezioni 2 e 4.

Esempio concreto di chiamata: l'utente scrive `(register :s (sdf-sphere 10))`. La macro `register` chiama `sdf-ensure-mesh` se vede un nodo SDF. Questa risale a [sdf/core.cljs](src/ridley/sdf/core.cljs#L11-L20):

```clojure
(let [xhr (js/XMLHttpRequest.)]
  (.open xhr "POST" "http://127.0.0.1:12321/sdf-mesh" false)  ; sync
  (.setRequestHeader xhr "Content-Type" "application/json")
  (.send xhr (js/JSON.stringify body-js))
  (if (= 200 (.-status xhr))
    (js/JSON.parse (.-responseText xhr))
    (throw (js/Error. ...))))
```

Lato Rust [geo_server.rs:300-348](desktop/src-tauri/src/geo_server.rs#L300-L348) deserializza `{tree, bounds, resolution}`, [sdf_ops.rs:294](desktop/src-tauri/src/sdf_ops.rs#L294) costruisce ricorsivamente il tree libfive e chiama `libfive_tree_render_mesh`, ritornando una `MeshData {vertices, faces}` serializzata JSON.

Nota correttiva: nel recon doc compariva una "vestigial Tauri IPC" con `tauri::invoke_handler![ping, manifold_union, ...]` etichettata come codice morto. Questi handler sono stati **rimossi**: `main.rs` corrente non ha più `invoke_handler`, e `manifold_ops.rs` è stato cancellato (commit `f990f70`). Il canale HTTP è quindi non solo principale ma **unico**.

---

## 3. Mappa a tre colonne

Premessa: la cartella `src/ridley/**` è interamente condivisa, sempre. La differenza tra webapp e desktop non è di compilazione ma di **disponibilità di servizi a runtime**. Quindi la colonna "solo desktop" significa "usabile solo quando il geo-server risponde", la colonna "solo webapp" significa "supportato solo dal browser".

| Categoria | Webapp | Desktop | Note |
|---|---|---|---|
| CSG booleane (union/diff/intersect/hull) | ✓ WASM Manifold v3 | ✓ stesso WASM Manifold v3 | Identico, nessuna divergenza |
| `mesh-smooth`, `mesh-refine`, `solidify`, `slice-at-plane`, `extrude-cross-section`, `hull-from-points`, `concat-meshes` | ✓ WASM | ✓ WASM | Tutto via [manifold/core.cljs](src/ridley/manifold/core.cljs) |
| Clipper2 2D booleane | ✓ JS lib | ✓ stessa JS lib | Stesso bundle |
| Turtle, extrude/loft/bloft/revolve, shape-fn, warp | ✓ | ✓ | Solo CLJS |
| Three.js viewport, picking, ruler, animation | ✓ | ✓ | Solo CLJS |
| WebRTC sync (host/client) | ✓ | ✓ | PeerJS via CDN, identico |
| WebXR (VR/AR) | ✓ in browser supportati | ? in WKWebView | Non testato in webview Tauri; il codice è lo stesso, l'API potrebbe non esistere lì |
| Voice input (Web Speech API) | ✓ in Chrome/Edge | ? | Stessa storia; WKWebView su macOS supporta Web Speech in modo parziale |
| AI (provider remoti via fetch HTTPS) | ✓ | ✓ | Identico |
| **SDF / libfive** | ✗ throw js/Error al primo `materialize` | ✓ via `/sdf-mesh` su geo-server | [sdf/core.cljs](src/ridley/sdf/core.cljs) sempre presente nel bundle, ma chiamarlo senza geo-server fallisce |
| **File system: librerie utente** | localStorage (`ridley:lib:*`) | filesystem `~/.ridley/libraries/*.clj` + `_index.json` + `_meta.json` + `_active.json` | [storage.cljs:160-178](src/ridley/library/storage.cljs#L160-L178) discrimina per `(env/desktop?)` |
| **File system: STL/3MF export** | `URL.createObjectURL` + `<a download>` (download in cartella browser) | dialog nativo (`/pick-save-path`) + `/write-file` (utente sceglie path completo) | [export/stl.cljs](src/ridley/export/stl.cljs) e [geo_server.rs:28](desktop/src-tauri/src/geo_server.rs#L28) |
| **File system: home dir** | n/a | `/home-dir` ([geo_server.rs:13](desktop/src-tauri/src/geo_server.rs#L13)) | usato per costruire `~/.ridley/libraries` |
| **File system: import file** | input `<file>` | letture dirette da `/read-file` per esempi/STL | sì funziona anche in browser via `<input type=file>`, ma il path-by-name lo fa solo desktop |

Come si vede, la mappa è sbilanciata: il "solo desktop" copre due capability (libfive e filesystem); il "solo webapp" è quasi vuoto. Questo è coerente con la decisione di mantenere il binding webview/CLJS unico e differenziare solo dove c'è bisogno di una capability di sistema.

---

## 4. Manifold e libfive

### Manifold WASM in desktop?

**Sì, esclusivamente**. Il binding Rust nativo a Manifold (di cui hai sentito parlare, basato su `manifold3d` 0.0.6 + TBB) è stato **rimosso** nei commit recenti:

- `f990f70 Remove native Manifold backend (HTTP geo-server endpoints)` (2026-04-23)
- `8ff98af Update Cargo.toml profile comment after native Manifold removal`

Conseguenze concrete misurabili nel codice attuale:

- [Cargo.toml](desktop/src-tauri/Cargo.toml#L9-L14) non ha più `manifold3d` come dipendenza.
- `desktop/src-tauri/src/manifold_ops.rs` non esiste più.
- Gli endpoint `/union`, `/difference`, `/intersection`, `/hull`, `/smooth`, `/refine` sono stati rimossi da `geo_server.rs`. Il match in [geo_server.rs:300-348](desktop/src-tauri/src/geo_server.rs#L300-L348) accetta solo `/sdf-mesh` e `/sdf-mesh-bin`.
- Il namespace `ridley.manifold.native` è stato cancellato. La cartella `src/ridley/manifold/` contiene solo `core.cljs`.
- I bindings DSL `native-union`, `native-difference`, `native-intersection`, `native-hull` non sono più registrati.
- L'esempio `examples/multiboard-native.clj`, citato nell'audit di backend, non esiste più.

Quindi: in desktop, **`mesh-union` (e tutta la famiglia CSG) chiamano il WASM in-process nella WebView**, esattamente come in browser. La motivazione è documentata in [manifold-backends-audit.md](dev-docs/manifold-backends-audit.md): WASM era 2.3x-6.5x più veloce del nativo Rust su tutte le taglie misurate, perché la serializzazione JSON XHR del round-trip costava più della computazione boolean stessa.

L'API DSL è invariata: l'utente scrive `(mesh-union a b c)` o equivalentemente `(register :u (mesh-union a b c))` e in entrambi gli ambienti il codice passa per Manifold WASM.

C'è uno strascico di debito: lo script [bundle-dylibs.sh:18-27](desktop/bundle-dylibs.sh#L18-L27) cerca ancora `libmanifold.3.dylib` e `libmanifoldc.3.dylib` dalla cartella `manifold3d-sys-*/out/lib`, che ora non esiste. In esecuzione stampa un warning ma non errore. Da pulire.

### libfive in desktop: stato

**Sì, integrato e funzionante.** L'integrazione è visibile in due punti:

1. **Build**: [build.rs:21-67](desktop/src-tauri/build.rs#L21-L67) compila libfive da `vendor/libfive/` con CMake (`-DCMAKE_BUILD_TYPE=Release -DBUILD_STUDIO_APP=OFF -DBUILD_GUILE_BINDINGS=OFF -DBUILD_PYTHON_BINDINGS=OFF`), linkando dynamic `libfive` + `libfive-stdlib`. Il primo build è lento (compila tutto C++); successivi sono incrementali.

2. **FFI**: [sdf_ops.rs:43-79](desktop/src-tauri/src/sdf_ops.rs#L43-L79) dichiara manualmente le firme C delle funzioni che usa (non c'è bindgen automatico). Chiamate principali: `libfive_tree_const`, `libfive_tree_x/y/z`, `libfive_tree_unary/binary`, `libfive_tree_render_mesh`, e dalle stdlib `sphere`, `box_exact`, `cylinder_z`, `rounded_box`, le CSG `_union/difference/intersection`, le SDF-only `shell/offset/blend_expt_unit/morph`, le trasformazioni `move/rotate_x|y|z/scale_xyz`.

### Quali primitive del DSL passano per libfive

Tutte e sole quelle del namespace `ridley.sdf.core` (binding nel DSL con prefisso `sdf-`):

- **Costruttori 3D**: `sdf-sphere`, `sdf-box`, `sdf-cyl`, `sdf-rounded-box`. Tutti finiscono in `build_tree` ([sdf_ops.rs:198-221](desktop/src-tauri/src/sdf_ops.rs#L198-L221)).
- **Combinatori**: `sdf-union`, `sdf-difference`, `sdf-intersection`, `sdf-blend`, `sdf-shell`, `sdf-offset`, `sdf-morph`, `sdf-displace`.
- **Trasformazioni**: `sdf-move`, `sdf-rotate`, `sdf-scale`, `sdf-revolve` (revolve mappa `x → sqrt(x²+y²)`, `y → z` via `libfive_tree_remap`, vedi [sdf_ops.rs:272-288](desktop/src-tauri/src/sdf_ops.rs#L272-L288)).
- **Pattern TPMS e altri**: `sdf-gyroid`, `sdf-schwarz-p`, `sdf-diamond`, `sdf-slats`, `sdf-bars`, `sdf-bar-cage`, `sdf-grid`. Questi non sono nodi atomici lato Rust: sono **costruiti in CLJS** combinando `sdf-formula` con espressioni `x y z` e finiscono in nodi `var`/`const`/`unary`/`binary` ricostruiti dall'interprete su [sdf_ops.rs:253-271](desktop/src-tauri/src/sdf_ops.rs#L253-L271).
- **Compilatore di formule**: `sdf-formula` accetta una forma Clojure (`x y z r rho theta phi` + un set di funzioni) e produce un tree di nodi `unary/binary/var/const`.

Il punto di materializzazione è `sdf-ensure-mesh` ([sdf/core.cljs](src/ridley/sdf/core.cljs#L11-L20)): viene chiamato dalla macro `register` se vede un nodo SDF (`{:op string}`), prima di registrare la mesh.

### Lazy: rappresentazione "uncommitted"?

Sì, lato SDF è effettivo. I costruttori `sdf-*` ritornano dati puri:

```clojure
(sdf-sphere 10)    ; => {:op "sphere" :r 10}
(sdf-union a b)    ; => {:op "union" :a a :b b}
```

Nessuna chiamata al server, nessuna mesh prodotta finché l'utente non chiede esplicitamente `(sdf->mesh node)` o non passa il nodo a `register`/`save-stl`/`mesh-union`/eccetera, dove `sdf-ensure-mesh` lo intercetta e materializza.

Lato **mesh** invece la rappresentazione è sempre **eager**: `(box 10 10 10)` produce immediatamente vertices/faces in Clojure, perché il sistema turtle deve essere in grado di chiedere `(mesh-bounds m)` o `(face-at m :top)` senza un round-trip al server.

Quindi la lazy evaluation che hai in mente esiste, ma solo nel mondo SDF. Se vuoi un punto unitario di vista in cui "primitive uncommitted" siano l'unica rappresentazione, non ci siamo: è un hybrid.

---

## 5. Lifecycle e wiring

### Inizializzazione del backend

Il geo-server **parte prima del frontend** ed è non-bloccante per il chiamante:

```rust
// main.rs:62
geo_server::start();  // Avvia thread::spawn dentro Server::http(...)
```

Il thread del server è completamente staccato dal main thread Tauri. Il `geo_server::start()` ritorna subito dopo aver fatto `thread::spawn`. Quindi al momento in cui Tauri costruisce la WebView, il server è (probabilmente) già in ascolto, ma non c'è una condition variable o handshake che lo confermi.

In dev, prima di aprire la webview, c'è un sync **separato** sulla porta 9000 del frontend: [main.rs:64-77](desktop/src-tauri/src/main.rs#L64-L77) fa polling con `wait_for_port(9000, 30s)`. Questo non riguarda il backend Rust ma il dev server CLJS.

### Reazione del frontend a un errore del backend

**Nessuna strategia di reconnect/restart.** Il pattern in `sdf/core.cljs` e `library/storage.cljs` è:

```clojure
(if (= 200 (.-status xhr))
  (... parse ...)
  (throw (js/Error. ...)))
```

Errori di rete (server giù) si propagano come eccezione SCI, intercettata dal REPL e mostrata come errore sullo script. Niente fallback, niente health check, niente UI che dica "il geo-server non risponde, riavvia l'app".

In webapp pura (geo-server non c'è proprio), `(env/desktop?)` ritorna false e i percorsi alternativi sono attivi (localStorage per librerie, `<a download>` per export). Ma se chiami `(sdf-sphere 10)` seguito da `register`, scatena XHR a `127.0.0.1:12321` che il browser rifiuta o lascia in timeout. **Non c'è una guardia che dica "in webapp gli SDF non funzionano, usa mesh primitive"**. Il binding esiste comunque nel DSL, fallisce a runtime al primo materialize. Da segnalare come UX gap.

### File system: dove vive il codice

Le primitive di I/O sono raggruppate in [storage.cljs](src/ridley/library/storage.cljs):

- **Filesystem mode** (desktop): wrapper `fs-write-text!`, `fs-read-text`, `fs-read-json`, `fs-write-json!`, `fs-delete!` che usano XHR sincrono ai 5 endpoint:
  - `/home-dir` per scoprire `$HOME` o `$USERPROFILE`.
  - `/write-file` con header `X-File-Path` e body raw bytes.
  - `/read-file` con header `X-File-Path`, ritorna bytes.
  - `/delete-file` con header `X-File-Path`.
  - `/read-dir` (crea la directory se non esiste, vedi [geo_server.rs:108-110](desktop/src-tauri/src/geo_server.rs#L108-L110), comportamento da segnalare).
- **localStorage mode** (webapp): chiavi `ridley:lib:*`, `ridley:libs:index`, `ridley:libs:active`.

Il binding alla scelta è una tripletta di funzioni pubbliche (`list-libraries`, `save-library!`, `delete-library!`, ...) che internamente fanno `(if (env/desktop?) ... ...)`.

### Raccordo con cap. 8 (stato condiviso) e cap. 11 (sottosistemi)

- **Librerie attive** ([core.cljs in library](src/ridley/library/core.cljs), non riletto qui ma noto dal recon): topo-sort dei `:requires` e eval in SCI come namespace separati. Il sistema di librerie consuma `storage.cljs` come pure data layer; non sa nulla del filesystem o localStorage.
- **Settings**: vivono ancora in localStorage **anche in desktop** (vedi [settings.cljs](src/ridley/settings.cljs) per riferimento). Non sono migrati al filesystem. Da segnalare come asimmetria, eventualmente debito per cap. 14.
- **Active set / metadati delle librerie**: il filesystem mode tiene `_index.json`, `_meta.json`, `_active.json` paralleli ai `.clj`. Il localStorage mode tiene tutto in chiavi separate. Le due rappresentazioni divergono leggermente: localStorage non distingue created/modified salvato dal salvataggio in atto, filesystem sì.

---

## 6. Tre cose che potrebbero sorprendere chi conosce solo la versione webapp

### 6.1 Il confine non passa per Tauri IPC: passa per HTTP localhost

Tauri offre un IPC nativo (Rust ↔ JS via `invoke`/`emit`). Ridley non lo usa. Il confine è un **HTTP server locale su 127.0.0.1:12321** colloquiato via XMLHttpRequest sincrono. La ragione è il contratto sincrono del DSL, indagato in dettaglio in [transport-audit.md](dev-docs/transport-audit.md): tutti i canali alternativi (`fetch`, Tauri `invoke`, custom URI scheme) sono async, e adattarli al DSL sincrono richiederebbe SharedArrayBuffer + Worker con complicazioni COOP/COEP non sostenibili al momento.

Conseguenze pratiche: la porta 12321 deve essere libera (collisioni con altre app sono possibili); CORS è aperto a `*` ([geo_server.rs:177](desktop/src-tauri/src/geo_server.rs#L177)) il che è ragionevole perché localhost ma è una superficie da tenere a mente; il CSP in [tauri.conf.json:13](desktop/src-tauri/tauri.conf.json#L13) include esplicitamente `http://127.0.0.1:12321` nei `connect-src`, altrimenti la WebView bloccherebbe le XHR. Nel CSP c'è anche `http://127.0.0.1:12322` come allowance: residuo del vecchio sidecar JVM, oggi nessuno ascolta su quella porta. Da pulire.

### 6.2 Il backend Rust è dimagrito molto: oggi è quasi solo file I/O più SDF

L'idea che ridley-desktop sia "una versione di Ridley con un motore CSG nativo veloce" non corrisponde più al codice. La storia è:

- ~2026-03: viene aggiunto un backend Rust Manifold (TBB) parallelo al WASM, con bindings CLJS `native-*`.
- 2026-04-23 (audit `manifold-backends-audit.md`): misurato che WASM è 2-6x più veloce del nativo a tutte le taglie, perché il roundtrip JSON XHR domina. Conclusione: la complessità di mantenere due backend non si ripaga.
- 2026-04-23 (commit `f990f70`): rimosso `manifold_ops.rs`, gli endpoint `/union /difference /intersection /hull /smooth /refine`, i bindings CLJS `native-*`, il file `manifold/native.cljs`.

Oggi il backend Rust ha **solo due responsabilità**:

1. **SDF meshing** (`/sdf-mesh`, `/sdf-mesh-bin`) tramite libfive. Questa è una capability strettamente desktop perché libfive non ha port WASM realistico.
2. **File I/O** (`/home-dir`, `/read-file`, `/read-dir`, `/write-file`, `/delete-file`, `/pick-save-path`). Capability di sistema operativo, non disponibili da browser.

Tutto il resto della pipeline geometrica vive nella WebView, identica al deploy webapp. Vale la pena capovolgere il modo di descrivere il desktop: non è "il browser più un motore CSG"; è "il browser più SDF e filesystem".

### 6.3 Lo `:app` di shadow-cljs è un singolo bundle e la differenza è una sola variabile globale

Non ci sono `:webapp` e `:desktop` come build target separati. Non ci sono macro-condizionali tipo `#?(:browser ... :tauri ...)`. Non c'è nemmeno feature detection complessa: il discriminante runtime è esattamente `(.-RIDLEY_ENV js/window)`, una stringa iniettata da Tauri prima del primo JS della pagina.

Implicazione 1: **il bundle distribuito su GitHub Pages è bit-identico a quello distribuito nel `.app`**. La differenza emerge solo perché Tauri inietta il global. Questo semplifica la pipeline (un solo `npm run release`), ma significa anche che bug specifici della webapp possono manifestarsi solo nella WebView (vedi memoria su WKWebView e `js/prompt`) e viceversa.

Implicazione 2: **tutto il codice CLJS ha sempre tutte le primitive nel namespace**. `(sdf-sphere 10)` è chiamabile in browser. Costruirà il nodo SDF-data (è puro), e fallirà solo a `materialize` perché la XHR a 12321 non risponde. Il codice utente che vuole essere portabile deve guardarsi in `(env/desktop?)` prima di usare SDF, dialog di salvataggio, o features filesystem-specific. Non c'è guardrail automatico.

---

Fine fase 1.
