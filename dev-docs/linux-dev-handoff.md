# Handoff — far girare la desktop su Linux (dev + release)

> File di passaggio da una sessione partita su macOS. Aprilo in una chat nuova
> **sulla macchina Linux** (Ubuntu 24.04, XPS-15-9550, GPU ibrida Intel+NVIDIA)
> dove i comandi girano davvero. Contiene tutto il contesto: non serve la chat
> originale.

## Aggiornamento 2026-10-15 (letto dal Mac prima di ripartire)

Il lavoro Linux si è fermato all'11 luglio: il Blocco 1 qui sotto (nessuna
finestra) **non risulta risolto**. Da allora la desktop è cambiata in punti che
toccano Linux. Verificali, non assumerli:

- **`cargo tauri dev` lancia `npm run dev` e aspetta fino a 30 s la porta :9000**
  (`main.rs`, ramo `debug_assertions`). La sezione "Config rilevante" più sotto
  ("non avvia un dev-server") è quindi superata. Senza Node o senza JDK
  (shadow-cljs) quei 30 s di attesa possono sembrare il "terminale appeso" del
  Blocco 1. Guarda se nel log c'è `frontend: waiting for :9000...` prima di
  dare la colpa a conda o alla GPU.
- **`beforeBuildCommand` ora è `npm run sync-manual`** (`tauri.conf.json`):
  `cargo tauri build` richiede Node e `npm ci` fatto.
- **Porta del geo-server variabile**: `geo_server::start()` prova
  12321..12330 e passa quella ottenuta alla webview come `window.RIDLEY_GEO_PORT`.
  Il `connect-src` della CSP in `tauri.conf.json` elenca però solo 12321 e
  12322: con tre o più finestre Ridley aperte la terza verrebbe bloccata dalla
  CSP (non verificato; vale anche su macOS).
