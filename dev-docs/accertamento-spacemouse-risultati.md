# Accertamento SpaceMouse — risultati

Risposte a `dev-docs/Accertamento spacemouse.md`, nell'ordine dei punti. Data: 2026-11-01.

**Stato in una riga.** Tutti i punti misurati, salvo il comportamento con 3DxWare (non installato) e il lettore dentro `cargo tauri dev`. Due ipotesi del design risultano false: traslazione e rotazione arrivano in **due report separati**, non in uno da 12 byte; e un filtro sull'asse dominante puro **sbaglia asse** sui gesti avanti/indietro, che inclinano il cappuccio quanto lo spostano. Vincolo nuovo: senza 3DxWare **macOS usa lo SpaceMouse come mouse** (il puntatore si muove, spingere giù clicca). **L'apertura esclusiva lo impedisce**, è verificata, e non chiede permessi. Il fatto più importante sul lato Ridley è al punto 11: il design "un comando discreto per soglia" con la preview attuale non regge, perché ogni comando rivaluta l'intero script e il debounce da 80 ms non scatta mai sotto un flusso continuo.

Nessuna modifica al codice di produzione. Gli unici file creati fuori da `dev-docs/` sono in `scratch/`, escluso da git solo in locale (`.git/info/exclude`):

- `scratch/spacemouse-logger/` — logger Rust (`hidapi` 2.6.7, compilato e provato a vuoto)
- `scratch/webhid-probe.js` — sonda WebHID da incollare nella console di Chrome

---

## A. Dispositivo e sistema operativo

Misure del 2026-11-01. SpaceMouse Compact collegato via USB (attraverso un hub), 3DxWare non installato, logger in modalità condivisa. Log completo: `scratch/spacemouse-logger/guided-1793540161.log` (4 234 righe, 18 passi guidati). Analisi per passo fatta con uno script usa-e-getta (mediana di ogni asse sulla metà centrale dei campioni del passo).

### 1. Formato dei report — **misurato**

**Product ID confermato `0xC635`.** Il device espone una sola interfaccia, ma tre coppie di usage:

```
$ spacemouse-logger list
vid=0x256F pid=0xC635 usage_page=0x0001 usage=0x0008 iface=0 product=Some("SpaceMouse Compact") manufacturer=Some("3Dconnexion") …
vid=0x256F pid=0xC635 usage_page=0x0001 usage=0x0030 iface=0 …
vid=0x256F pid=0xC635 usage_page=0x0001 usage=0x0033 iface=0 …
```

(1/8 = Multi-axis Controller, 1/0x30 = X, 1/0x33 = Rx. Hanno lo stesso `path`: è un solo device. Va aperto quello con usage 8.)

**Report ID usati: 1, 2, 3.** L'ipotesi "un unico report da 12 byte" è **falsa** per questa unità: traslazione e rotazione arrivano in **due report separati da 7 byte**, alternati.

| ID | lunghezza | contenuto |
|---|---|---|
| `01` | 7 byte | ID + tx, ty, tz come int16 little-endian con segno |
| `02` | 7 byte | ID + rx, ry, rz come int16 little-endian con segno |
| `03` | 3 byte | ID + bitmask dei bottoni (byte 1) + un byte a 0 |

Il lettore deve quindi tenere lo stato dei sei assi e aggiornarne metà a ogni report. `MaxInputReportSize = 7` nel registro di sistema conferma la lunghezza.

Estratto grezzo (passo "spingi a destra"):
```
   44179.8  id=01  len= 7  01 5E 01 00 00 27 01  | int16le: 350 0 295
   44187.7  id=02  len= 7  02 00 00 88 FF 1F 00  | int16le: 0 -120 31
```

**Assi e segni rispetto al gesto fisico** (asse dominante del passo, valore stabile):

