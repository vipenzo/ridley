# Accertamento mesh-split fase 2 — albero, componenti connesse, generatori di candidati

Natura: **accertamento, nessuna modifica al codice di produzione.** Misure reali (REPL, ispezione binding, scratch poi rimossi), risposte annotate sotto ogni domanda. La falsificazione è un esito valido.

## Contesto

Il primo uso reale di `edit-mesh-split` (STL di un mount multiboard) ha prodotto due riscontri:

1. Anche su oggetti semplici, dopo pochi tagli sia `:behind` sia `:ahead` risultano concavi e richiedono ulteriore splitting: il modello lineare "a ghigliottina" (behind sempre definitivo) non basta per l'hardware reale con rami (ganci, denti, clip). L'ipotesi di lavoro è che il fenomeno abbia tre cause sovrapposte con tre rimedi diversi: (a) pezzi **multi-componente** (un taglio su una forma a U lascia due rebbi staccati in una sola mesh, irrimediabilmente "concava" finché non la si separa — ma la separazione è topologica, non serve alcun piano); (b) **tolleranza**: pezzi moralmente convessi con concavità da chamfer/fillet tassellati che leggono rossi con epsilon 0.01; (c) il residuo che richiede davvero **sessioni ad albero** (continuare a tagliare un pezzo rosso senza uscire e rientrare).
2. Trovare la posa giusta del taglio a mano è faticoso: servono generatori di candidati (discussione già maturata in chat: eventi del profilo di sezione A(t) in traslazione e A(θ) in rotazione, spigoli riflessi della V2).

Questo accertamento misura i prerequisiti di entrambi i fronti. I brief verranno dopo, con l'ordine deciso dagli esiti.

Fatti già acquisiti su cui non tornare: il ritorno annidato `{:behind :ahead}` è già una forma d'albero (scelto per questo); `split-parts` è già ricorsivo depth-first; l'albero testuale è già esprimibile come let a catena di più chiamate `mesh-split` sullo stesso path con marks-vector diversi; il re-entry esistente è l'albero in forma scomoda e resta il fallback.

---

## Sezione A — Albero e componenti connesse

### A1 — Il binding espone decompose()?

**Perché conta.** Manifold C++ ha `Decompose()` (split in componenti connesse, esatto). Se il binding WASM lo espone, il tool può accorgersi che un pezzo ha N componenti e offrire "separa componenti" come gesto a costo zero — niente piano, niente mark — e il DSL può esporre una `mesh-components`. L'ipotesi è che questo gesto dissolva una parte consistente dei "servono altri tagli" osservati sul mount.

**Cosa misurare.** Il pacchetto manifold-3d in uso espone `decompose` (nome e firma esatti dal `.d.ts` e da `Object.keys` a runtime)? Prova su una U tagliata alla base: l'`:ahead` a due rebbi restituisce due manifold valide? Costo su mesh reale. Esiste anche un reader economico del *numero* di componenti (per il badge live "questo pezzo ha N componenti") senza fare la decomposizione completa, o il conteggio richiede decompose?

**Esito.** Confermato, misurato (Manifold 3.3.2, REPL browser).

- **Il binding espone `decompose()`.** `manifold-encapsulated-types.d.ts:897` → `decompose(): Manifold[]`, "inverse operation of Compose()", puramente topologica (union-find sulle componenti connesse, **nessuna booleana**). Confermato a runtime: su un'istanza cubo `(fn? (.-decompose m))` → `true`.
- **Prova sulla U tagliata alla base.** U intera (box − slot centrale) = 1 componente. Il pezzo `:ahead` del taglio orizzontale sopra il ponte-base (i due rebbi) → `decompose` restituisce **2 manifold valide**, vol 3060 ciascuna, status `NoError`. Caso minimo di controllo (due scatole disgiunte concatenate): una singola mesh Ridley **manifold-valida** (24 tri, `:status :ok`, vol 2000) che è topologicamente 2 componenti → `decompose` → 2 cubi (vol 1000, NoError). Questo è esattamente il fenomeno "concavo per sempre": la mesh è valida ma il suo hull-ratio ≈ 0.5, e nessun piano la "raddrizza" — la separazione è topologica.
- **Costo.** Trascurabile: 0 ms (U, 24 tri) → 0.3 ms (mount-dense 4800 tri) → 0.5 ms (mesh densa 12800 tri). Cresce ~lineare in V. Un badge live "N componenti" è ricalcolabile ad ogni tick senza throttle.
- **Reader del solo numero.** NON esiste `numComponents()` (verificato: `(fn? (unchecked-get m "numComponents"))` → `false`). L'unico conteggio è `(.length (.decompose m))` — ma decompose è così economico che la distinzione non ha valore pratico. (`genus()` esiste, `d.ts:970`, ma è **per-componente**, "best to call Decompose() first" — non è un conteggio di componenti.)
- **Verdetto.** Gesto "separa componenti" a costo zero e una `mesh-components` DSL, entrambi realizzabili senza piano né mark. Ipotesi (a) confermata: dissolve i pezzi multi-componente che leggono rossi ma non richiedono alcun taglio.

