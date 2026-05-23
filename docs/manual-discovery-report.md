# Manuale online di Ridley — Discovery Report

Stato attuale del manuale e della sua integrazione con il sorgente, in vista della ristrutturazione Guide/Reference e dell'integrazione con la search contestuale di CodeMirror. Le sezioni 1–9 seguono il brief.

Lavoro svolto leggendo il codice (rev. `main` al 2026-05-14). Riferimenti file:linea sono indicati dove utili. Punti aperti / decisioni editoriali ancora da prendere sono segnalati esplicitamente come **Punto da chiarire**.

---

## 1. Sorgente del manuale

**Source-of-truth unico**: [src/ridley/manual/content.cljs](src/ridley/manual/content.cljs) — file ClojureScript monolitico (~2984 righe) che contiene **due** strutture-dati nidificate:

- `structure` (righe 8–410) — albero gerarchico, **language-agnostic**, con sezioni → pagine → esempi. Ogni esempio è una mappa `{:id :code [:auto-run] [:no-run]}`. Il `:code` è una stringa Clojure (qui sta il codice degli esempi).
- `i18n` (righe 413–2984) — mappa nidificata `{:en {...} :it {...}}` che rispecchia l'albero `structure`. Contiene per pagina: `:title`, `:content` (prosa lunga), `:examples` mappa-da-id a `{:caption, :description}`.

Non esistono file Markdown sorgente per il manuale in-app. Quelli che sembrano sorgenti — [manual-output/Manual_en.md](manual-output/Manual_en.md) (2923 righe) e [manual-output/Manuale_it.md](manual-output/Manuale_it.md) (2924 righe) — sono **generati** da [src/ridley/manual/export.cljs](src/ridley/manual/export.cljs) (`generate-markdown-text` riga 148, `generate-markdown-with-images` riga 202).

**Struttura editoriale attuale** (dal `structure`):
- 7 top-level: `:part-1` … `:part-6` + `:gallery` (sezione esplicita, non capitolo a parte).
- ~55 pagine totali.
- ~110 esempi con codice + caption.

**Asset**:
- Immagini in [manual-output/images/](manual-output/images/) — 110 PNG, naming convention `{page-id}_{example-id}.png` (es. `hello-ridley_first-cube.png`).
- Generate dinamicamente da `export.cljs` evaluando ogni esempio nel REPL e catturando il viewport. **Non vengono ricostruite a ogni build**: c'è un set committato in repo.
- `images.zip` in `manual-output/` — pacchetto di distribuzione.

**TOC**: implicito, derivato dall'ordine di apparizione nel `structure`. Non c'è una struttura-dati TOC separata. `core.cljs` la calcola via `get-toc-structure` (riga 2926 di content.cljs), `find-page-structure`, `get-parent-section`, `get-adjacent-page` (riga 2967) per next/prev.

**Guide separate** [docs/examples/guides/](docs/examples/guides/) — 5 file Markdown EN-only (`working-with-sdfs.md`, `02_defining-2d-profiles.md`, `creation-pose-shifting.md`, `marks-as-discovery.md`, `skeleton-driven-assembly.md`). **Punto da chiarire**: questi file non risultano referenziati da nessun pezzo dell'app né dell'export. Sono orfani — utili come materiale grezzo per le future Guide, ma oggi nessuno li legge dal manuale in-app.

---

## 2. Bilinguismo

**Architettura**: per-stringa, doppia mappa `:en`/`:it` nello stesso file `content.cljs`. Le due lingue vivono fianco a fianco; per ogni pagina ci sono due copie dei testi.

**Pipeline di traduzione**: nessuna. Manutenzione manuale parallela. Quando cambia un testo `:en`, l'autore deve aggiornare a mano il corrispondente `:it` (e viceversa).

**Fallback**: `get-page` (content.cljs riga 2932 e successive) implementa fallback `:it` → `:en` per pagina mancante. Niente fallback a livello di campo: se la pagina italiana esiste ma manca `:description` di un esempio, quel campo finisce a `nil`.

**Drift**: non c'è un check automatico. Indicatori grossolani: le due `.md` esportate hanno lunghezze quasi identiche (2923 vs 2924 righe), il che suggerisce sincronizzazione ragionevole. Non c'è prova di una procedura di verifica.

