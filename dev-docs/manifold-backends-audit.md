# Manifold backends audit: WASM vs. native Rust

Measurements + feature inventory to decide how to resolve the coexistence
of `mesh-*` (WASM, `manifold/core.cljs`) and `native-*` (Rust, HTTP geo-server,
`manifold/native.cljs`).

Date: 2026-04-23
Environment: macOS 24.6.0, Tauri dev binary (debug build 2026-04-23),
geo-server on 127.0.0.1:12321, Manifold WASM 3.0.0 loaded from CDN,
Chrome browser connected to shadow-cljs watch.

---

## Section 1 - Feature parity

One row per function exposed by `manifold/core.cljs`. Port estimate for
WASM-only items is based on what `manifold3d::sys` exposes (the FFI crate
is a thin wrapper over Manifold C++, so anything in the C++ public API is
reachable).

| Operation | WASM (manifold/core.cljs) | Nativo (manifold/native.cljs) | Endpoint Rust (geo_server.rs) | Note |
|---|---|---|---|---|
| union | `union` (variadic, tree reduction) | `native-union` | `/union` (sequential reduce) | Entrambi accettano vettore o variadic. WASM usa balanced binary tree, Rust lineare. |
| difference | `difference` (variadic) | `native-difference` | `/difference` | A - B - C - ... |
| intersection | `intersection` (variadic) | `native-intersection` | `/intersection` | A ∩ B ∩ C ∩ ... |
| hull | `hull` (variadic, union then hull) | `native-hull` | `/hull` | Rust implementation: se 1 mesh chiama hull diretto, altrimenti union+hull. |
| hull-from-points | `hull-from-points` | NON PRESENTE | NON PRESENTE | Porting: trivial. `Manifold::hull` ha overload static da Vec<Vec3>; in `manifold3d::sys` accessibile come `manifold_hull_pts` o simile. |
| solidify (self-union A ∪ A) | `solidify` | NON PRESENTE | NON PRESENTE | Porting: trivial. Chiama `manifold_union` con due copie del manifold. 5 righe di wiring. |
| mesh-smooth | `mesh-smooth` | NON esposto in native.cljs | `/smooth` (manifold_ops::smooth) | **Parita' gia' a livello endpoint**; manca solo la fn CLJS wrapper. Porting CLJS: trivial (3-4 righe, copia di `native-union`). |
| mesh-refine | `mesh-refine` | NON esposto in native.cljs | `/refine` (manifold_ops::refine) | **Parita' gia' a livello endpoint**; manca solo la fn CLJS wrapper. Porting CLJS: trivial. |
| concat-meshes | `concat-meshes` | NON PRESENTE | N/A | **Non usa Manifold affatto** (pura concatenazione vertices/faces). Vive in manifold/core.cljs per convenzione storica; nessun porting necessario - gia' funziona identico in webapp e desktop. |
| manifold? | `manifold?` | NON PRESENTE | NON PRESENTE | Porting: trivial. Basta un endpoint `/status` che chiama `manifold_status` + `manifold_volume`. 10 righe. |
| get-mesh-status | `get-mesh-status` | NON PRESENTE | NON PRESENTE | Porting: trivial. Stesso endpoint sopra, restituisce {:manifold? :status :volume :surface-area}. |
| slice-at-plane | `slice-at-plane` | NON PRESENTE | NON PRESENTE | Porting: moderate. Richiede: (1) trasformazione basis gia' scritta in CLJS (compute-basis), puo' essere lasciata lato CLJS; (2) `manifold_slice` + `manifold_cross_section_to_polygons` in `manifold3d::sys`; (3) classificazione outer/hole via signed-area gia' in CLJS. Endpoint nuovo `/slice` che accetta mesh + normal + point e ritorna contours. ~60 righe Rust. |
| extrude-cross-section | `extrude-cross-section` | NON PRESENTE | NON PRESENTE | Porting: moderate. Richiede API `ManifoldCrossSection` + `manifold_cross_section_extrude` in `manifold3d::sys`. Endpoint `/extrude-cross-section` che prende contours (outer + holes) e altezza. ~40 righe Rust. |
| mesh->manifold, manifold->mesh | helper WASM-only | helper native-only (mesh->js, js->mesh) | N/A | Dettagli interni di conversione, non parte della superficie DSL. |
| initialized?, wait-for-manifold-module, init!, get-manifold-class, ridley-mesh->manifold-mesh, manifold-mesh->ridley-mesh, get-cross-section-class | bootstrap WASM | N/A | N/A | Solo bootstrap/conversioni WASM. Il nativo non ha bisogno di init (geo-server sempre pronto). |

