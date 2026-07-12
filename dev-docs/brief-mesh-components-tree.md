# Brief: mesh-components + sessioni ad albero — decomposizione oltre la ghigliottina

## Contesto

Terzo brief della famiglia "acquisizione STL". Il primo uso reale di `edit-mesh-split` (mount multiboard) ha mostrato che il modello lineare non basta: dopo pochi tagli sia `:behind` sia `:ahead` sono concavi. L'accertamento fase 2 (`dev-docs/mesh-split-phase2-accertamento.md`, 11 esiti, tutti misurati) ha diagnosticato le cause e falsificato un'ipotesi:

- **A1**: `decompose()` esiste nel binding, è topologico e gratis (<1 ms fino a 12800 tri), restituisce componenti valide. I pezzi multi-componente (la U tagliata alla base → due rebbi in una mesh) sono "concavi per sempre" per hull-ratio, ma la loro separazione non richiede alcun piano.
- **A4 (falsificazione)**: il "rosso perenne" NON è un problema di epsilon — i fillet/chamfer esterni restano convessi (0.9993–1.0), i rossi osservati sono concavità vere (0.55–0.78) che nessuna soglia salva. Il rimedio è un **criterio di finitezza diverso**: finito = ogni componente connessa è convessa. La metà a U coi due rebbi convessi è *già finita*; la separazione è un fatto di emissione, non di geometria.
- **A2**: il let-a-catena di più `mesh-split` nello stesso scope regge (risoluzione pura, turtle read-only, verificato per lettura e empiricamente). Trappola nota: mai confrontare mesh con `=` sulla map intera (i typed array confrontano per identità) — usare `:vertices`/`:faces` o volumi.
- **A3**: il refactor stack→albero è delimitato a ~7 funzioni (modello-stato, emissione, re-entry); il layer gesti e il rendering sono invariati. Nodo centrale: `:remaining` singolo → collezione di pezzi aperti + selettore; e `:accepted` che oggi fa tre lavori (stack undo, sorgente emissione, derivazione remaining) va sdoppiato in struttura-pezzi vs log-gesti.

Dipendenza trasversale dall'accertamento (B2): l'unico costo reale del ricalcolo live è la conversione mesh→manifold (~8 ms); questo brief introduce il **keep-alive del Manifold per pezzo aperto**, che paga subito il semaforo e prepara i generatori (brief successivi).

Questo brief consegna quattro parti, pensate per due tranche committabili: (1) `mesh-components` DSL, (2) il criterio di finitezza per-componente, (3) la sessione ad albero, (4) l'emissione ad albero con re-entry. Le decisioni di design sono state discusse e approvate da Vincenzo; le alternative scartate sono in fondo.

## Lavoro richiesto

### Parte 1 — mesh-components (wrapper + DSL)

Wrapper di `decompose()` in `manifold/core.cljs` col marshalling esistente e disciplina di `.delete` sugli intermedi.

`(mesh-components mesh)` → **vettore di mesh, in ordine deterministico: volume decrescente, tie-break sul centroide (confronto lessicografico x,y,z).** L'ordine è un contratto, non un dettaglio: l'emissione destruttura posizionalmente (`(let [[a b] (mesh-components x)] …)`) e l'ordine di `decompose()` di Manifold è implementation-defined — l'ordinamento vive nel DSL e il docstring lo dichiara. Senza questo, un re-entry può scambiare i pezzi in silenzio.

Casi: mesh a una componente → vettore di uno (totalità); mesh vuota → vettore vuoto, documentato. Metadati: ogni componente eredita secondo la policy carry-meta esistente.

### Parte 2 — Criterio di finitezza per-componente

Il criterio di "finito" del semaforo cambia, per le metà live e per i pezzi aperti: **verde = ogni componente connessa è convessa** (`decompose` + `convex?` per componente; entrambi nel budget live: <1 ms + ~2.6 ms/componente). Rosso = almeno una componente genuinamente concava. Nessun cambio a `convex?` né al suo epsilon: A4 ha stabilito che la soglia non è la leva.