**Glossario / no-translate**: nessun meccanismo. I termini tecnici (es. `extrude`, `turtle`, `loft`, `mesh`) sono lasciati invariati nelle caption italiane per convenzione, non per markup. Il codice degli esempi non viene mai tradotto (corretto by design: il `:code` vive in `structure`, non in `i18n`).

**Commenti negli esempi**: oggi gli esempi non hanno una convenzione applicata sui commenti (le stringhe `:code` sono libere). La regola "commenti sempre in inglese" del brief è un'aggiunta editoriale, non un vincolo esistente.

---

## 3. Build e rendering

**Modello**: rendering **runtime** dentro l'app, non sito statico. Il manuale è un pannello DOM costruito imperativamente con `createElement` / `appendChild` / `innerHTML`.

- Stato: [src/ridley/manual/core.cljs](src/ridley/manual/core.cljs) (98 righe) — atom con `{:open? :current-page :lang :history}`. Watcher `add-watch` (riga 91) ridisegna il pannello su ogni cambio di stato.
- Vista: [src/ridley/manual/components.cljs](src/ridley/manual/components.cljs) (359 righe) — `render-manual-panel` (righe 255–348). Mount via `getElementById("manual-container")` (in [src/ridley/core.cljs:1707-1723](src/ridley/core.cljs)).
- Contenuto: l'EDN di `content.cljs` viene **bundled** nel main bundle CLJS (`public/js/main.js`).

**Build**: nessuna build separata. Tutto passa per shadow-cljs target `:app` (vedi [shadow-cljs.edn](shadow-cljs.edn)). Conseguenza pratica: modificare un testo del manuale forza ricompilazione del bundle.

**Markdown parsing**: parsing **a regex** dentro `components.cljs` (righe ~113–159) — riconosce `**bold**`, `` `code` `` inline, link, liste. Non c'è un vero parser Markdown; gli abbellimenti di prosa sono limitati a queste poche regole.

**CodeMirror in-manual**: ogni esempio è renderizzato in un **CodeMirror in modalità read-only** (`create-code-view`, components.cljs righe 54–62). Quindi anche solo "mostrare codice" usa l'editor.

**Navigazione**:
- Gerarchica: section → page → example.
- TOC come pagina virtuale (`:toc`).
- Pulsanti header: back, up, language toggle, close.
- Cronologia push/pop su stack interno; nessun routing URL.
- **Nessuna ricerca testuale** integrata nel manuale.

**Hot-reload**: la struttura del pannello si ridisegna a ogni cambio di stato, ma il **contenuto** `content.cljs` è statico nel bundle — modifiche al sorgente richiedono ricompilazione (shadow-cljs hot-reload lo fa, ma non è "hot-reload del solo manuale": è hot-reload di tutto).

**Export Markdown**: il flusso `generate-markdown-with-images` (export.cljs riga 202) è interattivo, async, ~50ms di delay per esempio per evitare freeze del browser; produce data-URI base64 per le immagini. **Punto da chiarire**: chi/quando genera le `.md` committate in `manual-output/`? Dalla console del browser, presumibilmente a mano. Non è un passo di CI.

---

## 4. Indicizzazione e collegamento con il codice

**Spec.md è parsato programmaticamente — ma per l'AI, non per il manuale.**

- Script: [scripts/gen_rag_chunks.bb](scripts/gen_rag_chunks.bb) (Babashka, 208 righe) — splitta `Spec.md` su `##` e `###`, produce chunk `{:id :title :keywords :content}`.
- Output generato: [src/ridley/ai/rag_chunks.cljs](src/ridley/ai/rag_chunks.cljs).
- Consumatore: [src/ridley/ai/rag.cljs](src/ridley/ai/rag.cljs) — retrieval keyword-based per la generazione AI Tier-3.

**Tra Spec.md e il manuale in-app: nessun ponte.** Nessuna funzione `extrude` viene linkata automaticamente alla sua scheda. Tutti i riferimenti incrociati nel manuale sono prosa scritta a mano. Le "See Also" (components.cljs righe 162–184) sono campi dichiarati esplicitamente nel `structure`/`i18n`.

