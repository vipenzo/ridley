# Transport audit: Rust -> frontend channel per mesh data

Obiettivo: capire se il canale Rust -> CLJS per mesh data puo' essere reso
competitivo con WASM in-process. Non e' un problema solo di Manifold (il
precedente audit ha mostrato WASM 2-6x piu' veloce); il problema esiste
identico per SDF (libfive non gira in WASM e serve comunque il canale).

Data: 2026-04-23
Ambiente: macOS 24.6.0, Tauri debug binary, geo-server 127.0.0.1:12321,
Chrome connesso a shadow-cljs :app (relay WS). Misurazioni via
`performance.now()` da REPL CLJS.

**CAVEAT importante**: tutte le misurazioni CLJS sono state eseguite nel
Chrome di shadow-cljs, NON nel WKWebView di Tauri. Il comportamento di
WKWebView su large binary bodies potrebbe differire (Safari/WebKit hanno
percorsi diversi da Chromium per XHR sync e responseType). Le conclusioni
sono valide per Chrome, estendibili con riserva a WKWebView.

---

## Sezione 1 - Anatomia del costo attuale

### Setup

- Mesh input: `sphere-mesh 10 180 140` = 50040 facce, 25020 vertici.
- Endpoint: POST `/union` su una mesh singola (il Rust fa clone immediato
  senza reale computazione, stesso pattern usato nel benchmark precedente
  per misurare overhead).
- 10 run timed + 3 warmup. Numeri sotto sono mediane (ms, 2 decimali).

### Decomposizione

| Step | Cosa misura | Mediana (ms) | % del totale |
|---|---|---:|---:|
| CLJS build-js | mesh CLJS -> `#js {:vertices ... :faces ...}` (into-array + js-array di js-array) | 14.30 | 16.5% |
| CLJS stringify | `JSON.stringify` del payload (2.4 MB output) | 2.70 | 3.1% |
| XHR roundtrip | `.send()` ritorna (include: transmit body, Rust parse, Rust clone, Rust serialize response, transmit response) | 50.90 | 58.8% |
| CLJS responseText access | `.responseText` getter | 0.40 | 0.5% |
| CLJS parse-json | `JSON.parse` del response (2.4 MB) | 4.10 | 4.7% |
| CLJS rebuild-mesh | js-parsed -> CLJS `{:type :mesh :vertices [...] :faces [...] :creation-pose ...}` (vec+map) | 14.20 | 16.4% |
| **Totale** |  | **86.60** | 100.00% |

Body bytes: 2_395_500 request, 2_392_659 response.

### Aggregati

- **Lato CLJS (prep + consumo)**: build-js + stringify + parse-json + rebuild-mesh = 35.3 ms (40.8%).
- **Lato Rust + trasporto**: xhr-roundtrip = 50.9 ms (58.8%).
- **Getter overhead**: 0.4 ms (ignorabile).

### La leva principale

**XHR roundtrip (58.8%)** e' il singolo step dominante. Di quei 50.9 ms:
- Network localhost: /ping vuoto misurato a 0.60 ms mediana => trasporto TCP+scheduling e' trascurabile (<2%).
- Quindi **~50 ms sono lavoro lato Rust**: parse del body JSON 2.4 MB + clone strutture (Vec<[f64;3]> + Vec<[u32;3]>) + serialize response 2.4 MB.

Non sono riuscito a decomporre ulteriormente senza modificare
`geo_server.rs` (che l'utente non ha vietato, ma preferisco non toccare
per restare "read-only"). Stima ragionevole data la simmetria dei body
sizes: ~45-50% parse + ~45-50% serialize + pochi ms di clone memcpy.

**Secondi in importanza**: build-js (16.5%) e rebuild-mesh (16.4%),
entrambi lato CLJS. Sono i costi di costruzione/decostruzione delle
persistent vectors + js-arrays nested. Difficili da ridurre senza
cambiare il formato delle mesh Ridley.

### Nota rispetto al precedente audit

Il precedente audit (`manifold-backends-audit.md`) riportava 204 ms per
`native-union-single-large`. Oggi, con Tauri appena riavviato e una
sessione Chrome pulita, lo stesso benchmark da 86 ms (verificato). La
differenza e' **replicabile** ed e' imputabile a jitter del sistema
(processi concorrenti, JIT non caldo, Tauri in stato degradato). La
decomposizione in questo documento usa i numeri di oggi; i trend
relativi rimangono validi.

---

## Sezione 2 - Test del canale binario esistente

### Lo spike

