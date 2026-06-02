# Brief B — Infrastruttura del manuale (v1)

Documento di lavoro per Code. Definisce l'infrastruttura della prima versione pubblicabile (v1) del nuovo manuale di Ridley. Le decisioni a monte stanno nel piano `docs/manual-redesign-plan.md`: §2 (architettura), §9.2 (scope Flusso B), §10.2 (chiusura dei punti aperti), §13.1 (decisioni chiuse e definizione della v1). Questo brief riassume; in caso di divergenza la fonte è il piano.

Avvertenza di metodo. Dove il brief dice "già fatto", verificare lo stato reale nel working tree prima di costruirci sopra. In questa fase del progetto documenti, indici (incluso il project knowledge) e ricordi hanno più volte anticipato o disallineato lo stato effettivo del codice. La fonte di verità è il filesystem reale, non la descrizione.

## 1. Obiettivo

Sostituire il manuale attuale (`content.cljs`, monolitico) con un sistema a due tracce affiancate: Guide narrative in Markdown (capitoli 1-17, già redatti in `dev-docs/manual-drafts/`) e Reference a schede, consultabile dentro il manuale, organizzata per categoria e con search interna.

La v1 è un manuale completo e usabile: prosa eseguibile più reference sfogliabile. I link fra le due tracce (tooltip dell'editor verso scheda, prosa verso scheda) non sono nella v1: sono fast-follow, tracciati come T-009.

## 2. Cosa è già fatto (verificare, non rifare)

Tutti i punti di questa sezione vanno confermati nel codice prima di partire.

I draft narrativi dei capitoli 2-17 più `about-ridley` (cap. 1) sono in `dev-docs/manual-drafts/`, rivisti dall'autore. Gli esempi sono inline nel Markdown (vedi §5). Le convenzioni d'autore sono in `dev-docs/manual-drafts/CONVENTIONS.md`.

Le schede Reference sono scritte in inglese in `dev-docs/reference-manual/`, una per file, con sottocartella `internals/`. Frontmatter: `name`, `category` (slug stabile, non la stringa numerata di Spec), `since`, `status`. Scheda modello: `loft.md`. Esistono già anche `goto.md`, `look-at.md`, `turtle-state.md`.

L'editor CodeMirror ha già completion e tooltip in hover sui simboli. Questo brief non tocca quella parte, ma produce l'indice (`reference-index.cljs`) che la alimenta e che servirà ai link di T-009.

Un renderer dei blocchi `example-source` esiste come spike. Verificare lo stato dello spike e da dove ripartire.

## 3. Scope della v1

Dentro la v1:

- nuovo `structure.cljs` (albero di navigazione e metadati);
- renderer Markdown per le pagine, con esecuzione dei blocchi `example-source` (§5);
- Reference nel manuale: browser per categoria più search interna (§6);
- pipeline di build che produce `reference-index.cljs` (§6);
- bilinguismo con fallback bidirezionale (§7);
- migrazione da `content.cljs` con cutover `on: release` (§8);
- fix del bug Edit (§9);
- `manual-output/` declassato a build artifact, non più committato.

Fuori dalla v1 (non fare ora):

- i due link verso le schede (tooltip verso scheda, prosa verso scheda): fast-follow T-009;
- la galleria di progetti. Gli esempi sono preservati in `docs/examples/gallery/` ma non vanno cablati nel manuale v1;
- le guide tematiche (es. "Superfici parallele");
- il cap. 18 "Estendere Ridley", non ancora scritto;
- la traduzione. Alla v1 le guide sono solo IT e le schede solo EN; il fallback bidirezionale copre i buchi.

## 4. Architettura del sorgente

Ibrida (piano §2.3). `structure.cljs` contiene l'albero gerarchico del manuale e i metadati di navigazione: ordine dei capitoli, sezioni, titoli, slug, lingua, riferimenti incrociati. Non contiene il codice degli esempi. I file Markdown contengono prosa narrativa ed esempi inline, un file per pagina per lingua.

Frontmatter (piano §10.2 punto 2). Le guide hanno frontmatter zero: aprono con un heading `# N. Titolo`, e titolo, ordine, slug e lingua vivono in `structure.cljs` indicizzati sul path del file. Le schede Reference mantengono il loro frontmatter. Le coppie di lingua si appaiano per convenzione di path (es. `guides/it/08-...md` con `guides/en/08-...md`).

Il layout di cartelle proposto è in §2.3 del piano (guide e reference sotto `docs/manual/`, con `en/` e `it/`). È una proposta: la struttura definitiva è scelta di Code, adattandola al codice esistente.

Oggi i draft stanno in `dev-docs/manual-drafts/` con prefisso `NN-`. Portarli nella struttura finale fa parte di questo brief, incluso decidere se i prefissi spariscono negli slug pubblicati.

## 5. Renderer Markdown ed esempi inline

Parser Markdown vero, non regex su fence.

Gli esempi seguono la convenzione di `CONVENTIONS.md` e §2.4 del piano. Un blocco di codice è eseguibile quando è associato a un marker `example-source`. Due forme: marker inline `<!-- example-source: nome -->` subito prima di un blocco ```clojure (il codice è il blocco visibile), oppure codice racchiuso nel commento, `<!-- example-source: nome` su una riga, il codice nelle righe successive, `-->` a chiudere. Un blocco senza marker è illustrativo: niente bottoni, non eseguito.

Flag sul marker, dopo il nome. `:no-run` toglie il bottone Run e lascia solo Edit, per esempi che scrivono sulla REPL e non producono nulla nel viewport. `:warning slow` segnala che l'esecuzione richiede parecchi secondi: mostrare un avviso prima di eseguire.

Rendering di un esempio: blocco CodeMirror read-only con bottoni Run/Edit (Run assente se `:no-run`), badge di avviso se `:warning slow`. Ogni esempio gira in un contesto SCI fresco (`reset-ctx!`, piano §7.3): i `def`/`defn` di un esempio non sopravvivono al successivo, quindi ogni esempio definisce inline tutto ciò che usa.

Vincolo noto da `CONVENTIONS.md`: la prima riga di un file non deve avere testo sulla stessa riga di un commento HTML di apertura, perché shadow-cljs classificherebbe il file come HTML.

## 6. Reference nel manuale

Browser per categoria. Le schede sono raggruppate per `category` (slug nel frontmatter); label leggibile e ordine si ricostruiscono a build time. Le sezioni sono Functions, Clojure core, Internals (piano §2.2 e §6.8).

Search interna. Cerca sulle schede, almeno nome, signature e descrizione. La full-text sulle guide è nice-to-have, non obbligatoria per la v1.

Build di `reference-index.cljs` (piano §8.2). Uno script Babashka, come per gli altri, legge le schede più Spec.md e produce la mappa simbolo verso metadati, bundled. Niente parsing Markdown a runtime: il Markdown si parsa al build. Questo indice alimenta anche la completion/tooltip già esistenti nell'editor e, in futuro, i link di T-009.

## 7. Bilinguismo e fallback

`en/` e `it/` per guide e reference (piano §5.1). Source: guide IT, reference EN.

Fallback bidirezionale (piano §10.2 punto 7): se la pagina nella lingua richiesta manca, mostrare quella disponibile. È un meccanismo di transizione finché le traduzioni non sono complete. Alla v1 le guide sono solo IT e le schede solo EN, quindi è il fallback a evitare pagine rotte. Stato a regime: entrambe le lingue presenti per ogni pagina.

## 8. Migrazione e cutover

Migrazione non distruttiva (piano §12). `content.cljs` convive con la nuova struttura durante lo sviluppo; il pannello manuale può leggere la nuova struttura dietro un flag finché non è pronta.

Cutover su `on: release`: alla prima release post-ristrutturazione il manuale nuovo sostituisce il vecchio in un colpo. Il desktop (Tauri) segue lo stesso meccanismo del web.

`manual-output/` diventa build artifact, non più committato. Gli esempi di galleria in `docs/examples/gallery/` non fanno parte della build del manuale v1.

Guide orfane (§3.3 del piano). Risultano assorbite nei capitoli durante la stesura: `defining-2d-profiles` nel cap. 3, `marks-as-discovery`/`creation-pose-shifting`/`skeleton-driven-assembly` nel cap. 8, `working-with-sdfs` nel cap. 12 (vedi note §3.2). Verificare; se confermato, gli originali EN in `docs/examples/guides/` sono ridondanti e non vanno reintegrati.

## 9. Fix del bug Edit

Oggi (§7.4) il bottone Edit sovrascrive il contenuto dell'editor utente senza conferma né preservazione. Fix raccomandato: dialog di conferma quando l'editor non è vuoto. Le alternative di §7.4 restano sul tavolo (apri in nuovo buffer, inserisci o appendi al cursore, rinomina "Edit" in "Open in editor"): scegliere in implementazione con Vincenzo se la sola conferma non basta.

## 10. Domande aperte per Code

- Slug definitivi delle pagine: i prefissi `NN-` dei draft spariscono negli slug pubblicati?
- Motore della search interna: indice client-side derivato da `reference-index.cljs`, o altro?
- Struttura definitiva delle cartelle del manuale: la proposta §2.3 va bene o si adatta al codice esistente?
- Stato reale dello spike del renderer `example-source`: da dove ripartire.

## 11. Criteri di accettazione

- I 17 capitoli si leggono nel manuale, con gli esempi eseguibili (Run/Edit) e i flag `:no-run` e `:warning slow` rispettati.
- La Reference è sfogliabile per categoria e cercabile dentro il manuale.
- `reference-index.cljs` è prodotto dalla build e consumato senza parsing a runtime.
- Le pagine mancanti in una lingua ricadono sull'altra (fallback bidirezionale).
- Al cutover `content.cljs` è rimpiazzato e `manual-output/` non è più committato.
- Il bug Edit non distrugge più il lavoro dell'utente senza conferma.
