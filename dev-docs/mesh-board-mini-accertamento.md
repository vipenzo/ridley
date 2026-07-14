# Mini-accertamento mesh-board — attach sull'albero e layer scaffold

Natura: **accertamento, nessuna modifica al codice di produzione.** Riferimento architetturale: `dev-docs/mesh-board-design.md` **v2** — leggerlo prima di partire: l'architettura è cambiata rispetto all'accertamento grande (il board è una famiglia di direttive di visualizzazione stile `stamp`, non una sessione modale; il confronto con fedeltà è già costato in Q5; l'accettazione è riscrittura del sorgente, fuori scope qui). Restano due sole incognite, entrambe piccole. Scratch effimeri, esiti annotati sotto le domande.

---

## Q1 — attach su un valore-albero

**Perché conta.** Il ri-posizionamento del board è `(mesh-board (attach t (f 10)))`: si trasforma il dato, non il display. Serve che `attach` (non-bang, funzionale) accetti il valore-albero — una collezione di mesh (la mappa piatta nome→mesh dell'accertamento grande, Q1) — applicando la trasformazione rigida di gruppo che `transform` già fa sui vettori (disposizioni relative preservate, replay dalla creation-pose condivisa delle foglie).

**Cosa misurare.**
- Come dispatcha `attach` oggi (mesh/panel/SDF): dove si aggancia il caso "collezione"? Il macchinario di `transform` su vettore è riusabile direttamente o va adattato per mappe?
- Sorte delle pose: dopo `(attach t (f 10))` tutte le foglie devono avere la pose avanzata coerentemente (il gruppo resta rigido, la pose condivisa resta condivisa) — verificare su prototipo REPL con un albero da 3 pezzi: volumi invariati, disposizioni relative invariate, pose traslate.
- Casi bordo: albero vuoto, albero a foglia singola; `attach` su un vettore oggi cosa fa (errore? va reso leggibile)?
- Stima della superficie (stile A3): quali funzioni si toccano, nessuna implementazione.

**Esito.**

*Correzione preliminare al presupposto.* `mesh-split-tree/emit` (`mesh_split_tree.cljs:384-405`) produce oggi un **vettore** letterale `[piece-1 piece-2 …]`, non la mappa proposta dall'accertamento grande (Q1, mai implementata). Quindi: sul vettore che l'emissione produce OGGI, `attach` funziona già correttamente (percorso di gruppo esistente, verificato sotto) — nessuna lacuna presente. La lacuna riguarda specificamente la mappa nome→mesh proposta, non ancora costruita da nessuno.

*Dispatch di `attach-impl`* (`editor/impl.cljs:1596-1608`) — un `cond` semplice, non multimethod: selection-map → `sdf-node?` → `sequential?` → `panel?` → `:else` (mesh singola). Una mappa `{:piece-1 m1 :piece-2 m2}` fallisce TUTTI i test (non ha `:name`, non ha `:op`, `(sequential? {})` è `false`, non ha `:type :panel`) e cade nel ramo `:else` — trattata come se fosse una singola mesh.

*Falsificazione misurata: oggi `attach` su una mappa è un no-op silenzioso, non un errore.* Costruito live un albero a 2 foglie (compound box+boss splittato), le foglie condividono `:creation-pose {:position [20 -10 0] …}` (conferma diretta di Q4 dell'accertamento grande). Misurato:
```
(attach {:piece-1 behind :piece-2 ahead} (f 10))
;; => {:piece-1 behind :piece-2 ahead}   ; identico, (= risultato originale) => true
```
Meccanismo: `mesh-attach-impl` → `turtle/attach` cerca `(:creation-pose target)` in cima alla mappa (non esiste — le chiavi sono `:piece-1`/`:piece-2`), quindi lo stato torna **invariato** (`:attached` resta `nil`); `replay-path-commands` muove la sola turtle virtuale (innocuo); il fallback finale `(or (get-in state [:attached :mesh]) mesh)` restituisce la mappa originale. Nessuna eccezione, nessun avviso.

*Il percorso vettore funziona ed è verificato dal vivo.* `group-attach-impl` (`impl.cljs:1560-1576`) prende `(:creation-pose (first meshes))` come riferimento, fa girare il path UNA volta su una turtle virtuale, poi applica UNA trasformazione rigida via `turtle/group-transform`/`attachment/transform-mesh-rigid` (già condivisa con `transform` su vettori — stesso helper, zero duplicazione di matematica) a vertici **e** `:creation-pose` di ogni mesh. Misurato su `[behind ahead]` reali con pose condivisa:
- entrambe le nuove `:creation-pose` = `[30 -10 0]` (shift di `(f 10)`), **ancora identiche fra loro** — "condivisa resta condivisa" regge;
- conteggi vertici invariati (8, 16);
- centroidi spostati esattamente di `[10 0 0]` ciascuno; distanza centroide-centroide **identica** prima/dopo (16.108…) — disposizione relativa preservata, gruppo genuinamente rigido.

*Nota sulla robustezza del meccanismo.* "Condivisa resta condivisa" è un **sottoprodotto dell'invariante** (tutte le foglie nascono con la stessa posa via `carry-meta`), non una gestione esplicita: il codice usa SEMPRE e SOLO la posa della prima mesh come perno. Se due mesh in un gruppo avessero pose realmente diverse, il codice girerebbe comunque (nessun errore) ma userebbe la posa della prima come perno per tutte — non pertinente al caso mesh-split (l'invariante regge sempre lì) ma da annotare come assunzione, non garanzia generale.

*Casi bordo, misurati dal vivo.* Vettore vuoto `(attach [] path)` → `[]`, nessun errore (posa di default sprecata ma innocua). Vettore a un elemento → vettore a un elemento in uscita, **mai spacchettato** in mesh nuda — precedente diretto: la forma del contenitore in ingresso si preserva in uscita; una mappa dovrebbe restituire una mappa (stesse chiavi), non spacchettare.

*Insidia concreta per un'implementazione naive.* `(first {...})` su una mappa CLJS restituisce una `MapEntry` (coppia `[chiave valore]`), non una mesh — confermato dal vivo (`(first tree-map)` → `[:piece-1 {...mesh...}]`). Riusare `group-attach-impl` senza adattarlo (serve `(vals target)`) romperebbe silenziosamente il riferimento di posa (tornerebbe `nil` → fallback all'origine mondo).

*Superficie stimata (stile A3).* **Piccola**, due punti di tocco:
1. `attach-impl`'s `cond` (`impl.cljs:1596-1608`): un nuovo ramo prima di `:else`, discriminando "mappa di mesh" da "mesh singola" con lo stesso criterio già in uso altrove nella stessa funzione (`(:vertices target)`, vedi `resolve-anchor-source` a `impl.cljs:1305`) — assenza di `:vertices` in cima + valori tutti mesh-shaped.
2. Una nuova funzione sorella di `group-attach-impl` (o una generalizzazione): `(vals target)` per ottenere le mesh, `attachment/group-transform` riusato invariato per la matematica rigida, `(zipmap (keys target) risultato)` per ricostruire la mappa in uscita con le stesse chiavi. La mappa vuota `{}` si comporta simmetricamente al vettore vuoto (`(vals {})` → `()`, stesso fallback di posa default).

Nessuna modifica necessaria a `transform`/`group-transform`/`transform-mesh-rigid` — riusabili invariati. La duplicazione già esistente del wrapper "posa di riferimento → replay → posa finale" (oggi 2 copie: `transform-mesh` con `run-path`, `group-attach-impl` con `replay-path-commands` più ricco) diventerebbe una terza copia quasi identica — un piccolo refactor di fattorizzazione è un'occasione, non un prerequisito.

## Q2 — Layer scaffold nel renderer

**Perché conta.** Gli scaffold del board sono mesh visibili nel viewport ma fuori dal registry e sbarrate alla CSG ("leggibili, non incorporabili"), con ciclo di vita per-valutazione come `stamp`. Serve sapere quanto esiste già.

**Punto di partenza dichiarato: l'inset appena consegnato.** Tre risposte parziali sono già nel codice di `ridley.viewport.inset`: la convenzione ghost validata live (wireframe = riferimento), il pattern di push del contenuto (`set-content!`), e la sufficienza dell'API pubblica di `viewport.core`. Partire da lì.

**Cosa misurare.**
- Ciclo di vita per-valutazione: come `stamp` registra e pulisce i suoi display a ogni run (lettura del codice)? Lo stesso meccanismo regge mesh 3D display-only nel viewport principale?
- Picking/misura sugli scaffold: `raycast-mesh-face` / `raycast-world-point` lavorano sulla scena o sul registry? Se sul registry, cosa serve per raycastare una mesh display-only (e SOLO in lettura — la misura sì, la selezione per CSG no)?
- Il toggle di viewport "boards on/off" (view state, non linguaggio): dove vive naturalmente (parente del reveal)? Stima.
- Stima complessiva della superficie del layer (stile A3), nessuna implementazione.

**Esito.**

*Ciclo di vita di `stamp`, tracciato per intero (nessuna implementazione, solo lettura).* Primitiva → `turtle-state :stamps` (`turtle/core.cljs:1074-1090`) → drenata a ogni comando implicito in `state/scene-accumulator` (`editor/state.cljs:113`, **azzerato a ogni eval** da `reset-scene-accumulator!`) → `repl/extract-render-data` (`repl.cljs:162-174`) → due pattern di push nei call site di `core.cljs`: eval completo/definizioni = **sostituzione** (`registry/set-stamps!` + `refresh-viewport!`), eval incrementale REPL = **append** (`registry/add-stamps!` + `refresh-viewport! false`) → atom **separato** `scene-stamps` in `scene/registry.cljs:28` (mai `scene-meshes`) → `refresh-viewport!` → `viewport/update-scene` → `update-stamps-display` (`viewport/core.cljs:1039-1062`): **remove + dispose espliciti** del gruppo THREE precedente, poi ricostruzione condizionale dai dati appena forniti. Un `THREE.Group` non è intercettato dallo sweep generico `clear-geometry` (che pulisce solo `LineSegments`/`Mesh` diretti) — per questo stamp ha bisogno di questo remove/dispose dedicato, non di un meccanismo condiviso.

*Non esiste una slot pronta da riusare.* Il percorso mesh-registry (`scene-meshes`) è esattamente ciò che uno scaffold deve evitare (l'appartenenza al registry rende una mesh nominata/pickabile/nella superficie di gizmo-snap). Il percorso linee/stamp è il precedente più vicino ma è **anch'esso una pipeline parallela fatta a mano** (atom proprio, funzione di clear/rebuild propria, toggle proprio) — nessuna astrazione "categoria di display per-eval" condivisa da cui ereditare gratis. Un nuovo layer scaffold richiederebbe lo stesso trittico copiato: nuovo atom `scene-scaffolds` + `set-!`/`add-!` in registry, nuova chiave in `update-scene`, nuovi atom `scaffolds-visible`/`scaffolds-object`/`current-scaffolds` + `update-scaffolds-display`/`dispose-scaffolds-object!` in `viewport/core.cljs`, modellati 1:1 su stamp (~25 righe).

*Scoperta rilevante non anticipata dal design doc: appartenenza al registry e ammissibilità alla CSG sono oggi ORTOGONALE.* `union`/`difference`/`intersection` (`manifold/core.cljs:411+`) operano su **valori** mesh-shaped passati come argomenti, filtrati da uno spec `:ridley/mesh` (`schema.cljs:27,48-54`) — non consultano MAI `scene-meshes` per nome. Tenere lo scaffold fuori dal registry ottiene gratis "non nominato/non pickabile", ma **non** impedisce di per sé che i dati grezzi di uno scaffold finiscano dentro una `union` se soddisfano lo spec mesh — l'affermazione del design doc "il riferimento non entra mai nell'output, per costruzione" ha bisogno di un meccanismo separato ed esplicito (es. lo scaffold non è mesh-shaped secondo lo spec, o è avvolto in un tipo che `coerce-to-meshes` rifiuta). Punto aperto per il brief, non risolto qui.

*Raycast: sulla scena, mai sul registry — verificato per entrambe le funzioni.* `raycast-world-point`/`raycast-point` (`viewport/core.cljs:1794-1818`) e `raycast-mesh-face` (`:2672-2716`) raycastano `(.intersectObjects raycaster (.-children world-group) true)` — oggetti THREE reali, mai una lookup nel registry. Differenza chiave: `raycast-world-point` **non filtra su `userData.registryName`** (qualunque `THREE.Mesh` sotto `world-group` è un hit valido — è il raycast dietro la misura shift+click); `raycast-mesh-face` **filtra esplicitamente** su `registryName` (dixit il suo stesso docstring: "gli hit-zone del gizmo non ne hanno, così vengono saltati"), e lo stesso filtro è duplicato inline nella pipeline di pick alt-click. **Conseguenza diretta e favorevole**: uno scaffold aggiunto come `THREE.Mesh`/`LineSegments` puro sotto `world-group` (esattamente come fa già `ridley.viewport.inset` o `show-preview!`, senza `registryName`) sarebbe misurabile via shift+click **gratis, zero modifiche**, e automaticamente **escluso** dal pick strutturale/gizmo-snap **gratis, zero modifiche** — "misurabile ma non incorporabile" per la parte di interazione è già la conseguenza naturale dell'architettura di raycast esistente, non richiede nuovo codice.

*Precedente per il toggle "boards on/off".* La famiglia di atom `defonce` di `viewport/core.cljs` (`grid-visible`/`axes-visible`/`lines-visible`/`normals-visible`/`stamps-visible`, righe 18-40) — vita-app, globali al viewport, agnostici dal tool — è il precedente corretto, non `:reveal-all?` di `edit-mesh-split` (una chiave dentro un `session` che nasce e muore col tool modale: `reset! session {...}` a ogni ingresso senza quella chiave, `reset! session nil` all'uscita — non sopravvive, e un `mesh-board` è una direttiva di linguaggio senza sessione modale a cui agganciarsi). **Inventario del cablaggio UI esistente**: tutti e 6 gli atom hanno già bottone in toolbar (`index.html:52-61`) + wiring in `core.cljs`'s `setup-save-load` (es. `toggle-stamps-btn`, righe 973-982) + sync dello stato attivo (classe `.active`) — nessuno è "solo interno". **Nessuno dei 6 ha una scorciatoia da tastiera** (grep negativo su `core.cljs` per binding tastiera legati a queste toggle) — una eventuale scorciatoia per "boards on/off" sarebbe cablaggio nuovo comunque, non un risparmio.

*L'inset come punto di partenza — le tre risposte parziali si confermano, con una differenza da segnalare.* La convenzione ghost-wireframe è davvero lo standard (anche `stamp`'s pipeline conferma indipendentemente lo stesso principio "display separato da CSG"). Il pattern "clear+rebuild da dati pushati" di `set-content!` è esattamente l'idioma nativo di `update-stamps-display` — due implementazioni indipendenti sono convergentemente arrivate alla stessa forma. **Differenza**: l'inset ha richiesto **zero nuovi export pubblici** da `viewport.core` perché è guidato da chiamate esplicite di sessione (`set-content!` invocato dal tool); uno scaffold `mesh-board` è invece una **direttiva che deve reagire a ogni rivalutazione dello script**, come stamp — quindi SERVE una nuova superficie pubblica (nuove `set-!`/`add-!` sul registry, nuova chiave in `update-scene`), non riusa quella dell'inset direttamente. Il precedente di inset regge per la *tecnica* (ghosting, clear+rebuild), non per l'*innesto* (che è quello di stamp, non quello dell'inset).

*Superficie stimata (stile A3).* **Piccola-media**:
- Registry: nuovo atom + `set-scaffolds!`/`add-scaffolds!` (~10 righe, mirror di `scene-stamps`).
- Viewport: nuovi atom + `update-scaffolds-display`/`dispose-scaffolds-object!` (~25 righe, mirror di stamp); materiale ghost-wireframe già pronto e riusabile (tecnica di `ridley.viewport.inset`, o la `create-wireframe-mesh` privata già esistente in `viewport/core.cljs`).
- Toggle: nuovo atom + bottone toolbar + wiring (~35 righe totali su 3 file, mirror di `stamps-visible`).
- Raycast/misura: **zero modifiche** — già scena-wide per la misura, già correttamente esclusivo per il pick strutturale.
- Ammissibilità CSG: **punto aperto, non gratis** — richiede una decisione esplicita (fuori scope qui, materia del brief).
- Costi del confronto con fedeltà: già misurati nell'accertamento grande (Q5) — non rimisurati.

---

## Sintesi per il brief

- **Q1 chiusa, superficie piccola.** Oggi `attach` su una mappa è un no-op silenzioso (falsificato dal vivo), non un errore — quindi il brief deve trattarla esplicitamente, non assumerla "già gestita". Il meccanismo di `group-attach-impl`/`group-transform` è riusabile invariato per la matematica; serve solo un nuovo ramo di dispatch + `(vals target)`/`(zipmap (keys target) …)`. La forma del contenitore si preserva (mappa dentro → mappa fuori, stesse chiavi), come già accade per i vettori (mai spacchettati, anche a un elemento).
- **Q2 chiusa, superficie piccola-media, un punto aperto per il brief.** Nessuna slot pronta: il layer scaffold è un trittico registry+viewport+toggle copiato dal pattern di `stamp`, non un'estensione di qualcosa che già esiste generico. Il raycast/misura è **già** corretto per costruzione (scena, non registry — misurabile ovunque, escluso dal pick strutturale senza modifiche). **Ma "non incorporabile in CSG" NON è gratis dalla sola esclusione dal registry** (CSG e registry sono ortogonali oggi) — il brief deve decidere il meccanismo esplicito (spec/tipo che `coerce-to-meshes` rifiuta, o simile).
- **L'inset (`ridley.viewport.inset`, appena consegnato) resta il precedente tecnico giusto** (ghosting, clear+rebuild) ma non quello d'innesto: uno scaffold reattivo a ogni rivalutazione dello script ha bisogno di una superficie pubblica nuova sul registry/viewport (come stamp), non di quella — zero-footprint — che l'inset ha usato.
- **Nessuna delle due incognite tocca ciò che l'accertamento grande ha già chiuso** (reificazione, posa d'ingresso, costi del confronto, canale scan) — confermato, non riaperto.

---

## Fuori scope

- Le direttive `mesh-board` stesse, il confronto con fedeltà, il pass-through, la riscrittura assistita: materia del brief, che si scrive sugli esiti di queste due domande.
- Tutto ciò che l'accertamento grande ha già chiuso (Q1 reificazione, Q4 posa d'ingresso, Q5 costi confronto, Q6 caduta, Q7–9 canale scan): acquisito, non rimisurare.
