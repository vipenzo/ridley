# Brief: `move-to :from` e `:mate` (mating anchor-su-anchor)

## Contesto

Oggi `move-to` dentro `attach` fa combaciare la **creation-pose della mesh mobile** con un anchor del target. Il punto di aggancio sul lato mobile non è nominabile: gli anchor con nome funzionano solo sul lato target, e per usare una feature diversa dalla creation-pose come punto di mating serve la ginnastica `cp-f`/`cp-th`. Questa asimmetria è il primitivo mancante identificato nella voce Roadmap "Estensione del sistema di assemblaggio" (la parte viewport picking + code generation è la tranche 2, fuori scope qui).

Punti di aggancio nel codice:

- Il replay di `move-to` vive in `src/ridley/editor/impl.cljs`: `move-to-dispatch` (parsing e dispatch), `move-to-pose` (forma creation-pose), `move-to-anchor` (forme `:at`/`:on`/`:face`), `align-attached-mesh` (rotazione della mesh per portare un frame su un altro, con gestione del caso antiparallelo), `move-state-to-position` (traslazione via decomposizione in f/th/tv), `resolve-mesh` (risoluzione target).
- La registrazione è pass-through: `rec-move-to` in `src/ridley/turtle/core.cljs` appende `{:cmd :move-to :args (into [target] args)}` senza interpretare gli args, e `rec-move-to*` in `src/ridley/editor/macros.cljs` è un forward. Le nuove opzioni fluiscono da sole fino al dispatch: **il recorder non va toccato** (verificarlo con un test, non assumerlo).
- Gli anchor della mesh mobile sono world pose tenute in sync da `transform-poses` in `src/ridley/turtle/attachment.cljs`, quindi dentro l'attach sono leggibili da `[:attached :mesh :anchors]` e sono sempre aggiornate rispetto ai comandi precedenti del corpo attach.
- Il parsing attuale di `move-to-dispatch` assume `mode = (second args)`. Con `(move-to target :from :plug :mate)` il secondo arg sarebbe `:from`: serve una **normalizzazione delle opzioni** prima del dispatch (estrarre `:from <val>` e il flag `:mate` dagli args, poi dispatchare il resto come oggi).

### Decisioni prese (con alternative rigettate)