| gesto | asse dominante | valore stabile |
|---|---|---|
| spingi a destra | tx **+** | +344 |
| spingi a sinistra | tx **−** | −322 |
| spingi in avanti (lontano da sé) | ty **−** | −305 |
| tira indietro (verso di sé) | ty **+** | +275 *(ma rx +305 è più grande, vedi sotto)* |
| solleva | tz **−** | −350 |
| premi giù | tz **+** | +350 |
| inclina in avanti (bordo lontano giù) | rx **−** | −350 |
| inclina indietro (bordo vicino giù) | rx **+** | +350 |
| inclina a destra (bordo destro giù) | ry **−** | −304 |
| inclina a sinistra (bordo sinistro giù) | ry **+** | +350 |
| ruota in senso orario (visto dall'alto) | rz **+** | +350 |
| ruota in senso antiorario | rz **−** | −339 |

Il sistema è coerente: **X a destra, Y verso l'utente, Z verso il basso** (destrorso), e le rotazioni seguono la regola della mano destra sugli stessi assi (rx+ porta il bordo vicino giù, ry+ alza il bordo destro, rz+ è orario visto dall'alto). Per Ridley, che ha Z in alto, servirà una conversione fissa: y e z invertiti, e lo stesso per ry e rz.

**Range: ±350.** Tutti e sei gli assi toccano esattamente 350 in valore assoluto e lì si fermano (saturazione; massimo sul passo "a fondo corsa" e sui gesti singoli). In pratica è un range simmetrico −350…+350.

**Accoppiamento fra assi: forte, ed è il fatto più utile per il filtro.** Un gesto "puro" non produce mai un asse solo:

```
spingi a destra   tx=+344  tz=+217  ry=−206
spingi in avanti  ty=−305  rx=−272  tz=+115
tira indietro     ty=+275  rx=+305          ← la rotazione SUPERA la traslazione
premi giù         tz=+350  tutto il resto 0
```

- Spingere il cappuccio lo inclina anche: ty si accoppia a rx al 90-110%, tx a ry al 40-60%.
- Quasi tutti i gesti hanno anche tz positivo (+55…+277): la mano preme verso il basso mentre spinge.
- Conseguenza per il design: **un filtro "asse dominante" puro avrebbe sbagliato asse in 1 gesto su 12** (tira indietro → avrebbe emesso una rotazione) e ci è andato vicino in un altro (spingi avanti, 305 contro 272). Servono soglie distinte per traslazione e rotazione, oppure la scelta fra "modo traslazione" e "modo rotazione" deve essere esplicita e non dedotta. La nota di Vincenzo («forse ho confuso gli ultimi due step») riguarda i bottoni, non questi passi: le direzioni dominanti dei 12 gesti corrispondono tutte alle istruzioni.

**Bottoni:** report `03`, bitmask nel byte 1. **Bit 0 = sinistro, bit 1 = destro, 3 = entrambi.** Un report alla pressione e uno al rilascio (`03 00 00`):
```
  192852.9  id=03  len= 3  03 01 00     sinistro premuto
  193148.8  id=03  len= 3  03 00 00     rilasciato
  221796.9  id=03  len= 3  03 02 00     destro premuto
  227404.9  id=03  len= 3  03 03 00     entrambi
```
Premere un bottone muove anche un po' il puck (nei passi dei bottoni compaiono 22-28 report di assi, fino a ±52). In sé è trascurabile, ma significa che il click deve essere ignorato come movimento. I passi "entrambi" e "secondo riposo" si sono sovrapposti (come aveva sospettato Vincenzo): le due pressioni di entrambi i bottoni sono finite nella finestra successiva. Il dato `03` resta comunque inequivocabile.

### 2. Frequenza — **misurato**

