# Brief — `edit-bezier`: authoring interattivo di una curva di Bezier cubica

## Contesto

Primo esemplare concreto delle "sessioni di tracing" previste in `Roadmap.md` §2.2. Il bisogno è tracciare una Bezier cubica visualmente, senza calcolare a mano i punti di controllo — oggi l'unica via per ottenere i valori da passare a `bezier-to` / `bezier-as` è risolvere l'equazione della cubica. `edit-bezier` è il terzo modal evaluator e va costruito **sopra il layer `editor/modal_evaluator`** prodotto dal brief di estrazione (15.2.1), che è prerequisito di questo lavoro. Non clonare pilot.

API esistente rilevante (`Spec.md`, sezione Bezier): `(bezier-to [x y z] [c1x c1y c1z] [c2x c2y c2z])` è la cubica, con il punto di partenza P0 implicito = posa corrente della tartaruga. `edit-bezier` produce esattamente i tre vettori che questa firma già accetta: punto di arrivo, primo punto di controllo, secondo punto di controllo.

Frame di `bezier-to` — verificato nel sorgente: `bezier-to` interpreta `target` e i control point come **coordinate assolute di mondo**, non nel frame locale della tartaruga (`core.cljs`: `p3 (vec target)` usato tale e quale, `c1`/`c2` passati diretti a `cubic-bezier-point`, nessuna trasformazione di frame). Emettere in mondo darebbe round-trip ma produrrebbe una chiamata pose-dependent e numeri poco leggibili. Per ottenere sia la leggibilità (numeri piccoli, relativi all'heading, editabili a mano) sia una chiamata pose-independent, **`bezier-to` guadagna un flag opzionale `:local`** come ultimo argomento, additivo e senza toccare il comportamento di default (mondo). Vedi punto 7. `edit-bezier` lavora ed emette **sempre in locale** con `:local`, così che riaprire la chiamata prodotta sia un round-trip identità.

Riferimenti: `Roadmap.md` §2.2 (voce `edit-bezier`); `Architecture.md` §11.2.3–11.2.4 (ingresso in due fasi di pilot, geometria effimera in sessione); `Spec.md` sezione Bezier.

## Lavoro richiesto

1. Nuovo modal evaluator `edit-bezier` sopra `editor/modal_evaluator`. Keyboard-first, nativamente 3D (nessuna restrizione a un piano). Eredita dal layer condiviso stato, mutex, pannello, keyhandler, costruzione del replacement, commit con `cm/replace-range` + `run-definitions-fn`.

2. Forma d'uso `(bezier-to (edit-bezier))`. Il marker `(edit-bezier)` apre la sessione. P0 è la posa della tartaruga al call site, catturata durante l'eval con l'ingresso in due fasi `request!` / `enter!` (il valore restituito durante l'eval deve solo non rompere il flusso; la preview autorevole è quella della sessione, vedi punto 4). Alla prima apertura, in assenza di valori, inizializzare i tre punti mobili con una curva di default ragionevole (per esempio dolce lungo l'heading) così che ci sia qualcosa da spostare. P0 non è editabile e non viene mai scritto a sorgente: si ricalcola a ogni eval.

3. Geometria effimera nel viewport durante la sessione: i quattro punti (P0 fermo più i tre mobili), il poligono di controllo che li collega, e la curva di preview ricalcolata a ogni nudge. Verificare contro la convenzione di `bezier-to` l'associazione corretta delle maniglie ai segmenti del poligono di controllo (quale control point è tangente a P0 e quale al punto di arrivo). L'infrastruttura per mostrare geometria transitoria in sessione esiste già (turtle indicator e wireframe preview di pilot, preview di tweak): questo è additivo, è la parte di lavoro specifica di `edit-bezier`.

4. Keymap: Tab cicla fra i tre punti mobili; le frecce spostano il punto selezionato con step regolabile alla maniera di pilot; il terzo asse si raggiunge con la coppia di tasti / modificatore che pilot usa già per la profondità. Enter conferma, Esc annulla. La preview è guidata dalla sessione (ridisegno della geometria effimera a ogni keystroke), non da una rivalutazione dell'espressione utente.

5. Alla conferma, riscrittura testuale: il marker `(edit-bezier)` viene sostituito dai tre vettori letterali separati **più il flag `:local`**, così che `(bezier-to (edit-bezier))` diventi `(bezier-to [ex ey ez] [c1x c1y c1z] [c2x c2y c2z] :local)`. I tre vettori sono espressi nel frame locale `[right up heading]` della posa di P0. Nota l'unica differenza strutturale rispetto a tweak e pilot, che sostituiscono il proprio marker in place con una singola forma: `edit-bezier` lo sostituisce con più forme sibling. Resta una pura `cm/replace-range` su un range testuale, niente di concettualmente nuovo, ma da implementare con attenzione allo spacing. Il marker `(edit-bezier)` è annidato dentro `(bezier-to ...)`: il range da sostituire è la call interna, non l'esterna; la macro deve poter localizzare il range del marker interno (sulla falsariga di `pilot-request!`, che riceve la forma quotata).

6. Cancel ripristina il sorgente invariato.

7. Flag `:local` su `bezier-to` (prerequisito di geometria, additivo, default invariato). `bezier-to` accetta un flag opzionale `:local` come ultimo argomento: quando presente, `target` e i control point sono interpretati nel frame locale ortonormale `[right up heading]` della turtle (`right = heading × up`, cfr. `right-vector` in `core.cljs`), trasformati in mondo con `world = p0 + a·right + b·up + c·heading`. Senza il flag, comportamento attuale (mondo) invariato. Dettagli implementativi: (a) il parsing opzioni corrente usa `(apply hash-map (flatten options))`, che un `:local` **nudo** romperebbe (conteggio dispari) — filtrare `:local` da `options` *prima* dello `hash-map` settando un booleano, così il flag resta nudo e leggibile nel sorgente prodotto; (b) applicare la trasformazione in modo uniforme ai tre rami (cubica/quadratica/auto) per coerenza, anche se `edit-bezier` usa solo la cubica; (c) `bezier-to-anchor` delega a `bezier-to` passando una posizione di anchor in mondo (`core.cljs:845`) — `:local` non ha senso in quel percorso e va ignorato/sconsigliato lì; `edit-bezier` non lo tocca. Per `edit-bezier` l'inverso mondo→locale è la proiezione sugli assi (`a = (v−p0)·right`, ecc.), inverso esatto perché la base è ortonormale → round-trip identità per costruzione.

## Verifica

- `(bezier-to (edit-bezier))` apre il pannello in basso a sinistra; il turtle indicator è su P0; i tre punti mobili, il poligono di controllo e la curva si vedono nel viewport.
- Tab cicla i tre punti; le frecce muovono il punto selezionato con step regolabile; il terzo asse è raggiungibile da tastiera.
- La conferma produce una `(bezier-to [..] [..] [..])` valida; rieseguendo, la curva renderizzata coincide con quella vista in preview.
- Round-trip: riavvolgere i tre vettori prodotti in un nuovo `(edit-bezier)` riproduce posizioni identiche (stesso frame di `bezier-to`).
- Mutex: `edit-bezier` non si apre mentre tweak o pilot sono attivi; Cmd+Enter durante la sessione resta rifiutato.

## Fuori scope

- Drag dei punti di controllo col mouse e, più in generale, qualunque manipolazione diretta del viewport. Nota per il futuro: un layer di manipolazione viewport (maniglie effimere, picking, proiezione schermo-mondo / sul piano della vista, drag) gioverebbe anche a tweak e pilot ed è una fattorizzazione trasversale a sé, da innescare quando avrà i suoi casi concreti. Non costruirla qui, e non lasciare che l'idea gonfi lo scope.
- Inquadramento come "editor SVG" o restrizione a un piano: superato, la tastiera rende l'editing 3D nativo.
- `edit-bezier` dentro un loop o una funzione richiamata più volte nello stesso eval: limite "raggiunto una volta sola per eval" ereditato dal pattern, accettato per ora.
- L'estrazione di `modal_evaluator` (brief separato, prerequisito).

## Stima

A layer condiviso esistente, la logica specifica di `edit-bezier` è dell'ordine delle ~150 righe, più il rendering della geometria effimera (i quattro punti, il poligono di controllo, la curva live), che è la parte realmente nuova e la voce di costo dominante di questo brief.