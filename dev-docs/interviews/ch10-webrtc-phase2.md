# Capitolo 10 — Canale WebRTC, fase 2

Approfondimenti sui temi che probabilmente apparirano nel capitolo. Tutte le citazioni sono al codice attuale (snapshot 2026-04-26).

## A. I casi d'uso reali

### A1. Desktop ↔ Headset

Il flusso voluto:
- desktop = host (clicca **Share** in toolbar, [public/index.html:68](public/index.html#L68))
- headset = client (clicca **Link** ed entra il codice di 6 caratteri, [public/index.html:69](public/index.html#L69))

Confermato dal codice: `host-session` ([peer.cljs:133-172](src/ridley/sync/peer.cljs#L133-L172)) genera il peer-id (`ridley-XXXXXX`); `join-session` ([peer.cljs:198-223](src/ridley/sync/peer.cljs#L198-L223)) si connette dato un peer-id. Non c'è codice che imponga *quale* dei due sia il desktop e quale il visore — è una convenzione UI. Funziona perché su un Quest digitare un peer-id con la tastiera virtuale è già scomodo, e leggere/dettare/condividere un codice dal desktop è il senso più naturale del flusso.

**Come si trovano fisicamente, oggi:**
- digitazione manuale del codice corto sul Quest (l'unica via sempre disponibile);
- *deep link* via URL `?peer=ridley-XXXXXX` ([peer.cljs:324-328](src/ridley/sync/peer.cljs#L324-L328), auto-join in [core.cljs:1266-1269](src/ridley/core.cljs#L1266-L1269)) — utile se l'utente apre lo stesso URL su entrambi i dispositivi (es. via "Send to device" del browser, AirDrop di un link, scansione manuale di un QR generato a mano);
- **non** c'è QR code in UI. Il modale Share mostra solo il codice testuale ([core.cljs:1135-1164](src/ridley/core.cljs#L1135-L1164)).

`generate-qr-code` ([peer.cljs:306-320](src/ridley/sync/peer.cljs#L306-L320)) e `generate-share-url` ([peer.cljs:301-304](src/ridley/sync/peer.cljs#L301-L304)) sono *destinati esattamente a questo caso*: il QR sul desktop, il Quest che lo punta con la fotocamera (o con il browser-overlay) e fa auto-join. Il "Future Improvements" di [Architecture.md:1079](dev-docs/Architecture.md#L1079) lo dice esplicitamente: "QR code for easy headset connection".

**Cosa manca per chiudere il caso:**
1. Sul modale Share, generare il QR (chiamata a `generate-share-url` + `generate-qr-code`) e renderizzarlo come `<img>` nel modale — circa dieci righe di colla in `show-share-modal` ([core.cljs:1135-1164](src/ridley/core.cljs#L1135-L1164)).
2. Conoscere `base-url`: probabilmente `js/location.origin + js/location.pathname`. Banale.
3. (Opzionale) Server statico HTTPS perché il browser Quest accetti WebXR + WebRTC. Già un requisito esterno, non un blocker del codice di sync.

Stima: lavoro da un pomeriggio. Niente di sostanziale è bloccato; è solo cablaggio non scritto.

### A2. Desktop ↔ Desktop come viewer

**Funziona oggi**, perché il protocollo non discrimina sul tipo di client. Il client esegue lo stesso `on-script-received`/`on-repl-received` ([core.cljs:1038-1062](src/ridley/core.cljs#L1038-L1062)) sia in browser desktop che in WebXR.

**È esplicitamente supportato?** No. Il commento di sezione "Sync (Desktop <-> Headset)" a [core.cljs:1017](src/ridley/core.cljs#L1017) e tutta la prosa in [Architecture.md:925-944](dev-docs/Architecture.md#L925-L944) parlano sempre e solo del caso headset. È *funzionalmente caduto fuori* dalla simmetria del protocollo, non da un'intenzione documentata.

**Accorgimenti UX per il caso "viewer su desktop":**
- Il client *non* è messo in read-only. La textarea CodeMirror resta editabile in ogni modo. Il viewer può digitare nel proprio buffer, ma la prossima `script-update` dell'host gli sovrascriverà tutto via `cm/set-value` ([core.cljs:1042](src/ridley/core.cljs#L1042)) **senza preavviso**. È identico al caso headset, ma sul headset l'editor è raramente toccato dall'utente, mentre sul desktop è tentantissimo.
- Nessun banner "you are a viewer", nessun cursore remoto, nessuna indicazione visiva del fatto che il proprio buffer non è autoritativo. L'unico segnale è il testo `Connected to ABC123` nella status bar ([core.cljs:1078](src/ridley/core.cljs#L1078)).
- Il client ha però il proprio REPL pienamente attivo: può digitare comandi REPL, che vengono valutati nel proprio SCI ([core.cljs:462-488](src/ridley/core.cljs#L462-L488)). Però la valutazione dei comandi locali del client non viaggia su nessun canale: l'host non li vede. Quindi il viewer può "esplorare" da solo (`(* x 2)`, `(println turtle-state)`) finché non arriva un nuovo `repl-command` dall'host che cambia lo stato della tartaruga sotto i suoi piedi.

Per il capitolo: dal punto di vista del *protocollo* i due casi sono indistinguibili. Dal punto di vista *UX* il caso desktop-viewer è underserved e mette in evidenza lacune (no read-only, no badge, no cursor) che sul Quest non si notano.

### A3. Asimmetrie host/client legate al headset

**Il client si comporta diversamente in WebXR?** Nel codice di sync, no. Il file [src/ridley/sync/peer.cljs](src/ridley/sync/peer.cljs) non contiene una sola occorrenza di `xr`, `webxr`, `quest`, `presenting`. La logica di `on-script-received` in [core.cljs:1038-1044](src/ridley/core.cljs#L1038-L1044) e di `on-repl-received` in [core.cljs:1046-1062](src/ridley/core.cljs#L1046-L1062) non è condizionata sulla modalità XR. Il client valuta SCI in modo identico a un browser desktop.

L'unica differenza condizionata su XR è nel rendering, dentro [src/ridley/viewport/core.cljs:996](src/ridley/viewport/core.cljs#L996) e [src/ridley/viewport/xr.cljs:214-217](src/ridley/viewport/xr.cljs#L214-L217), dove `xr-presenting?` decide il loop di rendering — ma è ortogonale alla sync.

Quindi: **niente short-circuit, niente degradazione qualitativa, niente skip della valutazione.** Il client Quest paga *lo stesso costo* di un client desktop, su CPU/GPU molto più piccole. Se l'host genera una scena pesante (vedi D4), il Quest si congela. Non c'è guard, non c'è fallback.

### A4. Stato di completezza dello scaffolding

Triage onesto, voce per voce:

| Item | Esistente | Cablato in UI? | Stato |
|---|---|---|---|
| `generate-qr-code` ([peer.cljs:306-320](src/ridley/sync/peer.cljs#L306-L320)) | Sì, dipendenza `qrcode` in `package.json` | No, il modale Share non lo chiama | **WIP non finito.** Listato esplicitamente in [Architecture.md:1079](dev-docs/Architecture.md#L1079) come "Future Improvements". È scaffolding con intent dichiarato, mai chiuso. |
| `generate-share-url` ([peer.cljs:301-304](src/ridley/sync/peer.cljs#L301-L304)) | Sì | No | **WIP non finito.** Helper per il QR. Stessa storia, stessa motivazione. |
| `on-script-received` lato host ([peer.cljs:137,141,145,150](src/ridley/sync/peer.cljs#L141)) | Accettata da `host-session`, salvata nello state, *mai dispatchata* | N/A | **Spike abbandonato.** Il codice del primo commit ([28ff32f](https://github.com/.../commit/28ff32f)) la accettava già senza implementare lo handler `script-update` lato host. È residuo di un tentativo di simmetria che non è mai diventato vero protocollo bidirezionale. Anche [Architecture.md:1082](dev-docs/Architecture.md#L1082) elenca "Bidirectional editing (collaborative mode)" come futuro. |
| `:on-repl-received` per il client | Accettata da `join-session`, dispatchata correttamente quando arriva un `repl-command` dall'host | Cablata in [core.cljs:1238-1239](src/ridley/core.cljs#L1238-L1239) | **Funzionante.** Diverso dai due sopra: questo lato esiste ed è in uso. (La fase 1 era confusa qui, scuse: la callback c'è ed è viva sul client.) |
| `send-ping`/`pong` ([peer.cljs:264-269](src/ridley/sync/peer.cljs#L264-L269)) | Definite, mai chiamate, [Architecture.md:983-993](dev-docs/Architecture.md#L983-L993) le documenta come "Keepalive" | No timer in nessuna parte del codice | **Spike abbandonato.** Nessuna issue o TODO, e nemmeno un cenno in Roadmap.md. Sembra "sembrava utile, l'ho lasciato lì". |
| Reconnect automatica | No | N/A | Listata in [Architecture.md:1080](dev-docs/Architecture.md#L1080) come "Future Improvements". WIP non iniziato. |
| TURN server custom | No | `peer-config` è solo `{:debug 0}` ([peer.cljs:130-131](src/ridley/sync/peer.cljs#L130-L131)) | Listato in [Architecture.md:1081](dev-docs/Architecture.md#L1081) come "Custom PeerJS server for reliability". WIP non iniziato. |

Riassumendo: **il sistema è MVP shipato ma mai iterato.** È ferma a Gennaio 2026 (vedi A5).

### A5. Tracce nella git history

Letteralmente **due commit**, entrambi del 21 gennaio 2026:

```
28ff32f 2026-01-21  Add WebRTC peer-to-peer sync for desktop-to-headset communication
9684c8b 2026-01-21  Add REPL sync, fix modals and path macros, document sync architecture
```

- `28ff32f` (12:05): primo commit, già con multi-client, share modal, `qrcode` come dep, `generate-qr-code`, `generate-share-url`, le callback non simmetriche. Il design è interamente "headset-first": stat, modale, codice di 6 char, deep-link via `?peer=`. Nessun cenno al caso desktop-viewer.
- `9684c8b` (16:31, stesso giorno): aggiunta del messaggio `repl-command`, fix modali, e *documentazione* in `Architecture.md`.

Da gennaio a oggi (aprile 2026), zero commit. Il sottosistema è quindi:
- **headset-first by design** (commit, prosa, modale, peer-id schema);
- **desktop-viewer per inerzia del protocollo** (funziona ma nessuno l'ha esplicitamente targettato);
- **fermo da 3 mesi**: niente issue di follow-up, niente WIP non committato visibile, niente tracking in [Roadmap.md](dev-docs/Roadmap.md). L'unica menzione in Roadmap è ["Collaborative editing" — real-time sync](dev-docs/Roadmap.md#L603), che è un *futuro lontano*, non un piano per chiudere la fase 1 attuale.

Per il capitolo: il design originale era headset-first; tutto il resto è sub-prodotto.

## B. Determinismo della replica

### B1. Fonti di non-determinismo

Le ho cercate sistematicamente.

- **`rand`/`rand-int`/`rand-nth`/`shuffle`/`random-uuid`**: SCI eredita `clojure.core` per default e queste primitive non sono escluse. Non risultano *usate* nelle bindings di Ridley ([bindings.cljs](src/ridley/editor/bindings.cljs) — nessun match) né nel layer turtle, ma se l'utente le scrive nel proprio sorgente vengono valutate normalmente con seed nativo della VM JS. Non c'è seed condiviso, non c'è seeding deterministico esposto. Le primitive procedurali "noise/voronoi" del DSL fortunatamente sono *deterministiche per costruzione*: `noisy` e `voronoi-grid` derivano dai parametri `seed` espliciti dell'utente ([shape_fn.cljs:209-211](src/ridley/turtle/shape_fn.cljs#L209-L211), [shape_fn.cljs:650-654](src/ridley/turtle/shape_fn.cljs#L650-L654), [shape_fn.cljs:708-709](src/ridley/turtle/shape_fn.cljs#L708-L709)). Quindi: *l'idiomatica Ridley è deterministica*; un utente che chiama `rand` divaricherà.
- **`now`/timestamp**: `'perf-now` è esposto a SCI ([bindings.cljs:353](src/ridley/editor/bindings.cljs#L353)) con `(.now js/performance)`. Non deterministico. Anche `(System/currentTimeMillis)` da Clojure/SCI ritorna `Date.now()`. Se l'utente lo usa in un calcolo geometrico, divergenza garantita.
- **Viewport size**: cercato "width", "height", "canvas-size" nelle bindings — non trovati esposti a SCI. La scena è in coordinate world, indipendente dal canvas. Quindi *probabilmente* il rendering è size-agnostic. Nota però che gli helpers di camera/picking dipendono dal canvas, ma quelli sono lato render, non lato DSL.
- **Font cache**: `text/init-default-font!` ([turtle/text.cljs:56](src/ridley/turtle/text.cljs#L56)) è chiamata in `init` con `await`-style promise ([core.cljs:2448-2450](src/ridley/core.cljs#L2448-L2450)). Se il client riceve uno `script-update` e rivaluta *prima* del completamento del fetch, le `text-shape` daranno mesh vuoti o errori. È una vera race condition che sul Quest è verosimile (CDN fetch più lento dell'arrivo del DataChannel iniziale). L'host con cache calda non se ne accorge.
- **`env`/`desktop?`** ([env.cljs:8-17](src/ridley/env.cljs#L8-L17)): esposti a SCI come `'env` e `'desktop?` ([bindings.cljs:619-621](src/ridley/editor/bindings.cljs#L619-L621)). Restituiscono `:desktop` su Tauri, `:webapp` altrove. **Se l'utente ramifica il codice geometrico su `(if (desktop?) ...)`, host e client vedranno geometrie diverse.** È un primitivo legittimo (serve per scegliere se usare path Rust o WASM per CSG), ma è un piede di porco al determinismo della sync.
- **localStorage del client**: settings UI (`settings/load-settings!`, [core.cljs:2452](src/ridley/core.cljs#L2452)) e libreria attiva ([library/storage.cljs](src/ridley/library/storage.cljs)). Le librerie attive influenzano il contesto SCI: `make-sci-ctx` ([editor/repl.cljs:30-38](src/ridley/editor/repl.cljs#L30-L38)) carica `lib-ns` da `library/load-active-libraries`. Se host e client hanno set di librerie attive diversi, lo stesso sorgente può fallire o produrre geometrie diverse. **È una vera fonte di divergenza, non ipotetica.**
- **WebGL/Three.js**: il rendering è puro (mesh in input, pixel in output), non rientra nello stato sincronizzato. Differenze di antialiasing/picking/precision esistono ma non causano divergenza *del modello*, solo *della visualizzazione*.

Tre sorgenti realistiche di divergenza, nell'ordine di pericolosità:
1. Set di librerie attive diverso fra host e client (alta — invisibile finché non rompe).
2. Codice utente che usa `desktop?`/`env` per ramificare (media — è un'API legittima ma incompatibile con sync).
3. Font ancora non caricati al primo `script-update` sul client (bassa — transitorio, si riassesta a un secondo run).

### B2. Verifica della convergenza

**Non esiste.** Non c'è alcun hash della scena, nessuna RPC "compare", nessun checksum. Lo `script-ack` ([peer.cljs:107](src/ridley/sync/peer.cljs#L107)) conferma solo "ricevuto", non "valutato senza errori" né "produce la stessa mesh". Il modello è "fede ottimistica": se il sorgente è stato spedito, si presume che il client veda la stessa cosa.

Per il capitolo, la frase onesta è: la convergenza è *sperata*, non *garantita*. Ed è un caso d'uso che si autocura — l'host ha la scena di riferimento, il client si limita a guardare. Se il visore vede qualcosa di strano, l'host può sempre rifare Run.

### B3. Comandi REPL sequenziali

PeerJS `DataConnection` ha default `reliable: true` (dalla 1.0 in poi); `peer.connect(host-id)` in [peer.cljs:218](src/ridley/sync/peer.cljs#L218) è chiamato **senza opzioni**, quindi accetta i default. Il default di `reliable: true` traduce in un `RTCDataChannel` configurato implicitamente come `ordered: true` con `maxRetransmits` non impostato (= retransmit illimitato fino a successo). Quindi:

- **Ordering**: garantito dal trasporto. Se l'host manda `repl-command A` e poi `repl-command B`, il client li vedrà nell'ordine `A, B`.
- **Drop**: con `reliable: true` il messaggio non viene perso. Solo se la connessione muore del tutto (NAT timeout, host destroyed) i messaggi in coda vanno persi assieme alla connessione, e il client cade in `:disconnected`.

Quindi `(def x 10)` seguito da `(def y (* x 2))` è sicuro. Il rischio non è ordinamento ma *evaluation context*: se il client si è collegato dopo `(def x 10)`, riceve solo l'initial `script-update` (= contenuto attuale dell'editor) e poi il successivo `(def y (* x 2))` come repl-command. Se `x` non era ancora nelle definitions quando l'editor è stato spedito, il `repl-command` fallirà sul client.

## C. Race condition e setup

### C1. Buffer iniziale del client

Sequenza all'`init` di un client che si auto-collega via `?peer=`:

1. `(or (load-from-storage) default-code)` → `initial-content` ([core.cljs:2391](src/ridley/core.cljs#L2391)).
2. Editor creato con `initial-content` ([core.cljs:2393-2400](src/ridley/core.cljs#L2393-L2400)).
3. `setup-sync` chiama `join-session` ([core.cljs:1266-1269](src/ridley/core.cljs#L1266-L1269)).
4. PeerJS `peer "open"` event → `.connect` verso l'host ([peer.cljs:216-219](src/ridley/sync/peer.cljs#L216-L219)).
5. `DataConnection "open"` sul client → status `:connected` ([peer.cljs:93-96](src/ridley/sync/peer.cljs#L93-L96)).
6. Lato host, `setup-host-connection-handlers` "open" fa `notify-client-connected` ([peer.cljs:62-67](src/ridley/sync/peer.cljs#L62-L67)) → invoca `on-client-connected` in [core.cljs:1215-1219](src/ridley/core.cljs#L1215-L1219) → spedisce il sorgente attuale al client.
7. Sul client arriva `script-update` → `on-script-received` ([core.cljs:1038-1044](src/ridley/core.cljs#L1038-L1044)) → `cm/set-value`, `save-to-storage`, `evaluate-definitions`.

**Cosa succede al buffer pre-esistente?** Viene **silenziosamente sovrascritto**: `cm/set-value` rimpiazza l'intero documento, `save-to-storage` rimpiazza la chiave `ridley-definitions` in localStorage. Nessun avviso, nessuna conferma, nessun backup. La storia REPL del client è invece *preservata*: vive in `command-history` ([core.cljs:44](src/ridley/core.cljs#L44)) e nessuno la tocca. Solo l'editor e localStorage perdono.

Per chi ha lavorato in solitaria sul Quest e poi si collega a una sessione desktop senza pensarci, è una mina.

### C2. Modifica concorrente al setup

L'iter "open → push initial → edit successivo" **non è atomico in un senso forte**. Vediamo:

- `on-client-connected` (host) gira sincrono nello stesso tick dell'evento `connection.open`.
- `send-script-to-connection` ([peer.cljs:244-250](src/ridley/sync/peer.cljs#L244-L250)) chiama `.send` immediatamente (sincrono lato JS).
- Se l'host edita *prima* dell'evento `open`, il debounce di 500 ms ([core.cljs:1029-1036](src/ridley/core.cljs#L1029-L1036)) potrebbe scadere prima o dopo l'open. Ma poiché `send-script-debounced` controlla `sync/connected?` *al momento dello scadere del timer* ([core.cljs:1027](src/ridley/core.cljs#L1027)), se il client non è ancora connesso il timer non spedisce nulla, e il successivo `on-client-connected` farà comunque il push iniziale del buffer corrente (che include l'edit). Quindi il caso "edit prima dell'open" è benigno: il client riceve un *unico* push iniziale che contiene già l'edit.
- Se l'host edita *dopo* l'open ma prima che il client abbia processato il primo `script-update`: il client riceve due `script-update` in sequenza, applica entrambi in ordine. Il secondo vince. Benigno.
- Se l'host edita *durante* l'arrivo del primo `script-update` sul client: il client mette in coda l'eval (vedi D3), poi processa il secondo `script-update` che mette in coda un'altra eval. Benigno ma sprecone.

Riassumendo: il setup non ha race condition di correttezza grazie al fatto che ogni `script-update` è un full-buffer-replace + re-eval. La proprietà "last write wins" lo rende robusto. È vulnerabile invece all'overwrite del buffer locale del client (C1).

### C3. Auto-join via `?peer=`

L'ordine in `init` ([core.cljs:2385-2492](src/ridley/core.cljs#L2385-L2492)):

1. `(load-from-storage)` → carica buffer locale (linea 2391).
2. Editor creato con buffer locale (2393-2400).
3. `setup-sync` (2455) → `(sync/get-peer-from-url)` letto dalla query string.
4. Se presente, `(sync/clear-peer-from-url)` lo rimuove (per evitare loop di auto-join al reload).
5. `(join-session peer-id)` (1269) — async: aspetta che il proprio `Peer` apra, poi `.connect`, poi attende `script-update`.

Ordine deterministico: localStorage **vince** all'inizio (l'utente vede il proprio buffer), poi quando arriva il `script-update` viene sovrascritto. Tra i due passaggi c'è una finestra in cui:
- l'editor mostra il buffer locale,
- il viewport mostra la scena del buffer locale (perché `evaluate-definitions` non viene chiamata in init? in realtà viene chiamata solo all'azione esplicita "Run" o quando il sorgente arriva via sync — quindi al primo avvio il viewport è vuoto fino al Run).

Quando arriva il push iniziale, `on-script-received` fa `evaluate-definitions` ([core.cljs:1044](src/ridley/core.cljs#L1044)) che ricostruisce la scena. Quindi l'ordine *visibile* all'utente è: buffer locale momentaneo → buffer remoto + scena rigenerata. Niente flash visibile della scena locale, perché non era stata valutata.

**`clear-peer-from-url` è importante**: senza, ogni reload triggererebbe nuovi join. Nota però che lo URL viene "ripulito" via `replaceState`, **prima** che il join abbia avuto successo. Se il join fallisce, l'utente non ha più traccia del peer-id nemmeno per riprovarci — gli rimane solo lo stato error nella status bar.

### C4. Disconnessione e re-edit del client

**La realtà è esattamente quella che hai descritto, e va detta nel capitolo.**

Catena degli eventi:
1. Network glitch → `DataConnection.close` → handler client ([peer.cljs:118-121](src/ridley/sync/peer.cljs#L118-L121)) → `set-status! :disconnected`, `:connection nil`.
2. Editor del client resta editabile (CodeMirror non cambia stato, nessuno chiama `cm/set-readonly` o equivalente — non esiste).
3. Il client può digitare. `on-change` chiama `save-to-storage` e `send-script-debounced`. Quest'ultima esce immediatamente perché `(sync/connected?)` è falsa ([core.cljs:1027](src/ridley/core.cljs#L1027)). Le modifiche vivono solo in localStorage.
4. **Per ricollegarsi**, il client deve ripremere Link e digitare il codice. Niente reconnect automatico (lo conferma anche [Architecture.md:1080](dev-docs/Architecture.md#L1080) come "Future Improvement"). L'host deve essere ancora vivo con lo stesso peer-id (cosa probabile: il `Peer` instance lato host non è chiuso, solo la `DataConnection` con quel client è morta).
5. Al ricollegamento, l'host pusha di nuovo il buffer corrente via `on-client-connected`. **Le modifiche locali del client sono sovrascritte.**

Stessa storia se il client riapre il browser: `?peer=` non c'è più nell'URL (è stato pulito), quindi non c'è auto-join. Il client deve fare Link manualmente. Se lo fa con lo stesso codice, riprende, e perde di nuovo le modifiche locali.

Per il capitolo: il client è strutturalmente *stateless* nel senso del modello. Le sue edit sono "rumore" che dura fino al prossimo push dell'host.

## D. Performance e backpressure

### D1. Byte/secondo nel caso peggiore

Buffer Ridley tipici: gli esempi in `examples/` vanno da ~50 a ~500 righe. `default-code` in [core.cljs:2375-2383](src/ridley/core.cljs#L2375-L2383) è una decina di righe. Una sessione "ricca" stiamo a 2000-5000 righe, ~50-150 KB di sorgente.

Caso peggiore reale, scrittura continua: un `script-update` ogni 500 ms, con buffer da 100 KB → ~200 KB/s = 1.6 Mbps in upload dall'host, moltiplicato per N client. PeerJS serializza in binary-pack di default (più compatto di JSON). Comunque scrittura continua *senza pause* è non realistica — il debounce significa che chi scrive a velocità umana naturale fa pause >500 ms, quindi i `script-update` partono come una raffica di "1 ogni qualche secondo".

Stima realistica: 10-50 KB/s sostenuti durante una sessione di live coding attivo, con picchi a 100-200 KB/s. WebRTC su LAN gestisce comodamente 10x questo. WAN tipica (1 Mbps upload) si comincia a soffrire solo con buffer enormi e scrittura ininterrotta — caso non plausibile.

Niente compressione applicativa, niente diff. Sarebbe l'ottimizzazione più ovvia se dovesse mai servire (è un debito teorico, non pratico).

### D2. Latenza percepita

- LAN: il debounce di 500 ms domina (50-100 ms di trasporto sono trascurabili). L'utente vede il client aggiornarsi entro ~700 ms dal proprio ultimo tasto.
- WAN: 100-300 ms di trasporto su connessioni residenziali, sempre dominate dal debounce.
- Senza TURN, WAN cross-NAT può semplicemente non collegarsi (ICE-fail). In quel caso non parliamo di latenza ma di non-connettività. PeerJS usa STUN Google (`stun.l.google.com:19302`) per default, niente TURN custom; per NAT-NAT simmetrico, fallisce.

**Backpressure / accumulo**: se il client è bloccato in valutazione SCI (es. SDF marching-cubes che ci mette 5 s), gli `script-update` successivi *non si perdono*: arrivano sul DataChannel (reliable, ordered), il browser li bufferizza al livello transport, e li consegna allo handler `data` quando l'event loop lo permette. Lo handler `data` ([peer.cljs:98-116](src/ridley/sync/peer.cljs#L98-L116)) sincronicamente chiama `on-script-received` → `cm/set-value` (sincrono, ms) → `save-to-storage` (sincrono, ms) → `evaluate-definitions`. Quest'ultima però è *quasi-sincrona*: schedula tramite `requestAnimationFrame` + `setTimeout 0` ([core.cljs:268-274](src/ridley/core.cljs#L268-L274)). Quindi:

- `cm/set-value` esegue subito → buffer aggiornato.
- L'eval reale è schedulata. Se ne arrivano 5 in rapida successione, ogni `set-value` sovrascrive il buffer; tutte e 5 le eval restano in coda.
- Quando l'event loop le processa, `evaluate-definitions-sci` legge `(cm/get-value @editor-view)` che è il buffer *corrente*, cioè quello dell'ultima `set-value`. Tutte e 5 le eval vedono lo stesso input, fanno lo stesso lavoro, producono lo stesso output. **5x lavoro, 1x risultato.**

In sintesi: niente perdita, ma può sprecare CPU. Su Quest con SDF pesante, una raffica di edit dell'host può tradursi in N rivalutazioni successive senza stutter intermedio (perché non c'è coalescing).

### D3. Editor del client durante la valutazione

SCI è sincrono. Mentre `sci/eval-string` gira, l'event loop è bloccato. Però `evaluate-definitions` è schedulata via `requestAnimationFrame` + `setTimeout 0` (vedi sopra), quindi *tra* il momento in cui arriva `script-update` e il momento in cui parte l'eval, l'event loop ha modo di prendere altri eventi. Significa che:

- L'utente sul client può cliccare bottoni *fra* uno `script-update` e l'eval seguente.
- Durante l'eval (eseguito da `setTimeout`), tutto è bloccato. La pagina si congela.
- Se durante quel blocco arrivano altri `script-update`, l'evento `data` non viene servito finché l'eval non finisce. Lo handler `data` non gira mai *durante* un'eval.

Quindi: niente preempzione, niente cancellazione di eval in corso. Le eval seguenti sono accumulate in coda e processate una a una. Nessun coalescing.

### D4. Modelli pesanti

**Asimmetria di hardware ignorata per design.** Niente `defresolution`-like, niente "downsampling per device" nel DSL. Il client è obbligato a generare la stessa scena.

Un mitigante implicito: se l'utente espone `desktop?` nel suo codice (vedi B1), può scrivere `(if (desktop?) (sdf-march :resolution 256) (sdf-march :resolution 64))`. Ma questo è un workaround del codice utente, non un'astrazione del sistema. E violerebbe la sync determinism (B1) volutamente.

Niente di più strutturato esiste: niente "host invia mesh pre-computata invece del sorgente al client lento", niente "client può richiedere bake-out", niente downsample automatico. Per il capitolo, questa è una *limitazione architetturale onesta*: la replica per sorgente è elegante ma non gestisce eterogeneità di hardware. Se il caso d'uso headset crescesse, sarebbe una direzione naturale (mandare al Quest mesh STL già bakeate dall'host invece del sorgente).

## E. Sicurezza e modello di trust

### E1. Modello di trust dichiarato

**Non c'è dichiarazione esplicita.** Il commit message di `28ff32f` parla di "real-time code sharing" come feature, non di sicurezza. La sezione "Limitations" in [Architecture.md:1070-1075](dev-docs/Architecture.md#L1070-L1075) elenca NAT, dipendenza signaling, no persistence, one-way sync — niente sulla sicurezza. Anche "Future Improvements" non menziona auth o permessi.

È quindi una **conseguenza implicita del design minimal**, non una scelta filosofica articolata. Per il capitolo direi: il modello è "trust everyone with the code", coerente con il fatto che Ridley è un personal CAD per uso individuale o pair-mode amichevole, ma è un *fatto del codice*, non una posizione esplicita degli autori.

Se serve un appiglio: il fatto stesso che il codice esponga `?peer=` come deep-link senza alcuna confirmation modale ("vuoi davvero unirti alla sessione X?") è la prova che si presume buona fede.

### E2. Brute-force del codice di sessione

Stima quantitativa più seria della fase 1.

- Spazio dei codici: 31^6 ≈ 887 milioni.
- PeerJS server pubblico ha rate limiting interno non documentato pubblicamente (è una policy del servizio gratuito di peerjs.com, non una garanzia). Empiricamente accetta connessioni a frequenza alta.
- Un attaccante che provi peer-id casuali fa: per ognuno, un `new Peer(); peer.connect(target-id)`. Tempo per tentativo ~200 ms (dipende da round-trip al signaling). Su 887M codici → ~5500 anni. Anche con 1000 tentativi/s → ~10 giorni.
- *Ma* lo spazio interessante non è tutto: l'attaccante deve indovinare un codice **attualmente attivo**. Una sessione tipica vive ore. Se ci sono N sessioni Ridley attive nel mondo, lo spazio efficace è 887M/N. Con N=10 sessioni attive a livello mondo (stima molto generosa per un hobby tool), 88M codici → ~1 giorno a 1000 t/s.

Realisticamente: una sessione lasciata aperta una notte, attaccata casualmente, ha *bassissima* probabilità di essere indovinata, ma *non è formalmente sicura*. Il modello regge solo perché Ridley non è abbastanza popolare per attirare attaccanti dedicati. Se il volume crescesse, il design del codice di sessione (6 caratteri) andrebbe esteso (10-12 caratteri = quattrini).

Difese applicative: **nessuna**. Nessun rate limiting lato Ridley, nessun lockout dopo N fallimenti, nessun token aggiuntivo. PeerJS server fa quel che fa.

### E3. Signaling server pubblico

`peer-config` è solo `{:debug 0}` ([peer.cljs:130-131](src/ridley/sync/peer.cljs#L130-L131)) → PeerJS usa `0.peerjs.com:443` per default.

- **Sessione esistente sopravvive se peerjs.com cade?** Sì, per la durata. WebRTC signaling serve solo all'handshake iniziale; una volta stabilito il DataChannel, il P2P è diretto e indipendente da peerjs.com. Le sessioni in essere continuano a funzionare. Una sessione esistente non può però accettare *nuovi* client se peerjs.com è down (perché il `.connect` lato client passa dal signaling).
- **Nuova sessione fallisce se peerjs.com è down?** Sì, completamente. `new Peer()` non emetterà mai `"open"`; lo stato resta `:connecting`/`:error`.
- **Per il capitolo**: dipendenza esterna chiara da segnalare. `peerjs.com` è un servizio gratuito senza SLA. È una scelta ragionevole per un MVP, ma è il *single point of failure* per onboarding. Se Ridley dovesse uscire da MVP, "Custom PeerJS server" ([Architecture.md:1081](dev-docs/Architecture.md#L1081)) diventa una vera priorità — non solo "per affidabilità" ma per *autonomia operativa*.
- Privacy: peerjs.com vede peer-id, indirizzi IP candidati ICE, SDP. Non vede il *contenuto* del DataChannel (cifrato DTLS). Ma sa chi si è collegato a chi e quando — meta-dati non triviali.

### E4. Tokenless join via URL

`?peer=ridley-XXXXXX` triggera `join-session` automaticamente all'init ([core.cljs:1266-1269](src/ridley/core.cljs#L1266-L1269)).

- **Se l'host non c'è più**: PeerJS emette `"error"` sul `peer.connect` (peer-unavailable). Lo handler `peer "error"` ([peer.cljs:221-223](src/ridley/sync/peer.cljs#L221-L223)) imposta `:status :error`. UI mostra "Error" sui bottoni Share/Link. Nessun fallback, nessun retry. L'utente vede solo "Error" e niente più. **Falimento silenzioso dal punto di vista dell'utente** (non c'è messaggio testuale "host non disponibile").
- **Se l'host c'è ancora ma con scope cambiato**: il peer-id è univoco, non c'è notion di "scope". Se per coincidenza un host ha generato lo stesso codice (probabilità molto bassa, 1/887M per round) si entra nella sua sessione. È una coincidenza catastrofica ma improbabile. Più realisticamente, se l'utente ha condiviso uno share-url ieri e l'host ha riavviato la sessione oggi (nuovo peer-id generato, vecchio peer-id morto), il caso ricade in "host non c'è più" → :error.
- **Mitigation**: il peer-id viene rigenerato a ogni `host-session` (`generate-peer-id` ritorna sempre random, [peer.cljs:27-28](src/ridley/sync/peer.cljs#L27-L28)). Quindi un share-url ha vita limitata alla sessione che l'ha emesso. Una volta che l'host chiude e riapre, lo URL è stale. Come per E2, regge per assenza di volume; non è una feature di sicurezza intenzionale.

## F. Cose che cap. 14 dovrebbe raccogliere

Lista del debito tecnico specifico del canale, organizzata per categoria.

### F1. Codice morto / scaffolding non finito

- `:on-script-received` lato host accettata ma mai dispatchata ([peer.cljs:69-76](src/ridley/sync/peer.cljs#L69-L76), [peer.cljs:141](src/ridley/sync/peer.cljs#L141)). Da rimuovere o implementare. Se si vuole tenere il design simmetrico per il futuro, almeno commentare esplicitamente nel docstring; se si abbandona, eliminare il param.
- `send-ping` ([peer.cljs:264-269](src/ridley/sync/peer.cljs#L264-L269)) e l'handling di `pong`/`ping` ([peer.cljs:75](src/ridley/sync/peer.cljs#L75), [peer.cljs:113-114](src/ridley/sync/peer.cljs#L113-L114)): nessun call site, nessun timer. Decidere: cablare un keepalive (vedi F2) o eliminare.
- `generate-qr-code` ([peer.cljs:306-320](src/ridley/sync/peer.cljs#L306-L320)) e dipendenza `qrcode` in `package.json`: cablata zero. Cablare nel modale Share o eliminare la dep (in package.json risparmia un node_module).
- `generate-share-url` ([peer.cljs:301-304](src/ridley/sync/peer.cljs#L301-L304)): helper non chiamato. Stesso destino del QR.
- Documentazione di `host-session` ([peer.cljs:137](src/ridley/sync/peer.cljs#L137)): "called when client sends script" è una promessa non mantenuta dal codice, da rimuovere o adeguare.

### F2. Robustezza di rete

- **Reconnect automatico**: dichiarato come "Future Improvement" in [Architecture.md:1080](dev-docs/Architecture.md#L1080). Su un Quest che entra in sleep brevemente, oggi cade tutto e l'utente deve digitare di nuovo il codice. Implementabile lato client come retry con backoff su `connection.close` finché lo status non è esplicitamente `disconnect`.
- **Keepalive**: `send-ping` esiste, manca solo `setInterval` lato host con `(send-ping)` ogni 20-30 s. Senza, NAT timeout (tipicamente 60-180 s di inattività) chiude i canali idle e l'host non se ne accorge finché non manda qualcosa. Con keepalive, il client si accorge subito della disconnessione e può tentare reconnect.
- **TURN server**: nessun `iceServers` custom. NAT-simmetrico o carrier-grade NAT impediscono la connessione. Aggiungere un TURN (anche gratuito tipo OpenRelay, o uno self-hosted con coturn) è un add-one-line al `peer-config`.
- **Custom PeerJS server**: per evitare la dipendenza da peerjs.com (E3). Self-hostabile, ma operazionale.
- **Buffer iniziale del client overwrite senza warning** (C1): aggiungere conferma utente o backup-on-overwrite in localStorage.

### F3. Sicurezza

- **Autenticazione/autorizzazione**: nessuna. Per uso non-trusted serve almeno un token applicativo nel modale Share da inserire nel modale Link, oltre al peer-id.
- **Read-only client**: niente lo prevede. Per il caso desktop-viewer (A2) sarebbe naturale che il client sia messo in read-only nell'editor finché è connesso.
- **Codice di sessione più lungo** (E2): se mai serve protezione vs brute-force, allungare a 10-12 caratteri.
- **Confirmation su deep-link** (E4): un modale "Stai per unirti alla sessione X, continua?" prima di auto-joinare evita join accidentali da link condivisi al di fuori del contesto.

### F4. UX

- **Banner "you are a viewer"** sul client: oggi non c'è alcun segnale visivo che il proprio buffer è autoritativo solo per pochi secondi. Critico per il caso desktop-viewer (A2).
- **Cursore/selezione remoti**: in modalità collaborative-light, sapere dove sta scrivendo l'host. Niente di tutto questo oggi.
- **QR code nel modale Share** (A1): scaffolding pronto, manca cablatura.
- **Errori dettagliati**: oggi il client mostra solo "Error" sui bottoni quando il join fallisce ([core.cljs:1126-1132](src/ridley/core.cljs#L1126-L1132)). Distinguere "host non trovato", "rete down", "peer destroyed" aiuterebbe.
- **Storia REPL su client** dopo `repl-command`: oggi viene aggiunta come se fosse digitata localmente; potrebbe essere visivamente marcata come "remoto" per non confondere con i propri comandi locali.

### F5. Determinismo

- **Librerie attive divergenti** (B1): documentare che host e client devono avere lo stesso set di librerie attive, oppure spedire `lib-ns` come parte del payload `script-update`.
- **`desktop?`/`env` nel DSL** (B1): contraddice la sync. O proibirlo nel sandbox quando si è client, o accettare che è fonte di divergenza intenzionale.
- **Font caricato in tempo** (B1): bloccare `evaluate-definitions` su un `await` di `init-default-font!` se non ancora pronto. Una riga.
- **Verifica di convergenza** (B2): un comando `/sync-check` che calcoli un hash della scena su host e client e lo confronti. Diagnostic, non production-critical.

### F6. Performance

- **Coalescing delle eval in coda** (D2/D3): se 3 `script-update` arrivano mentre il client sta ancora valutando il primo, le successive 2 dovrebbero collassare in una sola. Oggi è 3 eval su input identico (ultimo).
- **Diff invece di full buffer** (D1): se buffer crescono molto, mandare solo le righe cambiate (operational transform leggera o anche solo prefix/suffix unchanged + diff). Non urgente, ma è il punto naturale di crescita se mai si va in WAN ad alta latenza.
- **Mesh STL invece di sorgente per client lenti** (D4): cambia il modello da "replica del DSL" a "rendering distribuito". Grosso intervento.

## G. Coda libera

### G1. Tre cose da raccontare nel capitolo che le mie domande non hanno coperto

**1. Il modello "replica per sorgente" è di fatto una scelta di cap. 5.** La sync funziona perché SCI è puro abbastanza, deterministico abbastanza, e veloce abbastanza da accettare un re-eval completo a ogni messaggio. Se il DSL fosse stato mutable-state-heavy, o se la valutazione fosse stata costosa (Manifold WASM 2 secondi a partire), il design avrebbe dovuto essere diverso. **Il fatto che si sincronizzi il sorgente e non la mesh è una conseguenza dell'architettura SCI** descritta in cap. 5, non una decisione indipendente del cap. 10. Vale la pena dirlo: i due capitoli si appoggiano a vicenda.

**2. La rimozione del JVM (commit `f990f70`, 2026-04-22, registrata in MEMORY.md) ha *aumentato* le garanzie di determinismo della sync.** Quando il backend nativo Manifold girava su JVM e non su WASM, host (desktop con JVM) e client (Quest senza JVM) usavano *backend diversi* per le CSG. Le mesh prodotte potevano divergere. Oggi entrambi usano Manifold WASM in browser, stessa libreria, stessa versione, stessa precisione. La sync è oggi più affidabile di quanto lo fosse 6 mesi fa, ma né il codice né la prosa lo dicono. È una sinergia accidentale fra cap. 9 (desktop) e cap. 10 (sync) che merita una nota.

**3. La sync non è integrata con cap. 8 (stato condiviso) come ci si aspetterebbe.** `peer-state` ([peer.cljs:9-18](src/ridley/sync/peer.cljs#L9-L18)) e `sync-mode` ([core.cljs:48](src/ridley/core.cljs#L48)) sono atom ma **vivono fuori** dal modello "registry/animations/turtle-state" del cap. 8. Non sono mai serializzati, non sono mai sincronizzati fra host e client, non partecipano al ciclo di refresh del viewport. Sono *stato di trasporto*, non *stato del modello*. Questa segregazione è corretta e intenzionale, ma è un'asimmetria che il capitolo 8 non racconta e il capitolo 10 dà per scontata. Vale la pena un paragrafo che dica: "il canale è dichiaratamente sotto il modello, non dentro". Questa scelta lo rende anche refactorizzabile: se domani la replica diventa CRDT-based, il cap. 8 non cambia di una riga.

### G2. Bonus: il legame con cap. 6

Cap. 6 (motore geometrico) descrive come la mesh viene calcolata. La sync **non** trasporta mesh — trasporta sorgente — ma assume che lo stesso sorgente produca la stessa mesh sui due lati. Questa è una *assunzione del cap. 10 sul cap. 6*: "il motore geometrico è una funzione pura del sorgente". Vale finché è vero. Se cap. 6 introducesse caching disco, GPU-accelerated CSG con fall-back software differenziato, o Manifold native side-by-side a WASM, la sync diventerebbe non-deterministica fra device. È un *vincolo implicito* che andrebbe documentato in entrambe le direzioni.

---

Materiale per il capitolo, fine fase 2.