- **Con il puck deflesso: 125 report/s in tutto**, cioè `01` ogni 16 ms e `02` ogni 16 ms, sfasati di 8 ms (mediana degli intervalli fra due `01` consecutivi = 16.0 ms in tutti i passi). Il registro di sistema dichiara `ReportInterval = 8000` µs, coerente. Nei passi da 3 s sono arrivati 200-375 report, perché il gesto inizia qualche istante dopo l'INVIO.
- **Il device trasmette anche se il valore non cambia**, finché il puck non è al centro: tenendolo fermo deflesso arrivano report identici a 125/s.
- **A riposo non trasmette niente:** il passo "riposo" ha 0 report in 5 s (`[no report for 12000 ms]`). Quando il puck torna al centro, il device manda **6-8 report a zero** (3-4 per ID) e poi tace:
  ```
  gap 64ms   dopo 6 report a zero
  gap 160ms  dopo 7 report a zero
  gap 22536ms dopo 6 report a zero
  ```
  Per il lettore: "puck a riposo" = ultimo stato tutto a zero. Il silenzio non va interpretato come "ultimo valore ancora valido".

### 3. Driver 3DxWare — **misurato: NON installato; c'è invece il driver di sistema di macOS**

```
$ ls "/Library/Application Support/3Dconnexion"   → No such file or directory
$ pgrep -lf -i 3dconnexion                          → niente
```

