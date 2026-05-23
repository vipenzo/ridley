# Brief: Aggiornamento Spec.md + popolamento allowlist

## Rationale

Code ha completato l'audit T-001 (`docs/symbol-audit-report.md`): 107 simboli classificati in 30 A, 62 B, 14 C, validato da Vincenzo + Claude. Questo brief chiude il terzo lavoro previsto in §6.9 del piano: integrare nel sorgente di verità (Spec.md + allowlist) i risultati dell'audit.

Ci sono tre lavori distinti:

1. **Aggiungere a Spec.md i 30 simboli classificati A**, distribuendoli nelle categorie esistenti dove hanno senso e creando una nuova categoria "Math utilities" per i sei `vec3-*`.
2. **Aggiungere a Spec.md una sezione dedicata `## 19. Internals` con i 62 simboli classificati B**, organizzati nelle 8 sotto-categorie definite in §6.8 del piano (la sezione `## 18. Not Yet Implemented` resta in coda).
3. **Popolare `scripts/spec_allowlist.edn`** con i 14 simboli C + i ~190 plumbing già esclusi dal pre-filtro dell'audit. Ogni voce deve avere un commento `;;` che spiega *perché* è esposta a SCI ma non documentata.

Dopo questo brief, lo script di lint (`scripts/spec_lint.bb`) deve passare con exit code 0: ogni simbolo bound è documentato in Spec o presente in allowlist.

## Riferimenti al piano

- §6.7 "Criterio editoriale di destinazione" — definizione delle destinazioni A/B/C
- §6.8 "Articolazione delle Internals" — le 8 sotto-categorie di destinazione per i simboli B (versione aggiornata post-audit: Contracts, Registry pattern, Scene visibility, Picking & selection, Interactive testing, Animation API, Collisions & pilot, Source tracking & metaprogramming, Runtime settings)
- §10.1 "Prerequisiti paralleli" — questo lavoro è (3) della lista, prerequisito del brief B

## Riferimenti ai deliverable T-001

- `docs/symbol-audit-report.md` — classificazione validata dei 107 simboli sospetti
- `scripts/spec_lint.bb` — lint script, già operativo, oggi segnala 201 orfani
- `scripts/spec_allowlist.edn` — file allowlist creato vuoto da T-001, da popolare in questo brief

## Decisioni editoriali prese in chat (validano l'audit)

Sono già fissate nel piano §14.5; le riassumo qui perché impattano direttamente la stesura:

