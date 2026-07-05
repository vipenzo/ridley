# Brief — Frame canonico per i bezier: up d'uscita indipendente dalla resolution

## Contesto

**Sintomo riportato** (2026-07-05, verificato sperimentalmente): la fine dell'estrusione di un rail con bezier concatenati non coincide con l'ultimo nodo mostrato da edit-path, e cambiando la resolution del modello l'estrusione finisce in posti diversi mentre i nodi dell'editor restano fermi. Caso di riproduzione:

```clojure
(def P (edit-path (bezier-to [-5.09 -38.75 56.4] [0 0 6.87] [-2.54 -35.2 35.82] :local) (bezier-to [-7.07 39.4 -5.43] [0 0 19.92] [-3.82 22.48 16.76] :local) (mark :PB) (bezier-to [25.2 -34.78 8.01] [0 0 9.07] [23.39 -30.35 -22.96] :local) (mark :PA) (bezier-to [-57.02 54.85 29.17] [0 0 30.86] [-49.48 44.49 34.88] :local)))
(register T (extrude (circle 5) P))
```

**Meccanismo.** Chi attraversa un bezier propaga l'up in tre modi diversi nel codebase:

1. Turtle runtime — `bezier-walk` (`src/ridley/turtle/core.cljs` ~730): parallel transport per corda (`bezier/parallel-transport-up`), a step = resolution.
2. Recorder — `rec-bezier-to*` (`src/ridley/editor/macros.cljs`): decomposizione Euler th-poi-tv per corda (`rec-compute-rotation-angles`), a step = resolution.
3. Editor — `bezier-frame-3d` (`src/ridley/editor/edit_path.cljs` ~707): projection-RMF per corda, a **24 step fissi** (usata da `walk-3d-segments` ~750 e dal path 2D ~477).

Le tre leggi convergono tutte al frame di Bishop nel limite continuo, ma a step finiti danno up d'uscita diversi — e in due casi su tre lo step count è agganciato alla resolution. L'heading d'uscita è analitico ovunque (∝ p3−c2) e coincide: a divergere è **l'up**, cioè un roll del frame attorno all'heading condiviso.

Il sintomo dell'editor nasce così: `request!` in modalità 3D restituisce allo script un re-bake (`{:type :path :commands (nodes->commands-3d nodes)}`) anche per un seed non toccato; il bake esprime end/c1/c2 di ogni bezier successivo in coordinate `:local` nel frame dell'editor (RMF a 24 step), e l'eval le interpreta nel frame del recorder (Euler th/tv a resolution step). Il roll di differenza sposta lateralmente ogni endpoint a valle, e l'errore si compone lungo la catena: massimo alla fine, esattamente dove il mismatch è stato osservato.

Ma il difetto è più largo del confine editor↔eval: **anche un path scritto a mano ha oggi un up post-bezier che dipende dalla resolution**, e con lui il roll dei segmenti successivi, la posa dei `mark` piazzati dopo una curva, e l'orientamento delle sezioni estruse. L'editor rende il difetto visibile perché confronta due walk diversi fianco a fianco; la classe esiste anche senza editor.

**Alternative considerate e rigettate:**