Badge nel pannello: "N componenti" per il pezzo corrente e per le metà live quando N > 1, ricalcolato senza throttle.

### Parte 3 — Sessione ad albero

**Modello di stato: struttura-pezzi separata dal log-gesti.** Il log è cronologico e contiene i gesti strutturali (taglio accettato, separazione componenti); l'undo è pop dell'ultimo gesto strutturale, qualunque ramo abbia toccato — un'unica semantica, come nel modello lineare. La struttura ad albero dei pezzi (aperti, finiti, corrente) è derivata dal log o cached, mai una seconda verità. I cambi di selezione NON entrano nel log: selezionare non è un gesto strutturale e non si annulla.

**Pezzi aperti multipli + selettore.** `:remaining` singolo diventa una collezione di pezzi aperti. Il pezzo corrente si seleziona con click sul pezzo (gli handle mouse esistono, addendum) e con un tasto che cicla i non-finiti (keymap di famiglia). Il piano di taglio, il semaforo e i generatori futuri operano sul pezzo corrente.

**Accept su un taglio**: apre behind e ahead come nodi; quelli già finiti (criterio Parte 2) sono foglie e si ghostano con etichetta; il pezzo corrente dopo l'accept resta `ahead` se non finito (continuità con la ghigliottina), altrimenti passa al prossimo non-finito.

**Gesto "separa componenti"** sul pezzo corrente (tasto in keymap di famiglia): materializza le componenti come pezzi distinti nell'albero (ordine della Parte 1), ciascuno col suo stato. È un gesto strutturale: entra nel log, si annulla con undo.

**Keep-alive del Manifold.** Ogni pezzo aperto mantiene vivo il suo oggetto Manifold; conversione una volta per pezzo, `.delete` garantito su undo che lo elimina, su cancel/Esc (tutti) e a fine sessione. Il ricalcolo per keystroke smette di pagare gli 8 ms di conversione. Il report deve confermare l'assenza di leak sul percorso cancel.