- **`claim-mesh!` → C** (chiarito post-validazione: è interno al meccanismo di `register`, non un'API per chi scrive macro register-like).
- **Tagging Internals in Spec.md → sezione separata** (opzione A delle alternative discusse). Una nuova sezione `## 19. Internals` raccoglie i 62 simboli B suddivisi nelle 8 sotto-categorie. La sezione `## 18. Not Yet Implemented` traslerà a `## 20. Not Yet Implemented`.
- **`vec3-*` → nuova categoria "Math utilities"**. Sei simboli (`vec3+`, `vec3-`, `vec3*`, `vec3-cross`, `vec3-dot`, `vec3-normalize`) operatori vettoriali per coordinate 3D. Diventano una sotto-sezione nuova all'interno di `## 17. Variables, Functions, Math` (subito dopo "Math functions"), titolo proposto: `### Vector math (3D)`.
- **Pattern bang `!` per le versioni user-facing**: dove esiste sia la funzione plain (`set-creation-pose`) sia il macro con bang (`set-creation-pose!`), il macro è quello user-facing; la funzione plain è plumbing. Da tenere a mente quando si valutano dubbi residui durante la stesura.

## Convenzioni di output

- I file modificati da questo brief sono **`docs/Spec.md`** e **`scripts/spec_allowlist.edn`**: hanno la loro casa per la natura del progetto (Spec.md è il source-of-truth pubblico; l'allowlist sta accanto al lint script che la consuma). Non spostarli.
- Qualunque **report, nota, o documento intermedio** prodotto da Code in questo task va in **`/dev-docs/`**, non in `/docs/`. Convenzione del repo: `/docs/` contiene materiale stabile e pubblico, `/dev-docs/` contiene materiale interno o temporaneo (brief, audit, report di lavoro).
- Se durante l'esecuzione emergono note che vale la pena fissare (es. simboli ambigui residui, decisioni prese in autonomia, anomalie nei sorgenti), scriverle in un file `dev-docs/spec-update-notes.md` o equivalente, *non* in `/docs/`.
- Il report finale a Vincenzo (vedi "Output complessivo" in coda) può essere in chat, senza creare un file dedicato — a meno che Code non lo trovi utile per la propria traccia, nel qual caso anche quello vive in `/dev-docs/`.

## Task 1 — Aggiungere i 30 simboli A a Spec.md

### Input

La colonna "A" del report `docs/symbol-audit-report.md`. Sono 30 simboli, con `claim-mesh!` non incluso (è C). I sorgenti sono nei file menzionati nella colonna "Note" del report.

### Distribuzione per categoria di Spec.md

I 30 simboli si distribuiscono come segue. Dove una categoria esiste già, aggiungere alla tabella esistente o creare una sotto-sezione interna se la categoria è composita. Dove indicato "nuova sotto-sezione", aggiungere una sezione `### Titolo` nuova all'interno della sezione di livello 2 indicata.

| Simbolo | Categoria di Spec.md | Note |
|---|---|---|
| `sdf-ensure-mesh` | §11 SDF Modeling, sub "Materialization" | aggiungere alla prosa esistente |
| `sdf-node?` | §11 SDF Modeling, nuova sub "Predicates" | unico simbolo della sub |
| `chamfer-edges` | §7 Mesh Operations, sub "3D Chamfer & Fillet" | aggiungere alla tabella |
| `chamfer-prisms` | §7 Mesh Operations, sub "3D Chamfer & Fillet" | aggiungere alla tabella |
| `shape-bridge` | §4 2D Shapes, sub "2D Booleans" | aggiungere come sotto-funzione |
| `slice-at-plane` | §7 Mesh Operations, sub "Cross-section (slice)" | variante con piano esplicito, NON con turtle |
| `find-sharp-edges` | §7 Mesh Operations, nuova sub "Edge analysis" | introduce la sub |
| `lay-flat` | §7 Mesh Operations, nuova sub "Orientation utilities" | introduce la sub |
| `extrude-text` | §12 Text, sub "Text shapes" | shortcut per text-shape + extrude |
| `shape?` | §4 2D Shapes, nuova sub "Predicates" | introduce la sub |
| `path?` | §3 Paths, sub "Path utilities" | aggiungere |
| `panel?` | §13 Scene, sub "3D Panels" | aggiungere |
| `svg-shapes` | §16 Export, contro-direzione: import → nuova §16.bis "Import" oppure §4 sub "Import" | scegliere la collocazione meno disruptive — vedi sotto |
| `decode-mesh` | §16 Export (rinominare in "Import/Export") oppure stessa logica di svg-shapes | stesso ragionamento |
| `save-3mf` | §16 Export, sub "STL export" | rinominare la sub in "STL/3MF export" se non già fatto |
| `save-stl` | §16 Export, sub "STL export" | spesso è alias di `(export ... :3mf? false)` — verificare |
| `save-mesh` | §16 Export, sub "STL export" | idem |
| `text-width` | §12 Text, sub "Text shapes" | utility per layout |
| `down` (alias di `d`) | §9 Positioning & Assembly, sub "Lateral movement" | aggiungere alla tabella come alias |
| `transform` | §9 Positioning & Assembly, sub "Top-level transforms" | alias di `mesh-translate`/`mesh-scale`/... — verificare; oppure documentare come polymorphic transform combinata |
| `pen-down` | §2 Turtle, sub "Pen control" | aggiungere alla tabella esistente come "alias di `(pen :on)`" |
| `pen-up` | §2 Turtle, sub "Pen control" | aggiungere come "alias di `(pen :off)`" |
| `extrude-y` | §6 Generative Operations, sub "Extrude" | menzionare come convenience |
| `extrude-z` | §6 Generative Operations, sub "Extrude" | menzionare come convenience |
| `vec3+` | §17 Variables, Functions, Math, **nuova sub `### Vector math (3D)`** | sei simboli vec3-* |
| `vec3-` | id. | |
| `vec3*` | id. | |
| `vec3-cross` | id. | |
| `vec3-dot` | id. | |
| `vec3-normalize` | id. | |

### Decisioni sub-locali (lasciate al giudizio di Code)

Tre simboli (`svg-shapes`, `decode-mesh`, eventualmente `save-*`) hanno una collocazione non ovvia perché §16 si chiama "Export" ma include anche import logico. Due opzioni equivalenti:

- **Rinominare §16 in "Import/Export"** e aggiungere una sub "Import" prima della sub "STL export". Più pulito ma cambia il titolo della sezione, propagandosi alla TOC.
- **Lasciare §16 com'è** e mettere `svg-shapes` in §4 2D Shapes (nuova sub "Import"), `decode-mesh` in §7 Mesh Operations (nuova sub "Import"). Più localmente coerente ma frammenta l'import in due sezioni.

Sceglie Code, motivando in 1-2 frasi nel report finale.

Allo stesso modo: se durante la stesura emerge un simbolo che non rientra naturalmente nella categoria indicata in tabella, può essere ricollocato — segnalandolo nel report finale con motivazione.

### Stile editoriale

Spec.md ha uno stile riconoscibile:

- Tabelle markdown `| Function | Description |` per liste di funzioni omogenee (signature + 1-frase descrizione).
- Blocchi clojure annotati per signature più complesse o varianti.
- Prosa breve tra una tabella e l'altra per spiegare il dominio.
- Opzioni come tabella `| Option | Default | Description |`.
- Esempi clojure brevi, idiomatici, eseguibili.

Per i simboli A che sono **alias** (`down` di `d`, `pen-up`/`pen-down` di `(pen :off)`/`(pen :on)`), aggiungere alla tabella esistente segnalando "alias di X". Non duplicare la descrizione completa.

Per i simboli che sono **convenience** (`extrude-y`, `extrude-z`), 2-3 righe di prosa nella sub Extrude + signature.

Per i simboli che meritano una sub propria (`find-sharp-edges` come "Edge analysis"), seguire lo schema delle sub esistenti: titolo `###`, 1-2 frasi di intro, signature, 1-2 esempi.

### Output

Spec.md aggiornato, con i 30 simboli A integrati nelle posizioni indicate, includendo:

- Signature corretta (dalla lettura del sorgente Clojure).
- Descrizione 1-2 frasi, in stile coerente con il resto del file.
- Eventuali esempi brevi dove utile (in particolare per i simboli con sub propria).
- Nuove sub `### ...` create dove indicato in tabella.

### Non-goals

- Non scrivere le schede Reference dei simboli A (sono Task del Flusso A futuro).
- Non riorganizzare Spec.md oltre alle aggiunte necessarie. Mantenere stabile l'ordine e il titolo delle sezioni esistenti.
- Non aggiornare il file RAG (`gen_rag_chunks.bb`) — la rigenerazione del RAG è automatica e successiva.

## Task 2 — Aggiungere §19 Internals a Spec.md con i 62 simboli B

### Posizionamento nel file

Inserire una nuova sezione `## 19. Internals` **prima** dell'attuale `## 18. Not Yet Implemented`. L'effetto è che §18 traslerà a §20. Aggiornare i riferimenti incrociati in Spec.md se ce ne sono che puntano a §18 (verifica con `grep -n "§18\|#18-" Spec.md`).

### Struttura della nuova sezione

```markdown
## 19. Internals

> **Per chi estende Ridley con codice proprio.** Questa sezione documenta API
> di basso livello esposte a SCI per chi scrive librerie di pezzi
> riutilizzabili, codice procedurale che interagisce con la scena, o
> integrazioni custom. Non sono parte ordinaria della scrittura di programmi
> Ridley: se stai scrivendo un programma Ridley e ti trovi qui per caso,
> probabilmente cercavi la funzione user-facing equivalente in una delle
> sezioni precedenti.

### 19.1 Registry pattern

[Prosa introduttiva: cos'è il registry, dispatch via :type.]

[Tabella o blocchi clojure per i 6 simboli "Registry pattern":
register-mesh!, register-path!, register-shape!, register-value!,
register-panel!, add-mesh!]

### 19.2 Registry introspection

[Prosa: differenza tra lookup (get-X) e elenco (X-names / visible-X).]

[Tabella per i 10 simboli "Registry introspection":
get-mesh, get-path, get-shape, get-panel,
registered-names, path-names, shape-names, visible-names, visible-meshes,
all-meshes-info]

### 19.3 Scene visibility

[Prosa: visibilità delle mesh, della turtle, refresh forzato.]

[Tabella per gli 11 simboli "Scene visibility":
show-mesh!, hide-mesh!, show-mesh-ref!, hide-mesh-ref!,
show-all!, hide-all!, show-only-registered!, show-by-prefix!, hide-by-prefix!,
show-turtle, hide-turtle, refresh-viewport!]

### 19.4 Picking & selection

[Prosa: selezione corrente via picking del viewport.]

[Tabella per i 7 simboli "Picking & selection":
selected, selected-face, selected-mesh, selected-name,
origin-of, last-op, source-of]

### 19.5 Interactive testing

[Prosa: modalità tweak interattivo, introspezione delle settings turtle.]

[Tabella per i 4 simboli "Interactive testing":
tweak-start!, tweak-start-registered!,
get-turtle-resolution, get-turtle-joint-mode]

### 19.6 Animation API

[Prosa: hook a basso livello per macro anim! / anim-proc!.]

[Tabella per gli 8 simboli "Animation API":
anim-make-cmd, anim-make-span,
anim-register!, anim-proc-register!,
anim-preprocess, anim-clear-all!,
get-camera-pose, get-orbit-target]

### 19.7 Collisions & pilot

[Prosa: collision handlers e pilot mode.]

[Tabella per i 6 simboli "Collisions & pilot":
on-collide, off-collide, reset-collide,
list-collisions, clear-collisions,
pilot-request!]

### 19.8 Source tracking & metaprogramming

[Prosa: source-history e source-form per mesh registrate.]

[Tabella per i 5 simboli "Source tracking & metaprogramming":
add-source, source-of, source-ref,
get-source-form, set-source-form!]

### 19.9 Runtime settings

[Prosa: setting accessibility, ambiente runtime.]

[Tabella per i 5 simboli "Runtime settings":
desktop?, env,
audio-feedback?, set-audio-feedback!,
run-definitions!]
```

### Conteggio per sotto-categoria

I 62 si distribuiscono come segue (somma totale: 62):

- Registry pattern: 6
- Registry introspection: 10
- Scene visibility: 11
- Picking & selection: 7
- Interactive testing: 4
- Animation API: 8
- Collisions & pilot: 6
- Source tracking & metaprogramming: 5
- Runtime settings: 5

(Il piano §6.8 elenca le sotto-categorie ma non i conteggi; il riferimento di verità per la classificazione dei singoli simboli è `docs/symbol-audit-report.md`.)

### Sotto-categoria Contracts: vuota

La sotto-categoria **Contracts** (shape-fn contract, thickness-fn contract, Pattern: collection inputs) di §6.8 del piano non riceve simboli da questo audit. Non aggiungere a Spec.md una sotto-sezione `### 19.X Contracts` vuota: shape-fn e thickness-fn sono macro-driven, non simboli bound diretti; le schede Internals verranno scritte a mano in fase di stesura Reference. La sotto-sezione si aggiungerà a Spec.md in un secondo momento se emergerà necessità di documentare contract a livello di Spec.

### Stile editoriale

- Le tabelle in §19 sono `| Function | Description |` esattamente come nel resto di Spec.md. Una riga per simbolo, signature inline nella colonna Function, descrizione di 1-2 frasi nella colonna Description.
- La prosa di intro sotto ciascun `### 19.X Titolo` è 2-4 righe massimo, abbastanza per orientare ma non per spiegare. La spiegazione approfondita vivrà nelle schede Reference Internals.
- Gli esempi clojure sono **opzionali** in §19 e vanno usati solo se la signature da sola non basta a capire come si chiama (es. callback signature dell'Animation API). Quando l'esempio c'è, deve essere breve (3-5 righe).

### Non-goals

- Non spiegare diffusamente *quando* usare un'API Internals — quello è materiale delle schede Reference future.
- Non aggiungere all'allowlist i simboli B (sono in Spec, quindi automaticamente non orfani dal punto di vista del lint).

## Task 3 — Popolare `scripts/spec_allowlist.edn`

### Input

- I 14 simboli C dell'audit (vedi report).
- I ~190 simboli plumbing esclusi dal pre-filtro dell'audit (suffisso `-impl`, prefissi `pure-`/`rec-`/`turtle-`, dynamic vars `*…*`, costruttori-helper come `make-recorder`/`make-shape`/...).

L'unione attesa è ~204 simboli (i 201 orfani al primo run del lint, meno quelli che T-001/T-002 hanno aggiunto a Spec.md, più gli eventuali nuovi A/B di Task 1-2).

### Comportamento atteso del lint dopo questo brief

Dopo `bb scripts/spec_lint.bb` con Spec.md aggiornato (Task 1+2) e allowlist popolata (Task 3):

```
✓ 0 simboli bound ma non documentati né in allowlist.
```

Exit code 0.

### Formato dell'allowlist

Conservare il formato `.edn` già creato in T-001. Per ciascun simbolo, un commento `;;` sulla riga immediatamente sopra che spiega *perché* è esposto a SCI senza essere documentato. Raggrupare per famiglia con un commento di separazione, in stile:

```clojure
{:symbols
 #{
   ;; ============================================================
   ;; Macro implementation helpers (suffix -impl)
   ;; Generated by macros like `path`, `extrude`, `with-path`; never
   ;; called directly by user code.
   ;; ============================================================

   ;; Helper of `path` macro
   path-impl

   ;; Helper of `extrude` macro
   extrude-impl

   ;; ...

   ;; ============================================================
   ;; Anchor support functions (suffix *)
   ;; Internal helpers of `with-path` / `anchor` macros.
   ;; ============================================================

   ;; Save anchor state before macro body
   save-anchors*

   ;; Restore anchor state after macro body
   restore-anchors*

   ;; ...
 }}
```

I commenti possono ripetersi (un commento sul "perché" della famiglia + un commento breve `;;` per simbolo) o essere solo a livello di famiglia se i simboli sono evidenti dal contesto. Code sceglie il livello di granularità che bilancia ridondanza vs leggibilità.

### Tassonomia delle famiglie attese

Sulla base del pre-filtro dell'audit T-001, le famiglie principali sono:

1. **Macro implementation helpers** (suffisso `-impl`, ~39 simboli)
2. **Shadow pure functions** (prefisso `pure-`, ~11 simboli)
3. **Recording helpers** (prefisso `rec-`, ~17 simboli)
4. **Low-level turtle namespace** (prefisso `turtle-`, ~24 simboli)
5. **Anchor support functions** (suffisso `*`, ~3 simboli + `save-anchors*`, `restore-anchors*`, `resolve-and-merge-marks*`)
6. **Dynamic vars** (`*eval-source*`, `*eval-text*`, `*turtle-state*`, ~3 simboli)
7. **Constructors-helper** (`make-recorder`, `make-shape`, `make-turtle`, ecc., ~6-8 simboli)
8. **Debug / introspection internals** (`perf-now`, `print-bench`, `pr`, `anonymous-count`, `anonymous-meshes`, ~5 simboli)
9. **Legacy aliases** (`ops-extrude`, `ops-loft`, `path-`, `path-from-recorder`, `init-turtle`, `set-creation-pose`, ~6 simboli)

Code è libero di proporre una tassonomia più fine o più grossa, purché ogni simbolo dell'allowlist abbia un commento intelligibile sul *perché* è plumbing.

### Output

`scripts/spec_allowlist.edn` popolato, lint che passa con exit 0.

### Non-goals

- Non aggiungere in allowlist nessun simbolo che è in Spec.md (sarebbe ridondante; il lint controlla "in Spec OR in allowlist", non lo richiede).
- Non implementare logiche aggiuntive nel lint script. Lo script è già operativo da T-001, basta che continui a funzionare con un'allowlist popolata.

## Output complessivo del brief

Quattro deliverable:

1. `docs/Spec.md` aggiornato — 30 simboli A integrati, nuova §19 Internals con 62 simboli B, §18 → §20.
2. `scripts/spec_allowlist.edn` popolato — ~204 simboli con commenti `;;` raggruppati per famiglia.
3. Verifica che `bb scripts/spec_lint.bb` esca con exit 0.
4. Breve report in conversazione con Vincenzo, che dichiari:
   - Conferma del conteggio finale (atteso: 30 A aggiunti, 62 B aggiunti, ~204 in allowlist, 0 orfani).
   - Eventuali simboli per cui la categoria di Spec.md proposta in Task 1 è stata cambiata, con motivazione.
   - Eventuali simboli ambigui residui che Code raccomanda di ridiscutere.
   - Decisione presa su §16 ("Export" vs "Import/Export" — vedi Task 1).

## Riassunto sequenza operativa

1. Code legge `docs/symbol-audit-report.md` (input principale) e questo brief.
2. Code legge i sorgenti per ricavare signature precise dei simboli A.
3. Code modifica `docs/Spec.md`: aggiunte progressive per Task 1, nuova §19 per Task 2.
4. Code popola `scripts/spec_allowlist.edn` per Task 3.
5. Code esegue `bb scripts/spec_lint.bb` e verifica exit 0.
6. Code riporta a Vincenzo in conversazione.

Vincenzo valida in chat. Le modifiche a Spec.md e all'allowlist sono "spinte" dal brief: se al passo 6 emergono dubbi o disaccordi puntuali, si correggono in iterazione, non a monte.