**Coherence check Spec ↔ manuale**: **non esiste**. Nessuno script verifica:
- funzioni in Spec.md non documentate nel manuale,
- esempi del manuale che usano simboli rimossi dallo Spec,
- divergenza tra signature in Spec e binding effettivo in `bindings.cljs`.

**Conoscenza programmatica dei simboli**: l'unico elenco canonico oggi è [src/ridley/editor/bindings.cljs](src/ridley/editor/bindings.cljs) (mappa `base-bindings` per il contesto SCI). È **hardcoded** — non deriva né dallo Spec né dal manuale. Aggiungere un simbolo richiede edit a mano.

**Implicazione per la search contestuale**: per costruire l'indice simbolo→scheda esistono già due ancore — i `:id` delle pagine del manuale e i nomi dei binding SCI — ma **non sono allineati programmaticamente**. La via più rapida è introdurre una mappa esplicita simbolo→`{:page :example?}` come terzo manifesto, oppure inferirla parsando Spec.md (analogo a quel che fa già `gen_rag_chunks.bb`).

---

## 5. Integrazione editor

**File chiave**: [src/ridley/editor/codemirror.cljs](src/ridley/editor/codemirror.cljs) — setup e helper API CodeMirror (~1100 righe).

**Estensioni attive** (righe 222–273):
- Syntax highlighting via `@nextjournal/clojure-mode` + `HighlightStyle` custom.
- `autocompletion()` (riga 232) — **attivo ma senza completion source custom**: usa defaults, non ha conoscenza dei simboli Ridley.
- `bracketMatching`, `closeBrackets`, paredit (`clojure-mode/paredit_keymap`), `searchKeymap`, fold gutter, history.
- Keymap custom: Cmd+Enter = Run, Esc = blur.
- **AI Focus marker** (righe 40–87): plugin con `StateField` + `StateEffect` + `Decoration` che evidenzia in arancione la forma corrente. È l'unico esempio in codice di decorazione transitoria pilotata da stato.

**Tooltip / popup**: **nessuna infrastruttura in-editor** oggi. Nessun hover, nessun tooltip, nessun completion popup arricchito.

**Helper utili per la futura search contestuale** (già in `codemirror.cljs`):
- `get-word-at-cursor` (righe 525–545) — estrae l'atom/simbolo sotto il cursore come `{:from :to :text}`.
- `get-element-at-cursor` (righe 784–818) — atom, forma delimitata o stringa.
- `get-cursor-position` (righe 344–354).
- `coordsAtPos` (riga 1128) per posizionare popup assoluti.

**Conoscenza dei simboli nell'editor**: oggi la lista vive in `bindings.cljs`. Non viene esposta come dato strutturato a CodeMirror (nessun `completionSource`). L'autocompletion default suggerisce token visti, non simboli Ridley.

**Riusabile per Tier C (hover-search)**: il pattern AI Focus (StateField/StateEffect + Decoration + UpdateListener) è il template più vicino. Per il tooltip serve aggiungere mouse listener sul `view.dom` e una mappa simbolo→entry per il contenuto del popup.

---

## 6. Esempi Run/Edit-abili

**Implementazione**: pulsanti DOM dentro [components.cljs](src/ridley/manual/components.cljs) righe 88–100, handler in [core.cljs](src/ridley/core.cljs):

- **Run** — `run-manual-code` (core.cljs riga 1675). Pulisce viewport (registry, animazioni, ruler), chiama `reset-ctx!`, evalua il `:code` dell'esempio, mostra il risultato.
- **Edit** — `copy-manual-code` (core.cljs righe 1698–1705). Codice:

```clojure
(defn- copy-manual-code [code]
  (when @editor-view
    (cm/set-value @editor-view code)
    (save-to-storage)
    (manual/close-manual!)))
```

`cm/set-value` ([codemirror.cljs](src/ridley/editor/codemirror.cljs) riga 295) emette un cambio da `0` a `doc.length` con `insert: code`: **sovrascrive l'intero editor** senza preservare nulla, senza diff, senza prompt.

**Comunicazione esempio↔viewport↔editor**: nessuna astrazione condivisa; le funzioni `run-manual-code` e `copy-manual-code` chiamano direttamente le API di REPL e CodeMirror. Niente eventi, niente bus.

### Bug "Edit non preserva il testo dell'editor"