### A2 — Risoluzione dei mark su chiamate multiple nello stesso scope

**Perché conta.** L'emissione ad albero prevista è un let a catena: `(mesh-split m cuts [:cut-1])`, poi `(mesh-split lato-a cuts [:cut-2])`, ecc., tutte sullo stesso path. Regge solo se le chiamate successive risolvono i mark alle stesse pose mondo — cioè se `mesh-split` non muove la turtle e la risoluzione del path è pura rispetto allo stato della sessione REPL. Dovrebbe essere vero per costruzione, ma va letto nel codice, non creduto.

**Cosa misurare.** Lettura del codice di `mesh-split` composita (la turtle resta intatta dopo la chiamata?) + script minimo: due chiamate consecutive sullo stesso path nello stesso scope, verificare che i piani risolti coincidano (stessi volumi delle metà su tagli identici).

**Esito.** Confermato per costruzione (lettura) + empiricamente.

- **Lettura del codice** (`implicit.cljs:844-893`, `implicit-mesh-split` + `guillotine-split`): la forma composita 3-arg fa `(turtle/resolve-marks @(turtle-ref) path)` — **dereferenzia** l'atom turtle ma non lo muta mai; `guillotine-split` lavora su dati puri (`mesh`, `mark-poses`), nessun `swap!`/`reset!` sulla turtle. La risoluzione delle pose-mondo dipende **solo** da `(stato-turtle, path)`, indipendente dall'argomento `mesh`.
- **Prova** (DSL via `repl/evaluate`): due `(mesh-split m p [:c1])` consecutivi nello stesso scope → `:behind` con `:vertices` e `:faces` **value-equal** (`=` → `true`), idem `:ahead`; volume behind identico (20700). Turtle finale a `[0 0 0]`, **invariata** dopo le due chiamate.
- **Trappola da conoscere.** `(= mesh1 mesh2)` sull'intera map dà `false` — ma per via dei `::raw-arrays` (typed array, confronto per identità), NON per differenza geometrica. Confrontare `:vertices`/`:faces` (o volume), mai la map intera.
- **Verdetto.** Il let-a-catena di più `mesh-split` sullo stesso path regge. Invariante esatto: *risoluzione pura + turtle read-only*; vale finché non ci sono comandi turtle **fra** le chiamate — e nel let-chain emesso non ce ne sono (tutte le chiamate vedono la stessa `entry-pose`). Nota di simmetria col resto del sistema: la risoluzione parte dalla posa **corrente** della turtle, non dall'identità interna del path (`implicit.cljs:864-865`), quindi anche l'albero eredita la stessa posa d'ingresso per tutti i rami.

### A3 — Costo del refactor sessione: da stack a albero

**Perché conta.** Lo stato attuale della sessione è lineare (stack di tagli accettati + remaining corrente). L'albero richiede: più pezzi "aperti" contemporaneamente, un pezzo corrente selezionabile (click col mouse ora che gli handle esistono, o tasto che cicla i rossi), undo che resta cronologico sui gesti (l'ordine dei gesti, non la struttura dell'albero), emissione a let a catena con nomi coerenti. Serve una stima onesta della superficie di refactor, misurata sul codice reale della sessione, per dimensionare il brief.

**Cosa misurare.** Ispezione dello stato di sessione attuale: quali strutture assumono la linearità? L'emissione attuale (delta canonico dal mark precedente) come si generalizza quando il "precedente" è la posa d'ingresso di un altro ramo? Riportare i punti che cambiano, senza implementare.

**Esito.** Superficie delimitata: ~7 funzioni, tutte nel modello-di-stato / emissione / re-entry. Il layer dei gesti è invariato. Lettura di `edit_mesh_split.cljs`.

