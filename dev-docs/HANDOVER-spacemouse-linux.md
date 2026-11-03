# Handover — SpaceMouse su Linux

> Da aprire in una chat nuova **sulla macchina Linux** (l'XPS-15-9550, Ubuntu
> 26.04, WebKitGTK 2.52 — vedi `dev-docs/HANDOVER-linux-to-mac.md` e
> `dev-docs/linux-dev-handoff.md` per il setup), con il SpaceMouse Compact
> collegato. Scritto dal Mac il 2026-11-03. Contiene tutto il contesto: non
> serve la chat originale.

## Dove siamo

Sul Mac il SpaceMouse funziona nella desktop (commit `6af4f82`, `15de664`,
`f4be353`):

- **orbita della vista** sempre, nella app desktop;
- **edit-attach**: il cappuccio muove l'oggetto, il tasto sinistro (o il bottone
  «SpaceMouse: object/view») passa il cappuccio fra oggetto e vista;
- alla conferma, se ha scritto lo SpaceMouse, i comandi passano per Simplify.

Su Linux **niente di questo funziona**, per una sola ragione: il lettore del
dispositivo in Rust è compilato solo per macOS. Su Linux il thread è uno stub
che risponde `connected: false` a ogni acquire.

**Tutto il resto è già multipiattaforma e non va toccato**: la logica del gesto
(`src/ridley/input/spacemouse.cljs`), l'orbita (`spacemouse_view.cljs`,
`viewport/move-view!`), la sessione edit-attach. Ricevono un evento Tauri e non
sanno da che sistema arriva. Il lavoro è **solo** `desktop/src-tauri/src/
spacemouse.rs` + `Cargo.toml` + pacchetto `.deb` + documentazione.

## Il contratto che il lettore deve rispettare

Leggere `desktop/src-tauri/src/spacemouse.rs` per intero prima di toccarlo. In
sintesi:

- comandi `spacemouse_acquire` / `spacemouse_release` (flag `WANTED`, `ANNOUNCE`);
  `set_focused` dall'evento `Focused` della finestra (`main.rs` ~riga 243);
- il device va tenuto aperto solo se `WANTED && FOCUSED`;
- evento `spacemouse` con `{t:[tx,ty,tz], r:[rx,ry,rz], buttons}` in **valori
  grezzi del device** (la conversione di frame è in CLJS — non convertire in
  Rust), coalescenza a 16 ms, ma stato tutto-zero e cambi di bottone escono
  subito;
- evento `spacemouse-status {connected}` a ogni cambio, e sempre subito dopo un
  acquire;
- al rilascio o a un errore di lettura, se l'ultimo stato inviato non era zero,
  emettere uno stato zero (altrimenti la webview resta con un gesto "tenuto").

