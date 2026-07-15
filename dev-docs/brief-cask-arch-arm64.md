# Brief: aggiungere depends_on arch: :arm64 al cask Ridley

## Contesto

I binari distribuiti nel DMG macOS sono thin arm64 (verificato sui Mach-O della release v3.1.0). Un utente Intel che installa il cask otterrebbe un crash muto all'avvio invece di un errore comprensibile. Il cask attuale non dichiara alcun vincolo di architettura.

Il file `Casks/ridley.rb` vive nel repo separato `vipenzo/homebrew-ridley` (tap Homebrew). Nota importante: `scripts/bump-cask.sh` non genera il cask da template, fa solo un sed in-place su `version` e `sha256`. La riga aggiunta qui sopravvive quindi a tutti i bump futuri e non serve alcuna modifica a `bump-cask.sh`.

## Lavoro richiesto

1. Clonare il tap: `gh repo clone vipenzo/homebrew-ridley` (in una directory temporanea o di lavoro, non dentro il repo Ridley).
2. In `Casks/ridley.rb`, aggiungere la riga `depends_on arch: :arm64` dopo il blocco url/name/desc/homepage e prima di `app "Ridley.app"`, separata da riga vuota, secondo l'ordine convenzionale delle stanza dei cask.
3. Commit con messaggio `Add arm64 arch requirement` e push su main.

## Verifica

- `brew style --fix Casks/ridley.rb` senza errori (o `brew audit --cask` se disponibile nel contesto del tap).
- `brew update && brew reinstall --cask vipenzo/ridley/ridley` su una macchina arm64: l'installazione deve completare normalmente.
- Controllo visivo del diff: la modifica deve essere una sola riga più la spaziatura, nessun altro cambiamento al file.

## Fuori scope

- Bump di version/sha256: avverrà col normale giro di `scripts/bump-cask.sh` alla release che include la fix di bundle-dylibs.sh (brief separato: brief-bundle-dylibs-libpng.md).
- Blocco livecheck nel cask: miglioria opzionale, non necessaria ora.
- Modifiche a `scripts/bump-cask.sh`: non necessarie, vedi Contesto.