- **Camera nell'app** (`3b0683c`): su macOS wry concede già il permesso a
  `getUserMedia`. **WebKitGTK è un altro codice**: forse servono
  `enable-media-stream` nelle WebKitSettings e un handler `permission-request`
  (via `with_webview`). Da collaudare con "scatta e registra".
  → **2026-10-16: confermato e scritto, NON collaudato.** wry 0.54 non fa né
  l'una né l'altra cosa (abilita solo webgl/webaudio/clipboard).
  `enable_camera` in `main.rs` attiva `enable-media-stream` e concede le
  `UserMediaPermissionRequest` solo video (la cattura chiede `:audio false`);
  il resto ricade sul default di WebKit, cioè negato. `tauri://` è già registrato
  come schema sicuro da wry. Compila. Su questo XPS **non c'è nessuna webcam
  visibile** (niente `/dev/video*`, niente in `lsusb`, `uvcvideo` non caricato:
  integrata spenta da BIOS o scollegata), quindi
  `getUserMedia` arriva in fondo e WebKit risponde **"invalid constraint"**, un
  messaggio fuorviante per "nessun dispositivo".
  → **Con una webcam USB (Logitech C922), stesso giorno: la webcam su Linux NON
  funziona, per un difetto di WebKitGTK/PipeWire e non nostro.** Esperimenti
  fatti nell'app viva via nREPL di shadow (:7888):
  - Il permesso arriva: compare il dialogo del portale camera di GNOME, che
    ricorda la scelta (PermissionStore `devices/camera`: "Ridley" yes).
    `getUserMedia` risolve a ogni risoluzione (640×480 … 2304×1536; con
    l'ideale 4K la C922 dà 2304×1536) e la traccia è `live`.
  - **Nessun fotogramma arriva mai al `<video>`**: `videoWidth` resta 0, sia
    con il video staccato dal DOM sia inserito. Nel log, a ogni apertura:
    `WebKitWebProcess CRITICAL: handle_format_change: assertion
    'gst_caps_is_fixed (pwsrc->caps)' failed`. WebKitGTK 2.52 cattura sempre via
    portale + `pipewiresrc`, e lì la negoziazione dei caps fallisce.
  - Fuori da WebKit GStreamer cattura senza problemi
    (`gst-launch-1.0 v4l2src device=/dev/video0 num-buffers=15 ! fakesink` e
    `pipewiresrc ! videoconvert ! fakesink` escono entrambi 0).
  - Non aggirabile da env: `GST_PLUGIN_FEATURE_RANK=pipewiredeviceprovider:NONE,pipewiresrc:NONE`
    e `WEBKIT_USE_PORTAL=0` lasciano lo stesso CRITICAL (e la label diventa vuota).
  - Stack: Ubuntu 26.04, WebKitGTK 2.52.6, GStreamer 1.28.2,
    gstreamer1.0-pipewire 1.6.2.
  - Difetti di WebKitGTK aggirati in `camera_capture.cljs` (validi a
    prescindere): la C922 compare DUE volte con lo stesso deviceId (ora
    deduplicata) e `deviceId {:exact id}` con l'id dato da `enumerateDevices`
    dà `OverconstrainedError`/"Invalid constraint" (ora si ritenta con
    `:ideal`, che apre quel dispositivo; su macOS `:exact` passa al primo colpo).
  - Altro da sapere: `start!` aspetta `loadedmetadata` su un `<video>` non ancora
    nel DOM. Su WebKitGTK quell'evento arriva solo alla chiusura dello stream
    (forse governato da `WEBKIT_GST_ALLOW_PLAYBACK_OF_INVISIBLE_VIDEOS`). Quando
    i fotogrammi arriveranno, `start!` potrebbe ancora restare appesa: inserire
    il video nel DOM prima di aspettare.
  - Strade possibili: segnalare il bug a WebKitGTK/PipeWire e aspettare un
    aggiornamento; oppure cattura nativa nel geo-server (GStreamer `v4l2src`
    da Rust, un endpoint "grab") che salta del tutto la webview. Il flusso
    principale, foto prese col telefono e messe nella cartella, non dipende
    dalla webcam.
- **Pannello file nativo:** collaudato il 2026-10-16. Open/Save As si aprono
  davanti all'app (portale xdg), Annulla non fa nulla, salva e riapre.
  Collaudati lo stesso giorno anche copia/incolla da e verso altre app, e il
  modale "New library" con la libreria che sopravvive al riavvio
  (`~/.ridley/libraries`).
- **Codice morto di Manifold-dylib già rimosso** (`23beda1`): quella voce non è
  più da fare.
- Già a posto, nessun lavoro: le scorciatoie accettano `metaKey` o `ctrlKey`; la
  `~` la espande il server con `$HOME`; il controllo `webkit-not-blink?` in
  `codemirror.cljs` scatta sullo user-agent WebKitGTK, quindi i correttivi
  WebKit si applicano anche qui.

## Dove siamo (già provato, verde)

- **Gate di fattibilità chiuso il 2026-07-08** (commit `c430b9f`): su questo
  Ubuntu, `cargo build --release` in `desktop/src-tauri` linka libfive col ramo
  `#[cfg(target_os = "linux")]` di `build.rs` (linka `stdc++`, rpath assoluto
  alle build dir), e il geo-server a runtime **mesha SDF corrette** — verificato
  con `POST http://127.0.0.1:12321/sdf-mesh` su una sfera r=10.
- Quel gate NON ha mai avviato la UI vera: ha colpito solo l'endpoint HTTP via
  curl. **Far partire l'app con la finestra è ciò che stiamo sbloccando ora.**
- Setup noto: `vendor/libfive` è git-ignored → dopo il clone va ri-clonata
  (`git clone --depth 1 https://github.com/libfive/libfive.git desktop/src-tauri/vendor/libfive`).
  apt deps: `cmake build-essential libboost-all-dev libeigen3-dev libpng-dev`
  (libfive) + `libwebkit2gtk-4.1-dev libgtk-3-dev librsvg2-dev libssl-dev`
  (webview Tauri v2).

## Config rilevante

- `desktop/src-tauri/tauri.conf.json`: **nessun** `beforeDevCommand`/`devUrl`;
  `frontendDist: ../../public` (statico). Quindi `cargo tauri dev` NON avvia un
  dev-server: carica lo statico e apre il webview. Un hang all'avvio è del
  **processo webview**, non di un server che non risponde.
- `bundle.targets: "all"` → produce AppImage **e** `.deb` **e** rpm in
  `desktop/src-tauri/target/release/bundle/`.

## Blocco 1 — RISOLTO (2026-10-16): la finestra compare

**Come si avvia:** da un terminale Ubuntu normale (non quello integrato in VS Code)

```bash
bash desktop/dev-linux.sh
```

Verificato su questo XPS (sessione Wayland, WebKitGTK 4.1): build Rust ok,
geo-server su :12321, shadow-cljs pronto su :9000, processi
`WebKitWebProcess`/`WebKitNetworkProcess` vivi, finestra Ridley visibile con
l'editor.

**Causa vera di `__libc_pthread_init`: non conda, ma VS Code installato come
snap.** Il terminale integrato eredita `GTK_PATH`, `GTK_EXE_PREFIX`,
`SNAP_LIBRARY_PATH`, `GIO_MODULE_DIR`, `GDK_PIXBUF_MODULE_FILE`, `LOCPATH`,
`GSETTINGS_SCHEMA_DIR` che puntano in `/snap/code/<rev>/`. GTK carica da lì
moduli linkati contro core20 (glibc 2.31) che trascinano una `libpthread`
vecchia → `symbol lookup error ... /snap/core20/.../libpthread.so.0: undefined
symbol: __libc_pthread_init` alla creazione della webview. È solo un problema
dell'ambiente di sviluppo: .deb/AppImage lanciati dal desktop non lo vedono.

`desktop/dev-linux.sh` fa due cose prima di `cargo tauri dev`:
1. uccide `shadow-cljs watch app` / istanze vecchie (altrimenti
   `ExceptionInfo: already started` e app in stato rotto);
2. toglie le variabili snap (`SNAP*`, `GTK_*`, `GIO_*`, `GDK_PIXBUF*`,
   `LOCPATH`, `GSETTINGS*`, `VSCODE_*`).

**DMABUF provato il 2026-10-16: qui NON serve.** La macchina è **Ubuntu 26.04
con WebKitGTK 2.52.6** (non 24.04/2.44 come scritto sopra), Intel HD 530 +
GTX 960M, Wayland. Senza la variabile la finestra compare, la scena si
disegna (`register` di box + sphere) e ruota in modo fluido. Il bug documentato
è della 2.44: su Ubuntu 24.04 potrebbe ancora servire.

**Deciso: test al lancio, nell'app** (`dmabuf_workaround` in `main.rs`, prima
istruzione di `main()`, prima di qualunque thread). Legge la versione runtime di
WebKitGTK (`webkit2gtk-sys`: `webkit_get_major/minor_version`) e, se è < 2.52,
l'unica che abbiamo visto andare bene, imposta `WEBKIT_DISABLE_DMABUF_RENDERER=1`
e lo scrive su stderr. Se l'utente ha già impostato la variabile, a qualunque
valore, non la tocca. Si guarda la versione di WebKit, non quella della distro,
perché Ubuntu aggiorna WebKitGTK anche sulle LTS e la regola vale così su
qualunque distro. Collaudato su 2.52: con la soglia
alzata a 2.53 per prova il ramo scatta e stampa; con 2.52 tace. Non verificato
direttamente: che WebKit legga la variabile nel processo UI (dove `set_var` la
mette) e non solo nei sottoprocessi. Nell'environ del `WebKitWebProcess` non
compare, ma per quanto noto la scelta del renderer è fatta nel processo UI.
Se un giorno una 2.5x più vecchia si rivela ok, basta abbassare `FIRST_OK`.

