# Mini-chiarimenti per il capitolo 11

## 1. Il target Node di `api.cljs` è ancora vivo?

**Verdetto: dormiente, di fatto codice morto residuo.**

Il build target è dichiarato in [`shadow-cljs.edn:27-53`](shadow-cljs.edn#L27-L53): si chiama `:core`, è un `:node-library` che produce `out/ridley-core.js` ed esporta 26 funzioni con nomi camelCase (`makeTurtle`, `boxMesh`, `sphereMesh`, `extrude`, `revolve`, `manifoldUnion`, le tre boolean shape Clipper, le sei vector ops di `math`, ecc.) più il marker `:api`. La superficie è pura geometria/turtle/CSG, niente browser e niente Three.js.

Il file ha un solo commit (`3900563 Add ridley-core Node library target for Desktop extraction`) e da allora non è mai stato toccato. Nessun consumer: il [`package.json`](package.json) non ha campo `main`, nessuno script npm compila il target `:core` (gli script sono `dev`/`test`/`release`/`build:gh-pages`, nessuno di loro coinvolge `ridley-core.js`), CI non lo costruisce, non c'è nessun `require` di `ridley.api` nel codice. Le uniche menzioni vive sono in [`dev-docs/architecture-recon.md`](dev-docs/architecture-recon.md) e in [`ch09-webapp-desktop-phase2.md:585`](dev-docs/interviews/ch09-webapp-desktop-phase2.md#L585), che lo descrive testualmente come "esiste per validare browser-independence ma non testa la WebView".

Il commit message dice "for Desktop extraction": era preparatorio per estrarre il core in un binario Desktop separato. Ma il Desktop poi è diventato Tauri+WKWebView con bundle unico (ch9), quindi lo scopo originale è superato. Esporta cose che esistono altrove: tutte e 26 le funzioni sono già accessibili via i namespace pieni `ridley.geometry.primitives`, `ridley.turtle.shape`, `ridley.manifold.core`, ecc. Non c'è nulla di unico in `api.cljs`, è solo re-export. Per ch11 il consiglio è citarlo in una riga come build target dichiarato ma inerte, e segnalarlo come potenziale candidato alla rimozione.

## 2. Quanto di `manual/` e `voice/help/` è codice e quanto è dato?

**[`manual/content.cljs`](src/ridley/manual/content.cljs) è ~97% dato, ~3% codice, con separazione netta.** Il file ha 2986 righe e 12 `defn` totali. Le prime 11 `defn` (es. `defn T` a riga 1181) non sono logica del manuale ma stringhe di esempio DSL incluse dentro la `(def i18n ...)`: sono parte del contenuto, non del comportamento. La logica vera del modulo inizia a [`content.cljs:2893`](src/ridley/manual/content.cljs#L2893) con `find-page-structure` e arriva in fondo: 10 accessor (`get-page`, `get-toc-structure`, `get-section-title`, `get-adjacent-page`, ecc.) per ~94 righe. Tutto il resto, 2890 righe, sono due grossi `def`: `structure` (L8-412, ~405 righe, gerarchia pagine) e `i18n` (L413-2892, ~2480 righe, contenuto multilingua). La separazione è pulita: dato in cima, accessor in fondo.

**[`voice/help/db.cljs`](src/ridley/voice/help/db.cljs) è 100% dato.** Il file ha 951 righe e zero `defn`. Solo due `def`: `tiers` (L9-12, classificazione delle tre fasce di simboli) e `help-entries` (L18-951, mappa di simboli con `:tier`, `:category`, `:template`, `:doc {:it :en}`, `:aliases {:it [...] :en [...]}`). È una tabella pura, nessun helper. Tutta la logica che la consuma vive in [`voice/help/core.cljs`](src/ridley/voice/help/core.cljs) (168 righe) e [`voice/help/ui.cljs`](src/ridley/voice/help/ui.cljs) (209 righe).

Per ch11: descrivere `manual/` come "rendering UI ~660 righe + contenuto 2890 righe" e `voice/help` come "logica + UI ~380 righe + db piatto 950 righe", invece di gonfiare le sezioni con blob di contenuto.

## 3. Settings: usato davvero solo da AI?

**Quasi solo da AI, con una sola chiave non-AI parassita.**

Lo stato di `settings.cljs` ha due rami: `:ai {...}` (provider, key, model, tier, ollama url e model, enabled) e `:audio-feedback` (boolean). Tutti i consumer fuori da `ai/*` toccano una di queste due cose:

- [`audio.cljs:59`](src/ridley/audio.cljs#L59) legge `audio-feedback?` per gating del ping/buzz.
- [`editor/bindings.cljs:615-616`](src/ridley/editor/bindings.cljs#L615-L616) espone `audio-feedback?` e `set-audio-feedback!` come binding DSL.
- [`voice/core.cljs:296`](src/ridley/voice/core.cljs#L296) legge `ai-configured?` per gating del flusso vocale (è AI-adjacent, non un consumer indipendente).
- [`ui/prompt_panel.cljs:103-105`](src/ridley/ui/prompt_panel.cljs#L103-L105) legge provider e model AI (è dentro la UI di prompt editor, AI-adjacent).
- [`core.cljs:1281, 1452, 1477, 1496-1604`](src/ridley/core.cljs#L1281) ospita la UI delle Settings: dropdown provider/model/tier, campo API key, toggle audio, validate-connection. Tocca tutte le chiavi.

Quindi: l'unica chiave non-AI è `audio-feedback`, letta da tre punti (audio.cljs, DSL, UI). Tutto il resto del modulo è AI. Il flag audio è isolato e potrebbe vivere altrove (es. dentro `audio.cljs` stesso) senza alcun impatto. La connection status, la detect-tier, la validate-connection, gli ollama-status, sono tutti AI.

Per ch11: trattare `settings` come parte del cluster AI ("infrastruttura di configurazione del cluster AI, ospita di passaggio anche un flag audio"), non come modulo settings generico. Se la storia futura aggiungerà chiavi non-AI (es. lingua UI, tema, scorciatoie), il file potrà essere ripromosso, ma oggi è nato per AI e contiene quasi solo AI.