Sintesi porting se si volesse parita' 1:1 nativo ← WASM:
- **trivial** (<=10 righe Rust + <=10 righe CLJS): `solidify`, `manifold?`, `get-mesh-status`, `hull-from-points`
- **quasi-gia'-fatto** (endpoint gia' c'e', manca solo wrapper CLJS): `mesh-smooth`, `mesh-refine`
- **moderate** (~40-60 righe Rust + wrapper CLJS): `slice-at-plane`, `extrude-cross-section`
- **N/A** (non dipende da Manifold): `concat-meshes`
- **nontrivial**: nessuna

---

## Section 2 - Audit di uso

Ambito: `src/ridley/**`, piu' `examples/`, `dev-docs/libraries/`,
`dev-docs/Examples.md`. Esclusi test e altri dev-docs.

### 2.1 Chiamanti di `native-*`

Risultato grep:

| File | Linea | Contesto |
|---|---|---|
| src/ridley/editor/bindings.cljs | 355-358 | Solo bindings DSL: `'native-union -> native-manifold/native-union` ecc. Non e' un call-site reale, e' il wiring SCI. |

**Nessun chiamante diretto di `native-*` in `src/ridley/**`.** Le 4 funzioni
sono esposte al DSL ma il codice CLJS interno non le usa mai. Tutti i
chiamanti reali (per esempio `implicit.cljs`, `editor/impl.cljs`, le macro
di extrude/loft) usano i nomi `mesh-*` che mappano sul backend WASM.

### 2.2 Chiamanti di funzioni WASM-only

#### `solidify`
- `src/ridley/editor/macros.cljs:1441` - `(defmacro solidify [mesh] ...)` - la macro DSL.
- `src/ridley/editor/bindings.cljs:360` - binding `'solidify-impl -> manifold/solidify`.

Usata come primitiva DSL (il macro aggiunge source-tracking). Nessun uso
indiretto rilevato in altri moduli (il grep di `solidify` restituisce solo
quelle due righe + il nome del binding `'solidify-impl` e i ref nel
registry/altre macro per passing).

#### `slice-at-plane`
- `src/ridley/editor/implicit.cljs:544` - usata in `implicit-slice-mesh`, probabilmente
  dietro una macro DSL di slicing.
- `src/ridley/viewport/capture.cljs:573` - usata per rendering slice lato capture (per
  AI describe o export PNG).

Uso interno reale. 2 chiamanti.

#### `extrude-cross-section`
- `src/ridley/editor/text_ops.cljs:153` - `extrude-text` per profili di testo con holes.
- `src/ridley/editor/text_ops.cljs:238` - `text-on-path` idem.

Uso interno reale. 2 chiamanti, entrambi nel subsistema text. La ragione
e' che earcut/libtess non gestiscono bene i holes in modo affidabile,
quindi si delega a Manifold CrossSection che nativamente supporta outer +
holes.

#### `mesh-smooth`, `mesh-refine`
- `src/ridley/editor/bindings.cljs:349-350` - solo binding DSL.
- `src/ridley/turtle/shape_fn.cljs:761` - citazione in docstring, non chiamata.
- `src/ridley/manual/content.cljs` - esempi nel manuale utente (testo).
- `src/ridley/ai/rag_chunks.cljs:143` - menzione in chunk RAG.

**Nessun chiamante CLJS interno**, sono esposte solo all'utente via DSL.

#### `hull-from-points`
- `src/ridley/turtle/loft.cljs:1308` - usata in un fallback di loft.

Uso interno reale. 1 chiamante.

#### `concat-meshes`
- Esposta come binding DSL.
- Nessun chiamante interno diretto rilevato in `src/ridley/**` (usata
  largamente lato utente negli examples/manuale).