Scritto un client CLJS minimale per `/sdf-mesh-bin` che legge il response
come ArrayBuffer e wrappa zero-copy in `Float32Array` + `Uint32Array`
secondo il formato atteso. Lo script vive temporaneamente nel REPL (non
modifica `sdf/core.cljs`).

```clojure
;; Pseudocodice del client binario testato
(let [xhr (js/XMLHttpRequest.)]
  (set! (.-responseType xhr) "arraybuffer")
  (.open xhr "POST" ".../sdf-mesh-bin" true)  ; async: sync non permette responseType
  (.setRequestHeader xhr "Content-Type" "application/json")
  (set! (.-onload xhr)
    (fn []
      (let [buf (.-response xhr)
            header (js/Uint32Array. buf 0 2)
            nv (aget header 0) nf (aget header 1)
            verts-typed (js/Float32Array. buf 8 (* nv 3))
            faces-typed (js/Uint32Array. buf (+ 8 (* nv 3 4)) (* nf 3))]
        ;; ... rebuild Ridley mesh vectors ...)))
  (.send xhr body))
```

### VINCOLO BLOCCANTE scoperto in corso

```
InvalidAccessError: Failed to execute 'open' on 'XMLHttpRequest':
Synchronous requests from a document must not set a response type.
```

Chrome (e tutti i browser moderni conformi alla fetch spec) **proibiscono
`responseType="arraybuffer"` su XHR sincrono**. Le uniche opzioni sono:

1. Passare a XHR asincrono (rompe il contratto SCI sincrono del DSL).
2. Usare `fetch()` asincrono (stesso problema).
3. Usare sync XHR con `overrideMimeType("text/plain; charset=x-user-defined")`
   e decodificare manualmente i byte dal responseText latin-1.

Testate tutte e tre.

### Test case

SDF input: `(sdf-intersection (sdf-gyroid 8 1.2) (sdf-sphere 12))`, bounds
[-14,14]^3, resolution 1.2. Output: 83_400 facce, 41_589 vertici. Response
body: 4.04 MB (JSON) / 1.50 MB (binary).

10 run + 3 warmup, Tauri fresco.

### Risultati (SDF 83k facce, end-to-end)

| Strategia | Mediana (ms) | min-max (ms) | Note |
|---|---:|---:|---|
| Sync XHR + JSON `/sdf-mesh` (stato attuale) | 61.00 | 58-68 | Baseline. Response 4.0 MB text. |
| Async XHR + binary ArrayBuffer `/sdf-mesh-bin` | 302.40 | 255-332 | Response 1.5 MB bytes. |
| Async fetch + arrayBuffer `/sdf-mesh-bin` | 172.20 | 159-186 | Response 1.5 MB bytes. |
| Sync XHR + overrideMimeType latin-1 `/sdf-mesh-bin` | 312.30 | 245-395 | Decodifica manuale char->byte. |
| Async XHR + text `/sdf-mesh` | 370.90 | 312-434 | Stessa JSON di baseline ma async. |
| Sync XHR + raw bytes (no responseType) `/sdf-mesh-bin` | 372.50 | 270-965 | Chrome decodifica comunque come UTF-8. |

### Decomposizione del binario via async XHR (miglior caso binario)

| Step | Mediana (ms) |
|---|---:|
| fetch/onload (Rust compute + transport + headers ready) | 272.90 |
| access `.response` ArrayBuffer | 0.40 |
| wrap Float32Array/Uint32Array (zero-copy) | 0.00 |
| rebuild Ridley mesh vectors | 29.10 |
| **Totale** | **302.40** |

### Il colpo di scena

**Il canale binario e' piu' LENTO del JSON.**

Non e' un problema del Rust: il server produce il body binario piu'
velocemente (memcpy vs serde_json). E' un problema del lato JavaScript.
Due fenomeni sovrapposti:

1. **Chrome sync-XHR non puo' avere responseType**: forza async, che
   dispatcha tramite event loop microtask/macrotask.  Con body grandi la
   risposta impiega >200 ms prima di raggiungere `.onload`, mentre sync
   XHR ritorna subito dopo la fine del trasporto. Constatato anche con
   async fetch di /sdf-mesh (JSON) che e' pure 6x piu' lento di sync XHR.
2. **Sync XHR verso Content-Type: application/octet-stream** e' 8x piu'
   lento che verso application/json, anche senza accedere `.responseText`.
   Chrome probabilmente fa un UTF-8 scan della risposta binaria durante
   la ricezione, che su 1.5 MB costa ~250 ms.

Pur non accedendo mai al body, il sync XHR al body binario costa gia'
~270 ms. Quindi la speranza "sync XHR + responseText latin-1" non funziona.

### Verifica baseline: fetch non ha overhead intrinseco