**Uscita**: invariata nella semantica (Enter accetta `:behind`, chiusura quando l'ultimo pezzo aperto si chiude o via Ctrl-Enter zucchero); la condizione "tutto verde" ora significa "tutti i pezzi dell'albero finiti".

### Parte 4 — Emissione ad albero e re-entry

**La forma emessa è un let a catena di composite lineari auto-contenute.** Ogni chiamata `mesh-split` emessa ha il **suo** path con i **suoi** mark (nomi path-scoped, niente collisioni tra path) e il suo marks-vector; i delta canonici di ogni path partono dalla posa d'ingresso della sessione (A2: tutte le chiamate nello stesso scope risolvono dalla stessa posa). La struttura ad albero vive nella topologia del let — quale binding alimenta quale chiamata — e le separazioni appaiono come destrutturazioni di `mesh-components`:

```clojure
(let [{p1 :behind resto :ahead} (mesh-split m (path …(mark :cut-1)) [:cut-1])
      [rebbo-a rebbo-b]         (mesh-components resto)
      {p2 :behind p3 :ahead}    (mesh-split rebbo-a (path …(mark :cut-1)) [:cut-1])]
  …)
```

**Conseguenza voluta: il re-entry non richiede alcun meccanismo nuovo.** Ogni chiamata emessa è una composita lineare esattamente come oggi: si ri-apre cambiando quella singola `mesh-split` in `edit-mesh-split`, col macro esistente invariato (la forcella A3#6 si dissolve: né passare N composite, né re-derivare — la decomposizione per-call la elimina). L'undo in un re-entry copre solo quel ramo, documentato. Aggiungere tagli a un pezzo già foglia = avvolgere il suo binding in una nuova chiamata.

**Nomi**: i pezzi emessi hanno nomi stabili generati dall'albero (depth-first, `piece-1…piece-N`, componenti con suffisso o nome proprio); le etichette in scena (addendum, Parte C) mostrano gli stessi nomi dell'emissione, inclusi quelli preservati in re-entry.

## Verifica

- `mesh-components`: U → 2 componenti valide; determinismo dell'ordine (stessa mesh in run ripetute e con ordine di costruzione permutato → stesso vettore, verificato su volumi/centroidi); una componente → `[mesh]`; mesh vuota → `[]`; carry-meta testato.
- Criterio di finitezza: metà a U (due rebbi convessi, una mesh) → verde con badge "2 componenti"; componente singola genuinamente concava (piastra+gancio da A4) → rosso. Test che salda il criterio ai casi misurati dall'accertamento.
- Sessione ad albero, live end-to-end su un sintetico tipo mount (piastra + 2 ganci): tagli su rami diversi con cambio di pezzo corrente (click e tasto), una separazione componenti, undo attraverso i rami (cronologico), commit. Forma emessa valutata via SCI → `split-parts` su ciascuna composita e somma volumi = volume originale entro epsilon.
- Re-entry: cambiare UNA chiamata del let emesso in `edit-mesh-split`, modificare un taglio, commit → forma coerente, il resto del let intatto, nessuna collisione di nomi.
- Keep-alive: budget per keystroke misurato prima/dopo (attesi ~8 ms in meno); nessun leak su Esc (tutti i Manifold liberati, verificabile con contatore o ispezione).
- Trappola A2 rispettata nei test: confronti su `:vertices`/`:faces`/volumi, mai `=` sulla map intera.
- I test WASM seguono l'idioma skip esistente; citare nel report l'entry di `code-issues.md` sulla superficie non coperta in CI.
- **Prova d'accettazione finale, su desktop**: il mount multiboard reale di Vincenzo via `import-stl`, decomposto fino a tutto verde. Riportare numero di gesti, uso di "separa componenti", frizioni residue — e i numeri A4/B3/B4 riconfermati sul pezzo vero (caveat dichiarato dell'accertamento).
- Suite completa verde; le due tranche (Parti 1–2, Parti 3–4) committate e verdi separatamente.

## Fuori scope

- Generatori di candidati (traslazionale col ranking |ΔA|, rotazionale, spigoli riflessi) e striscia di profilo: brief 4, sugli esiti B1–B5.
- Simmetria/mirror (B6–B7, PCA pesata per area): brief 5.
- Rilevazione di congruenza tra pezzi; VHACD; converter `stl->convex-sdf`; mesh-board: orizzonti invariati.
- Multi-selezione di pezzi; undo della selezione; viste esplose.

## Alternative considerate e scartate

- **Stato "quasi-verde" / epsilon più permissivo** per il rosso perenne: falsificato da A4 con misure — i rossi reali (0.55–0.78) sono fuori portata di qualunque soglia sensata, e la banda 0.985–0.995 che reagirebbe è arguibilmente giusto che resti rossa. Il criterio per-componente è il rimedio che i dati indicano.
- **Un solo path condiviso con tutti i mark, più chiamate sullo stesso path**: era l'ipotesi dell'accertamento (A2 la valida tecnicamente), scartata in emissione perché rompe il re-entry per singola chiamata e riapre la forcella del macro (A3#6). I path auto-contenuti per chiamata risolvono il re-entry senza toccare il macro, al costo di qualche riga emessa in più.
- **Re-entry per replay dell'intera forma let** (proposta intermedia in discussione): superato dalla decomposizione per-call — nessun parsing della forma, nessun meccanismo nuovo.
- **Passare N composite al macro** (A3#6, opzione a): non necessario per lo stesso motivo.
- **L'ordine di `decompose()` come contratto**: è implementation-defined nel motore; il determinismo vive nell'ordinamento del DSL (volume decrescente + tie-break centroide).
- **Undo che naviga l'albero (per-ramo)**: scartato — il log cronologico dei gesti strutturali è un'unica semantica onesta, coerente col modello lineare; la selezione non entra nel log perché selezionare non modifica nulla.