**Cosa succede oggi**: l'utente apre il manuale mentre ha codice in lavorazione → preme Edit su un esempio → il suo codice scompare, sostituito dall'esempio. C'è una chiamata `save-to-storage` subito dopo, che **persiste anche la sovrascrittura** (il testo originale viene perso anche da localStorage).

**Cause concorrenti**:
1. `cm/set-value` non rispetta il contenuto preesistente.
2. Non c'è confirm dialog quando l'editor non è vuoto.
3. Non c'è backup del contenuto precedente (né in memoria né nella history undo: il dispatch non è raggruppato come undo distinto, ma in realtà CodeMirror tratta la sostituzione come un singolo undo step — l'utente *potrebbe* recuperare con Cmd+Z, **se sa di doverlo fare e se l'editor non viene reinizializzato prima**).
4. `save-to-storage` viene chiamato sincrono dopo, sovrascrivendo lo snapshot persistito.

**Strade percorribili** (da decidere in fase di brief B, non ora):
- conferma se l'editor è non-vuoto;
- "Apri in nuovo buffer" come azione alternativa;
- "Inserisci al cursore / appendi alla fine" invece di sostituire;
- nominalmente rinominare "Edit" in "Open in editor" e rendere esplicito che sostituisce.

(Annoto come riferimento per il brief B, non implemento.)

### Distinzione esempio eseguibile vs frammento illustrativo

**Esiste già** ma è informale:
- `:auto-run true` (es. content.cljs righe 16, 213, 303) — l'esempio parte automaticamente all'apertura della pagina.
- `:no-run true` (es. righe 291, 294, 297, 307, 310, 313, 381, 384) — nasconde il pulsante Run. Visualizzato come codice statico.
- Default: entrambi i pulsanti visibili.

Quindi una categoria di "frammento illustrativo" esiste già: è `:no-run true`. La scelta editoriale di marcarla esplicitamente nella markup di un eventuale futuro formato Markdown va presa, ma il concetto non parte da zero.

### Dipendenze tra esempi (def/defn condivisi)

**Auto-contenimento per convenzione**: ogni esempio è eseguito in un contesto SCI fresco. `run-manual-code` (core.cljs riga 1679) chiama `reset-ctx!` prima di evaluare. Quindi i `def`/`defn` di un esempio **non sopravvivono** al prossimo Run.

Conseguenza: tutti gli esempi del manuale sono di fatto auto-contenuti. Se un esempio dipende da un binding, deve definirlo inline. Non c'è meccanismo di concatenazione né di "preludio" di pagina.

---

## 7. Vincoli, debiti e fragilità

**Vincoli architetturali**:

- **Monolite `content.cljs`** — ~3000 righe, contiene struttura + i18n + tutti i `:code`. Editing concorrente difficile, conflitti git frequenti, time-to-compile non trascurabile.
- **Contenuto bundled nel main app** — qualsiasi modifica al manuale riparte da una build CLJS completa. Non c'è split di lazy-loading per la sezione manuale.
- **Markdown parsing a regex** in components.cljs — fragile su edge case (es. backtick dentro backtick, link annidati, codice multiline inline). Limita l'arricchimento di prosa.
- **Asset immagini committate** — 110 PNG in repo. Il rigenerare ogni asset richiede l'esecuzione esempio per esempio nel browser, con cattura viewport. Non è un passaggio di CI.

**Fragilità note**:

- Il `:point-count` delle shape-fn è cachato in metadata alla costruzione ([src/ridley/turtle/shape_fn.cljs:68-70](src/ridley/turtle/shape_fn.cljs)); concatenazioni che alterano la point-count lo rendono stale silenziosamente. Documentare shape-fn d'autore deve fare attenzione a questo.
- Il binding SCI in `bindings.cljs` è enorme e monolitico; ogni aggiunta tocca un punto solo, ma anche ogni refactoring deve passare di lì.

**Assunzioni implicite che la ristrutturazione può violare**:

- **"Tutto in un file"**: la generazione Markdown, l'export, il fallback `:it`→`:en`, e la navigazione `find-page-structure` assumono `structure` e `i18n` come singoli alberi. Splittare in file richiede di toccare tutti questi consumatori.
- **"Ordine garantito"**: `get-adjacent-page` (riga 2967) usa la posizione nel vettore per next/prev. Una Reference a scheda con ordine alfabetico (anziché didattico) rompe questa logica.
- **"Sezione = capitolo lineare"**: oggi non esiste il concetto di pagina che vive in più sezioni. La distinzione Guide vs Reference duplica questa relazione (una funzione vive nella sua scheda Reference E può essere referenziata da più Guide), e richiede un'astrazione nuova.
- **Caption agganciata all'esempio**: nel modello attuale ogni esempio ha **una** caption e **una** descrizione per lingua. Una scheda Reference che mostri più esempi della stessa funzione, ognuno con commento proprio, è già supportata dal modello "molti esempi per pagina", ma non con commenti differenziati per esempio + chapeau di pagina.

**Test**:
- `.github/workflows/test.yml` esegue `npx shadow-cljs compile test` — compila i test CLJS ma non valida gli esempi del manuale.
- Nessun linter Markdown.
- Nessun link checker.
- Gli esempi del manuale **non sono mai compilati né eseguiti in CI**. La sola validazione è l'esecuzione interattiva tramite `generate-manual-with-images`.

---

## 8. Manutenzione e workflow di aggiornamento

**Stato attuale**: nessun processo formale. Pratica osservata dalla struttura del codice:

1. Aggiunta di una funzione → implementazione in `src/ridley/...` → aggiunta manuale a `bindings.cljs` → aggiornamento manuale di `Spec.md` → **opzionalmente** aggiunta di pagina/esempio in `content.cljs`. Niente garantisce gli ultimi due passi.
2. Rename / deprecation → nessuna propagazione automatica. Manuale, esempi e Spec restano disallineati finché qualcuno li aggiorna a mano.
3. Nessuno script lega Spec.md al manuale (a differenza di Spec.md → RAG, che invece esiste e funziona).
4. Gli esempi del manuale non sono testati né compilati in CI.

**Deploy del manuale online**:

- Workflow: [.github/workflows/deploy.yml](.github/workflows/deploy.yml) deploya `public/` su GitHub Pages **su release publish** (trigger `on: release`).
- Conseguenza: il manuale online è allineato all'ultima **release**, non a `main`. Aggiornamenti di documentazione tra release restano invisibili agli utenti web finché non si fa release.
- Per il desktop (Tauri) il manuale è bundled nell'app distribuita via cask (vedi [scripts/bump-cask.sh](scripts/bump-cask.sh)).

**Librerie builtin** in [public/builtin-libraries/](public/builtin-libraries/) (`gears.clj`, `puppet.clj`, `weave.clj`) — manutenute a mano, manifest in `_manifest.json`. **Non sono shape-fn personalizzate**, sono librerie di shape composte.

---

## 9. Estendere Ridley — shape-fn personalizzate

### Stato della documentazione del contratto

**Documentato oggi**:
- [dev-docs/ShapeFn.md](dev-docs/ShapeFn.md) — guida interna, **dettagliata ma in `dev-docs/`**: non viene servita all'utente finale.
- [docs/Spec.md](docs/Spec.md) righe ~497–644 — sezione shape-fn rivolta al **consumatore** (come usare `tapered`, `twisted`, `fluted`, ecc.), con esempi. Non documenta come **scriverne una propria**.
- Implementazione: [src/ridley/turtle/shape_fn.cljs](src/ridley/turtle/shape_fn.cljs) — il costruttore `shape-fn` (vedi metadata `{:type :shape-fn :base ... :point-count ...}`) è esposto via binding e potenzialmente chiamabile da SCI, ma non c'è una pagina del manuale che racconti come usarlo.

**Non documentato pubblicamente**:
- **Namespace di import**: dal punto di vista SCI non esiste import — i simboli vivono nel contesto. Ma quali helper sono accessibili (`displace-radial`, `angle`, `noise`, `fbm`, `sample-heightmap`) non è esplicito da nessuna parte.
- **Dynamic var `*path-length*`** ([shape_fn.cljs:20](src/ridley/turtle/shape_fn.cljs)) — il `loft` la binda durante l'evaluation di una shape-fn. Solo `capped` la consuma (righe 974–979). Un utente che voglia scrivere una shape-fn auto-scalante in funzione della lunghezza del path **non sa che questa var esiste**.
- **Ordine di composizione** in `(-> (circle 20) (fluted ...) (tapered ...))` — quale layer evaluta prima? Documentato in ShapeFn.md (riga 204), non in Spec.
- **Cache `:point-count`** — già menzionata in §7 come fragilità.