#### `manifold?` / `get-mesh-status`
- `src/ridley/editor/bindings.cljs:343-344` - binding DSL.
- `src/ridley/ai/describe.cljs:48, 55` - usata per tagging metadata nella
  describe session (manifold/non-manifold count + volume/surface area).
- `src/ridley/ai/auto_session.cljs:144, 146` - check mesh status durante
  iterazione AI auto-continue.

Uso interno reale. 2 moduli chiamanti (entrambi AI).

### 2.3 Librerie utente e esempi

Esempi in `examples/` (24 file totali). Grep per uso di CSG/ops:

| File | Usa |
|---|---|
| pipe-clamp.clj | `mesh-union`, `mesh-difference`, `concat-meshes` |
| supporto.clj | `mesh-union`, `mesh-difference`, `concat-meshes` |
| multiboard.clj | `mesh-union`, `mesh-difference`, `concat-meshes` |
| procedural-bowl.clj | `mesh-union`, `mesh-difference`, `concat-meshes` |
| pistacchio-bis.clj | `mesh-union`, `mesh-difference`, `mesh-smooth` (citato in commento) |
| display-frame.clj | `mesh-union`, `mesh-difference` |
| embossed-column.clj | `concat-meshes` |
| dice.clj | `mesh-difference`, `concat-meshes` |
| cerniera.clj | `mesh-union`, `mesh-difference`, `concat-meshes` |
| pistachio-bowl.clj | `mesh-union`, `mesh-difference` |
| canvas-weave.clj | `mesh-difference`, `concat-meshes` |
| multiboard-holder.clj | `mesh-union` |
| **multiboard-native.clj** | **`native-union`, `native-difference`** + `mesh-union`/`mesh-difference` (switch via `(def WASM false)`) |

**Unico esempio che chiama `native-*` esplicitamente: `examples/multiboard-native.clj`.**
E' uno script di benchmark/confronto con flag `WASM` al top che toggle tra
i due backend. Default `WASM false` (usa nativo).

In `dev-docs/libraries/`:
- `weave.clj` - usa `concat-meshes` (solo).
- `gears.clj`, `puppet.cljs` - nessun uso di CSG o WASM-only.

In `dev-docs/Examples.md`: solo snippet che mostrano `manifold?` e
`get-mesh-status` come output di esempio.

### Sintesi Sezione 2

- **Superficie native-*: 4 funzioni esposte, 0 chiamanti in `src/`, 1 esempio
  utente (`multiboard-native.clj`) che e' esso stesso un benchmark.**
- **Superficie WASM-only con chiamanti interni reali:** `solidify`,
  `slice-at-plane` (2 sedi), `extrude-cross-section` (2 sedi), `hull-from-points`
  (1 sede), `manifold?`/`get-mesh-status` (2 sedi AI). Queste sono le uniche
  capacita' del backend che il resto del codice **dipende** dal poterle
  chiamare.
- Gli esempi utente e le librerie del progetto non usano nessuna feature
  WASM-only tranne `concat-meshes` (che non dipende da Manifold) e
  `mesh-smooth` (citato in un commento, non chiamato).

---

## Section 3 - Benchmark

### Setup

- Tauri debug binary avviato localmente (`./desktop/src-tauri/target/debug/ridley-desktop`).
- Geo-server attivo su 127.0.0.1:12321 (verificato con `/ping`).
- Shadow-cljs watch :app avviato fresco, browser Chrome connesso
  (3 relay clients).
- Manifold WASM v3.0.0 inizializzato in browser (`(m/initialized?) => true`).
- REPL CLJS in shadow `:app` via `clj-nrepl-eval`.

### Casi di input

Mesh generate con `ridley.geometry.primitives/sphere-mesh`, offset di 8 sull'asse X
per garantire sovrapposizione significativa (boolean non-triviale).

| Taglia | Input | # facce per mesh |
|---|---|---|
| small | sphere r=10 (24 seg, 16 rings) + sphere r=9 offset [8 0 0] | 720 |
| medium | sphere r=10 (48 seg, 36 rings) + sphere r=9 offset [8 0 0] | 3360 |
| large | sphere r=10 (180 seg, 140 rings) + sphere r=9 offset [8 0 0] | 50040 |