1. **`:from <anchor>`**: nuova opzione di `move-to` che elegge un anchor della mesh mobile (invece della posa corrente della tartaruga, che all'inizio dell'attach coincide con la creation-pose) come frame di riferimento del mating. Accetta un keyword (anchor della mesh in attach) oppure una pose map esplicita `{:position … :heading … :up …}` (necessaria alla futura codegen dal viewport, che sintetizza frame da centroide + normale di facce senza mark). *Rigettata*: una funzione top-level `assemble` come primitivo. Duplicherebbe la semantica "torna una copia" che attach ha già e comporrebbe peggio col resto del vocabolario (`:on`, `:face`, `cp-*`, chaining). Un eventuale zucchero `assemble` potrà essere un wrapper sottile, dopo uso reale.
2. **`:mate`**: allinea il frame dell'anchor mobile sul **frame target composto con `th 180`**. È una rotazione propria (heading e right negati, up conservato), sempre realizzabile, che corrisponde fisicamente a girare il pezzo attorno all'asse verticale per portarlo faccia a faccia: due frecce "nord" disegnate sulle facce di mating restano concordi. `:mate` implica la rotazione (è una variante di `:align`; specificarli entrambi è ridondante ma innocuo). *Rigettata*: la convenzione `tv 180` (flip attorno al right, up su -up, il "libro che si chiude"): è la meno frequente nei mating meccanici ed è comunque ottenibile ruotando il mark nel path.
3. **Niente `:spin`**: la rotazione residua attorno alla normale di accoppiamento si esprime con `(tr α)` concatenato dopo il `move-to` (per-montaggio) oppure con un `(tr)` nella definizione del mark (proprietà dell'anchor). *Rigettata*: un'opzione `:spin` dedicata. Il `tr` concatenato è già nel vocabolario e funziona perché la posa post-operazione siede sull'asse di mating.
4. **Posa della tartaruga post-operazione = world pose dell'anchor mobile dopo l'operazione.** È un'estensione coerente della convenzione esistente ("la tartaruga adotta la posa su cui è andata"): senza `:mate` l'anchor mobile adotta il frame di destinazione tal quale, con `:mate` adotta il frame ruotato di th 180, e la tartaruga con lui in entrambi i casi. Conseguenze normative: dopo `:mate`, `(f -0.15)` arretra il pezzo lungo la normale della propria faccia aprendo il gioco di tolleranza FDM, `(f 0.15)` compenetra; `(tr α)` ruota attorno alla normale comune vista dal pezzo mobile. *Rigettata*: adottare il frame target anche dopo `:mate`. Spezzerebbe l'identità "posa tartaruga = posa anchor mobile" e costringerebbe a ragionare nel frame dell'altro pezzo proprio nei comandi concatenati che rifiniscono il montaggio.
5. **`:from` senza `:align`/`:mate` è traslazione pura**: la posizione dell'anchor mobile va sulla posizione di destinazione, la mesh non ruota, la tartaruga finisce sulla destinazione con il frame di destinazione (coerente con la forma default attuale). `:mate` con `:center` è un errore, come già `:align` con `:center`.

## Lavoro richiesto

1. In `move-to-dispatch`, normalizzare gli args estraendo `:from <val>` e il flag `:mate` prima del dispatch sul mode. Le nuove opzioni sono valide con la forma default (creation-pose), `:at`, `:on` e componibili con `:face`; errore esplicativo con `:center` e con target-anchor puri (keyword che risolvono ad anchor della tartaruga, che già oggi supportano solo default e `:align`).
2. Risoluzione di `:from`: keyword → lookup in `[:attached :mesh :anchors]` con errore esplicativo se l'anchor non esiste o se non c'è mesh in attach (un `move-to :from` fuori da attach non ha una mesh mobile); pose map → usata tal quale (accettare sia `:position` sia `:pos`, come fa già il macro `turtle` per le pose).
3. Calcolo della destinazione invariato (creation-pose / anchor / `:face` / `:on` compongono come oggi). Con `:mate`, comporre il frame di destinazione con th 180: heading e right negati, up invariato.
4. Trasformazione: traslare la mesh in modo che la **posizione dell'anchor mobile** (non la posizione della tartaruga) coincida con la posizione di destinazione; con `:align`/`:mate` ruotare la mesh con `align-attached-mesh` passando come frame corrente quello dell'anchor mobile e come pivot la posizione di destinazione. La gestione del caso antiparallelo già presente in `align-attached-mesh` (rotazione di 180° attorno all'up corrente) copre il caso frequente in `:mate` di facce già opposte.
5. Posa finale della tartaruga = world pose dell'anchor mobile dopo l'operazione (decisione 4). Senza `:from`, il comportamento attuale resta **bit-identico**: nessuna regressione sulle forme esistenti.
6. `sdf-move-to` (attach su SDF): rigettare `:from`/`:mate` con errore esplicativo che rimanda al supporto mesh (follow-up separato).
7. Aggiornare la sezione `move-to` di `Spec.md`: nuove forme in tabella, semantica di `:mate` (definizione th-180, chiralità up-su-up), esempio normativo di clearance con `(f -0.15)`, spin residuo via `(tr)`, nota sulla pose map come valore di `:from`.

## Verifica

- **Traslazione pura**: dopo `(move-to target :at :socket :from :plug)`, la posizione world dell'anchor `:plug` coincide con quella di `:socket`; la tartaruga ha il frame di `:socket`.
- **`:from :align`**: il frame dell'anchor mobile coincide col frame di destinazione (heading, up, e right coerente: det +1).
- **`:from :mate`**: heading mobile = -heading target, up = up target, right = -right target. Verificare che sia una rotazione propria e non una riflessione usando una mesh asimmetrica marcata e controllando la chiralità del risultato.
- **Trap test chiralità**: due mesh con una feature "nord" allineata all'up del rispettivo mark; dopo `:mate` le due feature sono concordi. La falsificazione (feature discordi) indicherebbe un errore nella composizione th-180 ed è un esito da documentare, non da nascondere.
- **Round-trip**: un mark target definito col `th 180` già nel path, usato con `:from … :align`, produce le stesse posizioni vertici di `:from … :mate` sul mark non ruotato. Se divergono, una delle due semantiche è sbagliata.
- **Clearance**: dopo `:mate`, `(f -0.15)` porta la distanza minima tra le due facce a 0.15; `(f 0.15)` compenetra (distanza 0 e overlap dei bounding box).
- **Spin**: `(tr 30)` dopo `:mate` ruota la mesh attorno alla normale comune lasciando invariata la posizione dell'anchor mobile.
- **Caso degenere**: facce già combacianti e opposte (ramo antiparallelo di `align-attached-mesh`): nessun NaN, risultato stabile e idempotente rispetto a un secondo `:mate` sugli stessi anchor.
- **Pass-through recorder**: un `(move-to … :from :plug :mate)` registrato in un path e replayato in attach produce lo stesso risultato della chiamata diretta.
- **Regressione**: tutta la suite esistente di `move-to` (default, `:align`, `:center`, `:at`, `:face`, `:on`, target path, target anchor) green e invariata.
- Commit piccoli, ognuno green.

## Fuori scope

- Viewport picking e generazione di codice (tranche 2 della voce Roadmap): richiede la sintesi di frame da facce senza mark e la decisione editoriale su cosa emettere (pose map letterali vs anchor generati). Il supporto della pose map in `:from` è il gancio predisposto.
- `:from`/`:mate` su attach SDF: solo errore esplicativo in questa tranche.
- Attach di gruppo (`attach [m1 m2 …]`) con `:from`: errore esplicativo (l'anchor mobile è ambiguo su un gruppo).
- Zucchero `assemble`: da valutare dopo uso reale del primitivo.
- Aggiornamento del manuale IT+EN (capitoli su attach/assemblaggio): brief editoriale separato dopo il merge, con edits simmetriche nelle due lingue.
