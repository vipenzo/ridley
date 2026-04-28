# Ridley - Architecture Reconnaissance

Documento fattuale di ricognizione dello stato del codice al 2026-04-23.
Materiale di riferimento per la riscrittura di Architecture.md.

---

## 1. Inventario moduli

### 1.1 Webapp ClojureScript - `src/ridley/`

```
src/ridley/
├── core.cljs                   # Entry point. UI wiring, CodeMirror, sync setup, Manifold init, voice init
├── api.cljs                    # Public API export per target :core (node-library ridley-core.js)
├── schema.cljs                 # Dev-time asserts per mesh/path/shape shapes
├── math.cljs                   # v+/v-/v*/dot/cross/normalize, rotazione attorno ad asse
├── version.cljs                # goog-define VERSION
├── settings.cljs               # LLM settings + localStorage persistence
├── audio.cljs                  # Web Audio beep feedback su eval
│
├── editor/
│   ├── state.cljs              # turtle-state-var (SCI dynamic), print-buffer, scene-accumulator, sci-ctx-ref
│   ├── repl.cljs               # SCI context init, evaluate-definitions / evaluate-repl
│   ├── bindings.cljs           # base-bindings: tutte le funzioni SCI-visible
│   ├── macros.cljs             # macro-defs: stringa di macro (register, attach, extrude, loft, turtle, ...)
│   ├── implicit.cljs           # implicit-* fn: mutano turtle atom (f, th, tv, rt, u, goto, stamp...)
│   ├── impl.cljs               # *-impl runtime: extrude-impl, loft-impl, attach-impl, warp, chamfer, fillet
│   ├── operations.cljs         # Pure extrude/revolve/loft/bloft (no side effect)
│   ├── text_ops.cljs           # extrude-text, text-on-path
│   ├── codemirror.cljs         # CodeMirror 6 + paredit, AI block helpers, tweak slider UI
│   ├── pilot_mode.cljs         # (pilot :name) modalita' interattiva da tastiera
│   └── test_mode.cljs          # (tweak ...) slider interattivi su literal numerici
│
├── turtle/
│   ├── core.cljs               # make-turtle, movimenti f/th/tv/tr, stamp, facade su modules sotto
│   ├── extrusion.cljs          # Mesh building, path analysis, sweep, corner meshing
│   ├── loft.cljs               # stamp-loft, finalize-loft, bloft (bezier-safe loft)
│   ├── attachment.cljs         # attach, attach-face, inset, scale, clone-mesh
│   ├── shape.cljs              # rect, ngon, star, poly-path, fillet-shape, chamfer-shape, bounds-2d
│   ├── shape_fn.cljs           # shape-fn (f di t), tapered/twisted/rugged/woven/heightmap/shell, noise/fbm
│   ├── path.cljs               # path recording types
│   ├── bezier.cljs             # cubic/quadratic bezier sampling, tangenti, auto control points
│   ├── transform.cljs          # scale-shape, rotate, morph, resample
│   └── text.cljs               # opentype.js -> 2D shapes
│
├── geometry/
│   ├── primitives.cljs         # box/sphere/cyl/cone mesh builders (due API: turtle e puro)
│   ├── operations.cljs         # extrude/revolve/loft/sweep puri
│   ├── faces.cljs              # Face groups, find-faces, face-at, auto-face-groups, chamfer-edges
│   ├── warp.cljs               # warp, inflate, dent, attract, twist, squash, roughen
│   └── mesh_utils.cljs         # merge-vertices, mesh-simplify, mesh-laplacian, mesh-diagnose
│
├── clipper/
│   └── core.cljs               # Wrapper clipper2-js: shape-union/difference/intersection/xor/offset/hull/bridge
│
├── manifold/
│   ├── core.cljs               # Manifold WASM init, ridley<->manifold mesh conv, union/difference/hull, slice-at-plane, mesh-smooth, mesh-refine, CrossSection
│   └── native.cljs             # native-union/difference/intersection/hull via XHR sync a localhost:12321
│
├── sdf/
│   └── core.cljs               # sdf-node constructors, compile-expr, auto-bounds, TPMS, materialize via XHR sync (Rust libfive)
│
├── scene/
│   ├── registry.cljs           # scene-meshes/lines/stamps/paths/panels/shapes/values + refresh-viewport!
│   └── panel.cljs              # Struttura dati panel (text billboard 3D)
│
├── viewport/
│   ├── core.cljs               # Three.js setup, render loop, grid/assi, turtle indicator, picking (Alt+Click + flood-fill face), OrbitControls, highlighting
│   ├── xr.cljs                 # WebXR VR/AR: enable-xr, setup-controller, camera-rig, VR panel buttons
│   └── capture.cljs            # Offscreen render per AI describe / PNG export (WebGLRenderTarget)
│
├── anim/
│   ├── core.cljs               # anim-registry atom, register-animation!/register-procedural-animation!, play/pause/stop/seek, collision-registry, link-registry
│   ├── preprocess.cljs         # Spans + turtle commands -> per-frame pose vector
│   ├── playback.cljs           # tick-animations!: chiamato dal render loop; applica rigid xform / procedural mesh
│   └── easing.cljs             # linear/in/out/in-out/bounce/elastic
│
├── sync/
│   └── peer.cljs               # PeerJS WebRTC: host-session/join-session, send-script, send-repl-command, QR code
│
├── export/
│   ├── stl.cljs                # STL binary export, download via Blob o filesystem (pick-save-path)
│   └── threemf.cljs            # 3MF export (ZIP+XML)
│
├── library/
│   ├── core.cljs               # load-active-libraries: topo-sort + eval in SCI, produce :namespaces map
│   ├── storage.cljs            # Dual backend: geo-server /home-dir /read-file /write-file vs. localStorage (auto-detect via /ping)
│   ├── panel.cljs              # UI pannello libreria (modal dialog HTML, non prompt nativo per WKWebView)
│   ├── stl.cljs                # STL import (decode base64 -> mesh)
│   └── svg.cljs                # SVG parsing -> 2D shapes (DOM parser)
│
├── measure/
│   └── core.cljs               # distance, ruler, bounds, area; supporta vettori, mesh kw, face-id
│
├── voronoi/
│   └── core.cljs               # d3-delaunay -> voronoi-shell (2D), PRNG deterministico
│
├── manual/
│   ├── core.cljs               # Manuale interattivo: stato, navigation
│   ├── components.cljs         # Rendering pannello manuale
│   ├── content.cljs            # Contenuto (IT/EN) hardcoded
│   └── export.cljs             # Genera Manual_en.md / Manuale_it.md con screenshot
│
├── voice/
│   ├── core.cljs               # Orchestratore STT -> parser -> handlers
│   ├── state.cljs              # voice-enabled?, voice-state atom
│   ├── speech.cljs             # Web Speech API, push-to-talk e continuous
│   ├── parser.cljs             # Deterministic pattern matcher, fillers strip
│   ├── panel.cljs              # Pannello stato voce
│   ├── i18n.cljs               # Tabelle comandi IT/EN
│   ├── modes/structure.cljs    # Paredit-style navigation
│   └── help/                   # core.cljs, db.cljs, ui.cljs: sistema help con fuzzy search
│
├── ai/
│   ├── core.cljs               # generate: chiama provider LLM, ritorna {:type :code|:clarification, :code, :question}
│   ├── prompts.cljs            # System prompts tier-1/2/3
│   ├── prompt_store.cljs       # Custom prompts in localStorage (provider/model cascade)
│   ├── history.cljs            # Parsing di step-block commenti nel source
│   ├── rag.cljs                # Keyword RAG su Spec.md (tier-3)
│   ├── rag_chunks.cljs         # Chunks pre-compilati (script/gen_rag_chunks.bb)
│   ├── capture_directives.cljs # Parse [view: front] [slice: z=30] dal prompt
│   ├── batch.cljs              # /ai-batch: esegue N prompt, cattura screenshot, zip
│   ├── auto_session.cljs       # /ai-auto: iterazione con visual feedback
│   ├── describe.cljs           # Multimodal: cattura view, chiama vision API
│   └── describe_session.cljs   # (describe)/(ai-ask)/(end-describe)/(cancel-ai)
│
└── ui/
    └── prompt_panel.cljs       # Pannello prompt AI
```

