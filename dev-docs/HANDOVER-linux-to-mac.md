# Handover — desktop Linux: ripresa sul Mac

> Scritto il 2026-10-16 sull'XPS Ubuntu, a fine sessione Linux. Aprilo in una
> chat nuova **sul Mac**. Basta da solo; il dettaglio (diagnosi, comandi,
> esperimenti) è in `dev-docs/linux-dev-handoff.md`.

## Stato in una riga

La desktop Linux funziona (dev e `.deb` installato, verificati a mano da
Vincenzo). La CI Linux è scritta ma **non è mai girata**, e i commit di questa
sessione sono **solo locali** sul branch `grab-and-register`: non ancora
pushati, non ancora in `main`.

## Primo passo sul Mac: far arrivare i commit

I commit sono stati fatti sull'XPS. Sul Mac non ci sono finché qualcuno non fa
push dall'XPS. Prima di tutto:

```bash
git fetch origin
git log --oneline origin/grab-and-register -12
```

Se tra i commit NON c'è `4e13a0f ci(desktop): build Linux — AppImage e .deb
allegati alla release`, il push dall'XPS non è avvenuto: chiedi a Vincenzo di
farlo da lì (`git push origin grab-and-register`). Non si può ricostruire dal
Mac. Al momento della scrittura il branch era 9 commit avanti a `main`, e `main`
aveva 2 commit (merge v3.9.0) assenti dal branch: serve un merge, non un
fast-forward.

## Commit della sessione Linux (in ordine)

| commit | cosa |
|---|---|
| `a5c2144` | `desktop/dev-linux.sh`: avvio dev su Linux (toglie l'env della snap di VS Code, che era la vera causa del `__libc_pthread_init`) |
| `87a4c80` | test DMABUF: su WebKitGTK 2.52 non serve; `Examples.md` usa `register` |
| `5b9d464` | `.deb`/`.rpm` spediscono libfive (`build.rs` + `bundle.linux.*.files`) |
| `5bdb54c` | doc: `.deb` installato e collaudato dal menu |
| `a01ce4b` | DMABUF spento a runtime solo se WebKitGTK < 2.52 (`dmabuf_workaround`) |
| `b46e870` | webcam: `enable-media-stream` + permesso video in WebKitGTK |
| `b8ad77a` | dev: la finestra aspetta la prima build di shadow-cljs (niente più "Stale Output") |
| `3f1c322` | camera: lista senza doppioni, `:exact` → `:ideal` sul deviceId |
| `4e13a0f` | CI `desktop-build-linux.yml` + `CLAUDE.md` Release Process |

Dopo `4e13a0f`: aggiornamento di `Roadmap.md` (Current Sprint) e questo file.

## Cosa toccano sul Mac (da verificare, non assumere)

Quasi tutto è dietro `#[cfg(target_os = "linux")]`, ma tre cambi girano anche su
macOS:

1. **`main.rs`, `wait_for_fresh_build`** (`b8ad77a`, solo `debug_assertions`):
   `cargo tauri dev` ora, dopo :9000, aspetta fino a 180 s che
   `public/js/manifest.edn` sia più recente dell'avvio. **Verifica sul Mac** che
   `cargo tauri dev` apra la finestra dopo `frontend: build written` e non dopo
   il timeout. Se shadow, con la cache, non riscrivesse il manifest, la finestra
   arriverebbe dopo 3 minuti: su Linux l'ha sempre riscritto.
2. **`camera_capture.cljs`** (`3f1c322`): dedup per deviceId e fallback
   `:exact` → `:ideal` solo su `OverconstrainedError` con
   `constraint = "deviceId"`. Su macOS `:exact` dovrebbe passare al primo colpo.
   **Verifica**: `edit-acquire` → Camera → scegli dal menu la webcam integrata
   e poi il telefono (Continuity); ognuno deve aprirsi.
3. **`tauri.conf.json`**: nuovo blocco `bundle.linux` (deb/rpm `files`,
   `depends`). Su macOS dovrebbe essere ignorato. **Verifica** con un
   `cargo tauri build` che il DMG si costruisca ancora.