Formato dei report (misurato sul Mac, `dev-docs/accertamento-spacemouse-
risultati.md` §1): VID `0x256F`, PID `0xC635`, usage page 1 / usage 8; report
`01` = tx,ty,tz int16 LE, `02` = rx,ry,rz, `03` = bitmask bottoni (bit 0
sinistro, bit 1 destro). **Su Linux via hidraw i report sono gli stessi byte**
(hidraw passa il report con l'ID in testa, come hidapi su macOS): il parsing
`match d.read_timeout(...)` dovrebbe valere così com'è. Verificarlo, non
assumerlo — primo passo del piano.

## Piano raccomandato

### 1. Accertamento (prima di scrivere codice)

- `lsusb | grep -i 256f` — il device c'è?
- `ls -l /dev/hidraw*` e `udevadm info -a /dev/hidrawN | grep -i -E 'idVendor|idProduct'`
  per trovare il nodo del SpaceMouse. Che permessi ha? (Di default di solito
  `root:root 0600` → un utente normale non lo apre.)
- **Il SpaceMouse muove il puntatore su GNOME?** Sul Mac sì (per questo lì
  l'apertura è esclusiva). Su Linux di solito no — libinput non lo tratta come
  mouse — ma va guardato: muovere il cappuccio con Ridley chiuso e vedere se il
  puntatore si sposta o si "clicca". Se sì, vedi «Rischi» più sotto.
- `systemctl status spacenavd` — c'è spacenavd? Non blocca hidraw (il suo grab
  è sul nodo evdev, non su hidraw), ma è utile saperlo.
- Lettura grezza a mano: `sudo cat /dev/hidrawN | xxd` muovendo il cappuccio →
  devono comparire righe `01 …`, `02 …`, `03 …` come sul Mac. Se i byte sono
  diversi, il parsing va adattato e i segni ricontrollati.

### 2. Il lettore (Rust)

- `Cargo.toml`: aggiungere una dipendenza Linux **senza libudev** (la CI
  `desktop-build-linux.yml` non ha `libudev-dev`, e non serve aggiungerlo):
  ```toml
  [target.'cfg(target_os = "linux")'.dependencies]
  hidapi = { version = "2.6", default-features = false, features = ["linux-native-basic-udev"] }
  ```
  `linux-native-basic-udev` è il backend in Rust puro (crate `basic-udev` +
  `nix`), presente in hidapi 2.6.7 (verificato dal registry del Mac). Va nella
  sezione Linux già esistente (quella con `webkit2gtk`). La sezione macOS resta
  com'è.
- `spacemouse.rs`: estendere `#[cfg(target_os = "macos")]` del modulo `imp` a
  `any(target_os = "macos", target_os = "linux")`, con l'unica differenza che
  **su Linux non si chiama `set_open_exclusive`** (esiste solo con la feature
  macOS; mettere quella riga sotto `#[cfg(target_os = "macos")]`). Il resto del
  loop è uguale. Lo stub resta per Windows.
- Verificare che il backend `linux-native` riporti `usage_page()`/`usage()`
  (legge il report descriptor da sysfs). Se tornano 0, `min_by_key` apre
  comunque il primo device col VID giusto — il Compact ne ha uno solo — quindi
  non è bloccante; annotarlo nel commento.
- Errore di apertura per permessi: oggi finisce in `OpenError::Busy(msg)` e il
  log dice `busy (...)`. Su Linux il caso tipico sarà "Permission denied":
  distinguerlo nel messaggio di log (`no permission — udev rule missing?`) così
  chi lancia da terminale capisce subito. Lo status alla webview resta
  `connected: false`.
- Aggiornare il commento di testa del file («macOS only: …»).

### 3. Permessi: la regola udev

File nuovo, ad es. `desktop/src-tauri/linux/70-ridley-spacemouse.rules`:

```
# 3Dconnexion devices: readable by the user at the seat (Ridley's SpaceMouse reader)
KERNEL=="hidraw*", ATTRS{idVendor}=="256f", TAG+="uaccess"
```

- Il numero **deve essere < 73**: `uaccess` è applicato da `73-seat-late.rules`.
- Valutare se aggiungere anche il vecchio VID Logitech `046d` (SpaceNavigator e
  modelli pre-2013, PID `c6xx`). Il lettore oggi filtra su `0x256F` soltanto:
  se si aggiunge, aggiungerlo in entrambi i punti, altrimenti lasciar perdere.
- Prova a mano prima di impacchettare:
  ```
  sudo cp desktop/src-tauri/linux/70-ridley-spacemouse.rules /etc/udev/rules.d/
  sudo udevadm control --reload && sudo udevadm trigger
  ```
  poi scollegare e ricollegare, `ls -l /dev/hidrawN` → deve comparire un `+`
  (ACL); `getfacl /dev/hidrawN` mostra l'utente.

**Nel `.deb`**: `tauri.conf.json` → `bundle.linux.deb.files` (stesso posto dove
ci sono già le `.so` di libfive):
```json
"/usr/lib/udev/rules.d/70-ridley-spacemouse.rules": "linux/70-ridley-spacemouse.rules"
```
(percorso sorgente relativo a `desktop/src-tauri/`; controllare). Dopo
l'installazione udev va ricaricato perché la regola valga senza riavvio: Tauri
v2 dovrebbe avere `bundle.linux.deb.postInstallScript` — **verificarlo nello
schema della versione di tauri-bundler in uso**; se c'è, uno script con
`udevadm control --reload-rules && udevadm trigger --subsystem-match=hidraw || true`.
Se non c'è, basta scollegare e ricollegare il device: scriverlo nel manuale.

**AppImage**: non installa niente nel sistema. Il manuale dirà di copiare la
regola a mano (i due comandi sopra). Valutare se mettere la regola anche fra le
risorse dell'AppImage o solo nel manuale — la seconda è sufficiente.

### 4. Collaudo sull'XPS

- `cd desktop && ./dev-linux.sh` (o come indicato in `linux-dev-handoff.md`:
  la snap di VS Code inquina l'ambiente). Nel log devono comparire
  `spacemouse: acquired (exclusive)` — **cambiare la stringa su Linux**, lì non
  è esclusiva.
- Orbita: col cappuccio, fuori da ogni sessione. Spingere lontano → la scena si
  allontana; ruotare → la scena ruota nello stesso verso del cappuccio («il
  cappuccio è la scena»). Se un asse ha il segno sbagliato, il device su Linux
  manda byte diversi dal Mac: correggere **in Rust**, normalizzando al formato
  del Mac, non in CLJS (che deve restare unico).
- edit-attach: `(register b (edit-attach (box 20)))` → spinta in avanti =
  `f`, tasto sinistro → l'etichetta diventa «SpaceMouse: view», Enter → codice
  condensato.
- Focus: passare a un'altra finestra mentre si spinge → la scena si ferma
  (deve arrivare uno stato zero). Tornare → riparte.
- Due finestre Ridley: con hidraw entrambe potrebbero aprire il device (niente
  esclusiva). Il gating su `FOCUSED` dovrebbe bastare perché ne legga una sola;
  verificarlo.
- Se c'è un nREPL di shadow attivo nella app (porta 7888, vedi l'handover
  Linux), `@ridley.input.spacemouse-device/state` e `/status` mostrano cosa
  arriva alla webview.

### 5. Il lancio in sviluppo — verificare anche questo

Il commit `d845aa4` (fatto sul Mac) cambia `main.rs`: il frontend parte in un
process group suo e all'uscita si ammazza il gruppo; in debug, all'avvio, si
cercano watcher shadow-cljs orfani con `ps -axo pid=,ppid=,command=` e si
fermano. È `cfg(unix)`, quindi gira anche su Linux, ma **è provato solo sul
Mac**. Controllare: (a) che `ps -axo …` di procps dia lo stesso formato;
(b) che dopo Ctrl+C su `cargo tauri dev` non resti nessun `java`/`node` di
shadow-cljs (`pgrep -af shadow-cljs`); (c) che un secondo `cargo tauri dev` parta.

### 6. spacenavd (opzionale, dopo)

Chi usa FreeCAD/Blender su Linux ha spesso spacenavd. Con hidraw diretto Ridley
funziona anche con spacenavd acceso (non si contendono il nodo). Un client del
socket di spacenavd (`/var/run/spnav.sock`) servirebbe solo a chi non vuole o
non può installare la regola udev. **Non farlo in questo giro** se il diretto
funziona: proporlo a Vincenzo come seguito.

### 7. Documenti e CI

- Manuale: `docs/manual/reference/en/edit-attach.md`, sezione «SpaceMouse
  (desktop app)», dice «on macOS» e chiude con la frase su macOS che lo usa come
  mouse: aggiungere Linux e la regola udev (`.deb` automatica, AppImage a mano).
  Poi `bb scripts/build_reference_index.bb`.
- Guide `docs/manual/guides/en|it/01-getting-started.md`: la frase sull'orbita.
- `docs/Architecture.md` §9.6 (riga SpaceMouse), `docs/Roadmap.md` Current
  Sprint, `dev-docs/brief-spacemouse-edit-attach.md`.
- CI: lanciare `desktop-build-linux.yml` (workflow_dispatch) e controllare che
  resti verde senza nuove dipendenze apt, e che il `.deb` contenga la regola
  (`dpkg -c Ridley-*.deb | grep udev`).
- Verificare sul Mac (o chiedere a Vincenzo) che `cargo tauri dev` lì compili
  ancora: la sezione `cfg` toccata è condivisa.

## Rischi noti

- **Puntatore che si muove** (se l'accertamento lo mostra): l'equivalente
  Linux dell'apertura esclusiva è `EVIOCGRAB` sul nodo `/dev/input/eventN` del
  SpaceMouse mentre Ridley lo tiene. Richiede permessi anche su quel nodo (la
  regola udev andrebbe estesa a `SUBSYSTEM=="input"`). Farlo solo se serve.
- **Wayland e focus**: l'evento `Focused` di Tauri su GNOME/Wayland dovrebbe
  arrivare; se no, il device resta aperto anche a finestra in secondo piano
  (scomodo ma non grave).
- **Le convenzioni del progetto**: UI sempre in inglese; i comandi a Vincenzo
  finiscono con «Prossimi passi per te» in italiano (vedi `CLAUDE.md`);
  `docs/manual/reference/en/edit-image-board.md` non è nostro, escluderlo dai
  commit; i messaggi di commit finiscono con la riga Co-Authored-By.
