# Accertamento mesh-board — le nove incognite della fase 3

Natura: **accertamento, nessuna modifica al codice di produzione.** Riferimento: `dev-docs/mesh-board-design.md` (architettura a due strumenti + dato condiviso, tre viste, workplace, canali d'ingresso). Le domande 1–6 riguardano il nucleo, le 7–9 il canale scan. Misure reali dove possibile, ispezione del codice dove la domanda è di superficie di refactor, proposta motivata dove la domanda è di forma. La falsificazione è un esito valido.

Nota preliminare: per le domande 7–9 serve una **scansione reale** (PLY o STL da app di scan/fotogrammetria — anche una scansione da telefono di un oggetto qualsiasi). Se non disponibile, usare una mesh sintetica con rumore gaussiano sui vertici + buchi artificiali, dichiarando il limite; ma il file vero è preferibile e Vincenzo può procurarlo.

> **Stato scansione reale:** nessun file `.ply`/`.obj`/`.stl` presente nel progetto. Q7 è stato misurato su mesh **sintetiche** (sfera con buchi artificiali + rumore gaussiano sui vertici), limite dichiarato in Q7. Le conclusioni qualitative (rumore vs buchi come gate) sono robuste; i numeri esatti di soglia vanno riconfermati su una scansione vera quando Vincenzo la procura. Q8/Q9 sono ispezione + proposta e non dipendono dal file.

---

## Q1 — Reificazione dell'albero: cosa restituisce il body del let

**Perché conta.** È l'interfaccia tra i due strumenti: il body della forma emessa (mai fissato finora) deve restituire la decomposizione come valore che `edit-mesh-board` prende in rassegna e che le viste leggono. Decide anche come il board localizza il binding da sostituire nel sorgente.

**Cosa misurare/esplorare.** Lettura del codice di emissione attuale: cosa serve per emettere un body strutturato (mappa nome→mesh piatta? con topologia dei rami per la vista processo?). Prototipo REPL: dalla forma emessa di una decomposizione a 3-4 pezzi, costruire a mano il valore candidato e verificare che le due viste possano derivarne i loro dati (conteggi aperti/chiusi, corrispondenza nome→geometria). Compatibilità col re-entry per-call esistente: la reificazione lo tocca? Proposta motivata della forma, con alternative.

**Esito.**

*Falsificazione preliminare.* Il body **non è oggi `…`**. `emit` (`src/ridley/editor/mesh_split_tree.cljs:384-405`) produce una **stringa** e chiude il let con un **vettore dei nomi delle foglie in ordine DFS pre-order**: `[piece-1 piece-2 piece-3]` (`:403`). Il «non specificato» del design è *semantico* (nessun consumatore a valle), non letterale. I nomi vengono da `name-map` (`:319-337`): ogni foglia nominata → `piece-N` in pre-order 1-based; la radice resta anonima (letterale `:source-expr`, es. `block`); gli *ahead intermedi* sono inlineati dentro il destructuring annidato `{… :behind … :ahead}` e non hanno nome; la simmetria compare solo come **commento** su una riga di taglio, mai come nome di binding.

*Il fatto strutturale che cambia la domanda.* La forma emessa è **una sola** `mesh-split` che produce **tutte** le foglie per destructuring — le foglie **non hanno un RHS proprio**, sono nomi di destructuring. Quindi «accettazione = sostituzione» (design §workplace) **non è un riempimento di slot**: non esiste un `piece-1 (…)` di cui riscrivere il valore. Sostituire una foglia richiede una **riscrittura strutturale del sorgente** — sollevare quella foglia fuori dal destructuring in un binding fratello `piece-1 (extrude …)` e toglierla dai marks della `mesh-split`. Questo salda Q1 e Q6 in **un solo meccanismo** (vedi Q6).

*Cosa serve davvero alle due viste e al board.*
- **Vista processo** (Q3): rami/foglie/conteggi/stato. La topologia dell'albero **non serve baked nel valore runtime** — è già ricostruibile in due modi: (1) dalla **forma del destructuring nel sorgente** (il design stesso dice «il sorgente È la struttura»), e (2) dal board che ri-deriva l'`mtree` esattamente come fa il re-entry di split (`build-reentry-tree`, `edit_mesh_split.cljs:1502`). Il modello `mtree` (`mesh_split_tree.cljs`) è già ricco: `pieces`, `log`, `leaf?` derivato, origini che codificano la parentela, `{:finished? :count}` per pezzo da `component-report`.
- **Corrispondenza nome→geometria**: il board riceve il valore a runtime e deve mapparlo ai nomi. Un vettore di mesh non porta i nomi; una **mappa `{:piece-1 mesh …}`** sì (il board è comunque un editor del sorgente, quindi può anche leggere i nomi dalle binding e indicizzare il vettore posizionalmente).

*Proposta (motivata).* Reificare il body come **mappa piatta nome→mesh delle foglie**: `{:piece-1 piece-1 :piece-2 piece-2 …}`. Motivi: (a) auto-descrittiva per un valore «ricevuto e messo in rassegna»; (b) topologia non necessaria nel valore (vive nel sorgente + ri-derivabile); (c) cambio minimo a `emit` (una riga, da `[…]` a `{…}`). Alternativa accettabile: lasciare il **vettore** e prendere i nomi dal sorgente — funziona ma è meno self-describing. Scartata: body strutturato ricco che duplica l'albero nel valore runtime (seconda verità, contro il principio del design).

*Compatibilità re-entry.* Nessun impatto: il re-entry legge il composito pre-valutato (catena lineare `{:behind :ahead}`) + i marks risolti contro `entry-pose` (`request!`, `edit_mesh_split.cljs:1567-1571`); non tocca il body. Se il board solleva foglie fuori dalla `mesh-split`, rientrare nella `mesh-split` residua funziona (meno marks).

## Q2 — Substrato del workplace: buffer di script con valutazione live

**Perché conta.** Il cuore del board: un buffer dove si scrive un mini-script Ridley (linguaggio pieno), valutato con un contesto di confronto (il pezzo-riferimento). Va capito quanto esiste già.

**Cosa misurare.** Ispezione: l'editor/REPL esistente (CodeMirror) può fare da buffer di sessione con eval isolata? Il substrato modale condiviso offre già l'aggancio per un pannello-editor? Cosa manca esattamente (stima di superficie, stile A3). Prova concreta: valutare oggi dal REPL uno script contro un pezzo splittato e sovrapporlo — riportare le frizioni del flusso manuale.

**Esito.**

*Substrato pronto (~80%).* Il modale condiviso (`src/ridley/editor/modal_evaluator.cljs`) offre già tutto lo scheletro di un nuovo tool: `claim!`/`release!` (mutex, editor read-only), `mount-panel!` (`:117`, inserisce un `<div>` sopra `#repl-input-line`, classe `modal-panel`), `install-keydown!` (cattura in fase capture), e soprattutto `reeval-script!` (`:278`) — la pipeline di eval live: azzera registry, resetta turtle/scene/print, valuta la stringa contro il ctx SCI condiviso, spinge lines/stamps e rinfresca la viewport. È esattamente il ciclo «scrivi→valuta→vedi» del workplace, già rodato dai tool `edit-*`.

*Due frizioni reali (misurate provando il flusso dal REPL).*
1. **Buffer secondario.** CodeMirror è un **singleton**: `create-editor` (`src/ridley/editor/codemirror.cljs:465`) fa `(reset! editor-instance view)` (`:551`) in un global (`:31`), e tutti gli helper 0-ari + la logica read-only del modale puntano al global. Ma ogni helper ha anche un'arità esplicita `[view …]` → un secondo buffer è **architetturalmente supportato**; il refactor è non sovrascrivere il global (un `create-editor*` che salta il `reset!`, o save/restore). Superficie: **piccola** (~mezza giornata).
2. **Contesto di confronto isolato.** Oggi l'eval è contro **un solo ctx SCI condiviso** (`repl.cljs:28`, `state/sci-ctx-ref`) con turtle/scene condivisi. Perché lo script possa (a) **riferire il pezzo per nome** (per misurare: `distance`/`section-area`/`bounds` sul riferimento) e (b) **non sporcare la scena principale**, serve o un ctx figlio con la foglia bindata a un nome (es. `ref`/`*piece*`), o l'iniezione di quel binding. `reeval-script!` già resetta la scena per-eval, quindi il meccanismo di isolamento esiste a metà; manca il **seeding del riferimento come binding**. Le funzioni di misura lavorano già su un valore-mesh, quindi bindare la foglia a un nome basta. Superficie: **piccola-media**.

*Frizione del flusso manuale (prova concreta REPL).* Ho costruito pezzi via `split-by-plane`, tenuto la foglia, costruito una mesh-script e confrontata (union/intersection/difference — cronometrata in Q5). Il flusso funziona end-to-end; ciò che il tool deve aggiungere è solo la plumbing: (1) ctx con la foglia bindata a un nome, (2) instradare il risultato dello script in un render-confronto invece che nella scena principale, (3) i tre modi di vista (sovrapposizione/intersezione/differenza) sopra le booleane.

*Stima complessiva (A3):* buffer secondario **S**, ctx seedato **S–M**, render-confronto a 3 modi **M**. Il nuovo davvero-nuovo è: de-singleton del buffer + ctx col riferimento + i tre modi di confronto. Substrato modale, eval e misure: **già esistenti**.

## Q3 — Vista processo: substrato per il diagramma-albero cliccabile

**Perché conta.** La vista "quanto manca" è anche navigazione (click su foglia = accesso diretto). Serve sapere con che substrato UI si costruisce nel pannello e quanto costa tenerla sincronizzata col dato.

**Cosa misurare.** Ispezione del pannello esistente (HTML? che infrastruttura?): un albero cliccabile con stati (aperto/chiuso/nativo) è a portata? Stima della superficie; eventuali librerie già in bundle riusabili.

**Esito.**

*Substrato.* UI **100% DOM a mano** (`createElement`/`innerHTML`/`addEventListener`) — nessun React/Reagent/hiccup (verificato su `deps.edn`/`shadow-cljs.edn`/`package.json`). **Nessun widget-albero esiste.** Ma il **modello dati di backing esiste già** ed è ricco: `mesh_split_tree.cljs` dà `leaf-ids` (DFS pre-order), la parentela via `:origin`, `{:finished? :count}` per nodo, `piece-name`, `position-info` (`:413`, ritorna `{:name :index :total :open? :count}` pensato proprio per il pannello). Non renderizza nulla.

*Precedenti riusabili (lift, non reinventare).*
- `src/ridley/library/panel.cljs`: helper DOM `el`/`el-with`/`append!`/`remove-children!` (`:37-51`), pattern **full-re-render da atom** a ogni cambio (`render!` `:309`), toggle `.collapsed` su header, drag-reorder manuale. Il toolkit-DOM più vicino a un builder riutilizzabile.
- `update-panel-display!` (`edit_mesh_split.cljs:1315`): tecnica di **stato-per-elemento via classi** (`.add`/`.remove` di `pilot-active-param`) — il modello per colorare i nodi (aperto/chiuso/nativo).
- `render-cut-strip!` + `strip-click!` (`edit_mesh_split.cljs:974`/`1015`): un **diagramma SVG con click-per-selezione** (mappa la x del click sul candidato più vicino) — il precedente d'interazione più vicino a «click su nodo = salto».

*Cosa esiste oggi come «albero renderizzato».* Non un diagramma nodi/archi, ma: **billboard labels nella viewport 3D** (`viewport/core.cljs:2503` `set-labels!`; `edit-mesh-split` `scene-labels` `:359`, una etichetta per foglia al centro bbox, solo in modo «reveal») + righe di testo nel pannello (`.ems-position`: «piece X (2/5) · aperto · 3 componenti»).

*Stima e proposta.* Widget-albero cliccabile = costruire una **lista annidata `<ul>/<li>`** (o un piccolo grafo SVG, riusando il precedente strip) da `mtree`, con click-per-nodo → `mtree/select`/focus. Dati **gratis**, widget **M** (~1-2 giorni di DOM). **Costo di sincronizzazione: trascurabile** — l'albero ha ~5-20 nodi, e il pattern full-re-render-da-atom già in uso rende un re-render completo a ogni `cut`/`separate`/`undo`/`select` banale (niente diffing). Raccomando la **lista DOM annidata** (stato-per-nodo più semplice via classi che in SVG), riusando gli helper di `library/panel` + la tecnica di `update-panel-display!`; SVG solo se si vuole un vero diagramma ramificato (riusando la mappatura hit di `strip-click!`).

## Q4 — Posa d'ingresso del mini-script

**Perché conta.** Il pezzo giace in coordinate mondo; la turtle dello script deve partire da un frame sensato o l'utente combatte con le coordinate invece di modellare.

**Cosa misurare.** Prova manuale sul mount decomposto: ricostruire *davvero* un pezzo via REPL (es. un gancio come extrude) provando i frame candidati — posa del taglio che delimita la foglia, creation-pose condivisa, bounds-center del pezzo — e riportare quale minimizza la frizione (quante trasformazioni servono per far combaciare). L'ergonomia si misura facendo, non congetturando.

**Esito.** *(misurato — prototipo REPL: compound box+boss a posa mondo `[50 30 20]`, tagliato in due a `z=27` per isolare il boss)*

*Falsificazione centrale.* `carry-meta` copia la creation-pose **dell'originale** su **ogni** foglia. Misurato: la parte intera, la foglia-boss e la foglia-piastra portano **tutte** la stessa `{:position [50 30 20], :heading [1 0 0], :up [0 0 1]}`. Quindi la «creation-pose condivisa» **non localizza il pezzo**: è il frame dell'intero oggetto, identico per tutte le foglie. Partendo di lì e scrivendo lo script ovvio (box centrato sul frame) → **fedeltà −0.74** (misurata: la geometria atterra all'origine dell'oggetto, a mezza piastra, e manca il boss).

*I tre candidati, misurati.*

| Frame candidato | Cosa vale (misurato) | Ricostruzione boss → fedeltà |
|---|---|---|
| creation-pose condivisa `[50 30 20]` | **uguale per tutte le foglie**, frame dell'intero. Orientamento coerente ma posizione sbagliata per-foglia | **−0.74** (script ovvio, nessun offset azzeccato) |
| bounds-center `[50 30 36.5]` | per-foglia, localizza **la posizione** ma **nessun orientamento** (bbox è world-axis) | **1.0** nel caso axis-aligned |
| **cut-pose** `[50 30 27]`, heading = normale `[0 0 1]` | posizione **sul piano di taglio**, heading = normale. Porta **posizione E orientamento** | **1.0** (un offset lungo heading = mezza altezza) |

*Verdetto.* **La cut-pose è il frame d'ingresso naturale.** È la faccia dove la foglia era attaccata; l'estrusione/costruzione lungo heading atterra sul pezzo, e — decisivo — porta l'**orientamento** (normale del taglio), che il bounds-center non ha e che la creation-pose condivisa sbaglia per-foglia. Sul pezzo *ruotato* la direzione di estrusione è corretta gratis solo con la cut-pose; col bounds-center modelli in assi mondo e combatti gli assi propri dell'oggetto. La cut-pose è già un **frame noto**: i marks risolti in `entry-pose` durante split/re-entry (`edit_mesh_split.cljs:1567-1571`).

*Sfumature (convenzione proposta).*
- Foglia delimitata da **più tagli** → più cut-pose candidate: **proponi-e-cicla** per l'utente (stesso modello di `y`/`:reflex`), default all'ultimo taglio.
- Foglia **radice** (mai tagliata su alcune facce) → fallback a **bounds-center** (localizza; l'orientamento lo dà l'utente o gli assi PCA/simmetria del pezzo).
- Riassunto: **cut-pose primaria**, **bounds-center fallback** per foglie non tagliate, **ciclo** quando più tagli delimitano la foglia.

## Q5 — Costo del confronto renderizzato

**Perché conta.** Overlay/intersezione/differenza sono booleane; decide se il confronto si aggiorna a ogni valutazione dello script (accettabile) o può essere live durante la digitazione.

**Cosa misurare.** Timing su pezzo realistico (il gancio del mount, ~qualche migliaio di tri) vs script tipico: differenza simmetrica completa (2 booleane + volumi) e mesh della differenza pronta per il render. Con Manifold vivo (keep-alive). Riportare i tempi e il verdetto live/on-eval/debounce.

**Esito.** *(misurato — Manifold 3.3.2 vivo, browser, medie su decine di run dopo warmup; due sfere confrontate)*

| Operazione | ~2.3k tri/pezzo | ~9.7k tri/pezzo |
|---|---:|---:|
| `mesh→manifold` (una conversione) | 2 ms | ~4 ms |
| singola `difference` (ridley→ridley) | 8 ms | — |
| singola `intersection` | 12 ms | — |
| singola `union` | 11 ms | — |
| **confronto pieno naive** (union+inter+2 diff+vol, tutto ridley→ridley) | **49 ms** | — |
| **keep-alive per-eval** (riferimento vivo, script convertito 1×, 2 mesh-diff renderizzate) | **29 ms** | **96 ms** |
| keep-alive floor (entrambi vivi, 4 booleane + vol, CPU pura) | 12 ms | 39 ms |
| **solo numero di fedeltà live** (union+inter+vol, keep-alive, nessuna mesh out) | **12–13 ms** | **39–44 ms** |
| `mirror-difference-ratio` (per riferimento) | — | 188 ms |

*Verdetto.*
- **On-eval (a ogni valutazione dello script): comodo** a entrambe le densità — il confronto pieno con differenza renderizzata è 29 ms (CAD) / 96 ms (scan). Nessun throttle necessario.
- **Live durante la digitazione:** il **solo numero di fedeltà** (2 booleane + volumi, nessuna mesh renderizzata) è 12–13 ms a densità CAD, ~40 ms a densità scan → **fattibile live con debounce leggero (~80–100 ms)**. La **differenza renderizzata** (2 booleane + conversione mesh in uscita) raddoppia il costo → tenerla on-eval o su debounce più lungo (150–200 ms), **non** per-keystroke a densità scan.
- **Keep-alive è essenziale** per il ramo live: convertire il riferimento **una volta** (precedente `split-live`/B2) dimezza il costo (49→29 ms).

*Raccomandazione.* Fedeltà % **live** (debounce ~80 ms, keep-alive del riferimento); deviazione **renderizzata on-eval** (Invio / re-eval esplicito). A densità CAD entrambe possono essere live; a densità scan (~10k+ tri) separarle.

## Q6 — Caduta dell'impalcatura

**Perché conta.** Quando un ramo è tutto nativo gli split di quel ramo devono poter cadere; quando cade l'ultimo, cade l'import. Serve la mappa di cosa si riscrive.

**Cosa misurare/esplorare.** Sulla forma emessa reale: quali riscritture servono (rimozione chiamate `mesh-split`, sostituzione binding, sorte delle pose come frame di assemblaggio); chi le fa (il board al commit? un gesto esplicito "consolida"?); stile A3, riportare i punti che cambiano senza implementare.

**Esito.** *(mappa; nessuna implementazione — salda con Q1)*

*Il fatto che governa tutto.* La forma emessa è **una sola** `mesh-split` che produce tutte le foglie per destructuring (Q1); non c'è un RHS per foglia. Quindi rendere nativa una foglia è una **riscrittura del sorgente**, non un riempimento di slot.

*Le riscritture, punto per punto.*
1. **Foglia accettata** → nuovo binding fratello `piece-N (…script…)` con la fedeltà a commento; il nome `piece-N` **rimosso dal destructuring** della `mesh-split` e dai suoi marks (e il `(mark :cut-N)` dal path se non più necessario ad altre foglie). Il body (mappa/vettore) mantiene il nome.
2. **Riferimento vivo finché gli split girano** (design) → la `mesh-split` **resta** finché il ramo non è del tutto consolidato, così il pezzo-dato è ancora disponibile per i confronti futuri.
3. **Ramo tutto nativo** → la chiamata `mesh-split` si **cancella**, i binding fratelli si promuovono; le **cut-pose** o cadono o **restano come letterali frame di assemblaggio** se una entry-pose nativa (Q4: cut-pose) le riferisce — allora la posa sopravvive come `(mark …)`/posa che alimenta la trasformazione del pezzo, non come taglio.
4. **Ultimo ramo nativo** → il binding radice `decode-mesh`/`import-stl` è morto → si cancella. Acquisizione completa.

*Chi le fa (proposta).* **Due modelli, combinati:**
- **Lift-on-accept** per la singola foglia: ogni accettazione è già un'edit del sorgente, quindi solleva la foglia al momento — il sorgente resta minimo a ogni passo («lo stato intermedio vive nel sorgente»).
- **Gesto esplicito «consolida ramo»** per collassare una `mesh-split` interamente nativa (cancellare la call, promuovere i fratelli): è una riscrittura più grande e rivedibile, che l'utente **innesca**, non un effetto collaterale silenzioso.

*Gap da segnalare (non bloccante).* Il re-entry localizza la call per **primo match testuale** di `(edit-mesh-split` (`find-form-bounds`, `modal_evaluator.cljs:228`). Una consolidazione che tocca **una** `mesh-split` specifica in un sorgente con **più** call ha bisogno di un localizzatore più preciso della «prima occorrenza». Da risolvere prima del brief caduta-impalcatura.

## Q7 — Repair: il gate del canale scan

**Perché conta.** Manifold esige mesh watertight/manifold; le scansioni spesso non lo sono. Senza una storia di repair (o un rifiuto leggibile), il canale scan non parte.

**Cosa misurare.** Sulla scansione reale: `import-stl`/`decode-mesh` la accetta? `mesh-diagnose` cosa riporta? Una CSG (`mesh-split` a metà) cosa fa — funziona, degrada, esplode? Il binding espone repair (Merge/fillHoles o equivalenti — ispezionare `.d.ts`)? Costo di un repair minimo. Criteri proposti per il rifiuto leggibile quando il repair non basta.

**Esito.** *(misurato su mesh **sintetiche** — limite dichiarato: nessuna scansione reale nel progetto. Le conclusioni qualitative reggono; le soglie numeriche vanno riconfermate su un file vero)*

*Import.* `import-stl`/`decode-mesh` (`library/stl.cljs`) accettano qualsiasi `{:vertices :faces}`; i parser **saldano** i vertici coincidenti (chiave-stringa). Una STL da scan importa bene geometricamente.

*Diagnosi.* `mesh-diagnose` (`geometry/mesh_utils.cljs:73`) riporta `open-edges`/`non-manifold-edges`/`degenerate-faces`/`is-watertight?`/`euler-characteristic` — puro CLJS, niente WASM, e il **genus** è derivabile da χ. Misurato: sfera sana → watertight, χ=2. Sfera **bucata** (rimosso ~5% delle facce) → **123 spigoli aperti**, χ=−39, non-watertight. Sfera **rumorosa** (jitter gaussiano sui vertici, topologia intatta) → watertight, χ=2. Rilevamento buono.

*Comportamento CSG — il risultato chiave.*
- **Rumore (topologia intatta): NON è il gate.** `mesh-split` funziona a densità grossolana **e fine** (~9.5k tri, densità scan), anche con rumore pesante (σ fino a scala-spigolo, 0.15→3.0 su raggio 10) — status `:ok`, volume ~corretto (4126 vs 4110). *Caveat:* lo `.status()` di Manifold **non rileva le auto-intersezioni** (assume input valido); `:ok` significa «costruito», non «pulito». Ma gli strumenti volumetrici/integrali (volume, `section-area`, differenza simmetrica) **integrano** sul rumore → degradazione graziosa, esattamente la tesi del design.
- **Buchi (spigoli aperti): È il gate.** La mesh bucata **non costruisce nemmeno un Manifold** (status `:failed-to-create`, volume 0); `split-by-plane` restituisce **nil in silenzio** — nessuna eccezione, nessun risultato. È la falsificazione del comportamento «esplode»: non esplode, fa un **no-op muto**.

*Repair disponibile.* Manifold 3.3.2 espone **solo** `MeshGL.merge()` (saldatura best-effort degli spigoli aperti entro tolleranza; ritorna bool; **inutilizzato** in Ridley). **Nessun `fillHoles`** in questa versione. Il lato Rust non fa repair di mesh. I repair propri di Ridley — `merge-vertices` (weld), `solidify` (self-union) — **non riempiono buchi**. Misurato: `merge-vertices` sulla mesh bucata lascia **123 spigoli aperti**, status ancora `:failed-to-create`. Il weld ripara le **coppie di vertici doppi** (il problema classico dei vertici sdoppiati di STL/scan), **non** le facce mancanti.

*Il gate, a due livelli.*
1. **Vertici doppi / non saldati** (comune nei raw scan/STL) → riparabile ora (`merge-vertices`, o cablare `MeshGL.merge()`). Costo basso.
2. **Buchi veri / non-manifold** → **non riparabile** con gli strumenti attuali. Serve o un algoritmo di hole-filling aggiunto, o `MeshGL.merge()` cablato + tolleranza, o un repair esterno a monte (MeshLab/Blender).

*Criterio di rifiuto leggibile (proposto).* All'import: `mesh-diagnose` → un passo di weld; se restano `open-edges>0` o `non-manifold-edges>0`, **rifiutare con i conteggi specifici** («scansione non chiusa: 123 spigoli aperti, 0 non-manifold — riparare a monte o abilitare il riempimento buchi») e **disabilitare i gesti CSG** (`mesh-split`, `decompose`) con quella ragione, stile addendum 3; i tool volumetrici read-only restano attivi. **Il pezzo mancante è la guardia**: mettere `mesh-diagnose`/`manifold?` (già presenti) **davanti** al percorso CSG — oggi la CSG fa no-op muto su una mesh bucata, senza avviso.

## Q8 — Formati: PLY e OBJ

**Perché conta.** PLY è la lingua franca degli scanner, OBJ della fotogrammetria; oggi c'è solo STL.

**Cosa misurare.** Struttura dell'import attuale (quanto è STL-specifico?); costo di un parser PLY (binario e ascii) e OBJ (solo geometria, niente materiali) — stima di superficie; eventuali parser JS già in bundle o a portata di dipendenza leggera.

**Esito.**

*Struttura import.* Il parser STL (`library/stl.cljs`, binario+ascii, auto-detect via euristica `84+50n`) è l'**unico** parser. Tutto ciò che è a valle consuma la mappa piatta `{:vertices :faces}` → **format-agnostico**. Aggiungere PLY/OBJ = (a) un parser che ritorni `{:vertices :faces}`, (b) un rilevatore di formato, (c) **3 punti di cablaggio**: la tabella dei binding, il codegen di menu-import (`generate-library-source`), l'importatore desktop path-based. Nessun cambiamento a valle (CSG/split/render già neutrali).

*Il cambiamento di conto: loader già nel bundle.* **three.js è già dipendenza** (`"three": "^0.160.0"`) e porta `node_modules/three/examples/jsm/loaders/OBJLoader.js` e `PLYLoader.js` — che parsano OBJ e PLY (ascii **e** binario, con endianness) in `BufferGeometry`. Riusandoli, l'header/binary parsing è **fatto**; resta solo l'**adattatore** `BufferGeometry → {:vertices :faces}` + weld. Questo abbatte il costo.

*Stima.*
- **Riuso three loaders (raccomandato):** adattatore ~piccolo per entrambi (position+index → tuple, weld) + rilevatore + 3 cablaggi. **S** per OBJ, **S–M** per PLY (verificare il caricamento ESM di un modulo `examples/jsm` nell'app browser).
- **Hand-rolled (coerente con l'ethos):** OBJ **S** (~mezza giornata: `v`/`f`, indici 1-based, primo campo di `v/vt/vn`, ignora materiali); PLY **M** (1-2 giorni: header + binario little/big-endian + ascii).
- Generalizzare due comodità oggi STL-only: il **weld** dei vertici (tenere — utile a ogni formato, aiuta gli scan) e l'euristica metri/`scale-warning`.

*Raccomandazione.* Provare prima il **riuso dei loader three** (già in bundle, PLY binario/ascii risolto); fallback hand-rolled se il caricamento ESM di `examples/jsm` dà attrito. Ordine: **OBJ** (più economico, fotogrammetria), poi **PLY** (lingua franca scanner). Entrambi entrano nel dispatch esistente senza toccare la pipeline a valle.

## Q9 — Calibrazione di scala 3D

**Perché conta.** La fotogrammetria è scale-free: serve il gesto "questo spigolo è 50 mm" → fattore di scala, erede del righello di `edit-image-board`.

**Cosa misurare/esplorare.** Cosa esiste per misurare sul riferimento (picking di due punti/vertici, `distance`): il gesto è componibile con primitive attuali? Come funziona il righello 2D (lettura del codice) e cosa si riusa dell'ergonomia. Proposta del gesto con stima.

**Esito.**

*Il righello 2D (antenato).* `edit_image_board.cljs`: i due estremi del righello sono in **frazioni-immagine scale-independent** (`{:a [.25 .5] :b [.75 .5]}`, `:495`) così un rescale non li muove; si **trascinano** le due ✕ su una feature; si digita la lunghezza reale; «set scale» → `scale = expected / ruler-norm-dist` (`apply-expected!`, `:247`), `live-reeval`. Bottone **esplicito**, mai a metà-drag.

*Primitive 3D — tutte presenti* (`viewport/core.cljs`): `raycast-world-point` (snap sulla geometria), `raycast-mesh-face` (`:2672`, ritorna i **3 vertici + centro** del triangolo colpito → snap-vertice/centro-faccia gratis), `raycast-plane-point`. Il **righello 3D built-in** `on-viewport-shift-click` (`:1820`): due shift-click → distanza euclidea 3D → `add-ruler!` — template pronto per il gesto di picking. Misura: `distance` (`measure/core.cljs:128`), `bounds` (`:161`), `section-area`, `face-at`. Applicazione: `scale-mesh`/`mesh-scale` (`attachment.cljs:115`, DSL `mesh-scale`) scala di un fattore pivotando sulla creation-pose.

*Componibilità: alta.* Il gesto è una **ricomposizione diretta** del righello 2D su picking 3D: (1) prendi due punti 3D (`raycast-world-point`, o `raycast-mesh-face` per snap-vertice); (2) misura euclidea (`math/magnitude`, o `measure/distance`); (3) `scale = expected / measured` — **la formula 2D invariata**; (4) applica `mesh-scale`. Il flusso two-shift-click è il template pronto.

*L'unica decisione da ri-prendere.* Il 2D ancorava gli estremi in spazio scale-independent (frazioni-immagine) così il rescale non li spostava. Il 3D deve ancorare gli estremi ai **vertici scelti** (i 3 vertici da `raycast-mesh-face`) o a **frazioni dei bounds** del pezzo, così ri-applicare la scala lascia stabile lo spigolo di calibrazione. Tenere **verbatim** il bottone «set scale» esplicito (ricalcolo on-demand, mai a metà-drag).

*Stima.* **S–M** — ogni primitiva esiste; è un tool modale che caccia due pick + un campo numerico + `mesh-scale` (~1-2 giorni). Erede diretto del righello 2D. Nota semantica: la mesh da fotogrammetria è scale-free; questo fissa la scala d'import una volta, e `mesh-scale` pivota sulla creation-pose — adatto a un import fresco.

---

## Fuori scope

- Qualsiasi implementazione: viste, workplace, board, repair, formati — tutto materia dei brief, il cui ordine (viste per prime) è nel design doc.
- Fotogrammetria in-app (foto→mesh): fuori per confine dichiarato — Ridley inizia alla mesh.
- Auto-fitting di primitive sul riferimento: l'occhio riconosce, la macchina misura; nessuna feature di riconoscimento automatico in vista.

---

## Sintesi per i brief

- **Q1+Q6 sono un solo meccanismo.** La forma emessa è una `mesh-split` unica con foglie per destructuring; «sostituire una foglia» è una **riscrittura del sorgente** (sollevare la foglia in un binding fratello + toglierla dai marks), non un riempimento di slot. Body reificato = **mappa piatta nome→mesh**; topologia dal sorgente + ri-derivabile via `mtree`.
- **Q4 falsifica la creation-pose condivisa** (identica per tutte le foglie → posizione sbagliata per-foglia, fedeltà −0.74). **Cut-pose** è il frame d'ingresso (posizione+orientamento, già noto dai marks); bounds-center fallback per le foglie non tagliate; ciclo se più tagli.
- **Q5:** on-eval comodo (29/96 ms); fedeltà % live fattibile con debounce ~80 ms + keep-alive (12–13/40 ms); differenza renderizzata on-eval.
- **Q7 falsifica «il rumore è il problema»:** il rumore passa (anche pesante, densità scan); **i buchi** sono il gate (Manifold non costruisce, CSG no-op muto). Nessun hole-filling disponibile; serve guardia `mesh-diagnose` davanti alla CSG + rifiuto leggibile.
- **Q2/Q3:** substrato modale + eval pronti; nuovo = buffer CodeMirror de-singleton + ctx col riferimento bindato + tre modi di confronto (Q2), lista-albero DOM da `mtree` (Q3).
- **Q8:** three.js già in bundle porta `OBJLoader`/`PLYLoader` → costo import crolla a un adattatore. **Q9:** gesto componibile al 100% da primitive esistenti, erede diretto del righello 2D.