`Cargo.toml` aggiunge `webkit2gtk` e `webkit2gtk-sys` solo sotto
`[target.'cfg(target_os = "linux")'.dependencies]`: sul Mac non entrano.

## Da fare, in ordine

1. **Merge in `main`** (serve il permesso di Vincenzo: è visibile da fuori), poi
   push.
2. **Prima esecuzione della CI Linux**: Actions → *Desktop Build (Linux)* → Run
   workflow, versione vuota. `workflow_dispatch` compare solo quando il file è
   sul branch di default. Prima volta: 15–25 min stimati. Punti a rischio, mai
   provati su runner:
   - `ubuntu-22.04` scelto per la glibc (il binario costruito su 24.04 non
     parte su 22.04). Serve WebKitGTK 4.1 recente abbastanza per la feature
     `v2_40` del crate (wry la chiede già).
   - linuxdeploy (AppImage) sul runner: installato `libfuse2`; se si lamenta
     di FUSE, prova `APPIMAGE_EXTRACT_AND_RUN=1` nell'env del passo di build.
   - `strip` in `build.rs` è best-effort, non rompe la build.
   - Lo step "Check libfive is inside the .deb" fallisce apposta se le `.so`
     mancano.
3. **Collaudo dell'artifact** `Ridley-linux` sull'XPS (Vincenzo): installare il
   `.deb` col comando `sudo apt install ./Ridley-…-linux-amd64.deb`, lanciarlo
   dal menu, fare una SDF. L'AppImage col doppio clic richiede
   `sudo apt install libfuse2t64` su Ubuntu ≥ 24.04: va scritto nelle note di
   release.
4. **Prima release con i pacchetti Linux**: `gh release create` fa partire
   entrambi i workflow. `scripts/bump-cask.sh` aspetta ancora solo il DMG e non
   cambia. Nelle note di release servono: requisito libfuse2 per l'AppImage; il
   `.deb` è il pacchetto consigliato; webcam non funzionante su Linux.
5. **Docs da aggiornare dopo la prima release Linux riuscita**:
   `docs/Architecture.md` (desktop multipiattaforma, bundling Linux),
   eventualmente il manuale (installazione su Linux).

## Aperti, non bloccanti

- **Webcam Linux**: nessun fotogramma. È un bug di WebKitGTK 2.52 + `pipewiresrc`
  (`assertion 'gst_caps_is_fixed (pwsrc->caps)' failed`); GStreamer da solo
  cattura. Vincenzo ha deciso: **resta aperto**, al massimo lo si segnala
  upstream (bugs.webkit.org, componente WebKitGTK; versioni nel
  `linux-dev-handoff.md`). Alternativa scartata per ora: cattura nativa nel
  geo-server.
- **`start!` in `camera_capture.cljs`** aspetta `loadedmetadata` su un `<video>`
  non ancora nel DOM: su WebKitGTK quell'evento non arriva. Oggi è mascherato dal
  bug sopra; sistemarlo quando la webcam Linux tornerà a dare fotogrammi.
- **`desktop/bundle-dylibs.sh`** (macOS) sceglie libfive con
  `ls …/ridley-desktop-*/… | head -1`: con più cartelle di build nel `target/`
  locale può impacchettare una libfive vecchia. In CI non succede. Correzione
  possibile: copiare in un percorso fisso come fa ora `build.rs` su Linux, ma
  lato macOS.
- **CSP `connect-src`** elenca solo le porte 12321 e 12322 del geo-server, che
  invece ne prova 12321..12330. Dalla terza finestra in poi la CSP bloccherebbe
  le richieste (non verificato, vale anche su macOS).
- **rpm** costruito ma mai ispezionato (sull'XPS non ci sono strumenti rpm); la
  CI produce solo deb + AppImage.
- **Lo script nREPL del Mac** citato in `CLAUDE.md`
  (`/Users/vipenzo/.local/bin/clj-nrepl-eval`) su Linux non esiste; lì si è
  usato un client Python bencode verso :7888.
