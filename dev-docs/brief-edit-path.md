# Brief — `edit-path`: editing interattivo di un path a segmenti

## Contesto

Quarto modal evaluator (dopo tweak, pilot, edit-bezier), da costruire **sopra il layer `editor/modal_evaluator`** — non clonare pilot né edit-bezier. È la seconda "sessione di tracing" della Roadmap §2.2: dove `edit-bezier` autora una singola cubica, `edit-path` edita un **path a segmenti** (sequenza di comandi tartaruga) regolandone a occhio lunghezze e angoli.

Il caso d'uso che lo motiva: un path usato come **control-polygon** per `bezier-as :control` (già implementato — vedi `Spec.md`, *Control-polygon mode*). Lo script definisce `(def ps (edit-path …))` e a valle fa `(bezier-as ps :control true)` per ottenere la curva liscia. Mentre l'utente trascina i segmenti del poligono, il live-reeval rivaluta lo script e **la curva a valle si rimodella in diretta**. Sostituisce il loop manuale "edita i numeri → rilancia → misura col righello" con l'editing interattivo. Il righello/midpoint resta utile per i vincoli: `(ruler [0 a] (mid ps 1))`.

Il path NON deve contenere curve per questo scopo: la curvatura la mette `bezier-as :control`, quindi il poligono è fatto di segmenti dritti e rotazioni. Gli arc restano supportati per i path usati direttamente (estrusi/loft così come sono), ma in un control-polygon non si usano.

API esistente rilevante: `path` (macro, registra comandi in `{:cmd :args}`), `bezier-as … :control` (`core.cljs/compute-midpoint-walk`), `points-to-path`/regenerazione di forme, `ruler`/`distance`/`mid`/`seg-mid` (`measure/core.cljs`). Il riorientamento `:shape` esiste già in `edit_bezier.cljs` (`pt->world` shape-seed).

Riferimenti: `Roadmap.md` §2.2; `Architecture.md` §11.2.3–11.2.4 (ingresso a due fasi, geometria effimera); `dev-docs/brief-edit-bezier.md` (pattern gemello); `Spec.md` *Path* e *Bezier along path*.

## Forma d'uso e modello di riscrittura

```clojure
(def ps (edit-path (f 30) (th 45) (f 25) (th 45) (f 30)))
;; alla conferma →
(def ps (path (f 30) (th 45) (f 25) (th 45) (f 30)))
```

`(edit-path …)` è uno stand-in per `(path …)`: durante l'eval **costruisce e restituisce il path** dai comandi correnti (così `bezier-as :control` e tutto il resto dello script girano), e apre la sessione modal. Alla conferma il marker `(edit-path …)` viene riscritto in `(path <comandi editati>)` — una pura `cm/replace-range`, con la forma rigenerata dalla lista di elementi in sessione (come `edit-bezier` rigenera la sua call). Round-trip: riavvolgere `(path …)` → `(edit-path …)` riproduce posizioni identiche.

Flag iniziali in testa al body, come edit-bezier: `:shape` (alias `:as-shape-seed`) e `:wireframe`.

Stato di sessione minimo: la **lista ordinata di comandi** `[{:cmd :args} …]`, l'indice selezionato, il **tipo-da-inserire** corrente, gli step (lunghezza e angolo), P0 (posa al call site, per il preview), più i campi condivisi (panel, key-handler, entered?).

## Lavoro richiesto

1. Nuovo modal evaluator `edit-path` sopra `editor/modal_evaluator`. Eredita stato/mutex/pannello/keyhandler, ingresso a due fasi (`request!`/`enter!`), commit con `cm/replace-range` + `run-definitions-fn`, skip-flag su cancel/preview.

2. Macro `edit-path`: parsa i flag iniziali (`:shape`/`:wireframe`), passa i comandi e i flag a `edit-path-request!`. Durante l'eval costruisce il path dai comandi (per il resto dello script) e apre la sessione (solo in modalità definizioni; da REPL passa attraverso restituendo il path, senza claimare il mutex — come edit-bezier).