- *Bake auto-descrittivo* — emettere un `set-heading` esplicito dopo ogni `bezier-to` nel bake dell'editor, così l'eval legge il frame invece di ricalcolarlo: cura il sintomo al confine editor↔eval ma lascia resolution-dipendenti i path scritti a mano, le pose dei mark e il roll a valle. Cura il sintomo, non la classe.
- *Allineare solo gli step count* (24 → resolution nell'editor): resterebbe la differenza di legge (projection-RMF vs Euler th/tv vs parallel transport) e soprattutto resterebbe la dipendenza dalla resolution, che è il difetto di fondo.
- *Correzione di roll concentrata alla fine* (un `tr` di aggiustamento dopo la curva verso un up target): l'ultimo anello dell'estrusione riceverebbe un twist brusco e i frame intermedi resterebbero dipendenti dalla tessellazione.

## Lavoro richiesto

Il principio: il frame lungo un bezier diventa un **campo canonico**, funzione dei soli quattro punti di controllo e dell'up d'ingresso — mai della tessellazione. Ogni consumatore campiona quel campo ai parametri della propria tessellazione; la resolution torna a influenzare solo la levigatezza della geometria, non il frame.

1. Funzione condivisa in `src/ridley/turtle/bezier.cljs` (il posto della matematica pura): dato `[p0 c1 c2 p3 entry-up]` e una sequenza di parametri `ts`, restituisce heading/up canonici a ciascun t. Implementazione: double-reflection RMF (Wang et al., il metodo standard, ordine 4) su una griglia fine fissa (es. N=64), con lettura ai `ts` richiesti. Deterministica per costruzione. Replicare i fallback per le degenerazioni già gestiti oggi (c2≈p3, corda quasi nulla, up quasi parallelo alla tangente).
2. `bezier-walk` (`core.cljs`): heading/up per step campionati dal campo canonico invece del parallel transport per corda. Le posizioni non cambiano (i chord move restano quelli); l'exit up diventa il campo a t=1, l'exit heading resta l'analitico.
3. `rec-bezier-to*`, `rec-bezier-as*`, `rec-bezier-to-anchor*` (`macros.cljs`): frames per step dal campo canonico. L'emissione resta th/tv taggati `:smooth` come oggi, più un `tr` taggato `:smooth` dove il roll canonico non è raggiungibile con soli th/tv. Per le curve **planari** il roll canonico è zero → nessun `tr` emesso → la proiezione 2D di `path-to-shape` resta intatta (è il vincolo per cui il recorder usa rotazioni relative). Verificare che i consumer trattino un `tr :smooth` come transizione liscia: `corner-rotation?` esclude già `:smooth`, `apply-rotation-to-state` gestisce già `:tr` — controllare che non ci siano altri punti che assumono che le finestre `:smooth` contengano solo th/tv.
4. Editor: `bezier-frame-3d` delega alla funzione canonica; rimozione del 24 hardcoded in tutti i punti d'uso (`walk-3d-segments` ~750, il walk 2D ~477, e i campionamenti nel render ~1333/~1350 se derivano frame e non solo punti).
5. Il tag `:pure` e i `:bez-cap :lead` del brief precedente non cambiano semantica; verificare solo che il `:veer-deg` analitico resti calcolato com'è (dipende da c1−p0 e dall'heading d'ingresso, non dall'up).
6. Reference pinnate: le mesh con curve 3D si assesteranno sul roll canonico; aggiornamenti deliberati, uno per uno, con motivazione nel commit — non rigenerazione in blocco.

## Verifica

- Rete prima del codice: i test nuovi vanno scritti prima del fix e devono fallire sul codice attuale.
- Script di riproduzione: fine della mesh coincidente con la posizione dell'ultimo nodo dell'editor a resolution 4, 16 e 64.
- Property: exit up del turtle dopo un `bezier-to` 3D identico (tolleranza stretta, es. 1e-6 sull'angolo) tra resolution 4 e 128; stesso test per il recorder (up dello stato recorder a fine curva).
- Coerenza tra le tre leggi: turtle runtime, recorder ed editor riportano lo stesso exit up sulla stessa curva (erano tre valori diversi; devono diventare uno).
- Round-trip editor: bake → eval → `seed->nodes-3d` → posizioni dei nodi stabili entro tolleranza, su un path a più bezier concatenati ad alta curvatura.
- Posa di un `mark` piazzato dopo una curva 3D: invariante alla resolution.
- Planare: il bake di un bezier dentro `path-2d` non emette alcun `tr` ed è byte-identico a oggi; test 2D esistenti verdi.
- Trap test: curva 3D che twista molto (tipo elica) a resolution 4 — il roll tra anelli consecutivi dell'estrusione resta graduale (nessun twist concentrato all'ultimo anello), mesh watertight, mesh-diagnose pulito.
- Guard rail-start: i test del brief precedente restano verdi senza modifiche.
- Suite completa: 0 failures; pinned aggiornate singolarmente con motivazione.

## Fuori scope

- La nota di Code in `dev-docs/code-issues.md` sul valore pre-confirm non consumabile da extrude/loft: adiacente (stessa closure `live` in `request!`) ma difetto distinto — resta lì.
- L'emissione `corr0` dell'editor 2D (già annotata come fuori scope nel brief rail-start).
- Gli archi (`arc-h`/`arc-v`): il loro frame d'uscita è determinato analiticamente dalla geometria del cerchio; se un accertamento mostrasse una dipendenza analoga dalla tessellazione, è un lavoro separato.
- Qualunque cambiamento all'API utente di `bezier-to`/`bezier-as` (firme, flag, default).
