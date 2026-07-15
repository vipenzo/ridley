# Brief: bundling ricorsivo delle dylib e fix crash all'avvio (libpng mancante)

## Contesto

Issue GitHub aperta da un mese: Ridley installato via brew cask crasha all'avvio prima del primo frame su un MacBook Pro M3 con macOS 14.4. Non riproducibile sulle macchine di sviluppo.

Root cause misurata ispezionando il DMG della release v3.1.0 (parsing dei load command Mach-O):

- `libfive.dylib` e `libfive-stdlib.dylib` referenziano libpng con path assoluto Homebrew: `/opt/homebrew/opt/libpng/lib/libpng16.16.dylib`.
- `libpng16.16.dylib` non è inclusa nel bundle. Su qualsiasi macchina senza libpng installata via brew, dyld fallisce al load del processo con `Library not loaded`, prima di qualsiasi frame.
- Sulle macchine di sviluppo il bug è invisibile perché libpng via brew è presente (dipendenza della build locale di libfive).
- La versione di macOS del reporter (14.4) è un red herring: tutti i binari dichiarano `minos 11.0`.

Alternative rifiutate:

- Fix dei deployment target (ipotesi minos): falsificata dalla misura, `minos 11.0` su tutti i binari, il crash sarebbe rimasto identico.
- Compilare libfive senza supporto PNG o con libpng statica: alternativa valida e più pulita a lungo termine, rimandata perché richiede intervento sulla build CMake di libfive e verifica che nessuna feature usata da Ridley dipenda dall'export PNG. Da rivalutare come item separato.

Stato attuale di `desktop/bundle-dylibs.sh`: fa solo `cp` delle due dylib libfive in `Contents/Frameworks/`, nessuna riscrittura di install name (gli id `@rpath` arrivano già dalla build CMake). Per questo il riferimento assoluto a libpng è passato inosservato.

Rilievo secondario emerso dall'ispezione: il binario principale `ridley-desktop` porta due rpath residui del runner CI (`/Users/runner/work/ridley/ridley/desktop/src-tauri/target/release/build/.../libfive-build/...`). Innocui ma da rimuovere in fase di packaging.

## Lavoro richiesto

Modificare `desktop/bundle-dylibs.sh` per rendere il bundling ricorsivo e autoconsistente:

1. Dopo la copia delle dylib libfive, analizzare le loro dipendenze con `otool -L` e individuare ogni riferimento non di sistema (tutto ciò che non inizia con `/usr/lib/` o `/System/`). Oggi l'unico caso è `libpng16.16.dylib`, ma il check deve essere generico: se in futuro compare un'altra dipendenza brew, lo script deve intercettarla, copiarla e riscriverla, oppure fallire con errore esplicito se il file sorgente non esiste.
2. Copiare le dipendenze trovate in `Contents/Frameworks/` seguendo la catena ricorsivamente (libpng dipende solo da `/usr/lib/libz.1.dylib` di sistema, quindi oggi la ricorsione si ferma al primo livello).
3. Riscrivere i riferimenti con `install_name_tool -change <path assoluto> @rpath/<nome>.dylib` su ogni dylib che li contiene (oggi: `libfive.dylib` e `libfive-stdlib.dylib`).
4. Impostare l'id delle dylib copiate: `install_name_tool -id @rpath/libpng16.16.dylib`.
5. Rimuovere gli rpath residui del runner CI dal binario `ridley-desktop` con `install_name_tool -delete_rpath` (i due path `/Users/runner/...`). Deve restare solo `@executable_path/../Frameworks`. Rendere la rimozione tollerante: se un rpath non è presente lo script non deve fallire.
6. Ri-firmare ad-hoc tutto ciò che è stato modificato: `codesign -f -s - <file>` su ogni dylib toccata e sul binario principale se modificato al punto 5. Su arm64 una firma invalidata da `install_name_tool` produce SIGKILL al lancio.
7. Verificare l'ordine rispetto alla pipeline di build/CI: lo script gira dopo `cargo tauri build` e prima della creazione del DMG. Se la CI (workflow GitHub Actions) invoca lo script, nessun cambio lato CI dovrebbe servire, ma va confermato leggendo il workflow.

## Verifica

- `otool -L` su tutte le dylib in `Contents/Frameworks/` e sul binario principale: nessun riferimento a `/opt/homebrew/` o ad altri path assoluti non di sistema.
- `otool -l ridley-desktop | grep -A2 LC_RPATH`: presente solo `@executable_path/../Frameworks`.
- `codesign --verify --deep --strict Ridley.app` senza errori.
- Test di lancio simulando l'assenza di libpng sulla macchina di sviluppo: `sudo mv /opt/homebrew/opt/libpng /opt/homebrew/opt/libpng.bak`, lancio dell'app dal bundle prodotto, ripristino con `sudo mv` inverso. L'app deve partire e mostrare il primo frame. Questo test riproduce la condizione esatta del reporter ed è il criterio di chiusura.
- Test di regressione sul percorso felice: lancio normale con libpng presente.

## Fuori scope

- Modifiche al cask nel repo homebrew-ridley (aggiunta di `depends_on arch: :arm64` per dare errore chiaro agli utenti Intel): la fa Vincenzo direttamente, il cask è un repo separato.
- Build di libfive senza PNG o con libpng statica: alternativa documentata sopra, item separato se si decide di perseguirla.
- Il piano di migrazione path (`2c`) e la pipeline Linux: `bundle-dylibs.sh` va modificato qui solo per macOS, la sua eventuale sostituzione per Linux resta sul suo binario.
