# Handover — le pagine del manuale sono vuote — CHIUSO

Aperto 2026-08-02 su segnalazione di Vincenzo, **diagnosticato e corretto lo
stesso giorno**. Questo documento è stato riscritto: l'originale conteneva la
mappa del meccanismo e delle ipotesi, tutte da verificare. Qui c'è il verdetto.

## Verdetto in una riga

Il guasto **non è nel manuale online** — il sito funziona — ma
**nell'applicazione desktop**: il DMG viene costruito senza copiare i file
Markdown del manuale, quindi l'app ha l'indice (compilato nel bundle JS) e
nessun corpo di pagina (scaricato a runtime).

## Le prove, in ordine

1. **Il sito è sano.** Su `https://vipenzo.github.io/ridley/` (rilascio
   v3.5.0), guidato dal vivo col browser: TOC, capitolo in EN, capitolo in IT,
   indice del Reference e scheda `arc-h` si aprono e si leggono. Zero errori in
   console. Spazzata di **tutti** i 294 URL delle schede + tutte le guide ×2
   lingue: rispondono 200 tranne le pagine aggiunte *dopo* v3.5.0 (`import-obj`,
   `import-mesh`, `plane-mark`, `edit-plane-mark`, capitolo 18) — assenze
   corrette per un sito costruito a quel tag — e `sdf-node?` (bug a sé, sotto).
2. **Il desktop no.** Nel binario dell'app installata
   (`/Applications/Ridley.app/Contents/MacOS/ridley-desktop`, 3.4.0) le chiavi
   degli asset incorporati contengono `/index.html`, `/css/style.css`,
   `/js/main.js`, `/vendor/gif.js` e **zero** occorrenze di `/manual/…`.
3. **La causa.** `desktop-build.yml` non eseguiva mai `npm run sync-manual`.
   `public/manual/` è in `.gitignore` (0 file tracciati), quindi in un checkout
   di CI *non esiste*; Tauri incorpora `frontendDist: ../../public` così com'è.
   L'hook `prerelease` di npm non salva la situazione: il workflow chiama
   `npx shadow-cljs release app` direttamente, non `npm run release`.
4. **Perché VUOTA e non un errore.** `fetch-markdown` aveva già il ramo
   d'errore, ma gli host SPA/Tauri rispondono a un file mancante con
   `index.html` e **HTTP 200** (fatto già annotato in `structure/
   default-guide-langs`): `resp.ok` è vero, `marked` riceve l'HTML della shell
   dell'app e lo stampa — nessun testo visibile. Il ramo d'errore non scattava
   mai.

## Le correzioni

- **`.github/workflows/desktop-build.yml`** — passo `npm run sync-manual` dopo
  `npm ci`, come in `deploy.yml`, col commento che spiega il vincolo.
- **`desktop/src-tauri/tauri.conf.json`** — `beforeBuildCommand:
  "npm run sync-manual"`, così anche un `cargo tauri build` locale non può
  produrre un'app senza manuale. (`npm run` risale l'albero fino al
  `package.json` di radice: funziona da `desktop/` come da `desktop/src-tauri`.)
- **`structure/shell-html?` + guardia in `fetch-markdown`** — un corpo che è in
  realtà `index.html` viene rifiutato con un messaggio esplicito
  (*"il file X non è in questa build … Manca `npm run sync-manual`"*) invece di
  finire nel renderer. **Questa modalità silenziosa non può più ripresentarsi:**
  qualunque sia la causa, si vede scritto cosa manca.
- **`structure/card-url` percent-encoda il nome file** (bug indipendente,
  trovato nella spazzata): il nome della scheda è il nome del simbolo, e
  `sdf-node?.md` chiedeva al server `sdf-node` con query string `.md` → 404.
  Valeva anche online e in dev, non solo nel desktop. `structure/card-file` è
  l'inversa, usata per riconoscere la scheda dal suo URL.

## Cosa è verificato e cosa no

Verificato dal vivo (browser su `localhost:9000`, **codice nuovo confermato nel
bundle emesso** prima del collaudo): la scheda `sdf-node?` ora si apre (3073
caratteri di corpo), scheda normale e capitolo IT invariati, la guardia produce
il messaggio d'errore invece della pagina bianca, il nome proprio della scheda
resta non auto-linkato (l'inversa dell'encoding funziona). Suite: **822 test,
0 fallimenti**, con `test/ridley/manual/structure_test.cljs` nuovo a coprire
encoding, round-trip e riconoscimento della shell.

**Verificato anche sul DMG.** Rilasciata **v3.5.1** (2026-08-02, `main`
fast-forward al branch): il passo `Sync manual content into public/` risulta
`success` nel run di Desktop Build, e nel binario del DMG scaricato dalla
release le chiavi degli asset incorporati contengono **40** occorrenze di
`/manual/guides` (20 guide × 2 lingue) e **296** di `/manual/reference` —
dov'era **zero** in 3.4.0. Il pacchetto passa da 5.5 MB a 6.2 MB, coerente col
Markdown aggiunto. Sul sito, sul build di release v3.5.1, la scheda `sdf-node?`
si apre (prima 404). Cask Homebrew allineato a 3.5.1.

Resta un solo gesto umano, non una verifica di codice: installare il DMG e
aprire una pagina del manuale nell'app.
