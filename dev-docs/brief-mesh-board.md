# Brief: mesh-board — direttive di visualizzazione scaffold con confronto e fedeltà

## Contesto

Il brief centrale della fase 3. Riferimenti vincolanti: `dev-docs/mesh-board-design.md` (v2, con la cittadinanza riformulata sull'inerzia referenziale) e i due accertamenti (`mesh-board-accertamento.md`, `mesh-board-mini-accertamento.md`). Fatti su cui il brief si appoggia:

- Il valore-albero **previsto** è la mappa piatta nome→mesh (proposta dell'accertamento grande, Q1) — ma l'`emit` attuale chiude ancora il body con un **vettore** (`mesh_split_tree.cljs:384-405`, verificato sul sorgente prima di questo brief): la mappa era una proposta documentata, mai implementata. L'adeguamento è la Parte 0. Le foglie portano la creation-pose condivisa.
- `attach` su un vettore di mesh funziona già (group-transform condiviso con `transform`, gruppo rigido verificato); **su una mappa è oggi un no-op silenzioso** (mini-accertamento Q1, falsificato dal vivo; entry nel tracker) — il fix è parte di questo brief.
- Il layer scaffold non ha slot pronte: è un trittico registry+viewport+toggle da copiare 1:1 dal pattern di `stamp` (~70 righe totali, mappa dettagliata nel mini-accertamento Q2, inclusi i due pattern di push: eval completo = sostituzione, eval incrementale = append).
- Raycast: **zero modifiche** — la misura shift+click è scena-based (gli scaffold sono misurabili gratis), il pick strutturale filtra su `registryName` (gli scaffold ne sono esclusi gratis).
- Costi del confronto già misurati (accertamento grande, Q5): fedeltà + differenza renderizzata on-eval 29–96 ms su pezzi realistici — comodo per una direttiva valutata per-eval, nessun ricalcolo live richiesto.
- Il frame naturale per lo script sostitutivo è la cut-pose (accertamento grande, Q4): rilevante per la documentazione e gli esempi, non per il codice di questo brief.

## Lavoro richiesto

### Parte 0 — Il body emesso diventa una mappa

`emit` chiude il body con la **mappa nome→mesh** (chiavi = i nomi dei pezzi come keyword, `{:piece-1 piece-1 :piece-2 piece-2 …}`) invece del vettore. Compatibilità: i sorgenti già emessi col vettore restano validi e continuano a valutare — per questo `mesh-board` e `attach` accettano anche i vettori (Parti 1 e 3); la mappa è il formato d'ora in avanti, non una migrazione forzata. Verificare esplicitamente che il cambio di body **non tocchi il re-entry** (il localizzatore matcha la call, non il body — ma va verificato, non assunto) né l'emissione in sessione ri-aperta.

### Parte 1 — attach sulle collezioni (fix del no-op muto)

- Nuovo ramo di dispatch in `attach` per le **mappe** di mesh: `(vals target)` → group-transform → `(zipmap (keys target) …)`. Il contenitore si preserva (mappa dentro → mappa fuori, stesse chiavi; i vettori già si preservano). La matematica è quella esistente di `group-attach-impl`/`group-transform`: zero matematica nuova.
- Pose: gruppo rigido — disposizioni relative preservate, la pose condivisa resta condivisa e avanza coerentemente.
- **Il no-op muto muore**: `attach` su un argomento non supportato (mappa che non è collezione di mesh, tipo estraneo) → errore leggibile che nomina il tipo ricevuto. Mai più il ritorno silenzioso dell'argomento.
- Casi bordo documentati: mappa vuota → mappa vuota (gruppo vuoto, niente da muovere, non è un errore); collezione a un elemento → contenitore preservato.

### Parte 2 — Layer scaffold (trittico stile stamp)

Copiare il pattern di `stamp` come mappato nel mini-accertamento Q2:

- Registry: atom `scene-scaffolds` + `set-scaffolds!`/`add-scaffolds!` (mirror di `scene-stamps`).
- Viewport: `update-scaffolds-display`/`dispose-scaffolds-object!` con remove+dispose espliciti del gruppo precedente (mirror di `update-stamps-display`); materiale **ghost-wireframe** riusando la tecnica esistente (inset / `create-wireframe-mesh`).
- Toggle "boards" in toolbar: atom `scaffolds-visible` + bottone + wiring + classe `.active`, mirror di `stamps-visible`. **Nessuna scorciatoia da tastiera** (nessuno dei sei toggle esistenti ne ha; non è richiesta).
- Gli oggetti scaffold vanno sotto `world-group` **senza** `registryName`: misurabili via shift+click e esclusi dal pick strutturale per costruzione (verificato in accertamento; il test permanente sta in Verifica).

### Parte 3 — La direttiva mesh-board

- `(mesh-board t)` — mostra come scaffold, **in place**, tutte le foglie di `t`: **mappa** nome→mesh, **vettore** di mesh (le vecchie emissioni: tutto visibile, ma senza nomi), oppure una **singola mesh** (totalità: una foglia sola).
- `(mesh-board t {:only [:piece-2 :piece-3]})` — sottoinsieme per nome, **solo per input mappa** (i nomi vivono nelle chiavi): su vettore o mesh → errore leggibile che spiega perché; nome inesistente → errore leggibile che lo nomina.
- `(mesh-board foglia candidato {:mode :overlay|:intersection|:diff})` — confronto: la foglia come scaffold, il candidato secondo il modo. `:overlay` (default): entrambi visibili sovrapposti; `:intersection`: la parte comune renderizzata; `:diff`: la **differenza simmetrica renderizzata come geometria scaffold** — si vede dove il candidato devia. In tutti i modi di confronto, alla valutazione viene **stampata la fedeltà** con **etichette fisse** — es. `mesh-board: riferimento vs candidato — fedeltà 97.3%` — più un **`:label` opzionale** nelle opts (`{:mode :diff :label "gancio-sx"}`) che compare nel messaggio, per disambiguare più confronti coesistenti nello stesso programma. `mesh-board` resta una **funzione** (mai macro con `&form`): la cattura dei nomi delle variabili non vale la perdita di componibilità (`run!`, `map`, threading).
- **Pass-through**: `mesh-board` ritorna il suo **primo argomento identico** (l'albero o la foglia), per la composizione nei threading — `(-> t (attach (f 10)) (mesh-board))`. È l'inerzia referenziale: il test in Verifica la salda.
- **Più direttive coesistono**: ogni `(mesh-board …)` nel programma contribuisce i suoi scaffold; l'accumulatore per-valutazione (lo stesso innesto di `stamp`) le raccoglie tutte nella run e sostituisce il set in blocco. Test dedicato in Verifica.
- Ciclo di vita per-valutazione, coi **due pattern di push di stamp**: eval completo = sostituzione (`set-scaffolds!`), eval incrementale REPL = append (`add-scaffolds!`).
- Nessun `:off` nel linguaggio (design doc, alternativa scartata); il muting è il toggle della Parte 2.

### Parte 4 — Garanzie di cittadinanza

- **Export**: verificare il percorso di export corrente e garantire che la geometria scaffold **non** vi entri; se il percorso attuale la includerebbe, escluderla (meccanismo a scelta motivata, riportato). Test permanente in Verifica.
- **Nessun type-wrapper anti-CSG**: i pezzi dell'albero restano dati ordinari e CSG-abili by design (l'ibrido li usa). La garanzia è l'inerzia referenziale (pass-through + pipeline di soli dati display + export escluso), come da design doc v2.

## Verifica

- Parte 0: una nuova emissione chiude con la mappa (chiavi keyword = nomi dei pezzi); una forma vecchia col body a vettore valuta ancora e `(mesh-board vettore)` la mostra; re-entry su una forma col body nuovo → invariato (commit coerente).
- Parte 1: albero reale a 2–3 foglie, `(attach t (f 10))` → volumi invariati, disposizioni relative preservate, pose condivisa ancora condivisa e avanzata; mappa dentro → mappa fuori con stesse chiavi; tipo non supportato → errore leggibile (il no-op muto ha il suo test di regressione); mappa vuota → mappa vuota.
- Parte 2/3, live: `(mesh-board t)` mostra gli scaffold ghost-wireframe in place; rivalutazione senza la call → scaffold spariti; eval incrementale → append; toggle boards on/off; shift+click misura su uno scaffold funziona; alt-click/pick strutturale su uno scaffold è inerte.
- Coesistenza: due `mesh-board` nello stesso programma (es. un albero + una mesh di controllo non derivata dallo split) → entrambi gli scaffold visibili; rimozione di una sola call → solo i suoi scaffold spariscono alla rivalutazione.
- Pass-through: `(identical? t (mesh-board t))` (o uguaglianza strutturale se identical? non regge il pass-through, documentato) — l'inerzia referenziale come test.
- Confronto: coppia nota (pezzo vs copia → 100%; pezzo vs copia con boss aggiunto → fedeltà attesa dal volume del boss); `:diff` rende la geometria della deviazione; la fedeltà stampata coincide col calcolo indipendente via `mirror?`-machinery; etichette fisse di default e `:label` che compare nel messaggio quando fornito.
- `{:only …}` con nome inesistente → errore leggibile.
- Export con board attivo: il file esportato non contiene geometria scaffold (test permanente).
- Test WASM con idioma skip; citare nel report l'entry di `code-issues.md`; chiudere nel tracker l'entry del no-op di `attach` su mappa (fixata qui).
- **Accettazione sul mount reale (desktop)**: flusso completo — decomposizione, `(mesh-board t)`, script di ricostruzione di un pezzo partendo dalla cut-pose (Q4), confronto `:diff` con fedeltà stampata, sostituzione manuale nel sorgente, `(mesh-board t {:only […]})` sul resto. Riportare frizioni.
- Suite completa verde; documentazione (reference card `mesh-board`, aggiornamento card `attach` per le collezioni, rigenerazione indice) come pass finale.

## Fuori scope

- Riscrittura assistita e caduta dell'impalcatura (brief successivo; prerequisito: il localizzatore preciso già nel tracker).
- Scorciatoia da tastiera per il toggle; `:off` nel linguaggio; snap della turtle a vertici/facce scaffold (la misura shift+click basta per ora; lo snap si riapre con un caso d'uso).
- Canale scan (repair, PLY/OBJ, calibrazione di scala): brief a parte, dopo la riconferma su scansione reale.

## Alternative considerate e scartate

- **`mesh-board` come macro con cattura di `&form`** (per stampare i nomi delle variabili nei messaggi di fedeltà): scartato — la cittadinanza da funzione (componibile in `run!`/`map`/threading, pass-through pulito) vale più dei nomi catturati; le etichette fisse + `:label` opzionale coprono la disambiguazione senza macro.
- **Type-wrapper che `coerce-to-meshes` rifiuta** (ipotesi del mini-accertamento per la garanzia CSG): scartato — protegge da un percorso che non esiste (gli scaffold non rientrano in userland) e vieterebbe ciò che è legittimo (i pezzi dell'albero devono restare CSG-abili). La garanzia vera è l'inerzia referenziale.
- **Riuso dell'innesto dell'inset** per gli scaffold: scartato dal mini-accertamento — l'inset è guidato da push espliciti di sessione, la direttiva deve reagire a ogni rivalutazione: l'innesto giusto è quello di stamp. Dell'inset si riusa la tecnica (ghost, clear+rebuild).
- **Ricalcolo live del confronto durante la digitazione**: non pertinente per una direttiva (si valuta alla eval); i costi Q5 lo confermano comodo on-eval.
- Le alternative architetturali (sessione-workplace, `:off`, attach sul display) restano scartate nel design doc; non riaprirle.