Nota: `src/dev/repl.clj` e' una utility JVM per nREPL dev, non codice runtime.

### 1.2 Desktop shell - `desktop/src-tauri/`

```
desktop/
├── build.sh                    # Build release Tauri
├── bundle-dylibs.sh            # Bundle libfive.dylib (macOS)
├── dev.sh                      # Dev: cargo tauri dev
└── src-tauri/
    ├── Cargo.toml              # tauri 2, manifold3d 0.0.6, tiny_http 0.12, rfd 0.15, serde
    ├── build.rs                # tauri-build; link libfive.dylib
    ├── tauri.conf.json         # frontendDist=../../public, CSP permissiva, port 12321 in connect-src
    ├── capabilities/default.json
    ├── vendor/libfive/         # Source vendorizzato libfive (cmake tree: libfive, studio, ...)
    └── src/
        ├── main.rs             # Entry: avvia geo_server, spawn `npm run dev` in debug, tauri::Builder con invoke_handler
        ├── geo_server.rs       # HTTP server su 127.0.0.1:12321 (tiny_http), CORS *
        ├── manifold_ops.rs     # FFI manifold3d::sys MeshGL64 (f64/u64) - union/difference/intersection/hull/smooth/refine
        └── sdf_ops.rs          # FFI libfive C API: SdfNode enum (serde) -> libfive tree -> render_mesh
```

---

## 2. Stack tecnologico

### 2.1 Webapp (dipendenze da `package.json` + `deps.edn`)

