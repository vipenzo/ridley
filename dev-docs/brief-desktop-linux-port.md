# Brief — Port desktop su Linux: il banco di prova cross-platform

## Contesto

La versione desktop (Tauri) è oggi macOS-only, ma le feature che la giustificano — SDF, image board, file I/O locale — sono desktop-only e servono a un pubblico più largo di macOS. Prima piattaforma nuova: **Linux**. Non perché sia l'utenza più grande (quella è Windows), ma perché è il banco di prova che valida tutta la catena non-macOS al costo minore, isolando il rischio vero di Windows per una fase separata.

**La correzione di premessa che rende il port trattabile.** Contrariamente a come lo si raccontava, il grosso non è nativo:

- **Manifold (CSG) è già cross-platform**: gira come WASM nel frontend (caricato da CDN, `src/ridley/manifold/core.cljs`), non nel sidecar Rust. Zero da portare.
- **Il «geo server» non è un binario**: è un thread `tiny_http` in-process dentro Tauri (`geo_server.rs:225`), compilato dentro l'eseguibile. Nessun sidecar da localizzare o impacchettare. Arrangiamento già massimamente portabile.
- **I dialog nativi** (`rfd`), la window-state, la risoluzione della home dir (ha già il fallback `USERPROFILE`, `geo_server.rs:13`), la soppressione console Windows, l'icona `.ico`, `bundle.targets: "all"` — tutto già cross-platform.

**L'unica dipendenza nativa legata a macOS è libfive** (l'SDF): C++ vendored in `vendor/libfive`, compilato via CMake in `build.rs`, con dipendenze boost + eigen + libpng. Poiché l'SDF è centrale (una versione desktop senza SDF è esclusa), libfive-che-linka **è** il gate di fattibilità di questa fase. Su Linux è terreno noto — libfive nasce anche lì — quindi il rischio è basso, ma va provato prima di costruire il resto.

**Lo scope è più corto della scorecard completa, perché parecchi item sono problemi Windows, non Linux:**

- La pulizia dei path CLJS (fallback `/tmp/.ridley/...`, join con `/` letterale in `library/storage.cljs`, `fonts/storage.cljs`, `export/gif.cljs`) serve a Windows: su Linux `/tmp` esiste e `/` è nativo. **Fuori da questa fase.**
- Il bundling delle native lib accanto all'eseguibile (`.dll`) è Windows. Su Linux servono solo `.so` con rpath `$ORIGIN`.

**Cosa assume macOS oggi, e va generalizzato:**

- `build.rs:9-12` — rpath `@executable_path/../Frameworks`, gated `#[cfg(target_os = "macos")]`. Linux vuole `$ORIGIN`.
- `build.rs:60-65` — linka `dylib=c++` e rpath delle dir di build, gated `#[cfg(target_os = "macos")]`. **Non esiste alcun ramo Linux**: `dylib=five`/`five-stdlib` (righe 57-58) sono fuori dal `cfg` e verrebbero linkati, ma senza `stdc++` e senza rpath il link fallisce (simboli C++ irrisolti / lib non trovata a runtime). Questo è il pezzo tecnico centrale del brief.
- `desktop-build.yml` — 100% macOS (brew, `hdiutil`/DMG, `bundle-dylibs.sh` che copia dylib in `Contents/Frameworks`). La CI è per la fase di packaging, non per lo spike.

**Nota webview, contro un'analogia sbagliata.** Linux usa **WebKitGTK**, che è WebKit — la stessa famiglia di WKWebView su macOS, **non** Blink come sarà WebView2 su Windows. Conseguenza controintuitiva: i workaround WKWebView già presenti (clipboard via `execCommand` in `codemirror.cljs:708/748`, modali custom al posto di `prompt`/`confirm` nei panel) **probabilmente servono ancora** su Linux, ed è un bene perché ci sono già. Il punto da verificare non è rimuoverli, ma che la detection `safari?` (`codemirror.cljs:396`) scatti sullo user-agent di WebKitGTK, altrimenti prende il ramo clipboard sbagliato.

**Alternative considerate e rigettate:**

- *Spedire Linux/Windows senza SDF (libfive disabilitato)* — eliminerebbe l'unico rischio serio e renderebbe il port banale, ma l'SDF è il gioiello desktop-only: senza, la versione desktop perde la sua ragione. Rigettata dall'utente.
- *Windows prima* — è l'utenza più grande, ma il rischio libfive/MSVC non è de-riscato e trascinerebbe con sé la pulizia path e il bundling DLL. Linux prova la stessa catena (build fuori da brew, link `stdc++`, `.so`, webview WebKit) a rischio quasi nullo; ciò che resta a Windows dopo è **solo** il suo delta specifico.
- *Job CI usa-e-getta come primo passo* — inutile: l'utente ha macchine Ubuntu, il loop locale è più veloce e la CI si scrive dopo, su un percorso già provato.

## Lavoro richiesto

