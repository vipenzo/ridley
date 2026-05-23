# Brief: Spike renderer Markdown nel pannello manuale

**Contesto**: i capitoli del manuale (7-12 finora) vivono come file `.md` in `dev-docs/manual-drafts/`. Il pannello manuale in-app mostra ancora il contenuto vecchio hardcoded in `content.cljs`. Questo spike li rende visibili e gli esempi verificabili, senza costruire il sistema definitivo del Flusso B.

**Obiettivo**: renderer Markdown minimale nel pannello manuale, con blocchi di codice eseguibili. I capitoli draft diventano navigabili e gli esempi verificabili con un click.

---

## 1. Renderer Markdown

### Libreria

Usare `marked` (già disponibile come dipendenza npm) o equivalente per convertire Markdown → HTML. Non serve un parser custom: i draft usano Markdown standard (heading, paragrafi, blocchi codice, enfasi, link).

### Integrazione nel pannello

Il pannello manuale ha già una struttura a sidebar + contenuto. Lo spike sostituisce il contenuto di `content.cljs` con l'HTML prodotto dal renderer. La sidebar mostra la lista dei capitoli disponibili. Click su un capitolo → il renderer carica il `.md` corrispondente e lo mostra.

### Sorgente dei file

In ambiente desktop (Tauri), i file `.md` vengono letti direttamente da `dev-docs/manual-drafts/` via l'endpoint `/read-file` già esistente.

In ambiente web, i file dovrebbero essere bundlati nel build (come stringhe in un namespace, o come asset statici). Per lo spike, è accettabile supportare solo desktop. Se è più semplice, è anche accettabile copiare i `.md` in una cartella servita staticamente e fare fetch.

### Styling

Stile minimale coerente con il pannello esistente. Heading, paragrafi, blocchi codice, enfasi, link. Non servono tabelle fancy, immagini, o layout complessi.

## 2. Blocchi di codice eseguibili

### Il pattern nei draft

I draft contengono due tipi di blocchi di codice:

**Blocchi visibili** (```clojure```): il codice che il lettore vede nella pagina. Spesso sono frammenti parziali, senza `register`, pensati per illustrare un concetto.

**Blocchi example-source** (commenti HTML): il codice completo e self-contained che produce output visibile nel viewport. Formato:

```html
<!-- example-source: nome-esempio
(register b (box 20))
(println (mesh-diagnose b))
-->
```

Il commento HTML segue immediatamente il blocco di codice visibile a cui si riferisce.

### Comportamento del renderer

Per ogni blocco ```clojure``` nel Markdown:

1. Renderizzalo come CodeMirror read-only (coerente con gli esempi del manuale attuale)
2. Controlla se immediatamente dopo c'è un commento `<!-- example-source: ... -->`
3. Se sì: mostra un bottone **Run**. Al click, manda il codice dal commento (non quello visibile) all'eval SCI via `run-definitions!` (o equivalente, come fa già il bottone Run degli esempi attuali)
4. Se no: mostra il blocco senza bottone Run (è un frammento illustrativo)

### Bottone Edit

Opzionale per lo spike. Se semplice da aggiungere: al click, copia il codice dell'example-source nell'editor principale. È il pattern già esistente nel manuale attuale.

### Parsing dei commenti HTML

Il renderer deve estrarre il contenuto dei commenti `<!-- example-source: id\n...codice...\n-->`. Regex sufficiente:

```
/<!--\s*example-source:\s*(\S+)\s*\n([\s\S]*?)-->/
```

Gruppo 1: id dell'esempio (per ora non serve a nulla, ma lo catturiamo per il futuro).
Gruppo 2: codice da mandare all'eval.

I commenti HTML non devono apparire nel rendering.

## 3. Navigazione

### Sidebar

Lista ordinata dei capitoli. Ogni voce mostra numero e titolo (estratti dall'heading `# N. Titolo` del file).

Ordine: numerico per filename (`07-mesh.md`, `08-assemblaggio.md`, ...).

Per lo spike, la lista può essere hardcoded o derivata da un elenco di file. Non serve discovery automatica.

### Stato di navigazione

Il capitolo attualmente visualizzato è evidenziato nella sidebar. Navigare fra capitoli non perde lo scroll position (o lo resetta al top, entrambi accettabili per lo spike).

## 4. Cosa NON fare in questo spike

- Non toccare `structure.cljs` (gli esempi restano nei commenti HTML dei draft, non vengono estratti)
- Non implementare il sistema shortcode `{{example: id}}`
- Non gestire il bilinguismo
- Non costruire il sistema di caption per gli esempi
- Non implementare il search nel pannello
- Non fare la migrazione del contenuto vecchio di `content.cljs` (che resta disponibile come fallback)
- Non gestire i capitoli 1-6 (che vivono già in `dev-docs/manual-drafts/` ma con un formato leggermente diverso; lo spike parte dai capitoli 7+)

## 5. Criteri di accettazione

1. Il pannello manuale mostra la lista dei capitoli 7-12 nella sidebar
2. Click su un capitolo mostra il Markdown formattato
3. I blocchi ```clojure``` appaiono come CodeMirror read-only
4. I blocchi con `<!-- example-source -->` hanno un bottone Run
5. Click su Run esegue il codice dell'example-source e il risultato appare nel viewport
6. I commenti HTML non appaiono nel testo

## 6. Dry-run consigliato

Prima di implementare, verificare:

1. Che `marked` (o il parser scelto) gestisca correttamente i commenti HTML nei draft (non li strippi, o se li strippa, che siano estraibili prima del parsing)
2. Che `run-definitions!` (o l'API equivalente per eseguire codice dal pannello) sia accessibile dal namespace del pannello manuale
3. Che CodeMirror read-only nel pannello funzioni con il setup attuale (potrebbe essere già usato negli esempi del manuale vecchio)

Il punto 1 è il più critico: se `marked` rimuove i commenti HTML prima che possiamo leggerli, bisogna estrarre gli example-source dal Markdown raw prima di passarlo al parser.
