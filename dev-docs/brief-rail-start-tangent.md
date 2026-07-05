# Brief — Invariante rail-start: misura analitica per i bezier e vincolo in edit-path 3D

## Contesto

L'invariante rail-start (`validate-rail-start-frame!`, `src/ridley/turtle/extrusion.cljs` ~270) impone che un path consumato come rail da extrude/loft inizi nel frame della tartaruga: né rotazione né roll prima del primo `:f`. È chiamato in tre punti di consumo: `extrude-from-path` (~1697), `extrude-with-holes-from-path` (~1551), e loft (`src/ridley/turtle/loft.cljs` ~585). Il guard ha già un carve-out per gli archi: il mezzo passo iniziale taggato `:arc-cap :lead` è escluso dal check e il primo anello viene stampato con lo stato pre-rotazione (`start-cap-state`), perpendicolare all'heading in ingresso.

L'invariante è corretto ma ha esposto due difetti distinti, uno nella misura del guard e uno nell'editor.

**Difetto 1 — falso positivo su bezier analiticamente tangenti.** `rec-bezier-to*` (`src/ridley/editor/macros.cljs` ~311) tessella la curva in passi `f` preceduti da rotazioni `th`/`tv` taggate `:smooth`. La rotazione prima del primo `:f` porta l'heading verso la **prima corda** della tessellazione, non verso la tangente analitica. Su una curva con curvatura forte vicino all'inizio, la corda devia dalla tangente anche quando la curva parte perfettamente tangente (c1 esattamente lungo l'heading). Caso di riproduzione (dal modello di Vincenzo, 2026-07-04):

```clojure
(def P (edit-path (bezier-to [0 -8.24 73.42] [0 0 24.63] [0 -28.37 59.61] :local) (mark :PB) (bezier-to [0 17.54 41.14] [0 0 5.75] [0 10.56 41.23] :local) (mark :PA) (bezier-to [0 43.95 64.43] [0 0 7.8] [0 43.95 55.6] :local)))
(register T (extrude (circle 5) P))
```

Il primo bezier ha c1 = `[0 0 24.63]` `:local` — componenti right e up nulle, puro heading: tangente analitica esatta. Eppure la prima corda devia ~3.8° a resolution 16 e ~2.6° a 24 step, sopra la tolleranza di 1° (`rail-start-frame-tol-deg`): il guard scatta. La firma è risoluzione-dipendente (shrinks all'aumentare degli step): il guard sta misurando un artefatto di tessellazione, non il difetto che deve fermare. Il commento nel codice ("a tangent-drawn bezier reads ~0.2°") vale solo per curve dolci.

**Difetto 2 — l'editor 3D produce rail invalidi con normalissime interazioni.** Due vie:

1. Il c1 del primo segmento bezier è deliberatamente libero: `reconstrain-handles-3d` (`src/ridley/editor/edit_path.cljs` ~760) salta i=1 con la motivazione "node 0 is the anchor — no incoming tangent". Ma il tangente in ingresso esiste: è l'heading del frame di consumo, `[1 0 0]` nello spazio editor (seed di `walk-3d-segments`). Trascinare quel handle fuori asse produce un rail che vira davvero al primo passo.
2. Un primo segmento **dritto** che non giace sul raggio +X baka come `(set-heading …)(f …)` (`nodes->commands-3d`): rotazione prima del primo `:f`, bocciata dal guard. Quindi oggi quasi ogni rail 3D disegnato liberamente nell'editor è invalido.

**Alternative considerate e rigettate** (documentate per non riaprire la decisione):

- *Bake compensativo* — far emettere a edit-path la rotazione di puntamento fuori dal path: non viabile, il marker `(edit-path …)` sostituisce una singola espressione e non può iniettare comandi turtle a monte; una rotazione dentro il path è esattamente ciò che l'invariante vieta.
- *Rilassare l'invariante per set-heading/th iniziali scritti a mano* — reintrodurrebbe il disallineamento stamp-vs-sweep e la saldatura non-flush in catena che il guard è nato per fermare.
- *Aumentare la tolleranza del guard* — sposterebbe solo la soglia di risoluzione a cui il falso positivo si presenta; la misura resta sbagliata.

L'ordine tra le due parti è vincolato: il fix editor da solo non basta, perché un primo bezier con handle perfettamente bloccato sull'asse verrebbe comunque bocciato a bassa risoluzione (è esattamente il caso di riproduzione). Prima la parte 1, poi la 2 sopra.

## Lavoro richiesto

### Parte 1 — misura analitica del guard (`:bez-cap :lead`)

1. In `rec-bezier-to*`: calcolare il veer analitico della curva, cioè l'angolo in gradi tra lo start heading del recorder e la direzione c1−p0 (usare il c1 effettivamente risolto, che la funzione già calcola per tutte le arità: esplicito, quadratico cp=c1, auto — nei casi auto il veer è 0 per costruzione). Taggare le rotazioni emesse **prima del primo `f`** della curva (al più una `th` e una `tv`) con `:bez-cap :lead` e riporre il veer nel tag (es. `:veer-deg`). L'angolo è pose-invariante, quindi calcolabile a record time e valido a qualunque posa di consumo. Stessa cosa in `rec-bezier-to-anchor*` se condivide il percorso di emissione.
2. In `validate-rail-start-frame!`: le rotazioni taggate `:bez-cap :lead` vengono escluse dalla riduzione di frame (come già le `:arc-cap :lead`), e al loro posto si confronta il `:veer-deg` taggato con `rail-start-frame-tol-deg`. Se supera, si lancia lo **stesso** messaggio "begin with a turn" attuale (la parentesi finale sul caso tangente resta vera e ora anche fedele al comportamento). Un `th`/`tv` scritto a mano prima della curva resta nella riduzione e continua a bocciare. Il controllo di roll non è toccato (il lead di un bezier emette solo th/tv, mai tr).
3. Carve-out del cap nei tre punti di consumo: `start-cap-state` deve escludere l'intera finestra di lead taggata (oggi esclude solo l'ultima rotazione se `:arc-cap :lead`; va esteso a una coda taggata `:bez-cap :lead`, che può essere lunga 2). I tre siti hanno oggi lo stesso pattern duplicato: estrarre un helper condiviso in `extrusion.cljs` (es. `split-leading-cap`, che dato `state` e `initial-rotations` restituisce lo stato per il cap e le rotazioni effettive) e usarlo in `extrude-from-path`, `extrude-with-holes-from-path` e nel loft (che già accede via `extrusion/`). L'astrazione è giustificata: tre casi concreti già esistenti.

Argomento di correttezza da verificare col trap test (vedi Verifica): escludere il lead del bezier dal guard lascia il primo anello stampato perpendicolare all'heading in ingresso mentre lo spine avanza lungo la prima corda, leggermente deviata. È lo stesso compromesso già accettato per gli archi: la deviazione shrinks con la risoluzione ed è un artefatto di discretizzazione, non un disallineamento rigido. Il trap: a risoluzione molto bassa (es. `:steps 4`) su una curva tangente ma molto piegata, verificare che la mesh resti watertight e senza fold al primo anello.

### Parte 2 — l'editor 3D possiede l'invariante

4. `reconstrain-handles-3d`: aggiungere il caso i=1 con h = `[1 0 0]` (l'heading del rail frame). Il nodo 0 è sempre smooth: nessuna opzione cusp sull'ancora (annotato in Fuori scope; si aggiungerà se emergerà un caso concreto non-rail). Il flusso di snap esiste già: `reconstrain!` (~1121) è chiamato dopo ogni drag, quindi il vincolo si applica senza nuovo plumbing.
5. Rendering: il c1 del primo bezier passa da handle libero (cyan) a length-only (`handle-len-color`, teal) — coerente con la semantica già stabilita "direction locked by smoothness". Adeguare la scelta colore dove oggi discrimina su `(:smooth? (nth nodes (dec i)))` per il caso i=1.
6. Primo segmento dritto fuori asse: quando il nodo 1 di un segmento dritto viene posato o trascinato fuori dal raggio +X oltre la tolleranza, l'editor **auto-promuove** il segmento a nodo bezier tangente — c1 lungo +X con lunghezza ragionevole (es. ⅓ della corda), c2 con belly di default modesto. Il rail parte sempre dritto e poi curva: è l'unica geometria che un rail può esprimere, e ciò che l'editor mostra coincide con ciò che il bake produce. La stessa promozione va applicata in `seed->nodes-3d` all'apertura di un seed che oggi violerebbe l'invariante (es. `th` iniziale scritto a mano, foldato nei waypoint): il riallineamento all'apertura è coerente con come `reconstrain-handles` già scatta sui nodi smooth.
7. Affordance: disegnare al nodo 0 una freccetta corta lungo +X, così la direzione obbligata di partenza è visibile prima che l'utente ci sbatta contro.

L'accettazione complessiva della parte 2 è una proprietà: **nessun rail prodotto dall'editor 3D può essere bocciato da `validate-rail-start-frame!`**.

## Verifica

- Rete prima del codice: i test della parte 1 vanno scritti prima del fix e devono fallire sul codice attuale.
- Lo script di riproduzione (sopra) builda senza errori dopo la parte 1.
- Indipendenza dalla risoluzione: un rail il cui primo bezier ha c1 esattamente lungo l'heading e curvatura forte (usare il primo bezier dello script) passa il guard sia a resolution 8 che a 64.
- Il guard non è indebolito: un primo bezier con c1 a 10° fuori asse viene bocciato; un `(th 30)` scritto a mano prima del primo `f` viene bocciato; un `(tr 30)` iniziale viene bocciato come twist. Tutti e tre con i messaggi attuali.
- Comportamento arc-started invariato: test esistenti su `:arc-cap` verdi.
- Cap flush: per un rail che parte con bezier tangente, la normale del primo anello coincide con l'heading in ingresso (entro tolleranza numerica), a qualunque risoluzione.
- Trap test (punto 3): curva tangente molto piegata a `:steps 4` → mesh watertight, nessun fold al primo anello (mesh-diagnose pulito).
- Parte 2: drag del primo handle → c1 snappa sull'asse; drag del nodo 1 di un primo segmento dritto fuori asse → il segmento diventa bezier tangente; apertura di un seed con `th` iniziale → riallineato. Se praticabile, test sul bake: per un campione di configurazioni di nodi sintetiche, `nodes->commands-3d` seguito da `validate-rail-start-frame!` non lancia mai.
- Suite completa: 0 failures.

## Fuori scope

- Editor 2D: `nodes->commands` emette un `th` iniziale (`corr0`) quando il nodo 0 ha un heading; se un path-2d così bakato viene usato come rail, il guard scatta. Caso raro (i path 2D fanno quasi sempre da profilo); annotare in `dev-docs/code-issues.md` e trattare a parte.
- Opzione cusp sul nodo 0 (partenza non tangente per consumatori non-rail come text-on-path): differita finché non emerge un caso concreto.
- Qualsiasi rilassamento dell'invariante per rotazioni iniziali scritte a mano (`th`/`tv`/`tr`/`set-heading`).
- L'adattamento del builder di `extrude-with-holes-from-path` al sizing direzionale (accertamento separato già tracciato in `dev-docs/extrude-holed-accertamento.md`): qui si tocca solo il suo carve-out del cap, identico agli altri due siti.