1. **Ramo Linux in `build.rs`.** Aggiungere `#[cfg(target_os = "linux")]` gemello di quello macOS: linka `dylib=stdc++` (non `c++`) e rpath `$ORIGIN` per l'eseguibile; per lo spike locale è sufficiente rpath assoluto sulle dir di build (righe 63-64 già lo fanno per macOS — replicare). Generalizzare la riga 11 (`@executable_path` → equivalente Linux o skip: per lo spike il binario gira da `target/`, l'rpath assoluto basta).
2. **Provisioning dipendenze documentato (apt).** `cmake`, `libboost-all-dev` (o i sotto-pacchetti che libfive usa), `libeigen3-dev`, `libpng-dev`, più le dipendenze webview di Tauri (`libwebkit2gtk-4.1-dev`, `build-essential`, `libssl-dev`, ecc. — allineare alla lista Tauri v2). Elencare il comando esatto nel report per riproducibilità.
3. **Spike di build+run sull'Ubuntu dell'utente.** `cargo tauri build` (o `dev`) compila libfive, linka, l'app avvia. Questo è il gate: se non linka, ci si ferma e si diagnostica il link, non si prosegue con la CI.
4. **Verifica SDF end-to-end.** Un esempio che usa l'SDF renderizza una mesh. È il test che conta: l'SDF è la ragione della fase, non un contorno.
5. **Verifica webview WebKitGTK.** Editing testo, clipboard (copia/incolla), un modale (es. salvataggio libreria). Se la detection `safari?` non scatta, correggerla per includere WebKitGTK.

La **CI Linux e il packaging** (job `ubuntu-latest`, AppImage/deb, rimpiazzo di `bundle-dylibs.sh` con copia `.so` o `resources` di Tauri) **non** sono in questo brief: si scrivono in un brief seguito, sul percorso di build già dimostrato verde. Un difetto una cura; qui la cura è «l'app SDF gira su Linux», non «l'app SDF è distribuibile su Linux».

## Verifica

- **libfive linka e l'app avvia** su Ubuntu — il gate. Comando apt e output di build citati nel report.
- **Un esempio SDF rende una mesh** — verifica funzionale, non solo di link.
- **Image board** carica un'immagine da un path Linux scelto col dialog nativo.
- **File I/O**: salva e ricarica una libreria in `~/.ridley/libraries/` (il layout dotdir è già cross-platform; su Linux nessun fix path serve).
- **Webview**: editing, clipboard e un modale funzionano; nota esplicita se `safari?` è scattato o è stato corretto.
- Suite CLJS esistente invariata (il ramo `build.rs` non tocca il frontend; regressione qui = sorpresa da capire).

## Fuori scope

- **Windows** — fase separata; il rischio libfive/MSVC (vcpkg, layout CMake `Release/`, DLL accanto all'exe) va de-riscato per conto suo. Ciò che questa fase prova su Linux riduce quella fase al suo delta specifico.
- **Pulizia path CLJS** (`/tmp` fallback, join `/`) — è un problema Windows, non Linux.
- **CI matrix e packaging finale** (AppImage/deb, bundling `.so`) — brief seguito, dopo spike verde.
- **Data-portability dei path assoluti** embeddati nei `.clj` (un image board autorizzato su macOS con `/Users/...` non risolve altrove) — sfumatura di dati, non blocco di build.
- **Il dead code di `bundle-dylibs.sh`** che copia dylib Manifold da un percorso `manifold3d-sys` non più esistente — va rimosso, ma nella fase packaging.

## Esito (2026-07-08)

**Gate chiuso, verde.** Su Ubuntu (enzo-XPS-15-9550): `cargo build --release` in `desktop/src-tauri` compila e linka libfive col nuovo ramo `#[cfg(target_os = "linux")]` di `build.rs` (commit `c430b9f`). Il binario a runtime produce mesh SDF corrette — verificato colpendo direttamente il geo server, senza frontend né Java:

```
POST http://127.0.0.1:12321/sdf-mesh
{"tree":{"op":"sphere","r":10},"bounds":[[-15,15],[-15,15],[-15,15]],"resolution":20}
→ {"vertices":[[3.766,3.766,8.463], …]}   # √(x²+y²+z²) ≈ 10 su tutti i punti: giacciono sulla sfera
```

Non solo *linka*: **mesha**. L'unico punto a rischio della fase (libfive nativo fuori da macOS) è provato. Conferme di setup incontrate: `vendor/libfive` è git-ignored → va ri-clonata dopo il checkout; il JDK che `npm run release` reclama è solo shadow-cljs a build-time (l'app resta JVM-free); il gate NON ha richiesto Node/frontend/Java (il binario Fase B + curl bastano).

**Resta aperto:** Fase C completa per la UI reale (JDK + `npm run release` + `cargo tauri build`) e il brief seguito di CI/packaging (job `ubuntu-latest`, AppImage/deb, rpath `$ORIGIN` per l'app bundlata, rimpiazzo `bundle-dylibs.sh`). Windows resta fase a sé.