| Tecnologia | Versione | Ruolo |
|---|---|---|
| ClojureScript | 1.11.132 | Linguaggio webapp |
| shadow-cljs | 2.28.5 | Build tool |
| SCI | 0.8.43 | Interpreter embedded per DSL utente |
| thi.ng/geom | 1.0.1 | (dichiarata, uso non verificato in moduli chiave) |
| three | 0.160.0 | Rendering 3D |
| manifold-3d | 3.0.0 | WASM Manifold (CSG booleans webapp) |
| clipper2-js | 1.2.4 | CSG 2D shapes |
| earcut | 3.0.2 | Triangolazione poligoni 2D |
| libtess | 1.2.2 | Tessellation alternativa |
| d3-delaunay | 6.0.4 | Voronoi diagrams |
| opentype.js | 1.3.4 | Font parsing per text-shape |
| peerjs | 1.5.5 | WebRTC wrapper per sync |
| qrcode | 1.5.4 | QR share URL |
| jszip | 3.10.1 | 3MF + /ai-batch export |
| @codemirror/* | 6.x | Editor |
| @nextjournal/clojure-mode | 0.3.3 | Parinfer/paredit |

### 2.2 Desktop (`Cargo.toml`)

| Tecnologia | Versione | Ruolo |
|---|---|---|
| tauri | 2 | Webview shell + IPC |
| manifold3d | 0.0.6 | CSG nativo (via FFI a manifold3d::sys) |
| libfive | vendored (CMake build) | SDF meshing, vendor/libfive |
| tiny_http | 0.12 | HTTP server locale 12321 |
| rfd | 0.15 | Native file dialog (save path) |
| serde / serde_json | 1 | JSON (request/response geo-server) |

### 2.3 Condivise webapp/desktop

- Tutta la webapp ClojureScript e' condivisa. Il binding desktop/webapp non passa per branch di build ma per feature detection runtime (vedi sez. 6).

### 2.4 Build targets (`shadow-cljs.edn`)

- `:app` - browser, `public/js/`, `:simple` optimizations per compat con Manifold WASM / Three.js
- `:release` - legacy GitHub Pages build (`docs/js/`), NON usato
- `:test` - node-test autorun
- `:core` - node-library `out/ridley-core.js` (ridley.api/exports), valida browser-independence

---

## 3. Sottosistemi principali

### 3.1 SCI runtime e DSL bindings
Cuore dell'esecuzione. Context SCI persistente in `editor/repl.cljs` (atom `sci-ctx`), inizializzato con `base-bindings` da `editor/bindings.cljs` e `macro-defs` da `editor/macros.cljs`. `evaluate-definitions` resetta contesto e turtle; `evaluate-repl` preserva pose ma azzera geometry transient. Re-eval del contesto e' quasi totale: ogni `/Run` costruisce un nuovo SCI ctx, ricarica librerie attive e ri-evalua le macro.

### 3.2 Turtle engine
Stato immutabile: `{:position :heading :up :pen-mode :sweep-rings :geometry :meshes :stamps :anchors :attached :resolution :material :preserve-up :reference-up}`. Definito in `turtle/core.cljs` con facade su extrusion/loft/attachment/bezier. Mutazione solo via atom tramite le `implicit-*` in `editor/implicit.cljs`. La macro `(turtle ...)` rebinda la SCI dynamic var `state/turtle-state-var` a un nuovo atom (scoping).

### 3.3 Shape system 2D + Clipper2
`turtle/shape.cljs` definisce `{:type :shape :points [[x y]...] :centered? bool :holes [...]}`. `clipper/core.cljs` incapsula `clipper2-js` per booleane 2D + offset. `turtle/shape_fn.cljs` aggiunge shape-fn (funzione di t con meta `:shape-fn`) per profili variabili lungo extrusion.

### 3.4 Mesh engine (extrude / loft / bloft / revolve)
Pipeline: path turtle -> analisi corner -> rings sweep -> triangolazione caps (earcut). Vive in `turtle/extrusion.cljs` + `turtle/loft.cljs` + `geometry/operations.cljs`. Le macro `extrude/loft/bloft/revolve` in `editor/macros.cljs` sono thin wrapper che costruiscono un path e delegano a `*-impl` in `editor/impl.cljs`.

### 3.5 Manifold integration
Due backend paralleli:
- `manifold/core.cljs` - WASM `manifold-3d` v3.0 caricato da CDN in `index.html` come ES module globale `window.ManifoldModule`. `init!` attende il global con polling, chiama `.setup()`, cache in atom privato.
- `manifold/native.cljs` - XHR sincroni a `localhost:12321/union|/difference|/intersection|/hull`.
Entrambi esposti al DSL come `mesh-union/difference/...` (WASM) e `native-union/...` (Rust). Il codice utente puo' usare entrambi esplicitamente.

### 3.6 libfive / SDF subsystem
Nuovo. Tree SDF come dati puri (map con `:op`) costruiti in `sdf/core.cljs`. `compile-expr` converte forme Clojure (`x`, `y`, `z`, `r`, `rho`, `theta`, `phi`, operatori) in nodi tree. `materialize` / `ensure-mesh` serializzano a JSON e chiamano endpoint `/sdf-mesh` del geo-server (libfive C API dietro). Resolution adattiva con voxel-budget e boost su thin features. Solo-desktop (richiede Rust server).

### 3.7 Scene registry
`scene/registry.cljs` contiene 7 atom paralleli: `scene-meshes`, `scene-lines`, `scene-stamps`, `scene-paths`, `scene-panels`, `scene-shapes`, `scene-values` + contatore `mesh-id-counter` + JS `Set` `claimed-meshes`. `refresh-viewport!` aggrega visible meshes + lines + stamps + panels e invia a viewport. `clear-all!` e' chiamato a ogni re-eval.

### 3.8 Viewport (Three.js)
`viewport/core.cljs` - singolo atom `state` con `{:scene :camera :renderer :controls :world-group :highlight-group :grid :axes}`. Render loop via `setAnimationLoop` (XR-compat) chiama `anim-playback/tick-animations!` ogni frame. Gestisce picking (Alt+Click raycaster, face-group resolution con fallback flood-fill coplanare), axis-constrained rotation (X/Y/Z + drag), ruler (Shift+Click), turtle indicator, adaptive grid.

### 3.9 Library / namespace system
`library/storage.cljs` - dual backend auto-rilevato via `/ping` a 12321: filesystem in `~/.ridley/libraries/*.clj` + `_index.json` + `_meta.json` + `_active.json`; o localStorage (`ridley:lib:*`, `ridley:libs:index`, `ridley:libs:active`). `library/core.cljs` fa topo-sort delle librerie attive, le evalua in SCI come namespaces separati e ritorna mappa `{ns-sym {sym value}}` passata a `sci/init :namespaces`. Warnings persistiti in atom `load-warnings`.

### 3.10 Animation system
`anim/core.cljs` - `anim-registry` (mappa name -> anim-data), `link-registry` (child -> parent), `collision-registry`. Due tipi: `:preprocessed` (spans pre-computati a fps) e `:procedural` (gen-fn per frame). `anim/playback.cljs` integra nel render loop viewport: applica rigid xform O(1) via `Object3D.position/quaternion` per preprocessed, rimpiazza geometry per procedural. Callbacks (get-mesh-fn, register-mesh-fn, update-geometry-fn, set-rigid-transform-fn, camera-pose-fn, refresh-fn, async-output-fn) passati da `core.cljs` per rompere circolarita'.

### 3.11 WebRTC sync (desktop <-> headset)
`sync/peer.cljs` - PeerJS via CDN. Host genera short-code `ridley-XXXXXX` (6 char alfanumerici, no I/O/0/1), serve QR code con `?peer=<id>`. Messaggi host->client: `{:type "script-update" :definitions}` e `{:type "repl-command" :command}`. Messaggi client->host: `{:type "script-ack"}`, `{:type "pong"}`. Host multi-client (set di connections), client single-connection.

### 3.12 Desktop shell (Tauri)
- ClojureScript frontend: identico al webapp. `frontendDist` in `tauri.conf.json` punta a `../../public`, stessi file.
- Rust backend: 3 file, due superfici di API.
  1. `tauri::invoke_handler` in main.rs: `ping`, `manifold_union`, `manifold_difference`, `manifold_intersection`, `manifold_hull`. VEDI sez. 8 (non risultano chiamati dalla webapp).
  2. HTTP server `tiny_http` su 12321 in `geo_server.rs`: e' la vera superficie usata dalla webapp (XHR sync).
- In dev-mode main.rs spawna `npm run dev` e aspetta port 9000 prima di aprire la webview.

---

## 4. Protocollo inter-sottosistema

### 4.1 SCI -> Turtle engine

```
Entry point:   implicit-* fn bound in base-bindings (editor/implicit.cljs)
Input:         args primitivi (distanza, angolo, shape-data, ecc.)
Output:        nil o valore di lookup (turtle-position, last-mesh)
Effetti:       swap! su @state/turtle-state-var (SCI dynamic var -> atom)
Direzione:     sincrono, push-based (utente chiama, atom muta)
Invarianti:    @turtle-state-var deve essere un atom; @@turtle-state-var deve
               essere una turtle map (make-turtle shape)
```

Nota: le funzioni "pure" (`turtle-f`, `turtle-th`, ecc.) sono anche esposte per threading esplicito senza mutazione, ma la via normale del DSL e' la mutation via atom.

### 4.2 Turtle engine -> Mesh engine

```
Entry point:   (extrude shape & body) macro -> (path body) -> extrude-impl
               (loft/bloft/revolve analoghi; attach-face -> attach-face-impl)
Input:         path data (sequenza di :f/:th/:tv/:rt/:u/...), shape data,
               opzioni (resolution, joint-mode)
Output:        mesh map {:type :mesh :vertices :faces :creation-pose :material
                         :source-history :face-groups?}
Effetti:       nessuna mutazione della turtle globale (le macro usano
               `turtle` o recorder locali); la mesh e' restituita, NON
               registrata automaticamente
Direzione:     sincrono
Invarianti:    :vertices vettore di [x y z], :faces vettore di [i j k];
               per register visibile, il chiamante deve fare (register name mesh)
```

### 4.3 Mesh engine -> Manifold (WASM)

```
Entry point:   manifold/core.cljs {union, difference, intersection, hull,
                                   mesh-smooth, mesh-refine, solidify,
                                   slice-at-plane, extrude-cross-section}
Input:         1+ mesh ridley (map con :vertices/:faces)
Output:        mesh ridley con ::raw-arrays (Float32Array/Uint32Array) cachata
               per zero-copy verso Three.js
Effetti:       new Manifold(...) + .delete() immediato post-op; Manifold e'
               un JS object C++-bound con lifecycle esplicito
Direzione:     sincrono (ma richiede init! asincrono precedente)
Invarianti:    (manifold/initialized?) deve essere true, altrimenti no-op;
               le ops restituiscono nil se mesh non manifold
```

### 4.4 Mesh engine -> Manifold (native Rust)

```
Entry point:   manifold/native.cljs {native-union, native-difference,
                                     native-intersection, native-hull}
Input:         1+ mesh ridley
Output:        mesh ridley (via deserializzazione JSON)
Effetti:       XHR sincrono POST http://127.0.0.1:12321/{union,difference,...}
               con body JSON {vertices, faces}[] o {base, cutters}
Direzione:     sincrono (XHR async=false), blocca thread UI
Invarianti:    geo-server attivo (solo desktop); nessun controllo esplicito,
               errore di rete rilancia come js/Error
```

### 4.5 Mesh engine -> Scene registry

```
Entry point:   registry/add-mesh!   (anonymous)
               registry/register-mesh!  (named, replace se esiste)
               registry/register-path!  (named path, no visibility)
               registry/register-shape! (named shape, no visibility)
               registry/register-panel! (named panel)
               registry/register-value! (raw, qualsiasi tipo)
Input:         mesh map + name kw opzionale
Output:        mesh con :registry-id aggiunto (counter monotono)
Effetti:       swap! su uno dei 7 atom di registry; schema/assert-mesh!
               a validation time
Direzione:     sincrono
Invarianti:    mesh deve passare assert-mesh!; register-mesh! con nome
               esistente replica in-place (stesso idx, :registry-id
               preservato via update-mesh-at-index!)
```

### 4.6 DSL -> Scene registry (via macro `register`)

```
Entry point:   (register name expr & opts)
Input:         name (simbolo), expr (valutato), opts (:hidden, ecc.)
Output:        valore definito (mesh, vector, panel, shape, path, altro)
Effetti:       (1) auto-materializza SDF node via sdf-ensure-mesh
               (2) register-value! per $-lookup (qualsiasi valore)
               (3) def symbol + register-mesh!/register-panel!/
                   register-shape!/register-path! secondo tipo
               (4) vector di mesh -> register-mesh! con suffix "/0", "/1", ...
               (5) map di mesh -> register-mesh! con suffix "/key"
               (6) map literal -> assembly mode (con asm-push!/asm-pop!,
                   frame con :prefix/:parent/:goto/:skeleton)
               (7) refresh-viewport! false al termine
Direzione:     sincrono (macro-expansion + eval)
Invarianti:    vector di mesh -> tutti gli elementi devono passare mesh?,
               altrimenti throw
```

### 4.7 Scene registry -> Viewport

```
Entry point:   registry/refresh-viewport! [reset-camera?]
Input:         legge @scene-meshes (visible only) + @scene-lines + @scene-stamps
               + (visible-panels)
Output:        nil
Effetti:       chiama viewport/update-scene con {:lines :stamps :meshes :panels
                                                 :reset-camera?}.
               Le mesh visible ricevono :registry-name iniettato per
               tagging in userData del Three.js mesh (serve a animazioni
               e preview).
Direzione:     sincrono push (registry dice al viewport cosa mostrare)
Invarianti:    viewport deve essere gia' inizializzato (init chiamato);
               altrimenti update-scene e' no-op (state atom nil)
```

### 4.8 SCI -> libfive / SDF

```
Entry point:   DSL: sdf-sphere, sdf-box, sdf-cyl, sdf-rounded-box, sdf-union,
               sdf-difference, sdf-intersection, sdf-blend, sdf-shell,
               sdf-offset, sdf-morph, sdf-displace, sdf-move, sdf-rotate,
               sdf-scale, sdf-revolve, sdf-formula, sdf-gyroid, sdf-schwarz-p,
               sdf-diamond, sdf-slats, sdf-bars, sdf-bar-cage, sdf-grid.
               Materializzazione: sdf->mesh, sdf-ensure-mesh.
Input:         per constructor: parametri numerici; per combinatori: altri
               sdf-node (mappe con :op string)
Output:        sdf-node (mappa {:op "..." :a :b ...}) - dato puro,
               non meshato finche' non richiesto
Effetti:       nessuno (costruzione pigra)
Direzione:     sincrono, lazy
Invarianti:    sdf-node? true solo per map con :op string;
               registry/macro register auto-materializza se vede SDF node
```

### 4.9 SDF subsystem -> Mesh engine

```
Entry point:   sdf/materialize [node bounds resolution]
               sdf/ensure-mesh [node] oppure [node reference-mesh]
Input:         sdf-node + bounds opzionali (auto-bounds se nil) + resolution
               opzionale (*sdf-resolution* dynamic, default 15)
Output:        mesh ridley con ::raw-arrays precalcolati da sdf/core
Effetti:       XHR sincrono POST /sdf-mesh con body {tree, bounds, resolution};
               warning se voxel-count > 5e8
Direzione:     sincrono, push
Invarianti:    geo-server attivo (solo desktop); il mesh ha creation-pose
               default (non eredita da nulla); chiama auto-bounds ricorsivo
               se bounds assenti
```

Punto di giunzione al resto del mesh world: l'output e' gia' in forma Ridley mesh, quindi puo' essere passato a `mesh-union` WASM, `mesh-smooth`, `register`, `save-stl` come qualsiasi altro mesh. La macro `register` intercetta sdf-node prima di salvare (`sdf-ensure-mesh` in macro body).

### 4.10 Animation system -> Scene registry + Viewport

```
Entry point:   anim-playback/tick-animations! (dt)
Input:         dt frame (secondi) dal render loop viewport
Output:        nil
Effetti:       per ogni anim `:playing`:
                 - preprocessed+rigid: set-rigid-transform-fn chiama
                   viewport/set-mesh-rigid-transform! (Object3D.position/
                   quaternion) O(1), poi register-mesh! con :creation-pose
                   aggiornato
                 - procedural: register-mesh! con vertices/faces nuovi +
                   update-geometry-fn (viewport/update-mesh-geometry!)
                 - camera: camera-pose-fn -> viewport/set-camera-pose!
                   (disabilita OrbitControls)
               Collisioni: on-collide callbacks invocati quando coppie
               di mesh entrano/escono da distanza-centroid threshold.
Direzione:     async push driven dal render loop (setAnimationLoop)
Invarianti:    i callback devono essere stati wired (core.cljs/init);
               se mesh "base" non esiste al play, catturato fresco dal
               registry al play!; se altra anim gia' play sullo stesso
               target, condivide :base-vertices/:base-pose
```

### 4.11 WebRTC sync

```
host -> client: {:type "script-update" :definitions string :timestamp n}
                {:type "repl-command" :command string :timestamp n}
                {:type "ping"}
client -> host: {:type "script-ack" :timestamp n}
                {:type "pong"}

Entry point (host): sync/send-script [definitions] -> broadcast a :connections
                    sync/send-repl-command [command]
Entry point (client): join-session -> on "data" handler -> callback
                      :on-script-received o :on-repl-received (set in
                      core.cljs/setup-sync a funzioni che reset editor
                      content o evaluate-repl)
Effetti: reset editor content, evaluate-definitions automatico, o
         evaluate-repl. Ack non ha side effect attivo.
Direzione: push asincrono via DataChannel
Invarianti: peer.js deve essere caricato (CDN in index.html); host
            puo' avere N client, client una sola connessione
```

### 4.12 ClojureScript frontend -> Rust backend

Due canali, asimmetrici:

#### 4.12a HTTP geo-server (canale principale)

```
Transport: XMLHttpRequest sincrono (async=false) a http://127.0.0.1:12321
Detection: storage/desktop-mode? fa POST /ping e cache'a il risultato

Endpoints:
  POST /ping                     -> {}             (solo status code)
  POST /home-dir                 -> {path:string}
  POST /pick-save-path           body {suggested_name}
                                 -> {path:string} | null (dialog cancel)
  POST /read-file   header X-File-Path -> bytes raw
  POST /write-file  header X-File-Path, body bytes -> {written:int}
  POST /delete-file header X-File-Path -> {deleted:true}
  POST /read-dir    body {path}  -> [{name, is_dir, size}, ...]
                                    (crea dir se non esiste!)
  POST /union       body [MeshData]           -> MeshData
  POST /difference  body {base, cutters}      -> MeshData
  POST /intersection body [MeshData]          -> MeshData
  POST /hull        body [MeshData]           -> MeshData
  POST /smooth      body {mesh, min_sharp_angle?, min_smoothness?, refine?}
                                              -> MeshData
  POST /refine      body {mesh, n}            -> MeshData
  POST /sdf-mesh    body {tree, bounds, resolution} -> MeshData (JSON)
  POST /sdf-mesh-bin body {tree, bounds, resolution} -> binary
                     (Format: [nv:u32 LE][nf:u32 LE][verts:nv*3 f32 LE]
                              [faces:nf*3 u32 LE])
                     NB: endpoint implementato, ma nessun chiamante CLJS
                     lo usa attualmente (sdf/core.cljs chiama /sdf-mesh).

MeshData:       {vertices: [[f64 f64 f64], ...],
                 faces:    [[u32 u32 u32], ...]}
Mesh -> Manifold conv: usa MeshGL64 (f64 verts, u64 indices) per evitare
                       artefatti di rounding f32.

Chiamanti:
  manifold/native.cljs       /union /difference /intersection /hull
  sdf/core.cljs              /sdf-mesh
  library/storage.cljs       /ping /home-dir /read-file /write-file
                             /delete-file
  export/stl.cljs            /pick-save-path /write-file
  (others)                   /read-dir ipotizzato per library; verificabile
                             in library/panel.cljs
```

#### 4.12b Tauri IPC (vestigiale)

```
tauri::command in main.rs: ping, manifold_union, manifold_difference,
                           manifold_intersection, manifold_hull.
Chiamanti: nessuno nel frontend CLJS (grep di __TAURI, invoke, tauri.invoke
           -> zero risultati). I4 invoke handlers sono registrati ma morti.
```

Anomalia segnalata in sez. 8.

---

## 5. Fotografia dello stato

### 5.1 Turtle

```
## turtle-state-var

Tipo:       SCI dynamic var contenente un atom
Vive in:    src/ridley/editor/state.cljs
Contiene:   @@turtle-state-var -> {:position :heading :up :pen-mode
                                   :stamped-shape :sweep-rings :geometry
                                   :meshes :stamps :anchors :attached
                                   :resolution :material :preserve-up
                                   :reference-up :pending-rotation
                                   :joint-mode}
Scritto da: implicit-* fn (editor/implicit.cljs), *-impl (editor/impl.cljs),
            operations, attachment, pilot-mode, test-mode, register macro
            (per reset pose a turtle scope start)
Letto da:   core.cljs (resolve-turtle-pose, update-turtle-indicator),
            editor/state.cljs (last-mesh, get-turtle-pose), binding symbols
            turtle-position/heading/up nel DSL
Lifecycle:  creato a load di state.cljs con (make-turtle);
            reset a ogni evaluate-definitions (state/reset-turtle!);
            la macro (turtle ...) SCI-bind'a a un NUOVO atom per lo scope
Note:       @turtle-state-var e' l'atom; @@turtle-state-var il valore;
            la doppia deref e' l'idiom in tutta la codebase
```

### 5.2 Source tracking dynamic vars

```
## eval-source-var, eval-text-var

Tipo:       SCI dynamic var (non atom)
Vive in:    src/ridley/editor/state.cljs
Contiene:   eval-source-var: :unknown | :definitions | :repl
            eval-text-var: nil | string
Scritto da: editor/repl.cljs via (sci/binding ...) durante evaluate-*
Letto da:   add-source (editor/bindings) per tracciare dove una mesh e'
            stata creata (:register {:op :register :as kw :line :col :source})
Lifecycle:  root-value :unknown; binding scope'd durante eval
Note:       usati per il system di source-history sulle mesh
```

### 5.3 Scene registry

```
## scene-meshes / scene-lines / scene-stamps / scene-paths / scene-panels
## / scene-shapes / scene-values / mesh-id-counter / claimed-meshes

Tipo:       atom (privati) + counter + JS Set
Vive in:    src/ridley/scene/registry.cljs
Contiene:   scene-meshes  vec di {:mesh :name :visible :source-form?}
            scene-lines   vec di line data
            scene-stamps  vec di stamp shapes per debug
            scene-paths   vec di {:path :name :visible}
            scene-panels  vec di {:panel :name :visible}
            scene-shapes  vec di {:shape :name}
            scene-values  map {kw -> any}   (raw register-value)
            mesh-id-counter  int monotono
            claimed-meshes   JS Set di mesh references
Scritto da: register macro, add-mesh!, register-*!, show/hide-*,
            update-mesh-by-ref!, set-source-form!, animation system
            (update-mesh-vertices!), definition-meshes!
Letto da:   refresh-viewport! (aggrega visible), get-mesh, registered-names,
            visible-meshes, measure, ai/describe
Lifecycle:  clear-all! a ogni evaluate-definitions;
            claimed-meshes azzerato dopo set-definition-meshes!
Note:       7 atom paralleli + 2 ausiliari: indicizzazione lineare,
            lookup O(n). I mesh hanno :registry-id (int) e opzionalmente
            :registry-name (kw); il primo e' iniettato da add-mesh!, il
            secondo e' aggiunto in refresh-viewport! per tagging Three.js.
```

### 5.4 Scene accumulator

```
## scene-accumulator / mark-anchors

Tipo:       atom
Vive in:    src/ridley/editor/state.cljs
Contiene:   scene-accumulator: {:lines [] :stamps []}
            mark-anchors: {kw -> {:position :heading :up}}
Scritto da: implicit-f (pen-mode :on -> aggiunge line segment),
            implicit-stamp, set-creation-pose (via marks)
Letto da:   repl/extract-render-data -> registry/set-lines!/set-stamps!
Lifecycle:  reset-scene-accumulator! a ogni evaluate-*
Note:       vive separato dal registry perche' le lines/stamps sono
            transient per-evaluation, mentre il registry persiste
            named entities
```

### 5.5 Viewport

```
## state (viewport)

Tipo:       atom
Vive in:    src/ridley/viewport/core.cljs
Contiene:   {:scene :camera :camera-rig :renderer :controls :world-group
             :highlight-group :grid :axes :canvas :resize-observer}
Scritto da: init (set once)
Letto da:   update-scene, render-frame, picking, ruler, capture, XR
Lifecycle:  creato in init, mai resettato
```

Altri atom di viewport (tutti `defonce ^:private` in `viewport/core.cljs`):

| atom | contenuto |
|---|---|
| `current-meshes` | ultimo `meshes` passato a update-scene (per export) |
| `current-stamps` | ultimo `stamps` (per toggle) |
| `grid-visible` / `axes-visible` / `lines-visible` | boolean toggle |
| `grid-level` | power-of-10 corrente (adaptive grid) |
| `turtle-visible` / `turtle-pose` / `turtle-source` | indicator state, source = `:global \| {:mesh kw} \| {:custom pose}` |
| `turtle-indicator` | THREE.Group |
| `lines-object` / `normals-object` / `stamps-object` | Three obj cache |
| `panel-objects` | `{name -> {:mesh :canvas :texture}}` |
| `axis-rotation-state` | `{:axis-key :drag-start :dragging}` |
| `preview-objects` | temporanei test-mode |
| `ruler-objects` | misurazioni |
| `measure-pending` | first-point di Shift+Click |
| `last-frame-time` | per dt |
| `saved-orbit-target` | per restore dopo camera anim |
| `on-pick-callback` | callback su selection change |

### 5.6 Animation

```
## anim-registry / link-registry / collision-registry

Tipo:       atom
Vive in:    src/ridley/anim/core.cljs
Contiene:   anim-registry: {name -> {:target :duration :fps :loop :spans
                                     :frames :total-frames :span-ranges
                                     :state :current-time :base-vertices
                                     :base-faces :base-pose :type
                                     (:preprocessed|:procedural)}}
            link-registry: {child-target -> parent-target}
            collision-registry: {sorted-pair-kw -> entry}
Scritto da: register-animation!, register-procedural-animation!, play!/
            pause!/stop!, link!/unlink!, on-collide/off-collide
Letto da:   anim/playback tick-animations!
Lifecycle:  clear-all! a ogni evaluate-definitions
```

### 5.7 WebRTC peer

```
## peer-state

Tipo:       atom
Vive in:    src/ridley/sync/peer.cljs
Contiene:   {:peer Peer :connections #{} :connection nil :role (:host|:client)
             :status (:disconnected|:waiting|:connecting|:connected|:error)
             :peer-id string :on-script-received fn :on-repl-received fn
             :on-status-change fn :on-clients-change fn :on-client-connected fn}
Scritto da: host-session, join-session, stop-hosting, setup-*-connection-handlers
Letto da:   send-script, send-repl-command, connected?, hosting?, status queries
Lifecycle:  init a load; reset da stop-hosting; persiste per durata sessione
```

### 5.8 Library system

```
## load-warnings / desktop-mode-cache / lib-dir-cache

Tipo:       atom
Vive in:    src/ridley/library/{core,storage}.cljs
Contiene:   load-warnings: vec di string (ultimo load)
            desktop-mode-cache: boolean o nil (auto-detect via /ping)
            lib-dir-cache: string (lib directory path)
Scritto da: load-active-libraries (warnings); desktop-mode? / lib-dir
            primo accesso
Letto da:   editor/repl.cljs (make-sci-ctx carica lib), library panel UI
Lifecycle:  load-warnings reset a ogni make-sci-ctx; cache persistono
```

### 5.9 Voice / AI / Settings / misc

```
## voice-state / voice-enabled?        voice/state.cljs
## settings / connection-status / ollama-status   settings.cljs
## handlers (voice)                     voice/core.cljs
## ai-history                           ai/core.cljs
## session (auto-session)               ai/auto_session.cljs
## pilot-state / skip-next-pilot        editor/pilot_mode.cljs
## test-state / skip-next-tweak         editor/test_mode.cljs
## interactive-mode                     editor/state.cljs
## sci-ctx (persistente SCI context)    editor/repl.cljs
## sci-ctx-ref                          editor/state.cljs (mirror di sci-ctx,
                                        per rompere circular dep verso test-mode)
## run-definitions-fn                   editor/state.cljs (callback set da core)
## get-editor-content                   editor/state.cljs
## command-history / history-index      core.cljs (REPL history UI)
## sync-mode / connected-host-id        core.cljs
```

### 5.10 Rust backend state

Essenzialmente stateless. Il geo-server gira su thread separato, ogni request e' indipendente. `manifold_ops` e `sdf_ops` non mantengono handle persistenti: Manifold objects creati per op e dropped (RAII via `ManifoldHandle`/`MeshGL64Handle`). libfive trees rebuilt ogni request da JSON.

---

## 6. Boundary webapp / desktop

### 6.1 Codice condiviso vs. specifico

- **Condiviso** (webapp e desktop): tutto `src/ridley/**`. Stesso build `:app` di shadow-cljs, stesso `public/js/main.js`.
- **Specifico desktop**:
  - `desktop/src-tauri/**` (Rust)
  - Nessun modulo CLJS e' "solo desktop" a livello di build. La distinzione e' runtime.
- **Specifico webapp**: niente che non sia anche presente in desktop (la webapp e' il contenuto della webview Tauri).

### 6.2 Adattamento runtime

Ridley NON usa feature flags o build target separati per webapp/desktop. La distinzione e' **capability detection via health check**:

- `library/storage.cljs/desktop-mode?` fa `XHR POST /ping` a 127.0.0.1:12321. Risultato cached in atom. Se 200 OK -> desktop mode, altrimenti webapp.
- Quando il server c'e': library su filesystem, `/pick-save-path` per dialog nativo, `native-*` per CSG Rust, `sdf-*` disponibile.
- Quando non c'e': library su localStorage, `save-stl` via `URL.createObjectURL + <a download>`, solo WASM Manifold, SDF non funziona (XHR fallisce).

Non c'e' check `window.__TAURI__`; il criterio di presenza e' solo la raggiungibilita' di 12321.

### 6.3 Ops native desktop vs. WASM webapp

| Operazione | Webapp | Desktop (geo-server) |
|---|---|---|
| CSG booleans (union/diff/intersect/hull) | WASM manifold-3d via `manifold/core.cljs` (binding `mesh-union`) | Rust manifold3d via `manifold/native.cljs` (binding `native-union`) |
| smooth / refine | WASM (`mesh-smooth`, `mesh-refine`) | endpoint `/smooth` `/refine` (ma nessun binding CLJS attuale sembra usarli; verificabile) |
| Slice, CrossSection extrude | WASM only | non esposto lato Rust |
| SDF meshing (libfive) | NON DISPONIBILE | Rust `/sdf-mesh` |
| File I/O (library persist, STL save) | localStorage / `<a download>` | `/read-file`, `/write-file`, `/delete-file`, `/pick-save-path` |
| Home dir detection | n/a | `/home-dir` |
| Clipper2 (2D boolean) | JS library condivisa | JS library (stesso bundle) |

Il DSL utente puo' mescolare tool: in desktop `native-union` e `mesh-union` sono entrambi disponibili. La macro `register` non sceglie automaticamente: l'utente decide.

### 6.4 Confine di processo

- **Webview (WKWebView su macOS)**: esegue il bundle CLJS. Event loop, Three.js, SCI, tutta la logica di editor e viewport. Comunica con Rust solo via HTTP a 12321 (mai Tauri IPC nel codice attuale).
- **Rust process (tauri app)**: esegue geo-server su thread separato + tauri runtime principale. In dev, spawna anche `npm run dev` come child process per il hot-reload.
- **Separazione**: il webview non puo' fallback su Rust se questi non risponde (fallisce con js/Error). Il Rust non chiama mai il webview.

CSP in `tauri.conf.json` include `http://127.0.0.1:12321` in `connect-src`, altrimenti XHR sarebbe bloccato.

---

## 7. SDF subsystem (dettaglio)

**Dove vive**
- Frontend CLJS: `src/ridley/sdf/core.cljs` (unico file)
- Backend Rust: `desktop/src-tauri/src/sdf_ops.rs` + vendor/libfive
- Endpoint: `POST /sdf-mesh` in `geo_server.rs`
- SCI bindings: `editor/bindings.cljs` riga 574 ss.

**Esposizione al DSL**

Costruttori primitive: `sdf-sphere`, `sdf-box`, `sdf-cyl`, `sdf-rounded-box`.
Combinatori: `sdf-union`, `sdf-difference`, `sdf-intersection`, `sdf-blend`, `sdf-shell`, `sdf-offset`, `sdf-morph`, `sdf-displace`.
Trasformazioni: `sdf-move`, `sdf-rotate`, `sdf-scale`, `sdf-revolve`.
Compilatore formule: `sdf-formula` (converte forma Clojure -> SDF tree; supporta `x y z r rho theta phi` + `sin cos tan sqrt abs exp log neg square atan2 mod pow` + `+ - * / min max`).
Pattern: `sdf-gyroid`, `sdf-schwarz-p`, `sdf-diamond` (TPMS), `sdf-slats`, `sdf-bars`, `sdf-bar-cage`, `sdf-grid`.
Materializzazione esplicita: `sdf->mesh`, `sdf-ensure-mesh`.

**Valutazione**

Lazy. I costruttori ritornano pure data maps `{:op "sphere" :r 10}`, `{:op "union" :a ... :b ...}`, ecc. La compilazione libfive avviene **solo** al momento di `materialize`. Sempre via geo-server:

```
CLJS sdf-ensure-mesh -> XHR POST /sdf-mesh {tree, bounds, resolution}
  Rust sdf_ops::sdf_to_mesh
    build_tree (ricorsivo, crea libfive_tree_*)
    libfive_tree_render_mesh (marching cubes)
  -> MeshData JSON -> parsed in CLJS con typed arrays raw cachati
```

**Integrazione con il mesh world**

Uniforme: un SDF materializzato e' un Ridley mesh standard. La macro `register` auto-materializza (`sdf-ensure-mesh`) prima del def/register, quindi l'utente non deve chiamare esplicitamente `sdf->mesh` nel caso tipico. Dopo la materializzazione si puo' usare qualsiasi operazione (`mesh-union`, `mesh-smooth`, `warp`, `save-stl`, CSG WASM o nativo).

**Bounds e risoluzione**

`auto-bounds` calcola bounding box ricorsivamente dal tree (sphere r*1.2 per lato, box sx*0.6, union di figli, rotate usa radius, ecc.). `resolution-for-bounds` mappa `*sdf-resolution*` dynamic (default 15) a voxels-per-unit con:
- Base res: `50 + 3*(res-10)` grid cells sul lato piu' lungo
- Cap per budget: `max-voxels` = 4e6 (base), ma thin-feature boost e' esente
- Thin-feature boost: 2.5 voxels nello thinnest feature (shell thickness, offset amount, scale factor)
- Hard cap: 200 voxels sul lato piu' lungo

Warning se voxel-count totale supera 5e8.

**Disponibilita'**

Solo desktop. Il geo-server deve rispondere. In webapp, chiamare `sdf-ensure-mesh` fallisce con `js/Error`.

**Unificazione con mesh world lato utente**

Primitive parallele non unificate: `(box 10 10 10)` e' un mesh, `(sdf-box 10 10 10)` e' un nodo SDF. Sono mondi paralleli finche' non materializzi. La convenzione parametri del box e' preservata (a->Y, b->Z, c->X).

---

## 8. Anomalie e debito tecnico

### 8.1 Tauri IPC handlers morti

`main.rs` registra `tauri::invoke_handler![ping, manifold_union, manifold_difference, manifold_intersection, manifold_hull]`. Grep esaustivo del frontend CLJS non trova nessun `invoke` o `__TAURI__`: le boolean operations passano tutte dal HTTP geo-server (endpoint `/union` ecc). I 4 invoke handler sono codice morto. Duplicazione logica completa: `main.rs::manifold_union` delega a `manifold_ops::union`, lo stesso fa `geo_server.rs /union`.

### 8.2 `/sdf-mesh-bin` non chiamato

L'endpoint binario per SDF esiste (`geo_server.rs` linee 348-388) ma `sdf/core.cljs` chiama solo `/sdf-mesh` (JSON). L'ottimizzazione non e' wired.

### 8.3 Sintomi di vecchio sidecar JVM

Memoria del progetto annota `JVM removal done 2026-04-22`. Rimane:
- `deps.edn` dichiara `thheller/shadow-cljs 2.28.5` come dep MVN, che e' normale, ma anche `org.clojure/clojurescript 1.11.132` come root dep (ridondante vs. node_modules). Non e' bug ma non e' pulito.
- `thi.ng/geom 1.0.1` dichiarato in `deps.edn` E `shadow-cljs.edn`; non ho verificato se usato in moduli attivi.
- `src/dev/repl.clj` resta come tool nREPL.

### 8.4 Scene registry: 7 atom paralleli

`scene-meshes`, `scene-lines`, `scene-stamps`, `scene-paths`, `scene-panels`, `scene-shapes`, `scene-values`. Piu' `mesh-id-counter` (atom) e `claimed-meshes` (JS Set). Nessuno schema unificato; `show-mesh!`/`hide-mesh!` hanno prefix-matching fallback per assembly, mentre `show-panel!` no. `visible-meshes` itera `@scene-meshes`, `visible-panels` itera `@scene-panels`: codice duplicato.

### 8.5 Doppio API mesh (turtle vs. pure) + *-impl

La macro expansion tipica e' `(extrude shape body) -> (path body-as-recording) -> extrude-impl` dove `*-impl` ricevono path data e delegano a `gen-ops/pure-extrude-path` o varianti. Questo livello di indirection rende nontriviale capire "cosa esegue davvero" una macro: c'e' la macro, c'e' il binding `*-impl` in `base-bindings` (editor/impl.cljs), c'e' la fn pura in `editor/operations.cljs`, c'e' l'engine in `turtle/extrusion.cljs`.

### 8.6 Callback-passing per rompere circolarita'

Per evitare circular dep, `core.cljs/init` spedisce callback function a moduli che ne hanno bisogno via `defonce (atom nil)` + setter:
- `anim-playback`: 6 callback (mesh get/register, update-geometry, rigid xform set/reset, camera pose, camera stop, refresh, async-output)
- `anim/core`: 3 callback (on-camera-stop, mesh-callbacks, reset-mesh-transform)
- `editor/state/run-definitions-fn`: set da core
- `editor/state/get-editor-content`: set da core
- `editor/state/sci-ctx-ref`: mirror di `editor/repl/sci-ctx`, set da repl, letto da test-mode
- `registry` non usa callback: importa direttamente `viewport/update-scene`
- `viewport` dichiara callback set da esterno per `on-pick-callback`

Il pattern e' pervasivo ma non uniformato: alcuni moduli usano callback atoms, altri import diretti con defonce + late init (pattern `defonce state (atom nil)` + `(init ...)`).

### 8.7 SCI dynamic var su atom (doppia deref)

`turtle-state-var` e' una SCI dynamic var che PUNTA a un atom, non ai dati. Accessi: `@turtle-state-var` -> atom, `@@turtle-state-var` -> map. Idiom usato ovunque. La macro `turtle` rebinda la dynamic var a un nuovo atom (scoping), le implicit-* fn mutano l'atom. Se il chiamante dimentica la doppia deref (es. fa `@turtle-state-var` aspettandosi la map), comportamento silenziosamente sbagliato.

### 8.8 Asimmetria sync peer messages

Host invia `script-update` e `repl-command`. Client puo' rispondere solo con `script-ack` e `pong`. Il client non puo' inviare comandi verso il host. Architetturalmente intenzionale (headset e' viewer), ma nei callbacks `on-script-received` del host il tipo "script-ack" e' gestito come no-op (utile solo per status?). Non c'e' routing per messaggi client-originated oltre il pong.

### 8.9 Architecture.md obsoleta in parti

`dev-docs/Architecture.md` e' lunga 1144 righe; contiene riferimenti allineati (Manifold WASM, tre.js) e altri che vanno verificati. Non coperti o superati: SDF subsystem, dual-backend storage, assembly mode con prefix hierarchies (register di map literal), pilot mode, tweak mode, face selection query API, describe AI session. La documentazione indicizza anche `Architecture_Assessment_2026-02-06.md` che potrebbe essere il punto piu' recente.

### 8.10 Detection desktop via XHR sincrono

`storage/desktop-mode?` fa uno XHR **sincrono** a `/ping` al primo uso. Bloccante. Se il server non risponde e TCP SYN e' lento (es. macOS firewall), blocca il main thread UI. Cacheato dopo primo check, quindi costo una tantum, ma anomalia worth noting.

### 8.11 Materiale di cache su mesh

I mesh possono portare `::raw-arrays` (Float32Array/Uint32Array) e `::_discarded-cache` (Manifold object cachato). I primi sono per zero-copy verso Three.js, i secondi sono un'ottimizzazione di CSG chaining che tiene un Manifold C++ object vivo dopo operazione (il nome `::_discarded-cache` suggerisce che sia rottura e non usage). Verificare se davvero usato da `union-two` successivi o rimuovere.

### 8.12 `capture-print` e print-buffer

`capture-print`/`capture-println` scrivono a `print-buffer` atom, drenato a fine eval. Ma il binding `log` (`js/console.log`) bypassa: va direttamente in console. Il DSL ha quindi due canali di output (REPL history vs devtools console) e convenzione non esplicita su quale usare quando.

### 8.13 Circularity tra registry e scene/panel

`scene/registry.cljs` importa `scene/panel` per `panel?`. `scene/panel.cljs` non importa registry, OK. Ma il `register` macro in `editor/macros.cljs` fa distinzione per-tipo con switch manuale: aggiungere un nuovo tipo (es. nuova "graph", nuova "material") richiede editare la macro. Non esiste un protocol/multimethod per registrazione.

---

Fine.