3. Keymap (keyboard-first):
   - **←/→** : sposta la selezione fra gli elementi, **clamp** agli estremi (niente wrap);
   - **↑/↓** : cambia il valore primario del selezionato (distanza per `f`/`u`, angolo per `th`/`tv`/`tr`/arc); **shift+↑/↓** : secondo parametro (raggio/angolo degli arc);
   - **↑/↓ su un `mark`** : non c'è valore numerico — apre invece un **campo nome inline** (sotto-modalità di text-entry: i tasti vanno al campo, Invio conferma, Esc annulla il rename). Lo stesso campo si usa all'inserimento di un mark;
   - **Tab** : cicla il **tipo-da-inserire** (`f th tv tr arc-h arc-v mark`; `u` opzionale), mostrato nel pannello — NON muta l'elemento selezionato;
   - **Ins** : inserisce un nuovo elemento (tipo predisposto, valore di default) *prima* del selezionato; **Shift+Ins** : *dopo*; la selezione passa al nuovo elemento;
   - **Canc** : rimuove il selezionato (cambio-tipo = Canc + Ins);
   - **digit** : imposta lo step (contestuale: lo step di lunghezza per `f`/`u`/raggio, lo step d'angolo per `th`/`tv`/`tr`/angolo arc — sulla falsariga di pilot che tiene step e angle-step distinti);
   - **Invio** conferma, **Esc** annulla.

4. Geometria effimera nel viewport: **solo il control-polygon** — i segmenti (linee fra i vertici), i vertici (marker), l'elemento selezionato evidenziato (per un `f` il segmento, per una rotazione il vertice/giunto, per un `mark` il punto). I `mark` si disegnano come **punti etichettati** (col nome) alla loro posizione. Riorientata da `:shape` riusando `pt->world` di edit-bezier. **Nessuna curva di preview**: ciò che usa il path a valle (`bezier-as :control`, `stroke-shape`, …) si aggiorna via live-reeval dello script col marker sostituito.

5. Editing strutturale sulla lista di comandi in sessione: Tab/Ins/Shift+Ins/Canc come sopra. Default all'inserimento: tipo iniziale `f`; `f`/`u` = step di lunghezza corrente; `th`/`tv`/`tr` = `45`; arc = raggio `step`, angolo `90`; `mark` = nome `:m1` (primo libero) e apertura immediata del campo nome.

5b. **Elementi non gestiti = pass-through opaco.** La lista di sessione sono le **forme sorgente** del body, non i comandi decomposti. Le forme gestite (`f u th tv tr arc-h arc-v mark`) sono editabili; ogni altra forma (`bezier-to`/`bezier-as`, `move-to`, `look-at`, `follow`, `side-trip`, `scale`, …) resta **opaca**: viene disegnata (la geometria del path completo si ottiene valutando tutte le forme con la macchina `path` reale), è selezionabile e **cancellabile**, ma ↑/↓ non fanno nulla e il pannello la segna come non-editabile. Alla conferma è **preservata verbatim**. Così `edit-path` è sicuro su qualsiasi path: editi i segmenti che puoi, il resto sopravvive intatto. (Nessun errore, nessuna perdita silenziosa.) Nota implementativa: il rendering usa il path valutato per i waypoint; la selezione mappa la forma *i* alla sua porzione di geometria — per un `f`/rotazione è 1:1, per una forma opaca con più punti (es. `bezier-to` decomposto, `follow`) si evidenzia l'intero tratto.

6. Conferma: riscrive `(edit-path …)` → `(path <comandi>)` (range del marker interno, regenerazione testuale con spacing curato) e rilancia le definizioni. Cancel: sorgente invariato, skip-flag armato, rilancio per ripristinare il viewport.

7. Live-reeval: a ogni nudge/insert/delete, rivaluta una copia dello script col marker sostituito da `(path <comandi correnti>)` (debounced, salvo `:wireframe`), poi ridisegna il poligono sopra. `arm-skip?` false (il marker è già sostituito, niente re-entry).

## Verifica

- `(def ps (edit-path (f 30) (th 45) (f 25) (th 45) (f 30)))` apre il pannello; il poligono si vede nel viewport, il primo elemento selezionato.
- ←/→ scorrono gli elementi (clamp); ↑/↓ cambiano lunghezza/angolo; shift+↑/↓ il secondo parametro di un arc.
- Tab cambia il tipo mostrato in "Inserisci: …"; Ins/Shift+Ins creano l'elemento prima/dopo e ci spostano la selezione; Canc lo rimuove.
- Su un `mark` selezionato ↑ apre il campo nome; digitando lo si rinomina, Invio conferma; all'inserimento di un `mark` il campo si apre con un nome di default.
- Un path con forme non gestite (es. `(edit-path (f 30) (bezier-to [10 0 0]) (mark :x))`) si apre senza errore: la `bezier-to` è disegnata e preservata, ma non editabile con le frecce; conferma → la `(path …)` la contiene verbatim.
- Con `bezier-as ps :control` a valle, la curva si rimodella in diretta a ogni modifica.
- `:shape` riorienta il poligono nel piano della sezione estrusa.
- La conferma produce un `(path …)` valido; round-trip riaprendolo come `(edit-path …)`.
- Mutex: non si apre mentre tweak/pilot/edit-bezier sono attivi.

## Fuori scope

- **Cambio-tipo in place** di un elemento: si fa Canc + Ins (deliberato — evita la conversione distanza↔angolo).
- **Editing del valore** di forme non gestite (`bezier-*`, `move-to`, `look-at`, `follow`, `side-trip`, `scale`, …): sono opache (pass-through, vedi 5b) — disegnate, cancellabili, preservate, ma non tunabili con le frecce. Editabili = `f u th tv tr arc-h arc-v mark`.
- Drag col mouse / manipolazione diretta del viewport (fattorizzazione trasversale a sé, vedi nota in `brief-edit-bezier.md`).
- `edit-path` dentro un loop o funzione richiamata più volte nello stesso eval (limite "una volta per eval" ereditato dal pattern).

## Stima

A layer condiviso esistente, la logica specifica è dell'ordine di edit-bezier. Le parti nuove dominanti: il rendering del poligono **selezionabile** (evidenziazione dell'elemento corrente, mapping comando→geometria) e l'**editing strutturale** della lista (Tab tipo-da-inserire, Ins/Shift+Ins/Canc, rigenerazione della forma). Stato, mutex, pannello, keyhandler, due fasi, commit e live-reeval vengono dal layer.
