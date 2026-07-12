# Brief: simmetria — mesh-mirror, mirror?, symmetry-planes e integrazione in edit-mesh-split

## Contesto

Quarto brief della famiglia "acquisizione STL" (l'ordine è stato invertito con accordo esplicito: simmetria prima del generatore traslazionale, perché sblocca di più per il flusso reale di lavoro). Fondamenta misurate nell'accertamento fase 2:

- **B6**: il binding espone `.mirror(normal)` nativo e **winding-corretto** (volume positivo, `NoError`, nessun flip manuale). La cascata di rilevazione funziona: gate gratis sull'uguaglianza dei volumi (già calcolati dal semaforo) → conferma per differenza simmetrica (riflettere `:behind` attraverso il piano, `vol(union) − vol(intersect)` rapportato al volume; ≈ 0 = specchio). Prova positiva ratio 0, negativa 0.87. Piano non passante per l'origine: `translate(−p) ∘ mirror(n) ∘ translate(+p)`, validato. Costo conferma completa 77–148 ms → on-demand/debounced, mai per-keystroke.
- **B7**: i piani di simmetria si *propongono* con PCA **pesata per area** (centroidi-faccia pesati per area del triangolo; Jacobi 3×3, ClojureScript puro) — la PCA sui vertici nudi è sconfitta dalla tessellazione disomogenea (centroide.x 17.69 invece di 0, misurato) ed è **vietata**. Verifica di ogni candidato con la cascata B6. Ciclo completo ~250–450 ms on-demand. Sul mount squadrato: entrambi i piani veri trovati, il falso rifiutato, zero errori.
- **B5**: i generatori sono funzioni pure di `(mesh, parametri)`, nessuno stato di sessione; l'amortizzazione Manifold vive dentro la chiamata. Il keep-alive per pezzo aperto (brief albero) è già disponibile.

Convenzione dei piani, invariata da tutta la famiglia: piano = posa della turtle (punto = position, normale = heading).

## Lavoro richiesto

### Parte 1 — mesh-mirror (wrapper + DSL)

`(mesh-mirror mesh)`: riflette la mesh attraverso il piano della posa corrente della turtle. Wrapper di `.mirror` col marshalling esistente, composizione translate∘mirror∘translate per piani fuori origine (come validato in B7), disciplina `.delete`. Metadati: policy carry-meta esistente.

Caso d'uso concreto già sul tavolo (per cui la funzione entra ora e non dopo): tenere metà di un oggetto simmetrico e ricostruire l'intero con `(mesh-union metà (turtle … (mesh-mirror metà)))`.

### Parte 2 — mirror? (predicato)

`(mirror? mesh)`: la mesh è simmetrica rispetto al piano della posa corrente? Implementa la cascata B6: gate volumetrico (le due metà dello split al piano hanno volumi uguali entro tolleranza), poi conferma per differenza simmetrica. Secondo argomento posizionale opzionale per l'epsilon sul ratio, coerente con `convex?`.

**Epsilon di default calibrato con misure, non a tavolino**: B6 dà 0 (vero) e 0.87 (falso) — margine enorme — ma il caso che decide la soglia è la *quasi*-simmetria: un oggetto simmetrico con una piccola feature asimmetrica (es. un foro fuori asse). Misurare il ratio su 2–3 forme così e motivare la soglia nel report.

Mesh vuota: `true` (coerente con `convex?`), documentato.

### Parte 3 — symmetry-planes (generatore)

`(symmetry-planes mesh)` → vettore di pose (piani di simmetria **verificati**), ordinate per qualità (ratio di differenza simmetrica crescente). Funzione pura di sola mesh. Pipeline: PCA pesata per area → fino a 3 candidati (piani per il baricentro, ortogonali agli assi principali) → verifica B6 su ciascuno → si restituiscono solo i promossi. Il formato delle pose restituite deve essere direttamente consumabile per piazzare la turtle (allinearsi alla rappresentazione di posa esistente del sistema — quella di mark/goto — e documentare come si usa).

**Punto d'attenzione non coperto da B7, da misurare in corso d'opera: gli autovalori degeneri.** Un oggetto a simmetria quadrata (piastra quadrata, N-fold) ha due momenti principali uguali → gli assi PCA nel sottospazio degenere sono arbitrari, e i candidati possono essere ruotati a caso e bocciare la verifica *anche se i piani di simmetria esistono*. Rilevare la quasi-degenerazione (rapporto tra autovalori entro tolleranza) e in quel caso aggiungere candidati allineati agli assi del bounding box. Testare esplicitamente su una piastra quadrata e riportare il comportamento.

Cache: il generatore è puro, ma nella sessione il risultato per pezzo aperto si calcola una volta e si riusa (i pezzi sono immutabili).

### Parte 4 — Integrazione in edit-mesh-split

- **Gesto "proponi piano di simmetria"** (tasto in keymap di famiglia): calcola (o legge dalla cache) `symmetry-planes` del pezzo corrente e teletrasporta il piano sul primo candidato; pressioni successive ciclano i candidati verificati. Durante il calcolo (~250–450 ms) lo stato è visivamente pending — i colori non mentono mai. Se non ci sono piani verificati: feedback esplicito nel pannello, nessun movimento.
- **Badge "specchio"**: quando il gate volumetrico passa (gratis, già per-keystroke) e il piano è fermo (debounce, es. ~300 ms), parte la conferma B6 in background; al successo il pannello mostra l'indicazione che behind e ahead sono specchio. Mai bloccare l'interazione; stato pending durante il calcolo; il badge decade appena il piano si muove.
- **Annotazione in emissione**: un taglio accettato con specchio confermato emette un commento sulla riga del suo mark (es. `;; :cut-2: piano di simmetria`) — la conoscenza resta nel sorgente per il converter e il mesh-board futuri. Nessun altro cambiamento al formato emesso.

### Parte 5 — Gesto "decomponi a specchio" (replay dei tagli riflessi)

Disponibile su un pezzo aperto il cui **gemello** — l'altra metà di un taglio con specchio confermato — è già stato decomposto (in tutto o in parte). Il gesto ri-esegue sul pezzo corrente i tagli del sottoalbero del gemello con le **pose riflesse** attraverso il piano del taglio-specchio, incluse le separazioni-componenti. I pezzi risultanti sono pezzi veri dell'originale: nessuna sostituzione con copie specchiate (quella è la simmetrizzazione, mestiere del converter, fuori scope).

Vincoli geometrici da implementare esattamente così:

- **Non riflettere il frame intero.** Il frame riflesso è left-handed e la turtle non può adottarlo per rotazioni. Si riflettono solo position e heading (tutto ciò che `mesh-split` legge); l'up lo sceglie libero la sintesi del delta.
- **Le etichette si conservano da sole.** La riflessione è un'isometria (preserva i prodotti scalari): il riflesso del `:behind` del gemello è esattamente il `:behind` del taglio riflesso. Nessuna rimappatura behind/ahead: la corrispondenza pezzo-a-pezzo tra i sottoalberi è automatica e va verificata, non gestita.

Semantica di sessione, decisioni esplicite:

- **Replay one-shot, nessun linking vivo**: gesti successivi sul gemello non si propagano; se serve, si ri-esegue il gesto. 
- **Un solo gesto strutturale nel log**: un undo rimuove l'intero replay.
- Costo N×~10 ms: istantaneo in pratica; stato pending se N è grande, i colori non mentono mai.

Emissione: nessun formato nuovo — il sottoalbero replicato emette normali chiamate `mesh-split` auto-contenute con path sintetizzati verso le pose riflesse, più un commento che ne nota la derivazione (es. `;; decomposizione a specchio di piece-2`). Codice ordinario, editabile, round-trip invariato.

## Verifica

- `mesh-mirror`: volume positivo e uguale all'originale, status ok; doppio mirror = identità (volumi/bbox entro epsilon; confronti su `:vertices`/volumi, mai `=` sulla map — trappola A2); piano fuori origine corretto.
- `mirror?`: repliche permanenti delle prove B6 (positiva sul piano di simmetria, negativa fuori); calibrazione dell'epsilon su forme quasi-simmetriche con ratio misurati nel report.
- `symmetry-planes`: mount squadrato sintetico → i due piani veri promossi, il falso bocciato (replica B7); forma con tessellazione deliberatamente disomogenea → il piano vero viene trovato (la scoperta B7 come test permanente della PCA pesata); oggetto asimmetrico → vettore vuoto; piastra quadrata → comportamento degenere misurato, fallback bbox verificato, esito documentato.
- Sessione, live end-to-end: gesto di proposta con teletrasporto e ciclo dei candidati; badge specchio con debounce; stato pending visibile; nessun freeze dell'interazione durante i calcoli.
- Emissione: il commento di simmetria appare solo sui tagli confermati; round-trip esistente intatto (il commento non altera la semantica).
- Parte 5, su una U simmetrica: decomporre un rebbo (2 tagli + eventuale separazione), gesto sul gemello → sottoalbero replicato; i pezzi sono pezzi veri dell'originale (somma dei volumi = originale entro epsilon), le etichette behind/ahead corrispondono a quelle del gemello senza rimappature; un solo undo rimuove l'intero replay; la forma emessa valutata via SCI riproduce i pezzi.
- Test WASM con idioma skip esistente; citare nel report l'entry di `code-issues.md` sulla superficie non coperta in CI.
- Suite completa verde.

## Fuori scope

- **Ricostruzione mirror-based in emissione** (tenere metà e ricostruire l'altra con `mesh-mirror` invece di emettere entrambi i pezzi): è il payoff per converter/mesh-board, orizzonte dichiarato — qui solo il commento annotativo.
- Simmetrie rotazionali (N-fold cicliche) e congruenza tra pezzi: orizzonti, stessa famiglia di meccaniche.
- Il generatore traslazionale con ranking |ΔA| e la striscia di profilo: brief 5.
- Auto-proposta della simmetria all'apertura della sessione (calcolo speculativo non richiesto): il gesto è esplicito.

## Alternative considerate e scartate

- **PCA sui vertici nudi**: falsificata da B7 con misure (centroide deviato di 17.69 su tessellazione disomogenea) — la pesatura per area è obbligatoria, non un raffinamento.
- **Conferma mirror per-keystroke**: 77–148 ms fuori budget live; il gate gratuito + debounce dà lo stesso valore percepito senza jank.
- **Confronto delle tessellazioni riflesse** al posto della differenza simmetrica volumetrica: fragile per costruzione (le due metà possono tassellare diversamente anche su oggetti perfettamente simmetrici); il confronto volumetrico è robusto e già validato.
- **Sostituire i pezzi del gemello con copie specchiate di quelli decomposti** (invece di replicare i tagli): scartato qui — l'output non sarebbe più una partizione dell'originale (`mirror?` conferma entro epsilon, non esattamente) e la simmetrizzazione forzata è il payoff del converter, orizzonte dichiarato.
- **Linking vivo tra sottoalberi gemelli** (propagazione continua dei gesti): scartato — complessità di sincronizzazione per un beneficio marginale rispetto al replay esplicito e ripetibile.
- **Restituire da `symmetry-planes` anche i candidati non verificati** (con flag): scartato — il contratto "solo piani veri" è più semplice e il chiamante non deve ricordarsi di filtrare; i bocciati non servono a nessun caso concreto.