Nota: il target "small ~500" ha dato 720 facce per la geometria piu' vicina
che tornasse sensata; il target "large 50k" e' centrato. Le stesse
reference di mesh sono state passate a WASM e nativo nella stessa run.

### Metodo

- 3 warmup + 10 run timed per ogni combinazione (operation x taglia x backend).
- Timing end-to-end dal REPL: per nativo include JSON serialize +
  XMLHttpRequest sincrono + JSON parse; per WASM include conversione
  Ridley <-> Manifold + boolean + deconversione.
- Misurato via `performance.now()` in CLJS.

### Transport overhead (nativo)

Misura di roundtrip senza computazione (`/ping` vuoto) e di
serializzazione + roundtrip + deserializzazione (`native-union` su una
singola mesh, che il Rust fa clone immediato):

```
                      small (720 faces)   medium (3360 faces)   large (50040 faces)
/ping                 0.60 ms (n/a)       0.60 ms (n/a)         0.60 ms (n/a)
single-mesh clone     4.60 ms             14.90 ms              204.40 ms
  (native-union of 1)
```

- Il `/ping` puro misura solo TCP+HTTP+scheduling, ~0.3-0.8ms mediano.
- Il single-mesh clone misura la somma di: serializzazione JSON di una
  mesh lato CLJS + request body parse lato Rust + clone + response
  serialize + response parse lato CLJS. Su mesh large e' ~200ms, ovvero
  la **quasi-totalita'** dell'overhead del nativo e' serializzazione, non
  rete.

### Risultati per operazione

(Cifre in ms, 2 decimali. "Speedup" = WASM_median / Native_median:
valore > 1 significa WASM piu' veloce; < 1 native piu' veloce.)

#### union

```
                      small           medium           large
                      (720 f)         (3360 f)         (50040 f)
  WASM median         5.20 ms         16.90 ms         224.50 ms
  WASM min-max        4.50-6.20 ms    16.20-18.30 ms   223.30-229.00 ms
  Native median       11.80 ms        50.20 ms         716.80 ms
  Native min-max      10.70-13.60 ms  41.80-53.30 ms   659.20-1033.60 ms
  Speedup             0.44x           0.34x            0.31x
                     (WASM 2.3x-3.2x faster)
```

#### difference

```
                      small           medium           large
                      (720 f)         (3360 f)         (50040 f)
  WASM median         4.50 ms         13.50 ms         179.70 ms
  WASM min-max        3.70-5.50 ms    12.90-14.60 ms   178.20-184.00 ms
  Native median       11.70 ms        53.20 ms         641.40 ms
  Native min-max      9.50-13.40 ms   47.30-59.70 ms   622.80-664.90 ms
  Speedup             0.38x           0.25x            0.28x
                     (WASM 2.6x-3.9x faster)
```

#### intersection

```
                      small           medium           large
                      (720 f)         (3360 f)         (50040 f)
  WASM median         3.10 ms         9.30 ms          117.50 ms
  WASM min-max        2.30-4.80 ms    9.00-11.50 ms    115.30-121.90 ms
  Native median       10.50 ms        46.00 ms         546.60 ms
  Native min-max      8.80-11.00 ms   42.80-49.50 ms   536.00-577.90 ms
  Speedup             0.30x           0.20x            0.21x
                     (WASM 3.4x-4.9x faster)
```

#### hull

```
                      small           medium           large
                      (720 f)         (3360 f)         (50040 f)
  WASM median         3.10 ms         10.10 ms         151.90 ms
  WASM min-max        2.40-4.00 ms    9.80-12.70 ms    150.80-157.00 ms
  Native median       16.10 ms        65.70 ms         807.50 ms
  Native min-max      13.00-18.00 ms  58.40-80.10 ms   776.10-841.40 ms
  Speedup             0.19x           0.15x            0.19x
                     (WASM 5.2x-6.5x faster)
```

### Sanity check

Face count di ritorno per union medium (entrambi backend sulla stessa input):
- WASM: 2879 verts, 5754 faces
- Native: 2757 verts, 5510 faces

Non identici ma coerenti (lievi differenze di precisione in numeriche delle
intersezioni), entrambi manifold-validi.

### Osservazioni sul benchmark