### Esiste materiale d'esempio?

**Niente shape-fn d'autore nelle librerie builtin**. `gears.clj`, `puppet.clj`, `weave.clj` usano shape primitive ed estrusioni standard, mai shape-fn custom. Quindi:

- **Per la guida "Estendere Ridley" non c'è codice pre-esistente da recuperare**: va scritto come materiale didattico nuovo, possibilmente prendendo come modello una shape-fn semplice già esistente (es. `tapered`) e mostrandone una variante user-side.

### Altri "contratti di estensione" del sistema

Candidati da considerare per gli Internals della Reference, non solo shape-fn:

1. **`register`** ([src/ridley/library/storage.cljs](src/ridley/library/storage.cljs) e binding) — registry di mesh/path/shape/value per nome. Dispatch implicito via `:type` nel valore. Nessun protocollo formale.
2. **Operatori di attachment** — `attach`, `attach-face`, `clone-face` ecc. La firma non è documentata in modo esplicito, solo per esempi.
3. **Animazioni** ([src/ridley/anim/core.cljs:19-29](src/ridley/anim/core.cljs)) — `register-animation!`, `register-procedural-animation!`, `register-collision!`. Non in Spec.md, solo commenti.
4. **Modi pilot/test** (`src/ridley/editor/pilot_mode.cljs`, `test_mode.cljs`) — non user-facing oggi, ma "modi turtle" è un asse di estensione potenziale.

**Punto da chiarire**: la sezione Internals del brief copre shape-fn. Decidere se vuole includere anche `register`-based extension e animation hooks come "Estensione: pattern di registry" e "Estensione: animazioni e collisioni" — la materia esiste, la documentazione esterna no.

---

## Note libere

- **`docs/examples/guides/` orfana** — già segnalato in §1. Materiale potenzialmente utile per le future Guide narrative; al momento non è linkato da nulla. Da decidere se rivitalizzarlo, archiviarlo o ignorarlo.
- **`manual-output/` committato** — `Manual_en.md`, `Manuale_it.md`, `images/` e `images.zip` sono in repo come distribuzione, non come sorgente. Vanno trattati come build artifact (e potrebbero diventare ignorati se generati in CI). Da decidere se restano committati.
- **RAG su Spec.md già funziona** ([scripts/gen_rag_chunks.bb](scripts/gen_rag_chunks.bb) → [src/ridley/ai/rag_chunks.cljs](src/ridley/ai/rag_chunks.cljs)) — è un precedente architetturale utile per la search contestuale. Lo stesso script, adattato, può produrre un indice simbolo→scheda fruibile sia in browser sia in CodeMirror, **senza** introdurre runtime parser di Markdown.
- **`autocompletion()` di CodeMirror è acceso ma vuoto** — completion source custom non configurato. È l'aggancio naturale per far comparire i simboli Ridley quando l'utente digita; il tooltip-hover può viaggiare in parallelo, ma il completion popup è "frutto basso" già abilitato a infrastruttura.
- **Esempio in CodeMirror read-only nel pannello manuale** (`create-code-view`) — significa che la search contestuale, una volta costruita, è applicabile **anche dentro al manuale stesso** (hover su `extrude` in un esempio → tooltip), non solo nell'editor utente. Vale la pena tenere a mente nel design.
- **Manuale online vs `main`** — il deploy `on: release` significa che, durante lo sviluppo della ristrutturazione, il manuale di produzione non si muove. Buono per stabilità, da tenere a mente per il rollout: la nuova versione apparirà tutta-in-un-colpo alla prima release post-ristrutturazione, senza fase intermedia.
- **Punto editoriale meta**: la decisione "esempi embedded nei Markdown" del brief implica un cambio di formato sostanziale rispetto a oggi (EDN inline). Non è un dettaglio implementativo — è un cambio di sorgente. Vale la pena nel brief B distinguere `structure` da `i18n`: l'`i18n` di prosa potrebbe diventare Markdown su disco senza toccare il `structure` degli esempi (che resta dati strutturati).