Senza 3DxWare, `hidapi` riceve dati senza problemi, sia in modalità condivisa sia esclusiva (in esclusiva provata solo l'apertura, vedi sotto). Il caso "driver 3DxWare attivo" su questa macchina non è provabile senza installarlo.

**Fatto nuovo e importante:** anche senza 3DxWare, **macOS non lascia il device libero.** Il suo driver HID di sistema lo aggancia come dispositivo di input:

```
$ hidutil list | grep 0x256f
0x256f 0xc635 … 1 8 … USB  AppleUserHIDEventService  SpaceMouse Compact  AppleUserHIDEventDriver
$ ioreg … (estratto)
"IOMatchedPersonality" = {"IOClass"="AppleUserHIDEventService", …,
    "DeviceUsagePairs"=({…Usage=6},{…Usage=7},{…Usage=8},{…Usage=128}), …}
"DeviceOpenedByEventSystem" = Yes
```

La personality di Apple elenca esplicitamente l'usage 8 (Multi-axis Controller). **Effetto osservato da Vincenzo, senza nessun nostro programma aperto: il puck muove il puntatore come un mouse, e spingerlo verso il basso clicca o trascina; i due bottoni non fanno nulla.** Per Ridley è un problema reale: mentre l'utente pilota un oggetto, il puntatore se ne va e la pressione verso il basso clicca dove capita.

**La contromisura funziona: l'apertura esclusiva** (seize, `hid_darwin_set_open_exclusive(1)`, `--exclusive` nel logger).

```
$ spacemouse-logger raw 20 --exclusive > esclusivo.log
      11.4  [opened]
      … 809 report id=01 + 809 report id=02, len 7, intervallo id=01 = 16 ms (708 su 769)
```

- **Durante l'apertura esclusiva il puntatore non si muove più e non ci sono click** (osservato da Vincenzo, puck mosso sopra altre finestre).
- I report arrivano identici a quelli in modalità condivisa: stessi ID, stessa lunghezza, stessa frequenza.
- Nessuna richiesta di permessi.
- Conseguenza per il design: **la build desktop deve aprire il device in esclusiva**, e solo mentre serve (sessione edit-attach o navigazione col puck attiva). Tenuto aperto per tutta la vita dell'app, lo SpaceMouse smetterebbe di funzionare come mouse anche nelle altre app. Il crate `hidapi` lo espone come `HidApi::set_open_exclusive(true)` (feature `macos-shared-device`) prima di `open_device`.

### 4. Permessi macOS — **misurato da terminale; resto da fare**

- **Da terminale: nessuna richiesta di permessi**, né in apertura condivisa (log guidato, riferito da Vincenzo) né in esclusiva (provata qui). Il terminale potrebbe avere già un permesso di Input Monitoring concesso in passato; il dato vale per "Terminale su questo Mac".
- `cargo tauri dev`: non provato (servirebbe aggiungere il lettore al binario dell'app, su un branch).
- **Bundle firmato: oggi non esiste.** Il DMG di CI non è firmato e non usa l'hardened runtime (`desktop/src-tauri/Info.plist:17-21`, commento sull'entitlement della camera: «needed only if this app is ever SIGNED with the hardened runtime (it is not today)»). Per lo stesso motivo non c'è un file entitlements (`bundle.macOS.entitlements` assente in `tauri.conf.json`). La domanda "servono entitlement" diventa reale solo il giorno in cui si firma.

### 5. Hotplug — **misurato**

```
$ spacemouse-logger raw 40 > hotplug.log
    3748.6  id=02  len= 7  02 00 00 00 00 00 00 …     ultimo report prima dello stacco
    7057.0  [read error: hidapi error: hid_read_timeout:  error waiting for more data] — device gone? reopening
    7365.5  [open failed: no device] retrying           (× 13, ogni ~500 ms)
   13965.4  [opened]
   14588.9  id=01  len= 7  01 00 00 00 00 00 00 …     primo report dopo il ricollegamento
```

- **Lo stacco si manifesta come errore di lettura**, non come silenzio: `hid_read_timeout` restituisce un errore e l'handle va buttato. Il segnale è affidabile; non serve un watchdog sul silenzio, che del resto a riposo è normale (punto 2).
- **Il riaggancio funziona riaprendo da capo**: enumerazione + `open_device` riescono non appena il device ricompare. La latenza fra il ricollegamento fisico e `[opened]` non è misurabile da questo log (l'istante del ricollegamento non è registrato); è comunque limitata dal polling a 500 ms più il tempo di enumerazione di macOS.
- Dopo la riapertura i report riprendono normalmente. Il thread di lettura nell'app deve quindi essere un ciclo apri → leggi fino all'errore → riprova, esattamente come `reader_loop`. In esclusiva la riapertura va rifatta in esclusiva.

### 6. WebHID nella build browser — **misurato**

Sonda `scratch/webhid-probe.js` in Chrome su `localhost:9000`, log salvato in `scratch/webhid.txt` (6 832 righe).

```
[webhid] requestDevice returned 1 device(s)
[webhid] opened SpaceMouse Compact vid=0x256f pid=0xc635 collections=[{"usagePage":1,"usage":8,"inputReports":[3,2,1]}]
   39871.9  id=01  len= 7  01 00 00 01 00 00 00  | int16le: 0 1 0
   39878.1  id=02  len= 7  02 00 00 00 00 00 00  | int16le: 0 0 0
   55046.2  id=03  len= 3  03 01 00  | int16le: 1
   …
[webhid] disconnect SpaceMouse Compact
```

- **Sì, `requestDevice` lo vede.** Il device dichiara una sola collection 1/8 con input report 1, 2, 3.
- **I report coincidono con `hidapi`**: stessi ID (3 408 `01`, 3 408 `02`, 15 `03`), stessa lunghezza, stessa codifica, range ±350, `01` ogni 16 ms (3 316 intervalli su 3 316 a 16 ms arrotondati). Bottoni con lo stesso bitmask (1, 2, 3). La sonda ricostruisce il byte di ID da `e.reportId`: WebHID consegna il payload senza ID, quindi il codice condiviso deve tenerne conto.
- **Lo scollegamento arriva come evento `disconnect`** su `navigator.hid`.
- **Con 3DxWare attivo:** non provabile (non installato).
- **Verificato (Vincenzo, 2026-11-01): Chrome NON lo prende in esclusiva.** Con la sonda WebHID attiva il puntatore di sistema continua a muoversi. Peggio: col bottone 1 premuto il puck fa orbitare il viewport di Ridley, col bottone 2 lo fa scorrere (pan). È l'emulazione mouse di macOS (punto 3) che arriva a TrackballControls come un normale trascinamento, in parallelo ai report WebHID. WebHID non ha un'opzione per l'apertura esclusiva.

> **Decisione (Vincenzo, 2026-11-01): supporto SpaceMouse SOLO nell'app desktop.** Nel browser i due canali (report WebHID + mouse emulato da macOS) agirebbero insieme sulla stessa scena, e non c'è modo di spegnere il secondo. Niente WebHID: il livello di input legge solo da Rust (`hidapi`, apertura esclusiva).
- Fatto indipendente dal device: la build desktop gira in WKWebView, che non implementa WebHID. Lì l'unica strada è la lettura lato Rust.

### Cosa resta della parte A

- Il comportamento con 3DxWare installato (punto 3): non provato, il driver non c'è.
- `cargo tauri dev` (punto 4): non provato, servirebbe agganciare il lettore all'app su un branch. Il terminale non chiede permessi né in condivisa né in esclusiva.

---

## B. Tauri

### 7. Versione e struttura — **misurato**

- **Tauri v2**: `desktop/src-tauri/Cargo.toml` → `tauri = { version = "2", … }`; `Cargo.lock` → `tauri 2.10.3`.
- `src-tauri/src/` = `main.rs` (207 righe), `geo_server.rs` (558), `sdf_ops.rs` (339).
- **Non esiste nessun comando né evento Tauri.** In `main.rs` non c'è `invoke_handler`, `#[tauri::command]` né `emit`. Il frontend non usa `window.__TAURI__` (in `src/` nessuna occorrenza fuori dai commenti). Tutta la comunicazione frontend↔Rust passa per HTTP: il geo-server `tiny_http` su `127.0.0.1:12321..12330` (`geo_server.rs:325-370`), con la porta iniettata nella webview via `initialization_script` (`main.rs:144-145, 178`). Lo SpaceMouse sarebbe quindi il **primo** uso di eventi Tauri nel progetto.
- Dove va il thread di lettura: in `main.rs` dentro `.setup(move |app| …)` (`main.rs:172-191`), dove si ha l'`AppHandle`. Un `std::thread::spawn` che possiede un `app.handle().clone()` e fa `handle.emit("spacemouse", payload)`, con il trait `tauri::Emitter` in scope. È lo stesso schema con cui il geo-server tiene il proprio thread (`geo_server.rs:352`).
- Permessi v2: **non serve aggiungere nulla.** Emettere da Rust non passa dall'ACL. Per ascoltare dal frontend (`window.__TAURI__.event.listen`, disponibile perché `withGlobalTauri: true` in `tauri.conf.json`) serve `core:event:allow-listen`, che è già incluso. Evidenza da `gen/schemas/acl-manifests.json`:
  ```
  core:default       -> [… 'core:event:default', …]
  core:event:default -> ['allow-listen', 'allow-unlisten', 'allow-emit', 'allow-emit-to']
  ```
  e `capabilities/default.json` concede `core:default` a `"windows": ["*"]`.
- Alternativa senza eventi: il frontend già interroga il geo-server via HTTP, e il thread potrebbe esporre l'ultimo stato del puck come endpoint da leggere a ogni frame. Non la raccomando: aggiunge un round-trip per frame.

### 8. Il frontend regge un emit per report? — **parzialmente misurato**

- L'emit Tauri in sé non è misurato: richiederebbe l'aggancio nel binario dell'app e, per avere senso, la frequenza reale del punto 2.
- **Misurato invece il fattore che domina:** quanto aspetta un messaggio in coda mentre il thread principale è occupato. Ho simulato input a 125 Hz (un `MessageChannel`, cioè un task in coda come un evento IPC) durante quattro preview edit-attach sulla scena "pesante" del punto 11:
  ```
  inputMsgs 77   latenza mediana 0 ms   p95 217 ms   max 237 ms
  ```
  A thread libero la consegna è immediata. Durante una rivalutazione ogni messaggio aspetta che finisca, e poi arrivano tutti insieme. Il problema non è la frequenza dell'emit ma le rivalutazioni sincrone (punto 11).
- Conseguenza per il design: serve **coalescenza**, non un semplice throttling. O lato Rust (emettere l'ultimo stato a ≤60 Hz invece di ogni report), o lato frontend (l'handler scrive soltanto "ultimo stato del puck" in un atom e il frame loop lo consuma). La seconda serve comunque, perché anche con l'emit limitato una rivalutazione da 200 ms accumula una raffica. Con eventi che portano posizioni assolute del puck, non incrementi, la raffica si riduce a "l'ultimo vince" e la coda non produce errori, solo ritardo.

---

## C. Ridley

### 9. edit-attach — **misurato**

- **Nome e file:** sessione `edit-attach`, `src/ridley/editor/edit_attach.cljs` (890 righe). **Sì, è la stessa cosa di `pilot`:** `pilot` è una macro alias che chiama lo stesso `edit-attach-request!` con corpo vuoto (`src/ridley/editor/macros.cljs:2162-2169`; docstring del ns, `edit_attach.cljs:9-10`; `Architecture.md:1655`).
  - *Deriva di documentazione trovata:* `Spec.md:4315` elenca una funzione `(pilot-request! mesh opts)` che nei binding non esiste più. C'è solo `edit-attach-request!` (`bindings.cljs:656`).
- **Stato:** `(defonce session (atom nil))` (`edit_attach.cljs:28`), schema documentato alle righe 30-51: `:commands`, `:orig-commands`, `:step` (5), `:angle-step` (15), `:scale-step` (1.1), `:digit-target`, `:gizmo-mode`, `:creation-pose`, `:world-xform`, offset del marker, ecc. Inizializzato in `request!` (`:787-803`).
- **Tasti → comandi:** `on-edit-attach-keydown` (`:549-697`). È modale: **Tab** cicla `:digit-target` fra step/angle/scale, e le **stesse frecce** fanno cose diverse a seconda del modo: STEP ↑↓ = `f`, ←→ = `rt`, Shift ↑↓ = `u`; ANGLE ↑↓ = `tv`, ←→ = `th`, Shift = `tr`; SCALE = `[:scale [a b c]]`. Alt+frecce = `cp-f`/`cp-rt`/`cp-u`. Ogni ramo chiama `(add-command! cmd value)`.
  - *Incongruenza minore trovata:* il messaggio di ingresso dice «Shift+arrows to rotate» (`:861`), ma Shift+frecce in modo STEP dà `u` (traslazione). Per ruotare serve Tab → ANGLE.
- **Step:** valori nella sessione; le cifre digitate li cambiano (`flush-digit-buffer!`, `:534-547`), che li passa anche al gizmo (`gizmo/set-snap!`). Il valore emesso è lo step stesso con segno: un tasto = un comando di ampiezza `step`/`angle-step`.
- **Compattazione:** `compact-commands` (`:86-119`), **solo alla conferma** (`confirm!`, `:383`). Durante la sessione `:commands` cresce di un elemento per comando, e anche il pannello mostra la lista non compattata (misurato: dieci `(f 5)` restano dieci `(f 5)` finché non si conferma). Per il puck significa che centinaia di micro-comandi vivono nella lista per tutta la sessione e vengono rivalutati a ogni preview. Si fondono solo alla fine.
- **Cosa succede nel viewport a ogni comando:** `add-command!` → `debounced-eval!` (80 ms, `:337-346`) → `eval-with-commands!`. In modalità script (`:305-323`) questo **rivaluta l'intero script** con il marker sostituito (`modal/reeval-script!`: `registry/clear-all!`, reset turtle, `sci/eval-string` di tutto, push scena; `modal_evaluator.cljs:348-378`). Poi valuta **una seconda volta** la sola espressione sostitutiva, per la wireframe preview, e la porta in posa mondo (`show-preview!`, `:260-268`). È una rivalutazione vera, non un wireframe trasformato.
  - Il gizmo invece **durante il drag** non rivaluta niente: chiama `viewport/nudge-preview!` (`gizmo.cljs:527-528, 545-546`; `viewport/core.cljs:2693-2713`), che cambia la matrice degli oggetti di preview, ed emette un solo `on-commit` → `add-command!` al rilascio (`gizmo.cljs:781-801`).

### 10. Layer modal_evaluator e input non da tastiera — **misurato**

- Installazione: `modal/install-keydown!` = `document.addEventListener("keydown", handler, capture=true)` (`modal_evaluator.cljs:203-210`), chiamato da `enter!` di edit-attach (`edit_attach.cljs:825-827`), rimosso in `cleanup!` (`:372`).
- **Un ingresso non da tastiera esiste già, ed è il gizmo:** `gizmo/enter!` riceve `{:on-commit add-command!}` (`edit_attach.cljs:840-845`). Il gizmo non sintetizza tasti: chiama direttamente la funzione che appende un comando. La sessione quindi **non** è legata a `keydown`. È legata a `add-command!`, che è privata (`defn-`, `:348`).
- Cosa servirebbe per una terza sorgente senza duplicare logica: fare come il gizmo, cioè esporre `add-command!` (o un `on-commit` registrabile) invece di fabbricare `KeyboardEvent`. Fabbricare eventi tastiera sarebbe la strada sbagliata: la mappa tasti è **modale** (Tab), quindi un "freccia su" sintetico produce `f`, `tv` o `scale` a seconda del modo corrente, e la sorgente dovrebbe conoscere e pilotare il modo.
  Per il puck il modello giusto è probabilmente quello del gizmo, non quello della tastiera: **anteprima rigida a costo zero mentre il puck è deflesso** (`nudge-preview!` + `gizmo/update-pose!`) e **commit di un comando (o pochi) quando il puck torna a riposo**. Il punto 11 mostra perché.
- Il layer non ha un concetto di "sorgente di input" generico: `install-keydown!` è l'unica primitiva di input. Il livello di input SpaceMouse andrebbe agganciato per sessione (come `:on-commit`), oppure in un registro di callback per tipo di sessione analogo a `register-kind!` (`modal_evaluator.cljs:396-407`).

### 11. Costo per comando — **misurato** (Chrome via Playwright, `localhost:9000`, build dev `:simple`)

Tempo sincrono di `eval-with-commands!` per un comando (rivalutazione completa + seconda valutazione + preview), 10 comandi per scena:

| scena | vertici in scena | ms per comando (10 campioni) | mediana |
|---|---|---|---|
| `(box 20)` | 8 | 15.6 7.6 6.2 5.6 5.2 5.1 4.0 3.9 3.6 4.4 | **5** |
| box − 2 cyl, + union sphere/box | 2 255 | 70 42.8 40.9 40.4 40.1 40.1 39.6 39.9 40 40 | **40** |
| sphere − union di 12 cyl, + union di 8 sphere | 7 462 | 242 211 214 213 216 233 226 232 227 232 | **227** |
| sphere 128×64 − union di 24 cyl | 33 893 | 5959 … 6227 | **6 112** |

Il costo non è quello della preview: è quello **dell'intero script**, perché ogni comando lo rivaluta tutto, CSG compresi. Il render di un frame dopo la preview resta sui 16 ms (due rAF = 15-16 ms).

Comportamento del debounce sotto un flusso continuo (scena banale, 20 `add-command!` a cadenza fissa, istanti delle valutazioni effettive):

```
ogni 50 ms  (20/s): valutazioni a [1059]               → UNA sola, a puck fermo
ogni 100 ms (10/s): valutazioni a [81 189 288 … 2029]  → una per comando
```

**L'ipotesi implicita è falsa.** "10-20 comandi al secondo con la preview fluida" non si ottiene con il meccanismo attuale:

1. sopra ~12 comandi/s (intervallo < 80 ms) il debounce si riarma sempre e **la preview resta congelata finché il puck non si ferma**;
2. sotto quella soglia ogni comando costa una rivalutazione completa: già a 40 ms (scena modesta) 10/s occupano il 40% del thread; a 227 ms la scena non regge nemmeno 5/s, e gli input si accodano (punto 8, p95 217 ms);
3. i comandi non si compattano fino alla conferma (punto 9), quindi la lista e la stringa rivalutata crescono con la durata del gesto.

Per confronto, `nudge-preview!` (matrice sugli oggetti di preview, nessuna rivalutazione): media **0.003 ms**, massimo 0.1 ms su 200 chiamate, sulla scena da 7 462 vertici. È il meccanismo che già rende fluido il drag del gizmo, e il candidato naturale per il puck: velocità → spostamento accumulato applicato come nudge a ogni frame → commit compattato (un `f`/`th`/… con il totale arrotondato allo step) quando il puck rientra nella zona morta.

### 12. Camera — **misurato**

- Controlli: **`TrackballControls`** (non Orbit). Import a `viewport/core.cljs:4`, creazione a `:156-163` (`staticMoving false`, `dynamicDampingFactor 0.2`, `rotateSpeed 3.0`, `zoomSpeed 1.2`, `panSpeed 0.8`). Diversi commenti e docstring dicono ancora "OrbitControls" (`:1256`, `:1290`, `:1349`, `:1426`), ma il codice usa Trackball.
- Render: **continuo**. `renderer.setAnimationLoop(render-frame)` (`:1261-1265`, scelto per WebXR), sospendibile con `pause-render-loop!`/`resume-render-loop!` (`:1267-1278`). `render-frame` (`:1223-1258`) calcola `dt` dal tempo reale, fa il tick delle animazioni, poi esegue **i callback per-frame registrati** (`(doseq [f (vals @frame-callbacks)] (f camera))`, `:1244-1245`), poi `controls.update()` se `controls.enabled`, poi il render.
- Dove si inserisce una velocità per frame: **c'è già il gancio**, `register-frame-callback!` / `unregister-frame-callback!` (`:84-94`), oggi usato dal gizmo per il riscalamento. Un callback "spacemouse" riceve la camera a ogni frame, legge l'ultimo stato del puck (scritto dall'handler dell'evento, vedi punto 8), lo integra con `dt` e muove camera e target. Questo vale sia per la camera sia per l'anteprima-nudge di edit-attach. Due attenzioni: il callback gira **prima** di `controls.update()`, e TrackballControls con `staticMoving false` ha la sua inerzia, quindi muovere la camera senza aggiornare `controls.target` e lo stato interno dei controlli produce scatti. `dt` non è passato ai callback (ricevono solo `camera`); andrà aggiunto o ricalcolato.

---

## Sintesi per il brief

1. Device: report `01` (tx ty tz) e `02` (rx ry rz) separati, 7 byte, int16 LE, range ±350, 125 report/s in tutto mentre è deflesso, silenzio a riposo dopo 3-4 report a zero per ID; bottoni = report `03`, bit 0 sinistro, bit 1 destro. Frame del device X destra, Y verso l'utente, Z giù.
1b. Filtro: l'asse dominante puro sbaglia sui gesti avanti/indietro (ty e rx accoppiati al 90-110%). Servono soglie separate per traslazione e rotazione, o un modo esplicito.
1c. macOS usa il device come mouse (puntatore + click premendo giù). Desktop: aprire **in esclusiva** (verificato: niente più puntatore, report identici), e solo finché serve. Browser: WebHID non può farlo.
1d. Stacco = errore di lettura; riaggancio = riapertura in ciclo (verificato). In WebHID = evento `disconnect`.
2. Tauri v2, nessun evento esistente, permessi già sufficienti; thread nel `setup`, coalescenza dell'ultimo stato.
3. **Solo desktop (decisione di Vincenzo).** WebHID in Chrome riceve report identici, ma non può aprire in esclusiva: l'emulazione mouse di macOS continua e orbita/pan il viewport in parallelo. Nel desktop (WKWebView) WebHID non esiste comunque.
4. **edit-attach col puck non deve emettere un comando ogni soglia**: ogni comando rivaluta l'intero script (5 ms → 6 s a seconda della scena) e il debounce lo congela sopra ~12/s. Modello consigliato: nudge rigido live a ogni frame + commit compattato al rilascio, lo stesso schema del gizmo.
5. L'ingresso giusto nella sessione è `add-command!` (oggi privata), come fa il gizmo, non eventi tastiera sintetici (la mappa tasti è modale).
6. Camera: TrackballControls, render continuo, `register-frame-callback!` è il punto d'innesto.