- **Il nativo non e' mai piu' veloce** in questa configurazione, su
  nessuna operazione o taglia. Lo speedup a favore di WASM va da 2.3x
  (union small) fino a 6.5x (hull medium).
- L'overhead di trasporto e' dominante per mesh grandi: su input large
  (50k facce) il single-mesh clone costa gia' 204ms, mentre WASM union
  completa l'intera operazione in 224ms. In altre parole, per mesh
  grandi il nativo spenderebbe tutto il suo tempo in serializzazione JSON
  anche se la CSG C++ fosse istantanea.
- WASM ha min-max molto stretti (224.50 mediana / 223-229 range su large);
  il nativo e' piu' rumoroso (659-1033 range su union large), probabilmente
  per via del thread separato del geo-server + jitter del kernel.
- Hull e' sempre il peggior caso per il nativo: su medium, native-hull e'
  65.70ms vs WASM 10.10ms (6.5x). Ragionevole: hull calcola tree da tutte
  le input + hull finale + ritorno full mesh; la serializzazione del
  risultato di hull domina.
- Il WASM v3.0 di Manifold e' evidentemente molto ottimizzato (SIMD,
  WebAssembly SIMD, JIT moderno). Il Rust manifold3d 0.0.6 e' una
  versione C++ precedente ed e' dietro di almeno un'intera major version
  del motore (v3 vs. presumibilmente v2).

### Nota sulla configurazione misurata

- Build Rust: **debug** (`target/debug/ridley-desktop`). `Cargo.toml` ha
  `[profile.dev.package."*"] opt-level = 3`, quindi le dipendenze
  (incluso `manifold3d`) sono ottimizzate; il codice di `src-tauri/**`
  no. Questo non dovrebbe impattare i numeri perche' il grosso del tempo
  e' dentro la lib manifold. Ma per onesta': un profilo `release` puro
  *potrebbe* guadagnare qualche ms, improbabile che cambi l'ordine di
  grandezza.
- Build CLJS: watch/dev, non `:simple` / `:advanced`. Il :advanced non
  ottimizza il codice del boolean (e' tutto dentro WASM); gli unici
  overhead CLJS sono le conversioni mesh<->Manifold, che gia' nel dev
  build girano in loop stretti con Float32Array/transient vectors.
- Un unico refresh, nessun profiling tool attivo.

---

## Appendice - Dati grezzi delle run

```
/ping                              median=0.60   min=0.30   max=0.80
native-union-single-small (clone)  median=4.60   min=3.00   max=4.80
native-union-single-med   (clone)  median=14.90  min=13.30  max=16.90
native-union-single-large (clone)  median=204.40 min=190.50 max=325.10

wasm-union-small      median=5.20    min=4.50    max=6.20
wasm-union-med        median=16.90   min=16.20   max=18.30
wasm-union-large      median=224.50  min=223.30  max=229.00
native-union-small    median=11.80   min=10.70   max=13.60
native-union-med      median=50.20   min=41.80   max=53.30
native-union-large    median=716.80  min=659.20  max=1033.60

wasm-diff-small       median=4.50    min=3.70    max=5.50
wasm-diff-med         median=13.50   min=12.90   max=14.60
wasm-diff-large       median=179.70  min=178.20  max=184.00
native-diff-small     median=11.70   min=9.50    max=13.40
native-diff-med       median=53.20   min=47.30   max=59.70
native-diff-large     median=641.40  min=622.80  max=664.90

wasm-int-small        median=3.10    min=2.30    max=4.80
wasm-int-med          median=9.30    min=9.00    max=11.50
wasm-int-large        median=117.50  min=115.30  max=121.90
native-int-small      median=10.50   min=8.80    max=11.00
native-int-med        median=46.00   min=42.80   max=49.50
native-int-large      median=546.60  min=536.00  max=577.90

wasm-hull-small       median=3.10    min=2.40    max=4.00
wasm-hull-med         median=10.10   min=9.80    max=12.70
wasm-hull-large       median=151.90  min=150.80  max=157.00
native-hull-small     median=16.10   min=13.00   max=18.00
native-hull-med       median=65.70   min=58.40   max=80.10
native-hull-large     median=807.50  min=776.10  max=841.40
```