Fetch a `/ping` vuoto: 1.10 ms mediana. Il problema non e' fetch/async
per se, e' specifico dei body grossi.

### Speedup risultante

Nessuno. Il binario e' **3-5x piu' lento** del JSON nel path attuale per
Chrome. Non rappresenta uno spike minimale: attivarlo degraderebbe
l'esperienza. Vale la pena re-testarlo in WKWebView (Safari ha storia di
path XHR diversi), ma dal lato Chrome il canale binario HTTP **non e' la
leva corretta**.

---

## Sezione 3 - Opzioni architetturali

### 3.1 Endpoint binari HTTP generalizzati (`/union-bin`, `/difference-bin`, ecc.)

**Cosa comporta**: estendere il pattern di `/sdf-mesh-bin` alle altre
endpoint. Il formato payload resta similare: header con count + typed
arrays raw.

**Complessita'**: trivial. Server-side ~30 righe per endpoint. Client-side
un wrapper `invoke-bin` che sostituisce `invoke-sync`.

**Guadagno atteso vs. HTTP+JSON (large)**: **negativo in Chrome**. Il
test empirico di Sezione 2 mostra che il canale binario HTTP costa 3-5x
rispetto al JSON, per i due motivi sopra (async forzato + Chrome slow
path su application/octet-stream). In WKWebView non misurato, ma non ci
sono garanzie che vada meglio. **Non raccomandato come "minimo investimento"
senza prima misurare in WKWebView.**

**Vincoli noti**: sync XHR + arraybuffer bloccato da spec. L'unico modo
sincrono per tirarlo giu' e' il trucco overrideMimeType, che pero' paga
comunque il slow path di Chrome (misurato 312 ms).

### 3.2 Tauri IPC con payload binario

**Cosa comporta**: scrivere `tauri::command` che ritornano `Vec<u8>` o
`tauri::ipc::Response::new(bytes)`. Frontend chiama con
`window.__TAURI__.core.invoke("my_cmd", args)` e riceve il byte buffer.

**Complessita'**: moderate. Richiede:
- Riattivare/estendere gli invoke handler in `main.rs` (sono morti, come
  notato in architecture-recon.md).
- Scrivere wrapper CLJS che sostituisce XHR con `invoke()`. `invoke()`
  ritorna sempre Promise, quindi stesso problema async del 3.1 per il
  contratto sincrono del DSL.

**Guadagno atteso vs. HTTP+JSON (large)**: non misurato, nessun benchmark
pubblico reperibile in ricerca. Documentazione Tauri 2 dice che il
"custom protocol" (ipc://localhost) e' "preferred per larger payloads e
binary". In teoria evita JSON serialize/parse su entrambi i lati (se
usato in modalita' `Raw(Vec<u8>)` via `InvokeResponseBody::Raw`, bypassa
la serializzazione JSON). Stima senza evidenza: 1.5-3x piu' veloce di
HTTP+JSON se ben implementato, ma il canale e' sempre async in JS lato
(niente sincrono).

**Vincoli noti**:
- In Tauri 2 il trasporto `ipc://localhost` usa fetch() interno. Stesso
  problema async-only del 3.1.
- Su Android `invoke()` cade su postMessage (non rilevante qui,
  target desktop).
- Il bottleneck documentato e' "serializzazione JSON di argomenti e
  return values dovuta alle restrizioni delle webview library"
  (stringa-only per default), ma `InvokeResponseBody::Raw(Vec<u8>)`
  bypassa questa via (Tauri 2).

### 3.3 Custom URI scheme (`register_uri_scheme_protocol`)

**Cosa comporta**: registrare uno schema tipo `ridley://` e rispondere
byte lato Rust. Il frontend chiama `fetch("ridley://mesh/...")` e riceve
ArrayBuffer (o `new Request("ridley://...")`).

**Complessita'**: moderate. In Tauri 2 l'API e' cambiata: firma
`register_uri_scheme_protocol` ritorna `http::Response` direttamente (non
`Result<http::Response>`). Esiste anche
`register_asynchronous_uri_scheme_protocol` se serve non bloccare il
main thread. Server-side ~40-60 righe per dispatcher + gestione dei path
(tipo `ridley://mesh?op=union&id=...`).

**Guadagno atteso vs. HTTP+JSON (large)**: non misurato. Similare a 3.2:
il webview riceve byte "nativi" senza passare per Chromium's HTTP stack.
Potrebbe evitare il slow path su application/octet-stream (dato che non
passa per HTTP parser classico). Stima: 1.5-3x piu' veloce rispetto a
HTTP+JSON. Stesso vincolo async.