**Lancia con `cargo tauri dev`, non il binario `target/debug/ridley-desktop`
da solo:** una build debug senza `devUrl` cerca `http://localhost:1420` (morto)
e mostra a tutta pagina `Could not connect to 127.0.0.1: Connection refused`.

La diagnosi originale resta sotto per il record (l'ipotesi conda era sbagliata).

### Diagnosi originale (luglio)

Sintomo osservato: nessuna finestra appare, il terminale resta appeso, e compare
un errore che cita `__libc_pthread_init`.

**Prima cosa da stabilire (il bivio che decide tutto): l'errore è a link-time o a
runtime?**

```bash
# ricompila pulito e guarda se l'errore è QUI (link-time)
cargo build 2>&1 | tee /tmp/ridley-build.log | grep -iE "pthread_init|undefined reference"
```

- Se `undefined reference to __libc_pthread_init` **durante il build/link** →
  problema di toolchain/linker. Sospetti: un linker alternativo (mold/lld) in
  `~/.cargo/config[.toml]`, o il modo in cui il ramo Linux di `build.rs:70-75`
  linka `stdc++`. Incolla il blocco d'errore completo nella chat.

- Se l'errore è `symbol lookup error: ... undefined symbol: __libc_pthread_init`
  **quando il binario parte** → **mismatch glibc a runtime**: viene caricata una
  libc/libstdc++ diversa da quella di sistema. Causa n.1 su queste macchine: un
  ambiente **conda/Anaconda/Miniconda** attivo che antepone le sue lib in
  `LD_LIBRARY_PATH`. WebKitGTK carica lib di sistema che si aspettano la glibc di
  sistema → simboli non combaciano → webview muore prima di creare la finestra
  (= esattamente il sintomo "nessuna finestra").

**Diagnosi runtime/conda:**

```bash
echo "LD_LIBRARY_PATH=$LD_LIBRARY_PATH"
which python              # ~/miniconda3/... o ~/anaconda3/... = sospetto
env | grep -i conda
ldd "$(find desktop/src-tauri/target -name Ridley -type f -executable | head -1)" \
  | grep -iE "libc\.|libstdc|not found"
```

**Prova in ambiente pulito (conda fuori dai piedi + fix GPU ibrida insieme):**

```bash
conda deactivate 2>/dev/null
env -u LD_LIBRARY_PATH -u LD_PRELOAD \
  WEBKIT_DISABLE_DMABUF_RENDERER=1 \
  cargo tauri dev
```

Se ancora appeso, alza il log e i fallback:

```bash
RUST_LOG=tauri=debug RUST_BACKTRACE=1 \
  WEBKIT_DISABLE_DMABUF_RENDERER=1 \
  WEBKIT_DISABLE_COMPOSITING_MODE=1 \
  LIBGL_ALWAYS_SOFTWARE=1 \
  cargo tauri dev
```

Guarda l'ultima riga prima dell'hang; da un altro terminale `ps aux | grep -i ridley`
dice se il processo è vivo (webview appeso) o se cargo è ancora fermo a compilare.

> **Nota GPU ibrida (Intel+NVIDIA, Ubuntu 24.04):** anche senza conda, WebKitGTK
> 2.44+ ha un bug noto del renderer DMABUF che si pianta prima di creare la
> finestra. `WEBKIT_DISABLE_DMABUF_RENDERER=1` è il fix. Se serve in dev, va
> **cablato anche nell'app spedita** (wrapper/`.desktop`), altrimenti ogni utente
> Linux con GPU ibrida/NVIDIA avrà lo stesso hang. → è un item di packaging.

## Blocco 2 — pacchetti (2026-10-16): costruiti e autosufficienti

`npm run release && cd desktop/src-tauri && cargo tauri build` produce i tre
pacchetti in `target/release/bundle/`.

- **AppImage (~96 MB):** linuxdeploy ci mette `libfive.so`/`libfive-stdlib.so`
  (già strippate) più ~157 lib di sistema (WebKitGTK compreso, il grosso del
  peso) e riscrive il RUNPATH in `$ORIGIN/../lib`. Verificato: lanciato con
  `--appimage-extract-and-run`, `/proc/<pid>/maps` mostra libfive caricata da
  dentro l'AppImage; `POST /sdf-mesh` di una sfera r=10 dà una mesh chiusa
  (V−1=8600, F=17196=2(V−1)−4) con tutti i vertici a r=10. Il vertice 0
  nell'origine non è usato da nessuna faccia: è il segnaposto di libfive.
- **.deb / .rpm (~16 MB):** al primo giro contenevano SOLO il binario, che
  funzionava sulla macchina di build solo grazie al RUNPATH assoluto verso
  `target/.../out/libfive-build`. Ora:
  - `build.rs` (ramo Linux) copia e strippa le `.so` in
    `target/<profile>/libfive/` (path fisso: la dir di build ha un hash, e quelle
    vecchie restano accanto) e mette `$ORIGIN/../lib/ridley` prima dei path
    assoluti nel RUNPATH;
  - `tauri.conf.json` → `bundle.linux.{deb,rpm}.files` le installa in
    `/usr/lib/ridley/` e dichiara libpng (unica dipendenza non-base di libfive):
    `libpng16-16t64 | libpng16-16` per deb, `libpng` per rpm.
  - Verificato sul .deb estratto con `dpkg-deb -x`: `ldd` risolve libfive in
    `<estratto>/usr/lib/ridley`. **rpm non ispezionato** (niente `rpm`/`bsdtar`
    su questa macchina): stessa config, stessa crescita di dimensione.
- **Installazione vera verificata (2026-10-16):** `sudo apt install ./…deb`
  → `/usr/bin/ridley-desktop` risolve libfive in `/usr/lib/ridley/`. Lanciata
  dal menu Applicazioni: parte, modella e le SDF funzionano. La riga
  `Download is performed unsandboxed as root … _apt … Permission denied` di apt è
  innocua: `_apt` non legge la home, apt ripiega su root.
- **Latente su macOS:** `bundle-dylibs.sh` cerca libfive con
  `ls .../ridley-desktop-*/... | head -1`. In un checkout con più dir di build
  può prendere una libfive vecchia. In CI parte da pulito, quindi lì non succede.

### FUSE (l'AppImage col doppio clic)

Ubuntu 24.04 spedisce FUSE3; l'AppImage è costruito contro **libfuse2**.

```bash
# test immediato senza FUSE (conferma che è solo quello)
./desktop/src-tauri/target/release/bundle/appimage/Ridley_*.AppImage --appimage-extract-and-run

# fix permanente per l'AppImage nativo
sudo apt install libfuse2t64

# alternativa migliore per Linux: installa il .deb (niente FUSE)
sudo dpkg -i desktop/src-tauri/target/release/bundle/deb/*.deb
```

Quando parte, verifica che **trovi le `.so` di libfive** e che l'SDF meshi: se sì,
il bundler Tauri (linuxdeploy) ha già raccolto le `.so` via rpath e il fix
`$ORIGIN` diventa leggero.

## Cosa manca per un RELEASE Linux (dopo aver sbloccato dev)

1. **Fase C completa** — `npm run release` (JDK build-time, l'app resta JVM-free)
   + `cargo tauri build`, e la UI vera gira. Verifica webview WebKitGTK: editing
   testo, clipboard copia/incolla, un modale (salvataggio libreria), image board
   da path Linux, save/reload libreria in `~/.ridley/`. **Controlla che la
   detection `safari?` in `src/ridley/editor/codemirror.cljs:396` scatti sullo
   user-agent WebKitGTK** (altrimenti prende il ramo clipboard sbagliato).
2. ~~**rpath distribuibile + bundling `.so`**~~ — FATTO il 2026-10-16, vedi
   Blocco 2. Testo originale: oggi il ramo Linux di `build.rs`
   usa rpath **assoluto alle build dir** (ok da `target/`, KO su una macchina
   senza quelle dir). Manca il gemello `$ORIGIN` per Linux (la riga
   `build.rs:11` mette `@executable_path/../Frameworks` solo su macOS). Serve
   l'equivalente Linux di `desktop/bundle-dylibs.sh` (copia `.so` accanto al
   binario / via `resources` Tauri). **Da verificare se linuxdeploy lo fa già da
   solo** (vedi Blocco 2).
3. ~~**Env var GPU** cablata nel launcher~~ — FATTO il 2026-10-16: test di
   versione WebKitGTK al lancio, nell'app (vedi Blocco 1).
4. **CI Linux** — `.github/workflows/desktop-build.yml` è 100% macOS. Aggiungere
   job `build-linux` su `ubuntu-latest`: apt deps, clone libfive, `npm ci`,
   `shadow-cljs release`, `cargo tauri build` → AppImage/deb, allega alla
   release. Da scrivere DOPO che il build locale è verde.

Fuori scope per Linux (sono problemi Windows): pulizia path CLJS (`/tmp`, join
`/`), bundling `.dll`.

## Riferimenti nel repo
- `dev-docs/brief-desktop-linux-port.md` — brief originale + esito del gate.
- `desktop/src-tauri/build.rs:70-75` — ramo link Linux.
- `desktop/bundle-dylibs.sh` — bundling macOS (dead code Manifold da rimuovere in
  fase packaging).
