# Handoff — far girare la desktop su Linux (dev + release)

> File di passaggio da una sessione partita su macOS. Aprilo in una chat nuova
> **sulla macchina Linux** (Ubuntu 24.04, XPS-15-9550, GPU ibrida Intel+NVIDIA)
> dove i comandi girano davvero. Contiene tutto il contesto: non serve la chat
> originale.

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

## Blocco 1 — `cargo tauri dev` non parte: nessuna finestra + errore `__libc_pthread_init`

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

## Blocco 2 — l'AppImage non parte (FUSE)

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
2. **rpath distribuibile + bundling `.so`** — oggi il ramo Linux di `build.rs`
   usa rpath **assoluto alle build dir** (ok da `target/`, KO su una macchina
   senza quelle dir). Manca il gemello `$ORIGIN` per Linux (la riga
   `build.rs:11` mette `@executable_path/../Frameworks` solo su macOS). Serve
   l'equivalente Linux di `desktop/bundle-dylibs.sh` (copia `.so` accanto al
   binario / via `resources` Tauri). **Da verificare se linuxdeploy lo fa già da
   solo** (vedi Blocco 2).
3. **Env var GPU** cablata nel launcher (vedi nota Blocco 1) se il fix DMABUF
   serve.
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