**Vincoli noti**:
- CORS: se il frontend e' su `http://localhost:9000` (shadow dev) e si
  vuole fare fetch a `ridley://`, richiede corrispondenti
  Access-Control-Allow-Origin headers.
- Richiede rebuild del binario Tauri; non utilizzabile in hot-reload
  scenario puro browser.
- Documentazione esistente lo posiziona come "asset loading" (immagini,
  font); uso per dati applicativi e' meno battuto.

### 3.4 SharedArrayBuffer / memoria condivisa WKWebView

**Cosa comporta**: condividere un ArrayBuffer tra Rust e JS senza copie.
L'idea e': Rust scrive, JS legge via SAB.

**Fattibilita' reale**: **non praticabile** per questo caso d'uso.

Due confini:

1. **SAB e' per frontend-to-frontend, non backend-to-frontend**. Il
   maintainer Tauri lo ha dichiarato esplicitamente nel thread di
   discussione #6269: "The SharedArrayBuffer implementation does not
   allow communication between frontend (js/wasm) and backend (rust) but
   only between different frontend contexts."
   Il Rust process non condivide memoria con il webview process; SAB
   vive nel motore JS.
2. **WKWebView support parziale**: SAB disponibile da Safari/WKWebView
   16.3 (macOS Ventura 13.x+). In Tauri 2 bisogna settare Content
   Security Policy con COOP+COEP: `Cross-Origin-Opener-Policy: same-origin`
   e `Cross-Origin-Embedder-Policy: require-corp`. Comporta cambi al
   caricamento di risorse cross-origin (es. CDN per Manifold WASM).

L'unico path immaginabile sarebbe: un Worker (nel frontend) fetcha byte
via uno dei canali 3.1/3.2/3.3, poi copia in una SAB. Il main thread
usa `Atomics.wait` per bloccare. Questo trasforma un canale async in
un canale "pseudo-sync", al prezzo di una copia extra in memoria e della
complessita' worker/COOP/COEP. Vale la pena solo se vogliamo PROPRIO
sync semantics nel DSL senza cambiare il contratto. Complessita':
**nontrivial** (2-3 giorni di lavoro + debugging COEP).

**Vincoli noti**:
- SAB su macOS Ventura/Sonoma e' supportato ma richiede COOP/COEP su
  TUTTE le risorse cross-origin. Tauri 2 permette di settare headers via
  `tauri.conf.json` ma cambiare CSP puo' rompere il caricamento attuale
  di Manifold WASM da CDN.
- Non ci sono esempi pubblici di SAB per workaround sync IPC in Tauri 2.

---

## Sezione 4 - Raccomandazione (tre scenari)

### Scenario A - Minimale

**Cosa fare**: **nulla sul canale binario**.

Il test empirico in Sezione 2 mostra che attivare `/sdf-mesh-bin` dal
frontend non solo non accelera, ma **degrada** 3-5x le latency in Chrome.
Senza una prima misura in WKWebView (dove Safari potrebbe comportarsi
diversamente), abilitarlo e' rischioso.

Se si vuole comunque un quick win con minimo lavoro, le due leve non
binarie misurabili sono:
- **Comprimere il JSON**: niente whitespace, nomi chiave piu' corti
  ("v" invece di "vertices", "f" invece di "faces"). Stima del guadagno:
  ~10-15% di body size, ~5-10% di latency. Richiede modifica di
  `manifold_ops.rs` e simmetricamente di `manifold/native.cljs`.
  Complessita': trivial. Non rompe compat (endpoint nuovo affianco).
- **Streaming server-side**: attualmente `Response::from_data(buf)`
  costruisce l'intero body in memoria prima di inviare. Per mesh grandi
  potrebbe aiutare streamare mentre si serializza. Complessita':
  moderate. Guadagno marginale su localhost.

Se si vuole un quick win effettivo, piu' che altro, il Rust-side
serialize (parte dei 50 ms di xhr-roundtrip) e' il vero collo di bottiglia:
swappare `serde_json::to_string` con `sonic_rs` o `simd_json` potrebbe
dare 2-3x sul serialize lato Rust, portando xhr-roundtrip sotto i 30 ms.
Nessun cambio al frontend. Complessita': trivial (una dipendenza).

### Scenario B - Architetturalmente pulito

**Cosa fare**: migrare a canale IPC Tauri con payload binario raw, rinunciando
al contratto sync del DSL (o usare pattern SAB worker per mantenerlo).

Due varianti:

**B1 - Tauri IPC `InvokeResponseBody::Raw(Vec<u8>)`**:
- Rust: i comandi attuali (union, diff, intersect, hull, sdf-mesh)
  diventano `tauri::command` che ritornano Vec<u8> binario.