Assunzioni di linearità nello stato attuale (`:40-56`, `request!:776-795`):

1. **`:remaining` = UN SOLO pezzo corrente.** È l'assunzione centrale. L'albero richiede una collezione di pezzi aperti + un selettore "pezzo corrente" (click col mouse — gli handle esistono già; o un tasto che cicla i rossi). *Cambiamento centrale.*
2. **`:accepted` = un vettore lineare che fa TRE lavori insieme** (coincidono solo in una linea): (a) stack di undo (`peek`/`pop` in `undo! :455-470`), (b) sorgente d'emissione (`build-emitted-code :401-419` mappa su una catena piatta), (c) derivazione di `:remaining` (`peek :remaining`). L'albero **sdoppia** queste due preoccupazioni che oggi confonde: la STRUTTURA ad albero dei pezzi vs il LOG cronologico dei gesti per l'undo.
3. **`accept-cut! :352-371`**: `(assoc :remaining current-ahead)` — avanza SEMPRE alla metà ahead (behind definitivo, ahead nuovo unico remaining: la ghigliottina). In albero, su un taglio attivo apre DUE nodi (behind e ahead); il selettore decide quale continuare. (Se ahead è già convesso → foglia — logica `ahead-convex?` già presente; se un pezzo è multi-componente → `decompose`, A1.)
4. **`build-emitted-code`**: da singola catena annidata `(mesh-split src path marks-vec)` con delta `partition 2 1 poses` (delta dal predecessore **lineare**) → a **let-a-catena di più `mesh-split`** (il target dichiarato dall'accertamento; A2 conferma che regge), uno per ramo, con delta-pose calcolati dalla **posa d'ingresso del ramo**. Qui si risponde alla domanda: il "precedente" diventa la posa d'ingresso del ramo, non il mark cronologicamente precedente.
5. **`composite->accepted :706-716`**: oggi scende SOLO in `:ahead` (catena). L'albero richiede di camminare **entrambi** i rami — nota che `manifold/split-parts (core.cljs:644)` GIÀ lo fa (DFS behind-before-ahead), quindi la primitiva di flatten generalizza gratis; è `composite->accepted` a dover essere generalizzato.
6. **Re-entry / macro** (`macros.cljs:2114`): il macro passa **UN** `composite-value`. Un albero emesso come let-a-catena di più `mesh-split` NON è un solo composite → **fork di design reale** per il brief: (a) il macro passa più composite, oppure (b) re-entry ri-deriva dai valori già valutati. Da decidere nel brief, non ora.
7. **`undo! :455-470`**: oggi cronologico perché lo stack È la cronologia (in linea). In albero l'undo resta sui **gesti** (log separato, punto 2), disaccoppiato dalla struttura dei pezzi.

**Invariati** (nessun tocco): layer dei gesti (turtle ops `:257-338`, gizmo `:819-827`, keyboard `:534-605`), primitive di rendering (quad/cono/ghost `:98-236`). È un refactor di **modello-di-stato + emissione + re-entry**, non del layer gesti. Funzioni toccate: session-shape, `accept-cut!`, `undo!`, `build-emitted-code`, `composite->accepted`, `render!`, `request!`/`enter!` — delimitato.

### A4 — Hull-ratio dei pezzi reali: il rosso è troppo severo?

**Perché conta.** Se i pezzi che l'utente considererebbe finiti (concavità solo da chamfer/fillet tassellati) hanno hull-ratio tipo 0.985–0.995, con epsilon 0.01 leggono rossi e spingono a tagli inutili. Il rimedio sarebbe uno stato intermedio "quasi verde" o un epsilon diverso — ma serve la distribuzione reale, non una congettura.

**Cosa misurare.** Sul mount multiboard usato da Vincenzo (o STL equivalente): decomporlo manualmente in pezzi "moralmente finiti" e misurare l'hull-ratio di ciascuno. Riportare la distribuzione e dove cadrebbe una soglia sensata. Include il caso limite: ratio di un pezzo con un solo piccolo fillet vs uno genuinamente concavo.

**Esito.** **Ipotesi in gran parte FALSIFICATA.** Distribuzione hull-ratio misurata (Manifold reale, hull-ratio = vol(mesh)/vol(hull)):

| pezzo | hull-ratio | verdetto (soglia 0.99) |
|---|---|---|
| box liscio | 1.0 | verde |
| box con TUTTI gli spigoli esterni raccordati (fillet) | **0.9993** | verde |
| box smussato/beveled (∩ sfera) | 1.0 | verde |
| tacca su cubo 40mm, prof 0.5 / 1.0 / 1.6 / 2 / 4 / 8 mm | 0.996 / 0.993 / 0.988 / 0.985 / 0.97 / 0.94 | verde fino a ~1.3mm |
| piastra + gancio-stub | **0.55** | rosso profondo |
| box + boss tondo | **0.78** | rosso |

- **I fillet/chamfer ESTERNI restano CONVESSI → verdi** (0.9993–1.0). Un convesso liscio tassellato ha i vertici sul proprio hull per costruzione (già noto: sfera 0.99999999 a 8×6). Quindi la causa ipotizzata — "concavità da chamfer/fillet tassellati che leggono rossi" — **non regge per le feature esterne**.
- **I rossi osservabili vengono da concavità VERE**, non da raccordi: sporgenze (gancio, boss → 0.55/0.78) e incassi più profondi di ~1% del bbox (tacca > ~1.3mm su 40mm). La soglia 0.99 significa letteralmente "concavità che toglie ~1% del volume del bbox".
- **Il rimedio proposto (stato "quasi-verde" / epsilon diverso) NON salva i casi osservati.** Alzare epsilon a 0.05 non tocca un 0.55 o un 0.78; reagisce solo la banda stretta 0.985–0.995 (tacche 1–1.6mm), ed è arguibilmente giusto che resti rossa — per una decomposizione convessa, piastra+gancio VA tagliata. hull-ratio confonde "ha una feature sporgente/incassata" con "serve un piano": nessun epsilon lo risolve.
- **Vero rimedio suggerito dai dati**: non un epsilon, ma un criterio diverso di "finito" — es. "convesso **oppure** poche componenti convesse" (`decompose` + `convex?` per-componente, A1), o separare "convesso" da "stampabile/accettabile".
- **Caveat.** Pezzi sintetici, non il mount reale di Vincenzo (STL non in repo; caricabile solo via `import-stl` col file server desktop, non disponibile in questo REPL browser). Il **meccanismo** (convesso→verde; sporgenze/incassi→rosso indipendentemente da epsilon) è STL-indipendente; la distribuzione esatta sul mount va rimisurata quando l'STL è disponibile, ma la conclusione qualitativa (epsilon non è la leva giusta) è robusta.

---

## Sezione B — Generatori di candidati

### B1 — slice-mesh: l'area della sezione è leggibile?

**Perché conta.** Il segnale percettivo per i candidati è l'area della sezione A (gradini = facce a filo, minimi = colli), sia lungo la traslazione A(t) sia lungo la rotazione A(θ). `slice-mesh` produce contorni 2D: l'area è direttamente calcolabile dai contorni restituiti (shoelace sui poligoni, con i buchi col segno giusto) o serve un reader nuovo?

**Cosa misurare.** Formato esatto del ritorno di `slice-mesh` (poligoni? con winding coerente per i buchi?). Prova: area della sezione di un cilindro cavo a metà altezza = corona circolare attesa.

**Esito.** Leggibile direttamente, nessun reader nuovo.

- **Formato di `slice-at-plane`** (`core.cljs:939`): vettore di `{:type :shape :points <outer CCW> :holes [<CW> …] :preserve-position? true}` — winding **già classificato** per area con segno (outer area>0, holes area<0), e ogni hole già assegnato al proprio outer per point-in-poly.
- **Area** = shoelace(outer) − Σ shoelace(holes), direttamente dai contorni. Prova: cilindro cavo (R20/r10) sezionato a metà altezza → 1 shape, 1 hole, area **940.96** vs π(400−100)=942.48 ideale, ratio 0.998 (il deficit 0.16% è esattamente il 64-gono inscritto vs cerchio). Buchi con segno giusto: confermato.
- **Path più economico** (usato in B2): la `CrossSection` nativa espone `.area()` (`d.ts:357`) e `Manifold.slice(height)` (`d.ts:859`), che saltano del tutto lo shoelace ClojureScript — vedi B2.

### B2 — Costo del profilo di sezione

**Perché conta.** Il profilo traslazionale A(t) dipende solo dall'heading (si calcola una volta per direzione, si riusa per tutta la scorsa); quello rotazionale A(θ) dipende da posizione e asse insieme (cache a granularità più fine). Decidono se il profilo si calcola in background per la striscia nel pannello o solo on-demand.

**Cosa misurare.** Tempo per M campioni (M ≈ 50–100) di slice + area su mesh reale (~10k tri), traslazionale e rotazionale. Con questo: budget sostenibile per l'uso live (ricalcolo del profilo rotazionale a ogni spostamento della turtle è affordabile o va throttlato/on-demand?).

**Esito.** Misurato su mesh densa 12800 tri, 100 campioni per profilo.

- **Naive** (`slice-at-plane` per campione, che ri-converte mesh→manifold ogni volta): **~9 ms/campione** (traslazionale e rotazionale). 100 campioni ≈ 900 ms → **troppo** per live per-tick.
- **La conversione mesh→manifold È tutto il costo**: 8.4 ms misurati isolati. Lo slice+area vero è quasi gratis.
- **Amortizzato** (converti UNA volta, tieni vivo l'oggetto Manifold, poi `.slice(z).area()` nativo):
  - **Traslazionale**: **0.039 ms/campione** (230× più veloce). Profilo 100-campioni ≈ 3.9 ms + 8.4 ms conversione ≈ **12 ms totali** → ricalcolabile **live liberamente**.
  - **Rotazionale** (`.rotate` + `.slice(0).area()`, la mesh va ri-trasformata per ogni angolo): **0.61 ms/campione**, 100-campioni ≈ **61 ms** → **on-demand o throttlato**, non ogni tick.
- **Verdetto** (conferma l'ipotesi): A(t) dipende solo dall'heading → converti una volta all'ingresso (o ad ogni accept) e il profilo vive live; A(θ) dipende da posizione+asse → più caro (~15×), cache a grana fine, ricalcolo on-demand/throttlato a fine drag. La chiave architetturale: **mantenere vivo l'oggetto Manifold del pezzo corrente** invece di ri-convertire per campione.

### B3 — Densità dei candidati-vertice

**Perché conta.** I candidati traslazionali esatti sono le proiezioni dei vertici sull'heading (O(V), gratis); quelli rotazionali gli angoli di attraversamento dei vertici nel pencil (pure O(V)). Ma su mesh tassellate V è grande: dopo dedup in tolleranza, quanti candidati distinti restano? Il ciclo "salta al prossimo evento" è gestibile o serve un ranking per salienza (ampiezza del salto di A, profondità della valle)?

**Cosa misurare.** Sul mount e su un paio di STL reali: numero di candidati traslazionali distinti dopo dedup (tolleranza da scegliere e riportare) lungo 2–3 heading tipici; idem rotazionali su un asse tipico. Riportare i numeri grezzi e un giudizio sulla necessità di ranking.

**Esito.** Candidati distinti dopo dedup greedy in tolleranza (proiezioni vertici su heading; angoli di attraversamento nel pencil attorno a +Y):

| mesh | V | trans Z (0.5mm) | trans X (0.5) | trans diag (0.5 / 0.1) | rot Y (2° / 0.5°) |
|---|---|---|---|---|---|
| mount-clean | 152 | **5** | 29 | 53 / 71 | 50 / 66 |
| mount-dense 4× | 2402 | 15 | 99 | 132 / 382 | 112 / 298 |
| sfera+cubo | 6402 | 99 | 99 | 109 / 403 | 168 / 568 |

- **Lungo un ASSE PRINCIPALE di hardware squadrato**: una **manciata** (5) → il ciclo "salta al prossimo evento" è perfetto, **nessun ranking**.
- **Off-axis o su feature CURVE**: centinaia (100–570) → il ciclo grezzo è ingestibile, **serve ranking per salienza**.
- **Il segnale di ranking è già in mano**: `|ΔA|` del profilo (B1/B2) fa doppio servizio — la stragrande maggioranza dei candidati-vertice è micro-incremento su superficie curva, pochi sono feature vere (gradini/valli di A). Quindi il generatore non dovrebbe restituire i candidati-vertice grezzi ma **le pose ordinate per |ΔA|** (o profondità di valle), collassando i 300–500 curvi ai pochi salienti.
- La tolleranza dimezza circa i conteggi curvi (0.5 vs 0.1 mm). **Verdetto**: dedup basta per gli assi; ranking-per-|ΔA| necessario altrove — ed è parte del generatore, non un extra.

### B4 — Spigoli riflessi (candidati V2)

**Perché conta.** La V2 propone pose complete (orientamento+posizione) dai luoghi dove la concavità vive: spigoli con angolo diedro > 180°. Ogni spigolo riflesso offre due candidati (i piani delle due facce adiacenti estesi). Su mesh tassellate gli spigoli riflessi possono essere migliaia (un fillet concavo ne è pieno): servono numeri reali per capire se il ciclo è gestibile o se serve clustering/ranking.

**Cosa misurare.** Conteggio degli spigoli riflessi sul mount e su 1–2 STL reali, prima e dopo un clustering elementare (per piano candidato in tolleranza, riusando la logica di dedup normale+offset). Riportare se i candidati clusterizzati scendono a una manciata utilizzabile.

**Esito.** Clustering efficace e **refinement-invariante**; scende a una manciata per le concavità *poliedriche*, i cilindri tassellati lo gonfiano.

- **Conteggio spigoli riflessi** (diedro interno >180°, test winding-independent con la normale uscente della faccia adiacente): mount-clean 104, mount-dense-4× 448, sfera+cubo **0** (correttamente — il cubo è interamente dentro la sfera → convesso puro; detector validato). Range angolo-base fino a 90° (rim del foro e angoli gancio-piastra = diedro interno 270°).
- **Clustering per piano candidato** (normale+offset canonicalizzati come piano indiretto, tol 0.05 / 0.5 mm): **refinement-INVARIANTE** — mount-clean e mount-dense-4× collassano ENTRAMBI a **72** piani (da 104 e 448 spigoli). Il clustering vede i piani-faccia sottostanti, non gli spigoli tassellati.
- **Ma 72 è ancora tanto**: dominato dai due cilindri 32-seg (foro + boss), ~1 piano per faccetta. Prova di controllo: mount **solo squadrato** (piastra + 2 ganci + tasca rettangolare, niente cilindri) → 14 spigoli riflessi → **10 piani clusterizzati** → direttamente ciclabili.
- **Verdetto**: il clustering riflessi è efficace e refinement-invariante; per feature poliedriche dà una manciata usabile (10); cilindri/fillet tassellati lo gonfiano (1 piano/faccetta, es. foro 32-gono → ~32 piani) → serve un grouping a livello di **feature** (riconoscere l'asse del cilindro e proporlo come una candidata sola) oppure un ranking per portarli a pochi. Non "sempre una manciata", ma "una manciata per le concavità poliedriche".

### B5 — Il generatore come funzione DSL pura

**Perché conta.** "Ruota su Z finché la derivata cambia segno" è un programma: se il generatore vive nel DSL come funzione pura (es. `(cut-candidates mesh {:mode :translation})` → pose ordinate), il tool interattivo ne è un consumatore sottile, testabile senza UI, e il gesto esiste sia come tasto sia come riga di codice. Va verificato che non ci siano ostacoli di architettura (il generatore ha bisogno di stato di sessione, o basta mesh + posa?).

**Cosa misurare.** Nessuna implementazione: analisi degli input necessari a ciascun generatore (traslazionale: mesh + heading; rotazionale: mesh + posizione + asse; riflessi: solo mesh) e conferma che sono tutti esprimibili come funzione pura di argomenti DSL esistenti. Segnalare eventuali dipendenze scomode.

**Esito.** Tutti puri, nessun ostacolo architetturale.

- **Input per generatore**: traslazionale = `mesh + heading`; rotazionale = `mesh + posizione + asse`; riflessi (V2) = solo `mesh`; simmetria (B7) = solo `mesh`; mirror-detect (B6) = `mesh + piano` (o le due metà). **Nessuno richiede stato di sessione.**
- In uso interattivo, heading/posizione/asse si **leggono** dalla turtle solo per riempire gli argomenti; il generatore prende argomenti espliciti → resta puro e testabile senza UI.
- **Nessuna dipendenza scomoda esposta.** L'unica accortezza è interna: l'amortizzazione di B2 (converti mesh→manifold una volta) vive DENTRO la chiamata — il generatore calcola l'**intero** profilo/insieme di candidati in una passata, quindi converte una volta sola per chiamata; la vita degli oggetti Manifold (`.delete`) è un dettaglio implementativo, non una dipendenza. Il path più economico usa `.slice().area()` nativo → il generatore vive in `ridley.manifold` (o ns nuovo) con accesso manifold-level, esposto come binding DSL; non deve passare da `slice-at-plane`.
- **Verdetto**: `(cut-candidates mesh {:mode :translation :heading […]})` → pose ordinate (già rankate, vedi B3) è realizzabile come funzione pura. Il tool interattivo ne è un consumatore sottile; il gesto esiste sia come tasto sia come riga di codice.

### B6 — Rilevazione mirror tra le metà

**Perché conta.** Su oggetti reali è naturale identificare volumi specchiati: il tool può segnalare "behind e ahead sono specchio uno dell'altro", e la simmetria dimezza tutto ciò che segue (si tiene metà, si rimonta con mirror; per converter e mesh-board è la compressione strutturale più forte estraibile da un STL). Cascata prevista: gate gratis sull'uguaglianza dei volumi (già calcolati dal semaforo), conferma volumetrica — riflettere `:behind` attraverso il piano e misurare la differenza simmetrica con `:ahead` (vol(union) − vol(intersection), rapportata al volume; ≈ 0 = specchio). Il confronto è volumetrico apposta: le tessellazioni delle due metà possono legittimamente differire anche su oggetti perfettamente simmetrici.

**Cosa misurare.** Il binding espone un mirror/riflessione nativo, o si compone (scale −1 nel frame del piano)? In entrambi i casi: il winding dei triangoli va invertito dopo la riflessione — verificare che la mesh riflessa sia una manifold valida a facce orientate correttamente, non rovesciata. Costo della conferma completa (riflessione + due booleane + volumi) su mesh reale: compatibile con un check on-accept o on-demand? Prova positiva su un oggetto simmetrico tagliato sul piano di simmetria e prova negativa su un taglio fuori simmetria.

**Esito.** `.mirror()` nativo e winding-corretto; la cascata funziona.

- **Binding**: `.mirror(normal)` NATIVO (`d.ts:636`). **Winding-corretto**: la metà riflessa ha volume **positivo** (20160, = originale) e status `NoError` → Manifold inverte il winding internamente, **nessun flip manuale necessario**.
- **Cascata**: gate volumetrico gratis (già dal semaforo) → conferma per differenza simmetrica (riflette `:behind` attraverso il piano, poi `vol(union) − vol(intersect)` rapportato al volume).
  - **Prova positiva** (taglio sul piano di simmetria X=0 e Y=0 del mount squadrato): gate 0, symdiff-ratio **0**.
  - **Prova negativa** (X=15, fuori simmetria): il gate fallisce subito (26280 vs 14040), symdiff-ratio 0.87.
- **Piano non passante per l'origine**: la riflessione si compone come `translate(−p) ∘ mirror(n) ∘ translate(+p)` (usata e validata in B7).
- **Costo** cascata completa (split + mirror + 2 booleane + conversioni): 77 ms (4800 tri) → 148 ms (12800 tri) → OK per check **on-accept / on-demand**, NON per live continuo. Il gate volumetrico (≈gratis) scarta la stragrande maggioranza dei non-specchi prima di pagare la conferma.

### B7 — Piani di simmetria come candidati proposti

**Perché conta.** Meglio che rilevare la simmetria dopo che l'utente l'ha trovata a mano: proporla. I piani di simmetria speculare passano per il baricentro e sono tipicamente ortogonali agli assi principali d'inerzia → PCA sui vertici (O(V), ClojureScript puro) dà tre ipotesi, verificabili con la conferma di B6. "Snap al piano di simmetria" diventerebbe un gesto solo.

**Cosa misurare.** Sul mount multiboard e su 1–2 STL reali simmetrici: la PCA sui vertici (eventualmente pesata per area) produce assi abbastanza stabili da centrare il piano di simmetria vero? Quanto è sensibile alla tessellazione asimmetrica? Costo del ciclo completo (PCA + 3 verifiche B6). Riportare falsi positivi/negativi osservati.

**Esito.** PCA propone, B6 dispone. **La PCA sui vertici nudi è sconfitta da tessellazione asimmetrica; la PCA pesata per area la salva.**

- **PCA** (Jacobi 3×3 su covarianza vertici, ClojureScript puro) sul mount squadrato → centroide esatto, autovalori [825, 242, 204], assi = **esattamente** gli assi coordinati. Verifica B6 su ciascun asse: X → symdiff **0** (simmetrico ✓), Y → **0** (✓), Z → 0.986 (correttamente **rifiutato**). Trova **entrambi** i piani di simmetria veri, **zero falsi positivi/negativi**. Mount asimmetrico (con lump): assi si inclinano, tutti e tre rifiutati (0.75–0.98).
- **Sensibilità alla tessellazione** (la domanda chiave): **stessa forma**, metà +X tassellata 6× più fitta → PCA-vertici NON pesata: centroide.x = **17.69** (dovrebbe essere 0!), assi = spazzatura inclinata. Il piano X=0 vero È ancora simmetrico (symdiff 0), ma la PCA lo **mancherebbe**. → PCA sui vertici nudi **inaffidabile su STL con tessellazione disomogenea** (rischio reale su scansioni/export).
- **Fix confermato**: PCA **pesata per area** (centroide e covarianza sui centroidi-faccia, peso = area triangolo) → centroide.x torna a **0**, assi riallineati agli assi coordinati. Tessellation-invariante.
- **Costo** ciclo: PCA O(V) puro (trascurabile) + 3× B6 (~77–148 ms) ≈ **250–450 ms on-demand**.
- **Verdetto**: proporre 3 piani via PCA **pesata per area** (non vertici nudi) + verificare con B6 (che è il gate: un candidato falso costa una booleana). "Snap al piano di simmetria" fattibile come gesto singolo. Caveat: mount sintetico; sui simmetrici veri la PCA-area centra il piano, ma la verifica B6 resta la rete di sicurezza a prescindere.

---

## Sintesi e implicazioni per l'ordine dei brief

Tutti i prerequisiti misurati sono **positivi**; una sola ipotesi è caduta (A4). Sintesi per fronte:

**Fronte A (albero + componenti) — prerequisiti solidi, ROI alto, causa vera del "rosso perenne".**
- `decompose` esiste, è gratis (<1ms), e restituisce componenti valide (A1). Il let-a-catena regge per costruzione ed empiricamente (A2). Il refactor stack→albero è delimitato a ~7 funzioni, layer gesti intatto (A3).
- A4 riorienta la diagnosi: il "rosso perenne" osservato sul mount **non** è un problema di epsilon/fillet (falsificato) — è (a) pezzi multi-componente (→ `decompose`, gratis) e (c) concavità vere che richiedono sessioni ad albero. L'epsilon non è la leva.
- ⇒ **Primo brief: `mesh-components` (gesto "separa componenti") + albero di sessione.** Dissolve direttamente le cause (a) e (c) viste sul mount; il gesto componenti è a costo quasi nullo e indipendente dall'albero, quindi consegnabile anche per primo/da solo.

**Fronte B (generatori) — pronto ma con ranking come parte integrante.**
- Il generatore **traslazionale** è quasi gratis (funzione pura, ~12 ms/profilo amortizzato) e i candidati lungo gli assi sono una manciata (B1, B2, B3, B5) → **primo generatore da esporre**, ma deve restituire pose **rankate per |ΔA|** (non i vertici grezzi: off-axis sono centinaia — B3) e, per i riflessi V2, **clusterizzate per feature** (i cilindri gonfiano il conteggio — B4).
- **Simmetria/mirror** (B6, B7) ha ROI altissimo per converter/mesh-board (dimezza la struttura) e costo on-demand accettabile; richiede PCA **pesata per area** + verifica B6 come gate. → **secondo**, dopo il traslazionale.
- Il **rotazionale** A(θ) è ~15× più caro del traslazionale → on-demand/throttlato, non ogni tick.

**Dipendenza tecnica trasversale** (vale per B2/B5 e per il live di edit-mesh-split): tenere **vivo l'oggetto Manifold** del pezzo corrente invece di ri-convertire mesh→manifold per campione — è l'unico costo reale (8 ms) e amortizzarlo abilita profili live a ~0.04 ms/campione.

## Fuori scope

- Qualsiasi implementazione: albero di sessione, `mesh-components`, generatori, striscia di profilo nel pannello — tutto materia dei brief successivi, il cui ordine si decide sugli esiti.
- La decomposizione convessa automatica (VHACD) resta esclusa by design: i generatori propongono, l'umano dispone.
- Rilevazione di congruenza tra pezzi (copie traslate/ruotate di una stessa feature): stessa meccanica di B6 con trasformazione rigida al posto della riflessione — orizzonte dichiarato, non oggetto di questo accertamento.
- Il converter `stl->convex-sdf` e il mesh-board: invariati come orizzonti della famiglia.