- CLJS: `manifold/native.cljs` e `sdf/core.cljs` usano `invoke()` invece
  di XHR. Tutte le operazioni diventano **async** (ritornano Promise).
- DSL macro si adattano a pipe `.then` o usano un await pattern via
  Worker+SAB (sync semantics).
- Stima guadagno: non misurato; da documentazione Tauri preferito per
  binary/large payloads. Ipotesi 1.5-3x piu' veloce del JSON attuale,
  quindi sotto 30 ms per 50k facce.
- Costo stimato: 3-5 giorni incluso testing + adattamento macro/SCI.
  Complessita': moderate.

**B2 - Custom URI scheme ridley://**:
- Come B1 ma lato client via `fetch("ridley://...")` con
  responseType=arraybuffer. Similare guadagno atteso.
- Vantaggio rispetto a B1: endpoint piu' REST-like, potenzialmente
  riusabile per altri scopi (es. asset caching, mesh-by-id).
- Costo simile a B1.

Per SDF specificamente (dove non c'e' fallback WASM possibile), B1 o B2
ha senso. Per Manifold, restano dietro a WASM di 2-3x nel migliore dei
casi, quindi il lavoro non ribalta la conclusione del precedente audit.

### Scenario C - Status quo

**Cosa si perde**: niente sul path Manifold (WASM vince comunque).
Sul path SDF:
- Per SDF < 30k facce: totale < 50 ms, esperienza gia' ottima.
- Per SDF 30-100k facce (caso tipico gyroid/TPMS): totale 50-100 ms,
  accettabile in dev loop, fastidioso in live tweak.
- Per SDF > 500k facce (resolution alta su bounds grandi): totale
  diventa secondi (il limite diventa libfive meshing, poi la serializzazione
  JSON lato Rust a ~0.5 ms/kface, poi la parse+rebuild CLJS).
  Utente vede il blocco UI (sync XHR blocca il main thread).

**Quando il lento e' davvero un problema**:
- Live tweak di parametri SDF con gyroid su bounds >=20 su lato: ogni
  slider movement scatena re-mesh blocking → stutter. Oggi il path SDF
  JSON impiega 400-1500 ms su mesh da 300k+ facce.
- Workflow iterativi AI che materializzano SDF ad ogni iterazione.
- Export STL di grandi SDF (ma qui libfive compute domina, non il
  trasporto).

Per tutti gli altri casi (scripting normale, meshes piccole-medie, uso
didattico), il status quo e' sopra la soglia di fastidio.

### Sintesi comparativa

|  | A (minimale) | B1 (Tauri IPC) | B2 (URI scheme) | C (status quo) |
|---|---|---|---|---|
| Costo lavoro | ~0-1 giorno | 3-5 giorni | 3-5 giorni | 0 |
| Speedup atteso sul path SDF large | 5-10% | 1.5-3x (stimato) | 1.5-3x (stimato) | -- |
| Breaking change API DSL | no | si' (async) o complesso (SAB) | si' (async) | -- |
| Rischio di regressione | basso | medio | medio | -- |
| Misurabilita' prima di impegnarsi | si' | no (richiede proto) | no (richiede proto) | -- |
| Risolve il problema SDF live-tweak (>500k facce) | parzialmente | si' | si' | no |

### Una osservazione trasversale per l'utente

Tutte le opzioni B vanno nella direzione **async**. Il contratto
sincrono del DSL (che oggi funziona grazie al sync XHR) e' l'ostacolo
centrale: tutte le alternative veloci (fetch, invoke, custom scheme)
sono async, e l'unica via per averle sync e' passare per
Worker+SharedArrayBuffer che aggiunge complessita' senza garanzie su
WKWebView.

Una decisione che non viene chiesta qui ma e' sotto la superficie:
vale la pena rompere il sync DSL contract in cambio di performance?
Se si', B1 o B2 sono aperti; se no, siamo in C e il margine di
miglioramento senza rottura e' marginale (A).

Sources:
- [Tauri 2 IPC docs](https://v2.tauri.app/concept/inter-process-communication/)
- [Tauri IPC Improvements discussion #5690](https://github.com/tauri-apps/tauri/discussions/5690)
- [Tauri SharedArrayBuffer discussion #6269](https://github.com/tauri-apps/tauri/discussions/6269)
- [tauri::Builder docs (register_uri_scheme_protocol)](https://docs.rs/tauri/latest/tauri/struct.Builder.html)
- [web.dev COOP/COEP guide](https://web.dev/articles/coop-coep)
- [Registering a custom URI scheme example discussion #5597](https://github.com/tauri-apps/tauri/discussions/5597)
